package com.astermediaplayer.astermediaplayer.ui

import androidx.compose.ui.focus.focusRestorer
import com.astermediaplayer.astermediaplayer.ui.detail.ThemeNowPlayingPanel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.IconButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astermediaplayer.astermediaplayer.data.Audiobook
import com.astermediaplayer.astermediaplayer.data.AudiobookShelf
import com.astermediaplayer.astermediaplayer.ui.audiobooks.AudiobookBar
import com.astermediaplayer.astermediaplayer.data.channels.ChannelTuner
import com.astermediaplayer.astermediaplayer.ui.channels.ChannelsScreen
import com.astermediaplayer.astermediaplayer.ui.auth.ServerLockScreen
import com.astermediaplayer.astermediaplayer.ui.settings.ServerActions
import com.astermediaplayer.astermediaplayer.ui.channels.ChannelSettingsScreen
import com.astermediaplayer.astermediaplayer.ui.channels.ChannelSettingsViewModel
import com.astermediaplayer.astermediaplayer.ui.downloads.DownloadsScreen
import com.astermediaplayer.astermediaplayer.ui.upcoming.UpcomingScreen
import com.astermediaplayer.astermediaplayer.ui.upcoming.UpcomingViewModel
import com.astermediaplayer.astermediaplayer.ui.livetv.LiveTvScreen
import com.astermediaplayer.astermediaplayer.ui.livetv.ProgramScreen
import com.astermediaplayer.astermediaplayer.ui.livetv.ProgramViewModel
import com.astermediaplayer.astermediaplayer.ui.livetv.SeriesTimerScreen
import com.astermediaplayer.astermediaplayer.ui.livetv.SeriesTimerViewModel
import com.astermediaplayer.astermediaplayer.ui.music.AlbumPage
import com.astermediaplayer.astermediaplayer.ui.music.ArtistPage
import com.astermediaplayer.astermediaplayer.ui.music.MusicPlayerScreen
import com.astermediaplayer.astermediaplayer.ui.music.MusicScreen
import com.astermediaplayer.astermediaplayer.ui.audiobooks.AudiobookPage
import com.astermediaplayer.astermediaplayer.ui.audiobooks.AudiobookPlayerScreen
import com.astermediaplayer.astermediaplayer.ui.audiobooks.AudiobookShelfPage
import com.astermediaplayer.astermediaplayer.ui.audiobooks.AudiobooksScreen
import com.astermediaplayer.astermediaplayer.ui.theme.DetailMetrics
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.LocalTvChip
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.rememberCoroutineScope
import com.astermediaplayer.astermediaplayer.AsterApp
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.DesktopMac
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import com.astermediaplayer.astermediaplayer.data.OpeningEnding
import com.astermediaplayer.astermediaplayer.ui.anime.AnimeScreen
import com.astermediaplayer.astermediaplayer.ui.anime.AnimeViewModel
import com.astermediaplayer.astermediaplayer.ui.auth.AuthScreen
import com.astermediaplayer.astermediaplayer.ui.auth.AuthViewModel
import com.astermediaplayer.astermediaplayer.ui.detail.DetailScreen
import com.astermediaplayer.astermediaplayer.ui.detail.DetailViewModel
import com.astermediaplayer.astermediaplayer.ui.home.HomeScreen
import com.astermediaplayer.astermediaplayer.ui.home.HomeViewModel
import com.astermediaplayer.astermediaplayer.ui.library.LibraryHomeScreen
import com.astermediaplayer.astermediaplayer.ui.library.LibraryHomeViewModel
import com.astermediaplayer.astermediaplayer.ui.library.LibraryScreen
import com.astermediaplayer.astermediaplayer.ui.library.LibraryViewModel
import com.astermediaplayer.astermediaplayer.ui.library.TypeScreen
import com.astermediaplayer.astermediaplayer.ui.library.TypeViewModel
import com.astermediaplayer.astermediaplayer.ui.player.ClipRange
import com.astermediaplayer.astermediaplayer.ui.player.DirectMedia
import com.astermediaplayer.astermediaplayer.ui.player.PlayerScreen
import com.astermediaplayer.astermediaplayer.ui.player.PlayerViewModel
import com.astermediaplayer.astermediaplayer.data.disc.DiscMenuDocument
import com.astermediaplayer.astermediaplayer.ui.disc.DiscMenuController
import com.astermediaplayer.astermediaplayer.ui.disc.DiscMenuScreen
import com.astermediaplayer.astermediaplayer.ui.search.SearchScreen
import com.astermediaplayer.astermediaplayer.ui.search.SearchViewModel
import com.astermediaplayer.astermediaplayer.ui.settings.SettingsScreen
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

/** A round back button over a page's art, like the title page's. */
@Composable
internal fun BackButton(onClick: () -> Unit) {
    // The remote's Back key does this, so a TV shows no button to steer to.
    if (LocalIsTv.current) return
    IconButton(
        onClick = onClick,
        modifier = Modifier.statusBarsPadding().padding(start = Lockup.screenPadding, top = 4.dp).size(DetailMetrics.backButtonSize).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)),
    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
}

/** Ids come either way: with dashes, or as 32 hex digits. */
private fun parseId(id: String): UUID? = runCatching {
    UUID.fromString(if (id.length == 32) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id)
}.getOrNull()

