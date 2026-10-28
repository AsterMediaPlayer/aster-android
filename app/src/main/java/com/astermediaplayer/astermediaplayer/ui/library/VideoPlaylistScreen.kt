package com.astermediaplayer.astermediaplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.ui.BackButton
import com.astermediaplayer.astermediaplayer.ui.components.LandscapeCard
import com.astermediaplayer.astermediaplayer.ui.detail.ActionButton
import com.astermediaplayer.astermediaplayer.ui.detail.MenuItem
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/**
 * A playlist of movies and episodes: Play All and Shuffle, and its titles in order, each of which a long press moves up or
 * down or takes out of the playlist. The Apple apps' `VideoPlaylistView`. [onPlay] plays the titles given, in that order.
 */
@Composable
fun VideoPlaylistScreen(repo: AsterRepository, playlistId: UUID, onItem: (BaseItemDto) -> Unit, onPlay: (List<UUID>) -> Unit, onBack: () -> Unit) {
    val playlist by produceState<BaseItemDto?>(null, playlistId) { value = runCatching { repo.item(playlistId) }.getOrNull() }
    var items by remember(playlistId) { mutableStateOf<List<BaseItemDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(playlistId) {
        items = runCatching { repo.playlistItems(playlistId) }.onFailure { error = it.message }.getOrDefault(emptyList())
    }

    // Each change shows at once and is put back if the server refuses it.
    fun change(updated: List<BaseItemDto>, call: suspend () -> Unit) {
        val previous = items
        items = updated
        scope.launch { runCatching { call() }.onFailure { items = previous; error = it.message ?: "The playlist couldn't be changed." } }
    }
    fun remove(item: BaseItemDto) {
        val entry = item.playlistItemId ?: return
        change(items.orEmpty().filterNot { it.playlistItemId == entry }) { repo.removeFromPlaylist(playlistId, listOf(entry)) }
    }
    fun move(item: BaseItemDto, to: Int) {
        val entry = item.playlistItemId ?: return
        val list = items.orEmpty().toMutableList()
        val from = list.indexOfFirst { it.playlistItemId == entry }.takeIf { it >= 0 } ?: return
        list.add(to, list.removeAt(from))
        change(list) { repo.movePlaylistItem(playlistId, entry, to) }
    }

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val room = maxWidth - Lockup.screenPadding * 2
            val perRow = maxOf(1, ((room + Lockup.shelfSpacing) / (Lockup.landscapeWidth + Lockup.shelfSpacing)).toInt())
            val list = items
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 56.dp + Lockup.screenPadding / 2).padding(horizontal = Lockup.screenPadding),
                        verticalArrangement = Arrangement.spacedBy(Lockup.sectionSpacing),
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(playlist?.name.orEmpty(), style = Type.pageTitle.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                            list?.let { Text("${it.size} item${if (it.size == 1) "" else "s"}", style = Type.callout, color = SecondaryText) }
                        }
                        if (!list.isNullOrEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ActionButton("Play All", Icons.Default.PlayArrow, prominent = true) { onPlay(list.map { it.id }) }
                            ActionButton("Shuffle", Icons.Default.Shuffle) { onPlay(list.shuffled().map { it.id }) }
                        }
                        error?.let { Text(it, style = Type.callout, color = MaterialTheme.colorScheme.error) }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                when {
                    list == null -> item { Box(Modifier.fillMaxWidth().padding(Lockup.pageBottomPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    list.isEmpty() -> item {
                        Column(Modifier.fillMaxWidth().height(320.dp).padding(horizontal = Lockup.screenPadding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("This playlist is empty", style = Type.title3)
                            Text("Use Add to Playlist on a movie or episode to fill it.", style = Type.callout, color = SecondaryText, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    else -> items(list.withIndex().chunked(perRow), key = { row -> row.first().value.playlistItemId ?: row.first().index.toString() }) { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = Lockup.screenPadding).padding(bottom = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing),
                        ) {
                            row.forEach { (position, item) ->
                                LandscapeCard(item, { onItem(item) }, menu = { dismiss ->
                                    if (position > 0) MenuItem("Move Up", Icons.Default.ArrowUpward, first = true) { dismiss(); move(item, position - 1) }
                                    if (position < list.size - 1) MenuItem("Move Down", Icons.Default.ArrowDownward, first = position == 0) { dismiss(); move(item, position + 1) }
                                    MenuItem("Remove from Playlist", Icons.Default.Delete, first = list.size == 1) { dismiss(); remove(item) }
                                })
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(TabBarClearance)) }
            }
        }
        BackButton(onBack)
    }
}
