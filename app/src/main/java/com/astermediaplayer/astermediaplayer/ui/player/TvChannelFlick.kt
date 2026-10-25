package com.astermediaplayer.astermediaplayer.ui.player

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.core.snap
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.channels.ChannelGroups
import com.astermediaplayer.astermediaplayer.data.channels.ChannelTuner
import com.astermediaplayer.astermediaplayer.data.channels.PlaylistChannel
import com.astermediaplayer.astermediaplayer.data.channels.ProgramRole
import com.astermediaplayer.astermediaplayer.ui.livetv.programProgress
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/** What's on a channel in the row: the Kotlin counterpart of the Apple TV's `FlickProgram`. */
data class FlickProgram(
    val title: String,
    /** The episode, as "S2, E5 · Name", or its name alone. */
    val subtitle: String?,
    val overview: String?,
    val timeRange: String?,
    /** How far through it, 0..1, now. */
    val progress: Float?,
    val minutesLeft: Int?,
    val imageUrl: String?,
    /** The library item on, for Go to Episode; null on Live TV, whose programmes aren't in the library. */
    val itemId: UUID? = null,
    val isMovie: Boolean = false,
)

/** A channel in the row. */
data class FlickChannel(
    val id: String,
    val number: String?,
    val name: String,
    val logoUrl: String?,
    val bugUrl: String? = null,
    val now: FlickProgram?,
    val group: String? = null,
)

/** The row of channels and which is picked (the Kotlin counterpart of `ChannelFlickModel`), going round from the last to the first. */
class TvFlickModel(val channels: List<FlickChannel>, val currentId: String) {
    var selected by mutableIntStateOf(channels.indexOfFirst { it.id == currentId }.coerceAtLeast(0))
    /** Go to Episode is picked, above the row, rather than the channel. */
    var onGoTo by mutableStateOf(false)

    val selectedChannel: FlickChannel? get() = channels.getOrNull(selected)
    val canGoTo: Boolean get() = selectedChannel?.now?.itemId != null

    fun move(step: Int) {
        if (channels.isEmpty()) return
        onGoTo = false
        selected = ((selected + step) % channels.size + channels.size) % channels.size
    }

    fun pickGoTo(on: Boolean) { onGoTo = on && canGoTo }

    /** The channel [offset] places from the picked one, going round; null when the row is too short for it (each shows once). */
    fun neighbour(offset: Int): FlickChannel? {
        if (channels.isEmpty()) return null
        val reach = if (offset > 0) channels.size / 2 else (channels.size - 1) / 2
        if (kotlin.math.abs(offset) > reach) return null
        return channels[((selected + offset) % channels.size + channels.size) % channels.size]
    }

    companion object {
        // The SDK's dates are in the device's own zone.
        private fun LocalDateTime.epoch() = atZone(ZoneId.systemDefault()).toEpochSecond()

        private fun clock(seconds: Double): String =
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(java.time.Instant.ofEpochSecond(seconds.toLong()).atZone(ZoneId.systemDefault()))

        /** Live TV's channels, with what's on each now. */
        fun live(channels: List<BaseItemDto>, repo: AsterRepository, currentId: String): TvFlickModel = TvFlickModel(
            channels.map { channel ->
                val program = channel.currentProgram
                val end = program?.endDate?.epoch()
                val left = end?.let { ((it - System.currentTimeMillis() / 1000.0) / 60).toInt().coerceAtLeast(1) }
                FlickChannel(
                    id = channel.id.toString(), number = channel.channelNumber, name = channel.name.orEmpty(), logoUrl = repo.channelLogoUrl(channel, 300),
                    now = program?.let {
                        FlickProgram(
                            title = it.name.orEmpty(), subtitle = it.episodeTitle?.takeIf { t -> t != it.name }, overview = it.overview,
                            timeRange = com.astermediaplayer.astermediaplayer.ui.livetv.timeRange(it), progress = programProgress(it), minutesLeft = left,
                            imageUrl = repo.imageUrl(it, ImageType.PRIMARY, 480),
                        )
                    },
                )
            },
            currentId,
        )

        /** The Channels tab's channels, from their schedules as they stand now. */
        fun playlists(repo: AsterRepository, currentId: String): TvFlickModel {
            val now = PlaylistChannel.now()
            val grouped = ChannelGroups.names(ChannelTuner.orderedChannels).isNotEmpty()
            return TvFlickModel(
                ChannelTuner.orderedChannels.map { channel ->
                    val airing = channel.airing(now)
                    val inBreak = channel.segment(now)?.role?.isBreak == true
                    fun describe(item: com.astermediaplayer.astermediaplayer.data.channels.ChannelItem): Pair<String, String?> {
                        if (!item.isEpisode) return item.displayTitle to null
                        val code = item.parentIndexNumber?.let { s -> item.indexNumber?.let { e -> "S$s, E$e" } }
                        return (item.seriesName ?: item.displayTitle) to listOfNotNull(code, item.name).joinToString(" · ").ifEmpty { null }
                    }
                    val program = airing?.let { a ->
                        // In a break: what's coming up, from now to its start.
                        val shown = if (inBreak) channel.airing(a.end) ?: a else a
                        val (title, subtitle) = describe(shown.item)
                        val dto = shown.item.source as? BaseItemDto
                        val start = if (inBreak) channel.segments(a.start, now).firstOrNull { it.role.isBreak }?.start ?: a.start else a.start
                        val end = if (inBreak) shown.start else a.end
                        FlickProgram(
                            title = if (inBreak) "Break" else title,
                            subtitle = if (inBreak) "Up next: " + listOfNotNull(title, subtitle).joinToString(" · ") else subtitle,
                            overview = dto?.overview,
                            timeRange = "${clock(start)} – ${clock(end)}",
                            progress = if (end > start && now in start..end) ((now - start) / (end - start)).toFloat() else null,
                            minutesLeft = if (end > now) ((end - now) / 60).toInt().coerceAtLeast(1) else null,
                            imageUrl = dto?.let { repo.imageUrl(it, ImageType.BACKDROP, 480) ?: repo.imageUrl(it, ImageType.PRIMARY, 480) },
                            itemId = dto?.id, isMovie = !shown.item.isEpisode,
                        )
                    }
                    val picture = channel.logoUrl(repo, 560)
                    FlickChannel(
                        id = channel.id, number = "${channel.number}", name = channel.name,
                        logoUrl = if (channel.showsLogo) picture else null,
                        bugUrl = if (channel.showsBug || channel.showsLogo) picture else null,
                        now = program, group = if (grouped) channel.group ?: ChannelGroups.OTHER else null,
                    )
                },
                currentId,
            )
        }
    }
}

