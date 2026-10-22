package com.astermediaplayer.astermediaplayer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.ui.components.cardTitle
import java.util.UUID
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.ui.components.ErrorBox
import com.astermediaplayer.astermediaplayer.ui.components.Load
import com.astermediaplayer.astermediaplayer.ui.components.LoadingBox
import com.astermediaplayer.astermediaplayer.ui.components.Shelf
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.CollectionType

/** Collection types whose libraries get no "Recently Added" shelf of their own on Home. */
private val NoLibraryShelf = setOf(
    CollectionType.MOVIES, CollectionType.TVSHOWS, CollectionType.MUSIC, CollectionType.PLAYLISTS,
    CollectionType.LIVETV, CollectionType.BOOKS, CollectionType.PHOTOS,
)

data class HomeContent(
    val resume: List<BaseItemDto>,
    val nextUp: List<BaseItemDto>,
    val movies: List<BaseItemDto>,
    val shows: List<BaseItemDto>,
    val anime: List<BaseItemDto>,
    val libraryShelves: List<Pair<BaseItemDto, List<BaseItemDto>>>,
) {
    /** What the banner pages through: what's being watched first, then the newest additions. */
    val featured: List<BaseItemDto> = (resume.take(3) + nextUp.take(3) + movies.take(4) + shows.take(3))
        .distinctBy { it.id }
        .take(8)
}

class HomeViewModel(private val repo: AsterRepository) : ViewModel() {
    private val _state = MutableStateFlow<Load<HomeContent>>(Load.Loading)
    val state: StateFlow<Load<HomeContent>> = _state.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init { load() }

    fun load(refresh: Boolean = false) {
        viewModelScope.launch {
            if (refresh) _refreshing.value = true else _state.value = Load.Loading
            _state.value = try {
                coroutineScope {
                    val views = repo.views()
                    val resume = async { repo.resume() }
                    val nextUp = async { repo.nextUp() }
                    // Anime has its own shelf, so the Movies and TV shelves skip its libraries.
                    val animeLibraries = repo.animeLibraries(views)
                    val animeIds = animeLibraries.map { it.id }.toSet()
                    val movies = views.firstOrNull { it.collectionType == CollectionType.MOVIES && it.id !in animeIds }
                        ?.let { library -> async { runCatching { repo.latest(library.id, kinds = listOf(BaseItemKind.MOVIE)) }.getOrDefault(emptyList()) } }
                    val shows = views.firstOrNull { it.collectionType == CollectionType.TVSHOWS && it.id !in animeIds }
                        ?.let { library -> async { runCatching { repo.latest(library.id, kinds = listOf(BaseItemKind.SERIES)) }.getOrDefault(emptyList()) } }
                    val anime = async { repo.recentlyAddedAnime(animeLibraries) }
                    val shelves = views.filter { it.collectionType !in NoLibraryShelf }
                        .map { view -> async { view to runCatching { repo.latest(view.id) }.getOrDefault(emptyList()) } }
                    val resumed = resume.await()
                    Load.Ready(
                        HomeContent(
                            resume = resumed,
                            nextUp = nextUp.await().filter { next -> resumed.none { it.id == next.id } },
                            movies = movies?.await().orEmpty(),
                            shows = shows?.await().orEmpty(),
                            anime = anime.await(),
                            libraryShelves = shelves.awaitAll().filter { it.second.isNotEmpty() },
                        ),
                    )
                }
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
            _refreshing.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onItem: (BaseItemDto) -> Unit,
    onPlay: (UUID) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    when (val s = state) {
        Load.Loading -> LoadingBox(modifier)
        is Load.Failed -> ErrorBox(s.message, { viewModel.load() }, modifier)
        is Load.Ready -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = { viewModel.load(refresh = true) }, modifier = modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = TabBarClearance)) {
                if (s.value.featured.isNotEmpty()) FeaturedBanner(s.value.featured, onItem, onPlay)
                Shelf("Continue Watching", s.value.resume, landscape = true, onItem = onItem)
                Shelf("Up Next", s.value.nextUp, landscape = true, onItem = onItem)
                Shelf("Recently Added Movies", s.value.movies, landscape = true, onItem = onItem)
                Shelf("Recently Added TV Shows", s.value.shows, landscape = true, onItem = onItem)
                Shelf("Recently Added Anime", s.value.anime, landscape = true, onItem = onItem)
                s.value.libraryShelves.forEach { (view, items) -> Shelf(view.name.orEmpty(), items, landscape = true, onItem = onItem) }
            }
        }
    }
}

