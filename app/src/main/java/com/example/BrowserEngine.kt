package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private const val TAG = "BrowserEngine"

// Ad & tracker blacklist keywords
private val AD_BLOCK_KEYWORDS = listOf(
    "doubleclick.net", "googlesyndication.com", "google-analytics.com",
    "adnxs.com", "popads.net", "popcash.net", "adsterra.com", "propellerads.com",
    "adtrue.com", "yllix.com", "exoclick.com", "trafficjunky.com",
    "/ads/", "/banner/", "/popups/", "adclick", "adservice"
)

// Direct streaming indicators
private val MEDIA_EXTENSIONS = listOf(
    ".m3u8", ".mp4", "/hls/", "/video/", ".mpd", ".ts", "/playlist.m3u8"
)

data class QuickBookmark(
    val title: String,
    val url: String,
    val isDirectMedia: Boolean = false
)

val DEFAULT_BOOKMARKS = listOf(
    QuickBookmark(
        title = "Big Buck Bunny (HLS)",
        url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        isDirectMedia = true
    ),
    QuickBookmark(
        title = "Tears of Steel (MP4)",
        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
        isDirectMedia = true
    ),
    QuickBookmark(
        title = "Sintel (HLS)",
        url = "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8",
        isDirectMedia = true
    ),
    QuickBookmark(
        title = "Archive Movies",
        url = "https://archive.org/details/movies",
        isDirectMedia = false
    ),
    QuickBookmark(
        title = "EgyBest Search",
        url = "https://www.google.com/search?q=egybest+watch+online",
        isDirectMedia = false
    )
)

/**
 * Android Video Sniffer and Popup/Ad-protected Browser
 */
class VideoSnifferClient(
    private val onMediaCaptured: (url: String) -> Unit,
    private val onPageLoading: (isLoading: Boolean, progress: Float) -> Unit,
    private val onUrlChanged: (url: String) -> Unit
) : WebViewClient() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastCapturedUrl: String? = null

    private fun isMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        // Must match media signatures
        val hasMediaPattern = MEDIA_EXTENSIONS.any { lower.contains(it) }
        // Must NOT be an ad or tracker
        val isAd = AD_BLOCK_KEYWORDS.any { lower.contains(it) }
        return hasMediaPattern && !isAd
    }

    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return AD_BLOCK_KEYWORDS.any { lower.contains(it) }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        val uri = request.url

        // Block non-http/https schemes (intents, market, tele)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            Log.d(TAG, "Blocked non-web intent scheme: $url")
            return true
        }

        // Block external ad redirects
        if (isAdUrl(url)) {
            Log.d(TAG, "Blocked ad redirect: $url")
            return true
        }

        // Check if navigation itself is a direct media stream
        if (isMediaUrl(url)) {
            Log.i(TAG, "Navigating to direct media: $url")
            notifyMediaCaptured(url)
            return true // handled by native player
        }

        return false
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

        // Block ad resources silently
        if (isAdUrl(url)) {
            return WebResourceResponse("text/plain", "UTF-8", null)
        }

        // Sniff media stream
        if (isMediaUrl(url)) {
            Log.i(TAG, "Sniffed direct stream from network request: $url")
            notifyMediaCaptured(url)
        }

        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageLoading(true, 0.1f)
        url?.let { onUrlChanged(it) }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageLoading(false, 1.0f)
        url?.let { onUrlChanged(it) }

        // Inject HTML5 Video interceptor script to catch client-side video playback
        val jsInjection = """
            (function() {
                if (window.__watchroom_injected) return;
                window.__watchroom_injected = true;

                function sniffVideo(v) {
                    if (!v) return;
                    var src = v.currentSrc || v.src;
                    if (src && src.length > 5 && !src.startsWith('blob:')) {
                        WatchRoomBridge.onMediaFound(src);
                    }
                    var sources = v.getElementsByTagName('source');
                    for (var i = 0; i < sources.length; i++) {
                        var s = sources[i].src;
                        if (s && s.length > 5) {
                            WatchRoomBridge.onMediaFound(s);
                        }
                    }
                }

                // Check existing videos
                document.querySelectorAll('video').forEach(sniffVideo);

                // Listen to play events
                document.addEventListener('play', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO') {
                        sniffVideo(e.target);
                    }
                }, true);

                // Hook HTMLMediaElement.prototype.play
                var origPlay = window.HTMLMediaElement.prototype.play;
                window.HTMLMediaElement.prototype.play = function() {
                    sniffVideo(this);
                    return origPlay.apply(this, arguments);
                };
            })();
        """.trimIndent()

        view?.evaluateJavascript(jsInjection, null)
    }

    private fun notifyMediaCaptured(url: String) {
        if (lastCapturedUrl == url) return
        lastCapturedUrl = url
        mainHandler.post {
            onMediaCaptured(url)
        }
    }
}

