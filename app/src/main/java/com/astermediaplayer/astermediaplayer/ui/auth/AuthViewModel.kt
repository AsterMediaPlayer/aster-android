package com.astermediaplayer.astermediaplayer.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.UserDto

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
)

class AuthViewModel(private val repo: AsterRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()
    private var quickConnectJob: Job? = null

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
                _state.update { it.copy(signedIn = true, quickConnectCode = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(quickConnectCode = null, error = "Quick Connect failed: ${e.userMessage()}") }
            }
        }
    }

    fun cancelQuickConnect() {
        quickConnectJob?.cancel()
        _state.update { it.copy(quickConnectCode = null) }
    }
}
