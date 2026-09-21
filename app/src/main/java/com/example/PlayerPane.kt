package com.example

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
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
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import android.webkit.CookieManager
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale

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

    // ExoPlayer creation with specialized Referer, User-Agent, and Cookies for streaming sites
    val exoPlayer = remember(context) {
        val uri = try { Uri.parse(mediaUrl) } catch (e: Exception) { Uri.EMPTY }
        val host = uri.host ?: ""
        val resolvedReferer = when {
            referer.isNotBlank() -> referer
            host.contains("qfilm") -> "https://a.qfilm.tv/"
            host.contains("egybest") -> "https://www.egybest.co.in/"
            host.isNotBlank() -> "${uri.scheme ?: "https"}://$host/"
            else -> "https://www.egybest.co.in/"
        }
        val origin = if (host.isNotBlank()) "${uri.scheme ?: "https"}://$host" else "https://www.egybest.co.in"
        val cookieHeader = try {
            CookieManager.getInstance().getCookie(resolvedReferer)
                ?: CookieManager.getInstance().getCookie(mediaUrl)
                ?: ""
        } catch (e: Exception) { "" }

        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
            "Referer" to resolvedReferer,
            "Origin" to origin,
            "Accept" to "*/*",
            "Accept-Language" to "ar,en-US,en;q=0.9"
        )
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

    // Prepare media when URL changes
    LaunchedEffect(mediaUrl, referer) {
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

    // Handle Remote Media Control Command (From Socket.io)
    LaunchedEffect(remoteControlCommand) {
        remoteControlCommand?.let { cmd ->
            val now = System.currentTimeMillis()
            // Set 1.5-second anti-echo window so local player callbacks don't bounce the event back!
            suppressUntilMs = now + 1500L

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

    // Listen to ExoPlayer lifecycle
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    val d = exoPlayer.duration
                    if (d > 0 && d != C.TIME_UNSET) {
                        durationMs = d
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = "Playback error: ${error.message ?: "Unknown"}"
                isBuffering = false
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Periodic time progress tracker
    LaunchedEffect(isPlaying, exoPlayer) {
        while (true) {
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
        // Player Surface View
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false // Custom Jetpack Compose overlay controls
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Buffering Indicator
        if (isBuffering && playbackError == null) {
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

        // Error State overlay
        if (playbackError != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Error",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = playbackError ?: "Error playing video",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = {
                                playbackError = null
                                isBuffering = true
                                exoPlayer.prepare()
                                exoPlayer.play()
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF00E676)
                        ) {
                            Text(
                                text = "إعادة المحاولة (Retry)",
                                color = Color.Black,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }

                        if (isHost) {
                            Surface(
                                onClick = onSwitchToBrowser,
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFF6C5CE7)
                            ) {
                                Text(
                                    text = "العودة للمتصفح (Browser)",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Custom Media Controls Overlay
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
                                Color.Black.copy(alpha = 0.75f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            ) {
                // Top Bar: Stream Info + Browser Switch (Host only) + Fullscreen
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color(0xFF1F293D).copy(alpha = 0.8f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00E676))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "SYNC PLAY",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E676)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = title,
                        fontSize = 13.sp,
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
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = "Browser",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Browser",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                // Center Controls: Rewind 15s | Play/Pause | Forward 15s
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
                            val maxMs = if (durationMs > 0) durationMs else Long.MAX_VALUE
                            val newPosMs = Math.min(maxMs, exoPlayer.currentPosition + 15000L)
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

                // Bottom Bar: Progress Slider + Timestamps + Fullscreen
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    // Time Slider
                    if (durationMs > 0) {
                        Slider(
                            value = if (isSeekingSlider) sliderValue else currentPositionMs.toFloat(),
                            onValueChange = { newValue ->
                                isSeekingSlider = true
                                sliderValue = newValue
                            },
                            onValueChangeFinished = {
                                isSeekingSlider = false
                                val targetMs = sliderValue.toLong()
                                exoPlayer.seekTo(targetMs)
                                currentPositionMs = targetMs
                                dispatchLocalAction("seek", targetMs / 1000L)
                            },
                            valueRange = 0f..durationMs.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF6C5CE7),
                                activeTrackColor = Color(0xFF6C5CE7),
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(22.dp)
                                .testTag("media_time_slider")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatTime(currentPositionMs)} / ${formatTime(durationMs)}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.85f)
                        )

                        IconButton(
                            onClick = onToggleFullscreen,
                            modifier = Modifier.size(32.dp).testTag("fullscreen_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = "Fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Guest placeholder waiting for the host to select/sniff a video
 */
@Composable
fun GuestWaitingPane(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F1424), Color(0xFF080A12))
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E263D)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Tv,
                    contentDescription = "Waiting for host",
                    tint = Color(0xFF6C5CE7),
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Waiting for Host to Pick a Video...",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "The host is browsing movies. Playback will start automatically for everyone in pristine native quality!",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    color = Color(0xFF6C5CE7),
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Sync standby active",
                    fontSize = 12.sp,
                    color = Color(0xFF6C5CE7),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

fun formatTime(timeMs: Long): String {
    if (timeMs <= 0) return "00:00"
    val totalSeconds = timeMs / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