/** Space the floating tab bar covers at the bottom of every tab. */
val TabBarClearance = 110.dp

@Composable
fun FeaturedBanner(items: List<BaseItemDto>, onItem: (BaseItemDto) -> Unit, onPlay: (UUID) -> Unit) {
    val pager = rememberPagerState { items.size }
    val height = (LocalConfiguration.current.screenHeightDp * 0.84f).dp
    Box(Modifier.fillMaxWidth().height(height)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page -> FeaturedPage(items[page], onItem, onPlay) }
        if (items.size > 1) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(items.size) { i ->
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.3f)))
                }
            }
        }
    }
}

@Composable
private fun FeaturedPage(item: BaseItemDto, onItem: (BaseItemDto) -> Unit, onPlay: (UUID) -> Unit) {
    val repo = (LocalContext.current.applicationContext as AsterApp).repository
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    // Portrait phones get the poster, which fills the tall banner; wide screens get the backdrop.
    val art = if (wide) repo.imageUrl(item, ImageType.BACKDROP, 1920) ?: repo.imageUrl(item, ImageType.PRIMARY, 1080)
    else repo.seriesPosterUrl(item, 1080) ?: repo.imageUrl(item, ImageType.PRIMARY, 1080) ?: repo.imageUrl(item, ImageType.BACKDROP, 1280)
    val logo = repo.imageUrl(item, ImageType.LOGO, 800)
    val playable = item.type != BaseItemKind.SERIES

    Box(Modifier.fillMaxSize().clickable { onItem(item) }) {
        AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.35f to Color.Transparent, 0.8f to Color.Black.copy(alpha = 0.85f), 1f to Color.Black),
            ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (logo != null) {
                AsyncImage(
                    model = logo, contentDescription = item.name, contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart, modifier = Modifier.fillMaxWidth(0.6f).heightIn(max = 110.dp),
                )
            } else {
                Text(item.cardTitle(), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    bannerMeta(item), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if ((item.width ?: 0) >= 3000) {
                    Text(
                        "4K", color = Color.White.copy(alpha = 0.75f), fontWeight = FontWeight.Bold,
                        modifier = Modifier.border(2.dp, Color.White.copy(alpha = 0.75f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                }
                item.communityRating?.let { Text("★ %.1f".format(it), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.titleMedium) }
            }
            item.overview?.let {
                Text(it, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            val left = timeLeft(item)
            Button(
                onClick = { if (playable) onPlay(item.id) else onItem(item) },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.92f), contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth(0.78f).height(56.dp),
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        !playable -> "View"
                        left != null -> "Resume"
                        else -> "Play"
                    },
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium,
                )
                if (playable && left != null) Text("  ·  $left", color = Color.Black.copy(alpha = 0.6f), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun bannerMeta(item: BaseItemDto): String = listOfNotNull(
    item.productionYear?.toString(),
    item.officialRating,
    item.runTimeTicks?.let { formatDuration(it / TICKS_PER_MINUTE) },
    item.genres?.take(2)?.joinToString(", ")?.ifEmpty { null },
).joinToString("  ·  ")

private fun timeLeft(item: BaseItemDto): String? {
    val position = item.userData?.playbackPositionTicks ?: return null
    val total = item.runTimeTicks ?: return null
    if (position <= 0) return null
    return formatDuration((total - position) / TICKS_PER_MINUTE) + " left"
}

private const val TICKS_PER_MINUTE = 600_000_000L

private fun formatDuration(minutes: Long): String = if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
