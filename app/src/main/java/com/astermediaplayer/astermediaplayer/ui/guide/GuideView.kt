package com.astermediaplayer.astermediaplayer.ui.guide

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A channel's row heading in the guide. */
data class GuideChannel(val id: String, val number: String?, val name: String, val logoUrl: String? = null, val favorite: Boolean = false)

/** A programme's cell: [title] over [episodeTitle] (else its time), from [start] to [end] (seconds since 1970). */
data class GuideProgram(
    val id: String, val title: String, val episodeTitle: String?, val start: Double, val end: Double,
    /** Set to record: marked with a red dot. */
    val recording: Boolean = false, val payload: Any? = null,
)

// About an hour of guide across a phone held upright, as the Apple apps' phone layout.
private val CHANNEL_COLUMN = 88.dp
private val ROW_HEIGHT = 60.dp
private const val POINTS_PER_MINUTE = 4f
private val COLUMN_GAP = 6.dp
private val CELL_GAP = 4.dp
private val CELL_RADIUS = 8.dp
private val MINIMUM_VISIBLE_CELL = 80.dp
private val MINIMUM_CELL_WIDTH = 20.dp
private val HEADER_HEIGHT = 32.dp
private val CellGrey = Color(0xFF292929)
private val AiringGrey = Color(0xFF383838)
private val PlaceholderGrey = Color(0xFF1F1F1F)

fun floorToHalfHour(now: Double): Double = kotlin.math.floor(now / 1800.0) * 1800.0

private fun now(): Double = System.currentTimeMillis() / 1000.0
private val zone: ZoneId get() = ZoneId.systemDefault()
private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

fun clock(seconds: Double): String = timeFormat.format(Instant.ofEpochSecond(seconds.toLong()).atZone(zone))

/** "8:00 AM – 9:00 AM". */
fun timeRange(start: Double, end: Double?): String = if (end == null) clock(start) else "${clock(start)} – ${clock(end)}"

/** "Today", "Tomorrow", "Yesterday", else "Saturday Oct 18". */
fun dayLabel(seconds: Double): String {
    val day = Instant.ofEpochSecond(seconds.toLong()).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (day) {
        today -> "Today"; today.plusDays(1) -> "Tomorrow"; today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("EEEE MMM d", Locale.getDefault()))
    }
}

