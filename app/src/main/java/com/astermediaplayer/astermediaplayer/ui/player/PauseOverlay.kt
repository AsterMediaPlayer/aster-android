package com.astermediaplayer.astermediaplayer.ui.player

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.data.PauseInfo
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.delay

// The TV player's pause screen, as the Apple app's on Apple TV: the title's clear logo in the top left, and above the timeline a
// panel of its details, a long description drifting slowly up so it can be read to the end. What it says is PauseInfo's.

/** The clear logo (the series' for an episode, a channel's on a channel), or the name in large type when there's none. */
@Composable
fun TvPauseLogo(info: PauseInfo, modifier: Modifier = Modifier) {
    val u = Lockup.tvUnit
    if (info.logoUrl != null) {
        AsyncImage(
            info.logoUrl, info.name, modifier.widthIn(max = (644f * u).dp).heightIn(max = (230f * u).dp),
            contentScale = ContentScale.Fit, alignment = Alignment.TopStart,
        )
    } else {
        Text(
            info.name, modifier.widthIn(max = (900f * u).dp), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = Type.pageTitle.copy(fontWeight = FontWeight.ExtraBold),
        )
    }
}

/** The description and facts, on darkened glass, with what the stream is. */
@Composable
fun TvPausePanel(info: PauseInfo, badges: List<StreamBadge>, modifier: Modifier = Modifier) {
    val u = Lockup.tvUnit
    val spacing = (14f * u).dp
    Column(
        modifier.widthIn(max = (1150f * u).dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape((36f * u).dp))
            .padding(horizontal = (36f * u).dp, vertical = (30f * u).dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        if (info.heading != null) {
            Column(verticalArrangement = Arrangement.spacedBy((4f * u).dp)) {
                Text(info.heading, style = Type.title3.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                info.subheading?.let { Text(it, style = Type.calloutSemibold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        info.overview?.let { AutoScrollingText(it, visibleLines = 4) }
        val shown = if (info.showsBadges) badges else emptyList()
        if (info.facts.isNotEmpty() || shown.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
                if (info.facts.isNotEmpty()) Text(info.facts.joinToString(" · "), style = Type.callout, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
                shown.forEach { Badge(it) }
            }
        }
    }
}

/** "HD", "4K", "CC": filled, or outlined for captions, as the system shows them. */
data class StreamBadge(val text: String, val filled: Boolean = true)

/** What the stream is, as the system player shows it: picture size and range filled, captions outlined. */
fun streamBadges(player: Player, hasSubtitles: Boolean): List<StreamBadge> = buildList {
    val tracks = player.currentTracks.groups.filter { it.isSelected }
    val video = tracks.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
    val audio = tracks.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { g -> (0 until g.length).filter { g.isTrackSelected(it) }.map { g.getTrackFormat(it) } }
    val width = video?.width ?: 0
    if (width >= 3200) add(StreamBadge("4K")) else if (width >= 1200) add(StreamBadge("HD"))
    when {
        video?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || video?.codecs?.startsWith("dvh") == true -> add(StreamBadge("Dolby Vision"))
        video?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 || video?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> add(StreamBadge("HDR"))
    }
    if (audio.any { it.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC }) add(StreamBadge("Atmos"))
    if (hasSubtitles) add(StreamBadge("CC", filled = false))
}

@Composable
private fun Badge(badge: StreamBadge) {
    val u = Lockup.tvUnit
    val shape = RoundedCornerShape((5f * u).dp)
    Text(
        badge.text, style = Type.caption2Bold.copy(fontWeight = FontWeight.Black), color = if (badge.filled) Color.Black else Color.White,
        modifier = (if (badge.filled) Modifier.background(Color.White, shape) else Modifier.border((2f * u).dp, Color.White, shape))
            .padding(horizontal = (8f * u).dp, vertical = (3f * u).dp),
    )
}

/**
 * Text in a window of a few lines. When it runs longer, it waits, drifts slowly up to its last line, holds there, and glides
 * back to the top, over and over while it's on screen.
 */
@Composable
private fun AutoScrollingText(text: String, visibleLines: Int) {
    val style = Type.callout
    val window = with(LocalDensity.current) { (style.lineHeight.toPx() * visibleLines).toDp() }
    val scroll: ScrollState = rememberScrollState()
    // Slow enough to read along: 18 points a second, as on the Apple TV.
    val pointsPerSecond = 18f * Lockup.tvUnit * LocalDensity.current.density
    LaunchedEffect(text, scroll.maxValue) {
        scroll.scrollTo(0)
        val distance = scroll.maxValue
        if (distance <= 1) return@LaunchedEffect
        val millis = (distance / pointsPerSecond * 1000).toInt()
        while (true) {
            delay(3_000)
            scroll.animateScrollTo(distance, androidx.compose.animation.core.tween(millis, easing = androidx.compose.animation.core.LinearEasing))
            delay(3_000)
            scroll.animateScrollTo(0, androidx.compose.animation.core.tween(800))
        }
    }
    Box(
        Modifier.heightIn(max = window)
            // Fades an edge only while there's more text past it.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val top = if (scroll.value > 1) 0.14f else 0f
                val bottom = if (scroll.maxValue - scroll.value > 1) 0.86f else 1f
                drawRect(
                    Brush.verticalGradient(
                        0f to (if (top > 0f) Color.Transparent else Color.Black), top to Color.Black,
                        bottom to Color.Black, 1f to (if (bottom < 1f) Color.Transparent else Color.Black),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        Text(text, style = style, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.verticalScroll(scroll, enabled = false))
    }
}
