package com.astermediaplayer.astermediaplayer.ui.channels

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.channels.BugCorner
import com.astermediaplayer.astermediaplayer.data.channels.BumperPlacement
import com.astermediaplayer.astermediaplayer.data.channels.BreakKind
import com.astermediaplayer.astermediaplayer.data.channels.ChannelBreaks
import com.astermediaplayer.astermediaplayer.data.channels.ChannelContent
import com.astermediaplayer.astermediaplayer.ui.detail.MenuHost
import com.astermediaplayer.astermediaplayer.ui.detail.MenuItem
import com.astermediaplayer.astermediaplayer.data.channels.ChannelLineup
import com.astermediaplayer.astermediaplayer.data.channels.ChannelMode
import com.astermediaplayer.astermediaplayer.data.channels.ChannelReshuffle
import com.astermediaplayer.astermediaplayer.data.channels.ChannelTags
import com.astermediaplayer.astermediaplayer.ui.settings.TvChoiceRow
import com.astermediaplayer.astermediaplayer.ui.settings.TvGroup
import com.astermediaplayer.astermediaplayer.ui.settings.TvRow
import com.astermediaplayer.astermediaplayer.ui.settings.TvSwitchRow
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import com.astermediaplayer.astermediaplayer.data.channels.ChannelTagParsing
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType

private fun BaseItemDto.plainId() = id.toString().replace("-", "")

