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
import com.astermediaplayer.astermediaplayer.ui.components.MetaLine
import com.astermediaplayer.astermediaplayer.ui.components.episodeCode
import com.astermediaplayer.astermediaplayer.ui.components.remainingText
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.sp
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
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = TabBarClearance), verticalArrangement = Arrangement.spacedBy(20.dp)) {
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

/**
 * The hero banner, the Apple apps' `HeroCarousel` and `HeroCard`: as tall as the window, one title per swipeable page
 * with its logo, meta line, overview and a Play button over the bottom, and the page dots under the tab bar.
 */
/** A page's own button, in place of Play / Resume (Upcoming's "Watch Now" and "Go to Show"). */
data class HeroAction(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun FeaturedBanner(
    items: List<BaseItemDto>,
    onItem: (BaseItemDto) -> Unit,
    onPlay: (UUID) -> Unit,
    /** A short label over a page's title, e.g. Upcoming's countdown. */
    tag: (BaseItemDto) -> String? = { null },
    /** A page with an action only opens (its destination); one without plays. */
    action: (BaseItemDto) -> HeroAction? = { null },
    /** What selecting a page opens; the page's own title unless given. */
    destination: (BaseItemDto) -> BaseItemDto = { it },
) {
    val pager = rememberPagerState { items.size }
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    Box(Modifier.fillMaxWidth().height(windowHeight)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page -> FeaturedPage(items[page], onItem, onPlay, tag(items[page]), action(items[page]), destination) }
        if (items.size > 1) {
            // Capsule of dots at the bottom right, in line with the Play button's bottom.
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = Lockup.screenPadding, bottom = 84.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(items.size) { i ->
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == pager.currentPage) 0.95f else 0.35f)))
                }
            }
        }
    }
}

@Composable
private fun FeaturedPage(item: BaseItemDto, onItem: (BaseItemDto) -> Unit, onPlay: (UUID) -> Unit, tag: String?, action: HeroAction?, destination: (BaseItemDto) -> BaseItemDto) {
    val repo = (LocalContext.current.applicationContext as AsterApp).repository
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    // Portrait phones get the poster, which fills the tall banner; wide screens get the backdrop.
    val art = if (wide) repo.imageUrl(item, ImageType.BACKDROP, 1920) ?: repo.imageUrl(item, ImageType.PRIMARY, 1080)
    else repo.seriesPosterUrl(item, 1080) ?: repo.imageUrl(item, ImageType.PRIMARY, 1080) ?: repo.imageUrl(item, ImageType.BACKDROP, 1280)
    val logo = repo.imageUrl(item, ImageType.LOGO, 800)
    val playable = item.type != BaseItemKind.SERIES
    val remaining = remainingText(item)

    Box(Modifier.fillMaxSize().clickable { onItem(destination(item)) }) {
        AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        // Dark toward the bottom from just under the middle, and a little at the left edge.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 0.725f to Color.Black.copy(alpha = 0.2f), 1f to Color.Black.copy(alpha = 0.9f)),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.5f to Color.Transparent)))
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = Lockup.screenPadding).padding(bottom = 125.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (tag != null) {
                // White with black text, readable over any backdrop.
                Text(
                    tag.uppercase(), style = Type.headline.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp), color = Color.Black,
                    modifier = Modifier.clip(CircleShape).background(Color.White).padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            if (logo != null) {
                Box(Modifier.widthIn(max = 260.dp).height(90.dp).fillMaxWidth()) {
                    AsyncImage(model = logo, contentDescription = item.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize())
                }
            } else {
                Text(
                    if (item.type == BaseItemKind.EPISODE) item.seriesName ?: item.name.orEmpty() else item.name.orEmpty(),
                    style = Type.pageTitle.copy(fontSize = 34.sp, lineHeight = 41.sp), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.type == BaseItemKind.EPISODE) {
                Text(listOfNotNull(item.episodeCode(), item.name).joinToString(" · "), style = Type.title3, color = Color.White)
            }
            MetaLine(item)
            item.overview?.let {
                Text(it, style = Type.callout, color = SecondaryText, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 600.dp))
            }
            Row(
                Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.9f))
                    .clickable { if (action != null) onItem(destination(item)) else if (playable) onPlay(item.id) else onItem(item) }.padding(horizontal = 28.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(action?.icon ?: Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(17.dp))
                Text(
                    when {
                        action != null -> action.title
                        (item.userData?.playbackPositionTicks ?: 0L) > 0 && playable -> "Resume"
                        item.type == BaseItemKind.SERIES -> "Watch Now"
                        else -> "Play"
                    },
                    style = Type.headline, color = Color.Black,
                )
                if (action == null && playable && remaining != null) Text("·  $remaining", style = Type.headline.copy(fontWeight = FontWeight.Normal), color = Color.Black.copy(alpha = 0.5f))
            }
        }
    }
}
