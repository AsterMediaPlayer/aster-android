package com.astermediaplayer.astermediaplayer.ui.search

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import org.jellyfin.sdk.model.api.BaseItemKind
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import com.astermediaplayer.astermediaplayer.ui.components.Shelf
import com.astermediaplayer.astermediaplayer.ui.music.ArtistLockup
import com.astermediaplayer.astermediaplayer.ui.music.SquareLockup
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.ui.components.PosterCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto

data class SearchState(
    val query: String = "",
    val results: List<BaseItemDto> = emptyList(),
    val searched: Boolean = false,
    val error: String? = null,
)

class SearchViewModel(private val repo: AsterRepository) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var job: Job? = null

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        job?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(results = emptyList(), searched = false, error = null) }
            return
        }
        job = viewModelScope.launch {
            delay(300) // let typing settle before hitting the server
            try {
                val results = repo.search(query.trim())
                _state.update { it.copy(results = results, searched = true, error = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Search failed") }
            }
        }
    }
}

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onItem: (BaseItemDto) -> Unit,
    onAlbum: (BaseItemDto) -> Unit,
    onArtist: (BaseItemDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as com.astermediaplayer.astermediaplayer.AsterApp
    val tv = LocalIsTv.current
    val u = Lockup.tvUnit
    val focus = remember { FocusRequester() }
    // On a TV the field is where the remote starts, and the system keyboard comes up with it.
    if (tv) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(modifier.fillMaxSize()) {
        if (tv) {
            var focused by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Lockup.screenPadding).padding(top = (120f * u).dp).height((86f * u).dp)
                    .clip(CircleShape).background(if (focused) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.12f)).padding(horizontal = (32f * u).dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((16f * u).dp),
            ) {
                Icon(Icons.Default.Search, null, tint = SecondaryText, modifier = Modifier.size((38f * u).dp))
                BasicTextField(
                    value = state.query, onValueChange = viewModel::setQuery, singleLine = true,
                    textStyle = Type.title3.copy(color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                    modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { focused = it.isFocused },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (state.query.isEmpty()) Text("Movies, shows, episodes, music", style = Type.title3.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = SecondaryText)
                            inner()
                        }
                    },
                )
            }
        } else OutlinedTextField(
            value = state.query, onValueChange = viewModel::setQuery, singleLine = true,
            placeholder = { Text("Movies, shows, episodes, music") },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        val results = state.results
        when {
            state.error != null -> Text(state.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            state.query.isBlank() -> SearchMessage(Icons.Default.Search, "Search", "Find movies, TV shows, episodes and music in your library.")
            state.searched && results.isEmpty() -> SearchMessage(Icons.Default.QuestionMark, "No Results", "Nothing matched \"${state.query.trim()}\".")
            else -> {
                val shows = results.filter { it.type == BaseItemKind.MOVIE || it.type == BaseItemKind.SERIES }
                val episodes = results.filter { it.type == BaseItemKind.EPISODE }
                val albums = results.filter { it.type == BaseItemKind.MUSIC_ALBUM || it.type == BaseItemKind.PLAYLIST }
                val artists = results.filter { it.type == BaseItemKind.MUSIC_ARTIST }
                val songs = results.filter { it.type == BaseItemKind.AUDIO }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = TabBarClearance), verticalArrangement = Arrangement.spacedBy(Lockup.sectionSpacing)) {
                    if (shows.isNotEmpty()) {
                        Text("Movies & TV Shows", style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding).padding(top = Lockup.sectionSpacing))
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val perRow = maxOf(1, ((maxWidth - Lockup.screenPadding * 2 + Lockup.gridSpacing) / (Lockup.posterWidth + Lockup.gridSpacing)).toInt())
                            Column(Modifier.padding(horizontal = Lockup.screenPadding), verticalArrangement = Arrangement.spacedBy(Lockup.gridRowSpacing)) {
                                shows.chunked(perRow).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(Lockup.gridSpacing)) { row.forEach { PosterCard(it, { onItem(it) }) } }
                                }
                            }
                        }
                    }
                    Shelf("Episodes", episodes, landscape = true, onItem = onItem)
                    SearchShelf("Albums & Playlists", albums) { SquareLockup(it) { onAlbum(it) } }
                    SearchShelf("Artists", artists) { ArtistLockup(it) { onArtist(it) } }
                    SearchShelf("Songs", songs) { song ->
                        SquareLockup(song) { app.music.play(songs, songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)) }
                    }
                }
            }
        }
    }
}

/** A titled row of cards for the music results. */
@Composable
private fun SearchShelf(title: String, items: List<BaseItemDto>, card: @Composable (BaseItemDto) -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Lockup.shelfTitleSpacing)) {
        Text(title, style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Lockup.screenPadding, vertical = Lockup.shelfVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing),
        ) { items(items, key = { it.id }) { card(it) } }
    }
}

@Composable
private fun SearchMessage(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    Column(Modifier.fillMaxWidth().height((500f * Lockup.tvUnit.coerceAtLeast(0.6f)).dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = SecondaryText, modifier = Modifier.size(56.dp))
        Text(title, style = Type.title3, modifier = Modifier.padding(top = 12.dp))
        Text(detail, style = Type.callout, color = SecondaryText, modifier = Modifier.padding(top = 6.dp, start = 32.dp, end = 32.dp))
    }
}
