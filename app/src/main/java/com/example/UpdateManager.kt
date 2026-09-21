package com.example

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String = "",
    val forceUpdate: Boolean = false
)

enum class UpdateCheckStatus {
    IDLE,
    CHECKING,
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    DOWNLOADING,
    READY_TO_INSTALL,
    ERROR
}

class UpdateManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _status = MutableStateFlow(UpdateCheckStatus.IDLE)
    val status: StateFlow<UpdateCheckStatus> = _status.asStateFlow()

    private val _updateInfo = MutableStateFlow<AppUpdateInfo?>(null)
    val updateInfo: StateFlow<AppUpdateInfo?> = _updateInfo.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private var downloadedApkFile: File? = null
    private var downloadId: Long = -1L

    /**
     * Current installed version code
     */
    fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    fun getCurrentVersionName(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    /**
     * Check server for updates
     */
    suspend fun checkForUpdates(serverBaseUrl: String): Boolean = withContext(Dispatchers.IO) {
        _status.value = UpdateCheckStatus.CHECKING
        _errorMessage.value = null

        val cleanBase = serverBaseUrl.trimEnd('/')
        val endpoint = "$cleanBase/api/version"

        try {
            val request = Request.Builder()
                .url(endpoint)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                _status.value = UpdateCheckStatus.ERROR
                _errorMessage.value = "HTTP ${response.code}: Failed to query version endpoint"
                return@withContext false
            }

            val responseBody = response.body?.string() ?: ""
            val json = JSONObject(responseBody)

            val remoteCode = json.optInt("versionCode", 1)
            val remoteName = json.optString("versionName", "1.0")
            var apkUrl = json.optString("apkUrl", "")
            if (apkUrl.startsWith("/")) {
                apkUrl = "$cleanBase$apkUrl"
            }
            val changelog = json.optString("changelog", "")
            val force = json.optBoolean("forceUpdate", false)

            val currentCode = getCurrentVersionCode()
            val info = AppUpdateInfo(
                versionCode = remoteCode,
                versionName = remoteName,
                apkUrl = apkUrl,
                changelog = changelog,
                forceUpdate = force
            )
            _updateInfo.value = info

            if (remoteCode > currentCode) {
                _status.value = UpdateCheckStatus.UPDATE_AVAILABLE
                return@withContext true
            } else {
                _status.value = UpdateCheckStatus.UP_TO_DATE
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e("UpdateManager", "Error checking for updates from $endpoint", e)
            _status.value = UpdateCheckStatus.ERROR
            _errorMessage.value = e.localizedMessage ?: "Network error checking update"
            return@withContext false
        }
    }

    /**
     * Trigger APK Download using robust Coroutine Direct Download into App-specific Cache/Files
     * This avoids DownloadManager permission or path issues completely.
     */
    fun startDownload(apkUrl: String) {
        scope.launch {
            _status.value = UpdateCheckStatus.DOWNLOADING
            _downloadProgress.value = 5
            _errorMessage.value = null

            val success = withContext(Dispatchers.IO) {
                downloadDirectly(apkUrl)
            }

            if (success && downloadedApkFile != null && downloadedApkFile!!.exists()) {
                _status.value = UpdateCheckStatus.READY_TO_INSTALL
                _downloadProgress.value = 100
                Toast.makeText(context, "تم تحميل التحديث بنجاح. جاري فتح التثبيت...", Toast.LENGTH_SHORT).show()
                installApk(downloadedApkFile)
            } else {
                _status.value = UpdateCheckStatus.ERROR
                if (_errorMessage.value == null) {
                    _errorMessage.value = "فشل تحميل ملف التحديث، يرجى التحقق من الاتصال."
                }
            }
        }
    }

    private fun downloadDirectly(apkUrl: String): Boolean {
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        try {
            val fileName = "watchroom_v${_updateInfo.value?.versionName ?: "latest"}.apk"
            // Use getExternalFilesDir or cacheDir (both covered by FileProvider)
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            val destination = File(downloadDir, fileName)
            if (destination.exists()) {
                destination.delete()
            }

            val request = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", "WatchRoom-Android/${getCurrentVersionName()}")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                _errorMessage.value = "فشل الاتصال بسيرفر التحديث: HTTP ${response.code}"
                return false
            }

            val body = response.body ?: return false
            val contentLength = body.contentLength()
            inputStream = body.byteStream()
            outputStream = FileOutputStream(destination)

            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            var totalRead = 0L
            var lastProgressReport = 0

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (contentLength > 0) {
                    val progress = ((totalRead * 100) / contentLength).toInt()
                    if (progress - lastProgressReport >= 2) {
                        lastProgressReport = progress
                        _downloadProgress.value = progress.coerceIn(5, 99)
                    }
                }
            }
            outputStream.flush()

            if (destination.length() < 1000) {
                _errorMessage.value = "ملف التحديث غير مكتمل (${destination.length()} بايت)"
                destination.delete()
                return false
            }

            // Ensure destination is readable
            destination.setReadable(true, false)
            downloadedApkFile = destination
            return true
        } catch (e: Exception) {
            Log.e("UpdateManager", "Direct download failed", e)
            _errorMessage.value = "خطأ أثناء التحميل: ${e.localizedMessage}"
            return false
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Launch Package Installer for downloaded APK
     */
    fun installApk(file: File? = downloadedApkFile) {
        val targetFile = file ?: downloadedApkFile
        if (targetFile == null || !targetFile.exists()) {
            _errorMessage.value = "ملف التثبيت (APK) غير موجود، يرجى إعادة التحميل"
            Toast.makeText(context, "ملف التثبيت غير موجود", Toast.LENGTH_LONG).show()
            return
        }

        // On Android 8.0+ (API 26+), check if app can install unknown apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                    context,
                    "يرجى السماح للتطبيق بتثبيت التطبيقات من هذا المصدر",
                    Toast.LENGTH_LONG
                ).show()
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e("UpdateManager", "Failed to open install permission settings", e)
                }
                return
            }
        }

        try {
            val authority = "${context.packageName}.provider"
            val contentUri = FileProvider.getUriForFile(
                context,
                authority,
                targetFile
            )

            Log.i("UpdateManager", "Launching installer with URI: $contentUri from $targetFile")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            // Grant temporary read permission explicitly to potential package installers
            val resInfoList = context.packageManager.queryIntentActivities(
                intent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
            for (resolveInfo in resInfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                context.grantUriPermission(
                    packageName,
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("UpdateManager", "Failed to launch installer", e)
            _errorMessage.value = "تعذر فتح مثبت الحزم: ${e.localizedMessage}"
            Toast.makeText(context, "تعذر فتح مثبت الحزم: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    fun dismiss() {
        _status.value = UpdateCheckStatus.IDLE
        _errorMessage.value = null
    }
}