/**
 * The programme guide the Live TV and Channels pages share (the Apple apps' `GuideView`): channels down the side, time
 * across the top, a cell as wide as each programme is long. The strip of times is dragged sideways through time; a flick
 * carries on and settles on a quarter hour. [programs] gives a channel's row (null while it loads) and [ensure] asks for
 * it as the row comes into view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GuideView(
    channels: List<GuideChannel>,
    guideStart: Double,
    guideEnd: Double,
    programs: (GuideChannel) -> List<GuideProgram>?,
    ensure: (GuideChannel) -> Unit,
    onProgram: (GuideChannel, GuideProgram) -> Unit,
    onChannel: (GuideChannel) -> Unit,
    onFavorite: ((GuideChannel) -> Unit)? = null,
    emptyDetail: String = "Add a tuner or M3U source in the Jellyfin dashboard.",
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 60.dp,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val ppm = with(density) { POINTS_PER_MINUTE.dp.toPx() }
    // Where the window starts, in seconds after the guide's start; moved by the strip, and animated when it jumps.
    val window = remember { Animatable(max(0.0, floorToHalfHour(now()) - guideStart).toFloat()) }
    var timelineWidth by remember { mutableFloatStateOf(0f) }
    val visibleSeconds = if (ppm > 0) timelineWidth / ppm * 60f else 5400f
    fun clamped(offset: Float): Float {
        val latest = max(0f, (guideEnd - guideStart).toFloat() - visibleSeconds)
        return min(max(offset, 0f), latest)
    }
    fun jumpTo(absolute: Double) {
        scope.launch { window.animateTo(clamped((absolute - guideStart).toFloat()), tween(250)) }
    }
    // Rows build the cells near the window; the window moving by half hours rebuilds them, a drag in between only moves them.
    val bucket by remember { derivedStateOf { (window.value / 1800f).toInt() } }
    val days = remember(guideStart, guideEnd) {
        if (guideEnd - guideStart <= 26 * 3600) emptyList() else {
            val out = mutableListOf<LocalDate>()
            var day = Instant.ofEpochSecond(guideStart.toLong()).atZone(zone).toLocalDate()
            while (day.atStartOfDay(zone).toEpochSecond() < guideEnd) { out += day; day = day.plusDays(1) }
            out
        }
    }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The whole strip, padding included, takes the drag; the day picker and Now still take taps.
        val drag = rememberDraggableState { dx -> scope.launch { window.snapTo(clamped(window.value - dx / ppm * 60f)) } }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Lockup.screenPadding).padding(top = 10.dp)
                .draggable(drag, Orientation.Horizontal, onDragStopped = { velocity ->
                    // A flick carries on a little, and settles on the nearest quarter hour.
                    val coasted = window.value - velocity * 0.3f / ppm * 60f
                    val absolute = guideStart + clamped(coasted)
                    val snapped = kotlin.math.round(absolute / 900.0) * 900.0
                    window.animateTo(clamped((snapped - guideStart).toFloat()), tween(350))
                }),
            horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
        ) {
            Box(Modifier.width(CHANNEL_COLUMN).height(HEADER_HEIGHT), contentAlignment = Alignment.CenterStart) {
                val absolute = guideStart + window.value
                val current = clamped((floorToHalfHour(now()) - guideStart).toFloat())
                when {
                    days.isNotEmpty() -> DayPicker(days, dayLabel(absolute).let { if (it.contains(' ') && !it.startsWith("Today")) Instant.ofEpochSecond(absolute.toLong()).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())) else it }) { day ->
                        val time = Instant.ofEpochSecond(now().toLong()).atZone(zone)
                        val target = if (day == LocalDate.now(zone)) now() else day.atTime(time.hour, time.minute).atZone(zone).toEpochSecond().toDouble()
                        jumpTo(floorToHalfHour(target))
                    }
                    kotlin.math.abs(window.value - current) >= 60f -> SmallButton("Now", Icons.AutoMirrored.Filled.Undo) { jumpTo(floorToHalfHour(now())) }
                    else -> Text(dayLabel(absolute), style = Type.headline, color = SecondaryText)
                }
            }
            // The times, a label each half hour, and a red marker where it's now.
            Box(Modifier.weight(1f).height(HEADER_HEIGHT).clip(RoundedCornerShape(0.dp)).onSizeChanged { if (kotlin.math.abs(it.width - timelineWidth) > 1) timelineWidth = it.width.toFloat() }) {
                val first = max(0, (((window.value - 1800f) / 1800f).toInt()))
                val count = (visibleSeconds / 1800f).toInt() + 3
                for (i in first until first + count) {
                    val at = guideStart + i * 1800.0
                    if (at >= guideEnd) break
                    Text(
                        clock(at), style = Type.callout.copy(fontWeight = FontWeight.SemiBold), color = SecondaryText, maxLines = 1, softWrap = false,
                        modifier = Modifier.align(Alignment.CenterStart).offset { IntOffset(((at - guideStart - window.value) / 60.0 * ppm).roundToInt(), 0) }.wrapContentUnbounded(),
                    )
                }
                val nowSeconds = now()
                if (nowSeconds >= guideStart + window.value) {
                    Box(
                        Modifier.align(Alignment.CenterStart).offset { IntOffset(((nowSeconds - guideStart - window.value) / 60.0 * ppm).roundToInt(), 0) }
                            .width(3.dp).height(28.dp).background(Color.Red),
                    )
                }
            }
        }

        if (channels.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("No channels", style = Type.title3)
                Text(emptyDetail, style = Type.callout, color = SecondaryText, modifier = Modifier.padding(top = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = Lockup.screenPadding, end = Lockup.screenPadding, top = 20.dp, bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(channels, key = { it.id }) { channel ->
                    // Fetches the row, and a stretch of rows ahead too, so scrolling rarely reaches one that is still loading.
                    LaunchedEffect(channel.id) {
                        ensure(channel)
                        val index = channels.indexOf(channel)
                        channels.drop(index + 1).take(10).forEach(ensure)
                    }
                    GuideRow(
                        channel, programs(channel), guideStart, guideEnd, { window.value }, bucket, visibleSeconds, ppm,
                        onProgram = { onProgram(channel, it) }, onChannel = { onChannel(channel) }, onFavorite = onFavorite?.let { f -> { f(channel) } },
                    )
                }
            }
        }
    }
}

/** Content wider than the box it's in, drawn from its left edge (a time label crossing the strip's end). */
private fun Modifier.wrapContentUnbounded(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(maxWidth = androidx.compose.ui.unit.Constraints.Infinity))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

@Composable
private fun SmallButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).combinedClickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(label, style = Type.segmented.copy(fontSize = 14.sp), maxLines = 1)
    }
}

