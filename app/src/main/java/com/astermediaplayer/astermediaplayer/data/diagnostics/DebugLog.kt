package com.astermediaplayer.astermediaplayer.data.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a debug report tells of this run: notable moments (each playback attempt, what it played and how, and why it failed),
 * kept in memory, and a crash, written to a file as the app goes down so the next run can offer to send it. Each line also goes
 * to Android's log, which the report reads too.
 */
object DebugLog {
    private const val KEEP = 300
    private const val CRASH_FILE = "last-crash.txt"

    private val events = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var crashFile: File? = null

    /** Sets the crash handler up; call once, as the app starts. */
    fun install(context: Context) {
        crashFile = File(context.filesDir, CRASH_FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                crashFile?.writeText("${stamp.format(Date())} on thread ${thread.name}\n${Log.getStackTraceString(error)}\n\nBefore it:\n${recent().joinToString("\n")}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A moment worth a report's notice. */
    fun note(tag: String, message: String) {
        // Android's log isn't there in a JVM unit test; the report's own list is.
        runCatching { Log.i("Aster", "[$tag] $message") }
        synchronized(events) {
            events.addLast("${stamp.format(Date())} [$tag] $message")
            while (events.size > KEEP) events.removeFirst()
        }
    }

    fun recent(): List<String> = synchronized(events) { events.toList() }

    /** What was last played, field by field (container, codecs, how it played, any error), for a report's `playback`. */
    @Volatile var lastPlayback: Map<String, String> = emptyMap()

    /** The crash the last run ended in, if there was one and it hasn't been sent or put aside. */
    fun lastCrash(): String? = crashFile?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    /** The crash has been sent, or the viewer chose not to: it isn't offered again. */
    fun clearCrash() { crashFile?.delete() }
}
