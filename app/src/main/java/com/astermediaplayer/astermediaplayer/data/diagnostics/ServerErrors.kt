package com.astermediaplayer.astermediaplayer.data.diagnostics

import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Puts the server's own words into a bug report when it refuses a request. The Jellyfin SDK reports only "invalid HTTP status
 * 400", so a report showed that a save failed but not why (report 20261116-Q53259). Each answer of 400 or more is noted with
 * its method, path (no query, which can carry user ids) and the start of what the server said. Pictures are left out: a
 * missing one is routine.
 */
object ServerErrors {
    /** How much of the server's answer a note keeps. */
    const val KEEP_CHARS = 500

    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code >= 400 && "/Images/" !in request.url.encodedPath) {
            val said = runCatching { response.peekBody(KEEP_CHARS * 4L).string() }.getOrDefault("")
            DebugLog.note("http", describe(request.method, request.url.encodedPath, response.code, said))
        }
        response
    }

    /** One line: "400 POST /Items/abc: what the server said", the answer's whitespace folded and cut to [KEEP_CHARS]. */
    fun describe(method: String, path: String, code: Int, said: String): String {
        val words = said.replace(Regex("\\s+"), " ").trim().take(KEEP_CHARS)
        return if (words.isEmpty()) "$code $method $path" else "$code $method $path: $words"
    }
}

/** A client whose refused requests are noted for bug reports. */
fun OkHttpClient.Builder.noteServerErrors(): OkHttpClient.Builder = addInterceptor(ServerErrors.interceptor)