/**
 * JavaScript interface bridge for DOM-level media capture
 */
class VideoSnifferBridge(
    private val onMediaCaptured: (url: String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onMediaFound(url: String?) {
        if (!url.isNullOrBlank()) {
            Log.i(TAG, "Bridge captured video DOM src: $url")
            mainHandler.post {
                onMediaCaptured(url)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserPane(
    initialUrl: String = "https://archive.org/details/movies",
    onMediaCaptured: (streamUrl: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var currentUrlText by remember { mutableStateOf(initialUrl) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingProgress by remember { mutableFloatStateOf(0f) }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D111A))
    ) {
        // Browser URL and Navigation Bar
        Surface(
            color = Color(0xFF131826),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (webViewInstance?.canGoBack() == true) {
                                webViewInstance?.goBack()
                            }
                        },
                        modifier = Modifier.size(36.dp).testTag("browser_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            if (webViewInstance?.canGoForward() == true) {
                                webViewInstance?.goForward()
                            }
                        },
                        modifier = Modifier.size(36.dp).testTag("browser_forward_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Forward",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { webViewInstance?.reload() },
                        modifier = Modifier.size(36.dp).testTag("browser_reload_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reload",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Address Bar
                    OutlinedTextField(
                        value = currentUrlText,
                        onValueChange = { currentUrlText = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("browser_url_input"),
                        singleLine = true,
                        placeholder = {
                            Text(
                                "Enter movie URL or search",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.4f)
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 12.sp,
                            color = Color.White
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6C5CE7),
                            unfocusedBorderColor = Color(0xFF2A344A),
                            focusedContainerColor = Color(0xFF0A0D15),
                            unfocusedContainerColor = Color(0xFF0A0D15),
                            cursorColor = Color(0xFF6C5CE7)
                        ),
                        shape = RoundedCornerShape(20.dp),
                        trailingIcon = {
                            if (currentUrlText.isNotBlank()) {
                                IconButton(
                                    onClick = { currentUrlText = "" },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear text",
                                        tint = Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                focusManager.clearFocus()
                                val target = currentUrlText.trim()
                                val finalUrl = when {
                                    target.startsWith("http://") || target.startsWith("https://") -> target
                                    target.contains(".") && !target.contains(" ") -> "https://$target"
                                    else -> "https://www.google.com/search?q=" + Uri.encode(target)
                                }
                                currentUrlText = finalUrl
                                webViewInstance?.loadUrl(finalUrl)
                            }
                        )
                    )
                }

                // Quick Bookmarks row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DEFAULT_BOOKMARKS.forEach { bm ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                focusManager.clearFocus()
                                if (bm.isDirectMedia) {
                                    // Direct media link test
                                    onMediaCaptured(bm.url)
                                } else {
                                    currentUrlText = bm.url
                                    webViewInstance?.loadUrl(bm.url)
                                }
                            },
                            label = {
                                Text(
                                    text = bm.title,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = if (bm.isDirectMedia) Color(0xFF1F2847) else Color(0xFF1A1F2C),
                                labelColor = if (bm.isDirectMedia) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.85f)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = false,
                                borderColor = if (bm.isDirectMedia) Color(0xFF3F51B5) else Color(0xFF2C344A)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }
        }

        // Web Loading Progress
        if (isLoading) {
            LinearProgressIndicator(
                progress = { loadingProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = Color(0xFF6C5CE7),
                trackColor = Color(0xFF131826),
            )
        }

        // Native WebView
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize().testTag("host_browser_webview"),
                factory = { context ->
                    WebView(context).apply {
                        webViewInstance = this

                        // Configure Ad/Popup-Blocking WebViewSettings
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            setSupportMultipleWindows(false) // Blocks popup popunders
                            javaScriptCanOpenWindowsAutomatically = false
                            loadsImagesAutomatically = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = settings.userAgentString + " WatchRoomBrowser/1.0"
                            databaseEnabled = true
                        }

                        // Add Javascript Interface for HTML5 video sniffing
                        addJavascriptInterface(
                            VideoSnifferBridge { capturedUrl ->
                                onMediaCaptured(capturedUrl)
                            },
                            "WatchRoomBridge"
                        )

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadingProgress = newProgress / 100f
                                isLoading = newProgress < 100
                            }

                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                return true
                            }
                        }

                        webViewClient = VideoSnifferClient(
                            onMediaCaptured = onMediaCaptured,
                            onPageLoading = { loading, prog ->
                                isLoading = loading
                                loadingProgress = prog
                            },
                            onUrlChanged = { newUrl ->
                                currentUrlText = newUrl
                            }
                        )

                        loadUrl(initialUrl)
                    }
                },
                update = { webView ->
                    webViewInstance = webView
                }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.destroy()
            webViewInstance = null
        }
    }
}
