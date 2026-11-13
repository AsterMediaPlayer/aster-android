package com.astermediaplayer.astermediaplayer.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astermediaplayer.astermediaplayer.data.AppSettings
import com.astermediaplayer.astermediaplayer.data.SavedServer
import com.astermediaplayer.astermediaplayer.data.Session
import com.astermediaplayer.astermediaplayer.data.SettingsStore
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.delay

/** What the servers list in Settings does beyond showing them: the screen's way back to sign-in, and to a server switched to. */
class ServerActions(val repo: com.astermediaplayer.astermediaplayer.data.AsterRepository, val onSwitched: () -> Unit, val onAddServer: () -> Unit, val onOpenChannels: () -> Unit = {})

/** The pages Settings opens (the Apple apps' `SettingsDestination`): a root of categories, each its own page. */
internal sealed interface SettingsPage {
    data object Root : SettingsPage
    data object Playback : SettingsPage
    data object Subtitles : SettingsPage
    data object DiscMenus : SettingsPage
    data object TitlePages : SettingsPage
    data object Tracking : SettingsPage
    data object ServerPlugins : SettingsPage
    data object Downloads : SettingsPage
    data object Account : SettingsPage
    data object About : SettingsPage
    data object OpenSource : SettingsPage
    data class License(val name: String) : SettingsPage
    data class Server(val id: String) : SettingsPage
    data class LockAfter(val id: String) : SettingsPage
}

/**
 * Settings in the Apple apps' shape: a root of categories (Playback, Audio & Subtitles, Title Pages, then Account and About), each
 * opening its own page, with a saved server's page, and its Lock After choices, under Account. One set of pages for a TV and a phone.
 */
@Composable
fun SettingsScreen(session: Session?, store: SettingsStore, servers: ServerActions, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    // The settings that follow the user are fetched as Settings opens, so a change made on another device shows here.
    val appForSync = LocalContext.current.applicationContext as com.astermediaplayer.astermediaplayer.AsterApp
    LaunchedEffect(session?.serverUrl, session?.userId) { if (session != null) appForSync.settingsSync.pull() }
    val stack = remember { mutableStateListOf<SettingsPage>(SettingsPage.Root) }
    val page = stack.last()
    fun open(next: SettingsPage) { stack.add(next) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { back() }

    SettingsFrame(page, modifier) {
        when (page) {
            SettingsPage.Root -> RootPage(session, servers.repo, servers.onOpenChannels, ::open)
            SettingsPage.Playback -> PlaybackPage(settings, store, scope)
            SettingsPage.Subtitles -> SubtitlesPage(settings, store, scope, servers.repo)
            SettingsPage.DiscMenus -> DiscMenusPage(settings, store, scope)
            SettingsPage.TitlePages -> TitlePagesPage(settings, store, scope, servers.repo)
            SettingsPage.Tracking -> TrackingPage(servers.repo)
            SettingsPage.ServerPlugins -> ServerPluginsPage(servers.repo, store)
            SettingsPage.Downloads -> DownloadsPage(settings, store, scope)
            SettingsPage.Account -> AccountPage(session, servers, onSignOut, ::open)
            SettingsPage.About -> AboutPage(::open)
            SettingsPage.OpenSource -> OpenSourcePage(::open)
            is SettingsPage.License -> LicensePage(page.name)
            is SettingsPage.Server -> ServerPage(page.id, servers, ::open, ::back)
            is SettingsPage.LockAfter -> LockAfterPage(page.id, servers, ::back)
        }
    }
}

internal fun SettingsPage.title(servers: List<SavedServer>) = when (this) {
    SettingsPage.Root -> "Settings"
    SettingsPage.Playback -> "Playback"
    SettingsPage.Subtitles -> "Audio & Subtitles"
    SettingsPage.DiscMenus -> "Disc Menus"
    SettingsPage.TitlePages -> "Title Pages"
    SettingsPage.Tracking -> "Tracking"
    SettingsPage.ServerPlugins -> "Server Plugins"
    SettingsPage.Downloads -> "Downloads"
    SettingsPage.Account -> "Account"
    SettingsPage.About -> "About"
    SettingsPage.OpenSource -> "Open Source & Licenses"
    is SettingsPage.License -> name
    is SettingsPage.Server -> servers.firstOrNull { it.id == id }?.titleAmong(servers) ?: "Server"
    is SettingsPage.LockAfter -> "Lock After"
}


/** The page's title and scrolling column, in the TV's list style or a phone's; focus goes into the first row as a page opens. */
@Composable
internal fun SettingsFrame(page: SettingsPage, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val tv = LocalIsTv.current
    val u = Lockup.tvUnit
    val first = remember(page) { FirstFocus() }
    // Each page opens at its top, not where the one before it was scrolled to.
    val scroll = remember(page) { androidx.compose.foundation.ScrollState(0) }
    // Rows aren't placed in the frame a page opens in, so asking again until one takes it.
    LaunchedEffect(page) { if (tv) repeat(50) { if (runCatching { first.requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect else delay(60) } }
    if (tv) {
        androidx.compose.runtime.CompositionLocalProvider(LocalFirstFocus provides first) {
            Column(
                modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = (90f * u).dp).padding(top = (40f * u).dp, bottom = (80f * u).dp),
                verticalArrangement = Arrangement.spacedBy((6f * u).dp),
            ) {
                Text(titleOf(page), style = Type.pageTitle.copy(fontSize = (76f * u).sp, lineHeight = (90f * u).sp), modifier = Modifier.padding(bottom = (18f * u).dp))
                content()
            }
        }
    } else {
        Column(
            modifier.fillMaxSize().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = TabBarClearance).focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(titleOf(page), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 8.dp))
            content()
        }
    }
}

