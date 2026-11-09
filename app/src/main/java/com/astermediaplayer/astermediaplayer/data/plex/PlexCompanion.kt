package com.astermediaplayer.astermediaplayer.data.plex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Aster for Plex (aster-companion-plugins/AsterPlex): a container beside a Plex server that answers `/AsterTracking`
 * as the Jellyfin plugin does, since Plex takes no plugins. Its admin adds its address to Plex's Custom server access
 * URLs, so plex.tv lists it among the server's addresses, and the app finds it there with nothing to set up.
 *
 * plex.tv turns a plain `http://192.168.1.240:8107` into `https://192-168-1-240.<hash>.plex.direct:8107` when asked
 * for secure addresses, as the app asks; the container doesn't speak HTTPS there, so a plex.direct address is also
 * tried as plain http on its address and port.
 */
object PlexCompanion {
    /** The addresses to look for it at: the server's own, with plain http beside each plex.direct one, the home network's first. */
    fun candidates(connections: List<PlexConnection>): List<String> {
        val urls = mutableListOf<String>()
        for (c in connections.filterNot { it.relay }.sortedBy { it.rank }) {
            urls += c.uri.trimEnd('/')
            val host = runCatching { java.net.URI(c.uri).host }.getOrNull().orEmpty()
            if (host.endsWith(".plex.direct") && c.address != null && c.port != null) {
                val address = if (':' in c.address) "[${c.address}]" else c.address
                urls += "http://$address:${c.port}"
            }
        }
        return urls.distinct()
    }

    /**
     * The first address where the container answers `/AsterTracking/Features` for this token, the home network's
     * the moment it answers; null when none does. Plex itself answers 404 there. All are asked at once, as a server
     * can list a dozen addresses that mostly don't answer from here.
     */
    suspend fun find(candidates: List<String>, token: String, device: PlexDevice, http: OkHttpClient = PlexAccount.DEFAULT_HTTP, timeoutSeconds: Long = 8): String? {
        if (candidates.isEmpty()) return null
        val probe = http.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
        return coroutineScope {
            val results = Channel<Pair<Int, String?>>(candidates.size)
            val jobs = candidates.mapIndexed { index, url ->
                launch(Dispatchers.IO) { results.send(index to if (answers(probe, url, token, device)) url else null) }
            }
            var best: Pair<Int, String>? = null
            repeat(candidates.size) {
                val (index, found) = results.receive()
                if (found == null) return@repeat
                // The first address listed is the most local: take it at once; else the best of those that answer.
                if (index == 0) { jobs.forEach { it.cancel() }; return@coroutineScope found }
                if (best == null || index < best!!.first) best = index to found
            }
            best?.second
        }
    }

    private fun answers(http: OkHttpClient, url: String, token: String, device: PlexDevice): Boolean = try {
        val builder = Request.Builder().url("$url/AsterTracking/Features")
        device.headers(token).forEach { (k, v) -> builder.header(k, v) }
        http.newCall(builder.build()).execute().use { r -> r.code == 200 && r.body.string().contains("\"Services\"") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
