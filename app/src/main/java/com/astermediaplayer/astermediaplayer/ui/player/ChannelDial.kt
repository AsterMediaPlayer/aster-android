package com.astermediaplayer.astermediaplayer.ui.player

import android.view.SoundEffectConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.ui.livetv.programProgress
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The dial's arithmetic, kept apart so it can be tested. The Kotlin counterpart of `ChannelDialMath`. */
object ChannelDialMath {
    /** Drag needed to pass one channel, in dp. */
    const val DETENT = 40f
    /** Most channels a flick spins on by, past where the finger let go. */
    const val FLING_CAP = 8
    /** How fast (dp a second) a finger must be moving as it lets go to spin the dial on. */
    const val FLING_SPEED = 900f
    /** How long the dial waits, still, before tuning: time to lift the finger and take another turn. */
    const val SETTLE_MS = 1000L

    /** Channels passed by a drag of [translation]: up (negative) is towards higher numbers, as with CH+. */
    fun steps(translation: Float, spacing: Float = DETENT): Int = if (spacing <= 0f) 0 else (-translation / spacing).toInt()

    /** How many more channels a flick carries on by: faster flicks go further, at most [cap] either way; none unless really flicking. */
    fun flingSteps(velocity: Float, spacing: Float = DETENT, cap: Int = FLING_CAP): Int {
        if (abs(velocity) < FLING_SPEED) return 0
        val extra = (-velocity * 0.3f / spacing).toInt()
        return extra.coerceIn(-cap, cap)
    }

    /** The pause before each of a flick's [count] steps, in ms: quick at first, slowing to a stop. */
    fun flingDelays(count: Int): List<Long> = (0 until count).map { i ->
        val progress = if (count == 1) 1f else i.toFloat() / (count - 1)
        40L + (progress * progress * 100).toLong()
    }
}

/** The row of channels the dial goes through, and which one it's on (the Kotlin counterpart of `ChannelFlickModel`). */
private class FlickModel(val channels: List<BaseItemDto>, currentId: UUID) {
    var selected by mutableIntStateOf(channels.indexOfFirst { it.id == currentId }.coerceAtLeast(0))

    val selectedChannel: BaseItemDto? get() = channels.getOrNull(selected)

    /** Moves along the row, going round from the last to the first. */
    fun move(step: Int) {
        if (channels.isEmpty()) return
        selected = ((selected + step) % channels.size + channels.size) % channels.size
    }

    /** The channel [offset] places from the selected one, going round; null when the row is too short for it. */
    fun neighbour(offset: Int): BaseItemDto? {
        if (channels.isEmpty()) return null
        val reach = if (offset > 0) channels.size / 2 else (channels.size - 1) / 2
        if (abs(offset) > reach) return null
        return channels[((selected + offset) % channels.size + channels.size) % channels.size]
    }
}

/**
 * Changing channel: a ridged dial on the right edge of the picture, like the tuning wheel on an old set. Drag it up or
 * down and it clicks through the channels, a tick (felt, and heard unless the phone is on silent) at each one, with
 * the channel's number and what's on beside it; flick it and it spins on, slowing down. Let go and it tunes, with a
 * firmer thunk, and slides away. It's only out while tuning, or for a moment after the screen's touched, with the
 * controls. The Kotlin counterpart of `ChannelDialOverlay`.
 */