@Composable
private fun titleOf(page: SettingsPage): String {
    val app = LocalContext.current.applicationContext as com.astermediaplayer.astermediaplayer.AsterApp
    val servers by app.repository.savedServers.collectAsState()
    return page.title(servers)
}

// --- The pages ---

@Composable
internal fun RootPage(session: Session?, repo: com.astermediaplayer.astermediaplayer.data.AsterRepository, openChannels: () -> Unit, open: (SettingsPage) -> Unit) {
    val tv = LocalIsTv.current
    // Tracking accounts are linked on the server, through its plugin: shown where it offers any.
    val tracks by produceState(false, session?.serverUrl, session?.userId) {
        value = session?.kind != com.astermediaplayer.astermediaplayer.data.emby.ServerKind.Emby && repo.trackingFeatures()?.services.orEmpty().isNotEmpty()
    }
    SRow("Playback", icon = Icons.Default.PlayCircle, chevron = true) { open(SettingsPage.Playback) }
    SRow("Audio & Subtitles", icon = Icons.Default.ClosedCaption, chevron = true) { open(SettingsPage.Subtitles) }
    // Disc menus come from a Jellyfin plugin.
    if (session?.kind == com.astermediaplayer.astermediaplayer.data.emby.ServerKind.Jellyfin) SRow("Disc Menus", icon = Icons.Default.Album, chevron = true) { open(SettingsPage.DiscMenus) }
    SRow("Title Pages", icon = Icons.Default.MusicNote, chevron = true) { open(SettingsPage.TitlePages) }
    if (tracks) SRow("Tracking", icon = Icons.Default.Checklist, chevron = true) { open(SettingsPage.Tracking) }
    // Channels are playlist tags on every server (Plex keeps them in a playlist's description); the plugins are Jellyfin's.
    if (session != null)
        SRow("Channels", icon = Icons.Default.Tv, chevron = true, onClick = openChannels)
    if (session?.kind == com.astermediaplayer.astermediaplayer.data.emby.ServerKind.Jellyfin)
        SRow("Server Plugins", icon = Icons.Default.Extension, chevron = true) { open(SettingsPage.ServerPlugins) }
    if (!tv) SRow("Downloads", icon = Icons.Default.Download, chevron = true) { open(SettingsPage.Downloads) }
    SGroup(" ")
    SRow("Account", session?.userName.orEmpty(), icon = Icons.Default.AccountCircle, chevron = true) { open(SettingsPage.Account) }
    SRow("About", icon = Icons.Default.Info, chevron = true) { open(SettingsPage.About) }
}
