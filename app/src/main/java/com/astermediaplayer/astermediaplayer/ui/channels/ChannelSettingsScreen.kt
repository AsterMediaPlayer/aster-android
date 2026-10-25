package com.astermediaplayer.astermediaplayer.ui.channels

import androidx.compose.foundation.layout.Arrangement
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
import kotlinx.coroutines.delay
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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            try {
                val admin = app.repository.isAdministrator()
                val playlists = app.repository.videoPlaylists()
                _state.update { it.copy(playlists = playlists, admin = admin, loaded = true, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(loaded = true, error = e.message ?: "Couldn't load") }
            }
        }
    }

    /** Applies [change] to a playlist's channel tags and saves them; the page shows the change at once and undoes it if the save fails. */
    fun update(playlist: BaseItemDto, change: (ChannelTags) -> ChannelTags) {
        val current = _state.value
        if (!current.admin || current.refused) return
        val before = current.playlists.firstOrNull { it.id == playlist.id } ?: return
        val previous = before.tags.orEmpty()
        var tags = change(ChannelTags.from(previous))
        if (tags.number == null) tags = tags.copy(number = numbers(current.playlists)[playlist.plainId()])
        val updated = tags.applied(previous)
        replace(playlist, updated)
        viewModelScope.launch {
            try {
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
    var folded by remember { mutableStateOf(setOf<String>()) }
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

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = (90f * u).dp).padding(top = (40f * u).dp, bottom = (80f * u).dp),
        verticalArrangement = Arrangement.spacedBy((6f * u).dp),
    ) {
        Text(
            if (only != null) shownChannels.firstOrNull()?.let { it.second.name ?: it.first.name } ?: "Channel" else "Channels (Beta)",
            style = Type.pageTitle.copy(fontSize = (76f * u).sp, lineHeight = (90f * u).sp), modifier = Modifier.padding(bottom = (12f * u).dp),
        )
        when {
            !state.loaded -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), Alignment.Center) { CircularProgressIndicator() }
            playlists.isEmpty() -> Note(if (state.error != null) state.error!! else "No video playlists yet. Make one in Jellyfin, or with Add to Playlist on any movie.")
            else -> {
                if (only == null) Note("Turn video playlists into TV-style channels. Each plays its movies and episodes around the clock, the same on every device; tune in and you join whatever's on.")
                if (!state.admin) Note("Only your server's administrator can set up channels. Ask them to, here or in Jellyfin.")
                else if (state.refused) Note("The server wouldn't save that, so channels are read-only now.")
                if (only != null && shownChannels.isEmpty()) Note("This playlist isn't a channel any more. Turn it back on in Edit Channels.")
                shownChannels.forEachIndexed { index, (playlist, tags) ->
                    val id = playlist.plainId()
                    val open = id in expanded
                    TvGroup("Channel ${numbers[id] ?: 0}")
                    TvRow(
                        tags.name ?: playlist.name.orEmpty(), if (open) "Hide" else "Edit", modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                        onClick = { expanded = if (open) expanded - id else expanded + id },
                    )
                    Note(summary(playlist, tags))
                    if (open) ChannelSettingsRows(viewModel, playlist, tags, channels.mapNotNull { it.second.group }, numbers[id] ?: 0, canEdit, id in folded) { folded = if (id in folded) folded - id else folded + id }
                }
                if (only == null && others.isNotEmpty()) {
                    TvGroup(if (channels.isEmpty()) "Video Playlists" else "More Playlists")
                    others.forEachIndexed { index, playlist ->
                        TvSwitchRow(playlist.name.orEmpty(), false, enabled = canEdit, modifier = if (shownChannels.isEmpty() && index == 0) Modifier.focusRequester(first) else Modifier) { on ->
                            if (on) { expanded = expanded + playlist.plainId(); viewModel.turnOn(playlist) }
                        }
                    }
                }
                if (only == null) clashes(channels).forEach { Note(it) }
            }
        }
    }
    state.error?.takeIf { playlists.isNotEmpty() }?.let {
        AlertDialog(onDismissRequest = viewModel::dismissError, title = { Text("Channels") }, text = { Text(it) }, confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("OK") } })
    }
}

@Composable
private fun Note(text: String) {
    val u = Lockup.tvUnit
    Text(text, style = Type.caption, color = SecondaryText, modifier = Modifier.padding(horizontal = (8f * u).dp, vertical = (4f * u).dp))
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
    if (tags.users.isNotEmpty()) parts += "Only " + tags.users.joinToString(", ")
    return parts.joinToString(" · ")
}

/** An open channel's settings: the essentials, then the rest folded away until asked for. */
@Composable
private fun ChannelSettingsRows(
    viewModel: ChannelSettingsViewModel, playlist: BaseItemDto, tags: ChannelTags, groups: List<String>, number: Int,
    canEdit: Boolean, advancedOpen: Boolean, toggleAdvanced: () -> Unit,
) {
    fun change(edit: (ChannelTags) -> ChannelTags) = viewModel.update(playlist, edit)
    var naming by remember { mutableStateOf<String?>(null) }

    TvSwitchRow("Channel", true, enabled = canEdit) { on -> if (!on) change { it.copy(isChannel = false) } }
    TvRow("Name", tags.name ?: playlist.name.orEmpty(), enabled = canEdit) { naming = "name" }
    val groupNames = (groups + listOfNotNull(tags.group)).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    TvChoiceRowIf(canEdit, "Group", listOf("None") + groupNames, if (tags.group == null) 0 else groupNames.indexOf(tags.group) + 1) { i -> change { it.copy(group = if (i == 0) null else groupNames[i - 1]) } }
    TvRow("New Group", null, enabled = canEdit) { naming = "group" }
    val numberOptions = (1..maxOf(99, number)).toList()
    TvChoiceRowIf(canEdit, "Number", numberOptions.map { "$it" }, ((tags.number ?: number) - 1).coerceIn(0, numberOptions.lastIndex)) { i -> change { it.copy(number = numberOptions[i]) } }
    TvChoiceRowIf(canEdit, "Mode", ChannelMode.entries.map { it.title }, ChannelMode.entries.indexOf(tags.mode)) { i -> change { it.copy(mode = ChannelMode.entries[i]) } }
    TvSwitchRow("Playlist Picture as Logo", tags.showsLogo, enabled = canEdit) { on -> change { it.copy(showsLogo = on) } }
    TvSwitchRow("Logo in the Corner While Playing", tags.showsBug, enabled = canEdit) { on -> change { it.copy(showsBug = on) } }
    TvRow("Advanced", if (advancedOpen) "Hide" else "Show", onClick = toggleAdvanced)
    if (advancedOpen) {
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
        Note("Every setting is a tag on the playlist in Jellyfin, so you can change it there too.")
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
