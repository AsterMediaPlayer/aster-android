package com.astermediaplayer.astermediaplayer.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Aster's companion plugins for Jellyfin: which are on the server and, for an administrator, installing them and turning them on or off. */
enum class AsterPlugin(
    val guid: String,
    /** Its name in the repository, which installing asks for. */
    val packageName: String,
    val headline: String,
    val summary: String,
    /** The oldest server it runs on, until the repository says. */
    val minimumServer: String,
    /** A route that answers whenever the plugin is running, so a user who can't list plugins can tell it's there. */
    val probePath: String,
) {
    Channels(
        "5b489227-2fc4-4dce-b7f8-6d0e3b2082ee", "Aster Channels", "Live TV, sorted",
        "Group your Live TV channels into sports, news, kids and more, in the guide and as you flip. Then rename, renumber and rearrange Aster's channels right from Jellyfin's dashboard.",
        "12.1", "/AsterChannels/web/channels.js",
    ),
    Disc(
        "913b9d44-cafe-4fe7-ae0b-f1362dc12cc7", "Aster Disc", "Real disc menus",
        "Explore a film's extras through a menu just like the one on the disc, with moving backgrounds and music. Pick from a community catalogue, or design your own.",
        "12.0", "/DiscMenus/web/discmenus.js",
    ),
    OpEd(
        "d459e0a2-e073-4e1e-b0ec-71883075cf4a", "Aster OP-ED", "Every anime opening and ending",
        "Watch a show's openings and endings on their own, play them back to back, or have each series greet you with its theme song. The song and artist come along too.",
        "12.0", "/OpEd/Library",
    );

    companion object {
        const val REPOSITORY_URL = "https://raw.githubusercontent.com/AsterMediaPlayer/aster-companion-plugins/main/manifest.json"
        const val REPOSITORY_NAME = "Aster Companion Plugins"
    }
}

/** A plugin on the server's list. */
data class InstalledPlugin(val id: String, val version: String, val status: String?)

/** Where a plugin stands on the server. */
sealed interface PluginState {
    data object Unknown : PluginState
    data object NotInstalled : PluginState
    data object Installing : PluginState
    /** Changed: on or off after the server restarts. */
    data class AfterRestart(val on: Boolean) : PluginState
    data object Active : PluginState
    data object Disabled : PluginState
    /** No version of it runs on this server: it needs this one or later. */
    data class NeedsNewerServer(val version: String) : PluginState
    data object Broken : PluginState
    /** For someone who isn't an administrator: whether it's running. */
    data class Present(val present: Boolean) : PluginState

    val isOn: Boolean get() = when (this) {
        Active, Installing -> true
        is AfterRestart -> on
        is Present -> present
        else -> false
    }

    /** Whether an administrator's switch can change it now. */
    val canSwitch: Boolean get() = when (this) {
        NotInstalled, Active, Disabled, is AfterRestart -> true
        else -> false
    }

    fun caption(serverName: String): String = when (this) {
        Unknown -> ""
        NotInstalled -> "Not installed yet"
        Installing -> "Installing…"
        is AfterRestart -> if (on) "Ready after a quick server restart" else "Turns off after a server restart"
        Active -> "On"
        Disabled -> "Off"
        is NeedsNewerServer -> "Needs $serverName $version or newer"
        Broken -> "$serverName couldn't start it. Check Plugins in your dashboard."
        is Present -> if (present) "On your server" else "Not on your server yet"
    }