/**
 * The channel row the TV player opens with Up (the Apple TV's `ChannelFlickBar`): what's on the picked channel across the top,
 * and the channels either side of it in a row beneath, in a pane of glass over the picture, which plays on. The remote is the
 * player's own; this only draws.
 */
@Composable
fun TvChannelFlick(model: TvFlickModel, modifier: Modifier = Modifier) {
    val u = Lockup.tvUnit
    fun pt(points: Float) = (points * u).dp
    val shape = RoundedCornerShape(pt(44f))
    Column(
        modifier.fillMaxSize().padding(start = pt(70f), end = pt(70f), top = pt(60f), bottom = pt(50f)),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Brush.verticalGradient(listOf(Color(0xE61E1B1A), Color(0xE6141212))))
                .border(BorderStroke(pt(1f), Color.White.copy(alpha = 0.16f)), shape).padding(pt(40f)),
            verticalArrangement = Arrangement.spacedBy(pt(30f)),
        ) {
            model.selectedChannel?.let { channel ->
                AnimatedContent(channel, transitionSpec = { (fadeIn() togetherWith fadeOut()).using(SizeTransform(clip = false) { _, _ -> snap() }) }, label = "flickDetail") { picked ->
                    FlickDetail(picked, isCurrent = picked.id == model.currentId, isOnGoTo = model.onGoTo)
                }
            }
            // The group of the channel picked, when channels have groups: it changes as flicking crosses into the next group.
            // Its line is kept for every channel once any has a group, so the pane is the same height whichever is picked.
            if (model.channels.any { it.group != null }) model.selectedChannel?.let { picked ->
                Text(picked.group ?: " ", style = Type.calloutSemibold, color = SecondaryText, modifier = Modifier.fillMaxWidth().padding(bottom = pt(-14f).coerceAtLeast(0.dp)), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(pt(24f), Alignment.CenterHorizontally)) {
                for (offset in -2..2) {
                    val channel = model.neighbour(offset)
                    if (channel != null) FlickCard(channel, isSelected = offset == 0, isCurrent = channel.id == model.currentId)
                    else Spacer(Modifier.width(pt(300f)).height(pt(150f)))
                }
            }
        }
    }
}

