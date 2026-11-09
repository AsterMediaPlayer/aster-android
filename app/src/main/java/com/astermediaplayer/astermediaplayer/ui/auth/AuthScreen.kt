package com.astermediaplayer.astermediaplayer.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.focusable
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AuthScreen(viewModel: AuthViewModel, onSignedIn: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // On a TV the remote's Up and Down always move between the fields and buttons: a text field being typed in would otherwise
    // keep them, and the remote could never leave it for Quick Connect.
    val tv = com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // The rest of the TV app's focus ring, so the remote's place shows plainly; it draws nothing on a touch screen.
    val ring = if (tv) Modifier.tvFocus(24.dp) else Modifier
    val leaves = if (!tv) Modifier else Modifier.onPreviewKeyEvent { event ->
        val direction = when (event.key) {
            androidx.compose.ui.input.key.Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
            androidx.compose.ui.input.key.Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
            else -> return@onPreviewKeyEvent false
        }
        if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) focusManager.moveFocus(direction)
        true
    }
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    // Android 17 asks before the app may reach the home network, where most servers are; asked just before the first try.
    val localNetwork = com.astermediaplayer.astermediaplayer.ui.components.rememberLocalNetworkAccess()
    LaunchedEffect(state.signedIn) { if (state.signedIn) onSignedIn() }
    // Back steps back a stage, as on the Apple apps: out of Quick Connect, then to the server screen, rather than leaving the app.
    androidx.activity.compose.BackHandler(enabled = state.connectedServer != null || state.plexCode != null || state.plexServers != null || state.plexUsers != null) {
        when {
            state.plexPinFor != null -> viewModel.backToPlexUsers()
            state.plexCode != null || state.plexServers != null || state.plexUsers != null -> viewModel.cancelPlex()
            state.quickConnectCode != null -> viewModel.cancelQuickConnect()
            else -> viewModel.changeServer()
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Aster", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            if (state.plexCode != null) {
                Text("Link Aster to Plex", style = MaterialTheme.typography.titleMedium)
                Text("On any device, go to plex.tv/link, sign in, and enter this code:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.plexCode!!, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = viewModel::cancelPlex, modifier = ring) { Text("Cancel") }
            } else if (state.plexPinFor != null) {
                // A profile with a PIN: plex.tv checks it by switching to the profile.
                var pin by remember { mutableStateOf("") }
                val person = state.plexPinFor!!
                Text("${person.title}'s PIN", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(8); viewModel.setPlexPinTyping() }, label = { Text("PIN") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().then(leaves), visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { viewModel.submitPlexPin(pin) }),
                )
                state.plexPinError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { viewModel.submitPlexPin(pin) }, enabled = !state.busy && pin.isNotEmpty(), modifier = Modifier.fillMaxWidth().then(ring)) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Continue")
                }
                TextButton(onClick = viewModel::backToPlexUsers, enabled = !state.busy, modifier = ring) { Text("Choose Another Profile") }
            } else if (state.plexUsers != null) {
                Text("Who's watching?", style = MaterialTheme.typography.titleMedium)
                state.plexUsers!!.forEach { person ->
                    OutlinedButton(onClick = { viewModel.pickPlexUser(person) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().then(ring), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(person.title, style = MaterialTheme.typography.bodyLarge)
                            if (person.hasPin) Text("Asks for a PIN", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (state.busy) CircularProgressIndicator(Modifier.padding(2.dp))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = viewModel::cancelPlex, modifier = ring) { Text("Cancel") }
            } else if (state.plexServers != null) {
                Text("Choose a Plex server", style = MaterialTheme.typography.titleMedium)
                state.plexServers!!.forEach { server ->
                    OutlinedButton(onClick = { viewModel.choosePlexServer(server) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().then(ring), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(server.name, style = MaterialTheme.typography.bodyLarge)
                            Text(if (server.owned) "Yours" else "Shared with you", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (state.busy) CircularProgressIndicator(Modifier.padding(2.dp))
                TextButton(onClick = viewModel::cancelPlex, modifier = ring) { Text("Cancel") }
            } else if (state.connectedServer == null) {
                Text("Connect to your Jellyfin or Emby server, or sign in with Plex", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.serverInput, onValueChange = viewModel::setServer,
                    label = { Text("Server address") }, placeholder = { Text("jellyfin.example.com:8096") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().then(leaves),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { localNetwork(viewModel::connect) }),
                )
                Button(
                    onClick = { localNetwork(viewModel::connect) }, enabled = !state.busy && state.serverInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().then(ring),
                ) { if (state.busy) CircularProgressIndicator(Modifier.padding(2.dp)) else Text("Connect") }
                OutlinedButton(onClick = { localNetwork(viewModel::startPlex) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().then(ring)) { Text("Sign in with Plex") }
                // Servers already signed in to: back to one without typing it.
                if (servers.isNotEmpty()) {
                    Text("Your Servers", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                    servers.forEach { server ->
                        OutlinedButton(onClick = { localNetwork { viewModel.switchTo(server) } }, modifier = Modifier.fillMaxWidth().then(ring), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(server.titleAmong(servers), style = MaterialTheme.typography.bodyLarge)
                                Text("${server.session.kind.displayName} · ${server.session.userName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (server.asksPasswordToSwitch) Icon(Icons.Default.Lock, "Asks for password", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                Text(state.connectedServer!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.quickConnectCode != null) {
                    Text("Approve this code in Jellyfin", style = MaterialTheme.typography.titleMedium)
                    Text("Open any signed-in Jellyfin app, go to Settings ▸ Quick Connect and enter:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.quickConnectCode!!, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = viewModel::cancelQuickConnect, modifier = ring) { Text("Cancel") }
                } else {
                    Text("Sign in", style = MaterialTheme.typography.titleMedium)
                    if (state.users.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.users.forEach { user ->
                                AssistChip(onClick = { viewModel.setUsername(user.name.orEmpty()) }, label = { Text(user.name.orEmpty()) })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = state.username, onValueChange = viewModel::setUsername,
                        label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth().then(leaves),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    OutlinedTextField(
                        value = state.password, onValueChange = viewModel::setPassword,
                        label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth().then(leaves),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { viewModel.signIn() }),
                    )
                    // Plain http:// over the internet works, but the password goes in the clear: said before it's sent, as on the Apple apps.
                    if (com.astermediaplayer.astermediaplayer.data.ServerAddress.isUnencryptedRemote(state.connectedServer!!)) {
                        Row(
                            // Focusable, so a TV's remote can reach it to read.
                            Modifier.fillMaxWidth().focusable(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
                        ) {
                            Icon(Icons.Default.LockOpen, null, tint = UnencryptedOrange)
                            Text(
                                "This address isn't encrypted, so your password and what you watch travel over the internet where others could see them. If your server has an https:// address, use that instead.",
                                style = MaterialTheme.typography.bodyMedium, color = UnencryptedOrange,
                            )
                        }
                    }
                    Button(
                        // On a TV it stays enabled, so the remote can stop on it on the way to Quick Connect; with no name it says so.
                        onClick = viewModel::signIn, enabled = !state.busy && (tv || state.username.isNotBlank()),
                        modifier = Modifier.fillMaxWidth().then(ring),
                    ) { if (state.busy) CircularProgressIndicator(Modifier.padding(2.dp)) else Text("Sign in") }
                    if (state.quickConnectAvailable) {
                        OutlinedButton(onClick = viewModel::startQuickConnect, modifier = Modifier.fillMaxWidth().then(ring)) { Text("Use Quick Connect") }
                    }
                    TextButton(onClick = viewModel::changeServer, modifier = ring) { Text("Use a different server") }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    state.asking?.let { server ->
        ServerPasswordDialog(server, "Switch", title = server.titleAmong(servers), onCheck = { viewModel.switchWithPassword(server, it) }, onDismiss = viewModel::dismissAsking)
    }
}

/** The Apple apps' orange for a warning, which reads on the dark background. */
private val UnencryptedOrange = androidx.compose.ui.graphics.Color(0xFFFF9F0A)
