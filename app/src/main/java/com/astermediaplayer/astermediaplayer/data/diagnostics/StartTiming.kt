package com.astermediaplayer.astermediaplayer.data.diagnostics

import android.os.SystemClock

/**
 * How long a video took to start, step by step: the item, the server's playback answer, the stream asked for, the stream ready
 * (for a transcode, the server's first piece of it), and the first picture. Told in a debug report's "This run", which outlasts
 * the device's own short log.
 */
class StartTiming(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private val began = now()
    private val marks = mutableListOf<Pair<String, Long>>()
    /** "direct play" or "transcode", once known. */
    var method: String? = null

    fun mark(step: String, once: Boolean = false) {
        if (once && marks.any { it.first == step }) return
        marks += step to now()
    }

    /** "Start (transcode) took 6.42 s: item 0.12 s, server's playback answer +0.30 s, …". */
    fun summary(leftEarly: Boolean = false): String {
        val total = (marks.lastOrNull()?.second ?: began) - began
        val steps = marks.mapIndexed { i, (step, at) ->
            val since = at - (if (i == 0) began else marks[i - 1].second)
            "$step ${if (i == 0) "" else "+"}${seconds(since)}"
        }
        val how = method?.let { " ($it)" }.orEmpty()
        return if (leftEarly) {
            "Start$how left after ${seconds(now() - began)} before the first picture" + (if (steps.isEmpty()) "" else "; it had reached: ${steps.joinToString(", ")}")
        } else {
            "Start$how took ${seconds(total)}: ${steps.joinToString(", ")}"
        }
    }

    private fun seconds(ms: Long) = String.format(java.util.Locale.US, "%.2f s", ms / 1000.0)
}