    companion object {
        /** What the server's plugin list, its catalogue and its version say. */
        fun from(installed: List<InstalledPlugin>, packages: List<Pair<String, List<String?>>>?, serverVersion: String?, plugin: AsterPlugin): PluginState {
            val guid = plain(plugin.guid)
            // A replaced version stays listed as superseded beside its successor.
            val current = current(installed, plugin)
            if (current != null) return when (current.status) {
                "Active", null -> Active
                "Restart" -> AfterRestart(true)
                "Disabled" -> Disabled
                "Deleted" -> AfterRestart(false)
                else -> Broken
            }
            // Not installed: can it be, on this server?
            if (serverVersion == null) return NotInstalled
            val abis = packages?.firstOrNull { plain(it.first) == guid }?.second.orEmpty().filterNotNull()
            val oldestNeeded = abis.minWithOrNull { a, b -> compare(a, b) } ?: plugin.minimumServer
            return if (compare(serverVersion, oldestNeeded) < 0) NeedsNewerServer(shortVersion(oldestNeeded)) else NotInstalled
        }

        fun current(installed: List<InstalledPlugin>, plugin: AsterPlugin): InstalledPlugin? =
            installed.filter { plain(it.id) == plain(plugin.guid) && it.status != "Superceded" && it.status != "Superseded" }
                .maxWithOrNull { a, b -> compare(a.version, b.version) }

        /** The server writes GUIDs without dashes; the repository with them. */
        fun plain(guid: String) = guid.replace("-", "").lowercase()

        /** "12.1.0" < "12.1.0.1": by number, missing parts as 0. */
        fun compare(a: String, b: String): Int {
            val left = a.split('.').map { it.toIntOrNull() ?: 0 }
            val right = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(left.size, right.size)) {
                val l = left.getOrElse(i) { 0 }
                val r = right.getOrElse(i) { 0 }
                if (l != r) return l.compareTo(r)
            }
            return 0
        }

        /** "12.1.0.0" as "12.1". */
        fun shortVersion(version: String): String {
            val parts = version.split('.').toMutableList()
            while (parts.size > 2 && parts.last() == "0") parts.removeAt(parts.lastIndex)
            return parts.joinToString(".")
        }
    }
}

/**
 * The companion plugins on the signed-in server, for Settings ▸ Server Plugins: for an administrator, switches that install
 * and turn them on or off (either takes a server restart, which the app can ask for); for anyone else, what's there.
 * The Kotlin counterpart of the Apple apps' `ServerPluginsModel`.
 */
class ServerPluginsModel(private val repo: AsterRepository) {
    sealed interface Restart {
        data object Idle : Restart
        data object Restarting : Restart
        data class Failed(val message: String) : Restart
    }

    var states by mutableStateOf<Map<AsterPlugin, PluginState>>(emptyMap())
        private set
    /** Whether the user is an administrator; null until known. */
    var isAdministrator by mutableStateOf<Boolean?>(null)
        private set
    /** A change waits on a restart. */
    var needsRestart by mutableStateOf(false)
        private set
    /** False where something else must restart the server (Docker, say). */
    var canRestart by mutableStateOf(true)
        private set
    var restart by mutableStateOf<Restart>(Restart.Idle)
        private set
    var errorMessage by mutableStateOf<String?>(null)

    private var installed = emptyList<InstalledPlugin>()
    private var packages: List<Pair<String, List<String?>>>? = null
    private var serverVersion: String? = null
    /** Changes made here since the last restart, which the server's list doesn't always show yet. */
    private val pending = mutableMapOf<AsterPlugin, Boolean>()

    fun state(plugin: AsterPlugin): PluginState = states[plugin] ?: PluginState.Unknown

    suspend fun load() {
        val admin = repo.isAdministrator()
        isAdministrator = admin
        if (admin) {
            installed = installedPlugins() ?: emptyList()
            val info = runCatching { JSONObject(repo.serverRequest("GET", "/System/Info")) }.getOrNull()
            packages = runCatching {
                JSONArray(repo.serverRequest("GET", "/Packages")).let { a ->
                    List(a.length()) { i ->
                        val p = a.getJSONObject(i)
                        val versions = p.optJSONArray("versions")
                        p.optString("guid") to (if (versions == null) emptyList() else List(versions.length()) { v -> versions.getJSONObject(v).optString("targetAbi").ifEmpty { null } })
                    }
                }
            }.getOrNull()
            serverVersion = info?.optString("Version")?.ifEmpty { null }
            canRestart = info?.optBoolean("CanSelfRestart", true) ?: true
            needsRestart = needsRestart || info?.optBoolean("HasPendingRestart", false) == true
            updateStates()
        } else {
            states = AsterPlugin.entries.associateWith { repo.serverHas(it.probePath)?.let(PluginState::Present) ?: PluginState.Unknown }
        }
    }

