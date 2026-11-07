package com.astermediaplayer.astermediaplayer.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrFilter
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrRequest
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrRequestFilter
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrRequestOptions
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrService
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrServiceChoices
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrStatus
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Requests and where each stands: everyone's for whoever manages them (Approve, Edit, Decline), else the viewer's own (Cancel while it waits). */
@Composable
internal fun RequestsList(model: RequestsModel, header: @Composable () -> Unit, modifier: Modifier) {
    LaunchedEffect(model) { model.start() }
    val tv = LocalIsTv.current
    var confirming by remember { mutableStateOf<Pair<SeerrRequest, Boolean>?>(null) }
    var editing by remember { mutableStateOf<SeerrRequest?>(null) }
    LazyColumn(Modifier.then(modifier).fillMaxSize(), contentPadding = PaddingValues(bottom = TabBarClearance), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(key = "header") { header() }
        item(key = "filters") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = discoverInset),
                horizontalArrangement = Arrangement.spacedBy(if (tv) pt(40f) else 16.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionPicker(SeerrRequestFilter.entries.map { it.label }, model.filter.ordinal, if (tv) Modifier else Modifier.width(520.dp)) { model.showFilter(SeerrRequestFilter.entries[it]) }
                if (model.manages) SectionPicker(listOf("Everyone's", "Mine"), if (model.mine) 1 else 0, if (tv) Modifier else Modifier.width(200.dp)) { model.showMine(it == 1) }
            }
        }
        model.problem?.let { p -> item(key = "problem") { Note(p, color = Color(0xFFFF6961)) } }
        if (model.requests.isEmpty() && !model.loading) item(key = "none") { Note("No requests here.") }
        items(model.requests, key = { it.id }) { request ->
            RequestRow(model, request, onConfirm = { decline -> confirming = request to decline }, onEdit = { editing = request })
        }
        if (model.loading) item(key = "loading") { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
    }
    confirming?.let { (request, decline) ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(if (decline) "Decline this request?" else "Cancel this request?") },
            confirmButton = { TextButton({ confirming = null; model.act(request, if (decline) RequestsModel.Action.Decline else RequestsModel.Action.Cancel) }) { Text(if (decline) "Decline" else "Cancel Request", color = Color(0xFFFF6961)) } },
            dismissButton = { TextButton({ confirming = null }) { Text("Keep It") } },
        )
    }
    editing?.let { request ->
        DestinationDialog(
            kind = if (request.type == "movie") SeerrFilter.Kind.Movie else SeerrFilter.Kind.Tv,
            current = request.serverId?.let { SeerrRequestOptions(it, request.profileId, request.rootFolder, request.languageProfileId, request.is4k) },
            // A 4K request stays one; a usual one stays usual.
            fourK = request.is4k, confirmTitle = "Save",
            onDismiss = { editing = null },
        ) { options -> if (options != null) model.update(request, options) }
    }
}

@Composable
private fun RequestRow(model: RequestsModel, request: SeerrRequest, onConfirm: (decline: Boolean) -> Unit, onEdit: () -> Unit) {
    val tv = LocalIsTv.current
    val title = model.titles[request.titleKey]
    val posterWidth = if (tv) pt(120f) else 60.dp
    Row(
        Modifier.fillMaxWidth().padding(horizontal = discoverInset, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(if (tv) pt(28f) else 16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (title != null) SeerrTitleLockup(title, width = posterWidth, labels = false)
        else Box(Modifier.width(posterWidth).heightIn(min = posterWidth * 1.5f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF262626)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title?.displayTitle ?: "…", style = Type.title3, maxLines = 2)
            Text(detailLine(request, title?.year), style = Type.callout, color = SecondaryText)
            Text(request.stateLabel, style = Type.calloutSemibold, color = stateColor(request))
            request.requestedByName?.let { who ->
                Text(listOfNotNull(who, request.createdDay?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))).joinToString(" · "), style = Type.caption, color = SecondaryText)
            }
        }
        if (model.working == request.id) CircularProgressIndicator(Modifier.size(24.dp))
        else if (request.isPending) Row(horizontalArrangement = Arrangement.spacedBy(if (tv) pt(16f) else 8.dp)) {
            if (model.manages) {
                Chip("Approve", true, icon = Icons.Default.Check) { model.act(request, RequestsModel.Action.Approve) }
                Chip("Edit", false, icon = Icons.Default.Tune) { onEdit() }
                Chip("Decline", false, icon = Icons.Default.Close) { onConfirm(true) }
            }
            if (request.requestedById == model.userId) Chip("Cancel", false, icon = Icons.Default.Delete) { onConfirm(false) }
        }
    }
}

private fun detailLine(request: SeerrRequest, year: String?): String {
    val parts = listOfNotNull(year, if (request.type == "movie") "Film" else "Series").toMutableList()
    val numbers = request.seasons.sorted()
    if (numbers.isNotEmpty()) parts += if (numbers.size == 1) "Season ${numbers[0]}" else "Seasons ${numbers.joinToString(", ")}"
    if (request.is4k) parts += "4K"
    return parts.joinToString(" · ")
}

private fun stateColor(request: SeerrRequest): Color = when (request.status) {
    1 -> Color(0xFFFF9F0A)
    3, 4 -> Color(0xFFFF453A)
    else -> if (request.mediaStatus == SeerrStatus.Available) Color(0xFF30D158) else Color(0xFF0A84FF)
}

