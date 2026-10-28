package com.astermediaplayer.astermediaplayer.data

import android.util.Log
import com.astermediaplayer.astermediaplayer.data.emby.ServerKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Settings that follow the Jellyfin (or Emby) user from device to device, kept on their server in Aster's own display
 * preferences, the same record the Apple apps use. These are matters of taste: Up Next, skipping intros, styled
 * subtitles, disc menus, theme music and videos. Settings tied to the device stay on it.
 *
 * The server's values are fetched when the app opens or comes back to the foreground and when Settings opens. A change
 * to a synced setting is saved a moment after it's made; the last change wins. A setting the server doesn't have yet is
 * sent up from the first device that has set it, and a device that never set one keeps the default rather than pushing
 * it over another device's choice. The Kotlin counterpart of the Apple apps' `SettingsSync`.
 */
class SettingsSync(private val repo: AsterRepository, private val settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    /** What the server holds for the signed-in user, as last fetched or saved, so a value just fetched isn't sent straight back. */
    private var serverValues = mutableMapOf<String, String>()
    /** Who [serverValues] belongs to; nothing is saved before they've been fetched, so a device opening with old values can't overwrite newer ones. */
    private var owner: String? = null
    private var hasPulled = false
    private var watching: Job? = null

    /** Takes the server's values, and sends up any it hasn't got. */
    suspend fun pull() {
        lock.withLock { pullLocked() }
    }

    private suspend fun pullLocked() {
        val id = repo.currentServerId ?: return
        if (owner != id) { owner = id; serverValues = mutableMapOf(); hasPulled = false }
        // Plex has no display preferences to keep them in, so there they stay on the device, as on the Apple apps.
        if (repo.serverKind == ServerKind.Plex) return
        watch()
        val remote = runCatching { fetch() }.onFailure { Log.w("Aster", "Settings sync: couldn't read the server's settings: $it") }.getOrNull() ?: return
        val local = settings.syncedValues.first()
        val missing = mutableMapOf<String, String>()
        val taken = mutableMapOf<String, String>()
        for ((name, value) in remote) if (value == "true" || value == "false") { taken[name] = value; serverValues[name] = value }
        for ((name, value) in local) if (name !in taken) missing[name] = value
        // Noted before the change lands, so the save that follows the change sees nothing new.
        if (taken.isNotEmpty()) settings.applySynced(taken)
        hasPulled = true
        if (missing.isNotEmpty()) save(missing)
    }

    /** Watches the settings, and a second after one changes saves those that differ from the server's. */
    private fun watch() {
        if (watching?.isActive == true) return
        watching = scope.launch {
            settings.syncedValues.collectLatest { values ->
                delay(1_000)
                lock.withLock {
                    if (!hasPulled) return@withLock
                    val changed = values.filter { (name, value) -> serverValues[name] != value }
                    if (changed.isNotEmpty()) save(changed)
                }
            }
        }
    }

    /** The user's Aster record: its custom preferences by name. */
    private suspend fun record(): JSONObject =
        JSONObject(repo.serverRequest("GET", path()))

    private suspend fun fetch(): Map<String, String> {
        val prefs = record().optJSONObject("CustomPrefs") ?: return emptyMap()
        return buildMap { for (key in prefs.keys()) if (key.lowercase() !in ACCOUNT_WIDE) put(key, prefs.optString(key)) }
    }

    /** Saves settings on the server, keeping the others already there. */
    private suspend fun save(values: Map<String, String>) {
        try {
            val record = record()
            val prefs = record.optJSONObject("CustomPrefs") ?: JSONObject().also { record.put("CustomPrefs", it) }
            // The account-wide web settings the server mixes into every record come back as they were, never changed.
            for ((name, value) in values) prefs.put(name, value)
            repo.serverRequest("POST", path(), record.toString())
            serverValues.putAll(values)
        } catch (e: Exception) {
            Log.w("Aster", "Settings sync: saving ${values.keys.sorted()} failed: $e")
        }
    }

    private fun path() = "/DisplayPreferences/$RECORD?userId=${repo.userIdForServer}&client=$CLIENT"

    private companion object {
        const val RECORD = "asterfin"
        const val CLIENT = "AsterFIN"
        /** Account-wide web settings the server mixes into every record, and saves to the account from any record posted. */
        val ACCOUNT_WIDE = setOf("chromecastversion", "skipforwardlength", "skipbacklength", "enablenextvideoinfooverlay", "tvhome", "dashboardtheme")
    }
}
