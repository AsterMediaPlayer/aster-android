package com.astermediaplayer.astermediaplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.astermediaplayer.astermediaplayer.data.emby.ServerKind
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Session(
    val serverUrl: String,
    val accessToken: String,
    val userId: String,
    val userName: String,
    /** Jellyfin or Emby, found when the server was reached. */
    val kind: ServerKind = ServerKind.Jellyfin,
    /** What the server calls itself, for the list of servers. */
    val serverName: String? = null,
)

/**
 * A server signed in to and kept, to switch back to (the Apple apps' `SavedServer`): one per server and user, with how it
 * guards switching to it and the app being left.
 */
data class SavedServer(
    val session: Session,
    /** Switching to it asks for the user's own password first, checked by the server. */
    val asksPasswordToSwitch: Boolean = false,
    /** Minutes away from the app after which it asks for the password to carry on with this server; null for never. */
    val lockAfterMinutes: Int? = null,
) {
    val id: String get() = id(session.serverUrl, session.userId)
    val title: String get() = session.serverName ?: session.serverUrl.substringAfter("://").substringBefore("/")

    /** The name, with the address after it when another saved server calls itself the same (two servers named "Server"). */
    fun titleAmong(all: List<SavedServer>): String =
        if (all.count { it.title == title } > 1) "$title (${session.serverUrl.substringAfter("://").substringBefore("/")})" else title

    companion object {
        /** The lock-after choices offered, in minutes. */
        val LOCK_CHOICES = listOf(5, 15, 30, 60, 240)
        fun id(serverUrl: String, userId: String) = "$serverUrl|$userId"
        fun lockTitle(minutes: Int?) = when {
            minutes == null || minutes <= 0 -> "Never"
            minutes < 60 -> "$minutes Minutes"
            minutes == 60 -> "1 Hour"
            else -> "${minutes / 60} Hours"
        }
    }
}

private val Context.dataStore by preferencesDataStore("aster_session")

class SessionStore(private val context: Context) {
    private val serverKey = stringPreferencesKey("server")
    private val tokenKey = stringPreferencesKey("token")
    private val userIdKey = stringPreferencesKey("user_id")
    private val userNameKey = stringPreferencesKey("user_name")
    private val kindKey = stringPreferencesKey("server_kind")
    private val nameKey = stringPreferencesKey("server_name")
    private val serversKey = stringPreferencesKey("servers")
    private val leftAtKey = androidx.datastore.preferences.core.longPreferencesKey("left_at")

    suspend fun load(): Session? {
        val prefs = context.dataStore.data.first()
        val server = prefs[serverKey] ?: return null
        val token = prefs[tokenKey]?.let(SecretBox::open) ?: return null
        val userId = prefs[userIdKey] ?: return null
        return Session(server, token, userId, prefs[userNameKey].orEmpty(), ServerKind.of(prefs[kindKey]), prefs[nameKey])
    }

    suspend fun lastServer(): String? = context.dataStore.data.map { it[serverKey] }.first()

    suspend fun save(session: Session) {
        context.dataStore.edit {
            it[serverKey] = session.serverUrl
            it[tokenKey] = SecretBox.seal(session.accessToken)
            it[userIdKey] = session.userId
            it[userNameKey] = session.userName
            it[kindKey] = session.kind.raw
            session.serverName?.let { name -> it[nameKey] = name } ?: it.remove(nameKey)
        }
    }

    /** Signing out keeps the server address so the sign-in screen can prefill it, and the saved servers. */
    suspend fun clearCredentials() {
        context.dataStore.edit {
            it.remove(tokenKey)
            it.remove(userIdKey)
            it.remove(userNameKey)
        }
    }

    /** The servers signed in to, each with its sign-in sealed. */
    suspend fun loadServers(): List<SavedServer> {
        val text = context.dataStore.data.first()[serversKey] ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val token = SecretBox.open(o.getString("token")) ?: return@mapNotNull null
                SavedServer(
                    Session(
                        o.getString("server"), token, o.getString("userId"), o.optString("userName"), ServerKind.of(o.optString("kind")),
                        o.optString("name").ifEmpty { null },
                    ),
                    asksPasswordToSwitch = o.optBoolean("asks"),
                    lockAfterMinutes = if (o.has("lock")) o.getInt("lock") else null,
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun saveServers(servers: List<SavedServer>) {
        val array = JSONArray()
        servers.forEach { s ->
            array.put(
                JSONObject().put("server", s.session.serverUrl).put("token", SecretBox.seal(s.session.accessToken)).put("userId", s.session.userId)
                    .put("userName", s.session.userName).put("kind", s.session.kind.raw).put("name", s.session.serverName.orEmpty())
                    .put("asks", s.asksPasswordToSwitch).apply { s.lockAfterMinutes?.let { put("lock", it) } },
            )
        }
        context.dataStore.edit { it[serversKey] = array.toString() }
    }

    /** When the app was left, for the lock; read again when it comes back. */
    suspend fun noteLeft(at: Long) { context.dataStore.edit { it[leftAtKey] = at } }
    suspend fun leftAt(): Long? = context.dataStore.data.first()[leftAtKey]
    suspend fun clearLeft() { context.dataStore.edit { it.remove(leftAtKey) } }
}