/** Which video playlists are channels, and how each plays (the Apple apps' `ChannelSettingsView`, its core settings). */
class ChannelSettingsViewModel(private val app: AsterApp) : ViewModel() {
    data class State(
        val playlists: List<BaseItemDto> = emptyList(),
        val loaded: Boolean = false,
        val error: String? = null,
        val admin: Boolean = false,
        /** A save the server refused: the page turns read-only. */
        val refused: Boolean = false,
        /** What a channel can be filled from or limited to, by name. */
        val genres: List<String> = emptyList(),
        val libraries: List<String> = emptyList(),
        val networks: List<String> = emptyList(),
        val collections: List<String> = emptyList(),
        val ratings: List<String> = emptyList(),
        val users: List<String> = emptyList(),
        val series: List<Pair<UUID, String>> = emptyList(),
        /** New Channel is making one. */
        val creating: Boolean = false,
        /** The playlist New Channel just made: the page opens it. */
        val created: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { load() }

    fun load() {
        // Off the main thread: the playlists and the lists the pickers offer are JSON the SDK reads where it's called.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val admin = app.repository.canEditChannels()
                val playlists = app.repository.videoPlaylists()
                _state.update { it.copy(playlists = playlists, admin = admin, loaded = true, error = null) }
                loadChoices()
            } catch (e: Exception) {
                _state.update { it.copy(loaded = true, error = e.message ?: "Couldn't load") }
            }
        }
    }

    /** The lists the pickers offer; one that fails to load is just empty. */
    private suspend fun loadChoices() = coroutineScope {
        val repo = app.repository
        val genres = async { runCatching { repo.videoGenres() }.getOrDefault(emptyList()) }
        val views = async { runCatching { repo.views() }.getOrDefault(emptyList()) }
        val studios = async { runCatching { repo.videoStudios() }.getOrDefault(emptyList()) }
        val boxSets = async { runCatching { repo.collections() }.getOrDefault(emptyList()) }
        val ratings = async { runCatching { repo.videoRatings() }.getOrDefault(emptyList()) }
        val users = async { repo.userNames() }
        val series = async { runCatching { repo.seriesList() }.getOrDefault(emptyList()) }
        // Video libraries only: a genre channel plays movies and episodes.
        val skipped = setOf("music", "books", "livetv", "playlists", "boxsets", "musicvideos")
        _state.update {
            it.copy(
                genres = genres.await().mapNotNull { g -> g.name }, networks = studios.await().mapNotNull { s -> s.name },
                libraries = views.await().filter { v -> v.collectionType?.serialName?.lowercase() !in skipped }.mapNotNull { v -> v.name },
                // Empty collections (book series often are, to video) are left out.
                collections = boxSets.await().filter { c -> (c.childCount ?: 1) > 0 }.mapNotNull { c -> c.name },
                ratings = ratings.await(), users = users.await(), series = series.await().mapNotNull { s -> s.name?.let { n -> s.id to n } },
            )
        }
    }

    /** New Channel: a playlist of a show's episodes, or an empty one filled from a genre, network or collection, numbered next. */
    fun create(source: NewChannelSource, name: String, seriesId: UUID? = null) {
        val current = _state.value
        if (!current.admin || current.refused || current.creating) return
        _state.update { it.copy(creating = true) }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                // Its number goes past the highest, which would move the channels numbered by themselves.
                keepNumbers()
                var tags = ChannelTags(isChannel = true, number = (numbers(current.playlists).values.maxOrNull() ?: 0) + 1)
                tags = when (source) {
                    NewChannelSource.Show -> tags.copy(showsLogo = true)
                    NewChannelSource.Genre -> tags.copy(genres = listOf(name))
                    NewChannelSource.Network -> tags.copy(networks = listOf(name))
                    NewChannelSource.Collection -> tags.copy(collections = listOf(name))
                    NewChannelSource.Playlist -> tags
                }
                val episodes = if (seriesId != null) app.repository.seriesEpisodes(seriesId).map { it.id } else emptyList()
                val id = app.repository.createVideoPlaylist(name, episodes)
                app.repository.setTags(id, tags.applied(emptyList()))
                val playlists = app.repository.videoPlaylists()
                _state.update { it.copy(playlists = playlists, creating = false, created = id.toString().replace("-", "")) }
                app.channels.load(app.repository.session?.userName, force = true)
            } catch (e: Exception) {
                _state.update { it.copy(creating = false, error = e.message ?: "Couldn't make the channel") }
            }
        }
    }

    fun opened() = _state.update { it.copy(created = null) }

    /** Applies [change] to a playlist's channel tags and saves them; the page shows the change at once and undoes it if the save fails. */
    fun update(playlist: BaseItemDto, change: (ChannelTags) -> ChannelTags) {
        val current = _state.value
        if (!current.admin || current.refused) return
        val before = current.playlists.firstOrNull { it.id == playlist.id } ?: return
        val previous = before.tags.orEmpty()
        val was = ChannelTags.from(previous)
        var tags = change(was)
        // Turning a channel on or off, or giving it a number, would move the channels numbered by themselves.
        val movesOthers = tags.isChannel != was.isChannel || tags.number != was.number
        if (tags.number == null) tags = tags.copy(number = numbers(current.playlists)[playlist.plainId()])
        val updated = tags.applied(previous)
        replace(playlist, updated)
        viewModelScope.launch {
            try {
                if (movesOthers) keepNumbers(except = playlist)
                app.repository.setTags(playlist.id, updated)
                // Channels, the guide and the player pick the change up.
                app.channels.load(app.repository.session?.userName, force = true)
            } catch (e: Exception) {
                replace(playlist, previous)
                val refused = e.message?.let { it.contains("401") || it.contains("403") } == true
                _state.update { it.copy(error = e.message ?: "Couldn't save", refused = refused || it.refused) }
            }
        }
    }

    /**
     * Writes each channel numbered by itself (no aster:number) the number it has now: those come after the highest number set, so a
     * channel added, removed or renumbered would otherwise move them.
     */
    private suspend fun keepNumbers(except: BaseItemDto? = null) {
        val playlists = _state.value.playlists
        val current = numbers(playlists)
        for (p in playlists) {
            if (p.id == except?.id) continue
            val tags = ChannelTags.from(p.tags)
            if (!tags.isChannel || tags.number != null) continue
            val number = current[p.plainId()] ?: continue
            val updated = tags.copy(number = number).applied(p.tags.orEmpty())
            app.repository.setTags(p.id, updated)
            replace(p, updated)
        }
    }

    /** A playlist made a channel: numbered after the highest, with its picture as logo when it has one. */
    fun turnOn(playlist: BaseItemDto) = update(playlist) { tags ->
        val taken = numbers(_state.value.playlists).values.maxOrNull() ?: 0
        val sets = playlist.tags.orEmpty().any { ChannelTagParsing.parse(it)?.first == "logo" }
        tags.copy(isChannel = true, number = tags.number ?: (taken + 1), showsLogo = if (sets) tags.showsLogo else playlist.imageTags?.containsKey(ImageType.PRIMARY) == true)
    }

    private fun replace(playlist: BaseItemDto, tags: List<String>) {
        _state.update { s -> s.copy(playlists = s.playlists.map { if (it.id == playlist.id) it.copy(tags = tags) else it }) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    companion object {
        /** Each channel's number in the guide, its own or the next free one, by plain playlist id. */
        fun numbers(playlists: List<BaseItemDto>): Map<String, Int> = ChannelTags.numbers(
            playlists.mapNotNull { p -> ChannelTags.from(p.tags).takeIf { it.isChannel }?.let { Triple(p.plainId(), it.name ?: p.name.orEmpty(), it.number) } },
        )
    }
}

private val bugHideChoices = listOf(0, 10, 30, 60, 120, 300)
private val bugOpacityChoices = listOf(25, 35, 45, 60, 75, 90, 100)
private val bugSizeChoices = listOf(50, 75, 100, 125, 150, 200, 250, 300)
private val lengthChoices = listOf<Int?>(null, 5, 10, 15, 20, 30, 45, 60, 90, 120, 150, 180)
private val blockChoices = listOf(ChannelLineup.DEFAULT_BLOCKS, 1..2, 1..3, 2..3, 3..4, 3..5, 3..6, 4..6, 4..8, 6..10)

private fun lengthTitle(minutes: Int?) = when {
    minutes == null -> "Any"
    minutes >= 60 -> if (minutes % 60 == 0) "${minutes / 60} hr" else "${minutes / 60} hr ${minutes % 60} min"
    else -> "$minutes min"
}

private fun blocksTitle(range: IntRange) = if (range.count() == 1) "${range.first}" else "${range.first} to ${range.last}"

/** A choice from a list of options that always includes the current value. */
private fun <T> withCurrent(options: List<T>, current: T, sort: Boolean = false): List<T> =
    (if (current in options) options else options + current).let { if (sort) it.sortedBy { o -> (o as? Int) ?: 0 } else it }

/**
 * Edit Channels: every video playlist, those that are channels with their settings and the rest to turn on. Alone ([only]) it
 * is one channel's settings, as Select on its guide cell opens them. Only the server's administrator can change anything.
 */
@Composable
fun ChannelSettingsScreen(viewModel: ChannelSettingsViewModel, only: String?, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val u = Lockup.tvUnit
    val canEdit = state.admin && !state.refused
    var expanded by remember(only) { mutableStateOf(if (only != null) setOf(only) else emptySet<String>()) }
    // Which of a channel's folded sections are open, as "plays|<id>" or "advanced|<id>".
    var folded by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(state.created) { state.created?.let { expanded = expanded + it; viewModel.opened() } }
    // New Channel's page for picking what to make it from, over the list, as on the Apple apps.
    var picking by remember { mutableStateOf<NewChannelSource?>(null) }
    // Back from the page lands on the row that opened it.
    var cameFrom by remember { mutableStateOf<NewChannelSource?>(null) }
    val returnFocus = remember { FocusRequester() }
    LaunchedEffect(picking) { if (picking == null && cameFrom != null) repeat(10) { if (runCatching { returnFocus.requestFocus() }.isSuccess) return@LaunchedEffect else delay(60) } }
    androidx.activity.compose.BackHandler(enabled = picking != null) { picking = null }
    val first = remember { FocusRequester() }
    LaunchedEffect(state.loaded) {
        if (!state.loaded) return@LaunchedEffect
        repeat(20) { if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect else delay(100) }
    }

    val playlists = state.playlists
    val channels = playlists.map { it to ChannelTags.from(it.tags) }.filter { it.second.isChannel }.let { list ->
        val numbers = ChannelSettingsViewModel.numbers(playlists)
        list.sortedWith(compareBy({ numbers[it.first.plainId()] ?: 0 }, { it.second.name ?: it.first.name.orEmpty() }))
    }
    val numbers = ChannelSettingsViewModel.numbers(playlists)
    val others = playlists.filter { !ChannelTags.from(it.tags).isChannel && !ChannelTagParsing.isFillerPlaylist(it.tags) }
    val shownChannels = if (only == null) channels else channels.filter { it.first.plainId() == only }

    val choosing = picking
    // The page for picking what to make a channel from is its own list, of which only the rows on screen are made (Plex's anime library alone has a thousand genres).
    if (choosing != null) NewChannelPicker(choosing, state, others, modifier) { id, name ->
        picking = null
        if (choosing == NewChannelSource.Playlist) others.firstOrNull { it.plainId() == id }?.let { expanded = expanded + id; viewModel.turnOn(it) }
        else viewModel.create(choosing, name, if (choosing == NewChannelSource.Show) UUID.fromString(id) else null)
    } else Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = (90f * u).dp).padding(top = (40f * u).dp, bottom = (80f * u).dp),
        verticalArrangement = Arrangement.spacedBy((6f * u).dp),
    ) {
        Text(
            if (only != null) shownChannels.firstOrNull()?.let { it.second.name ?: it.first.name } ?: "Channel" else "Channels (Beta)",
            style = Type.pageTitle.copy(fontSize = (76f * u).sp, lineHeight = (90f * u).sp), modifier = Modifier.padding(bottom = (12f * u).dp),
        )
        when {
            !state.loaded -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), Alignment.Center) { CircularProgressIndicator() }
            playlists.isEmpty() -> {
                if (only == null) Note(CHANNELS_INTRO)
                Note(if (state.error != null) state.error!! else if (serverKind() == com.astermediaplayer.astermediaplayer.data.emby.ServerKind.Plex) "No video playlists yet. Make one in Plex." else "No video playlists yet. Make one in ${serverName()}, or with Add to Playlist on any movie.")
                if (only == null && canEdit) NewChannel(state, others, cameFrom, returnFocus) { cameFrom = it; picking = it }
            }
            else -> {
                if (only == null) Note(CHANNELS_INTRO)
                if (!state.admin) Note("Only your server's administrator can set up channels. Ask them to, here or in ${serverName()}.")
                else if (state.refused) Note("The server wouldn't save that, so channels are read-only now.")
                if (only != null && shownChannels.isEmpty()) Note("This playlist isn't a channel any more. Turn it back on in Edit Channels.")
                if (only == null && canEdit) NewChannel(state, others, cameFrom, returnFocus) { cameFrom = it; picking = it }
                shownChannels.forEachIndexed { index, (playlist, tags) ->
                    val id = playlist.plainId()
                    val open = id in expanded
                    TvGroup("Channel ${numbers[id] ?: 0}")
                    TvRow(
                        tags.name ?: playlist.name.orEmpty(), null, modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                        subtitle = summary(playlist, tags), fold = open, onClick = { expanded = if (open) expanded - id else expanded + id },
                    )
                    if (open) ChannelSettingsRows(viewModel, state, playlist, tags, channels.mapNotNull { it.second.group }, numbers[id] ?: 0, canEdit, folded.filter { it.endsWith("|$id") }.map { it.substringBefore("|") }.toSet()) { key -> val k = "$key|$id"; folded = if (k in folded) folded - k else folded + k }
                }
                if (only == null && others.isNotEmpty()) {
                    TvGroup(if (channels.isEmpty()) "Video Playlists" else "More Playlists")
                    others.forEachIndexed { index, playlist ->
                        TvSwitchRow(playlist.name.orEmpty(), false, enabled = canEdit, modifier = if (shownChannels.isEmpty() && index == 0) Modifier.focusRequester(first) else Modifier, subtitle = titles(playlist)) { on ->
                            if (on) { expanded = expanded + playlist.plainId(); viewModel.turnOn(playlist) }
                        }
                    }
                }
                if (only == null) clashes(channels).forEach { Note(it) }
                if (only == null) HowChannelsWork()
            }
        }
    }
    state.error?.takeIf { playlists.isNotEmpty() }?.let {
        AlertDialog(onDismissRequest = viewModel::dismissError, title = { Text("Channels") }, text = { Text(it) }, confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("OK") } })
    }
}

