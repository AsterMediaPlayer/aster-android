package com.astermediaplayer.astermediaplayer.ui.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File

/**
 * Keeps the next stretch of an audiobook on the device as it plays, so a place with no signal doesn't stop it. As it's
 * heard, the next N minutes (Settings ▸ Playback ▸ Listen Ahead, 30 unless changed) are saved to a cache of their own,
 * running on into the book's next file, and the player reads the book through that cache: saved bytes first, the network
 * for the rest. It works by position in a file, so a one-file ten-hour book is covered as well as one in chapter files.
 * The Kotlin counterpart of the Apple apps' `ListenAhead`. The cache is in the app's cache folder, which the system may
 * empty when space is short; it's capped, the book's part of it is let go when the book ends or another starts, and a
 * downloaded book never comes here.
 */
@OptIn(UnstableApi::class)
class ListenAhead(context: Context, private val upstream: () -> DataSource) {
    private val cache = SimpleCache(File(context.cacheDir, "listen-ahead"), LeastRecentlyUsedCacheEvictor(MAX_BYTES), StandaloneDatabaseProvider(context))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * What a player builds on: what's been saved first, the server for the rest. It only reads the cache: a player's own read
     * of a file holds the rest of that file locked for writing, which would leave the saving below nothing to write into.
     */
    val dataSourceFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache).setUpstreamDataSourceFactory { upstream() }.setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** A file of the book being listened to: where it's read from, and how long it plays. */
    class Part(val url: String, val seconds: Double)

    private var book: String? = null
    private var urls = emptyList<String>()
    /** Where the listener is, as last told; a pass saves from here. */
    private var wanted = Triple(0, 0.0, 0)
    /** Where the running pass started. */
    private var running: Pair<Int, Double>? = null
    private var parts = emptyList<Part>()
    /** When the last pass ended, and whether it got all the way through (it can't offline). */
    private var endedAt = 0L
    private var complete = false

    /**
     * The listener is at [seconds] into [parts]`[part]`, and wants the next [minutes] saved. Called as the position moves. A pass
     * in progress is left to finish (cancelling a slow download would throw its work away), and the next starts from where the
     * listener has got to; a seek, or a jump to another file, restarts it at once.
     */
    fun update(bookId: String, parts: List<Part>, part: Int, seconds: Double, minutes: Int) {
        if (minutes <= 0) { release(); return }
        if (book != bookId) { release(); book = bookId; urls = parts.map { it.url } }
        this.parts = parts
        wanted = Triple(part, seconds, minutes)
        val active = job?.isActive == true
        val from = running
        val sought = from != null && (part != from.first || seconds < from.second - 5 || seconds > from.second + minutes * 60.0)
        if (active && !sought) return
        // A finished pass has the stretch covered until the listener has gone on a while; one that couldn't finish is tried again soon.
        val settled = from != null && !sought && (if (complete) seconds < from.second + REFILL_SECONDS else System.currentTimeMillis() - endedAt < RETRY_MS)
        if (settled) return
        launch()
    }

    private fun launch() {
        val (part, seconds, minutes) = wanted
        running = part to seconds
        job?.cancel()
        job = scope.launch {
            complete = false
            complete = fill(parts, part, seconds, minutes)
            endedAt = System.currentTimeMillis()
            // The listener has moved on while that ran: another pass from where they are now.
            val (p, s, _) = wanted
            if (currentCoroutineContext().isActive && complete && (p != part || s > seconds + REFILL_SECONDS)) launch()
        }
    }

    /** Saves the stretch from the listener's place on, [minutes] long, across files. */
    private suspend fun fill(parts: List<Part>, from: Int, startSeconds: Double, minutes: Int): Boolean {
        var room = minutes * 60.0
        var part = from
        var at = startSeconds
        // The bytes a second of this book takes, learned from the first file read; the files that follow are sized by it.
        var bytesPerSecond = 0.0
        while (room > 0 && part < parts.size) {
            currentCoroutineContext().ensureActive()
            val p = parts[part]
            val length = contentLength(p.url)
            if (length != null && p.seconds > 0) bytesPerSecond = length / p.seconds
            val total = length ?: if (bytesPerSecond > 0) (bytesPerSecond * p.seconds).toLong() else { Log.i("Aster", "Listen ahead: file ${part + 1}'s size isn't known yet"); return false }
            val take = minOf(room, p.seconds - at)
            if (take > 0) {
                val start = (total * (at / p.seconds)).toLong().coerceIn(0, total)
                val end = (total * ((at + take) / p.seconds)).toLong().coerceIn(start, total)
                Log.i("Aster", "Listen ahead: file ${part + 1}, ${at.toInt()}s +${take.toInt()}s, bytes $start..$end of $total")
                if (end > start) save(p.url, start, end - start) ?: return false
            }
            room -= p.seconds - at
            part++
            at = 0.0
        }
        Log.i("Aster", "Listen ahead: ${minutes} minutes saved from ${startSeconds.toInt()}s")
        return true
    }

    private val lengths = mutableMapOf<String, Long>()

    /** A file's size: what the cache knows of it, else asked of the server with a one-byte range, whose reply says the whole size. */
    private suspend fun contentLength(url: String): Long? {
        lengths[url]?.let { return it }
        ContentMetadata.getContentLength(cache.getContentMetadata(url)).takeIf { it > 0 }?.let { lengths[url] = it; return it }
        return runInterruptible {
            val source = upstream()
            try {
                source.open(DataSpec.Builder().setUri(url).setPosition(0).setLength(1).build())
                val range = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value?.firstOrNull()
                range?.substringAfter('/', "")?.trim()?.toLongOrNull()?.also { lengths[url] = it }
            } catch (e: Exception) { null } finally { runCatching { source.close() } }
        }
    }

    /** Reads [length] bytes of [url] from [start] into the cache, skipping what's there; null when it can't be done now (offline, say). */
    private suspend fun save(url: String, start: Long, length: Long): Unit? {
        val writer = CacheWriter(
            CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory { upstream() }.createDataSource(),
            DataSpec.Builder().setUri(url).setKey(url).setPosition(start).setLength(length).build(), null, null,
        )
        val job = currentCoroutineContext()[Job]
        job?.invokeOnCompletion { writer.cancel() }
        return try {
            val before = cache.getCachedBytes(url, start, length)
            val t = System.currentTimeMillis()
            runInterruptible { writer.cache() }
            Log.i("Aster", "Listen ahead: cached before ${before / 1024} KB, after ${cache.getCachedBytes(url, start, length) / 1024} KB of ${length / 1024} KB wanted, in ${System.currentTimeMillis() - t} ms")
            Unit
        } catch (e: Exception) {
            if (e !is java.io.InterruptedIOException && e !is InterruptedException) Log.i("Aster", "Listen ahead paused: $e")
            null
        }
    }

    /** The book is over, another has started, or listening ahead is off: the work stops and the book's part of the cache goes. */
    fun release() {
        job?.cancel()
        job = null
        val keys = urls
        book = null
        urls = emptyList()
        scope.launch { for (key in keys) runCatching { cache.removeResource(key) } }
    }

    private companion object {
        /** How far the listener goes on before more is saved, and how soon a pass that couldn't finish is tried again. */
        const val REFILL_SECONDS = 30.0
        const val RETRY_MS = 10_000L
        /** The most it keeps: a day and a half of audio at 128 kbit/s. */
        const val MAX_BYTES = 512L * 1024 * 1024
    }
}
