package com.astermediaplayer.astermediaplayer.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import org.jellyfin.sdk.model.api.MediaStreamType
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import com.astermediaplayer.astermediaplayer.ui.components.MetaLine
import com.astermediaplayer.astermediaplayer.ui.components.episodeCode
import com.astermediaplayer.astermediaplayer.ui.components.remainingText
import com.astermediaplayer.astermediaplayer.ui.theme.ButtonGrey
import com.astermediaplayer.astermediaplayer.ui.theme.DetailMetrics
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicVideo
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    /** A show's next episode to watch (null when there isn't one), which the Play button leads with. */
    val nextEpisode: BaseItemDto? = null,
    /** An episode page: the episodes of its season, in the strip under its buttons. */
    val seasonEpisodes: List<BaseItemDto> = emptyList(),
    /** Behind the scenes, trailers and deleted scenes. */
    val extras: List<BaseItemDto> = emptyList(),
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
        val extras = async {
            if (item.type in setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.SEASON)) repo.specialFeatures(itemId) else emptyList()
        }
        val content = when (item.type) {
            BaseItemKind.SERIES -> {
                val seasons = repo.seasons(itemId)
                val next = runCatching { repo.nextUp(1, itemId).firstOrNull() }.getOrNull()
                // The season with the next episode, else the first real one (specials come first as season 0).
                val season = next?.seasonId?.takeIf { id -> seasons.any { it.id == id } }
                    ?: seasons.firstOrNull { (it.indexNumber ?: 0) > 0 }?.id ?: seasons.firstOrNull()?.id
                val episodes = if (season != null) repo.episodes(itemId, season) else emptyList()
                DetailContent(item, seasons, season, episodes, next ?: episodes.firstOrNull(), nextEpisode = next)
            }
            BaseItemKind.SEASON -> {
                val seriesId = item.seriesId
                val seasons = if (seriesId != null) runCatching { repo.seasons(seriesId) }.getOrDefault(emptyList()) else emptyList()
                val episodes = if (seriesId != null) repo.episodes(seriesId, itemId) else emptyList()
                DetailContent(item, seasons, itemId, episodes, playTarget = episodes.firstOrNull { it.userData?.played != true } ?: episodes.firstOrNull())
            }
            BaseItemKind.EPISODE -> {
                val seriesId = item.seriesId
                val seasonId = item.seasonId
                val seasonEpisodes = if (seriesId != null && seasonId != null) runCatching { repo.episodes(seriesId, seasonId) }.getOrDefault(emptyList()) else emptyList()
                DetailContent(item, playTarget = item, seasonEpisodes = seasonEpisodes)
            }
            else -> DetailContent(item, playTarget = item)
        }
        Load.Ready(content.copy(similar = similar.await(), extras = extras.await()))
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
    onPlayFromBeginning: (UUID) -> Unit,
    onGoToSeries: (UUID) -> Unit,
    onItem: (BaseItemDto) -> Unit,
    onPlayTheme: (OpeningEnding) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = state) {
            Load.Loading -> LoadingBox()
            is Load.Failed -> ErrorBox(s.message, viewModel::load)
            is Load.Ready -> DetailBody(s.value, viewModel, onPlay, onPlayFromBeginning, onGoToSeries, onItem, onPlayTheme)
        }
        // Over the art, so a dark round button like the Apple apps' back button.
        IconButton(
            onClick = onBack,
            modifier = Modifier.statusBarsPadding().padding(start = Lockup.screenPadding, top = 4.dp)
                .size(DetailMetrics.backButtonSize).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
    }
}

/** The tabs of a show's or season's page. Everything else is one scroll of shelves. */
private enum class DetailTab(val label: String) { Episodes("Episodes"), Music("Music"), Extras("Extras"), Related("Related"), Details("Details") }

