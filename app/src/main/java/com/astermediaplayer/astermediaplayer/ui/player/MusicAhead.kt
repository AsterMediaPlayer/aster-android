package com.astermediaplayer.astermediaplayer.ui.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Saves the next few songs in the music queue as whole files (the Apple apps' `MusicAhead`): on Wi-Fi or a wired connection,
 * or on cellular data too when that's chosen in Settings ▸ Playback ▸ Music, and never with Data Saver on. A saved song starts
 * at once and plays on through a spot with no signal; one not yet saved streams as before.
 *
 * The files live in the app's cache folder, which the system may empty when space is short. Only the song playing and the
 * ones coming up are kept: what's been heard is let go as the queue moves on. Called on the main thread; [savedFile] may be
 * asked from the player's loading thread.
 */
class MusicAhead(
    private val folder: File,
    /** Whether the connection now is one songs may be saved on. Asked before each song and as it comes in. */
    private val mayFetch: () -> Boolean,
    /** Saves one song into the file given; false when it can't be had (or isn't a song). */
    private val fetch: suspend (id: String, into: File, stillAllowed: () -> Boolean) -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Saved songs by item id (plain, as the queue's ids print). */
    private val saved = ConcurrentHashMap<String, File>()
    /** The songs wanted, in the order they'll play. */
    private var wanted = emptyList<String>()
    /** Songs that couldn't be saved, or whose file didn't play; they stream this time. */
    private val failed = mutableSetOf<String>()
    private var job: Job? = null
    private var fetching: String? = null

    init {
        folder.mkdirs()
        // What's still there from last time, named by item id; a half-saved one is let go.
        folder.listFiles()?.forEach { f -> if (f.name.endsWith(PART)) f.delete() else saved[f.name] = f }
    }

    /** The saved file for a song, if it's here. */
    fun savedFile(id: String): File? {
        val file = saved[id] ?: return null
        if (!file.exists()) { saved.remove(id); return null } // The system emptied the cache.
        return file
    }

    /** The songs coming up, in order: saves those not yet saved, one at a time, and lets go of every other file but the one playing. */
    fun prepare(upcoming: List<String>, playing: String?) {
        wanted = upcoming.take(SONGS_AHEAD)
        val keep = wanted.toSet() + listOfNotNull(playing)
        for ((id, file) in saved) if (id !in keep && id != fetching) { file.delete(); saved.remove(id) }
        fetchNext()
    }

    /** Stops the download under way (it's started again later), so a song starting to play has the connection to itself. */
    fun hold() {
        job?.cancel()
        job = null
        fetching = null
    }

    /** Lets go of a song's saved file that didn't play, and doesn't save it again this time: it streams. */
    fun forget(id: String) {
        failed += id
        saved.remove(id)?.delete()
    }

    /** Removes every saved song, as the setting goes off or playback stops. */
    fun clear() {
        hold()
        wanted = emptyList()
        failed.clear()
        for (file in saved.values) file.delete()
        saved.clear()
        folder.listFiles()?.forEach { it.delete() }
    }

    private fun fetchNext() {
        if (job?.isActive == true) return
        val id = wanted.firstOrNull { saved[it] == null && it !in failed } ?: return
        if (!mayFetch()) return
        fetching = id
        // Named afresh each time, so one cancelled and still closing never meets the next.
        val part = File(folder, "$id.${System.nanoTime()}$PART")
        job = scope.launch {
            val ok = withContext(io) {
                runCatching { fetch(id, part, mayFetch) }.getOrElse { false }.also { currentCoroutineContext().ensureActive() }
            }
            job = null
            fetching = null
            val file = File(folder, id)
            when {
                // The queue moved on while it came.
                ok && id !in wanted -> part.delete()
                ok && part.renameTo(file) -> saved[id] = file
                else -> { part.delete(); failed += id; DebugLog.note("music", "A song couldn't be saved ahead; it streams instead") }
            }
            fetchNext()
        }.also { j -> j.invokeOnCompletion { if (it != null) part.delete() } }
    }

    companion object {
        /** How many songs past the one playing are saved. */
        const val SONGS_AHEAD = 5
        private const val PART = ".part"

        /** The song a stream address is for: `…/Audio/{id}/stream…`, the id plain. */
        fun songId(uri: Uri?): String? = songId(uri?.path)

        fun songId(path: String?): String? {
            val parts = path?.split('/') ?: return null
            val at = parts.indexOfFirst { it.equals("Audio", ignoreCase = true) }
            return parts.getOrNull(at + 1)?.takeIf { at >= 0 && it.isNotEmpty() }?.replace("-", "")?.lowercase()
        }

        /** Whether songs may be saved on this connection: one that isn't metered, or cellular when that's chosen; never in Data Saver. */
        fun allowed(unmetered: Boolean, cellularChosen: Boolean, dataSaver: Boolean): Boolean = !dataSaver && (unmetered || cellularChosen)

        /** [allowed], as the system reports the connection now. */
        fun connectionAllows(context: Context, cellularChosen: Boolean): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
            val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
            val dataSaver = cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
            return allowed(unmetered, cellularChosen, dataSaver)
        }

        /**
         * Reads a song from the server into [into] through [source] (the players' own sign-in and redirect rules). It stops
         * when the connection stops being one it may use; false for that, a failure, or something that isn't a song.
         */
        @OptIn(UnstableApi::class)
        suspend fun download(source: DataSource, url: String, into: File, stillAllowed: () -> Boolean): Boolean = runInterruptible {
            try {
                source.open(DataSpec.Builder().setUri(url).build())
                val type = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()?.lowercase()
                if (type != null && !type.startsWith("audio/") && !type.startsWith("application/octet-stream") && !type.startsWith("video/")) return@runInterruptible false
                val buffer = ByteArray(64 * 1024)
                var sinceCheck = 0L
                into.outputStream().use { out ->
                    while (true) {
                        val n = source.read(buffer, 0, buffer.size)
                        if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) break
                        out.write(buffer, 0, n)
                        sinceCheck += n
                        if (sinceCheck >= CHECK_EVERY) { sinceCheck = 0; if (!stillAllowed()) return@runInterruptible false }
                        if (Thread.interrupted()) throw InterruptedException()
                    }
                }
                into.length() > 0
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                false
            } finally {
                runCatching { source.close() }
            }
        }

        /** How often, in bytes, a download checks it may still use the connection (Wi-Fi gone, say, with cellular not chosen). */
        private const val CHECK_EVERY = 1L * 1024 * 1024
    }
}
