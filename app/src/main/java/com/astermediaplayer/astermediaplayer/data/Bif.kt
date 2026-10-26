package com.astermediaplayer.astermediaplayer.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import java.util.UUID

// Emby's scrub thumbnails: a BIF file (`/Videos/{id}/index.bif`), the format Roku players read: a header, an index of when each
// picture is for and where it starts, then the pictures themselves as JPEGs. Jellyfin's equivalent is `Trickplay.kt`.

/** A BIF file: every picture of a title, in one piece. */
class BifFile private constructor(private val bytes: ByteArray, private val times: LongArray, private val offsets: IntArray, private val multiplierMs: Long) {
    /** How many pictures it holds. */
    val count: Int get() = times.size

    /** Where the picture for a moment is in the file (its first byte, and its length), or null when there are none. */
    fun picture(seconds: Double): Pair<Int, Int>? {
        if (times.isEmpty() || !seconds.isFinite()) return null
        val target = (seconds.coerceAtLeast(0.0) * 1000 / multiplierMs).toLong()
        // The last picture that isn't after the moment.
        var low = 0
        var high = times.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (times[mid] <= target) low = mid else high = mid - 1
        }
        val start = offsets[low]
        val end = if (low + 1 < offsets.size) offsets[low + 1] else bytes.size
        return if (start in 0 until end && end <= bytes.size) start to end - start else null
    }

    fun decode(seconds: Double): Bitmap? {
        val (start, length) = picture(seconds) ?: return null
        return BitmapFactory.decodeByteArray(bytes, start, length, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
    }

    companion object {
        private val MAGIC = byteArrayOf(0x89.toByte(), 'B'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        private const val HEADER = 64
        private const val INDEX_ENTRY = 8

        private fun uint(bytes: ByteArray, at: Int): Long =
            (bytes[at].toLong() and 0xFF) or ((bytes[at + 1].toLong() and 0xFF) shl 8) or ((bytes[at + 2].toLong() and 0xFF) shl 16) or ((bytes[at + 3].toLong() and 0xFF) shl 24)

        /** Null when [bytes] isn't a BIF file, is cut short, or holds no pictures. */
        fun parse(bytes: ByteArray): BifFile? {
            if (bytes.size < HEADER || !MAGIC.indices.all { bytes[it] == MAGIC[it] }) return null
            val count = uint(bytes, 12)
            if (count <= 0 || count > 100_000 || HEADER + (count + 1) * INDEX_ENTRY > bytes.size) return null
            // The time of each picture is in units of this many milliseconds (1000 when the file says 0).
            val multiplier = uint(bytes, 16).let { if (it == 0L) 1000L else it }
            val n = count.toInt()
            val times = LongArray(n) { uint(bytes, HEADER + it * INDEX_ENTRY) }
            // One more entry follows the pictures', where the last one ends.
            val offsets = IntArray(n + 1) { uint(bytes, HEADER + it * INDEX_ENTRY + 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
            return BifFile(bytes, times, offsets, multiplier)
        }
    }
}

/** The frames shown above the timeline while scrubbing, from either server's thumbnails. */
interface ScrubPreview {
    /** The frame for a moment, or null when there isn't one (yet). */
    suspend fun frame(seconds: Double): Bitmap?

    fun release()
}

/**
 * An Emby title's scrub frames. The file is fetched the first time a frame is asked for, and until it arrives the timeline shows
 * just the time; a title Emby has made no previews for (its file holds none) shows just the time throughout.
 */
class BifScrubPreview(private val repo: AsterRepository, private val itemId: UUID, private val mediaSourceId: String) : ScrubPreview {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loading: Deferred<BifFile?>? = null

    override suspend fun frame(seconds: Double): Bitmap? {
        val pending = loading ?: scope.async { repo.embyBif(itemId, mediaSourceId)?.let(BifFile::parse) }.also { loading = it }
        // Waits for the file; the timeline shows just the time meanwhile, and asks again when the scrub moves on.
        return pending.await()?.decode(seconds)
    }

    override fun release() = scope.cancel()
}
