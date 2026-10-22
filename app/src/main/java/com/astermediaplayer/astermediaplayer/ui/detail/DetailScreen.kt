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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.ui.player.ThemePlayer
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.AppSettings
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.OpEdTheme
import com.astermediaplayer.astermediaplayer.data.OpeningEnding
import com.astermediaplayer.astermediaplayer.data.OpeningsEndings
import com.astermediaplayer.astermediaplayer.data.isAnime
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
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.PersonKind
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
    /** An anime series' openings and endings: the OP/ED plugin's, else ones found in the episodes' chapters. */
    val openingsEndings: List<OpeningEnding> = emptyList(),
    /** What the Watch OP and Watch ED buttons play: on a series page the selected season's, on an episode page the ones that play in it. */
    val watchOpening: OpeningEnding? = null,
    val watchEnding: OpeningEnding? = null,
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
                    val ready = loadContent()
                    val content = ready.value
                    val anime = isAnime(content.item)
                    val list = if (content.item.type == BaseItemKind.SERIES && anime) seriesThemes(content.item) else emptyList()
                    val withList = content.copy(openingsEndings = list)
                    val (opening, ending) = watchThemes(withList, anime)
                    Load.Ready(withList.copy(watchOpening = opening, watchEnding = ending))
                }
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
        }
    }

    private suspend fun loadContent(): Load.Ready<DetailContent> = coroutineScope {
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

    private var episodeThemes: List<OpEdTheme>? = null
    private val seasonThemes = mutableMapOf<UUID, List<OpEdTheme>>()

    private suspend fun isAnime(item: BaseItemDto): Boolean = when (item.type) {
        BaseItemKind.SERIES -> item.isAnime()
        BaseItemKind.EPISODE -> item.seriesId?.let { runCatching { repo.item(it).isAnime() }.getOrDefault(false) } == true
        else -> false
    }

    /** The plugin's themes when it has any that play (or soon will) for the show, otherwise clips from chapters. */
    private suspend fun seriesThemes(series: BaseItemDto): List<OpeningEnding> {
        val themes = repo.opEdThemes(series.id).orEmpty().filter { it.playablePath != null || it.isPreparing }
        if (themes.isNotEmpty()) return themes.map { OpeningEnding.Theme(it) }
        return repo.chapterThemes(series.id).map { OpeningEnding.Chapter(it) }
    }

    /** The first opening (or ending) that plays, video before audio. */
    private fun preferredTheme(themes: List<OpEdTheme>, opening: Boolean): OpEdTheme? {
        val matching = themes.filter { it.isOpening == opening && it.playablePath != null }
        return matching.firstOrNull { it.videoMp4Url != null } ?: matching.firstOrNull()
    }

    /** A plugin opening that can stand in behind the page: its video, or with only audio, its song when openings have sound. */
    private fun backgroundTheme(themes: List<OpEdTheme>, sound: Boolean): OpEdTheme? {
        val matching = themes.filter { it.isOpening && !it.spoiler && !it.nsfw && (it.videoMp4Url != null || (sound && it.audioM4aUrl != null)) }
        return matching.firstOrNull { it.videoMp4Url != null } ?: matching.firstOrNull()
    }

    /** The opening for a series' background, and whether it's for the selected season (else the show's own first). */
    class BackgroundOpening(val entry: OpeningEnding, val forSeason: Boolean)

    /**
     * Anime with no theme video of its own loops an opening behind the page instead, never one marked as a spoiler or
     * NSFW. Preference: the plugin's opening for the selected season, then one found in that season's chapters, then
     * the show's first opening of either kind.
     */
    suspend fun backgroundOpening(content: DetailContent, sound: Boolean): BackgroundOpening? {
        if (content.item.type != BaseItemKind.SERIES || content.openingsEndings.isEmpty()) return null
        val themes = content.openingsEndings.filterIsInstance<OpeningEnding.Theme>().map { it.theme }
        val clips = content.openingsEndings.filterIsInstance<OpeningEnding.Chapter>().map { it.clip }.filter { it.opening }
        val seasonId = content.selectedSeason
        if (seasonId != null) {
            if (themes.isNotEmpty()) {
                val forSeason = seasonThemes.getOrPut(seasonId) { repo.opEdThemes(seasonId).orEmpty() }
                    .filter { it.seasonId != null && repo.plainId(it.seasonId) == repo.plainId(seasonId.toString()) }
                backgroundTheme(forSeason, sound)?.let { return BackgroundOpening(OpeningEnding.Theme(it), forSeason = true) }
            }
            val number = content.seasons.firstOrNull { it.id == seasonId }?.indexNumber
            clips.firstOrNull { it.season == number }?.let { return BackgroundOpening(OpeningEnding.Chapter(it), forSeason = true) }
        }
        backgroundTheme(themes, sound)?.let { return BackgroundOpening(OpeningEnding.Theme(it), forSeason = false) }
        return clips.firstOrNull()?.let { BackgroundOpening(OpeningEnding.Chapter(it), forSeason = false) }
    }

    private suspend fun watchThemes(content: DetailContent, anime: Boolean): Pair<OpeningEnding?, OpeningEnding?> =
        watchTheme(content, opening = true, anime) to watchTheme(content, opening = false, anime)

    /**
     * Series: the plugin's theme for the selected season, else that season's chapter clip, else the show's first.
     * Episode: the plugin's theme that plays in it (then its season's, then a show-wide one), else the episode's own
     * chapter when the show is anime.
     */
    private suspend fun watchTheme(content: DetailContent, opening: Boolean, anime: Boolean): OpeningEnding? {
        val item = content.item
        when (item.type) {
            BaseItemKind.SERIES -> {
                val themes = content.openingsEndings.filterIsInstance<OpeningEnding.Theme>().map { it.theme }
                val clips = content.openingsEndings.filterIsInstance<OpeningEnding.Chapter>().map { it.clip }.filter { it.opening == opening }
                val seasonId = content.selectedSeason
                if (seasonId != null) {
                    if (themes.isNotEmpty()) {
                        val forSeason = seasonThemes.getOrPut(seasonId) { repo.opEdThemes(seasonId).orEmpty() }
                            .filter { it.seasonId != null && repo.plainId(it.seasonId) == repo.plainId(seasonId.toString()) }
                        preferredTheme(forSeason, opening)?.let { return OpeningEnding.Theme(it) }
                    }
                    val number = content.seasons.firstOrNull { it.id == seasonId }?.indexNumber
                    clips.firstOrNull { it.season == number }?.let { return OpeningEnding.Chapter(it) }
                }
                preferredTheme(themes, opening)?.let { return OpeningEnding.Theme(it) }
                return clips.firstOrNull()?.let { OpeningEnding.Chapter(it) }
            }
            BaseItemKind.EPISODE -> {
                val all = episodeThemes ?: repo.opEdThemes(item.id).orEmpty().also { episodeThemes = it }
                val candidates = all.filter { it.isOpening == opening && it.playablePath != null }
                val pick = candidates.firstOrNull { it.playsInEpisode == true }
                    ?: candidates.firstOrNull { it.seasonId != null && item.seasonId != null && repo.plainId(it.seasonId) == repo.plainId(item.seasonId.toString()) }
                    ?: candidates.filter { it.seasonId == null }.minByOrNull { it.sequence }
                if (pick != null) return OpeningEnding.Theme(pick)
                if (anime) OpeningsEndings.find(listOf(item)).firstOrNull { it.opening == opening }?.let { return OpeningEnding.Chapter(it) }
                return null
            }
            else -> return null
        }
    }

    fun selectSeason(seasonId: UUID) {
        val current = (_state.value as? Load.Ready)?.value ?: return
        _state.value = Load.Ready(current.copy(selectedSeason = seasonId))
        viewModelScope.launch {
            val episodes = runCatching { repo.episodes(itemId, seasonId) }.getOrDefault(emptyList())
            _state.update { s -> (s as? Load.Ready)?.let { Load.Ready(it.value.copy(episodes = episodes)) } ?: s }
            val updated = (_state.value as? Load.Ready)?.value ?: return@launch
            if (updated.openingsEndings.isNotEmpty()) {
                val (opening, ending) = watchThemes(updated, anime = true)
                _state.update { s -> (s as? Load.Ready)?.let { Load.Ready(it.value.copy(watchOpening = opening, watchEnding = ending)) } ?: s }
            }
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
    onPlayTheme: (OpeningEnding) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            Load.Loading -> LoadingBox()
            is Load.Failed -> ErrorBox(s.message, viewModel::load)
            is Load.Ready -> DetailBody(s.value, viewModel, onPlay, onItem, onPlayTheme)
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
    onPlayTheme: (OpeningEnding) -> Unit,
) {
    val app = LocalContext.current.applicationContext as AsterApp
    val repo = app.repository
    val item = content.item
    val target = content.playTarget
    val themePlayer = app.themePlayer
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

    // The title's theme song and video, shared with its series so moving to an episode doesn't restart them.
    DisposableEffect(item.id) {
        themePlayer.pageAppeared(item)
        onDispose { themePlayer.pageDisappeared(item) }
    }
    LaunchedEffect(item.id, settings.themeMusic, settings.themeVideos) {
        themePlayer.show(item, settings.themeMusic, settings.themeVideos)
    }
    // Picking another season swaps in that season's opening.
    LaunchedEffect(item.id, content.selectedSeason, content.openingsEndings.size, settings.themeVideos, settings.openingSound) {
        if (!settings.themeVideos) return@LaunchedEffect
        val opening = viewModel.backgroundOpening(content, settings.openingSound) ?: return@LaunchedEffect
        themePlayer.showOpening(opening.entry, item, sound = settings.openingSound, onlyIfNone = !opening.forSeason && content.selectedSeason != null)
    }
    // Quiet while the app is in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) themePlayer.suspend() else if (event == Lifecycle.Event.ON_START) themePlayer.resume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val resuming = target?.progress() != null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AsyncImage(
                model = repo.imageUrl(item, ImageType.BACKDROP, 1280) ?: repo.imageUrl(item, ImageType.PRIMARY, 800),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            ThemeVideo(themePlayer, item, Modifier.fillMaxSize())
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
            if (content.watchOpening != null || content.watchEnding != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    content.watchOpening?.let { OutlinedButton(onClick = { onPlayTheme(it) }) { Icon(Icons.Default.MusicNote, null); Text("Watch OP", Modifier.padding(start = 6.dp)) } }
                    content.watchEnding?.let { OutlinedButton(onClick = { onPlayTheme(it) }) { Icon(Icons.Default.MusicNote, null); Text("Watch ED", Modifier.padding(start = 6.dp)) } }
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
        if (content.openingsEndings.isNotEmpty()) {
            Column(Modifier.padding(top = 20.dp)) {
                Text("Openings & Endings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(content.openingsEndings) { oe -> ThemeCard(oe, onClick = { onPlayTheme(oe) }) }
                }
            }
        }
        CastRow(item.people.orEmpty(), repo)
        Shelf("More like this", content.similar, onItem = onItem)
    }
}

/** The theme video looping over the still backdrop, once it has drawn a frame and only when it belongs to this title. */
@OptIn(UnstableApi::class)
@Composable
private fun ThemeVideo(themePlayer: ThemePlayer, item: BaseItemDto, modifier: Modifier = Modifier) {
    val key by themePlayer.key.collectAsStateWithLifecycle()
    val video by themePlayer.video.collectAsStateWithLifecycle()
    val ready by themePlayer.videoReady.collectAsStateWithLifecycle()
    val player = video
    if (player == null || key != (item.seriesId ?: item.id).toString()) return
    val alpha by animateFloatAsState(if (ready) 1f else 0f, tween(600), label = "themeVideo")
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = modifier.alpha(alpha),
    )
}

@Composable
private fun ThemeCard(oe: OpeningEnding, onClick: () -> Unit) {
    val playable = when (oe) { is OpeningEnding.Theme -> oe.theme.playablePath != null; is OpeningEnding.Chapter -> true }
    val (title, detail) = when (oe) {
        is OpeningEnding.Theme -> (oe.theme.code) to (oe.theme.songLine ?: if (oe.theme.isPreparing) "Preparing…" else null)
        is OpeningEnding.Chapter -> oe.clip.title to "%d:%02d".format(oe.clip.seconds / 60, oe.clip.seconds % 60)
    }
    Row(
        Modifier.width(240.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = playable, onClick = onClick).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.MusicNote, null, tint = if (playable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun CastRow(people: List<BaseItemPerson>, repo: AsterRepository) {
    val cast = people.filter { it.type == PersonKind.ACTOR || it.type == PersonKind.GUEST_STAR }.ifEmpty { people }.take(30)
    if (cast.isEmpty()) return
    Column(Modifier.padding(top = 20.dp)) {
        Text("Cast", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cast, key = { "${it.id}-${it.role}" }) { person ->
                Column(Modifier.width(90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        AsyncImage(
                            model = repo.personImageUrl(person, 200), contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Text(person.name.orEmpty(), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    person.role?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
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
