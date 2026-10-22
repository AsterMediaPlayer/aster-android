package com.astermediaplayer.astermediaplayer.ui.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.ui.audiobooks.Segmented
import com.astermediaplayer.astermediaplayer.ui.components.LandscapeCard
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

private enum class LiveSection(val label: String) { OnNow("On Now"), Channels("Channels"), Recordings("Recordings") }

/** Live TV: what's on now, the channel list and recordings. */
@Composable
fun LiveTvScreen(onPlay: (UUID) -> Unit, modifier: Modifier = Modifier, topInset: Dp = 0.dp) {
    val app = LocalContext.current.applicationContext as AsterApp
    val repo = app.repository
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf(LiveSection.OnNow) }
    var channels by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var onNow by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var movies by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var sports by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var kids by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var news by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var recordings by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            coroutineScope {
                val list = async { repo.liveChannels() }
                val recommended = async { runCatching { repo.recommendedPrograms(30) }.getOrDefault(emptyList()) }
                val m = async { runCatching { repo.recommendedPrograms(20, movie = true) }.getOrDefault(emptyList()) }
                val s = async { runCatching { repo.recommendedPrograms(20, sports = true) }.getOrDefault(emptyList()) }
                val k = async { runCatching { repo.recommendedPrograms(20, kids = true) }.getOrDefault(emptyList()) }
                val n = async { runCatching { repo.recommendedPrograms(20, news = true) }.getOrDefault(emptyList()) }
                channels = list.await()
                onNow = recommended.await(); movies = m.await(); sports = s.await(); kids = k.await(); news = n.await()
            }
        } catch (e: Exception) { error = e.message ?: "Live TV isn't available" }
        loading = false
        recordings = runCatching { repo.recordings() }.getOrDefault(emptyList())
    }

    LazyColumn(
        modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(top = 8.dp + topInset, bottom = TabBarClearance), verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = Lockup.screenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Live TV", style = Type.pageTitle.copy(fontSize = 34.sp, lineHeight = 41.sp))
                Segmented(LiveSection.entries.map { it.label }, section.ordinal) { section = LiveSection.entries[it] }
            }
        }
        when {
            loading && channels.isEmpty() -> item { Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            error != null && channels.isEmpty() -> item { Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { Text("Live TV isn't available", color = SecondaryText) } }
            section == LiveSection.OnNow -> {
                item { ProgramShelf("On Now", onNow, onPlay) }
                item { ProgramShelf("Movies", movies, onPlay) }
                item { ProgramShelf("Sports", sports, onPlay) }
                item { ProgramShelf("Kids", kids, onPlay) }
                item { ProgramShelf("News", news, onPlay) }
            }
            section == LiveSection.Channels -> {
                val rows = channels.chunked(2)
                items(rows.size) { i ->
                    Row(Modifier.padding(horizontal = Lockup.screenPadding), horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing)) {
                        rows[i].forEach { channel ->
                            ChannelTile(channel, Modifier.weight(1f), onClick = { onPlay(channel.id) }, onFavorite = {
                                val now = !(channel.userData?.isFavorite == true)
                                channels = channels.map { if (it.id == channel.id) it.copy(userData = it.userData?.copy(isFavorite = now)) else it }
                                scope.launch { runCatching { repo.setFavorite(channel.id, now) } }
                            })
                        }
                        if (rows[i].size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            else -> {
                if (recordings.isEmpty()) item { Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { Text("No recordings yet", color = SecondaryText) } }
                else item {
                    Column(Modifier.padding(horizontal = Lockup.screenPadding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        recordings.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing)) { row.forEach { LandscapeCard(it, { onPlay(it.id) }) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgramShelf(title: String, programs: List<BaseItemDto>, onPlay: (UUID) -> Unit) {
    if (programs.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Lockup.shelfTitleSpacing)) {
        Text(title, style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding))
        LazyRow(contentPadding = PaddingValues(horizontal = Lockup.screenPadding, vertical = Lockup.shelfVerticalPadding), horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing)) {
            items(programs, key = { it.id }) { program -> ProgramCard(program) { program.channelId?.let(onPlay) } }
        }
    }
}

/** A programme on now: its picture and how far through it is, its title and the channel and time. */
@Composable
private fun ProgramCard(program: BaseItemDto, onClick: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as AsterApp).repository
    Column(Modifier.width(Lockup.landscapeWidth).combinedClickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(Lockup.cardLabelSpacing)) {
        Box(Modifier.fillMaxWidth().aspectRatio(Lockup.LANDSCAPE_ASPECT).clip(RoundedCornerShape(Lockup.cornerRadius)).background(Color(0xFF1C1C1E))) {
            AsyncImage(model = repo.programImageUrl(program, 600), contentDescription = program.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            programProgress(program)?.let { p ->
                Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0.03f, 1f)).height(6.dp).clip(CircleShape).background(Color.White))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Lockup.cardTextSpacing)) {
            Text(program.name.orEmpty(), style = Type.calloutMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(program.channelName, timeRange(program)).joinToString(" · "), style = Type.caption, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A channel: its logo, number and name, and what's on it. Long press for the favourite flag. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelTile(channel: BaseItemDto, modifier: Modifier, onClick: () -> Unit, onFavorite: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as AsterApp).repository
    val now = channel.currentProgram
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = onFavorite), verticalArrangement = Arrangement.spacedBy(Lockup.cardLabelSpacing)) {
        Box(Modifier.fillMaxWidth().aspectRatio(Lockup.LANDSCAPE_ASPECT).clip(RoundedCornerShape(Lockup.cornerRadius)).background(Color(0xFF1C1C1E)), contentAlignment = Alignment.Center) {
            AsyncImage(model = repo.channelLogoUrl(channel, 300), contentDescription = channel.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(16.dp))
            if (channel.userData?.isFavorite == true) Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(Lockup.cardTextSpacing)) {
            Text(listOfNotNull(channel.channelNumber, channel.name).joinToString(" "), style = Type.calloutMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            now?.name?.let { Text(it, style = Type.caption, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun LocalDateTime.instant() = toInstant(ZoneOffset.UTC)

/** How far through a programme it is, 0..1, or null when it isn't on now. */
fun programProgress(program: BaseItemDto): Float? {
    val start = program.startDate?.instant() ?: return null
    val end = program.endDate?.instant() ?: return null
    val now = java.time.Instant.now()
    if (!end.isAfter(start) || now.isBefore(start) || !now.isBefore(end)) return null
    return ((now.epochSecond - start.epochSecond).toFloat() / (end.epochSecond - start.epochSecond))
}

/** "8:00 – 9:00 PM", in the viewer's time. */
fun timeRange(program: BaseItemDto): String? {
    val fmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    val start = program.startDate?.instant()?.atZone(ZoneId.systemDefault()) ?: return null
    val end = program.endDate?.instant()?.atZone(ZoneId.systemDefault()) ?: return fmt.format(start)
    return "${fmt.format(start)} – ${fmt.format(end)}"
}