@Composable
private fun Note(text: String, readable: Boolean = false) {
    val u = Lockup.tvUnit
    // Long help text takes the remote's focus, so the page scrolls down to it.
    Text(text, style = Type.caption, color = SecondaryText, modifier = Modifier.then(if (readable) Modifier.focusable() else Modifier).padding(horizontal = (8f * u).dp, vertical = (4f * u).dp))
}

/** "Halloween and Sitcoms are both channel 3." */
private fun clashes(channels: List<Pair<BaseItemDto, ChannelTags>>): List<String> =
    channels.filter { it.second.number != null }.groupBy { it.second.number!! }.toSortedMap().mapNotNull { (number, group) ->
        val names = group.map { it.second.name ?: it.first.name.orEmpty() }
        if (names.size > 1) "${names.dropLast(1).joinToString(", ")} and ${names.last()} are all channel $number. Give each its own number." else null
    }

/** "Rotation · Weekly · 120 titles · Logo · Bug", for a closed channel. */
private fun summary(playlist: BaseItemDto, tags: ChannelTags): String {
    val parts = mutableListOf(tags.mode.title)
    val sources = tags.genres + tags.networks + tags.collections
    if (sources.isNotEmpty()) parts.add(0, sources.joinToString(", "))
    if (tags.mode.reshuffles && tags.reshuffle != ChannelReshuffle.Day) parts += tags.reshuffle.title
    playlist.childCount?.let { parts += if (it == 1) "1 title" else "$it titles" }
    if (tags.showsLogo) parts += "Logo"
    if (tags.showsBug) parts += "Bug"
    if (tags.users.isNotEmpty()) parts += "Only " + andList(tags.users)
    if (tags.hasBreaks) parts += if (tags.breaks.align > 0) "Breaks, " + alignTitle(tags.breaks.align).lowercase() else "Breaks"
    return parts.joinToString(" · ")
}

