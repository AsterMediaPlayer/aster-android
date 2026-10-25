package com.astermediaplayer.astermediaplayer.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.ui.settings.SRow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/** "Add to Playlist": pick one of the user's video playlists, or name a new one, and the items go in. */
@Composable
fun PlaylistPicker(itemIds: List<UUID>, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as AsterApp).repository
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<BaseItemDto>?>(null) }
    var newName by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { playlists = runCatching { repo.videoPlaylists() }.getOrDefault(emptyList()) }

    fun attempt(name: String, block: suspend () -> Unit) {
        working = true; error = null
        scope.launch {
            try { block(); onDone(name) } catch (e: Exception) { error = e.message ?: "That didn't work."; working = false }
        }
    }

    var naming by remember { mutableStateOf(false) }

    fun create(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty() && !working) { naming = false; attempt(trimmed) { repo.createPlaylist(trimmed, itemIds) } }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
            Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Add to Playlist", style = MaterialTheme.typography.headlineMedium)
                Text("${itemIds.size} item${if (itemIds.size == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                // The name is typed in its own small dialog: a TV's keyboard keeps the arrow keys while a field is focused, which would trap the remote here.
                SRow("New Playlist…", icon = Icons.Default.Add, enabled = !working) { naming = true }
                when (val list = playlists) {
                    null -> CircularProgressIndicator()
                    else -> if (list.isEmpty()) Text("No video playlists yet. Make one above.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        Text("Your Playlists", style = MaterialTheme.typography.titleMedium)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(list, key = { it.id }) { playlist ->
                                val count = playlist.childCount
                                SRow(playlist.name.orEmpty(), count?.let { "$it item${if (it == 1) "" else "s"}" }, enabled = !working) {
                                    attempt(playlist.name.orEmpty()) { repo.addToPlaylist(playlist.id, itemIds) }
                                }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (naming) NamePlaylistDialog(onCreate = ::create, onDismiss = { naming = false })
}

/** A small dialog for the new playlist's name; the keyboard's Done makes it. */
@Composable
private fun NamePlaylistDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.background(Color(0xFF1C1C1E), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("New Playlist", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                name, { name = it }, Modifier.widthIn(min = 420.dp).focusRequester(focus), singleLine = true, placeholder = { Text("Playlist name") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onCreate(name) }),
            )
            Text("Press Done on the keyboard to create it.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
