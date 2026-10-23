package com.astermediaplayer.astermediaplayer.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where playback is, for the timeline: read from the player a few times a second, only while something is looking. */
class PlayerClock {
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    /** Wanted to play (not paused by the viewer); false while paused, even when buffering. */
    var playing by mutableStateOf(false)

    fun read(player: Player) {
        position = player.currentPosition.coerceAtLeast(0L)
        duration = player.duration.takeIf { it > 0 } ?: 0L
        buffered = player.bufferedPosition.coerceAtLeast(0L)
        playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
    }
}

@Composable
fun rememberPlayerClock(player: Player): PlayerClock {
    val clock = remember { PlayerClock() }
    LaunchedEffect(player) {
        while (true) { clock.read(player); delay(250) }
    }
    return clock
}

/** "4:05", or "1:04:05" past an hour. */
fun clockText(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = total % 3600 / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * The timeline: a capsule of what's been played over what's buffered, with the time played and left under it. Dragging along
 * it scrubs, and a bubble above the playhead shows the frame there (when the server has made them) and the time; the player
 * seeks once it's let go. [canScrub] is false on a channel, where the bar only shows how far in.
 */
@Composable
fun Timeline(
    clock: PlayerClock,
    canScrub: Boolean,
    onScrubbing: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    preview: suspend (Double) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    var scrub by remember { mutableStateOf<Long?>(null) }
    val duration = max(clock.duration, 0L)
    val position = scrub ?: clock.position
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val buffered = if (duration > 0) (clock.buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(24.dp).then(
                if (canScrub) Modifier.pointerInput(duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (duration <= 0) return@awaitEachGesture
                        onScrubbing(true)
                        fun at(x: Float) = (x / size.width).coerceIn(0f, 1f) * duration
                        scrub = at(down.position.x).toLong()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            scrub = at(change.position.x).toLong()
                            change.consume()
                        }
                        scrub?.let(onSeek)
                        scrub = null
                        onScrubbing(false)
                    }
                } else Modifier,
            ),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val h = 6.dp.toPx()
                val y = (size.height - h) / 2
                val radius = CornerRadius(h / 2, h / 2)
                drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y), Size(size.width, h), radius)
                drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(0f, y), Size(size.width * buffered, h), radius)
                drawRoundRect(Color.White, Offset(0f, y), Size(size.width * fraction, h), radius)
            }
            scrub?.let { seconds ->
                // Above the playhead, kept inside the bar's ends; laid out outside the bar so it doesn't make it taller.
                Box(
                    Modifier.align(Alignment.TopStart).layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
                        val barWidth = constraints.maxWidth
                        val x = (barWidth * fraction - placeable.width / 2f).roundToInt().coerceIn(0, max(barWidth - placeable.width, 0))
                        layout(0, 0) { placeable.place(IntOffset(x, -placeable.height - 16.dp.roundToPx())) }
                    },
                ) { ScrubBubble(seconds / 1000.0, preview) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val style = Type.caption.copy(fontFeatureSettings = "tnum")
            Text(clockText(position), style = style, color = Color.White.copy(alpha = 0.8f))
            Text("-" + clockText(max(duration - position, 0)), style = style, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

/** The frame and time above the playhead while scrubbing. */
@Composable
private fun ScrubBubble(seconds: Double, preview: suspend (Double) -> Bitmap?) {
    var image by remember { mutableStateOf<Bitmap?>(null) }
    // A new frame every two seconds of timeline, not every pixel dragged.
    LaunchedEffect(seconds.toInt() / 2) { preview(seconds)?.let { image = it } }
    Column(Modifier.width(200.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        image?.let {
            Image(
                it.asImageBitmap(), null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(200.dp).height(200.dp * 9 / 16).shadow(8.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)),
            )
        }
        Text(
            clockText((seconds * 1000).toLong()), style = Type.callout.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = Color.White,
            modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Back 10 seconds, play or pause, forward 10, as the Apple apps' centre buttons; the skips are hidden on a channel. */
@Composable
fun CenterButtons(playing: Boolean, canSkip: Boolean, onSkip: (Int) -> Unit, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Skip(Icons.Default.Replay10, "Back 10 seconds", canSkip) { onSkip(-10) }
        Box(Modifier.size(64.dp).clip(CircleShape).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle), contentAlignment = Alignment.Center) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(44.dp))
        }
        Skip(Icons.Default.Forward10, "Forward 10 seconds", canSkip) { onSkip(10) }
    }
}

@Composable
private fun Skip(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).then(if (enabled) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { if (enabled) Icon(icon, description, tint = Color.White, modifier = Modifier.size(30.dp)) }
}

/** "10" with an arrow, briefly, over the side that was double-tapped. */
@Composable
fun SkipFlash(forward: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(horizontal = 60.dp), contentAlignment = if (forward) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(28.dp)) {
            Icon(if (forward) Icons.Default.Forward10 else Icons.Default.Replay10, null, tint = Color.White, modifier = Modifier.size(44.dp))
        }
    }
}

/** A pinch on the picture fills the screen (spreading) or fits it again (pinching), judged when the fingers are lifted. */
fun Modifier.pinchToFill(onFill: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        // Watched without consuming, so taps and drags on the same surface carry on as they were.
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var start = 0f
        var last = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.none { it.pressed }) break
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val spread = hypot(pressed[0].position.x - pressed[1].position.x, pressed[0].position.y - pressed[1].position.y)
                if (start == 0f) start = spread
                last = spread
            }
        }
        if (start > 0f) {
            val magnification = last / start
            if (magnification > 1.1f) onFill(true) else if (magnification < 0.9f) onFill(false)
        }
    }
}
