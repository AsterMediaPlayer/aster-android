package com.astermediaplayer.astermediaplayer.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astermediaplayer.astermediaplayer.data.AppSettings
import com.astermediaplayer.astermediaplayer.data.SavedServer
import com.astermediaplayer.astermediaplayer.data.Session
import com.astermediaplayer.astermediaplayer.data.SettingsStore
import com.astermediaplayer.astermediaplayer.data.ThemeCut
import com.astermediaplayer.astermediaplayer.ui.detail.MenuHost
import com.astermediaplayer.astermediaplayer.ui.detail.MenuItem
import com.astermediaplayer.astermediaplayer.ui.downloads.DownloadOptions
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import com.astermediaplayer.astermediaplayer.ui.player.Qualities
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the servers list in Settings does beyond showing them: the screen's way back to sign-in, and to a server switched to. */
class ServerActions(val repo: com.astermediaplayer.astermediaplayer.data.AsterRepository, val onSwitched: () -> Unit, val onAddServer: () -> Unit, val onOpenChannels: () -> Unit = {})

/** The pages Settings opens (the Apple apps' `SettingsDestination`): a root of categories, each its own page. */
private sealed interface SettingsPage {
    data object Root : SettingsPage
    data object Playback : SettingsPage
    data object Subtitles : SettingsPage
    data object DiscMenus : SettingsPage
    data object TitlePages : SettingsPage
    data object ServerPlugins : SettingsPage
    data object Downloads : SettingsPage
    data object Account : SettingsPage
    data object About : SettingsPage
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
            SettingsPage.Root -> RootPage(session, servers.onOpenChannels, ::open)
            SettingsPage.Playback -> PlaybackPage(settings, store, scope)
            SettingsPage.Subtitles -> SubtitlesPage(settings, store, scope)
            SettingsPage.DiscMenus -> DiscMenusPage(settings, store, scope)
            SettingsPage.TitlePages -> TitlePagesPage(settings, store, scope)
            SettingsPage.ServerPlugins -> ServerPluginsPage(servers.repo)
            SettingsPage.Downloads -> DownloadsPage(settings, store, scope)
            SettingsPage.Account -> AccountPage(session, servers, onSignOut, ::open)
            SettingsPage.About -> AboutPage()
            is SettingsPage.Server -> ServerPage(page.id, servers, ::open, ::back)
            is SettingsPage.LockAfter -> LockAfterPage(page.id, servers, ::back)
        }
    }
}

private fun SettingsPage.title(servers: List<SavedServer>) = when (this) {
    SettingsPage.Root -> "Settings"
    SettingsPage.Playback -> "Playback"
    SettingsPage.Subtitles -> "Audio & Subtitles"
    SettingsPage.DiscMenus -> "Disc Menus"
    SettingsPage.TitlePages -> "Title Pages"
    SettingsPage.ServerPlugins -> "Server Plugins"
    SettingsPage.Downloads -> "Downloads"
    SettingsPage.Account -> "Account"
    SettingsPage.About -> "About"
    is SettingsPage.Server -> servers.firstOrNull { it.id == id }?.titleAmong(servers) ?: "Server"
    is SettingsPage.LockAfter -> "Lock After"
}

/** The first row of a page that can be pressed takes the remote as the page opens. */
private class FirstFocus {
    val requester = androidx.compose.ui.focus.FocusRequester()
    private var taken = false
    fun claim(): Boolean { if (taken) return false; taken = true; return true }
}
private val LocalFirstFocus = androidx.compose.runtime.compositionLocalOf<FirstFocus?> { null }

/** A row's modifier: the page's first pressable one asks for focus when the page opens. */
@Composable
private fun firstFocusModifier(pressable: Boolean): Modifier {
    val first = LocalFirstFocus.current
    // Decided again when a row becomes pressable (a page still loading has its rows disabled).
    return remember(pressable) { if (pressable && first?.claim() == true) Modifier.focusRequester(first.requester) else Modifier }
}