@Composable
private fun FlickDetail(channel: FlickChannel, isCurrent: Boolean, isOnGoTo: Boolean) {
    val u = Lockup.tvUnit
    fun pt(points: Float) = (points * u).dp
    // Tall enough for a channel with every line, so the pane (anchored at the bottom) keeps its top edge whichever is picked.
    Row(Modifier.fillMaxWidth().heightInMin(pt(340f)), horizontalArrangement = Arrangement.spacedBy(pt(36f))) {
        channel.now?.imageUrl?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.width(pt(400f)).height(pt(225f)).clip(RoundedCornerShape(pt(12f))).background(Color.White.copy(alpha = 0.08f)))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(pt(10f))) {
            Row(horizontalArrangement = Arrangement.spacedBy(pt(14f))) {
                channel.number?.let { Text(it, style = Type.headline, color = Color.White.copy(alpha = 0.7f)) }
                Text(channel.name, style = Type.headline, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            }
            Text(channel.now?.title ?: "No programme information", style = Type.pageTitle.copy(fontSize = pt(40f).value.sp, lineHeight = pt(48f).value.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
            channel.now?.subtitle?.let { Text(it, style = Type.calloutSemibold, maxLines = 1, color = Color.White, overflow = TextOverflow.Ellipsis) }
            channel.now?.overview?.let { Text(it, style = Type.callout, color = Color.White.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            channel.now?.let { program ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(pt(16f))) {
                    program.timeRange?.let { Text(it, style = Type.callout, color = Color.White.copy(alpha = 0.75f)) }
                    program.progress?.let { FlickProgress(it, Modifier.width(pt(220f)).height(pt(6f))) }
                    program.minutesLeft?.let { Text(if (it >= 60) "${it / 60} hr, ${it % 60} min left" else "$it min left", style = Type.callout, color = Color.White.copy(alpha = 0.75f)) }
                }
            }
            Row(Modifier.padding(top = pt(4f)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(pt(28f))) {
                channel.now?.takeIf { it.itemId != null }?.let { GoToPill(if (it.isMovie) "Go to Movie" else "Go to Episode", isOnGoTo) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(pt(10f))) {
                    val dim = isCurrent || isOnGoTo
                    Icon(if (isCurrent) Icons.Default.PlayArrow else Icons.Default.PlayCircle, null, tint = if (dim) Color.White.copy(alpha = 0.6f) else Color.White, modifier = Modifier.size(pt(28f)))
                    Text(if (isCurrent) "Watching" else "Press OK to Watch", style = Type.calloutSemibold, color = if (dim) Color.White.copy(alpha = 0.6f) else Color.White)
                }
            }
        }
        // The bug sits in the top right beside the channel and what's on.
        channel.bugUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, alignment = Alignment.TopEnd, modifier = Modifier.width(pt(280f)).height(pt(180f))) }
    }
}

private fun Modifier.heightInMin(min: androidx.compose.ui.unit.Dp): Modifier = this.then(Modifier.defaultMinSize(minHeight = min))

/** Go to Episode (or Movie): white when picked, with Up's way to it shown otherwise. */
@Composable
private fun GoToPill(title: String, picked: Boolean) {
    val u = Lockup.tvUnit
    Row(
        Modifier.scale(if (picked) 1.05f else 1f).clip(CircleShape).background(if (picked) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.14f))
            .padding(horizontal = (24f * u).dp, vertical = (12f * u).dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((10f * u).dp),
    ) {
        Icon(if (picked) Icons.Default.PlayArrow else Icons.Default.KeyboardArrowUp, null, tint = if (picked) Color.Black else Color.White, modifier = Modifier.size((28f * u).dp))
        Text(title, style = Type.calloutSemibold, color = if (picked) Color.Black else Color.White, maxLines = 1)
    }
}

@Composable
private fun FlickProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(CircleShape).background(Color.White))
    }
}

/** A channel in the row: its logo or name, number, and what's on. */
@Composable
private fun FlickCard(channel: FlickChannel, isSelected: Boolean, isCurrent: Boolean) {
    val u = Lockup.tvUnit
    fun pt(points: Float) = (points * u).dp
    val shape = RoundedCornerShape(pt(14f))
    Column(
        Modifier.width(pt(300f)).height(pt(150f)).scale(if (isSelected) 1.08f else 1f)
            .clip(shape).background(Color.White.copy(alpha = if (isSelected) 0.22f else 0.08f))
            .border(BorderStroke(pt(3f), Color.White.copy(alpha = if (isSelected) 0.9f else 0f)), shape).padding(pt(16f)),
        verticalArrangement = Arrangement.spacedBy(pt(8f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(pt(10f))) {
            channel.number?.let { Text(it, style = Type.calloutSemibold.copy(fontWeight = FontWeight.Bold), color = Color.White) }
            if (channel.logoUrl != null) AsyncImage(model = channel.logoUrl, contentDescription = channel.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.height(pt(36f)).weight(1f))
            else Text(channel.name, style = Type.calloutSemibold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (isCurrent) Icon(Icons.Default.PlayArrow, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(pt(22f)))
        }
        Text(channel.now?.title ?: " ", style = Type.caption, color = Color.White.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        channel.now?.progress?.let { FlickProgress(it, Modifier.fillMaxWidth().height(pt(4f))) }
    }
}