@Composable
private fun DetailBody(
    content: DetailContent,
    viewModel: DetailViewModel,
    onPlay: (UUID) -> Unit,
    onPlayFromBeginning: (UUID) -> Unit,
    onGoToSeries: (UUID) -> Unit,
    onItem: (BaseItemDto) -> Unit,
    onPlayTheme: (OpeningEnding) -> Unit,
) {
    val app = LocalContext.current.applicationContext as AsterApp
    val repo = app.repository
    val item = content.item
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

    val usesTabs = item.type == BaseItemKind.SERIES || item.type == BaseItemKind.SEASON
    val tabs = buildList {
        add(DetailTab.Episodes)
        if (content.openingsEndings.isNotEmpty()) add(DetailTab.Music)
        if (content.extras.isNotEmpty()) add(DetailTab.Extras)
        if (content.similar.isNotEmpty()) add(DetailTab.Related)
        add(DetailTab.Details)
    }
    var selectedTab by rememberSaveable { mutableStateOf(DetailTab.Episodes.name) }
    val tab = tabs.firstOrNull { it.name == selectedTab } ?: tabs.first()
    val scroll = rememberScrollState()
    // The Play button's middle across the screen, which the episode strip lines the next episode up under.
    var playCenterPx by remember { mutableStateOf<Float?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        val landscape = maxWidth > maxHeight

        // The art fills the screen behind the page and stays put while the page scrolls over it.
        val wide = maxWidth >= 600.dp
        AsyncImage(
            model = repo.imageUrl(item, ImageType.BACKDROP, if (wide) 1920 else 1280)
                ?: repo.seriesPosterUrl(item, 1080) ?: repo.imageUrl(item, ImageType.PRIMARY, 1080),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
        ThemeVideo(themePlayer, item, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.3f to Color.Transparent, 0.75f to Color.Black.copy(alpha = 0.7f), 1f to Color.Black.copy(alpha = 0.9f)),
            ),
        )

        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            if (landscape) {
                // Only the title shows over the art; the info is a scroll away.
                Box(Modifier.fillMaxWidth().height(screenHeight)) {
                    DetailTitle(item, repo, Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 24.dp, bottom = 24.dp))
                }
                DetailInfo(content, viewModel, onPlay, onPlayFromBeginning, onGoToSeries, onPlayTheme, showTitle = false) { playCenterPx = it }
            } else {
                Column(Modifier.fillMaxWidth().heightIn(min = DetailMetrics.headerMinHeight), verticalArrangement = Arrangement.Bottom) {
                    DetailInfo(content, viewModel, onPlay, onPlayFromBeginning, onGoToSeries, onPlayTheme, showTitle = true) { playCenterPx = it }
                }
            }
            Column(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.88f)).navigationBarsPadding()
                    .padding(top = DetailMetrics.sectionSpacing, bottom = DetailMetrics.pageBottomPadding),
                verticalArrangement = Arrangement.spacedBy(DetailMetrics.sectionSpacing),
            ) {
                if (usesTabs) {
                    SegmentedControl(tabs.map { it.label }, tabs.indexOf(tab)) { selectedTab = tabs[it].name }
                    when (tab) {
                        DetailTab.Episodes -> EpisodesSection(content, viewModel, onPlay)
                        DetailTab.Music -> MusicSection(content, onPlayTheme)
                        DetailTab.Extras -> Shelf("Extras", content.extras, landscape = true, onItem = { onPlay(it.id) })
                        DetailTab.Related -> Shelf("Related", content.similar, landscape = true, onItem = onItem)
                        DetailTab.Details -> DetailsSection(item, repo)
                    }
                } else {
                    // A movie's or an episode's page: one shelf after another.
                    if (item.type == BaseItemKind.EPISODE) EpisodeStrip(content.seasonEpisodes, item.id, playCenterPx, onPlay)
                    if (content.openingsEndings.isNotEmpty()) MusicSection(content, onPlayTheme)
                    Shelf("Extras", content.extras, landscape = true, onItem = { onPlay(it.id) })
                    Shelf("Related", content.similar, landscape = true, onItem = onItem)
                    DetailsSection(item, repo)
                }
            }
        }
    }
}

/** An episode or season page names its show above its own title. */
private fun BaseItemDto.isEpisodic() = type == BaseItemKind.EPISODE || type == BaseItemKind.SEASON

