package com.astermediaplayer.astermediaplayer.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.astermediaplayer.astermediaplayer.data.SettingsStore
import kotlinx.coroutines.launch

/** Aster's companion plugins on the server: a switch each for an administrator, else whether they're there. */
@Composable
internal fun ServerPluginsPage(repo: com.astermediaplayer.astermediaplayer.data.AsterRepository, store: SettingsStore) {
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
    // Brings the welcome screen back for this server, in place of the tabs.
    repo.currentServerId?.let { id ->
        SGroup(" ")
        SRow("Show Welcome Screen") { scope.launch { store.showWelcomeAgain(id) } }
        SNote("What Aster does, and what these plugins add, as it first appeared when you signed in.")
    }
    if (confirming) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Restart your server now?") }, text = { Text("Anyone watching will be paused for about a minute.") },
        confirmButton = { androidx.compose.material3.TextButton({ confirming = false; scope.launch { model.restartServer() } }) { Text("Restart") } },
        dismissButton = { androidx.compose.material3.TextButton({ confirming = false }) { Text("Cancel") } },
    )
}
