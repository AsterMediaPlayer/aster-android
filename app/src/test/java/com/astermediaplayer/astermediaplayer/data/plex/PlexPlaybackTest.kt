package com.astermediaplayer.astermediaplayer.data.plex

import com.astermediaplayer.astermediaplayer.data.SubtitleCue
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Playing from Plex: what to skip, subtitle files beside the video, which tracks are fetched, and the transcoder's settings. */
class PlexPlaybackTest {
    private fun segments(json: String) = PlexPlayback.segments(JSONObject(json), "6139").map { it.getValue("Type").jsonPrimitive.content to (it.getValue("StartTicks").jsonPrimitive.content.toLong() to it.getValue("EndTicks").jsonPrimitive.content.toLong()) }

    @Test fun markersComeFirst() {
        val found = segments("""{"duration":1422428,"Marker":[{"type":"intro","startTimeOffset":60000,"endTimeOffset":150000},{"type":"credits","startTimeOffset":1300000,"endTimeOffset":1400000}],
            "Chapter":[{"tag":"Opening","startTimeOffset":135990,"endTimeOffset":226000}]}""")
        assertEquals(listOf("Intro", "Outro"), found.map { it.first })
        assertEquals(600_000_000L to 1_500_000_000L, found[0].second)
    }

    /** The anime episode on the test server: no markers, chapters named Opening and Ending. */
    @Test fun namedChaptersStandInForMarkers() {
        val found = segments("""{"duration":1422428,"Chapter":[
            {"tag":"Prologue","startTimeOffset":1002,"endTimeOffset":135990},{"tag":"Opening","startTimeOffset":135990,"endTimeOffset":226000},
            {"tag":"As of Today, I'm a Bad Girl!","startTimeOffset":226000,"endTimeOffset":545980},{"tag":"Ending","startTimeOffset":1280970,"endTimeOffset":1371020},
            {"tag":"Epilogue","startTimeOffset":1371020,"endTimeOffset":1422420}]}""")
        assertEquals(listOf("Intro", "Outro"), found.map { it.first })
        assertEquals(1_359_900_000L to 2_260_000_000L, found[0].second)
        assertEquals(12_809_700_000L, found[1].second.first)
    }

    @Test fun plainChaptersGiveNothing() {
        assertTrue(segments("""{"duration":60000,"Chapter":[{"tag":"Chapter 1","startTimeOffset":0,"endTimeOffset":60000}]}""").isEmpty())
    }

    @Test fun srtAndWebVtt() {
        val srt = "1\r\n00:00:01,500 --> 00:00:03,000\r\n<i>Hello</i>\r\nthere\r\n\r\n2\r\n00:01:02,000 --> 00:01:04,250\r\nAgain\r\n"
        assertEquals(listOf(SubtitleCue(1500, 3000, "Hello\nthere"), SubtitleCue(62_000, 64_250, "Again")), PlexSubtitles.cues(srt, "srt"))
        val vtt = "WEBVTT\n\n00:05.000 --> 00:06.000 align:start\nShort form\n"
        assertEquals(listOf(SubtitleCue(5000, 6000, "Short form")), PlexSubtitles.cues(vtt, "vtt"))
    }

    @Test fun assDialogueAsText() {
        val ass = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:10.50,0:00:12.00,Default,,0,0,0,,{\\an8}Signs, here\\Nand there"
        assertEquals(listOf(SubtitleCue(10_500, 12_000, "Signs, here\nand there")), PlexSubtitles.cues(ass, "ass"))
    }

    /** Plex hands out subtitle files beside the video, not tracks inside it: those are burned in when the stream is converted. */
    @Test fun onlySubtitleFilesAreFetched() {
        val sidecar = JSONObject("""{"id":1,"streamType":3,"index":9,"codec":"srt","key":"/library/streams/1"}""")
        val embedded = JSONObject("""{"id":2,"streamType":3,"index":4,"codec":"ass"}""")
        val picture = JSONObject("""{"id":3,"streamType":3,"index":5,"codec":"pgs"}""")
        fun delivery(s: JSONObject, converting: Boolean) = PlexPlayback.playbackStream(s, converting).getValue("DeliveryMethod").jsonPrimitive.content
        assertEquals("External", delivery(sidecar, true))
        assertEquals("External", delivery(sidecar, false))
        assertEquals("Encode", delivery(embedded, true))
        assertEquals("Embed", delivery(embedded, false))
        assertEquals("Encode", delivery(picture, true))
        assertEquals("Embed", delivery(picture, false))
        assertFalse(PlexPlayback.isFetchableText(embedded))
        assertTrue(PlexPlayback.isFetchableText(sidecar))
    }

    @Test fun theTranscodersSettings() {
        val device = PlexDevice("client-1", "Pixel")
        val query = PlexPlayback.transcodeQuery("6139", "s1", 600_0000000, 8_000_000, burnIn = 5, hostIsLocal = true, token = "tok", device = device)
        assertEquals("/library/metadata/6139", query["path"])
        assertEquals("8000", query["maxVideoBitrate"])
        assertEquals("600.0", query["offset"])
        assertEquals("burn", query["subtitles"])
        assertEquals("5", query["SubtitleStreamIndex"])
        assertEquals("lan", query["location"])
        assertEquals("tok", query["X-Plex-Token"])
        assertEquals("none", PlexPlayback.transcodeQuery("1", "s", 0, 1_000_000, null, false, "t", device)["subtitles"])
    }
}
