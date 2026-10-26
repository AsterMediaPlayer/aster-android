package com.astermediaplayer.astermediaplayer.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.SavedServer
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.UserDto
import com.astermediaplayer.astermediaplayer.data.plex.PlexResource

data class AuthState(
    val serverInput: String = "",
    val connectedServer: String? = null,
    val users: List<UserDto> = emptyList(),
    val username: String = "",
    val password: String = "",
    val quickConnectAvailable: Boolean = false,
    val quickConnectCode: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val signedIn: Boolean = false,
    /** A saved server that asks for its password before switching to it. */
    val asking: SavedServer? = null,
    /** The code to enter at plex.tv/link while signing in with Plex. */
    val plexCode: String? = null,
    /** The Plex account's servers, to pick from once the code is entered; null until then. */
    val plexServers: List<PlexResource>? = null,
)

class AuthViewModel(private val repo: AsterRepository, private val onServerChanged: () -> Unit = {}) : ViewModel() {
    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()
    private var quickConnectJob: Job? = null
    private var plexJob: Job? = null
    private var plexToken: String? = null

    /** The servers already signed in to: back to one without typing it. */
    val servers: StateFlow<List<SavedServer>> = repo.savedServers

    init {
        viewModelScope.launch {
            repo.lastServer()?.let { last -> _state.update { it.copy(serverInput = last) } }
        }
    }

    fun setServer(value: String) = _state.update { it.copy(serverInput = value, error = null) }
    fun setUsername(value: String) = _state.update { it.copy(username = value, error = null) }
    fun setPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun connect() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val server = repo.probeServer(_state.value.serverInput)
                val users = runCatching { repo.publicUsers() }.getOrDefault(emptyList())
                _state.update {
                    it.copy(
                        busy = false, connectedServer = server, users = users,
                        quickConnectAvailable = false,
                    )
                }
                val quick = repo.quickConnectAvailable()
                _state.update { it.copy(quickConnectAvailable = quick) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.userMessage()) }
            }
        }
    }

    /** Back to a saved server, asking for its password first where it's set to. */
    fun switchTo(server: SavedServer) {
        if (server.asksPasswordToSwitch) { _state.update { it.copy(asking = server) }; return }
        viewModelScope.launch {
            try {
                repo.switchTo(server)
                onServerChanged()
                _state.update { it.copy(signedIn = true) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage()) }
            }
        }
    }

    /** The password was right (the server says so, or this throws): switch with the fresh sign-in. */
    suspend fun switchWithPassword(server: SavedServer, password: String) {
        val fresh = repo.signInAgain(server, password)
        repo.switchTo(server, fresh)
        onServerChanged()
        _state.update { it.copy(asking = null, signedIn = true) }
    }

    fun dismissAsking() = _state.update { it.copy(asking = null) }

    fun changeServer() {
        quickConnectJob?.cancel()
        _state.update { it.copy(connectedServer = null, users = emptyList(), quickConnectCode = null, error = null) }
    }

    fun signIn() {
        val s = _state.value
        val server = s.connectedServer ?: return
        if (s.busy || s.username.isBlank()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                repo.signIn(server, s.username.trim(), s.password)
                onServerChanged()
                _state.update { it.copy(busy = false, signedIn = true) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = "Sign-in failed. Check the username and password.") }
            }
        }
    }

    /** Shows a code to approve from another signed-in Jellyfin client, then polls until it is approved. */
    fun startQuickConnect() {
        val server = _state.value.connectedServer ?: return
        quickConnectJob?.cancel()
        quickConnectJob = viewModelScope.launch {
            try {
                val initiated = repo.quickConnectInitiate()
                _state.update { it.copy(quickConnectCode = initiated.code, error = null) }
                while (true) {
                    delay(2_000)
                    if (repo.quickConnectState(initiated.secret).authenticated) break
                }
                repo.signInWithQuickConnect(server, initiated.secret)
                onServerChanged()
                _state.update { it.copy(signedIn = true, quickConnectCode = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(quickConnectCode = null, error = "Quick Connect failed: ${e.userMessage()}") }
            }
        }
    }

    /** Shows a code to enter at plex.tv/link, polls until it is, then lists the account's servers (or signs in to the only one). */
    fun startPlex() {
        plexJob?.cancel()
        plexJob = viewModelScope.launch {
            try {
                _state.update { it.copy(busy = true, error = null) }
                val pin = repo.plexStartLink()
                _state.update { it.copy(busy = false, plexCode = pin.code) }
                // The code lasts a while (15 minutes or so); asked about every couple of seconds until it's been entered.
                val deadline = System.currentTimeMillis() + (pin.expiresInSeconds ?: 900) * 1000L
                var token: String? = null
                while (token == null) {
                    if (System.currentTimeMillis() > deadline) throw IllegalStateException("The code ran out. Try again for a new one.")
                    delay(2_000)
                    token = runCatching { repo.plexLinkedToken(pin) }.getOrNull()
                }
                plexToken = token
                val servers = repo.plexServers(token)
                if (servers.isEmpty()) throw IllegalStateException("This Plex account has no servers.")
                if (servers.size == 1) choosePlexServer(servers.single())
                else _state.update { it.copy(plexCode = null, plexServers = servers) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, plexCode = null, plexServers = null, error = e.userMessage()) }
            }
        }
    }

    fun choosePlexServer(server: PlexResource) {
        val token = plexToken ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, plexCode = null, error = null) }
        plexJob = viewModelScope.launch {
            try {
                repo.signInWithPlex(server, token)
                onServerChanged()
                _state.update { it.copy(busy = false, plexServers = null, signedIn = true) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.userMessage()) }
            }
        }
    }

    fun cancelPlex() {
        plexJob?.cancel()
        plexToken = null
        _state.update { it.copy(busy = false, plexCode = null, plexServers = null, error = null) }
    }

    fun cancelQuickConnect() {
        quickConnectJob?.cancel()
        _state.update { it.copy(quickConnectCode = null) }
    }
}
