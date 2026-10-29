package com.astermediaplayer.astermediaplayer.data

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Redirects that can't carry the sign-in elsewhere. OkHttp follows redirects itself and drops only the standard
 * Authorization header, and only when one goes to another host; custom headers such as X-Emby-Authorization and
 * X-Plex-Token go along to wherever the server (or anything between) points. So every request on the way to another
 * origin than the one it was sent to leaves the credential headers behind; within that origin it keeps them all.
 * As the Windows app's `CredentialSafeRedirects`.
 */
object CredentialSafeRedirects {
    /** The headers that carry a sign-in, never sent to another origin. */
    val credentialHeaders = setOf(
        "Authorization", "Proxy-Authorization", "Cookie",
        "X-Emby-Authorization", "X-Emby-Token", "X-MediaBrowser-Token", "X-Plex-Token",
    )

    /** Same scheme, host and port: an https address sent to http, or another port, is another origin. */
    fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && a.port == b.port

    /**
     * A network interceptor, so it sees each hop of a redirect: the call's own request says where it was first sent.
     * (An application interceptor would only see the first request, and the SDK turns OkHttp's redirects on.)
     */
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        if (sameOrigin(chain.call().request().url, request.url)) chain.proceed(request)
        else chain.proceed(request.newBuilder().apply { credentialHeaders.forEach { removeHeader(it) } }.build())
    }
}

/** A client for requests that carry a sign-in. */
fun OkHttpClient.Builder.credentialSafeRedirects(): OkHttpClient.Builder = addNetworkInterceptor(CredentialSafeRedirects.interceptor)

/** One connection pool for every stream: video, music, audiobooks, themes and downloads. */
private val mediaHttp: OkHttpClient by lazy { OkHttpClient.Builder().credentialSafeRedirects().build() }

/**
 * Where the players and downloads read from the server, with [token] as the stream's sign-in. Media3's default data source
 * goes through Android's HttpURLConnection, which follows redirects with every header, the token included.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun signedInMedia(token: String?, timeoutMs: Long? = null): androidx.media3.datasource.okhttp.OkHttpDataSource.Factory {
    val client = if (timeoutMs == null) mediaHttp
    else mediaHttp.newBuilder().connectTimeout(java.time.Duration.ofMillis(timeoutMs)).readTimeout(java.time.Duration.ofMillis(timeoutMs)).build()
    return androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client).setDefaultRequestProperties(token?.let { mapOf("X-Emby-Token" to it) }.orEmpty())
}
