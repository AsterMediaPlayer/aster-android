package com.astermediaplayer.astermediaplayer.data

import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.model.api.BaseItemDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import java.time.ZoneId
import java.util.TimeZone

/** A channel's tags save on a device west of Greenwich (report 20261116-Q53259). */
class ItemSavingTest {
    private val zone = TimeZone.getDefault()

    @Before fun eastern() = TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    @After fun back() = TimeZone.setDefault(zone)

    // A playlist as Jellyfin 12.0 sends it, with its "no date" placeholders.
    private val playlist = """{"Name":"Channel","Id":"5f6bb679382a84af90733cce8828b93d","Type":"Playlist","Tags":["aster:channel"],
        "DateCreated":"2026-11-16T02:28:18.0000000Z","PremiereDate":"0001-01-01T00:00:00.0000000Z",
        "DateLastMediaAdded":"0001-01-01T00:00:00.0000000Z","UserData":{"PlaybackPositionTicks":0,"ItemId":"5f6bb679382a84af90733cce8828b93d","PlayCount":0,"IsFavorite":false,"Played":false,
        "LastPlayedDate":"0001-01-01T00:00:00.0000000Z","Key":"k"},"Overview":"Kept"}"""

    @Test fun theSdkAloneWritesYearZero() {
        val record = ApiSerializer.decodeResponseBody<BaseItemDto>(playlist)
        assertTrue(ApiSerializer.encodeRequestBody(record)!!.contains("\"0000-"))
    }

    @Test fun savingLeavesPlaceholderDatesOut() {
        val record = ApiSerializer.decodeResponseBody<BaseItemDto>(playlist)
        val body = ApiSerializer.encodeRequestBody(forSaving(record, listOf("aster:channel", "aster:number=3")))!!
        assertFalse(body, body.contains("\"0000-") || body.contains("\"0001-"))
        val saved = ApiSerializer.decodeResponseBody<BaseItemDto>(body)
        assertEquals(listOf("aster:channel", "aster:number=3"), saved.tags)
        assertEquals("Kept", saved.overview)
        assertEquals(record.dateCreated, saved.dateCreated)
    }

    @Test fun localMeanTimeDatesAreLeftOutEastOfGreenwichToo() {
        val paris = ZoneId.of("Europe/Paris")
        TimeZone.setDefault(TimeZone.getTimeZone(paris))
        val old = playlist.replace("\"PremiereDate\":\"0001-01-01T00:00:00.0000000Z\"", "\"PremiereDate\":\"1850-06-01T00:00:00.0000000Z\"")
        val record = ApiSerializer.decodeResponseBody<BaseItemDto>(old)
        assertTrue(Regex("[+-]\\d\\d:\\d\\d:\\d\\d\"").containsMatchIn(ApiSerializer.encodeRequestBody(record)!!))
        val body = ApiSerializer.encodeRequestBody(forSaving(record, listOf("aster:channel"), paris))!!
        assertFalse(body, Regex("[+-]\\d\\d:\\d\\d:\\d\\d\"").containsMatchIn(body))
    }

    @Test fun modernDatesAreKept() {
        val chicago = ZoneId.of("America/Chicago")
        val modern = java.time.LocalDateTime.of(2026, 11, 15, 20, 0)
        assertEquals(modern, readableDate(modern, chicago))
        assertEquals(null, readableDate(java.time.LocalDateTime.of(0, 12, 31, 18, 9, 24), chicago))
        assertEquals(null, readableDate(java.time.LocalDateTime.of(1850, 6, 1, 0, 0), chicago))
    }

    @Test fun jellyfinsOwnJsonGoesBackUntouchedButForTheTags() {
        val saved = JSONObject(withTags(playlist, listOf("aster:channel", "aster:number=3")))
        val sent = JSONObject(playlist)
        assertEquals(listOf("aster:channel", "aster:number=3"), (0 until saved.getJSONArray("Tags").length()).map { saved.getJSONArray("Tags").getString(it) })
        for (key in sent.keys().asSequence().filter { it != "Tags" }) assertEquals(key, sent.get(key).toString(), saved.get(key).toString())
        assertEquals("0001-01-01T00:00:00.0000000Z", saved.getString("PremiereDate"))
    }
}
