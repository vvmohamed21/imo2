package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale

private const val TAG = "PlayerPane"

@OptIn(UnstableApi::class)
@Composable
fun PlayerPane(
    mediaUrl: String,
    referer: String = "",
    title: String = "WatchRoom Stream",
    isHost: Boolean = false,
    onUserControlAction: (action: String, timeSeconds: Long) -> Unit,
    onSwitchToBrowser: () -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
    isFullscreen: Boolean = false,
    remoteControlCommand: RemoteMediaControl? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Player Engine: "native" (ExoPlayer) or "web" (Hardware-accelerated HTML5 / Hls.js WebView)
    var playerEngine by remember { mutableStateOf("native") }

    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isBuffering by remember { mutableStateOf(true) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var isSeekingSlider by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }

    // Anti-echo suppression timestamp: local actions are ignored from re-triggering network events
    var suppressUntilMs by remember { mutableLongStateOf(0L) }

    // ExoPlayer creation with clean, non-conflicting headers for streaming sites
    val exoPlayer = remember(context) {
        val uri = try { Uri.parse(mediaUrl) } catch (e: Exception) { Uri.EMPTY }
        val host = uri.host ?: ""
        val resolvedReferer = when {
            referer.isNotBlank() -> referer
            host.isNotBlank() -> "${uri.scheme ?: "https"}://$host/"
            else -> ""
        }
        val cookieHeader = try {
            if (resolvedReferer.isNotBlank()) {
                CookieManager.getInstance().getCookie(resolvedReferer)
                    ?: CookieManager.getInstance().getCookie(mediaUrl)
                    ?: ""
            } else {
                CookieManager.getInstance().getCookie(mediaUrl) ?: ""
            }
        } catch (e: Exception) { "" }

        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "ar,en-US,en;q=0.9"
        )
        if (resolvedReferer.isNotBlank()) {
            headers["Referer"] = resolvedReferer
        }
        if (cookieHeader.isNotBlank()) {
            headers["Cookie"] = cookieHeader
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)
            .setConnectTimeoutMs(25000)
            .setReadTimeoutMs(30000)
            .setDefaultRequestProperties(headers)

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    // Function to reload native stream
    fun reloadNativeStream() {
        if (mediaUrl.isNotBlank()) {
            playbackError = null
            isBuffering = true
            try {
                val uri = Uri.parse(mediaUrl)
                val mediaItem = if (mediaUrl.contains(".m3u8") || mediaUrl.contains("/hls/") || mediaUrl.contains("m3u8")) {
                    MediaItem.Builder()
                        .setUri(uri)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()
                } else {
                    MediaItem.fromUri(uri)
                }

                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.play()
            } catch (e: Exception) {
                playbackError = "Failed to load stream: ${e.localizedMessage}"
            }
        }
    }

    // Prepare media when URL changes
    LaunchedEffect(mediaUrl, referer) {
        if (playerEngine == "native") {
            reloadNativeStream()
        } else {
            playbackError = null
            isBuffering = false
        }
    }

    // Handle Remote Media Control Command (From Socket.io)
    LaunchedEffect(remoteControlCommand) {
        remoteControlCommand?.let { cmd ->
            val now = System.currentTimeMillis()
            // Set 1.5-second anti-echo window so local player callbacks don't bounce the event back!
            suppressUntilMs = now + 1500L

            if (playerEngine == "native") {
                val targetMs = cmd.timeSeconds * 1000L
                when (cmd.action) {
                    "play" -> {
                        if (Math.abs(exoPlayer.currentPosition - targetMs) > 2000L) {
                            exoPlayer.seekTo(targetMs)
                        }
                        exoPlayer.play()
                    }
                    "pause" -> {
                        if (Math.abs(exoPlayer.currentPosition - targetMs) > 2000L) {
                            exoPlayer.seekTo(targetMs)
                        }
                        exoPlayer.pause()
                    }
                    "seek" -> {
                        exoPlayer.seekTo(targetMs)
                    }
                }
            }
        }
    }

    // Listen to ExoPlayer lifecycle
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (playerEngine == "native") {
                    isBuffering = state == Player.STATE_BUFFERING
                    if (state == Player.STATE_READY) {
                        val d = exoPlayer.duration
                        if (d > 0 && d != C.TIME_UNSET) {
                            durationMs = d
                        }
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                if (playerEngine == "native") {
                    isPlaying = playing
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer error: ${error.errorCodeName} - ${error.message}", error)
                val detail = error.cause?.message ?: error.message ?: "Source error"
                playbackError = "Source error: $detail"
                isBuffering = false
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Periodic time progress tracker for ExoPlayer
    LaunchedEffect(isPlaying, exoPlayer, playerEngine) {
        while (playerEngine == "native") {
            if (!isSeekingSlider && exoPlayer.playbackState != Player.STATE_IDLE) {
                currentPositionMs = exoPlayer.currentPosition
                val d = exoPlayer.duration
                if (d > 0 && d != C.TIME_UNSET) {
                    durationMs = d
                }
            }
            delay(500)
        }
    }

    // Auto-hide controls overlay after 4 seconds of inactivity
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(4000)
            showControls = false
        }
    }

    fun dispatchLocalAction(action: String, targetSeconds: Long) {
        val now = System.currentTimeMillis()
        if (now < suppressUntilMs) return // Inside suppression window

        onUserControlAction(action, targetSeconds)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
            }
    ) {
        // ========================================================
        // 1. ACTIVE PLAYER ENGINE (NATIVE EXOPLAYER vs WEB PLAYER)
        // ========================================================
        if (playerEngine == "native") {
            // Native ExoPlayer Surface
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Hardware-accelerated Synchronized Web Video Player
            WebPlayerEngine(
                mediaUrl = mediaUrl,
                referer = referer,
                onStateChange = { playing, timeSec ->
                    isPlaying = playing
                    currentPositionMs = timeSec * 1000L
                    dispatchLocalAction(if (playing) "play" else "pause", timeSec)
                },
                onSeekChange = { timeSec ->
                    currentPositionMs = timeSec * 1000L
                    dispatchLocalAction("seek", timeSec)
                },
                remoteControlCommand = remoteControlCommand,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Buffering Indicator (For Native Engine)
        if (playerEngine == "native" && isBuffering && playbackError == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color(0xFF6C5CE7),
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        // ========================================================
        // 2. ERROR STATE OVERLAY WITH INSTANT WEB PLAYER RECOVERY
        // ========================================================
        if (playbackError != null && playerEngine == "native") {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.90f))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF5252).copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Playback Error",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "تعذر تشغيل الرابط في المشغل الداخلي",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "سيرفر البث يمنع الاتصال المباشر أو يحتاج لتخطي الحماية. الحل متاح بالأسفل فوراً:",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // PRIMARY RESCUE: Switch to Web Player (Works with protected / CDN streams!)
                    Surface(
                        onClick = {
                            playbackError = null
                            playerEngine = "web"
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF00E676),
                        shadowElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth(0.85f).testTag("switch_to_web_player_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "Play via Web Player",
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "⚡ تشغيل عبر مشغل الويب (Web Player)",
                                color = Color.Black,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Retry Native Player
                        Surface(
                            onClick = {
                                playbackError = null
                                isBuffering = true
                                reloadNativeStream()
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF26324A)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Retry",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "إعادة المحاولة",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // Host: Switch back to browser to pick another server (Server 2, 3, etc.)
                        if (isHost) {
                            Surface(
                                onClick = onSwitchToBrowser,
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFF6C5CE7)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Language,
                                        contentDescription = "Browser",
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "اختيار سيرفر آخر",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ========================================================
        // 3. TOP BAR & STREAM ENGINE SWITCH CONTROLS
        // ========================================================
        AnimatedVisibility(
            visible = showControls && playbackError == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.8f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            ) {
                // Top Bar: Stream Info + Engine Toggle + Browser Switch + Fullscreen
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Engine Switcher Pill (Native vs Web)
                    Surface(
                        onClick = {
                            playerEngine = if (playerEngine == "native") "web" else "native"
                            if (playerEngine == "native") reloadNativeStream()
                        },
                        color = if (playerEngine == "web") Color(0xFF00E676).copy(alpha = 0.2f) else Color(0xFF1F293D).copy(alpha = 0.85f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (playerEngine == "web") Color(0xFF00E676) else Color(0xFF3F4D6B)
                        ),
                        modifier = Modifier.testTag("toggle_player_engine")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                imageVector = if (playerEngine == "web") Icons.Default.Public else Icons.Default.Bolt,
                                contentDescription = "Engine",
                                tint = if (playerEngine == "web") Color(0xFF00E676) else Color(0xFF00E5FF),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (playerEngine == "web") "مشغل الويب (Web)" else "المشغل المباشر (Exo)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (playerEngine == "web") Color(0xFF00E676) else Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // Host: Return to Browser button
                    if (isHost) {
                        Surface(
                            onClick = onSwitchToBrowser,
                            color = Color(0xFF6C5CE7).copy(alpha = 0.85f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.testTag("switch_to_browser_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = "Browser",
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "المتصفح",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    // Fullscreen Toggle
                    IconButton(
                        onClick = onToggleFullscreen,
                        modifier = Modifier.size(34.dp).testTag("fullscreen_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = "Toggle Fullscreen",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Center Controls (For Native ExoPlayer): Rewind 15s | Play/Pause | Forward 15s
                if (playerEngine == "native") {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Rewind 15s
                        IconButton(
                            onClick = {
                                val newPosMs = Math.max(0L, exoPlayer.currentPosition - 15000L)
                                exoPlayer.seekTo(newPosMs)
                                currentPositionMs = newPosMs
                                dispatchLocalAction("seek", newPosMs / 1000L)
                            },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.15f))
                                .testTag("media_rewind_15s_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastRewind,
                                contentDescription = "Rewind 15s",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Play / Pause Toggle
                        IconButton(
                            onClick = {
                                if (isPlaying) {
                                    exoPlayer.pause()
                                    dispatchLocalAction("pause", exoPlayer.currentPosition / 1000L)
                                } else {
                                    exoPlayer.play()
                                    dispatchLocalAction("play", exoPlayer.currentPosition / 1000L)
                                }
                            },
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF6C5CE7))
                                .testTag("media_play_pause_button")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        // Forward 15s
                        IconButton(
                            onClick = {
                                val d = if (durationMs > 0) durationMs else Long.MAX_VALUE
                                val newPosMs = Math.min(d, exoPlayer.currentPosition + 15000L)
                                exoPlayer.seekTo(newPosMs)
                                currentPositionMs = newPosMs
                                dispatchLocalAction("seek", newPosMs / 1000L)
                            },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.15f))
                                .testTag("media_forward_15s_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastForward,
                                contentDescription = "Forward 15s",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // Bottom Bar: Progress Slider + Timestamp Formatting
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Slider(
                            value = if (isSeekingSlider) sliderValue else {
                                if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                            },
                            onValueChange = { newValue ->
                                isSeekingSlider = true
                                sliderValue = newValue
                            },
                            onValueChangeFinished = {
                                isSeekingSlider = false
                                val targetMs = (sliderValue * durationMs).toLong()
                                exoPlayer.seekTo(targetMs)
                                currentPositionMs = targetMs
                                dispatchLocalAction("seek", targetMs / 1000L)
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF6C5CE7),
                                activeTrackColor = Color(0xFF6C5CE7),
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier.fillMaxWidth().height(20.dp).testTag("media_scrubber_slider")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatTime(currentPositionMs),
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (durationMs > 0) formatTime(durationMs) else "--:--",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Embedded Hardware-Accelerated Web Video Player
 * Runs inside standard Android WebView possessing all browser cookies, origin,
 * and Hls.js runtime. This guarantees playback even for CDNs that block direct ExoPlayer HTTP calls.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebPlayerEngine(
    mediaUrl: String,
    referer: String,
    onStateChange: (isPlaying: Boolean, timeSec: Long) -> Unit,
    onSeekChange: (timeSec: Long) -> Unit,
    remoteControlCommand: RemoteMediaControl?,
    modifier: Modifier = Modifier
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Respond to remote synchronization commands
    LaunchedEffect(remoteControlCommand) {
        remoteControlCommand?.let { cmd ->
            val sec = cmd.timeSeconds
            val script = when (cmd.action) {
                "play" -> """
                    (function() {
                        var v = document.querySelector('video');
                        if (v) {
                            if (Math.abs(v.currentTime - $sec) > 2) v.currentTime = $sec;
                            v.play().catch(function(){});
                        }
                    })();
                """.trimIndent()
                "pause" -> """
                    (function() {
                        var v = document.querySelector('video');
                        if (v) {
                            v.currentTime = $sec;
                            v.pause();
                        }
                    })();
                """.trimIndent()
                "seek" -> """
                    (function() {
                        var v = document.querySelector('video');
                        if (v) {
                            v.currentTime = $sec;
                        }
                    })();
                """.trimIndent()
                else -> ""
            }
            if (script.isNotBlank()) {
                webViewRef?.evaluateJavascript(script, null)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                webViewRef = this
                setBackgroundColor(android.graphics.Color.BLACK)

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    allowFileAccess = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }

                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return false
                    }
                }

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onPlay(timeSec: Long) {
                        onStateChange(true, timeSec)
                    }

                    @JavascriptInterface
                    fun onPause(timeSec: Long) {
                        onStateChange(false, timeSec)
                    }

                    @JavascriptInterface
                    fun onSeeked(timeSec: Long) {
                        onSeekChange(timeSec)
                    }
                }, "AndroidBridge")

                val html = """
                    <!DOCTYPE html>
                    <html dir="ltr">
                    <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                    <script src="https://cdn.jsdelivr.net/npm/hls.js@1.5.8/dist/hls.min.js"></script>
                    <style>
                      * { box-sizing: border-box; margin: 0; padding: 0; }
                      html, body { width: 100%; height: 100%; background: #000; overflow: hidden; display: flex; align-items: center; justify-content: center; }
                      video { width: 100%; height: 100%; max-height: 100vh; object-fit: contain; background: #000; }
                    </style>
                    </head>
                    <body>
                    <video id="player" controls playsinline autoplay></video>
                    <script>
                      var v = document.getElementById('player');
                      var src = '$mediaUrl';
                      if (src.indexOf('.m3u8') !== -1 && typeof Hls !== 'undefined' && Hls.isSupported()) {
                        var hls = new Hls({ enableWorker: true, lowLatencyMode: false });
                        hls.loadSource(src);
                        hls.attachMedia(v);
                        hls.on(Hls.Events.MANIFEST_PARSED, function() {
                          v.play().catch(function(e){});
                        });
                      } else {
                        v.src = src;
                        v.play().catch(function(e){});
                      }

                      v.addEventListener('play', function() {
                        if (window.AndroidBridge) AndroidBridge.onPlay(Math.floor(v.currentTime));
                      });
                      v.addEventListener('pause', function() {
                        if (window.AndroidBridge) AndroidBridge.onPause(Math.floor(v.currentTime));
                      });
                      v.addEventListener('seeked', function() {
                        if (window.AndroidBridge) AndroidBridge.onSeeked(Math.floor(v.currentTime));
                      });
                    </script>
                    </body>
                    </html>
                """.trimIndent()

                loadDataWithBaseURL(
                    if (referer.isNotBlank()) referer else mediaUrl,
                    html,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        },
        modifier = modifier
    )

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = Math.max(0L, millis / 1000L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