/** The logo in its box (a show's, on an episode or season page), or the name as text when there isn't one. */
@Composable
private fun DetailTitle(item: BaseItemDto, repo: AsterRepository, modifier: Modifier = Modifier, small: Boolean = false) {
    val logo = repo.imageUrl(item, ImageType.LOGO, 800)
    val name = if (item.isEpisodic()) item.seriesName ?: item.name else item.name
    if (logo != null) {
        // The box is its full size whatever the logo's shape, with the logo fit inside and centered vertically.
        Box(
            modifier.widthIn(max = if (small) DetailMetrics.seriesLogoMaxWidth else DetailMetrics.logoMaxWidth)
                .height(if (small) DetailMetrics.seriesLogoMaxHeight else DetailMetrics.logoMaxHeight),
        ) {
            AsyncImage(
                model = logo, contentDescription = name, contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize(),
            )
        }
    } else if (small) {
        Text(name.orEmpty(), style = Type.title3, color = SecondaryText, modifier = modifier)
    } else {
        Text(name.orEmpty(), style = Type.pageTitle, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
    }
}

/** The title, meta line, overview, what plays next and the buttons, over the art. */
@Composable
private fun DetailInfo(
    content: DetailContent,
    viewModel: DetailViewModel,
    onPlay: (UUID) -> Unit,
    onPlayFromBeginning: (UUID) -> Unit,
    onGoToSeries: (UUID) -> Unit,
    onPlayTheme: (OpeningEnding) -> Unit,
    showTitle: Boolean,
    onPlayCenter: (Float) -> Unit,
) {
    val repo = (LocalContext.current.applicationContext as AsterApp).repository
    val item = content.item
    val target = content.playTarget
    val isSeries = item.type == BaseItemKind.SERIES
    val resumable = (if (isSeries) content.nextEpisode else target)?.let { (it.userData?.playbackPositionTicks ?: 0L) > 0 } == true
    var menu by remember { mutableStateOf(false) }
    val seriesId = item.seriesId

    Column(
        Modifier.fillMaxWidth().padding(horizontal = Lockup.screenPadding).padding(bottom = DetailMetrics.headerBottomPadding),
        verticalArrangement = Arrangement.spacedBy(DetailMetrics.headerSpacing),
    ) {
        if (item.isEpisodic()) {
            // The show's logo (else its name), then this page's own title.
            if (showTitle) DetailTitle(item, repo, small = true)
            Text(item.name.orEmpty(), style = Type.pageTitle, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.type == BaseItemKind.EPISODE) item.episodeCode()?.let { Text(it, style = Type.headline, color = SecondaryText) }
        } else if (showTitle) {
            DetailTitle(item, repo)
        }
        MetaLine(item, showsUnwatched = true)
        item.overview?.let {
            Text(it, style = Type.callout, color = Color.White, maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
        if (isSeries && content.nextEpisode != null) {
            val next = content.nextEpisode
            Text("Up next: " + listOfNotNull(next.episodeCode(), next.name).joinToString(" · "), style = Type.callout, color = SecondaryText)
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(DetailMetrics.actionRowSpacing)) {
            // A phone can't fit every action across, so the row scrolls sideways.
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(DetailMetrics.actionSpacing), verticalAlignment = Alignment.CenterVertically,
            ) {
                if (target != null) {
                    ActionButton(
                        when {
                            isSeries -> if (content.nextEpisode == null) "Play" else if (resumable) "Resume" else "Play Next"
                            else -> if (resumable) "Resume" else "Play"
                        },
                        Icons.Default.PlayArrow, prominent = true,
                        modifier = Modifier.onGloballyPositioned { onPlayCenter(it.boundsInRoot().center.x) },
                    ) { onPlay(target.id) }
                    if (resumable) ActionButton("From Beginning", Icons.Default.Replay) { onPlayFromBeginning(target.id) }
                }
                IconActionButton(if (item.userData?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorite") { viewModel.toggleFavorite() }
                if (!isSeries && item.type != BaseItemKind.SEASON) {
                    IconActionButton(if (item.userData?.played == true) Icons.Default.CheckCircle else Icons.Outlined.CheckCircle, "Watched") { viewModel.togglePlayed() }
                }
                if (item.type == BaseItemKind.EPISODE && seriesId != null) {
                    Box {
                        IconActionButton(Icons.Default.MoreHoriz, "More") { menu = true }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Go to Series") },
                                leadingIcon = { Icon(Icons.Default.Tv, null) },
                                onClick = { menu = false; onGoToSeries(seriesId) },
                            )
                        }
                    }
                }
                if (!isSeries) remainingText(item)?.let { Text(it, style = Type.callout, color = SecondaryText, maxLines = 1) }
            }
            if (content.watchOpening != null || content.watchEnding != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(DetailMetrics.themeButtonSpacing)) {
                    content.watchOpening?.let { ActionButton("Watch OP", Icons.Default.MusicVideo) { onPlayTheme(it) } }
                    content.watchEnding?.let { ActionButton("Watch ED", Icons.AutoMirrored.Filled.QueueMusic) { onPlayTheme(it) } }
                }
            }
        }
    }
}

/** The Apple apps' capsule action button: white with black text for the main action, grey for the rest. */
@Composable
private fun ActionButton(title: String, icon: ImageVector, modifier: Modifier = Modifier, prominent: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier.height(DetailMetrics.buttonHeight).clip(CircleShape).background(if (prominent) Color.White else ButtonGrey)
            .clickable(onClick = onClick).padding(horizontal = DetailMetrics.buttonHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val color = if (prominent) Color.Black else Color.White
        Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
        Text(title, style = Type.headline, color = color, maxLines = 1, softWrap = false)
    }
}

/** Heart, watched and more: a capsule as tall as the text buttons. */
@Composable
private fun IconActionButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(DetailMetrics.iconButtonWidth, DetailMetrics.buttonHeight).clip(CircleShape).background(ButtonGrey).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color.White, modifier = Modifier.size(20.dp)) }
}