private object Routes {
    const val AUTH = "auth"
    const val MAIN = "main"
    const val LIBRARY = "library/{id}/{name}"
    const val DETAIL = "detail/{id}"
    const val PERSON = "person/{id}"
    const val PLAYLIST = "playlist/{id}"
    const val PLAYER = "player/{id}?start={start}&end={end}&label={label}&begin={begin}&at={at}&queue={queue}"
    const val DISC = "disc/{id}"
    const val AUDIOBOOK = "audiobook/{id}"
    const val CHANNEL = "channel/{id}"
    const val CHANNEL_SETTINGS = "channelsettings?only={only}"
    const val PROGRAM = "program/{id}"
    const val SERIES_TIMER = "seriestimer/{id}"
    const val ALBUM = "album/{id}"
    const val ARTIST = "artist/{id}"
    const val SHELF = "audiobookshelf/{id}"
    const val THEME = "theme?path={path}&title={title}&subtitle={subtitle}"
    fun library(id: UUID, name: String) = "library/$id/${android.net.Uri.encode(name)}"
    fun detail(id: UUID) = "detail/$id"
    fun person(id: UUID) = "person/$id"
    fun playlist(id: UUID) = "playlist/$id"
    fun audiobook(id: UUID) = "audiobook/$id"
    fun channel(id: String) = "channel/$id"
    fun channelSettings(only: String? = null) = if (only == null) "channelsettings" else "channelsettings?only=$only"
    fun program(id: String) = "program/$id"
    fun seriesTimer(id: String) = "seriestimer/$id"
    fun album(id: UUID) = "album/$id"
    fun artist(id: UUID) = "artist/$id"
    fun shelf(id: String) = "audiobookshelf/${android.net.Uri.encode(id)}"
    fun player(id: UUID, fromBeginning: Boolean = false) = if (fromBeginning) "player/$id?begin=true" else "player/$id"
    fun disc(id: UUID) = "disc/$id"
    /** From a disc menu: from a point in the item (ticks), and the line of items that follows it. */
    fun player(id: UUID, atTicks: Long, followedBy: List<UUID>?) =
        "player/$id?at=$atTicks" + (followedBy?.let { "&queue=" + (it.joinToString(",").ifEmpty { "-" }) } ?: "")
    /** A stretch of an episode, in milliseconds. */
    fun clip(id: UUID, startMs: Long, endMs: Long, label: String) = "player/$id?start=$startMs&end=$endMs&label=${android.net.Uri.encode(label)}"
    fun theme(path: String, title: String, subtitle: String?) =
        "theme?path=${android.net.Uri.encode(path)}&title=${android.net.Uri.encode(title)}&subtitle=${android.net.Uri.encode(subtitle.orEmpty())}"
}

