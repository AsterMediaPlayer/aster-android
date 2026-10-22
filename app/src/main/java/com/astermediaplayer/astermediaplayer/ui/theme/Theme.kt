package com.astermediaplayer.astermediaplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF9B8CFF)

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1B1140),
    background = Color(0xFF0E0E12),
    surface = Color(0xFF0E0E12),
    surfaceVariant = Color(0xFF1C1C24),
    surfaceContainer = Color(0xFF16161D),
    onSurfaceVariant = Color(0xFFB8B8C4),
)

private val Light = lightColorScheme(primary = Color(0xFF5A48D6))

@Composable
fun AsterTheme(content: @Composable () -> Unit) {
    // Media apps read best dark; follow the system only when it's explicitly light.
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