    private suspend fun installedPlugins(): List<InstalledPlugin>? = runCatching {
        JSONArray(repo.serverRequest("GET", "/Plugins")).let { a ->
            List(a.length()) { i -> a.getJSONObject(i).let { InstalledPlugin(it.optString("Id"), it.optString("Version"), it.optString("Status").ifEmpty { null }) } }
        }
    }.getOrNull()

    /** An administrator's switch: installs, turns on or turns off. */
    suspend fun set(plugin: AsterPlugin, on: Boolean) {
        val state = state(plugin)
        errorMessage = null
        try {
            when {
                state == PluginState.NotInstalled && on -> {
                    states = states + (plugin to PluginState.Installing)
                    addRepository()
                    repo.serverRequest(
                        "POST",
                        "/Packages/Installed/${android.net.Uri.encode(plugin.packageName)}?assemblyGuid=${plugin.guid}&repositoryUrl=${android.net.Uri.encode(AsterPlugin.REPOSITORY_URL)}",
                    )
                }
                (state == PluginState.Disabled && on) || (state == PluginState.AfterRestart(false) && on) ||
                    (state == PluginState.Active && !on) || (state == PluginState.AfterRestart(true) && !on) -> {
                    val current = PluginState.current(installed, plugin)
                    if (current == null) {
                        // Installed here and not listed yet: the server can't change it until it restarts.
                        errorMessage = "Restart the server first, then turn ${plugin.packageName} off."
                        return
                    }
                    repo.serverRequest("POST", "/Plugins/${current.id}/${current.version}/${if (on) "Enable" else "Disable"}")
                }
                else -> return
            }
            pending[plugin] = on
            needsRestart = true
        } catch (e: Exception) {
            errorMessage = if (on) "Couldn't install ${plugin.packageName}: ${e.message}" else "Couldn't turn ${plugin.packageName} off: ${e.message}"
        }
        installed = installedPlugins() ?: installed
        updateStates()
    }

    /** Restarts the server and waits for it to come back, then looks again. */
    suspend fun restartServer() {
        restart = Restart.Restarting
        try {
            repo.serverRequest("POST", "/System/Restart")
        } catch (e: Exception) {
            restart = Restart.Failed("The server wouldn't restart: ${e.message}")
            return
        }
        // It takes a moment to go; then it's back once it answers again.
        val started = System.currentTimeMillis()
        var wentDown = false
        while (System.currentTimeMillis() - started < 180_000) {
            delay(2_000)
            val answers = repo.serverHas("/System/Info/Public") == true
            if (!answers) wentDown = true
            if (answers && (wentDown || System.currentTimeMillis() - started > 20_000)) {
                pending.clear()
                needsRestart = false
                restart = Restart.Idle
                load()
                return
            }
        }
        restart = Restart.Failed("The server hasn't come back after three minutes. Check on it, then open this page again.")
    }

    /** Aster's repository in the server's list, turned on, keeping the rest. */
    private suspend fun addRepository() {
        val list = JSONArray(repo.serverRequest("GET", "/Repositories"))
        var found = -1
        for (i in 0 until list.length()) if (list.getJSONObject(i).optString("Url").equals(AsterPlugin.REPOSITORY_URL, ignoreCase = true)) found = i
        if (found >= 0) {
            val entry = list.getJSONObject(found)
            if (entry.optBoolean("Enabled", true)) return
            entry.put("Enabled", true)
        } else {
            list.put(JSONObject().put("Name", AsterPlugin.REPOSITORY_NAME).put("Url", AsterPlugin.REPOSITORY_URL).put("Enabled", true))
        }
        repo.serverRequest("POST", "/Repositories", list.toString())
    }

    private fun updateStates() {
        states = AsterPlugin.entries.associateWith { plugin ->
            pending[plugin]?.let { PluginState.AfterRestart(it) } ?: PluginState.from(installed, packages, serverVersion, plugin)
        }
    }
}