/** "A", "A and B", "A, B and C". */
private fun andList(names: List<String>) = if (names.size < 2) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()

/** "1 title" or "12 titles", when the playlist says. */
private fun titles(playlist: BaseItemDto): String? = playlist.childCount?.let { if (it == 1) "1 title" else "$it titles" }

/** "Horror, Thriller · Movies", or the playlist's own titles, for the What It Plays fold when it's closed. */
private fun playsSummary(playlist: BaseItemDto, tags: ChannelTags): String {
    val sources = tags.genres + tags.networks + tags.collections
    if (sources.isEmpty()) return titles(playlist) ?: "Its playlist"
    return (listOf(sources.joinToString(", ")) + listOfNotNull(tags.content.takeIf { it != ChannelContent.All }?.title)).joinToString(" · ")
}

private const val CHANNELS_INTRO = "Turn video playlists into TV-style channels. Each plays its movies and episodes around the clock, the same on every device; tune in and you join whatever's on. Watching a channel doesn't touch your watch history. The Channels tab appears once there is one."

/** The server's name for the footers and help, as the app shows it. */
@Composable
private fun serverName(): String =
    (androidx.compose.ui.platform.LocalContext.current.applicationContext as AsterApp).repository.serverKind.displayName

/** "On the Half Hour", "On the Hour", "Every 15 Minutes". */
private fun alignTitle(minutes: Int) = when (minutes) { 30 -> "On the Half Hour"; 60 -> "On the Hour"; else -> "Every $minutes Minutes" }

/** "Any Year", "1980s", "1985–1999" or "1994". */
private fun yearsTitle(years: IntRange?) = when {
    years == null -> "Any Year"
    years.count() == 1 -> "${years.first}"
    years.first % 10 == 0 && years.count() == 10 -> "${years.first}s"
    else -> "${years.first}–${years.last}"
}

private val yearChoices: List<IntRange?> = listOf(null) + (1950..2020 step 10).map { it..it + 9 }
private val commercialChoices = listOf(0, 1, 2, 3, 4, 5, 6, 8, 10, 15, 20, 30)