/** The page's title and scrolling column, in the TV's list style or a phone's; focus goes into the first row as a page opens. */
@Composable
private fun SettingsFrame(page: SettingsPage, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val tv = LocalIsTv.current
    val u = Lockup.tvUnit
    val first = remember(page) { FirstFocus() }
    // Rows aren't placed in the frame a page opens in, so asking again until one takes it.
    LaunchedEffect(page) { if (tv) repeat(50) { if (runCatching { first.requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect else delay(60) } }
    if (tv) {
        androidx.compose.runtime.CompositionLocalProvider(LocalFirstFocus provides first) {
            Column(
                modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = (90f * u).dp).padding(top = (40f * u).dp, bottom = (80f * u).dp),
                verticalArrangement = Arrangement.spacedBy((6f * u).dp),
            ) {
                Text(titleOf(page), style = Type.pageTitle.copy(fontSize = (76f * u).sp, lineHeight = (90f * u).sp), modifier = Modifier.padding(bottom = (18f * u).dp))
                content()
            }
        }
    } else {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = TabBarClearance).focusGroup(),
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

// --- The kit: a group heading, a row, a switch, a choice, a note; each drawn the TV way or the phone's. ---

@Composable
internal fun SGroup(title: String? = null) {
    if (title == null) return
    if (LocalIsTv.current) TvGroup(title)
    else Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, start = 4.dp))
}

@Composable
internal fun SNote(text: String) {
    if (LocalIsTv.current) TvNote(text)
    else Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
internal fun SRow(
    title: String, value: String? = null, icon: ImageVector? = null, chevron: Boolean = false, enabled: Boolean = true,
    destructive: Boolean = false, onClick: (() -> Unit)? = null,
) {
    if (LocalIsTv.current) { TvRow(title, value, enabled = enabled, destructive = destructive, modifier = firstFocusModifier(onClick != null && enabled), icon = icon, chevron = chevron, onClick = onClick); return }
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.padding(end = 14.dp).size(24.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant)
        if (value != null) Text(value, Modifier.padding(start = 12.dp).widthIn(max = 220.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (chevron) Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
internal fun SSwitch(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    if (LocalIsTv.current) { TvSwitchRow(title, checked, enabled = enabled, modifier = firstFocusModifier(enabled), onChange = onChange); return }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)).padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
internal fun SChoice(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    if (LocalIsTv.current) { TvChoiceRow(title, options, selected, modifier = firstFocusModifier(true), onPick = onPick); return }
    var open by remember { mutableStateOf(false) }
    Box {
        SRow(title, options.getOrNull(selected).orEmpty(), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, name -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(i) }, trailingIcon = if (i == selected) ({ Icon(Icons.Default.Check, null) }) else null) }
        }
    }
}

// --- The pages ---

@Composable
private fun RootPage(session: Session?, openChannels: () -> Unit, open: (SettingsPage) -> Unit) {
    val tv = LocalIsTv.current
    SRow("Playback", icon = Icons.Default.PlayCircle, chevron = true) { open(SettingsPage.Playback) }
    SRow("Audio & Subtitles", icon = Icons.Default.ClosedCaption, chevron = true) { open(SettingsPage.Subtitles) }
    // Disc menus come from a Jellyfin plugin.
    if (session?.kind == com.astermediaplayer.astermediaplayer.data.emby.ServerKind.Jellyfin) SRow("Disc Menus", icon = Icons.Default.Album, chevron = true) { open(SettingsPage.DiscMenus) }
    SRow("Title Pages", icon = Icons.Default.MusicNote, chevron = true) { open(SettingsPage.TitlePages) }
    // Channels are playlist tags in Jellyfin's shape, and the plugins are Jellyfin's: neither on Emby.
    if (session?.kind == com.astermediaplayer.astermediaplayer.data.emby.ServerKind.Jellyfin) {
        SRow("Channels (Beta)", icon = Icons.Default.Tv, chevron = true, onClick = openChannels)
        SRow("Server Plugins", icon = Icons.Default.Extension, chevron = true) { open(SettingsPage.ServerPlugins) }
    }
    if (!tv) SRow("Downloads", icon = Icons.Default.Download, chevron = true) { open(SettingsPage.Downloads) }
    SGroup(" ")
    SRow("Account", session?.userName.orEmpty(), icon = Icons.Default.AccountCircle, chevron = true) { open(SettingsPage.Account) }
    SRow("About", icon = Icons.Default.Info, chevron = true) { open(SettingsPage.About) }
}