@Composable
fun AsterNavHost(signedIn: Boolean) {
    val app = LocalContext.current.applicationContext as AsterApp
    val repo = app.repository
    val nav = rememberNavController()
    // There's one main activity (so the launcher brings back a video in picture in picture rather than opening another), so
    // a link that comes while the app is open arrives here, not as a new activity.
    val activity = LocalContext.current as? androidx.activity.ComponentActivity
    DisposableEffect(activity, nav) {
        val listener = androidx.core.util.Consumer<android.content.Intent> { intent -> if (intent.data != null) nav.handleDeepLink(intent) }
        activity?.addOnNewIntentListener(listener)
        onDispose { activity?.removeOnNewIntentListener(listener) }
    }
    val scope = rememberCoroutineScope()

    fun open(item: BaseItemDto) = when (item.type) {
        BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW -> nav.navigate(Routes.library(item.id, item.name.orEmpty()))
        BaseItemKind.PERSON -> nav.navigate(Routes.person(item.id))
        // A music playlist opens as an album does; any other is a playlist of movies and episodes.
        BaseItemKind.PLAYLIST -> nav.navigate(if (item.mediaType == org.jellyfin.sdk.model.api.MediaType.AUDIO) Routes.album(item.id) else Routes.playlist(item.id))
        else -> nav.navigate(Routes.detail(item.id))
    }

    val presented by app.audiobooks.presented.collectAsStateWithLifecycle()
    val musicPresented by app.music.presented.collectAsStateWithLifecycle()
    // Notifications carry the book's controls on the lock screen; asked once, with the first book.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(presented || musicPresented) {
        if ((presented || musicPresented) && android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    // Switching servers starts the app afresh on the new one: the whole back stack goes, and with it every screen's loaded data.
    val servers = remember {
        ServerActions(
            repo,
            onSwitched = { app.resetForServerChange(); nav.navigate(Routes.MAIN) { popUpTo(nav.graph.id) { inclusive = true } } },
            onOpenChannels = { nav.navigate(Routes.channelSettings()) },
            onAddServer = {
                scope.launch {
                    repo.changeServer(); app.resetForServerChange()
                    nav.navigate(Routes.AUTH) { popUpTo(nav.graph.id) { inclusive = true } }
                }
            },
        )
    }
    val locked by repo.locked.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current
    val deck = app.themeDeck
    val route = nav.currentBackStackEntryAsState().value?.destination?.route.orEmpty()
    // A video, a disc menu or a theme taking over the screen ends the songs playing under the page.
    LaunchedEffect(route) { if (route.startsWith("player") || route.startsWith("disc") || route.startsWith("theme") || route.startsWith("channel/") || route == Routes.AUTH) deck.stop() }
    // Reaching the main tabs (opening signed in, signing in, switching servers): the settings that follow the user are fetched.
    LaunchedEffect(route.startsWith("main")) { if (route.startsWith("main") && repo.session != null) app.settingsSync.pull() }
    val miniFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val firstRequest = remember { deck.focusRequest }
    LaunchedEffect(deck.focusRequest) { if (deck.focusRequest != firstRequest) runCatching { miniFocus.requestFocus() } }
    // Holding Select from anywhere brings focus to the slim Now Playing panel, as holding Play/Pause does on the Apple TV.
    var holdHandled by remember { mutableStateOf(false) }
    val pageFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (!isTv || !deck.isActive || deck.cardHoldFocused || e.key != androidx.compose.ui.input.key.Key.DirectionCenter) false
            else when (e.type) {
                androidx.compose.ui.input.key.KeyEventType.KeyDown ->
                    if (e.nativeKeyEvent.repeatCount > 0) {
                        if (!holdHandled) { holdHandled = true; deck.requestPanelFocus() }
                        true
                    } else false
                // The release after a hold is part of the hold, not a press on whatever the panel's focus has moved to.
                androidx.compose.ui.input.key.KeyEventType.KeyUp -> if (holdHandled) { holdHandled = false; true } else false
                else -> false
            }
        },
    ) {
        // The pages remember what had focus, so Back from the Now Playing panel lands where you were.
        // And a card's long-press menu plays and opens titles through them.
        val itemActions = remember(nav) { com.astermediaplayer.astermediaplayer.ui.components.ItemActions(play = { id, fromBeginning -> nav.navigate(Routes.player(id, fromBeginning)) }, open = { open(it) }) }
        androidx.compose.runtime.CompositionLocalProvider(
            com.astermediaplayer.astermediaplayer.ui.detail.LocalPageFocus provides pageFocus,
            com.astermediaplayer.astermediaplayer.ui.components.LocalItemActions provides itemActions,
        ) {
        NavHost(nav, startDestination = if (signedIn) Routes.MAIN else Routes.AUTH, modifier = Modifier.fillMaxSize().focusRequester(pageFocus).focusRestorer()) {
            composable(Routes.AUTH) {
                val vm: AuthViewModel = viewModel(factory = viewModelFactory { initializer { AuthViewModel(repo, app::resetForServerChange) } })
                AuthScreen(vm, onSignedIn = { nav.navigate(Routes.MAIN) { popUpTo(Routes.AUTH) { inclusive = true } } })
            }
            composable(Routes.MAIN) {
                // Once for each Jellyfin server signed in to (the plugins are Jellyfin's): the welcome screen stands in for the tabs.
                val seen by app.settings.welcomeSeen.collectAsStateWithLifecycle(initialValue = null)
                val serverId = repo.currentServerId
                val welcomeDue = seen != null && serverId != null && repo.serverKind == com.astermediaplayer.astermediaplayer.data.emby.ServerKind.Jellyfin && serverId !in seen.orEmpty()
                if (seen == null) Box(Modifier.fillMaxSize()) else if (welcomeDue) {
                    com.astermediaplayer.astermediaplayer.ui.welcome.WelcomeScreen(repo, repo.session?.serverName, onDone = { scope.launch { app.settings.markWelcomeSeen(serverId!!) } })
                } else MainTabs(
                    onItem = ::open,
                    onBook = { nav.navigate(Routes.audiobook(it.id)) },
                    onShelf = { nav.navigate(Routes.shelf(it.id)) },
                onAlbum = { nav.navigate(Routes.album(it.id)) },
                onArtist = { nav.navigate(Routes.artist(it.id)) },
                onTuneChannel = { nav.navigate(Routes.channel(it)) },
                onEditChannels = { nav.navigate(Routes.channelSettings(it)) },
                onProgram = { nav.navigate(Routes.program(it.id.toString())) },
                onSeriesRule = { nav.navigate(Routes.seriesTimer(it)) },
                    onPlay = { nav.navigate(Routes.player(it)) },
                    servers = servers,
                    onSignOut = {
                        scope.launch {
                            repo.signOut()
                            app.resetForServerChange()
                            nav.navigate(Routes.AUTH) { popUpTo(Routes.MAIN) { inclusive = true } }
                        }
                    },
                )
            }
            composable(Routes.PERSON, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = parseId(entry.arguments!!.getString("id").orEmpty())
                if (id == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return@composable }
                com.astermediaplayer.astermediaplayer.ui.library.PersonScreen(repo, id, onItem = ::open, onBack = { nav.popBackStack() })
            }
            composable(Routes.PLAYLIST, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = parseId(entry.arguments!!.getString("id").orEmpty())
                if (id == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return@composable }
                com.astermediaplayer.astermediaplayer.ui.library.VideoPlaylistScreen(
                    repo, id, onItem = ::open, onBack = { nav.popBackStack() },
                    // The first from its start, the rest after it in turn.
                    onPlay = { ids -> ids.firstOrNull()?.let { nav.navigate(Routes.player(it, 0L, ids.drop(1))) } },
                )
            }
            composable(
                Routes.LIBRARY,
                arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType }),
            ) { entry ->
                val id = UUID.fromString(entry.arguments!!.getString("id"))
                val name = entry.arguments!!.getString("name").orEmpty()
                val vm: LibraryViewModel = viewModel(factory = viewModelFactory { initializer { LibraryViewModel(repo, id) } })
                LibraryScreen(name, vm, onItem = ::open, onBack = { nav.popBackStack() })
            }
            composable(
                Routes.DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
                deepLinks = listOf(navDeepLink { uriPattern = "aster://item/{id}" }),
            ) { entry ->
                // A link may carry the id either way (see parseId); one that isn't an id goes back rather than crashing.
                val id = parseId(entry.arguments!!.getString("id").orEmpty())
                if (id == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return@composable }
                val vm: DetailViewModel = viewModel(factory = viewModelFactory { initializer { DetailViewModel(repo, id) } })
                DetailScreen(
                    vm, onBack = { nav.popBackStack() }, onPlay = { nav.navigate(Routes.player(it)) }, onItem = ::open,
                    onPlayFromBeginning = { nav.navigate(Routes.player(it, fromBeginning = true)) },
                    onDiscMenu = { nav.navigate(Routes.disc(it)) },
                    onGoToSeries = { nav.navigate(Routes.detail(it)) },
                    onPlayTheme = { oe ->
                        scope.launch {
                            when (oe) {
                                // Chapters say where an opening is, so it plays that stretch of the episode.
                                is OpeningEnding.Chapter -> nav.navigate(Routes.clip(oe.clip.episode.id, oe.clip.startMs, oe.clip.endMs, oe.clip.title))
                                is OpeningEnding.Theme -> {
                                    val theme = oe.theme.preferring(app.settings.current().themeCut)
                                    val published = theme.libraryItemId?.let(::parseId)
                                    val path = theme.playablePath
                                    when {
                                        published != null -> nav.navigate(Routes.player(published))
                                        path != null -> nav.navigate(Routes.theme(path, theme.code, theme.songLine))
                                    }
                                }
                            }
                        }
                    },
                )
            }
            composable(Routes.DISC, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = parseId(entry.arguments!!.getString("id").orEmpty())
                if (id == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return@composable }
                // The menu is fetched afresh as it opens; the controller outlives the player that opens over it.
                var loaded by remember { mutableStateOf<Pair<DiscMenuDocument, Boolean>?>(null) }
                LaunchedEffect(id) {
                    val document = repo.discMenu(id)
                    if (document == null) nav.popBackStack() else loaded = document to app.settings.current().discMenuSounds
                }
                loaded?.let { (document, sounds) ->
                    val controller: DiscMenuController = viewModel(key = "disc-$id", factory = viewModelFactory { initializer { DiscMenuController(app, repo, document, id, sounds) } })
                    DiscMenuScreen(
                        controller,
                        onPlay = { play -> nav.navigate(Routes.player(play.itemId, play.startTicks.takeIf { it > 0 } ?: -1L, play.followedBy)) },
                        onClose = { nav.popBackStack() },
                    )
                }
            }
            composable(Routes.AUDIOBOOK, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = UUID.fromString(entry.arguments!!.getString("id"))
                Box(Modifier.fillMaxSize()) {
                    AudiobookPage(id)
                    BackButton { nav.popBackStack() }
                }
            }
            composable(Routes.PROGRAM, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val programId = entry.arguments!!.getString("id").orEmpty()
            val vm: ProgramViewModel = viewModel(key = "program-$programId", factory = viewModelFactory { initializer { ProgramViewModel(repo, programId) } })
            ProgramScreen(
                vm, onClose = { nav.popBackStack() },
                // Watching leaves the page behind, so Back from the channel returns to Live TV.
                onWatch = { channel -> nav.navigate(Routes.player(channel)) { popUpTo(Routes.PROGRAM) { inclusive = true } } },
                onSeriesOptions = { nav.navigate(Routes.seriesTimer(it)) },
            )
        }
        composable(Routes.SERIES_TIMER, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val ruleId = entry.arguments!!.getString("id").orEmpty()
            val vm: SeriesTimerViewModel = viewModel(key = "rule-$ruleId", factory = viewModelFactory { initializer { SeriesTimerViewModel(repo, ruleId) } })
            SeriesTimerScreen(vm, onClose = { nav.popBackStack() })
        }
        composable(Routes.CHANNEL_SETTINGS, arguments = listOf(navArgument("only") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
            val only = entry.arguments?.getString("only")
            val vm: ChannelSettingsViewModel = viewModel(key = "channel-settings-$only", factory = viewModelFactory { initializer { ChannelSettingsViewModel(app) } })
            ChannelSettingsScreen(vm, only)
        }
        composable(Routes.CHANNEL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val channelId = entry.arguments!!.getString("id").orEmpty()
            // What's on the channel now, from as far in as it's got; nothing on it leaves the screen.
            val live = remember(channelId) { ChannelTuner.live(channelId) }
            val dto = live?.item?.source as? BaseItemDto
            if (live == null || dto == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                val vm: PlayerViewModel = viewModel(
                    key = "channel-$channelId",
                    factory = viewModelFactory {
                        initializer {
                            PlayerViewModel(
                                app, repo, app.settings, dto.id, playlistChannelId = channelId,
                                startAtMs = live.startTicks / 10_000L, cutAtMs = live.cutTicks?.let { it / 10_000L },
                            )
                        }
                    },
                )
                // Go to Episode plays what's on by itself, off the channel, as from its show's page.
                PlayerScreen(vm, onBack = { nav.popBackStack() }, onPlayNext = {}, onGoTo = { id -> nav.navigate(Routes.player(id)) { popUpTo(Routes.CHANNEL) { inclusive = true } } })
            }
        }
        composable(Routes.ALBUM, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            Box(Modifier.fillMaxSize()) {
                AlbumPage(UUID.fromString(entry.arguments!!.getString("id")))
                BackButton { nav.popBackStack() }
                AudiobookBar(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        composable(Routes.ARTIST, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            Box(Modifier.fillMaxSize()) {
                ArtistPage(UUID.fromString(entry.arguments!!.getString("id")), onAlbum = { nav.navigate(Routes.album(it.id)) })
                BackButton { nav.popBackStack() }
                AudiobookBar(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        composable(Routes.SHELF, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                Box(Modifier.fillMaxSize()) {
                    AudiobookShelfPage(entry.arguments!!.getString("id").orEmpty(), onBook = { nav.navigate(Routes.audiobook(it.id)) })
                    BackButton { nav.popBackStack() }
                }
            }
            composable(
                Routes.THEME,
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("subtitle") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val media = DirectMedia(
                    entry.arguments!!.getString("path").orEmpty(),
                    entry.arguments!!.getString("title").orEmpty(),
                    entry.arguments!!.getString("subtitle")?.ifEmpty { null },
                )
                val vm: PlayerViewModel = viewModel(factory = viewModelFactory { initializer { PlayerViewModel(app, repo, app.settings, UUID(0, 0), direct = media) } })
                PlayerScreen(vm, onBack = { nav.popBackStack() }, onPlayNext = {})
            }
            composable(
                Routes.PLAYER,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("start") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("end") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("label") { type = NavType.StringType; defaultValue = "" },
                    navArgument("begin") { type = NavType.BoolType; defaultValue = false },
                    navArgument("at") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("queue") { type = NavType.StringType; defaultValue = "" },
                ),
                deepLinks = listOf(navDeepLink { uriPattern = "aster://play/{id}" }),
            ) { entry ->
                // A link may carry the id either way (see parseId); one that isn't an id goes back rather than crashing.
                val id = parseId(entry.arguments!!.getString("id").orEmpty())
                if (id == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return@composable }
                val start = entry.arguments!!.getLong("start")
                val end = entry.arguments!!.getLong("end")
                val clip = if (start >= 0 && end > start) ClipRange(start, end, entry.arguments!!.getString("label")?.ifEmpty { null }) else null
                val at = entry.arguments!!.getLong("at")
                // A disc menu's line of items: "-" is an empty one (nothing follows), absent is the usual next episode.
                val line = entry.arguments!!.getString("queue").orEmpty().takeIf { it.isNotEmpty() }?.let { q -> if (q == "-") emptyList() else q.split(',').mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() } }
                val vm: PlayerViewModel = viewModel(factory = viewModelFactory {
                    initializer { PlayerViewModel(app, repo, app.settings, id, clip, fromBeginning = entry.arguments!!.getBoolean("begin"), startAtMs = at.takeIf { it >= 0 }?.let { it / 10_000 }, followedBy = line) }
                })
                PlayerScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onPlayNext = { next ->
                        val route = if (line != null) Routes.player(next, -1L, line.drop(1)) else Routes.player(next)
                        nav.navigate(route) { popUpTo(Routes.PLAYER) { inclusive = true } }
                    },
                )
            }
        }
        }
        // The slim Now Playing panel, over the browsing pages (a show's own page carries its own).
        val onDetail = route.startsWith("detail")
        val browsing = onDetail || route.startsWith("main") || route.startsWith("library/") || route.startsWith("audiobook") || route.startsWith("album") || route.startsWith("artist")
        if (isTv && deck.isActive && browsing) androidx.compose.runtime.CompositionLocalProvider(com.astermediaplayer.astermediaplayer.ui.detail.LocalPageFocus provides pageFocus) {
            ThemeNowPlayingPanel(
                deck, null,
                Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 40.dp).width(300.dp),
                showDetails = onDetail, playFocus = miniFocus,
                onFullScreen = {
                    deck.current?.let { oe ->
                        deck.stop()
                        scope.launch {
                            when (oe) {
                                is OpeningEnding.Chapter -> nav.navigate(Routes.clip(oe.clip.episode.id, oe.clip.startMs, oe.clip.endMs, oe.clip.title))
                                is OpeningEnding.Theme -> {
                                    val theme = oe.theme.preferring(app.settings.current().themeCut)
                                    val published = theme.libraryItemId?.let(::parseId)
                                    val path = theme.playablePath
                                    when {
                                        published != null -> nav.navigate(Routes.player(published))
                                        path != null -> nav.navigate(Routes.theme(path, theme.code, theme.songLine))
                                    }
                                }
                            }
                        }
                    }
                },
                onOpenShow = if (onDetail) null else deck.showItem?.let { show -> { nav.navigate(Routes.detail(show.id)) } },
            )
        }
        if (presented) {
            androidx.activity.compose.BackHandler { app.audiobooks.setPresented(false) }
            AudiobookPlayerScreen()
        }
        if (musicPresented) {
            androidx.activity.compose.BackHandler { app.music.setPresented(false) }
            MusicPlayerScreen()
        }
        // The server in use was left too long: its password, or another server, before anything shows. Focus can't leave it.
        if (locked) {
            val server = repo.currentServer
            if (server != null) {
                androidx.activity.compose.BackHandler { }
                @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                Box(Modifier.fillMaxSize().focusProperties { exit = { androidx.compose.ui.focus.FocusRequester.Cancel } }.focusGroup()) {
                    ServerLockScreen(
                        server, server.titleAmong(repo.savedServers.value),
                        onUnlock = { password -> repo.unlock(repo.signInAgain(server, password)) },
                        onUseAnother = {
                            scope.launch {
                                repo.changeServer(); app.resetForServerChange()
                                nav.navigate(Routes.AUTH) { popUpTo(nav.graph.id) { inclusive = true } }
                            }
                        },
                    )
                }
            }
        }
    }

}

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Movies("Movies", Icons.Default.Movie),
    Shows("TV Shows", Icons.Default.Tv),
    Anime("Anime", Icons.Default.AutoAwesome),
    More("More", Icons.Default.MoreHoriz),
}

