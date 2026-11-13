package com.astermediaplayer.astermediaplayer.ui.auth

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Alignment
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.astermediaplayer.astermediaplayer.data.SavedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The user's own password for a saved server, checked by the server itself ([onCheck] throws when it's wrong), before switching
 * to it. Closes itself once it's right.
 */
@Composable
fun ServerPasswordDialog(
    server: SavedServer, button: String, title: String = server.title,
    /** What it's for, when not switching to the server: "Enter Ann's password to {purpose}." (on Plex, "Plex Home PIN"). */
    purpose: String = "switch to this ${server.session.kind.displayName} server",
    onCheck: suspend (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun check() {
        if (busy || !isReady(password, server)) return
        busy = true; error = null
        scope.launch {
            try { onCheck(password); onDismiss() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { busy = false; attempt++; error = failure(e, server); if (server.asksProfilePin) password = "" }
        }
    }
    val plex = server.credential == "PIN"
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val what = when { server.session.kind == com.astermediaplayer.astermediaplayer.data.emby.ServerKind.Plex -> "Plex Home PIN"; plex -> "PIN"; else -> "password" }
                Text("Enter ${server.session.userName}'s $what to $purpose.")
                // A household's PIN on the PIN pad; a password or a Plex Home PIN in a field.
                if (server.asksProfilePin) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    com.astermediaplayer.astermediaplayer.ui.profiles.PinCodeEntry(password, { password = it; error = null }, attempt, enabled = !busy, onSubmit = ::check)
                } else OutlinedTextField(
                    value = password, onValueChange = { password = it; error = null }, label = { Text(if (plex) "PIN" else "Password") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (plex) KeyboardType.NumberPassword else KeyboardType.Password, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { check() }),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = ::check, enabled = !busy && isReady(password, server)) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(button) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** Whether what's typed is worth sending: any password, or a whole household PIN. */
private fun isReady(text: String, server: SavedServer) =
    if (server.asksProfilePin) com.astermediaplayer.astermediaplayer.ui.profiles.PinCode.isComplete(text) else text.isNotEmpty()

/** Why a check failed: a profile's own reason (a wrong PIN, too many tries), else that it didn't work. */
private fun failure(e: Exception, server: SavedServer): String =
    (e as? com.astermediaplayer.astermediaplayer.data.profiles.ProfileSwitchException)?.message ?: "That ${server.credential} didn't work."

/**
 * The server in use after the app was away longer than its lock-after (or as it opens, for a profile with a PIN): its user's
 * password or PIN (checked by the server) to carry on, or another server. In a household, someone else can pick their own profile
 * ([onSwitchProfile]) without the locked one's PIN.
 */
@Composable
fun ServerLockScreen(server: SavedServer, title: String = server.title, onUnlock: suspend (String) -> Unit, onUseAnother: () -> Unit, onSwitchProfile: (() -> Unit)? = null) {
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun check() {
        if (busy || !isReady(password, server)) return
        busy = true; error = null
        scope.launch {
            try { onUnlock(password) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { busy = false; attempt++; error = failure(e, server); if (server.asksProfilePin) password = "" }
        }
    }
    val field = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (!server.asksProfilePin) runCatching { field.requestFocus() } }
    val tv = com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv.current
    val ring = if (tv) Modifier.tvFocus((24f * Lockup.tvUnit).dp, lift = 1.04f) else Modifier
    // Nothing behind it takes a touch while it's up.
    // The keyboard lifts it, so the buttons stay in view with it open; it scrolls where there's little room.
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) { detectTapGestures { } }.safeDrawingPadding().imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val household = server.session.household
            if (server.asksProfilePin && household != null) {
                // The profile's own picture and name: it's this person's PIN that's asked for.
                com.astermediaplayer.astermediaplayer.ui.profiles.ProfileAvatar(
                    household.avatarInitial ?: server.session.userName.take(1).uppercase(),
                    household.avatarColor ?: com.astermediaplayer.astermediaplayer.data.profiles.Bonfire.DEFAULT_AVATAR_COLOR,
                    householdImage(server), if (tv) 110.dp else 72.dp,
                )
                Text(server.session.userName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("Enter your PIN to carry on.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                com.astermediaplayer.astermediaplayer.ui.profiles.PinCodeEntry(password, { password = it; error = null }, attempt, enabled = !busy, onSubmit = ::check)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = ::check, enabled = !busy && isReady(password, server), modifier = ring) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Unlock") }
                    if (onSwitchProfile != null) TextButton(onClick = onSwitchProfile, enabled = !busy, modifier = ring) { Text("Switch Profile") }
                }
                TextButton(onClick = onUseAnother, enabled = !busy, modifier = ring) { Text("Use Another Server") }
                return@Column
            }
            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            val plex = server.credential == "PIN"
            val what = when { server.session.kind == com.astermediaplayer.astermediaplayer.data.emby.ServerKind.Plex -> "Plex Home PIN"; plex -> "PIN"; else -> "password" }
            Text("Enter ${server.session.userName}'s $what to carry on.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = password, onValueChange = { password = it; error = null }, label = { Text(if (plex) "PIN" else "Password") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(field), visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = if (plex) KeyboardType.NumberPassword else KeyboardType.Password, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { check() }),
            )
            Button(onClick = ::check, enabled = !busy && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Unlock") }
            if (onSwitchProfile != null) TextButton(onClick = onSwitchProfile, enabled = !busy) { Text("Switch Profile") }
            TextButton(onClick = onUseAnother, enabled = !busy) { Text("Use Another Server") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** A household profile's picture, as the picker finds it: a web address as it is, anything else through the plugin's image route. */
private fun householdImage(server: SavedServer): String? {
    val image = server.session.household?.profileImage?.ifEmpty { null } ?: return null
    if (image.startsWith("http://", true) || image.startsWith("https://", true)) return image
    return com.astermediaplayer.astermediaplayer.data.profiles.BonfireClient({ _, _, _ -> error("unused") }, server.session.serverUrl).imageUrl(server.session.userId)
}
