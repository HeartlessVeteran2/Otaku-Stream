package com.otakustream.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// A dark, neon-violet look — a video app lives in the dark, so this is the only theme.
private val OtakuColors = darkColorScheme(
    primary = Color(0xFFB388FF),
    onPrimary = Color(0xFF1A0A2E),
    primaryContainer = Color(0xFF4A2B7A),
    onPrimaryContainer = Color(0xFFE8DDFF),
    secondary = Color(0xFFFF80AB),
    onSecondary = Color(0xFF2E0A1A),
    background = Color(0xFF12121A),
    onBackground = Color(0xFFE6E1EC),
    surface = Color(0xFF1A1A24),
    onSurface = Color(0xFFE6E1EC),
    surfaceVariant = Color(0xFF262430),
    onSurfaceVariant = Color(0xFFCAC4D4),
    error = Color(0xFFFFB4AB),
)

@Composable
fun OtakuStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OtakuColors, content = content)
}