/** The More list, in the Apple apps' order; the ones built so far. */
private enum class MoreDestination(val label: String, val icon: ImageVector) {
    Music("Music", Icons.Default.MusicNote),
    Audiobooks("Audiobooks", Icons.Default.LibraryBooks),
    Upcoming("Upcoming", Icons.Default.CalendarMonth),
    LiveTv("Live TV", Icons.Default.Sensors),
    Channels("Channels", Icons.Default.Tv),
    Library("Library", Icons.Default.Layers),
    Downloads("Downloads", Icons.Default.Download),
    Settings("Settings", Icons.Default.Settings),
    Search("Search", Icons.Default.Search),
}

/** What the bar shows: a tab, or a More destination given a place of its own while there's room (a phone on its side). */
private data class BarItem(val label: String, val icon: ImageVector, val tab: Tab, val destination: MoreDestination? = null)

/** The destinations that move into the bar from More, in the Apple apps' tab order, as far as the width allows. */
private val PromotedOrder = listOf(MoreDestination.Music, MoreDestination.Audiobooks, MoreDestination.Upcoming)

/** Google TV's side menu lists every destination in this order; Downloads is a phone's. */
private val TvDestinations = listOf(
    MoreDestination.Music, MoreDestination.Audiobooks, MoreDestination.Upcoming, MoreDestination.LiveTv,
    MoreDestination.Channels, MoreDestination.Library, MoreDestination.Settings, MoreDestination.Search,
)

