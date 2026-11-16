package com.astermediaplayer.astermediaplayer.data.diagnostics

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerErrorsTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().noteServerErrors().build()

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun notes() = DebugLog.recent().filter { "[http]" in it }

    @Test fun aRefusalIsNotedWithWhatTheServerSaid() {
        val before = notes().size
        server.enqueue(MockResponse.Builder().code(400).body("{\"title\": \"One or more validation errors occurred.\",\n \"errors\": {\"Type\": [\"bad\"]}}").build())
        val call = Request.Builder().url(server.url("/Items/abc?userId=someone")).post("{}".toRequestBody()).build()
        client.newCall(call).execute().use { assertEquals(400, it.code); assertTrue(it.body.string().contains("validation")) }
        val note = notes().drop(before).single()
        assertTrue(note, note.endsWith("[http] 400 POST /Items/abc: {\"title\": \"One or more validation errors occurred.\", \"errors\": {\"Type\": [\"bad\"]}}"))
        assertFalse("the query stays out", "someone" in note)
    }

    @Test fun successesAndPicturesAreNotNoted() {
        val before = notes().size
        server.enqueue(MockResponse.Builder().body("ok").build())
        server.enqueue(MockResponse.Builder().code(404).build())
        client.newCall(Request.Builder().url(server.url("/Users/Me")).build()).execute().close()
        client.newCall(Request.Builder().url(server.url("/Items/abc/Images/Primary")).build()).execute().close()
        assertEquals(before, notes().size)
    }

    @Test fun longAnswersAreCut() {
        val line = ServerErrors.describe("GET", "/x", 500, "a".repeat(2000))
        assertEquals("500 GET /x: " + "a".repeat(ServerErrors.KEEP_CHARS), line)
        assertEquals("401 GET /x", ServerErrors.describe("GET", "/x", 401, "  "))
    }
}
