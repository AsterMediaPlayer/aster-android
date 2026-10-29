package com.astermediaplayer.astermediaplayer.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
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
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedIn) { if (state.signedIn) onSignedIn() }
    // Back steps back a stage, as on the Apple apps: out of Quick Connect, then to the server screen, rather than leaving the app.
    androidx.activity.compose.BackHandler(enabled = state.connectedServer != null || state.plexCode != null || state.plexServers != null) {
        when {
            state.plexCode != null || state.plexServers != null -> viewModel.cancelPlex()
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
                TextButton(onClick = viewModel::cancelPlex) { Text("Cancel") }
            } else if (state.plexServers != null) {
                Text("Choose a Plex server", style = MaterialTheme.typography.titleMedium)
                state.plexServers!!.forEach { server ->
                    OutlinedButton(onClick = { viewModel.choosePlexServer(server) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(server.name, style = MaterialTheme.typography.bodyLarge)
                            Text(if (server.owned) "Yours" else "Shared with you", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (state.busy) CircularProgressIndicator(Modifier.padding(2.dp))
                TextButton(onClick = viewModel::cancelPlex) { Text("Cancel") }
            } else if (state.connectedServer == null) {
                Text("Connect to your Jellyfin or Emby server, or sign in with Plex", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.serverInput, onValueChange = viewModel::setServer,
                    label = { Text("Server address") }, placeholder = { Text("jellyfin.example.com:8096") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { viewModel.connect() }),
                )
                Button(
                    onClick = viewModel::connect, enabled = !state.busy && state.serverInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { if (state.busy) CircularProgressIndicator(Modifier.padding(2.dp)) else Text("Connect") }
                OutlinedButton(onClick = viewModel::startPlex, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Plex") }
                // Servers already signed in to: back to one without typing it.
                if (servers.isNotEmpty()) {
                    Text("Your Servers", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                    servers.forEach { server ->
                        OutlinedButton(onClick = { viewModel.switchTo(server) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
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
                    TextButton(onClick = viewModel::cancelQuickConnect) { Text("Cancel") }
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
                        label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    OutlinedTextField(
                        value = state.password, onValueChange = viewModel::setPassword,
                        label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { viewModel.signIn() }),
                    )
                    Button(
                        onClick = viewModel::signIn, enabled = !state.busy && state.username.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { if (state.busy) CircularProgressIndicator(Modifier.padding(2.dp)) else Text("Sign in") }
                    if (state.quickConnectAvailable) {
                        OutlinedButton(onClick = viewModel::startQuickConnect, modifier = Modifier.fillMaxWidth()) { Text("Use Quick Connect") }
                    }
                    TextButton(onClick = viewModel::changeServer) { Text("Use a different server") }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    state.asking?.let { server ->
        ServerPasswordDialog(server, "Switch", title = server.titleAmong(servers), onCheck = { viewModel.switchWithPassword(server, it) }, onDismiss = viewModel::dismissAsking)
    }
}