/** The day label that opens the days the guide reaches: today goes back to now, another day opens at the same time of day. */
@Composable
private fun DayPicker(days: List<LocalDate>, label: String, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).combinedClickable(onClick = { open = true }).padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = Type.segmented.copy(fontSize = 14.sp), maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            days.forEach { day ->
                val seconds = day.atStartOfDay(zone).toEpochSecond().toDouble() + 12 * 3600
                DropdownMenuItem(text = { Text(dayLabel(seconds)) }, onClick = { open = false; onPick(day) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideRow(
    channel: GuideChannel,
    programs: List<GuideProgram>?,
    guideStart: Double,
    guideEnd: Double,
    window: () -> Float,
    bucket: Int,
    visibleSeconds: Float,
    ppm: Float,
    onProgram: (GuideProgram) -> Unit,
    onChannel: () -> Unit,
    onFavorite: (() -> Unit)?,
) {
    val density = LocalDensity.current
    // Programmes near the window, an hour or so either side so they're there as it moves.
    val nearby = remember(programs, bucket, visibleSeconds) {
        val lower = guideStart + bucket * 1800.0 - 5400
        val upper = guideStart + bucket * 1800.0 + visibleSeconds + 1800 + 5400
        programs.orEmpty().filter { it.end > lower && it.start < upper && it.end > guideStart }
    }
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
        ChannelCell(channel, Modifier.width(CHANNEL_COLUMN).fillMaxHeight(), onChannel, onFavorite)
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(0.dp))) {
            when {
                programs == null -> Placeholder(null, Modifier.offset { IntOffset(0, 0) }.width(with(density) { (90 * ppm).toDp() }), onChannel)
                nearby.isEmpty() -> Placeholder("No guide data", Modifier.width(with(density) { (90 * ppm).toDp() }), onChannel)
            }
            val nowSeconds = now()
            nearby.forEach { program ->
                androidx.compose.runtime.key(program.id) {
                    val start = max(program.start, guideStart)
                    val end = max(program.end, start + 300)
                    val widthPx = ((end - start) / 60.0 * ppm - with(density) { CELL_GAP.toPx() }).toFloat()
                    ProgramCell(
                        program, start, end, nowSeconds, widthPx, window, guideStart, ppm,
                        // As wide as the programme is long, whatever the room: placed by its time against the window, and clipped by the row.
                        Modifier.layout { measurable, constraints ->
                            val width = max(widthPx, with(density) { MINIMUM_CELL_WIDTH.toPx() }).roundToInt()
                            val x = ((start - guideStart - window()) / 60.0 * ppm).roundToInt()
                            val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width, constraints.maxHeight))
                            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x, 0) }
                        },
                    ) { onProgram(program) }
                }
            }
        }
    }
}

@Composable
private fun Placeholder(text: String?, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().clip(RoundedCornerShape(CELL_RADIUS)).background(PlaceholderGrey).combinedClickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (text != null) Text(text, style = Type.callout, color = SecondaryText) else CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCell(channel: GuideChannel, modifier: Modifier, onClick: () -> Unit, onFavorite: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxSize().clip(RoundedCornerShape(CELL_RADIUS)).background(CellGrey)
                .combinedClickable(onClick = onClick, onLongClick = { if (onFavorite != null) menu = true })
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(channel.number.orEmpty(), style = Type.caption2Bold, color = SecondaryText, maxLines = 1, modifier = Modifier.width(26.dp))
            if (channel.logoUrl != null) {
                AsyncImage(model = channel.logoUrl, contentDescription = channel.name, contentScale = ContentScale.Fit, modifier = Modifier.weight(1f).fillMaxHeight())
            } else {
                Text(channel.name, style = Type.caption.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (channel.favorite) "Remove from Favorites" else "Add to Favorites") }, onClick = { menu = false; onFavorite?.invoke() })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProgramCell(
    program: GuideProgram, start: Double, end: Double, nowSeconds: Double, widthPx: Float, window: () -> Float, guideStart: Double, ppm: Float,
    modifier: Modifier, onClick: () -> Unit,
) {
    val airing = nowSeconds >= program.start && nowSeconds < program.end
    // Share of the cell that has already aired.
    val aired = if (airing && end > start) ((nowSeconds - start) / (end - start)).coerceIn(0.0, 1.0).toFloat() else null
    val subtitle = program.episodeTitle?.takeIf { it != program.title } ?: timeRange(program.start, program.end)
    val density = LocalDensity.current
    Box(modifier.clip(RoundedCornerShape(CELL_RADIUS)).background(if (airing) AiringGrey else CellGrey).combinedClickable(onClick = onClick)) {
        if (aired != null) Box(Modifier.fillMaxHeight().fillMaxWidth(aired).background(Color.White.copy(alpha = 0.12f)))
        Column(
            Modifier.fillMaxSize()
                // The text stays in view when the cell's start is scrolled out of the window to the left.
                .layout { measurable, constraints ->
                    val hidden = max(0f, (guideStart + window() - start).toFloat() / 60f * ppm)
                    val inset = min(hidden, max(0f, widthPx - with(density) { MINIMUM_VISIBLE_CELL.toPx() })).roundToInt()
                    val placeable = measurable.measure(constraints.copy(maxWidth = max(0, constraints.maxWidth - inset), minWidth = 0))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(inset, 0) }
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (program.recording) Icon(Icons.Default.FiberManualRecord, null, tint = Color.Red, modifier = Modifier.size(12.dp))
                Text(program.title, style = Type.caption.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(subtitle, style = Type.caption.copy(fontSize = 11.sp, lineHeight = 13.sp), color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
