package com.astermediaplayer.astermediaplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF0A84FF)

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF8E8E93),
)

@Composable
fun AsterTheme(content: @Composable () -> Unit) {
    // Aster is dark everywhere, like the Apple apps.
    MaterialTheme(colorScheme = Dark, content = content)
}
