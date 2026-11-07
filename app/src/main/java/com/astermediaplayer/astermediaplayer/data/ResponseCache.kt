package com.astermediaplayer.astermediaplayer.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * The last of each screen's answers, kept on disk (the Apple apps' ResponseCache): shown the moment the screen opens, then
 * replaced by what the server says now. Each is a file named for its server, user and screen, so one person's or server's
 * never shows for another. Nothing expires; a screen always asks again after showing it. Cleared on sign-out.
 */
class ResponseCache(private val dir: File) {
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }

    suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        runCatching { file(key).takeIf { it.isFile }?.readText() }.getOrNull()
    }

    /** Saved in the background, whole or not at all. */
    fun write(key: String, text: String) {
        writes.launch {
            runCatching {
                dir.mkdirs()
                val target = file(key)
                val partial = File(dir, target.name + ".part")
                partial.writeText(text)
                if (!partial.renameTo(target)) { target.delete(); partial.renameTo(target) }
            }
        }
    }

    fun clear() { writes.launch { runCatching { dir.deleteRecursively() } } }
}