@Composable
private fun PlaybackPage(settings: AppSettings, store: SettingsStore, scope: kotlinx.coroutines.CoroutineScope) {
    SChoice("Default quality", Qualities.map { it.label }, settings.qualityIndex) { scope.launch { store.setQualityIndex(it) } }
    SChoice("Opening and ending version", ThemeCut.entries.map { it.settingTitle }, ThemeCut.entries.indexOf(settings.themeCut)) { scope.launch { store.setThemeCut(ThemeCut.entries[it]) } }
    SSwitch("Skip intros and credits automatically", settings.autoSkipSegments) { scope.launch { store.setAutoSkipSegments(it) } }
    SSwitch("Play the next episode automatically", settings.autoPlayNext) { scope.launch { store.setAutoPlayNext(it) } }
}

@Composable
private fun SubtitlesPage(settings: AppSettings, store: SettingsStore, scope: kotlinx.coroutines.CoroutineScope) {
    SSwitch("Draw styled ASS/SSA subtitles (libass)", settings.styledSubtitles) { scope.launch { store.setStyledSubtitles(it) } }
    SNote("Draws a track's own fonts, colours and positions, as the Apple apps do, instead of plain text.")
}

@Composable
private fun DiscMenusPage(settings: AppSettings, store: SettingsStore, scope: kotlinx.coroutines.CoroutineScope) {
    SSwitch("Open disc menu when pressing Play", settings.preferDiscMenu) { scope.launch { store.setPreferDiscMenu(it) } }
    SSwitch("Menu music and sounds", settings.discMenuSounds) { scope.launch { store.setDiscMenuSounds(it) } }
    SNote("Titles with a JellyDisc menu on the server show a Disc Menu button on their page. Turn on the first option to start those titles at their menu, like putting in the disc.")
}

@Composable
private fun TitlePagesPage(settings: AppSettings, store: SettingsStore, scope: kotlinx.coroutines.CoroutineScope) {
    SSwitch("Theme music", settings.themeMusic) { scope.launch { store.setThemeMusic(it) } }
    SSwitch("Theme videos", settings.themeVideos) { scope.launch { store.setThemeVideos(it) } }
    SNote("Plays a title's theme song quietly, or loops its theme video behind the page, when your library has them.")
    SGroup("Anime")
    SSwitch("Opening plays with sound", settings.openingSound, enabled = settings.themeVideos) { scope.launch { store.setOpeningSound(it) } }
    SNote("Anime with no theme video of its own loops its first opening behind the series page, when Theme videos is on. With sound, it plays quietly, like theme music.")
}

/** Aster's companion plugins on the server: a switch each for an administrator, else whether they're there. */
@Composable
private fun ServerPluginsPage(repo: com.astermediaplayer.astermediaplayer.data.AsterRepository) {
    val model = remember { com.astermediaplayer.astermediaplayer.data.ServerPluginsModel(repo) }
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(model) { model.load() }
    val admin = model.isAdministrator
    if (admin == null) { SNote("Looking at your server…"); return }
    for (plugin in com.astermediaplayer.astermediaplayer.data.AsterPlugin.entries) {
        val state = model.state(plugin)
        val title = "${plugin.headline} · ${plugin.packageName}"
        if (admin) SSwitch(title, state.isOn, enabled = state.canSwitch && model.restart != com.astermediaplayer.astermediaplayer.data.ServerPluginsModel.Restart.Restarting) { scope.launch { model.set(plugin, it) } }
        else SRow(title)
        // An administrator's switch already says On or Off.
        val caption = state.caption("Jellyfin").takeIf { it.isNotEmpty() && !(admin && (state == com.astermediaplayer.astermediaplayer.data.PluginState.Active || state == com.astermediaplayer.astermediaplayer.data.PluginState.Disabled)) }
        SNote(plugin.summary + caption?.let { "\n$it" }.orEmpty())
    }
    if (admin) SNote("Free plugins for Jellyfin that add more to Aster. Switching one on installs it on your server; off turns it off and keeps its settings. Either takes a server restart.")
    if (admin && (model.needsRestart || model.restart != com.astermediaplayer.astermediaplayer.data.ServerPluginsModel.Restart.Idle || model.errorMessage != null)) {
        SGroup("Restart")
        when (val restart = model.restart) {
            com.astermediaplayer.astermediaplayer.data.ServerPluginsModel.Restart.Restarting -> SNote("Restarting your server. This usually takes about a minute…")
            is com.astermediaplayer.astermediaplayer.data.ServerPluginsModel.Restart.Failed -> SNote(restart.message)
            com.astermediaplayer.astermediaplayer.data.ServerPluginsModel.Restart.Idle -> if (model.needsRestart) {
                if (model.canRestart) {
                    SNote("Almost there. Your server needs a quick restart to finish.")
                    SRow("Restart Server", destructive = true) { confirming = true }
                } else SNote("Almost there. Restart Jellyfin to finish (it can't restart itself where it's running).")
            }
        }
        model.errorMessage?.let { SNote(it) }
    }
    SGroup(" ")
    SNote("Aster Aurora, for the web: brings as much of Aster as it can to Jellyfin in your browser, with big hero banners, trailers playing behind titles, and more. Once Aster's repository is on your server, you'll find it in the plugin catalogue in Jellyfin's dashboard.")
    if (!admin) SNote("Want these? Ask whoever runs your server. They can switch them on from Aster in a few seconds, or add this repository under Plugins ▸ Repositories in Jellyfin's dashboard: ${com.astermediaplayer.astermediaplayer.data.AsterPlugin.REPOSITORY_URL}")
    if (confirming) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Restart your server now?") }, text = { Text("Anyone watching will be paused for about a minute.") },
        confirmButton = { androidx.compose.material3.TextButton({ confirming = false; scope.launch { model.restartServer() } }) { Text("Restart") } },
        dismissButton = { androidx.compose.material3.TextButton({ confirming = false }) { Text("Cancel") } },
    )
}

