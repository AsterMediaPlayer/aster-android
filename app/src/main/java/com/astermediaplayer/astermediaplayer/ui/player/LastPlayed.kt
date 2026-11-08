package com.astermediaplayer.astermediaplayer.ui.player

import android.content.Context
import java.util.UUID
import kotlin.math.abs

/**
 * What was playing last, kept so a car can pick up where listening left off: its play button with nothing loaded, or
 * the car connecting, brings it back (see [CarLibrary.onPlaybackResumption]). A queue of songs is kept with the song it
 * was on and how far in; a book by itself, as Jellyfin keeps where in it listening got to.
 */
class LastPlayed(context: Context) {
    private val prefs = context.getSharedPreferences("last_played", Context.MODE_PRIVATE)

    sealed interface Item
    data class Songs(val ids: List<UUID>, val index: Int, val positionMs: Long) : Item
    data class Book(val id: UUID) : Item

    fun load(): Item? = runCatching {
        when (prefs.getString(KIND, null)) {
            MUSIC -> {
                val ids = prefs.getString(IDS, null)?.split(',')?.filter { it.isNotEmpty() }?.map(UUID::fromString).orEmpty()
                if (ids.isEmpty()) null
                else Songs(ids, prefs.getInt(INDEX, 0).coerceIn(0, ids.size - 1), prefs.getLong(POSITION, 0))
            }
            BOOK -> prefs.getString(BOOK_ID, null)?.let { Book(UUID.fromString(it)) }
            else -> null
        }
    }.getOrNull()

    private var savedQueue: List<UUID>? = null
    private var savedIndex = -1
    private var savedPositionMs = 0L

    /**
     * The songs playing: written when the queue or the song changes, and otherwise every [STEP_MS] of listening, so a
     * song mid-way resumes near where it was without writing every second.
     */
    fun noteSongs(ids: List<UUID>, index: Int, positionMs: Long) {
        if (ids.isEmpty() || index !in ids.indices) return
        // A long queue is kept as a window around the song playing, a few songs back and the rest ahead.
        val from = if (ids.size <= MAX_SONGS) 0 else (index - 20).coerceIn(0, ids.size - MAX_SONGS)
        val kept = ids.subList(from, minOf(ids.size, from + MAX_SONGS)).toList()
        val at = index - from
        val sameSong = kept == savedQueue && at == savedIndex
        if (sameSong && abs(positionMs - savedPositionMs) < STEP_MS) return
        val edit = prefs.edit().putString(KIND, MUSIC).putInt(INDEX, at).putLong(POSITION, positionMs)
        if (kept != savedQueue) edit.putString(IDS, kept.joinToString(","))
        edit.apply()
        savedQueue = kept
        savedIndex = at
        savedPositionMs = positionMs
    }

    /** The book playing. */
    fun noteBook(id: UUID) {
        prefs.edit().putString(KIND, BOOK).putString(BOOK_ID, id.toString()).apply()
        savedQueue = null
        savedIndex = -1
    }

    private companion object {
        const val KIND = "kind"
        const val MUSIC = "music"
        const val BOOK = "book"
        const val IDS = "ids"
        const val INDEX = "index"
        const val POSITION = "positionMs"
        const val BOOK_ID = "bookId"
        /** A queue of songs past this is cut short: enough for any album or playlist resumed in a car. */
        const val MAX_SONGS = 500
        const val STEP_MS = 10_000L
    }
}
