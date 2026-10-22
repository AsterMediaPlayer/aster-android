package com.astermediaplayer.astermediaplayer.ui

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
import com.astermediaplayer.astermediaplayer.data.OpeningEnding
import com.astermediaplayer.astermediaplayer.ui.anime.AnimeScreen
import com.astermediaplayer.astermediaplayer.ui.anime.AnimeViewModel
import com.astermediaplayer.astermediaplayer.ui.auth.AuthScreen
import com.astermediaplayer.astermediaplayer.ui.auth.AuthViewModel
import com.astermediaplayer.astermediaplayer.ui.detail.DetailScreen
import com.astermediaplayer.astermediaplayer.ui.detail.DetailViewModel
import com.astermediaplayer.astermediaplayer.ui.home.HomeScreen
import com.astermediaplayer.astermediaplayer.ui.home.HomeViewModel
import com.astermediaplayer.astermediaplayer.ui.library.LibraryScreen
import com.astermediaplayer.astermediaplayer.ui.library.LibraryViewModel
import com.astermediaplayer.astermediaplayer.ui.library.TypeScreen
import com.astermediaplayer.astermediaplayer.ui.library.TypeViewModel
import com.astermediaplayer.astermediaplayer.ui.player.ClipRange
import com.astermediaplayer.astermediaplayer.ui.player.DirectMedia
import com.astermediaplayer.astermediaplayer.ui.player.PlayerScreen
import com.astermediaplayer.astermediaplayer.ui.player.PlayerViewModel
import com.astermediaplayer.astermediaplayer.ui.search.SearchScreen
import com.astermediaplayer.astermediaplayer.ui.search.SearchViewModel
import com.astermediaplayer.astermediaplayer.ui.settings.SettingsScreen
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

/** Ids come either way: with dashes, or as 32 hex digits. */
private fun parseId(id: String): UUID? = runCatching {
    UUID.fromString(if (id.length == 32) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id)
}.getOrNull()

private object Routes {
    const val AUTH = "auth"
    const val MAIN = "main"
    const val LIBRARY = "library/{id}/{name}"
    const val DETAIL = "detail/{id}"
    const val PLAYER = "player/{id}?start={start}&end={end}&label={label}&begin={begin}"
    const val THEME = "theme?path={path}&title={title}&subtitle={subtitle}"
    fun library(id: UUID, name: String) = "library/$id/${android.net.Uri.encode(name)}"
    fun detail(id: UUID) = "detail/$id"
    fun player(id: UUID, fromBeginning: Boolean = false) = if (fromBeginning) "player/$id?begin=true" else "player/$id"
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
    val scope = rememberCoroutineScope()

    fun open(item: BaseItemDto) = when (item.type) {
        BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW -> nav.navigate(Routes.library(item.id, item.name.orEmpty()))
        else -> nav.navigate(Routes.detail(item.id))
    }

    NavHost(nav, startDestination = if (signedIn) Routes.MAIN else Routes.AUTH, modifier = Modifier.fillMaxSize()) {
        composable(Routes.AUTH) {
            val vm: AuthViewModel = viewModel(factory = viewModelFactory { initializer { AuthViewModel(repo) } })
            AuthScreen(vm, onSignedIn = { nav.navigate(Routes.MAIN) { popUpTo(Routes.AUTH) { inclusive = true } } })
        }
        composable(Routes.MAIN) {
            MainTabs(
                onItem = ::open,
                onPlay = { nav.navigate(Routes.player(it)) },
                onSignOut = {
                    scope.launch {
                        repo.signOut()
                        nav.navigate(Routes.AUTH) { popUpTo(Routes.MAIN) { inclusive = true } }
                    }
                },
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
            val id = UUID.fromString(entry.arguments!!.getString("id"))
            val vm: DetailViewModel = viewModel(factory = viewModelFactory { initializer { DetailViewModel(repo, id) } })
            DetailScreen(
                vm, onBack = { nav.popBackStack() }, onPlay = { nav.navigate(Routes.player(it)) }, onItem = ::open,
                onPlayFromBeginning = { nav.navigate(Routes.player(it, fromBeginning = true)) },
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
            ),
            deepLinks = listOf(navDeepLink { uriPattern = "aster://play/{id}" }),
        ) { entry ->
            val id = UUID.fromString(entry.arguments!!.getString("id"))
            val start = entry.arguments!!.getLong("start")
            val end = entry.arguments!!.getLong("end")
            val clip = if (start >= 0 && end > start) ClipRange(start, end, entry.arguments!!.getString("label")?.ifEmpty { null }) else null
            val vm: PlayerViewModel = viewModel(factory = viewModelFactory { initializer { PlayerViewModel(app, repo, app.settings, id, clip, fromBeginning = entry.arguments!!.getBoolean("begin")) } })
            PlayerScreen(
                vm,
                onBack = { nav.popBackStack() },
                onPlayNext = { next -> nav.navigate(Routes.player(next)) { popUpTo(Routes.PLAYER) { inclusive = true } } },
            )
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

private enum class MoreDestination(val label: String, val icon: ImageVector) {
    Search("Search", Icons.Default.Search),
    Settings("Settings", Icons.Default.Settings),
}

@Composable
private fun MainTabs(onItem: (BaseItemDto) -> Unit, onPlay: (UUID) -> Unit, onSignOut: () -> Unit) {
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

    val content: @Composable (Modifier) -> Unit = { modifier ->
        when (current) {
            Tab.Home -> HomeScreen(home, onItem = onItem, onPlay = onPlay, modifier = modifier)
            Tab.Movies -> if (detectedAnime != null) TypeTab(BaseItemKind.MOVIE, animeLibraries, onItem, onPlay, modifier)
            Tab.Shows -> if (detectedAnime != null) TypeTab(BaseItemKind.SERIES, animeLibraries, onItem, onPlay, modifier)
            Tab.Anime -> AnimeScreen(anime, onItem = onItem, onPlay = onPlay, modifier = modifier)
            Tab.More -> when (more) {
                MoreDestination.Search -> SearchScreen(search, onItem = onItem, modifier = modifier.statusBarsPadding())
                MoreDestination.Settings -> SettingsScreen(repo.session, app.settings, onSignOut, modifier.statusBarsPadding())
                null -> MoreMenu(onPick = { more = it }, modifier = modifier.statusBarsPadding())
            }
        }
    }

    if (wide) {
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
            FloatingTabBar(
                tabs = tabs, current = current, onSelect = ::select,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun MoreMenu(onPick: (MoreDestination) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("More", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
        MoreDestination.entries.forEach { dest ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(dest) }.padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(dest.icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(dest.label, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** The Apple apps' floating, rounded tab bar: the selected tab is blue on a darker pill. */
@Composable
private fun FloatingTabBar(tabs: List<Tab>, current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(36.dp)).background(Color(0xCC2C2C2E)).padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        tabs.forEach { tab ->
            val active = tab == current
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(30.dp))
                    .background(if (active) Color.Black.copy(alpha = 0.45f) else Color.Transparent)
                    .clickable { onSelect(tab) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val tint = if (active) MaterialTheme.colorScheme.primary else Color.White
                Icon(tab.icon, tab.label, tint = tint, modifier = Modifier.size(26.dp))
                Text(tab.label, color = tint, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}