@Composable
private fun DownloadsPage(settings: AppSettings, store: SettingsStore, scope: kotlinx.coroutines.CoroutineScope) {
    SSwitch("Allow downloads", settings.downloadsEnabled) { scope.launch { store.setDownloadsEnabled(it) } }
    if (settings.downloadsEnabled) DownloadOptions()
}

@Composable
private fun AboutPage() {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    SRow("Aster", version)
    SNote("A Jellyfin and Emby player in the Apple apps' design.")
}

/** Account: the server in use, the user, and the servers signed in to, to switch between. */
@Composable
private fun AccountPage(session: Session?, servers: ServerActions, onSignOut: () -> Unit, open: (SettingsPage) -> Unit) {
    val repo = servers.repo
    val saved by repo.savedServers.collectAsState()
    val version by produceState<String?>(null, session?.serverUrl) { value = repo.serverVersion() }
    SGroup("Server")
    SRow("Server", session?.serverName ?: session?.serverUrl.orEmpty())
    SRow("Address", session?.serverUrl.orEmpty())
    if (version != null) SRow("${session?.kind?.displayName ?: "Server"} version", version)
    SGroup("User")
    SRow("User", session?.userName.orEmpty())
    SRow("Switch User", destructive = true, onClick = onSignOut)
    SGroup("Servers")
    saved.forEach { server ->
        val current = server.id == repo.currentServerId
        SRow(server.titleAmong(saved), "${server.session.kind.displayName} · ${server.session.userName}" + if (current) " · In Use" else "", chevron = true) { open(SettingsPage.Server(server.id)) }
    }
    SRow("Add Server", onClick = servers.onAddServer)
    SNote("Servers you've signed in to stay signed in, to switch between. Add one to sign in to another Jellyfin or Emby server.")
}

/** One saved server: switch to it, ask for its password first, lock it after a while, or forget it. */
@Composable
private fun ServerPage(id: String, servers: ServerActions, open: (SettingsPage) -> Unit, back: () -> Unit) {
    val repo = servers.repo
    val saved by repo.savedServers.collectAsState()
    val server = saved.firstOrNull { it.id == id } ?: return
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    val inUse = id == repo.currentServerId
    SRow("Server", server.session.kind.displayName)
    SRow("Address", server.session.serverUrl)
    SRow("User", server.session.userName)
    SGroup(" ")
    if (inUse) SNote("This is the server in use.")
    else SRow("Switch to This Server") {
        if (server.asksPasswordToSwitch) asking = true else scope.launch { repo.switchTo(server); servers.onSwitched() }
    }
    SSwitch("Ask for Password to Switch", server.asksPasswordToSwitch) { scope.launch { repo.setAsksPasswordToSwitch(id, it) } }
    SNote("Switching to this server asks for ${server.session.userName}'s password first, checked by the server, so others on this device can't switch to it.")
    SGroup(" ")
    SRow("Lock After", SavedServer.lockTitle(server.lockAfterMinutes), chevron = true) { open(SettingsPage.LockAfter(id)) }
    SNote("After this long away from Aster, coming back to this server asks for ${server.session.userName}'s password.")
    if (!inUse) {
        SGroup(" ")
        SRow("Remove Server", destructive = true) { scope.launch { repo.removeServer(id); back() } }
    }
    if (asking) com.astermediaplayer.astermediaplayer.ui.auth.ServerPasswordDialog(
        server, "Switch", title = server.titleAmong(saved),
        onCheck = { password -> val fresh = repo.signInAgain(server, password); repo.switchTo(server, fresh); servers.onSwitched() },
        onDismiss = { asking = false },
    )
}