/** The system segmented control: a rounded track with the picked segment raised in it. */
@Composable
private fun SegmentedControl(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.padding(horizontal = Lockup.screenPadding).widthIn(max = DetailMetrics.segmentedMaxWidth).fillMaxWidth()
            .height(DetailMetrics.segmentedHeight).clip(CircleShape).background(Color.White.copy(alpha = 0.19f)).padding(2.dp),
    ) {
        labels.forEachIndexed { index, label ->
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                    .background(if (index == selected) Color.White.copy(alpha = 0.21f) else Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = Type.segmented, color = Color.White, maxLines = 1) }
        }
    }
}

@Composable
private fun EpisodesSection(content: DetailContent, viewModel: DetailViewModel, onPlay: (UUID) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DetailMetrics.seasonShelfSpacing)) {
        if (content.seasons.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Lockup.screenPadding, vertical = DetailMetrics.seasonVerticalPadding),
                horizontalArrangement = Arrangement.spacedBy(DetailMetrics.seasonSpacing),
            ) {
                items(content.seasons, key = { it.id }) { season ->
                    val active = season.id == content.selectedSeason
                    // The Apple apps' bordered buttons: white tint (black text) when picked, grey tint otherwise.
                    Box(
                        Modifier.height(DetailMetrics.seasonPillHeight).clip(CircleShape)
                            .background((if (active) Color.White else ButtonGrey).copy(alpha = 0.25f))
                            .clickable { viewModel.selectSeason(season.id) }.padding(horizontal = DetailMetrics.seasonPillHorizontalPadding),
                        contentAlignment = Alignment.Center,
                    ) { Text(season.name.orEmpty(), style = Type.calloutSemibold, color = if (active) Color.Black else Color.White, maxLines = 1) }
                }
            }
        }
        Shelf("Episodes", content.episodes, landscape = true, onItem = { onPlay(it.id) }, showSeriesTitle = false)
    }
}

