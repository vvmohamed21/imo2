package com.example

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

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
            val apkUrl = json.optString("apkUrl", "")
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
     * Trigger APK Download using DownloadManager or internal storage
     */
    fun startDownload(apkUrl: String) {
        try {
            _status.value = UpdateCheckStatus.DOWNLOADING
            _downloadProgress.value = 10

            val uri = Uri.parse(apkUrl)
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            val fileName = "watchroom_v${_updateInfo.value?.versionName ?: "latest"}.apk"
            val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (destination.exists()) {
                destination.delete()
            }
            downloadedApkFile = destination

            val request = DownloadManager.Request(uri).apply {
                setTitle("WatchRoom Update")
                setDescription("Downloading WatchRoom v${_updateInfo.value?.versionName ?: ""}")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationUri(Uri.fromFile(destination))
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            downloadId = downloadManager.enqueue(request)

            val onComplete = object : BroadcastReceiver() {
                override fun onReceive(ctxt: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
                    if (id == downloadId) {
                        _status.value = UpdateCheckStatus.READY_TO_INSTALL
                        _downloadProgress.value = 100
                        try {
                            context.unregisterReceiver(this)
                        } catch (_: Exception) {}
                        installApk(destination)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    onComplete,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(
                    onComplete,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }
        } catch (e: Exception) {
            Log.e("UpdateManager", "Failed to start download", e)
            _status.value = UpdateCheckStatus.ERROR
            _errorMessage.value = "Failed to start download: ${e.localizedMessage}"
        }
    }

    /**
     * Launch Package Installer for downloaded APK
     */
    fun installApk(file: File? = downloadedApkFile) {
        val targetFile = file ?: downloadedApkFile ?: return
        if (!targetFile.exists()) {
            _errorMessage.value = "APK file not found"
            return
        }

        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                targetFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("UpdateManager", "Failed to launch installer", e)
            _errorMessage.value = "Failed to launch installer: ${e.localizedMessage}"
        }
    }

    fun dismiss() {
        _status.value = UpdateCheckStatus.IDLE
        _errorMessage.value = null
    }
}
