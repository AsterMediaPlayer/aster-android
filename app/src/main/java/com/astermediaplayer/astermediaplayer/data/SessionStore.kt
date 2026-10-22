package com.astermediaplayer.astermediaplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Session(
    val serverUrl: String,
    val accessToken: String,
    val userId: String,
    val userName: String,
)

private val Context.dataStore by preferencesDataStore("aster_session")

class SessionStore(private val context: Context) {
    private val serverKey = stringPreferencesKey("server")
    private val tokenKey = stringPreferencesKey("token")
    private val userIdKey = stringPreferencesKey("user_id")
    private val userNameKey = stringPreferencesKey("user_name")

    suspend fun load(): Session? {
        val prefs = context.dataStore.data.first()
        val server = prefs[serverKey] ?: return null
        val token = prefs[tokenKey]?.let(SecretBox::open) ?: return null
        val userId = prefs[userIdKey] ?: return null
        return Session(server, token, userId, prefs[userNameKey].orEmpty())
    }

    suspend fun lastServer(): String? = context.dataStore.data.map { it[serverKey] }.first()

    suspend fun save(session: Session) {
        context.dataStore.edit {
            it[serverKey] = session.serverUrl
            it[tokenKey] = SecretBox.seal(session.accessToken)
            it[userIdKey] = session.userId
            it[userNameKey] = session.userName
        }
    }

    /** Signing out keeps the server address so the sign-in screen can prefill it. */
    suspend fun clearCredentials() {
        context.dataStore.edit {
            it.remove(tokenKey)
            it.remove(userIdKey)
            it.remove(userNameKey)
        }
    }
}
