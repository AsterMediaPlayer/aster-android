package com.astermediaplayer.astermediaplayer.data

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A redirect carries the sign-in only within the server it was sent to. As the Windows app's tests. */
class CredentialSafeRedirectsTest {
    private val server = MockWebServer()
    private val elsewhere = MockWebServer()
    private val client = OkHttpClient.Builder().credentialSafeRedirects().build()

    @Before fun start() { server.start(); elsewhere.start() }
    @After fun stop() { server.close(); elsewhere.close() }

    private fun signedIn(url: okhttp3.HttpUrl) = Request.Builder().url(url)
        .header("X-Emby-Authorization", "MediaBrowser Token=\"secret\"").header("X-Emby-Token", "secret")
        .header("X-Plex-Token", "secret").header("Authorization", "MediaBrowser Token=\"secret\"").header("X-Other", "kept")
        .build()

    private fun redirect(to: okhttp3.HttpUrl, code: Int = 302) = MockResponse.Builder().code(code).addHeader("Location", to.toString()).build()

    @Test fun anotherHostGetsNoSignIn() {
        server.enqueue(redirect(elsewhere.url("/landing")))
        elsewhere.enqueue(MockResponse.Builder().body("ok").build())
        client.newCall(signedIn(server.url("/Users/Me"))).execute().use { assertEquals("ok", it.body.string()) }
        assertEquals("secret", server.takeRequest().headers["X-Emby-Token"])
        val landed = elsewhere.takeRequest().headers
        CredentialSafeRedirects.credentialHeaders.forEach { assertNull(it, landed[it]) }
        assertEquals("kept", landed["X-Other"])
    }

    @Test fun theSameServerKeepsEveryHeader() {
        server.enqueue(redirect(server.url("/web/index.html"), code = 301))
        server.enqueue(MockResponse.Builder().body("ok").build())
        client.newCall(signedIn(server.url("/"))).execute().close()
        server.takeRequest()
        val followed = server.takeRequest().headers
        assertEquals("secret", followed["X-Emby-Token"])
        assertEquals("secret", followed["X-Plex-Token"])
        assertEquals("MediaBrowser Token=\"secret\"", followed["X-Emby-Authorization"])
    }

    @Test fun backAtTheServerAfterAnotherHostStaysWithout() {
        // Once a hop has left, nothing it says brings the sign-in back to a third address.
        server.enqueue(redirect(elsewhere.url("/a")))
        elsewhere.enqueue(redirect(elsewhere.url("/b")))
        elsewhere.enqueue(MockResponse.Builder().body("ok").build())
        client.newCall(signedIn(server.url("/"))).execute().close()
        server.takeRequest()
        assertNull(elsewhere.takeRequest().headers["X-Emby-Token"])
        assertNull(elsewhere.takeRequest().headers["X-Emby-Token"])
    }

    @Test fun anOriginIsSchemeHostAndPort() {
        val a = "https://media.example.com/emby".toHttpUrl()
        assertTrue(CredentialSafeRedirects.sameOrigin(a, "https://MEDIA.example.com/other".toHttpUrl()))
        assertFalse(CredentialSafeRedirects.sameOrigin(a, "http://media.example.com/emby".toHttpUrl()))
        assertFalse(CredentialSafeRedirects.sameOrigin(a, "https://media.example.com:8920/emby".toHttpUrl()))
        assertFalse(CredentialSafeRedirects.sameOrigin(a, "https://evil.example.com/emby".toHttpUrl()))
    }
}