/** An open channel's settings: the essentials, what it plays, then the rest folded away until asked for. */
@Composable
private fun ChannelSettingsRows(
    viewModel: ChannelSettingsViewModel, state: ChannelSettingsViewModel.State, playlist: BaseItemDto, tags: ChannelTags, groups: List<String>, number: Int,
    canEdit: Boolean, open: Set<String>, toggle: (String) -> Unit,
) {
    fun change(edit: (ChannelTags) -> ChannelTags) = viewModel.update(playlist, edit)
    var naming by remember { mutableStateOf<String?>(null) }

    TvSwitchRow(playlist.name.orEmpty(), true, enabled = canEdit, subtitle = titles(playlist)) { on -> if (!on) change { it.copy(isChannel = false) } }
    TvRow("Name", tags.name ?: playlist.name.orEmpty(), enabled = canEdit) { naming = "name" }
    val groupNames = (groups + listOfNotNull(tags.group)).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    TvChoiceRowIf(canEdit, "Group", listOf("None") + groupNames, if (tags.group == null) 0 else groupNames.indexOf(tags.group) + 1) { i -> change { it.copy(group = if (i == 0) null else groupNames[i - 1]) } }
    TvRow("New Group", null, enabled = canEdit) { naming = "group" }
    val numberOptions = (1..maxOf(99, number)).toList()
    TvChoiceRowIf(canEdit, "Number", numberOptions.map { "$it" }, ((tags.number ?: number) - 1).coerceIn(0, numberOptions.lastIndex)) { i -> change { it.copy(number = numberOptions[i]) } }

    TvRow("What It Plays", if ("plays" in open) null else playsSummary(playlist, tags), fold = "plays" in open, onClick = { toggle("plays") })
    if ("plays" in open) {
        TvMultiRow(canEdit, "Genres", state.genres, tags.genres, "None") { v -> change { it.copy(genres = v) } }
        TvMultiRow(canEdit, "Networks", state.networks, tags.networks, "None") { v -> change { it.copy(networks = v) } }
        TvMultiRow(canEdit, "Collections", state.collections, tags.collections, "None") { v -> change { it.copy(collections = v) } }
        if (tags.hasSources) {
            TvChoiceRowIf(canEdit, "Content", ChannelContent.entries.map { it.title }, ChannelContent.entries.indexOf(tags.content)) { i -> change { it.copy(content = ChannelContent.entries[i]) } }
            if (tags.genres.isNotEmpty() || tags.networks.isNotEmpty()) TvMultiRow(canEdit, "Libraries", state.libraries, tags.libraries, "All") { v -> change { it.copy(libraries = v) } }
            val years = withCurrent(yearChoices, tags.years)
            TvChoiceRowIf(canEdit, "Years", years.map(::yearsTitle), years.indexOf(tags.years)) { i -> change { it.copy(years = years[i]) } }
            TvMultiRow(canEdit, "Ratings", state.ratings, tags.ratings, "Any") { v -> change { it.copy(ratings = v) } }
        }
    }
    TvChoiceRowIf(canEdit, "Mode", ChannelMode.entries.map { it.title }, ChannelMode.entries.indexOf(tags.mode)) { i -> change { it.copy(mode = ChannelMode.entries[i]) } }
    TvSwitchRow("Playlist Picture as Logo", tags.showsLogo, enabled = canEdit) { on -> change { it.copy(showsLogo = on) } }
    TvSwitchRow("Logo in the Corner While Playing", tags.showsBug, enabled = canEdit) { on -> change { it.copy(showsBug = on) } }

    // Breaks: the filler playlists (tagged aster:filler) whose clips play between programmes.
    val fillers = state.playlists.filter { ChannelTagParsing.isFillerPlaylist(it.tags) }.mapNotNull { it.name }
    val fillerOptions = fillers + tags.fillerPlaylists.filter { n -> fillers.none { it.equals(n, ignoreCase = true) } }
    // Break clips are told apart by their tags, which Plex's videos don't have: no breaks there yet.
    val plex = serverKind() == com.astermediaplayer.astermediaplayer.data.emby.ServerKind.Plex
    if (fillerOptions.isNotEmpty() && !plex) TvMultiRow(canEdit, "Breaks", fillerOptions, tags.fillerPlaylists, "Off") { names ->
        change { t ->
            val turningOn = t.fillerPlaylists.isEmpty() && names.isNotEmpty()
            val next = t.copy(fillerPlaylists = names)
            // Lined up on the half hour, best effort: how a channel with breaks usually wants to run.
            if (turningOn && t.breaks.align == 0) next.copy(breaks = next.breaks.copy(align = 30, exactAlign = false)) else next
        }
    }

    TvRow("Advanced", null, fold = "advanced" in open, onClick = { toggle("advanced") })
    if ("advanced" in open) {
        if (tags.mode == ChannelMode.Blocks) {
            val options = withCurrent(blockChoices, tags.blocks)
            TvChoiceRowIf(canEdit, "Episodes in a Row", options.map(::blocksTitle), options.indexOf(tags.blocks)) { i -> change { it.copy(blocks = options[i]) } }
        }
        if (tags.mode.reshuffles) {
            TvChoiceRowIf(canEdit, "Reshuffle", ChannelReshuffle.entries.map { it.title }, ChannelReshuffle.entries.indexOf(tags.reshuffle)) { i -> change { it.copy(reshuffle = ChannelReshuffle.entries[i]) } }
            TvRow("Shuffle Again", null, enabled = canEdit) { change { it.copy(seed = it.seed + 1) } }
        }
        val shortest = withCurrent(lengthChoices, tags.minLength).sortedBy { it ?: 0 }
        TvChoiceRowIf(canEdit, "Shortest", shortest.map(::lengthTitle), shortest.indexOf(tags.minLength)) { i -> change { it.copy(minLength = shortest[i]) } }
        val longest = withCurrent(lengthChoices, tags.maxLength).sortedBy { it ?: 0 }
        TvChoiceRowIf(canEdit, "Longest", longest.map(::lengthTitle), longest.indexOf(tags.maxLength)) { i -> change { it.copy(maxLength = longest[i]) } }
        if (tags.showsBug) {
            TvChoiceRowIf(canEdit, "Corner", BugCorner.entries.map { it.title }, BugCorner.entries.indexOf(tags.bugCorner)) { i -> change { it.copy(bugCorner = BugCorner.entries[i]) } }
            val hide = withCurrent(bugHideChoices, tags.bugHideAfter, sort = true)
            TvChoiceRowIf(canEdit, "Hide After", hide.map { if (it == 0) "Never" else if (it >= 60) "${it / 60} min" else "$it sec" }, hide.indexOf(tags.bugHideAfter)) { i -> change { it.copy(bugHideAfter = hide[i]) } }
            val opacity = withCurrent(bugOpacityChoices, tags.bugOpacity, sort = true)
            TvChoiceRowIf(canEdit, "Opacity", opacity.map { "$it%" }, opacity.indexOf(tags.bugOpacity)) { i -> change { it.copy(bugOpacity = opacity[i]) } }
            val size = withCurrent(bugSizeChoices, tags.bugSize, sort = true)
            TvChoiceRowIf(canEdit, "Logo Size", size.map { "$it%" }, size.indexOf(tags.bugSize)) { i -> change { it.copy(bugSize = size[i]) } }
        }
        if (tags.fillerPlaylists.isNotEmpty() && !plex) {
            TvMultiRow(canEdit, "Filler Types", BreakKind.entries.map { it.title }, BreakKind.entries.filter { it in tags.fillerTypes }.map { it.title }, "All") { titles ->
                val kinds = BreakKind.entries.filter { it.title in titles }.toSet()
                change { it.copy(fillerTypes = kinds.ifEmpty { BreakKind.entries.toSet() }) }
            }
        }
        if (tags.hasBreaks && !plex) {
            val b = tags.breaks
            fun breaks(edit: (ChannelBreaks) -> ChannelBreaks) = change { it.copy(breaks = edit(it.breaks)) }
            val counts = (0..ChannelTags.MOST_PROMOS).toList()
            if (BreakKind.Bumpers in tags.fillerTypes) TvChoiceRowIf(canEdit, "Bumpers", BumperPlacement.entries.map { it.title }, BumperPlacement.entries.indexOf(b.bumpersAt)) { i -> breaks { it.copy(bumpersAt = BumperPlacement.entries[i]) } }
            if (BreakKind.Commercials in tags.fillerTypes) {
                val minutes = withCurrent(commercialChoices, b.minutes, sort = true)
                TvChoiceRowIf(canEdit, "Commercial Time", minutes.map { if (it == 0) "None" else lengthTitle(it) }, minutes.indexOf(b.minutes)) { i -> breaks { it.copy(minutes = minutes[i]) } }
            }
            if (BreakKind.Promos in tags.fillerTypes) TvChoiceRowIf(canEdit, "Promos per Break", counts.map { "$it" }, b.promoCount) { i -> breaks { it.copy(promoCount = counts[i]) } }
            if (BreakKind.Trailers in tags.fillerTypes) TvChoiceRowIf(canEdit, "Trailers per Break", counts.map { "$it" }, b.trailerCount) { i -> breaks { it.copy(trailerCount = counts[i]) } }
            val aligns = listOf(0) + ChannelTags.ALIGNMENTS
            TvChoiceRowIf(canEdit, "Line Up", aligns.map { if (it == 0) "Off" else alignTitle(it) }, aligns.indexOf(b.align)) { i -> breaks { it.copy(align = aligns[i]) } }
            if (b.align > 0) TvChoiceRowIf(canEdit, "Fit", listOf("Best Effort", "Exact"), if (b.exactAlign) 1 else 0) { i -> breaks { it.copy(exactAlign = i == 1) } }
            if (b.align == 0) {
                if (BreakKind.Interstitials in tags.fillerTypes) TvChoiceRowIf(canEdit, "Interstitials per Break", counts.map { "$it" }, b.interstitialCount) { i -> breaks { it.copy(interstitialCount = counts[i]) } }
                val gaps = (1..ChannelTags.LONGEST_BREAK_GAP).toList()
                TvChoiceRowIf(canEdit, "Break After", gaps.map { if (it == 1) "Every Programme" else "Every $it Programmes" }, (b.every - 1).coerceIn(0, gaps.lastIndex)) { i -> breaks { it.copy(every = gaps[i]) } }
            }
        }
        val users = state.users + tags.users.filter { n -> state.users.none { it.equals(n, ignoreCase = true) } }
        if (users.isNotEmpty()) TvMultiRow(canEdit, "Users", users, tags.users, "Everyone") { v -> change { it.copy(users = v) } }
        Note(if (plex) "Every setting is kept in the playlist's description in Plex (aster:channel, aster:number=3, …), so you can change it there too: see How Channels Work."
            else "Every setting is a tag on the playlist in ${serverName()}, so you can change it there too: see How Channels Work.")
    }
    naming?.let { field ->
        TextDialog(
            title = if (field == "name") "Channel Name" else "New Group",
            initial = if (field == "name") tags.name.orEmpty() else "",
            hint = if (field == "name") "The playlist's own name when left empty" else "A name for the group",
            onDismiss = { naming = null },
            onSave = { text ->
                naming = null
                val value = text.trim().takeIf { it.isNotEmpty() }
                if (field == "name") change { it.copy(name = value) } else if (value != null) change { it.copy(group = value) }
            },
        )
    }
}

