package com.astermediaplayer.astermediaplayer.data

import org.jellyfin.sdk.model.api.LyricDto
import org.jellyfin.sdk.model.api.LyricLine
import org.jellyfin.sdk.model.api.LyricMetadata
import kotlin.math.roundToLong

/**
 * Lyrics from LRC text, as Emby serves them: `[mm:ss.xx]` stamps before each line, several when a line repeats, and
 * `[offset:±ms]`; tags such as `[ar:]` are skipped. Text without stamps is unsynced, a line each. The Apple apps' `Lyrics(lrc:)`.
 */
object Lrc {
    /** Null when there are no words. */
    fun parse(text: String): LyricDto? {
        val timed = mutableListOf<LyricLine>()
        val plain = mutableListOf<String>()
        var offsetTicks = 0L
        for (raw in text.lines()) {
            var line = raw.trim()
            val stamps = mutableListOf<Long>()
            var tagged = false
            while (line.startsWith("[")) {
                val close = line.indexOf(']').takeIf { it > 0 } ?: break
                val tag = line.substring(1, close)
                line = line.substring(close + 1)
                val ticks = ticks(tag)
                if (ticks != null) stamps += ticks
                else {
                    tagged = true
                    val parts = tag.split(":", limit = 2)
                    if (parts.size == 2 && parts[0].equals("offset", ignoreCase = true)) parts[1].trim().removePrefix("+").toLongOrNull()?.let { offsetTicks = it * 10_000 }
                }
            }
            val words = line.trim()
            if (stamps.isNotEmpty()) timed += stamps.map { LyricLine(text = words, start = it) }
            else if (!tagged) plain += words
        }
        if (timed.isNotEmpty()) {
            // A positive offset shows the lines sooner.
            return LyricDto(
                metadata = LyricMetadata(isSynced = true),
                lyrics = timed.sortedBy { it.start ?: 0 }.map { LyricLine(text = it.text, start = maxOf((it.start ?: 0) - offsetTicks, 0)) },
            )
        }
        val words = plain.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        if (words.isEmpty()) return null
        return LyricDto(metadata = LyricMetadata(isSynced = false), lyrics = words.map { LyricLine(text = it, start = null) })
    }

    /** `mm:ss`, `mm:ss.xx` or `mm:ss.xxx` in ticks; null for other tags. */
    private fun ticks(stamp: String): Long? {
        val parts = stamp.split(":")
        if (parts.size != 2) return null
        val minutes = parts[0].toLongOrNull() ?: return null
        if (!parts[1].all { it.isDigit() || it == '.' }) return null
        val seconds = parts[1].toDoubleOrNull() ?: return null
        return minutes * 600_000_000 + (seconds * 10_000_000).roundToLong()
    }
}