/** About what a tab takes in the side-on bar, in dp: icon, gap, label and padding. */
private fun barItemWidth(label: String) = 20 + 6 + (label.length * 6.5f).toInt() + 20

private fun promotedFor(tabs: List<Tab>, candidates: List<MoreDestination>, widthDp: Int): List<MoreDestination> {
    var used = 12 + tabs.sumOf { barItemWidth(it.label) }
    val shown = mutableListOf<MoreDestination>()
    for (destination in candidates) {
        val width = barItemWidth(destination.label)
        if (used + width > widthDp * 0.88f) break
        used += width
        shown += destination
    }
    return shown
}

@Composable
private fun MainTabs(
    onEditChannels: (String?) -> Unit,
    onItem: (BaseItemDto) -> Unit,
    onPlay: (UUID) -> Unit,
    onBook: (Audiobook) -> Unit,
    onShelf: (AudiobookShelf) -> Unit,
    onAlbum: (BaseItemDto) -> Unit,
    onArtist: (BaseItemDto) -> Unit,
    onTuneChannel: (String) -> Unit,
    onProgram: (BaseItemDto) -> Unit,
    onSeriesRule: (String) -> Unit,
    servers: ServerActions,
    onSignOut: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as AsterApp
    val repo = app.repository
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var more by rememberSaveable { mutableStateOf<MoreDestination?>(null) }
    val wide = LocalConfiguration.current.smallestScreenWidthDp >= 600

    val home: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(repo) } })
    val anime: AnimeViewModel = viewModel(factory = viewModelFactory { initializer { AnimeViewModel(repo) } })
    val search: SearchViewModel = viewModel(factory = viewModelFactory { initializer { SearchViewModel(repo) } })
    val libraries by produceState(emptyList<BaseItemDto>()) { value = runCatching { repo.views() }.getOrDefault(emptyList()) }

    // Anime is whatever Shoko/AniDB matched; those libraries get their own tab and Movies and TV Shows skip them.
    // Null until the check has run, so Movies and TV Shows don't load with anime still in them.
    val detectedAnime by produceState<List<BaseItemDto>?>(null, libraries) { value = if (libraries.isEmpty()) null else runCatching { repo.animeLibraries(libraries) }.getOrDefault(emptyList()) }
    // Upcoming is listed only while the server has something coming up, as on the Apple apps.
    // Channels are playlist tags in Jellyfin's shape: not on Emby yet, as in the Apple apps.
    // The tab is there once the server has channels shown to this user (editing them is Settings' Channels page), as on the Apple apps.
    val hasChannels by produceState(false, repo.currentServerId) {
        repo.convertOldChannelTags()
        value = repo.serverKind == com.astermediaplayer.astermediaplayer.data.emby.ServerKind.Jellyfin &&
            runCatching { repo.videoPlaylists() }.getOrDefault(emptyList()).any { p ->
                com.astermediaplayer.astermediaplayer.data.channels.ChannelTags.from(p.tags).let { it.isChannel && it.isShown(repo.session?.userName) }
            }
    }
    val hasUpcoming by produceState(false) { value = runCatching { repo.upcoming(1).isNotEmpty() }.getOrDefault(false) }
    // What the menu lists is what the server has, as on the Apple apps: Music, Audiobooks and Live TV need a library of their own.
    val hasMusic = libraries.any { it.collectionType == CollectionType.MUSIC }
    val hasBooks = libraries.any { it.collectionType == CollectionType.BOOKS }
    val hasLiveTv = libraries.any { it.collectionType == CollectionType.LIVETV }
    fun listed(d: MoreDestination) = when (d) {
        MoreDestination.Upcoming -> hasUpcoming
        MoreDestination.Channels -> hasChannels
        MoreDestination.Music -> hasMusic
        MoreDestination.Audiobooks -> hasBooks
        MoreDestination.LiveTv -> hasLiveTv
        else -> true
    }
    val upcoming: UpcomingViewModel = viewModel(factory = viewModelFactory { initializer { UpcomingViewModel(repo) } })
    val libraryHome: LibraryHomeViewModel = viewModel(factory = viewModelFactory { initializer { LibraryHomeViewModel(repo) } })
    val animeLibraries = detectedAnime.orEmpty()
    val animeIds = animeLibraries.map { it.id }.toSet()
    val moviesLibrary = libraries.firstOrNull { it.collectionType == CollectionType.MOVIES && it.id !in animeIds }
    val showsLibrary = libraries.firstOrNull { it.collectionType == CollectionType.TVSHOWS && it.id !in animeIds }
    val tabs = Tab.entries.filter {
        when (it) {
            Tab.Movies -> moviesLibrary != null
            Tab.Shows -> showsLibrary != null
            Tab.Anime -> animeLibraries.isNotEmpty()
            else -> true
        }
    }
    val current = tabs.getOrElse(selected) { Tab.Home }
    fun select(tab: Tab) { selected = tabs.indexOf(tab); more = null }

    // A phone on its side has room for more tabs, as the Apple apps' bar does: as many as fit, then More.
    val config = LocalConfiguration.current
    val isTv = LocalIsTv.current
    val landscape = !wide && config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val promoted = when {
        isTv -> TvDestinations.filter(::listed)
        landscape -> promotedFor(tabs, PromotedOrder.filter(::listed), config.screenWidthDp)
        else -> emptyList()
    }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val chip = remember { mutableStateOf(true) }
    val focusManager = LocalFocusManager.current
    val barItems = if (isTv || landscape) {
        tabs.filter { it != Tab.More }.map { BarItem(it.label, it.icon, it) } +
            promoted.map { BarItem(it.label, it.icon, Tab.More, it) } + if (isTv) emptyList() else listOf(BarItem(Tab.More.label, Tab.More.icon, Tab.More))
    } else tabs.map { BarItem(it.label, it.icon, it) }
    fun isSelected(item: BarItem) = when {
        item.destination != null -> current == Tab.More && more == item.destination
        item.tab == Tab.More -> current == Tab.More && more !in promoted
        else -> current == item.tab
    }
    fun pick(item: BarItem) {
        if (item.destination != null) { selected = tabs.indexOf(Tab.More); more = item.destination } else select(item.tab)
        menuOpen = false
        chip.value = true
    }
    // Back from a page under More goes to the list; from one with a place in the bar, Home.
    fun back() { if (more in promoted) select(Tab.Home) else more = null }

    @Composable
    fun TypeTab(kind: BaseItemKind, anime: List<BaseItemDto>, onItem: (BaseItemDto) -> Unit, onPlay: (UUID) -> Unit, modifier: Modifier) {
        key(kind, anime.map { it.id }) {
            val vm: TypeViewModel = viewModel(
                key = "type-$kind-${anime.size}",
                factory = viewModelFactory { initializer { TypeViewModel(repo, kind, anime.map { it.id }) } },
            )
            TypeScreen(vm, onItem = onItem, onPlay = onPlay, modifier = modifier)
        }
    }

    @Composable
    fun Library(view: BaseItemDto?, modifier: Modifier) {
        if (view == null) return
        key(view.id) {
            val vm: LibraryViewModel = viewModel(key = view.id.toString(), factory = viewModelFactory { initializer { LibraryViewModel(repo, view.id) } })
            LibraryScreen(view.name.orEmpty(), vm, onItem = onItem, onBack = null, modifier = modifier)
        }
    }

    androidx.activity.compose.BackHandler(enabled = current == Tab.More && more != null) { back() }
    // On a TV Back opens the side menu, and closes it again; from Home with the menu open it leaves the app.
    androidx.activity.compose.BackHandler(enabled = isTv && !menuOpen) { menuOpen = true }
    androidx.activity.compose.BackHandler(enabled = isTv && menuOpen && current != Tab.Home) { menuOpen = false }
    val content: @Composable (Modifier) -> Unit = { modifier ->
        when (current) {
            Tab.Home -> HomeScreen(home, onItem = onItem, onPlay = onPlay, modifier = modifier)
            Tab.Movies -> if (detectedAnime != null) TypeTab(BaseItemKind.MOVIE, animeLibraries, onItem, onPlay, modifier)
            Tab.Shows -> if (detectedAnime != null) TypeTab(BaseItemKind.SERIES, animeLibraries, onItem, onPlay, modifier)
            Tab.Anime -> AnimeScreen(anime, onItem = onItem, onPlay = onPlay, modifier = modifier)
            Tab.More -> when (more) {
                MoreDestination.Music -> Box(modifier) {
                    MusicScreen(onAlbum = onAlbum, onArtist = onArtist, modifier = Modifier.fillMaxSize(), topInset = 56.dp)
                    BackButton { back() }
                }
                MoreDestination.Channels -> Box(modifier) {
                    ChannelsScreen(onTune = onTuneChannel, onOpen = onItem, onEdit = onEditChannels, modifier = Modifier.fillMaxSize(), topInset = 56.dp)
                    BackButton { back() }
                }
                MoreDestination.LiveTv -> Box(modifier) {
                    LiveTvScreen(onPlay = onPlay, onProgram = onProgram, onSeriesRule = onSeriesRule, modifier = Modifier.fillMaxSize(), topInset = 56.dp)
                    BackButton { back() }
                }
                MoreDestination.Audiobooks -> Box(modifier) {
                    AudiobooksScreen(onBook = onBook, onShelf = onShelf, onListen = { app.audiobooks.play(it) }, modifier = Modifier.fillMaxSize(), topInset = 64.dp)
                    BackButton { back() }
                }
                MoreDestination.Library -> Box(modifier) {
                    LibraryHomeScreen(libraryHome, onItem = onItem, modifier = Modifier.fillMaxSize(), topInset = 56.dp)
                    BackButton { back() }
                }
                MoreDestination.Downloads -> Box(modifier) {
                    DownloadsScreen(onPlay = onPlay, onBook = { id -> (app.audiobookLibrary.state.value.book(id) ?: app.downloads.savedBook(id))?.let(onBook) }, modifier = Modifier.fillMaxSize(), topInset = 56.dp)
                    BackButton { back() }
                }
                MoreDestination.Upcoming -> Box(modifier) {
                    UpcomingScreen(upcoming, onItem = onItem, onPlay = onPlay, modifier = Modifier.fillMaxSize())
                    BackButton { back() }
                }
                MoreDestination.Search -> Box(modifier) {
                    SearchScreen(search, onItem = onItem, onAlbum = onAlbum, onArtist = onArtist, modifier = Modifier.statusBarsPadding().padding(top = if (isTv) 0.dp else 56.dp))
                    BackButton { back() }
                }
                MoreDestination.Settings -> Box(modifier) {
                    SettingsScreen(repo.session, app.settings, servers, onSignOut, Modifier.statusBarsPadding().padding(top = 56.dp))
                    BackButton { back() }
                }
                null -> MoreMenu(MoreDestination.entries.filter { listed(it) && it !in promoted }, onPick = { more = it }, modifier = modifier.statusBarsPadding())
            }
        }
    }

    if (isTv) {
        // Left, with nothing further left to go to, pulls the menu out.
        Box(
            Modifier.fillMaxSize().onKeyEvent { event ->
                // Left, or Up, with nothing further that way to go to, pulls the menu out.
                val direction = when (event.key) { Key.DirectionLeft -> FocusDirection.Left; Key.DirectionUp -> FocusDirection.Up; else -> null }
                if (direction == null || menuOpen) false
                else { if (event.type == KeyEventType.KeyDown && !focusManager.moveFocus(direction)) menuOpen = true; true }
            },
        ) {
            CompositionLocalProvider(LocalTvChip provides chip) { content(Modifier.fillMaxSize()) }
            val here = barItems.firstOrNull(::isSelected)
            if (here != null) AnimatedVisibility(!menuOpen && chip.value, Modifier.align(Alignment.TopStart)) { TvMenuChip(here) }
            if (menuOpen) TvMenu(barItems, ::isSelected, ::pick, onClose = { menuOpen = false })
            AudiobookBar(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp))
        }
    } else if (wide) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail {
                tabs.forEach { tab ->
                    NavigationRailItem(
                        selected = current == tab, onClick = { select(tab) },
                        icon = { Icon(tab.icon, tab.label) }, label = { Text(tab.label) },
                    )
                }
            }
            Box(Modifier.weight(1f)) { content(Modifier.fillMaxSize()) }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            content(Modifier.fillMaxSize())
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AudiobookBar()
                FloatingTabBar(items = barItems, isSelected = ::isSelected, onPick = ::pick, sideOn = landscape)
            }
        }
    }
}