/** Every setting there is, as written in a tag or a description. */
private const val SETTINGS_LIST = "aster:channel, aster:number=3, aster:mode=blocks (or rotation, shuffle, sequential, airdate), aster:reshuffle=week (or day, month), aster:blocks=3-6, aster:seed=2, aster:logo=on, aster:bug=on (with aster:bug-location=top-left, top-right, bottom-left or bottom-right, aster:bug-size=150%, aster:bug-hide=30 and aster:bug-opacity=60%), aster:channel-name=Your Name, aster:group=Kids (channels go in the guide by group, groups in order of their lowest number), aster:min-length=20 and aster:max-length=60 (in minutes), aster:weight=Friends:2 (a show taking two turns to everyone else's one), and to fill a channel aster:genre=Horror, aster:network=NBC and aster:collection=Star Wars (once each), narrowed by aster:type=movies (or shows, all), aster:years=1980-1989, aster:rating=TV-PG,PG and aster:library=TV Shows (once per library, for genres and networks)."

private const val HELP_MODES = "Rotation plays one episode of each show in turn, and Blocks a few in a row; either way a show's episodes come in order and pick up where they left off, each movie takes a turn of its own, and specials are left out. Shuffle plays everything in its own order. These three deal a new order every day, week or month, as Reshuffle says, and Shuffle Again deals a different one. In Order plays the playlist as it's arranged, round and round."
private const val HELP_TAGS = "On Jellyfin and Emby, channels are saved as tags on the playlists, so every device finds them. You can set them there too, in the playlist's Edit Metadata ▸ Tags, one tag each: $SETTINGS_LIST"
private const val HELP_PLEX = "On Plex, which has no tags on playlists, channels are saved in the playlist's description, so every device finds them. You can set them there too, in the playlist's Edit ▸ Description (Summary), as one line with a comma between settings: $SETTINGS_LIST"
private const val HELP_SOURCES = "A channel with genres, networks or collections plays every movie and every episode of every show in them (any of them), as well as anything in its playlist. Air Date plays them all in the order they first aired."
private const val HELP_BREAKS = "A channel with filler playlists has a break after its programmes: a bumper, commercials, promos, trailers, interstitials, and a bumper back. Put the clips in Jellyfin or Emby as videos and tag each one with what it is: aster:commercial, aster:promo, aster:trailer, aster:interstitial or aster:bumper (aster:bumper=in only closes a break, right before the show, and aster:bumper=out only opens one); a clip without one isn't played. Put them in playlists tagged aster:filler, and pick those for a channel here, or name them with aster:filler-playlists=Its Name. aster:filler-types=commercials,bumpers keeps to some kinds; aster:bumpers-at=both (or out, in), aster:break-length=2 (minutes of commercials), aster:promo-count=1, aster:trailer-count=1, aster:interstitial-count=1 and aster:break-every=1 shape each break. With aster:align=30, programmes line up on the hour and half hour: Best Effort (aster:align-fit=best) fills each break with whole commercials, promos, trailers, interstitials and bumpers, as near the mark as they get, so a programme can start a few seconds early; Exact (aster:align-fit=exact) fills with interstitials and cuts the last one short to land right on it."
private const val HELP_WHO = "Other people on your server see a channel only if its playlist is shared with them, or public. Users (aster:user=peppy,jd) keeps a channel to certain people in Aster's Channels tab; it still lists every channel here, and doesn't hide the playlist on the server."
private const val HELP_WHO_PLEX = "A Plex playlist belongs to the account that made it, so its channel is yours alone: others on your server make their own. Breaks (filler playlists) aren't available on Plex yet."