/**
 * An admin's choice of where a request goes, as Seerr's own Advanced panel offers it: which Sonarr or Radarr, the folder, the
 * quality, and Sonarr's language. It starts on Seerr's defaults (its anime ones for an anime show), or on what a pending
 * request already has. [confirm] gets null when Seerr has no server to choose (it then uses its default).
 */
@Composable
internal fun DestinationDialog(
    kind: SeerrFilter.Kind,
    anime: Boolean = false,
    current: SeerrRequestOptions? = null,
    fourK: Boolean? = null,
    confirmTitle: String = "Request",
    onDismiss: () -> Unit,
    confirm: suspend (SeerrRequestOptions?) -> Unit,
) {
    val repo = (androidx.compose.ui.platform.LocalContext.current.applicationContext as AsterApp).repository
    val scope = rememberCoroutineScope()
    val tv = LocalIsTv.current
    var services by remember { mutableStateOf<List<SeerrService>>(emptyList()) }
    var choices by remember { mutableStateOf<Map<Int, SeerrServiceChoices>>(emptyMap()) }
    var options by remember { mutableStateOf<SeerrRequestOptions?>(null) }
    var loading by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    suspend fun loadChoices(server: Int) {
        if (choices.containsKey(server)) return
        val client = repo.seerr()?.client ?: return
        try { choices = choices + (server to client.serviceChoices(kind, server)) } catch (e: Exception) { problem = e.message }
    }
    LaunchedEffect(Unit) {
        try {
            val client = repo.seerr()?.client
            services = client?.services(kind, fourK).orEmpty()
            // The request's own server; else the default (the usual copy's, unless only 4K ones are offered).
            val start = current?.let { c -> services.firstOrNull { it.id == c.serverId && it.is4k == c.is4k } }
                ?: services.firstOrNull { it.isDefault && it.is4k == (fourK ?: false) } ?: services.firstOrNull()
            if (start != null) {
                options = if (current?.serverId == start.id) current else SeerrRequestOptions.defaults(start, anime)
                loadChoices(start.id)
            }
        } catch (e: Exception) {
            problem = e.message
        }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = if (tv) pt(1000f) else 460.dp).fillMaxWidth().clip(RoundedCornerShape(if (tv) pt(40f) else 24.dp))
                .background(Color(0xF21C1C1E)).padding(if (tv) pt(36f) else 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (kind == SeerrFilter.Kind.Tv) "Where the Series Goes" else "Where the Movie Goes", style = Type.title3, color = Color.White)
            val o = options
            when {
                loading -> CircularProgressIndicator()
                o == null -> Text("Seerr has no ${if (kind == SeerrFilter.Kind.Tv) "Sonarr" else "Radarr"} server to choose; it uses its own setting.", style = Type.callout, color = SecondaryText)
                else -> {
                    if (services.size > 1) Field("Server") {
                        ChipMenu(
                            services.firstOrNull { it.id == o.serverId }?.let(::serverName) ?: "Server", false,
                            services.map(::serverName), services.indexOfFirst { it.id == o.serverId },
                        ) { i -> val s = services[i]; options = SeerrRequestOptions.defaults(s, anime); scope.launch { loadChoices(s.id) } }
                    }
                    val found = choices[o.serverId]
                    if (found == null) CircularProgressIndicator()
                    else {
                        if (found.rootFolders.isNotEmpty()) Field("Folder") {
                            ChipMenu(o.rootFolder ?: "Folder", false, found.rootFolders.map(::folderLabel), found.rootFolders.indexOfFirst { it.first == o.rootFolder }) { options = o.copy(rootFolder = found.rootFolders[it].first) }
                        }
                        if (found.profiles.isNotEmpty()) Field("Quality") {
                            ChipMenu(found.profiles.firstOrNull { it.first == o.profileId }?.second ?: "Quality", false, found.profiles.map { it.second }, found.profiles.indexOfFirst { it.first == o.profileId }) { options = o.copy(profileId = found.profiles[it].first) }
                        }
                        if (found.languageProfiles.isNotEmpty()) Field("Language") {
                            ChipMenu(found.languageProfiles.firstOrNull { it.first == o.languageProfileId }?.second ?: "Language", false, found.languageProfiles.map { it.second }, found.languageProfiles.indexOfFirst { it.first == o.languageProfileId }) { options = o.copy(languageProfileId = found.languageProfiles[it].first) }
                        }
                    }
                }
            }
            problem?.let { Text(it, style = Type.callout, color = Color(0xFFFF6961)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                Chip("Cancel", false, icon = Icons.Default.Close) { onDismiss() }
                Chip(confirmTitle, true, icon = Icons.Default.Check, enabled = !loading && !working) {
                    working = true
                    scope.launch {
                        try { confirm(options); onDismiss() } catch (e: Exception) { problem = e.message } finally { working = false }
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = Type.callout.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        content()
    }
}

private fun serverName(s: SeerrService) = if (s.is4k && !s.name.contains("4k", ignoreCase = true)) "${s.name} (4K)" else s.name

/** "/data/anime (2.1 TB free)". */
private fun folderLabel(folder: Pair<String, Long?>): String {
    val free = folder.second ?: return folder.first
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    var size = free.toDouble()
    var unit = 0
    while (size >= 1000 && unit < units.lastIndex) { size /= 1000; unit++ }
    return "${folder.first} (${String.format(java.util.Locale.US, if (size < 10) "%.1f" else "%.0f", size)} ${units[unit]} free)"
}
