package com.astermediaplayer.astermediaplayer.ui.livetv

import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalConfiguration
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
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
import com.astermediaplayer.astermediaplayer.ui.guide.GuideChannel
import com.astermediaplayer.astermediaplayer.ui.guide.GuideProgram
import com.astermediaplayer.astermediaplayer.ui.guide.GuideView
import com.astermediaplayer.astermediaplayer.ui.guide.dayLabel
import com.astermediaplayer.astermediaplayer.ui.guide.floorToHalfHour
import com.astermediaplayer.astermediaplayer.ui.guide.timeRange
import org.jellyfin.sdk.model.api.ImageType
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
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

private enum class LiveSection(val label: String) { OnNow("On Now"), Guide("Guide"), Channels("Channels"), Recordings("Recordings") }

/** Live TV: what's on now, the channel list and recordings. */
@Composable
fun LiveTvScreen(onPlay: (UUID) -> Unit, onProgram: (BaseItemDto) -> Unit, onSeriesRule: (String) -> Unit, modifier: Modifier = Modifier, topInset: Dp = 0.dp) {
    val app = LocalContext.current.applicationContext as AsterApp
    val repo = app.repository
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf(LiveSection.OnNow) }
    // Read here: the lazy list below is no place for a composable call.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    var channels by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var onNow by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var movies by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var sports by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var kids by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var news by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var recordings by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var timers by remember { mutableStateOf<List<org.jellyfin.sdk.model.api.TimerInfoDto>>(emptyList()) }
    var seriesRules by remember { mutableStateOf<List<org.jellyfin.sdk.model.api.SeriesTimerInfoDto>>(emptyList()) }
    // The guide: a day from the half hour, each channel's row fetched as it comes into view.
    val guideStart = remember { floorToHalfHour(System.currentTimeMillis() / 1000.0) }
    val guideEnd = guideStart + 24 * 3600.0
    val listings = remember { androidx.compose.runtime.mutableStateMapOf<String, List<GuideProgram>>() }
    val loadingRows = remember { HashSet<String>() }
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
        timers = runCatching { repo.timers() }.getOrDefault(emptyList())
        seriesRules = runCatching { repo.seriesTimers() }.getOrDefault(emptyList())
    }

    fun open(program: BaseItemDto) { repo.openedPrograms[program.id.toString()] = program; onProgram(program) }
    fun favorite(channelId: UUID) {
        val now = !(channels.firstOrNull { it.id == channelId }?.userData?.isFavorite == true)
        channels = channels.map { if (it.id == channelId) it.copy(userData = it.userData?.copy(isFavorite = now)) else it }
        scope.launch { runCatching { repo.setFavorite(channelId, now) } }
    }

    Column(modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp + topInset)) {
        Column(Modifier.padding(horizontal = Lockup.screenPadding).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Live TV", style = Type.pageTitle.copy(fontSize = 34.sp, lineHeight = 41.sp))
            Segmented(LiveSection.entries.map { it.label }, section.ordinal) { section = LiveSection.entries[it] }
        }
        when {
            loading && channels.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null && channels.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Live TV isn't available", color = SecondaryText) }
            section == LiveSection.Guide -> {
                val rows = remember(channels) { channels.map { GuideChannel(it.id.toString(), it.channelNumber, it.name.orEmpty(), repo.channelLogoUrl(it, 200), it.userData?.isFavorite == true) } }
                GuideView(
                    channels = rows, guideStart = guideStart, guideEnd = guideEnd,
                    programs = { listings[it.id] },
                    ensure = { row ->
                        if (row.id !in listings && loadingRows.add(row.id)) scope.launch {
                            // A failed fetch isn't kept, so the row's next appearance tries again.
                            runCatching { repo.livePrograms(UUID.fromString(row.id), guideStart, guideEnd) }.onSuccess { loaded ->
                                listings[row.id] = loaded.filter { (it.endDate?.instant()?.epochSecond ?: 0L) > guideStart + 60 }.mapNotNull { it.toGuide() }
                            }
                            loadingRows.remove(row.id)
                        }
                    },
                    onProgram = { _, program -> val dto = program.payload as? BaseItemDto; if (dto != null) open(dto) },
                    onChannel = { onPlay(UUID.fromString(it.id)) },
                    onFavorite = { favorite(UUID.fromString(it.id)) },
                    bottomPadding = TabBarClearance,
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 10.dp, bottom = TabBarClearance), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when (section) {
                    LiveSection.OnNow -> {
                        if (onNow.isEmpty() && movies.isEmpty() && sports.isEmpty()) item {
                            Column(Modifier.fillMaxWidth().height(320.dp).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text("Nothing on right now", style = Type.title3)
                                Text("The guide has no programmes airing at the moment. Check the Channels section to tune in directly.", style = Type.callout, color = SecondaryText, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                        item { ProgramShelf("On Now", onNow) { open(it) } }
                        item { ProgramShelf("Movies", movies) { open(it) } }
                        item { ProgramShelf("Sports", sports) { open(it) } }
                        item { ProgramShelf("Kids", kids) { open(it) } }
                        item { ProgramShelf("News", news) { open(it) } }
                        item {
                            val favorites = channels.filter { it.userData?.isFavorite == true }
                            if (favorites.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(Lockup.shelfTitleSpacing)) {
                                Text("Favorite Channels", style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding))
                                LazyRow(contentPadding = PaddingValues(horizontal = Lockup.screenPadding, vertical = Lockup.shelfVerticalPadding), horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing)) {
                                    items(favorites, key = { it.id }) { channel -> ChannelTile(channel, Modifier.width(Lockup.landscapeWidth), onClick = { onPlay(channel.id) }, onFavorite = { favorite(channel.id) }) }
                                }
                            }
                        }
                    }
                    LiveSection.Channels -> {
                        // A TV fills the row with the Apple TV's 280-point tiles; a phone has two across.
                        val tv = Lockup.onTv
                        val tile = (280f * Lockup.tvUnit).dp
                        val perRow = if (!tv) 2 else maxOf(1, ((screenWidthDp.dp - Lockup.screenPadding * 2 + Lockup.gridSpacing) / (tile + Lockup.gridSpacing)).toInt())
                        val rows = channels.chunked(perRow)
                        items(rows.size) { i ->
                            Row(Modifier.padding(horizontal = Lockup.screenPadding).padding(bottom = if (tv) Lockup.gridRowSpacing else 0.dp), horizontalArrangement = Arrangement.spacedBy(if (tv) Lockup.gridSpacing else Lockup.shelfSpacing)) {
                                rows[i].forEach { channel -> ChannelTile(channel, if (tv) Modifier.width(tile) else Modifier.weight(1f), onClick = { onPlay(channel.id) }, onFavorite = { favorite(channel.id) }) }
                                if (!tv && rows[i].size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    else -> {
                        if (recordings.isEmpty() && timers.isEmpty() && seriesRules.isEmpty()) item {
                            Column(Modifier.fillMaxWidth().height(320.dp).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text("No recordings", style = Type.title3)
                                Text("Open a programme from the guide and choose Record to schedule one.", style = Type.callout, color = SecondaryText, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                        item { RecordingShelf("Recorded", recordings) { LandscapeCard(it, { onPlay(it.id) }) } }
                        item { RecordingShelf("Scheduled", timers) { timer -> TimerCard(timer) { val info = timer.programInfo; if (info != null) open(info) } } }
                        item { RecordingShelf("Series Rules", seriesRules) { rule -> SeriesTimerCard(rule) { val ruleId = rule.id; if (ruleId != null) onSeriesRule(ruleId) } } }
                    }
                }
            }
        }
    }
}

/** A row of recordings, timers or series rules under its title. */
@Composable
private fun <T> RecordingShelf(title: String, items: List<T>, card: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Lockup.shelfTitleSpacing)) {
        Text(title, style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding))
        LazyRow(contentPadding = PaddingValues(horizontal = Lockup.screenPadding, vertical = Lockup.shelfVerticalPadding), horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing)) {
            items(items.size) { card(items[it]) }
        }
    }
}

/** A scheduled recording: the programme's picture, a clock or a red dot while it records, its name, channel and time. */
@Composable
private fun TimerCard(timer: org.jellyfin.sdk.model.api.TimerInfoDto, onClick: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as AsterApp).repository
    val recording = timer.status == org.jellyfin.sdk.model.api.RecordingStatus.IN_PROGRESS
    val start = timer.startDate?.instant()?.epochSecond?.toDouble()
    val end = timer.endDate?.instant()?.epochSecond?.toDouble()
    Column(Modifier.width(Lockup.landscapeWidth).combinedClickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(Lockup.cardLabelSpacing)) {
        Box(Modifier.fillMaxWidth().aspectRatio(Lockup.LANDSCAPE_ASPECT).clip(RoundedCornerShape(Lockup.cornerRadius)).background(Color(0xFF1C1C1E))) {
            timer.programInfo?.let { AsyncImage(model = repo.programImageUrl(it, 600), contentDescription = timer.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Icon(if (recording) Icons.Default.FiberManualRecord else Icons.Default.Schedule, null, tint = if (recording) Color.Red else Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(20.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(Lockup.cardTextSpacing)) {
            Text(timer.name ?: "Recording", style = Type.calloutMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(timer.channelName, start?.let { "${dayLabel(it)}, ${timeRange(it, end)}" }).joinToString(" · "),
                style = Type.caption, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One series recording rule: artwork, name, channel and the gist of its options. */
@Composable
private fun SeriesTimerCard(rule: org.jellyfin.sdk.model.api.SeriesTimerInfoDto, onClick: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as AsterApp).repository
    val picture = rule.imageTags?.get(ImageType.PRIMARY)?.let { tag -> rule.programId?.let(::guid)?.let { repo.imageUrlFor(it, tag, 600) } }
        ?: rule.parentPrimaryImageItemId?.let { id -> rule.parentPrimaryImageTag?.let { repo.imageUrlFor(id, it, 600) } }
        ?: rule.channelId?.let { id -> rule.channelPrimaryImageTag?.let { repo.imageUrlFor(id, it, 600) } }
    val summary = buildList {
        rule.channelName?.takeIf { rule.recordAnyChannel != true }?.let { add(it) }
        add(if (rule.recordNewOnly == true) "New episodes" else "All episodes")
        rule.keepUpTo?.takeIf { it > 0 }?.let { add("Keep $it") }
    }.joinToString(" · ")
    Column(Modifier.width(Lockup.landscapeWidth).combinedClickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(Lockup.cardLabelSpacing)) {
        Box(Modifier.fillMaxWidth().aspectRatio(Lockup.LANDSCAPE_ASPECT).clip(RoundedCornerShape(Lockup.cornerRadius)).background(Color(0xFF1C1C1E))) {
            if (picture != null) AsyncImage(model = picture, contentDescription = rule.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Icon(Icons.Default.Repeat, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(20.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(Lockup.cardTextSpacing)) {
            Text(rule.name ?: "Series Recording", style = Type.calloutMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(summary, style = Type.caption, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Jellyfin writes ids as 32 hex digits; the SDK wants them with dashes. */
private fun guid(text: String): UUID? = runCatching {
    UUID.fromString(if (text.length == 32) "${text.substring(0, 8)}-${text.substring(8, 12)}-${text.substring(12, 16)}-${text.substring(16, 20)}-${text.substring(20)}" else text)
}.getOrNull()

/** A programme as the guide draws it. */
private fun BaseItemDto.toGuide(): GuideProgram? {
    val start = startDate?.instant()?.epochSecond?.toDouble() ?: return null
    val end = endDate?.instant()?.epochSecond?.toDouble() ?: return null
    return GuideProgram(id.toString(), name.orEmpty(), episodeTitle, start, end, recording = timerId != null || seriesTimerId != null, payload = this)
}

@Composable
private fun ProgramShelf(title: String, programs: List<BaseItemDto>, onOpen: (BaseItemDto) -> Unit) {
    if (programs.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Lockup.shelfTitleSpacing)) {
        Text(title, style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding))
        LazyRow(contentPadding = PaddingValues(horizontal = Lockup.screenPadding, vertical = Lockup.shelfVerticalPadding), horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing)) {
            items(programs, key = { it.id }) { program -> ProgramCard(program) { onOpen(program) } }
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
    Column(modifier.tvFocus(Lockup.cornerRadius, lift = 1.08f, ring = false, pictureAspect = Lockup.LANDSCAPE_ASPECT).combinedClickable(onClick = onClick, onLongClick = onFavorite), verticalArrangement = Arrangement.spacedBy(Lockup.cardLabelSpacing)) {
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