/** How Channels Work, folded open from a row at the foot of the page: where settings are kept differs by server. */
@Composable
private fun HowChannelsWork() {
    var open by remember { mutableStateOf(false) }
    val plex = serverKind() == com.astermediaplayer.astermediaplayer.data.emby.ServerKind.Plex
    TvRow("How Channels Work", null, fold = open, onClick = { open = !open })
    if (open) {
        val notes = buildList {
            add(HELP_MODES)
            add(if (plex) HELP_PLEX else HELP_TAGS)
            add(HELP_SOURCES)
            // Break clips are told apart by their tags, which Plex's videos don't have.
            if (!plex) add(HELP_BREAKS)
            add(if (plex) HELP_WHO_PLEX else HELP_WHO)
        }
        notes.forEach { Note(it, readable = true) }
    }
}

/** What New Channel can make a channel from. */
enum class NewChannelSource(val title: String, val pickTitle: String) {
    Show("From a Show", "Pick a Show"), Genre("From a Genre", "Pick a Genre"), Network("From a Network", "Pick a Network"),
    Collection("From a Collection", "Pick a Collection"), Playlist("From One of Your Playlists", "Pick a Playlist"),
}

/** New Channel: what to make it from, then a page to pick which one; picking it makes the channel and opens it here. */
@Composable
private fun NewChannel(state: ChannelSettingsViewModel.State, others: List<BaseItemDto>, cameFrom: NewChannelSource?, returnFocus: FocusRequester, onPick: (NewChannelSource) -> Unit) {
    TvGroup("New Channel")
    val plex = serverKind() == com.astermediaplayer.astermediaplayer.data.emby.ServerKind.Plex
    NewChannelSource.entries.forEach { source ->
        TvRow(source.title, if (state.creating) "Making…" else null, enabled = !state.creating, chevron = true, modifier = if (source == cameFrom) Modifier.focusRequester(returnFocus) else Modifier, onClick = { onPick(source) })
    }
    Note(if (plex) "A show plays its episodes in order; a genre, network or collection plays every movie and show in it (a network is a show's network, or a movie's studio). A playlist plays what you put in it. On Plex a playlist, and so its channel, is only yours."
        else "A show plays its episodes in order; a genre, network or collection plays every movie and show in it. A playlist plays what you put in it. Each is shared with everyone on your server.")
}

