package com.astermediaplayer.astermediaplayer.ui.library

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.ui.components.ErrorBox
import com.astermediaplayer.astermediaplayer.ui.components.LandscapeCard
import com.astermediaplayer.astermediaplayer.ui.components.Load
import com.astermediaplayer.astermediaplayer.ui.components.LoadingBox
import com.astermediaplayer.astermediaplayer.ui.components.PosterCard
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType

/** What the Library page shows: the libraries, the favourite titles and the video playlists. */
data class LibraryHomeContent(val libraries: List<BaseItemDto>, val favorites: List<BaseItemDto>, val playlists: List<BaseItemDto>)

class LibraryHomeViewModel(private val repo: AsterRepository) : ViewModel() {
    private val _state = MutableStateFlow<Load<LibraryHomeContent>>(Load.Loading)
    val state: StateFlow<Load<LibraryHomeContent>> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = try {
                coroutineScope {
                    val favorites = async { runCatching { repo.favoriteTitles() }.getOrDefault(emptyList()) }
                    val playlists = async { runCatching { repo.videoPlaylists() }.getOrDefault(emptyList()) }
                    val libraries = repo.views().filter { it.collectionType != CollectionType.PLAYLISTS }
                    Load.Ready(LibraryHomeContent(libraries, favorites.await(), playlists.await()))
                }
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
        }
    }
}

/** The Library tab: every library as a tile, then the favourites and the playlists (the Apple apps' `LibraryView`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryHomeScreen(viewModel: LibraryHomeViewModel, onItem: (BaseItemDto) -> Unit, modifier: Modifier = Modifier, topInset: Dp = 0.dp) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        Load.Loading -> LoadingBox(modifier)
        is Load.Failed -> ErrorBox(s.message, viewModel::load, modifier)
        is Load.Ready -> Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 8.dp + topInset, bottom = TabBarClearance),
            verticalArrangement = Arrangement.spacedBy(Lockup.sectionSpacing),
        ) {
            Text("Library", style = Type.pageTitle.copy(fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = 1.0.sp), modifier = Modifier.padding(horizontal = Lockup.screenPadding))
            val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT && !LocalIsTv.current
            if (s.value.libraries.isNotEmpty() && portrait) {
                // A phone held upright wraps the tiles down the page, instead of a row that runs off the side.
                FlowRow(
                    modifier = Modifier.padding(horizontal = Lockup.screenPadding, vertical = Lockup.shelfVerticalPadding),
                    horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing),
                    verticalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing),
                ) { s.value.libraries.forEach { library -> key(library.id) { LibraryTile(library) { onItem(library) } } } }
            } else if (s.value.libraries.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Lockup.screenPadding, vertical = Lockup.shelfVerticalPadding),
                    horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing),
                ) { items(s.value.libraries, key = { it.id }) { LibraryTile(it) { onItem(it) } } }
            }
            ShelfOf("Favorites", s.value.favorites) { item ->
                if (item.type == BaseItemKind.EPISODE) LandscapeCard(item, { onItem(item) }) else PosterCard(item, { onItem(item) })
            }
            ShelfOf("Playlists", s.value.playlists) { LandscapeCard(it, { onItem(it) }) }
        }
    }
}

@Composable
private fun ShelfOf(title: String, items: List<BaseItemDto>, card: @Composable (BaseItemDto) -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Lockup.shelfTitleSpacing)) {
        Text(title, style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Lockup.screenPadding, vertical = Lockup.shelfVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing),
        ) { items(items, key = { it.id }) { card(it) } }
    }
}

/** A library (user view) as a wide tile: its picture, darkening toward the bottom, and its name. */
@Composable
private fun LibraryTile(library: BaseItemDto, onClick: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as AsterApp).repository
    // The Apple apps' order for a wide picture: its Thumb, then its backdrop, then the Primary.
    val picture = repo.imageUrl(library, ImageType.THUMB, 800) ?: repo.imageUrl(library, ImageType.BACKDROP, 800) ?: repo.imageUrl(library, ImageType.PRIMARY, 800)
    val icon = when (library.collectionType) {
        CollectionType.MOVIES -> Icons.Default.Movie
        CollectionType.TVSHOWS -> Icons.Default.Tv
        CollectionType.MUSIC -> Icons.Default.MusicNote
        CollectionType.BOXSETS -> Icons.Default.Layers
        else -> Icons.Default.Folder
    }
    Box(
        Modifier.width(Lockup.landscapeWidth).height(Lockup.landscapeWidth / Lockup.LANDSCAPE_ASPECT)
            .clip(RoundedCornerShape(Lockup.cornerRadius)).background(Color(0xFF1C1C1E)).clickable(onClick = onClick),
    ) {
        if (picture != null) AsyncImage(model = picture, contentDescription = library.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(icon, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.Center).size(32.dp))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
        Text(library.name.orEmpty(), style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
    }
}
