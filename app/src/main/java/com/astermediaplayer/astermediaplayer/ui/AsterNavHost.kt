package com.astermediaplayer.astermediaplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
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
import com.astermediaplayer.astermediaplayer.ui.auth.AuthScreen
import com.astermediaplayer.astermediaplayer.ui.auth.AuthViewModel
import com.astermediaplayer.astermediaplayer.ui.detail.DetailScreen
import com.astermediaplayer.astermediaplayer.ui.detail.DetailViewModel
import com.astermediaplayer.astermediaplayer.ui.home.HomeScreen
import com.astermediaplayer.astermediaplayer.ui.home.HomeViewModel
import com.astermediaplayer.astermediaplayer.ui.library.LibraryScreen
import com.astermediaplayer.astermediaplayer.ui.library.LibraryViewModel
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

private object Routes {
    const val AUTH = "auth"
    const val MAIN = "main"
    const val LIBRARY = "library/{id}/{name}"
    const val DETAIL = "detail/{id}"
    const val PLAYER = "player/{id}"
    fun library(id: UUID, name: String) = "library/$id/${android.net.Uri.encode(name)}"
    fun detail(id: UUID) = "detail/$id"
    fun player(id: UUID) = "player/$id"
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
                onLibrary = ::open,
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
            DetailScreen(vm, onBack = { nav.popBackStack() }, onPlay = { nav.navigate(Routes.player(it)) }, onItem = ::open)
        }
        composable(
            Routes.PLAYER,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
            deepLinks = listOf(navDeepLink { uriPattern = "aster://play/{id}" }),
        ) { entry ->
            val id = UUID.fromString(entry.arguments!!.getString("id"))
            val vm: PlayerViewModel = viewModel(factory = viewModelFactory { initializer { PlayerViewModel(app, repo, app.settings, id) } })
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
    Search("Search", Icons.Default.Search),
    Account("Account", Icons.Default.Person),
}

@Composable
private fun MainTabs(onItem: (BaseItemDto) -> Unit, onLibrary: (BaseItemDto) -> Unit, onSignOut: () -> Unit) {
    val app = LocalContext.current.applicationContext as AsterApp
    val repo = app.repository
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    val home: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(repo) } })
    val search: SearchViewModel = viewModel(factory = viewModelFactory { initializer { SearchViewModel(repo) } })

    val content: @Composable (Modifier) -> Unit = { modifier ->
        when (Tab.entries[selected]) {
            Tab.Home -> HomeScreen(home, onItem = onItem, onLibrary = onLibrary, modifier = modifier)
            Tab.Search -> SearchScreen(search, onItem = onItem, modifier = modifier)
            Tab.Account -> SettingsScreen(repo.session, app.settings, onSignOut, modifier)
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail {
                Tab.entries.forEachIndexed { index, tab ->
                    NavigationRailItem(
                        selected = selected == index, onClick = { selected = index },
                        icon = { Icon(tab.icon, tab.label) }, label = { Text(tab.label) },
                    )
                }
            }
            Box(Modifier.weight(1f)) { content(Modifier.fillMaxSize()) }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selected == index, onClick = { selected = index },
                            icon = { Icon(tab.icon, tab.label) }, label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { padding -> content(Modifier.padding(padding)) }
    }
}