/** Google TV's "‹ Home" chip over the top left of a page: where you are, and a way into the menu. */
@Composable
private fun TvMenuChip(here: BarItem, modifier: Modifier = Modifier) {
    fun pt(points: Float) = (points * Lockup.tvUnit).dp
    Row(
        modifier.padding(start = pt(22f), top = pt(51f)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(pt(4f)),
    ) {
        // Not focusable: Up (or Left) at the edge of the page is what opens the menu, so nothing here takes the remote.
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = Color.White, modifier = Modifier.size(pt(30f)))
        Row(
            Modifier.height(pt(56f)).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(start = pt(8f), end = pt(21f)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(pt(17f)),
        ) {
            Icon(tvMenuIcon(here), null, tint = Color.White, modifier = Modifier.size(pt(29f)))
            Text(here.label, style = Type.headline.copy(fontSize = (27f * Lockup.tvUnit).sp, lineHeight = (34f * Lockup.tvUnit).sp), color = Color.White, maxLines = 1)
        }
    }
}

/** The Apple TV menu's icons are SF Symbols; these are the nearest Material ones, on a TV only. */
private fun tvMenuIcon(item: BarItem): ImageVector = when (item.destination ?: item.tab) {
    Tab.Movies -> Icons.Default.Theaters
    Tab.Shows -> Icons.Default.DesktopMac
    MoreDestination.Audiobooks -> Icons.Default.LocalLibrary
    MoreDestination.Library -> Icons.Default.Inventory2
    else -> item.icon
}

/**
 * The side menu, drawn to the Apple TV app's measurements (its screenshot, in points on a 1920x1080 screen): a tall rounded
 * glass panel 36 in from the edge, a 76-pitch row of pills in it, and the focused one a white pill. The page behind is dimmed.
 * The remote starts on the current page; Right, or Back, hands focus back to the page.
 */
@Composable
private fun TvMenu(items: List<BarItem>, isSelected: (BarItem) -> Boolean, onPick: (BarItem) -> Unit, onClose: () -> Unit) {
    val u = Lockup.tvUnit
    fun pt(points: Float) = (points * u).dp
    val current = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { current.requestFocus() } }
    val shape = RoundedCornerShape(pt(46f))
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
        Column(
            Modifier.align(Alignment.TopStart).padding(start = pt(36f), top = pt(36f), bottom = pt(37f)).fillMaxHeight().width(pt(355f))
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xF71E1612), Color(0xF717120F))))
                .background(Color.White.copy(alpha = 0.07f))
                .border(pt(1f), Color.White.copy(alpha = 0.16f), shape)
                .verticalScroll(rememberScrollState()).padding(horizontal = pt(13f), vertical = pt(14f))
                .onKeyEvent { if (it.key == Key.DirectionRight) { if (it.type == KeyEventType.KeyDown) onClose(); true } else false },
            verticalArrangement = Arrangement.spacedBy(pt(1f)),
        ) {
            items.forEach { item ->
                var focused by remember { mutableStateOf(false) }
                val tint = if (focused) Color.Black else Color.White
                val label = if (item.destination == MoreDestination.Channels) "Channels (Beta)" else item.label
                Row(
                    Modifier.fillMaxWidth().height(pt(75f)).then(if (isSelected(item)) Modifier.focusRequester(current) else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .clip(CircleShape).background(if (focused) Color.White else Color.Transparent)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(item) }
                        .padding(start = pt(22f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(tvMenuIcon(item), null, tint = tint, modifier = Modifier.size(pt(30f)))
                    Text(label, style = Type.callout.copy(fontSize = (23.5f * u).sp, lineHeight = (30f * u).sp), color = tint, maxLines = 1, modifier = Modifier.padding(start = pt(18f)))
                }
            }
        }
    }
}

