package com.astermediaplayer.astermediaplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astermediaplayer.astermediaplayer.data.ThemeCards
import com.astermediaplayer.astermediaplayer.data.ThemeInfo
import com.astermediaplayer.astermediaplayer.data.ThemeMoment
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup

/**
 * The song card, drawn in the lower left over the video: up for the first seconds of an opening or ending and, when it's long enough
 * for two, its last; it sits above the player's controls (which reach [controlsHeight] up) rather than under them. The Compose
 * counterpart of the Apple apps' `ThemeInfoOverlay`.
 */
@Composable
fun ThemeCardOverlay(moments: List<ThemeMoment>, positionMs: Long, controlsHeight: Dp, modifier: Modifier = Modifier) {
    if (moments.isEmpty()) return
    val seconds = positionMs / 1000.0
    val active = moments.firstOrNull { ThemeCards.visible(seconds, it.startSec, it.endSec) }
    // The card fades out showing what it showed.
    val last = remember { arrayOfNulls<ThemeInfo>(1) }
    if (active != null) last[0] = active.info
    val lift by animateDpAsState(controlsHeight, tween(350), label = "cardLift")
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = active != null, modifier = Modifier.align(Alignment.BottomStart),
            enter = slideInHorizontally(tween(600)) { -it / 3 } + fadeIn(tween(600)), exit = fadeOut(tween(600)),
        ) { last[0]?.let { ThemeCard(it, lift) } }
    }
}

@Composable
private fun ThemeCard(info: ThemeInfo, controlsHeight: Dp) {
    val tv = LocalIsTv.current
    val u = Lockup.tvUnit
    // The card at TV size (the Apple TV's points), or scaled down for a phone or tablet.
    val gap = if (tv) 22.dp * u else 12.dp
    val barWidth = if (tv) 5.dp * u else 3.dp
    val lineSpacing = if (tv) 8.dp * u else 4.dp
    val leading = if (tv) 90.dp * u else 24.dp
    val bottom = if (controlsHeight > 0.dp) controlsHeight + if (tv) 30.dp * u else 12.dp else if (tv) 120.dp * u else 40.dp
    val maxWidth = if (tv) 900.dp * u else 420.dp
    val shade = if (tv) 1100.dp * u to 500.dp * u else 520.dp to 240.dp
    fun size(phone: Float, tvPoints: Float) = if (tv) (tvPoints * u).sp else phone.sp
    val shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 2f), 6f)
    val white = Color.White

    Box(Modifier.wrapContentSize()) {
        // A soft shade behind the text so it reads over bright frames: elliptical from the corner, clear at the top and right edges.
        Box(
            Modifier.align(Alignment.BottomStart).size(shade.first, shade.second + controlsHeight).drawBehind {
                val w = size.width
                val h = size.height
                scale(1f, h / w, pivot = Offset(0f, h)) {
                    drawRect(
                        brush = Brush.radialGradient(0f to Color.Black.copy(alpha = 0.55f), 0.55f to Color.Black.copy(alpha = 0.3f), 1f to Color.Transparent, center = Offset(0f, h), radius = w),
                        topLeft = Offset(0f, h - w), size = Size(w, w),
                    )
                }
            },
        )
        Row(
            Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Start)).padding(start = leading, bottom = bottom)
                .widthIn(max = maxWidth).height(IntrinsicSize.Min),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap),
        ) {
            Box(Modifier.width(barWidth).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(white))
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(lineSpacing)) {
                Text(
                    info.kicker.uppercase(), color = white.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = size(11f, 22f), fontWeight = FontWeight.SemiBold, letterSpacing = 2.5.sp, shadow = shadow),
                )
                Text(info.title, color = white, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = size(24f, 50f), lineHeight = size(28f, 56f), fontWeight = FontWeight.Bold, shadow = shadow))
                info.artists?.let { Text(it, color = white, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = size(14f, 30f), fontWeight = FontWeight.Medium, shadow = shadow)) }
                info.series?.let { Text(it, color = white.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = size(13f, 28f), fontWeight = FontWeight.SemiBold, shadow = shadow)) }
                info.detail?.let { Text(it, color = white.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = size(12f, 26f), shadow = shadow)) }
            }
        }
    }
}
