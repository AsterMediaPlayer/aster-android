package com.astermediaplayer.astermediaplayer.ui.settings

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.astermediaplayer.astermediaplayer.data.SavedServer
import com.astermediaplayer.astermediaplayer.data.Session
import kotlinx.coroutines.launch

/** Account: the server in use, the user, and the servers signed in to, to switch between. */
@Composable
internal fun AccountPage(session: Session?, servers: ServerActions, onSignOut: () -> Unit, open: (SettingsPage) -> Unit) {
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

/** One saved server: switch to it, ask for its password (a Plex profile's PIN) first, lock it after a while, or forget it. */
@Composable
internal fun ServerPage(id: String, servers: ServerActions, open: (SettingsPage) -> Unit, back: () -> Unit) {
    val repo = servers.repo
    val saved by repo.savedServers.collectAsState()
    val server = saved.firstOrNull { it.id == id } ?: return
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var loosening by remember { mutableStateOf<LockChange?>(null) }
    val inUse = id == repo.currentServerId
    SRow("Server", server.session.kind.displayName)
    SRow("Address", server.session.serverUrl)
    SRow("User", server.session.userName)
    SGroup(" ")
    if (inUse) SNote("This is the server in use.")
    else SRow("Switch to This Server") {
        if (server.asksPasswordToSwitch) asking = true else scope.launch { repo.switchTo(server); servers.onSwitched() }
    }
    // A password to switch or unlock is the server's own, checked by the server; on Plex it's the Home profile's PIN, checked by plex.tv.
    val plex = server.session.kind == com.astermediaplayer.astermediaplayer.data.emby.ServerKind.Plex
    if (server.canBeLocked) {
        val who = if (plex) "${server.session.userName}'s Plex Home PIN" else "${server.session.userName}'s password"
        SSwitch("Ask for ${server.credential.replaceFirstChar { it.uppercase() }} to Switch", server.asksPasswordToSwitch) { asks ->
            // Turning it off asks for the password (or PIN), so nobody else can.
            if (server.loosens(asksToSwitch = asks)) loosening = LockChange("stop asking for it to switch", "Turn Off") { repo.setAsksPasswordToSwitch(id, false) }
            else scope.launch { repo.setAsksPasswordToSwitch(id, asks) }
        }
        SNote("Switching to this server asks for $who first, checked by ${if (plex) "plex.tv" else "the server"}, so others on this device can't switch to it.")
        SGroup(" ")
        SRow("Lock After", SavedServer.lockTitle(server.lockAfterMinutes), chevron = true) { open(SettingsPage.LockAfter(id)) }
        SNote("After this long away from Aster, coming back to this server asks for $who.")
    } else if (plex) {
        // A profile with no PIN, or a sign-in from before the Home was kept: signing in again can pick a profile that has one.
        SRow("Sign In with Plex Again") { servers.onAddServer() }
        SNote("Asking for a PIN to switch, and Lock After, need a Plex Home profile with a PIN. If ${server.session.userName} has one now (set at plex.tv), sign in with Plex again to use it.")
    }
    if (!inUse) {
        SGroup(" ")
        SRow("Remove Server", destructive = true) { scope.launch { repo.removeServer(id); back() } }
    }
    if (asking) com.astermediaplayer.astermediaplayer.ui.auth.ServerPasswordDialog(
        server, "Switch", title = server.titleAmong(saved),
        onCheck = { password -> val fresh = repo.signInAgain(server, password); repo.switchTo(server, fresh); servers.onSwitched() },
        onDismiss = { asking = false },
    )
    loosening?.let { change -> LockChangeDialog(server, saved, repo, change) { loosening = null } }
}

/** A change that lets more past a server's lock, held until its password is given, as on the Apple apps. */
private class LockChange(val purpose: String, val button: String, val apply: suspend () -> Unit)

@Composable
private fun LockChangeDialog(server: SavedServer, saved: List<SavedServer>, repo: com.astermediaplayer.astermediaplayer.data.AsterRepository, change: LockChange, onDismiss: () -> Unit) {
    com.astermediaplayer.astermediaplayer.ui.auth.ServerPasswordDialog(
        server, change.button, title = server.titleAmong(saved), purpose = change.purpose,
        // The check is a fresh sign-in, which ends the one saved: it's kept in its place.
        onCheck = { password -> repo.keepSignIn(repo.signInAgain(server, password)); change.apply() },
        onDismiss = onDismiss,
    )
}

/** A saved server's Lock After choices: picking one saves it and goes back. */
@Composable
internal fun LockAfterPage(id: String, servers: ServerActions, back: () -> Unit) {
    val repo = servers.repo
    val saved by repo.savedServers.collectAsState()
    val server = saved.firstOrNull { it.id == id }
    val chosen = server?.lockAfterMinutes
    val scope = rememberCoroutineScope()
    var loosening by remember { mutableStateOf<LockChange?>(null) }
    (listOf<Int?>(null) + SavedServer.LOCK_CHOICES).forEach { minutes ->
        SRow(SavedServer.lockTitle(minutes), if (minutes == chosen) "✓" else null) {
            // Never, or a longer wait, asks for the password first.
            if (server != null && server.loosens(lockAfter = minutes)) loosening = LockChange("change when it locks", "Change") { repo.setLockAfter(id, minutes); back() }
            else scope.launch { repo.setLockAfter(id, minutes); back() }
        }
    }
    if (server != null) loosening?.let { change -> LockChangeDialog(server, saved, repo, change) { loosening = null } }
}
