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
    /** A Plex sign-in's plex.tv side: the account's token and the server's other addresses; null on Jellyfin and Emby. */
    val plex: com.astermediaplayer.astermediaplayer.data.plex.PlexLink? = null,
    /** The household (Bonfire profiles, a Plex Home) this sign-in belongs to, once known; null for an ordinary sign-in. */
    val household: com.astermediaplayer.astermediaplayer.data.profiles.StoredHousehold? = null,
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

    /**
     * Whether it can ask for anything: the user's password on Jellyfin and Emby, checked by the server; on Plex, the PIN of the Home
     * profile signed in as, checked by plex.tv. A Plex sign-in as a profile with no PIN (or before the Home was kept) has none to ask for.
     */
    val canBeLocked: Boolean get() = session.kind != com.astermediaplayer.astermediaplayer.data.emby.ServerKind.Plex || session.plex?.canAskPin == true

    /** What switching and Lock After ask for: "password", or a Plex Home profile's or a Bonfire profile's "PIN". */
    val credential: String get() = if (session.kind == com.astermediaplayer.astermediaplayer.data.emby.ServerKind.Plex || isHouseholdProfile) "PIN" else "password"

    /** Whether this is a household profile (not the main account), whose password on the server is its PIN. */
    val isHouseholdProfile: Boolean get() = session.household?.isMaster == false

    /** Whether a new Lock After lets more past the lock than the one set (never, or a longer wait): that asks for the password first. */
    fun loosens(lockAfter: Int?): Boolean {
        val current = lockAfterMinutes ?: return false
        return canBeLocked && (lockAfter == null || lockAfter > current)
    }

    /** Whether turning Ask for Password to Switch to [asksToSwitch] lets anyone switch without it: that asks for the password first. */
    fun loosens(asksToSwitch: Boolean): Boolean = canBeLocked && asksPasswordToSwitch && !asksToSwitch

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
    private val plexKey = stringPreferencesKey("plex_link")
    private val householdKey = stringPreferencesKey("household")
    private val leftAtKey = androidx.datastore.preferences.core.longPreferencesKey("left_at")

    suspend fun load(): Session? {
        val prefs = context.dataStore.data.first()
        val server = prefs[serverKey] ?: return null
        val token = prefs[tokenKey]?.let(SecretBox::open) ?: return null
        val userId = prefs[userIdKey] ?: return null
        val plex = prefs[plexKey]?.let(SecretBox::open)?.let { runCatching { com.astermediaplayer.astermediaplayer.data.plex.PlexLink.fromJson(JSONObject(it)) }.getOrNull() }
        return Session(server, token, userId, prefs[userNameKey].orEmpty(), ServerKind.of(prefs[kindKey]), prefs[nameKey], plex, household(prefs[householdKey]))
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
            session.plex?.let { link -> it[plexKey] = SecretBox.seal(link.toJson().toString()) } ?: it.remove(plexKey)
            session.household?.let { h -> it[householdKey] = h.toJson().toString() } ?: it.remove(householdKey)
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
                        o.optString("plex").ifEmpty { null }?.let(SecretBox::open)?.let { runCatching { com.astermediaplayer.astermediaplayer.data.plex.PlexLink.fromJson(JSONObject(it)) }.getOrNull() },
                        household(o.optString("household").ifEmpty { null }),
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
                    .put("asks", s.asksPasswordToSwitch).apply { s.lockAfterMinutes?.let { put("lock", it) }; s.session.plex?.let { put("plex", SecretBox.seal(it.toJson().toString())) }; s.session.household?.let { put("household", it.toJson().toString()) } },
            )
        }
        context.dataStore.edit { it[serversKey] = array.toString() }
    }

    private fun household(text: String?) =
        text?.let { runCatching { com.astermediaplayer.astermediaplayer.data.profiles.StoredHousehold.fromJson(JSONObject(it)) }.getOrNull() }

    /** When the app was left, for the lock; read again when it comes back. */
    suspend fun noteLeft(at: Long) { context.dataStore.edit { it[leftAtKey] = at } }
    suspend fun leftAt(): Long? = context.dataStore.data.first()[leftAtKey]
    suspend fun clearLeft() { context.dataStore.edit { it.remove(leftAtKey) } }
}