/**
 * An episode page's strip: the other episodes of its season, scrolled so the next one sits centered under the Play
 * button (the one before it, on a season's last), as near as the row can scroll.
 */
@Composable
private fun EpisodeStrip(season: List<BaseItemDto>, currentId: UUID, playCenterPx: Float?, onPlay: (UUID) -> Unit) {
    val episodes = season.filter { it.id != currentId }
    if (episodes.isEmpty()) return
    val index = season.indexOfFirst { it.id == currentId }
    val targetId = season.getOrNull(index + 1)?.id ?: season.getOrNull(index - 1)?.id
    val state = rememberLazyListState()
    val density = LocalDensity.current
    LaunchedEffect(currentId, playCenterPx, episodes.size) {
        val center = playCenterPx ?: return@LaunchedEffect
        val target = episodes.indexOfFirst { it.id == targetId }.takeIf { it >= 0 } ?: return@LaunchedEffect
        with(density) {
            val card = Lockup.landscapeWidth.toPx()
            val cardStart = Lockup.screenPadding.toPx() + target * (card + Lockup.shelfSpacing.toPx())
            state.scrollToItem(0)
            state.scrollBy((cardStart - (center - card / 2)).coerceAtLeast(0f))
        }
    }
    Shelf("Episodes", episodes, landscape = true, onItem = { onPlay(it.id) }, listState = state, showSeriesTitle = false)
}

/** An anime title's openings and endings, as a list of cards. */
@Composable
private fun MusicSection(content: DetailContent, onPlayTheme: (OpeningEnding) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Lockup.shelfTitleSpacing)) {
        Text("Openings & Endings", style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding))
        Column(Modifier.padding(horizontal = Lockup.screenPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content.openingsEndings.forEach { oe -> ThemeCard(oe, onClick = { onPlayTheme(oe) }) }
        }
    }
}

/** Who's in it, and the facts about it. */
@Composable
private fun DetailsSection(item: BaseItemDto, repo: AsterRepository) {
    CastRow(item.people.orEmpty(), repo)
    InformationSection(item)
}

/** Cast and crew in a row of round photos. */
@Composable
private fun CastRow(people: List<BaseItemPerson>, repo: AsterRepository) {
    val cast = people.take(40)
    if (cast.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Lockup.shelfTitleSpacing)) {
        Text("Cast & Crew", style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Lockup.screenPadding, vertical = Lockup.shelfVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(Lockup.personSpacing),
        ) {
            items(cast, key = { "${it.id}-${it.role}-${it.type}" }) { person ->
                Column(Modifier.width(Lockup.personWidth), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Lockup.personLabelSpacing)) {
                    Box(Modifier.size(Lockup.personPhotoSize).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        AsyncImage(
                            model = repo.personImageUrl(person, 240), contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Text(person.name.orEmpty(), style = Type.calloutMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    person.role?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = Type.caption, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Director, writers, studio, genres, rating and release date, three across. */
@Composable
private fun InformationSection(item: BaseItemDto) {
    val people = item.people.orEmpty()
    val rows = buildList {
        people.filter { it.type == PersonKind.DIRECTOR }.mapNotNull { it.name }.takeIf { it.isNotEmpty() }?.let { add("Director" to it.joinToString(", ")) }
        people.filter { it.type == PersonKind.WRITER }.mapNotNull { it.name }.takeIf { it.isNotEmpty() }?.let { add("Writers" to it.take(4).joinToString(", ")) }
        item.studios?.mapNotNull { it.name }?.takeIf { it.isNotEmpty() }?.let { add("Studio" to it.take(3).joinToString(", ")) }
        item.genres?.takeIf { it.isNotEmpty() }?.let { add("Genres" to it.joinToString(", ")) }
        item.officialRating?.let { add("Rated" to it) }
        item.premiereDate?.let { add("Released" to it.toString().take(10)) }
    }
    if (rows.isEmpty()) return
    Column(Modifier.padding(horizontal = Lockup.screenPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Information", style = Type.title3)
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            rows.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, value) ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(label, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = SecondaryText)
                            Text(value, style = Type.callout)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
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
