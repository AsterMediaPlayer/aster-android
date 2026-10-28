package com.astermediaplayer.astermediaplayer.ui.anime

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.ui.components.ErrorBox
import com.astermediaplayer.astermediaplayer.ui.components.Load
import com.astermediaplayer.astermediaplayer.ui.components.LoadingBox
import com.astermediaplayer.astermediaplayer.ui.components.Shelf
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import com.astermediaplayer.astermediaplayer.ui.home.FeaturedBanner
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType

/** Everything in the libraries Shoko matched, which Movies and TV Shows leave out. */
data class AnimeContent(
    val libraries: List<BaseItemDto>,
    val resume: List<BaseItemDto>,
    val nextUp: List<BaseItemDto>,
    val recentlyAdded: List<BaseItemDto>,
    /** The OP/ED plugin's music video library (openings and endings) with its newest videos. */
    val musicVideoShelves: List<Pair<BaseItemDto, List<BaseItemDto>>> = emptyList(),
) {
    /** The banner's titles: anime only, picked the way Home picks its own. */
    val featured: List<BaseItemDto> = (resume.take(3) + nextUp.take(3) + recentlyAdded.take(5)).distinctBy { it.id }.take(8)
}

class AnimeViewModel(private val repo: AsterRepository) : ViewModel() {
    private val _state = MutableStateFlow<Load<AnimeContent>>(Load.Loading)
    val state: StateFlow<Load<AnimeContent>> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = Load.Loading
            _state.value = try {
                coroutineScope {
                    val views = repo.views()
                    val libraries = repo.animeLibraries(views)
                    val opEdVideosId = repo.opEdLibraries()?.videosLibraryId
                    val musicLibraries = views.filter { it.collectionType == CollectionType.MUSICVIDEOS && opEdVideosId != null && repo.plainId(it.id.toString()) == repo.plainId(opEdVideosId) }
                    val musicShelves = musicLibraries.map { library -> async { library to repo.latestMusicVideos(library.id) } }
                    val showLibraries = libraries.filter { it.collectionType == CollectionType.TVSHOWS }
                    val resume = libraries.map { async { runCatching { repo.resume(parentId = it.id) }.getOrDefault(emptyList()) } }
                    val next = showLibraries.map { async { runCatching { repo.nextUp(parentId = it.id) }.getOrDefault(emptyList()) } }
                    val latest = async { repo.recentlyAddedAnime(libraries) }
                    // Newest first across libraries.
                    val resumed = resume.awaitAll().flatten().sortedByDescending { it.userData?.lastPlayedDate }.take(20)
                    Load.Ready(
                        AnimeContent(
                            libraries = libraries + musicLibraries,
                            resume = resumed,
                            nextUp = next.awaitAll().flatten().filter { n -> resumed.none { it.id == n.id } }.take(20),
                            recentlyAdded = latest.await(),
                            musicVideoShelves = musicShelves.awaitAll(),
                        ),
                    )
                }
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
        }
    }
}

@Composable
fun AnimeScreen(
    viewModel: AnimeViewModel,
    onItem: (BaseItemDto) -> Unit,
    onPlay: (java.util.UUID) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        Load.Loading -> LoadingBox(modifier)
        is Load.Failed -> ErrorBox(s.message, viewModel::load, modifier)
        is Load.Ready -> Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = TabBarClearance), verticalArrangement = Arrangement.spacedBy(if (LocalIsTv.current) Lockup.sectionSpacing else 20.dp)) {
            if (s.value.featured.isNotEmpty()) {
                FeaturedBanner(s.value.featured, onItem)
            } else {
                Text(
                    "Anime", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            // Each opens the whole library as a grid.
            Shelf("Libraries", s.value.libraries, landscape = true, onItem = onItem)
            Shelf("Continue Watching", s.value.resume, landscape = true, onItem = onItem)
            Shelf("Next Up", s.value.nextUp, landscape = true, onItem = onItem)
            Shelf("Recently Added", s.value.recentlyAdded, landscape = true, onItem = onItem)
            // Openings & endings (the OP/ED plugin's library).
            s.value.musicVideoShelves.forEach { (library, items) -> Shelf(library.name.orEmpty(), items, landscape = true, onItem = onItem) }
        }
    }
}