/** A saved server's Lock After choices: picking one saves it and goes back. */
@Composable
private fun LockAfterPage(id: String, servers: ServerActions, back: () -> Unit) {
    val repo = servers.repo
    val saved by repo.savedServers.collectAsState()
    val chosen = saved.firstOrNull { it.id == id }?.lockAfterMinutes
    val scope = rememberCoroutineScope()
    (listOf<Int?>(null) + SavedServer.LOCK_CHOICES).forEach { minutes ->
        SRow(SavedServer.lockTitle(minutes), if (minutes == chosen) "✓" else null) { scope.launch { repo.setLockAfter(id, minutes); back() } }
    }
}

@Composable
internal fun TvGroup(title: String) {
    val u = Lockup.tvUnit
    Text(title.uppercase(), style = Type.caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp), color = SecondaryText, modifier = Modifier.padding(top = (26f * u).dp, bottom = (6f * u).dp, start = (8f * u).dp))
}

@Composable
internal fun TvNote(text: String) {
    val u = Lockup.tvUnit
    Text(text, style = Type.caption, color = SecondaryText, modifier = Modifier.padding(horizontal = (8f * u).dp, vertical = (4f * u).dp).widthIn(max = (1200f * u).dp))
}

/** One row of the list: its title on the left and a value on the right, grey, and white with black text when focused. */
@Composable
internal fun TvRow(
    title: String, value: String?, enabled: Boolean = true, destructive: Boolean = false, modifier: Modifier = Modifier,
    icon: ImageVector? = null, chevron: Boolean = false, onClick: (() -> Unit)?,
) {
    val u = Lockup.tvUnit
    var focused by remember { mutableStateOf(false) }
    val tint = when {
        focused -> if (destructive) Color(0xFFD70015) else Color.Black
        !enabled -> Color.White.copy(alpha = 0.4f)
        destructive -> Color(0xFFFF453A)
        else -> Color.White
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = (82f * u).dp).then(if (onClick != null) Modifier.tvFocus((14f * u).dp, lift = 1.02f, ring = false).onFocusChanged { focused = it.isFocused } else Modifier)
            .clip(RoundedCornerShape((14f * u).dp)).background(if (focused) Color.White else Color.White.copy(alpha = 0.1f))
            .then(if (onClick != null && enabled) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick) else if (onClick != null) Modifier.focusable() else Modifier)
            .padding(horizontal = (28f * u).dp, vertical = (14f * u).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.padding(end = (20f * u).dp).size((38f * u).dp))
        // A long title wraps in what the value leaves; a long value gives way with an ellipsis, never out of the row.
        Text(title, style = Type.callout, color = tint, modifier = Modifier.weight(1f))
        if (value != null) Text(
            value, style = Type.callout, color = if (focused) Color.Black.copy(alpha = 0.6f) else SecondaryText, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = (16f * u).dp).widthIn(max = (900f * u).dp),
        )
        if (chevron) Icon(Icons.Default.ChevronRight, null, tint = if (focused) Color.Black.copy(alpha = 0.5f) else SecondaryText, modifier = Modifier.padding(start = (12f * u).dp).size((34f * u).dp))
    }
}

@Composable
internal fun TvSwitchRow(title: String, checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) =
    TvRow(title, if (checked) "On" else "Off", enabled = enabled, modifier = modifier, onClick = { onChange(!checked) })

/** A row showing the current choice; OK opens the glass menu of them below it. */
@Composable
internal fun TvChoiceRow(title: String, options: List<String>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TvRow(title, options.getOrNull(selected).orEmpty(), modifier = modifier, onClick = { open = true })
        MenuHost(expanded = open, onDismiss = { open = false }, below = true) {
            options.forEachIndexed { i, name -> MenuItem(name, null, first = i == selected, checked = i == selected) { open = false; onPick(i) } }
        }
    }
}
