package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WatchRoomColorScheme = darkColorScheme(
    primary = WatchPurplePrimary,
    onPrimary = Color.White,
    secondary = WatchCyanAccent,
    onSecondary = Color(0xFF001F29),
    tertiary = WatchGreenSync,
    onTertiary = Color(0xFF00220F),
    background = WatchDarkBackground,
    onBackground = Color.White,
    surface = WatchDarkSurface,
    onSurface = Color.White,
    surfaceVariant = WatchDarkSurfaceVariant,
    onSurfaceVariant = Color.White.copy(alpha = 0.75f)
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = WatchRoomColorScheme,
        typography = Typography,
        content = content
    )
}

