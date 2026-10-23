package com.astermediaplayer.astermediaplayer.ui.player

import android.content.Context
import android.view.accessibility.CaptioningManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The current cue near the bottom of the picture, in the viewer's system caption style (Settings › Accessibility ›
 * Caption preferences), as the Apple apps' `SubtitleOverlayView`. It takes no touches.
 *
 * The picture is taken as 16:9 fitted to the screen, so the line is sized and placed by it: a phone shows video at many
 * sizes, and the line should sit near the picture's bottom edge, not the screen's.
 */
@Composable
fun SubtitleOverlay(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val captioning = remember(context) { context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager }
    val style = captioning?.userStyle
    val scale = captioning?.fontScale?.takeIf { it > 0f } ?: 1f
    val foreground = style?.takeIf { it.hasForegroundColor() }?.let { Color(it.foregroundColor) } ?: Color.White
    val background = style?.takeIf { it.hasBackgroundColor() }?.let { Color(it.backgroundColor) } ?: Color.Black.copy(alpha = 0.65f)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val videoHeight = minOf(maxHeight, maxWidth * 9 / 16)
        val videoBottom = (maxHeight + videoHeight) / 2
        val size = (videoHeight.value * 0.05f).coerceIn(14f, 30f)
        Text(
            text,
            color = foreground,
            fontSize = (size * scale).sp,
            lineHeight = (size * scale * 1.3f).sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            style = androidx.compose.ui.text.TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), 2f)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = maxOf(maxWidth * 0.06f, 16.dp))
                .padding(bottom = maxHeight - videoBottom + videoHeight * 0.04f)
                .background(background, RoundedCornerShape((size * 0.24f).dp))
                .padding(horizontal = (size * 0.5f).dp, vertical = (size * 0.24f).dp),
        )
    }
}