/** The page New Channel opens: what there is to make the channel from, one row each, in a list that makes only the rows showing. */
@Composable
private fun NewChannelPicker(source: NewChannelSource, state: ChannelSettingsViewModel.State, others: List<BaseItemDto>, modifier: Modifier, onPick: (id: String, name: String) -> Unit) {
    val u = Lockup.tvUnit
    // Each as an id to make it from and the name it's given.
    val options: List<Pair<String, String>> = when (source) {
        NewChannelSource.Show -> state.series.map { it.first.toString() to it.second }
        NewChannelSource.Genre -> state.genres.map { it to it }
        NewChannelSource.Network -> state.networks.map { it to it }
        NewChannelSource.Collection -> state.collections.map { it to it }
        NewChannelSource.Playlist -> others.map { it.plainId() to it.name.orEmpty() }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(options.isNotEmpty()) { if (options.isNotEmpty()) repeat(10) { if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect else delay(100) } }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier.fillMaxSize().padding(horizontal = (90f * u).dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = (40f * u).dp, bottom = (80f * u).dp),
        verticalArrangement = Arrangement.spacedBy((6f * u).dp),
    ) {
        item { Text(source.pickTitle, style = Type.pageTitle.copy(fontSize = (76f * u).sp, lineHeight = (90f * u).sp), modifier = Modifier.padding(bottom = (12f * u).dp)) }
        if (options.isEmpty()) item { Note(if (source == NewChannelSource.Playlist) "Every video playlist is a channel already." else "There's nothing to pick yet.", readable = true) }
        itemsIndexed(options) { i, option ->
            TvRow(option.second, null, enabled = !state.creating, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier, onClick = { onPick(option.first, option.second) })
        }
    }
}

@Composable
private fun serverKind() = (androidx.compose.ui.platform.LocalContext.current.applicationContext as AsterApp).repository.serverKind

/** A row of several choices: OK opens the menu, each press checks or unchecks one and the menu stays open. */
@Composable
private fun TvMultiRow(canEdit: Boolean, title: String, options: List<String>, selection: List<String>, none: String, onChange: (List<String>) -> Unit) {
    val value = selection.joinToString(", ").ifEmpty { none }
    if (!canEdit || options.isEmpty()) { TvRow(title, value, enabled = false) {}; return }
    var open by remember { mutableStateOf(false) }
    Box {
        TvRow(title, value, onClick = { open = true })
        MenuHost(expanded = open, onDismiss = { open = false }, below = true) {
            options.forEachIndexed { i, name ->
                val on = selection.any { it.equals(name, ignoreCase = true) }
                MenuItem(name, null, first = i == 0, checked = on) {
                    onChange(if (on) selection.filterNot { it.equals(name, ignoreCase = true) } else options.filter { o -> o == name || selection.any { it.equals(o, ignoreCase = true) } })
                }
            }
        }
    }
}

/** A choice row that only opens when the page can be edited. */
@Composable
private fun TvChoiceRowIf(canEdit: Boolean, title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    if (canEdit) TvChoiceRow(title, options, selected.coerceAtLeast(0), onPick = onPick)
    else TvRow(title, options.getOrNull(selected).orEmpty(), enabled = false) {}
}

@Composable
private fun TextDialog(title: String, initial: String, hint: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text(hint) }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
