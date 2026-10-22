package com.astermediaplayer.astermediaplayer.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.ui.components.ErrorBox
import com.astermediaplayer.astermediaplayer.ui.components.Load
import com.astermediaplayer.astermediaplayer.ui.components.LoadingBox
import com.astermediaplayer.astermediaplayer.ui.components.Shelf
import com.astermediaplayer.astermediaplayer.ui.components.progress
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID

data class DetailContent(
    val item: BaseItemDto,
    val seasons: List<BaseItemDto> = emptyList(),
    val selectedSeason: UUID? = null,
    val episodes: List<BaseItemDto> = emptyList(),
    /** What the main button plays: the item itself, or a series' next unwatched episode. */
    val playTarget: BaseItemDto? = null,
    val similar: List<BaseItemDto> = emptyList(),
)

class DetailViewModel(private val repo: AsterRepository, private val itemId: UUID) : ViewModel() {
    private val _state = MutableStateFlow<Load<DetailContent>>(Load.Loading)
    val state: StateFlow<Load<DetailContent>> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = Load.Loading
            _state.value = try {
                coroutineScope {
                    val item = repo.item(itemId)
                    val similar = async { runCatching { repo.similar(itemId) }.getOrDefault(emptyList()) }
                    when (item.type) {
                        BaseItemKind.SERIES -> {
                            val seasons = repo.seasons(itemId)
                            val next = async { runCatching { repo.nextUp(1, itemId).firstOrNull() }.getOrNull() }.await()
                            val season = next?.seasonId?.takeIf { id -> seasons.any { it.id == id } } ?: seasons.firstOrNull()?.id
                            val episodes = if (season != null) repo.episodes(itemId, season) else emptyList()
                            Load.Ready(DetailContent(item, seasons, season, episodes, next ?: episodes.firstOrNull(), similar.await()))
                        }
                        BaseItemKind.SEASON -> {
                            val seriesId = item.seriesId
                            val episodes = if (seriesId != null) repo.episodes(seriesId, itemId) else emptyList()
                            Load.Ready(DetailContent(item, episodes = episodes, playTarget = episodes.firstOrNull(), similar = emptyList()))
                        }
                        else -> Load.Ready(DetailContent(item, playTarget = item, similar = similar.await()))
                    }
                }
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
        }
    }

    fun selectSeason(seasonId: UUID) {
        val current = (_state.value as? Load.Ready)?.value ?: return
        _state.value = Load.Ready(current.copy(selectedSeason = seasonId))
        viewModelScope.launch {
            val episodes = runCatching { repo.episodes(itemId, seasonId) }.getOrDefault(emptyList())
            _state.update { s -> (s as? Load.Ready)?.let { Load.Ready(it.value.copy(episodes = episodes)) } ?: s }
        }
    }

    fun toggleFavorite() = updateUserData { item ->
        val next = !(item.userData?.isFavorite ?: false)
        repo.setFavorite(item.id, next)
        item.copy(userData = item.userData?.copy(isFavorite = next))
    }

    fun togglePlayed() = updateUserData { item ->
        val next = !(item.userData?.played ?: false)
        repo.setPlayed(item.id, next)
        item.copy(userData = item.userData?.copy(played = next, playbackPositionTicks = 0))
    }

    private fun updateUserData(change: suspend (BaseItemDto) -> BaseItemDto) {
        val current = (_state.value as? Load.Ready)?.value ?: return
        viewModelScope.launch {
            runCatching { change(current.item) }.onSuccess { updated ->
                _state.update { s -> (s as? Load.Ready)?.let { Load.Ready(it.value.copy(item = updated)) } ?: s }
            }
        }
    }
}

@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onPlay: (UUID) -> Unit,
    onItem: (BaseItemDto) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            Load.Loading -> LoadingBox()
            is Load.Failed -> ErrorBox(s.message, viewModel::load)
            is Load.Ready -> DetailBody(s.value, viewModel, onPlay, onItem)
        }
        IconButton(onClick = onBack, modifier = Modifier.padding(8.dp).padding(top = 24.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
    }
}

@Composable
private fun DetailBody(
    content: DetailContent,
    viewModel: DetailViewModel,
    onPlay: (UUID) -> Unit,
    onItem: (BaseItemDto) -> Unit,
) {
    val repo = (LocalContext.current.applicationContext as AsterApp).repository
    val item = content.item
    val target = content.playTarget
    val resuming = target?.progress() != null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AsyncImage(
                model = repo.imageUrl(item, ImageType.BACKDROP, 1280) ?: repo.imageUrl(item, ImageType.PRIMARY, 800),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(120.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))),
            )
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(item.name.orEmpty(), style = MaterialTheme.typography.headlineMedium)
            if (item.type == BaseItemKind.EPISODE) {
                Text(
                    listOfNotNull(item.seriesName, item.parentIndexNumber?.let { "S$it" }?.plus(item.indexNumber?.let { "E$it" } ?: "")).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(metaLine(item), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (target != null) {
                    Button(onClick = { onPlay(target.id) }) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text(
                            when {
                                resuming -> "Resume"
                                item.type == BaseItemKind.SERIES && target.id != item.id ->
                                    "Play S${target.parentIndexNumber ?: 1}:E${target.indexNumber ?: 1}"
                                else -> "Play"
                            },
                        )
                    }
                }
                FilledTonalIconToggleButton(checked = item.userData?.played == true, onCheckedChange = { viewModel.togglePlayed() }) {
                    Icon(Icons.Default.Check, "Watched")
                }
                FilledTonalIconToggleButton(checked = item.userData?.isFavorite == true, onCheckedChange = { viewModel.toggleFavorite() }) {
                    Icon(if (item.userData?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorite")
                }
            }
            item.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (!item.genres.isNullOrEmpty()) {
                Text(item.genres!!.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
        }

        if (content.seasons.size > 1) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 20.dp),
            ) {
                items(content.seasons, key = { it.id }) { season ->
                    FilterChip(
                        selected = season.id == content.selectedSeason,
                        onClick = { viewModel.selectSeason(season.id) },
                        label = { Text(season.name.orEmpty()) },
                    )
                }
            }
        }
        if (content.episodes.isNotEmpty()) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content.episodes.forEach { episode -> EpisodeRow(episode, repo, onPlay) }
            }
        }
        Shelf("More like this", content.similar, onItem = onItem)
    }
}

@Composable
private fun EpisodeRow(episode: BaseItemDto, repo: AsterRepository, onPlay: (UUID) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPlay(episode.id) }.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            AsyncImage(
                model = repo.imageUrl(episode, ImageType.PRIMARY, 400), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            episode.progress()?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(episode.indexNumber?.toString(), episode.name).joinToString(". "),
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                runtimeText(episode).orEmpty(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            episode.overview?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (episode.userData?.played == true) Icon(Icons.Default.Check, "Watched", tint = MaterialTheme.colorScheme.primary)
    }
}

private fun runtimeText(item: BaseItemDto): String? {
    val minutes = item.runTimeTicks?.let { it / 600_000_000L } ?: return null
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

private fun metaLine(item: BaseItemDto): String = listOfNotNull(
    item.productionYear?.toString(),
    item.officialRating,
    runtimeText(item),
    item.communityRating?.let { "★ %.1f".format(it) },
).joinToString("  ·  ")
