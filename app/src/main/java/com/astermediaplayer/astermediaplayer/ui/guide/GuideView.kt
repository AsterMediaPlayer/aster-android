package com.astermediaplayer.astermediaplayer.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.floor

/** A channel's row heading in the guide. */
data class GuideChannel(val id: String, val number: String?, val name: String, val logoUrl: String? = null)

/** A programme's cell: [title] over [subtitle], from [start] to [end] (seconds since 1970). */
data class GuideProgram(val id: String, val title: String, val subtitle: String?, val start: Double, val end: Double, val payload: Any? = null)

/** How far ahead the guide reaches, like a TV guide's week. */
const val GUIDE_DAYS = 7

private val DP_PER_MINUTE = 4.dp
private val ROW_HEIGHT = 64.dp
private val CHANNEL_WIDTH = 92.dp
private val HEADER_HEIGHT = 36.dp

/** The guide's start: [now] floored to the half hour. */
fun floorToHalfHour(now: Double): Double = floor(now / 1800.0) * 1800.0

/**
 * The programme guide the Channels tab and Live TV share: channels down the left, time across, a cell for each
 * programme as wide as it's long, and a marker where it's now. [programs] is asked for a channel's programmes between
 * two times as its row comes into view.
 */
@Composable
fun GuideView(
    channels: List<GuideChannel>,
    guideStart: Double,
    programs: suspend (GuideChannel, Double, Double) -> List<GuideProgram>,
    onProgram: (GuideChannel, GuideProgram) -> Unit,
    onChannel: ((GuideChannel) -> Unit)? = null,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
) {
    val guideEnd = guideStart + GUIDE_DAYS * 24 * 3600.0
    val totalWidth = DP_PER_MINUTE * ((guideEnd - guideStart) / 60.0).toFloat()
    val scroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val now = remember { System.currentTimeMillis() / 1000.0 }
    // Starts a little before now, so the programme on is in view with something before it.
    LaunchedEffect(Unit) { scroll.scrollTo(0) }

    Column(modifier.fillMaxSize()) {
        // The times, scrolling with the rows.
        Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
            Box(Modifier.width(CHANNEL_WIDTH), contentAlignment = Alignment.CenterStart) {
                Text("Today", style = Type.calloutSemibold, color = SecondaryText, modifier = Modifier.padding(start = Lockup.screenPadding))
            }
            Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                Box(Modifier.width(totalWidth).fillMaxHeight()) { TimeHeader(guideStart, guideEnd) }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = bottomPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(channels, key = { it.id }) { channel ->
                GuideRow(channel, guideStart, guideEnd, now, totalWidth, scroll, programs, onProgram, onChannel)
            }
        }
    }
}

@Composable
private fun BoxScope.TimeHeader(start: Double, end: Double) {
    val fmt = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val zone = remember { ZoneId.systemDefault() }
    var t = start
    // A label for each half hour, which composes cheaply (336 of them) because each is one small Text.
    while (t < end) {
        val x = DP_PER_MINUTE * ((t - start) / 60.0).toFloat()
        val time = Instant.ofEpochSecond(t.toLong()).atZone(zone)
        val label = if (time.hour == 0 && time.minute == 0) time.format(DateTimeFormatter.ofPattern("EEE")) + " " + fmt.format(time) else fmt.format(time)
        Text(label, style = Type.callout, color = SecondaryText, maxLines = 1, softWrap = false, modifier = Modifier.offset(x = x + 6.dp).align(Alignment.CenterStart).wrapContentWidth(unbounded = true))
        t += 1800.0
    }
}

@Composable
private fun GuideRow(
    channel: GuideChannel, start: Double, end: Double, now: Double, totalWidth: Dp, scroll: androidx.compose.foundation.ScrollState,
    programs: suspend (GuideChannel, Double, Double) -> List<GuideProgram>,
    onProgram: (GuideChannel, GuideProgram) -> Unit, onChannel: ((GuideChannel) -> Unit)?,
) {
    var row by remember(channel.id, start) { mutableStateOf<List<GuideProgram>?>(null) }
    LaunchedEffect(channel.id, start) { row = withContext(Dispatchers.Default) { programs(channel, start, end) } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Only the cells near the screen are made, as the row is a week long.
    val bucketPx = with(density) { 240.dp.toPx() }
    val bucket by remember { derivedStateOf { (scroll.value / bucketPx).toInt() } }
    val viewportPx = with(density) { 480.dp.toPx() }

    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
        ChannelHeading(channel, Modifier.width(CHANNEL_WIDTH), onChannel)
        Box(Modifier.weight(1f).horizontalScroll(scroll)) {
            Box(Modifier.width(totalWidth).fillMaxHeight()) {
                val cells = row
                if (cells != null) {
                    val lo = bucket * bucketPx - bucketPx * 3
                    val hi = bucket * bucketPx + viewportPx + bucketPx * 3
                    for (p in cells) {
                        val left = with(density) { (DP_PER_MINUTE * ((maxOf(p.start, start) - start) / 60.0).toFloat()).toPx() }
                        val width = DP_PER_MINUTE * ((minOf(p.end, end) - maxOf(p.start, start)) / 60.0).toFloat()
                        val widthPx = with(density) { width.toPx() }
                        if (left + widthPx < lo || left > hi) continue
                        key(p.id) { ProgramCell(channel, p, now, Modifier.offset(x = with(density) { left.toDp() }).width(width - 3.dp).fillMaxHeight(), onProgram) }
                    }
                } else Box(Modifier.fillMaxSize().padding(end = 8.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.04f)))
                // Where it's now.
                if (now in start..end) Box(Modifier.offset(x = DP_PER_MINUTE * ((now - start) / 60.0).toFloat()).width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

@Composable
private fun ChannelHeading(channel: GuideChannel, modifier: Modifier, onChannel: ((GuideChannel) -> Unit)?) {
    Row(
        modifier.fillMaxHeight().padding(end = 6.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f))
            .then(if (onChannel != null) Modifier.clickable { onChannel(channel) } else Modifier).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        channel.number?.let { Text(it, style = Type.calloutSemibold, color = SecondaryText) }
        if (channel.logoUrl != null) AsyncImage(model = channel.logoUrl, contentDescription = channel.name, contentScale = ContentScale.Fit, modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp))
        else Text(channel.name, style = Type.caption.copy(fontSize = 12.sp), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

@Composable
private fun ProgramCell(channel: GuideChannel, program: GuideProgram, now: Double, modifier: Modifier, onProgram: (GuideChannel, GuideProgram) -> Unit) {
    val airing = now >= program.start && now < program.end
    val past = program.end <= now
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = if (airing) 0.18f else 0.08f))
            .clickable(enabled = !past) { onProgram(channel, program) }.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(program.title, style = Type.calloutMedium.copy(fontSize = 14.sp, lineHeight = 18.sp), color = if (past) SecondaryText else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val time = remember(program.start, program.end) {
            val zone = ZoneId.systemDefault()
            "${timeFormat.format(Instant.ofEpochSecond(program.start.toLong()).atZone(zone))} – ${timeFormat.format(Instant.ofEpochSecond(program.end.toLong()).atZone(zone))}"
        }
        Text(program.subtitle ?: time, style = Type.caption, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