@Composable
fun ChannelDialOverlay(
    channels: List<BaseItemDto>,
    playingId: UUID,
    controlsShown: Boolean,
    onActivity: () -> Unit,
    onTune: (UUID) -> Unit,
    onTuned: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (channels.size < 2) return
    val model = remember(channels) { FlickModel(channels, playingId) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val detentPx = with(density) { ChannelDialMath.DETENT.dp.toPx() }
    val flingSpeedPx = with(density) { ChannelDialMath.FLING_SPEED.dp.toPx() }

    var held by remember { mutableStateOf(false) }
    var spinning by remember { mutableStateOf(false) }
    var tunePending by remember { mutableStateOf(false) }
    var turned by remember { mutableFloatStateOf(0f) }
    var dragged by remember { mutableFloatStateOf(0f) }
    var applied by remember { mutableIntStateOf(0) }
    var spin by remember { mutableStateOf<Job?>(null) }
    var tuneJob by remember { mutableStateOf<Job?>(null) }
    val tuning = held || spinning || tunePending
    val out = tuning || controlsShown

    fun tick(direction: Int, turnRidges: Boolean) {
        model.move(direction)
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        view.playSoundEffect(SoundEffectConstants.CLICK)
        if (turnRidges) turned -= direction * detentPx
    }

    fun tune() {
        tunePending = false
        val picked = model.selectedChannel ?: return
        // Back where it started: nothing to change, and the dial stays out with the controls.
        if (picked.id == playingId) return
        onTune(picked.id)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onTuned()
    }

    fun scheduleTune() {
        tuneJob?.cancel()
        tunePending = true
        tuneJob = scope.launch {
            delay(ChannelDialMath.SETTLE_MS)
            if (!held) tune()
        }
    }

    // Another channel playing (changed elsewhere) puts the dial back on it.
    LaunchedEffect(playingId) { if (!tuning) model.selected = channels.indexOfFirst { it.id == playingId }.coerceAtLeast(0) }

    Row(modifier.fillMaxSize().padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(tuning, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut()) {
            model.selectedChannel?.let { ChannelCard(model, it, it.id == playingId) }
        }
        val slide by animateDpAsStateCompat(if (out) 0f else 90f)
        Box(
            Modifier.width(46.dp).fillMaxHeight(0.6f).offset(x = slide.dp)
                .draggable(
                    orientation = Orientation.Vertical,
                    enabled = out,
                    onDragStarted = {
                        held = true; applied = 0; dragged = 0f
                        spin?.cancel(); spinning = false; tuneJob?.cancel(); tunePending = false
                    },
                    state = rememberDraggableState { delta ->
                        onActivity()
                        dragged += delta
                        turned += delta
                        val steps = ChannelDialMath.steps(dragged, detentPx)
                        while (applied != steps) {
                            val direction = if (steps > applied) 1 else -1
                            applied += direction
                            tick(direction, turnRidges = false)
                        }
                    },
                    onDragStopped = { velocity ->
                        held = false
                        onActivity()
                        // The ridges settle into the detent of the channel picked, so what's shown is what tunes.
                        turned -= (dragged % detentPx)
                        val extra = if (abs(velocity) >= flingSpeedPx) ChannelDialMath.flingSteps(velocity / density.density, ChannelDialMath.DETENT) else 0
                        if (extra == 0) scheduleTune() else {
                            val direction = if (extra > 0) 1 else -1
                            spinning = true
                            spin = scope.launch {
                                for (d in ChannelDialMath.flingDelays(abs(extra))) { delay(d); tick(direction, turnRidges = true) }
                                spinning = false
                                scheduleTune()
                            }
                        }
                    },
                )
                .scale(if (held) 1.06f else 1f),
        ) { DialWheel(turned, held, Modifier.fillMaxSize()) }
    }
}

@Composable
private fun animateDpAsStateCompat(target: Float): State<Float> = androidx.compose.animation.core.animateFloatAsState(target, tween(350), label = "dial")

/** The wheel: a drum of ridges that turns with the finger, bunching and darkening towards its ends as a cylinder's do. */
@Composable
private fun DialWheel(turned: Float, held: Boolean, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    Box(
        modifier.clip(CircleShape)
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.5f to Color(0xFF383838).copy(alpha = 0.85f), 1f to Color.Black.copy(alpha = 0.85f)))
            .border(1.dp, Color.White.copy(alpha = if (held) 0.35f else 0.15f), CircleShape),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.height / 2
            val centre = size.height / 2
            val spacing = with(density) { 14.dp.toPx() }
            val angleStep = spacing / radius
            val phase = (turned / radius) % angleStep
            val count = ((Math.PI / 2) / angleStep).toInt() + 1
            for (index in -count..count) {
                val angle = index * angleStep + phase
                if (abs(angle) >= Math.PI / 2) continue
                val facing = cos(angle.toDouble()).toFloat()
                val y = centre + radius * sin(angle.toDouble()).toFloat()
                val thickness = max(0.6f, 2.6f * facing) * density.density
                val inset = size.width * (0.18f + 0.12f * (1 - facing))
                drawRoundRect(
                    Color.White.copy(alpha = 0.15f + 0.6f * facing * facing), Offset(inset, y - thickness / 2),
                    Size(size.width - inset * 2, thickness), CornerRadius(thickness / 2),
                )
            }
            // The notch at the middle marks the channel picked.
            val notch = Path().apply {
                moveTo(0f, centre - 6.dp.toPx()); lineTo(7.dp.toPx(), centre); lineTo(0f, centre + 6.dp.toPx()); close()
            }
            drawPath(notch, accent)
        }
    }
}

/** The channel the dial's on: a big number, the channel, and what's on, with the ones either side faded above and below. */
@Composable
private fun ChannelCard(model: FlickModel, channel: BaseItemDto, isPlaying: Boolean) {
    val app = LocalContext.current.applicationContext as AsterApp
    Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Neighbour(model.neighbour(1), channel)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                channel.channelNumber?.let { Text(it, style = Type.pageTitle.copy(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold), color = Color.White) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(channel.name.orEmpty(), style = Type.headline, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isPlaying) Text("Watching", style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
                }
                app.repository.channelLogoUrl(channel, 300)?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(56.dp, 32.dp))
                }
            }
            channel.currentProgram?.let { program ->
                Text(program.name.orEmpty(), style = Type.calloutSemibold.copy(fontSize = 15.sp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                program.episodeTitle?.takeIf { it != program.name }?.let { Text(it, style = Type.caption, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                programProgress(program)?.let { p ->
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                        Box(Modifier.fillMaxWidth(p.coerceIn(0.03f, 1f)).height(4.dp).clip(CircleShape).background(Color.White))
                    }
                }
            }
        }
        Neighbour(model.neighbour(-1), channel)
    }
}

@Composable
private fun Neighbour(channel: BaseItemDto?, current: BaseItemDto) {
    if (channel == null || channel.id == current.id) return
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        channel.channelNumber?.let { Text(it, style = Type.calloutSemibold, color = Color.White.copy(alpha = 0.55f)) }
        Text(channel.name.orEmpty(), style = Type.callout, color = Color.White.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