@Composable
private fun MoreMenu(destinations: List<MoreDestination>, onPick: (MoreDestination) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        // The Apple apps' navigation title: small and centered.
        Text("More", style = Type.headline, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        destinations.forEach { dest ->
            Row(Modifier.fillMaxWidth().clickable { onPick(dest) }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { Icon(dest.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) }
                Column(Modifier.weight(1f)) {
                    androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                    Row(Modifier.fillMaxWidth().height(51.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(dest.label, style = Type.title3.copy(fontWeight = FontWeight.Normal), modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = SecondaryText)
                    }
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 56.dp), color = Color.White.copy(alpha = 0.15f))
    }
}

/**
 * The Apple apps' floating, rounded tab bar: the selected tab is blue on a darker pill. Upright it spans the width with each
 * label under its icon; on a phone's side it is a centered pill of icon-and-label tabs, as the Apple apps' is there.
 */
@Composable
private fun FloatingTabBar(items: List<BarItem>, isSelected: (BarItem) -> Boolean, onPick: (BarItem) -> Unit, sideOn: Boolean, modifier: Modifier = Modifier) {
    Row(
        (if (sideOn) modifier else modifier.fillMaxWidth()).clip(RoundedCornerShape(36.dp)).background(Color(0xCC2C2C2E)).padding(6.dp),
        horizontalArrangement = if (sideOn) Arrangement.spacedBy(2.dp) else Arrangement.SpaceBetween,
    ) {
        items.forEach { item ->
            val active = isSelected(item)
            val tint = if (active) MaterialTheme.colorScheme.primary else Color.White
            val pill = Modifier.tvFocus(30.dp, lift = 1.08f).clip(RoundedCornerShape(30.dp)).background(if (active) Color.Black.copy(alpha = 0.45f) else Color.Transparent).clickable { onPick(item) }
            if (sideOn) {
                Row(
                    pill.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(item.icon, item.label, tint = tint, modifier = Modifier.size(20.dp))
                    Text(item.label, color = tint, style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp), maxLines = 1)
                }
            } else {
                Column(Modifier.weight(1f).then(pill).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(item.icon, item.label, tint = tint, modifier = Modifier.size(26.dp))
                    Text(item.label, color = tint, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}
