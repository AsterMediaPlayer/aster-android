package com.astermediaplayer.astermediaplayer.data.emby

import org.jellyfin.sdk.model.api.MediaSegmentType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class EmbyMarkersTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000070")

    private fun item(vararg chapters: String, runtime: Long = 14_400_000_000L) =
        JSONObject("""{"RunTimeTicks":$runtime,"Chapters":[${chapters.joinToString(",")}]}""")

    private fun chapter(ticks: Long, marker: String) = """{"Name":"x","StartPositionTicks":$ticks,"MarkerType":"$marker"}"""

    @Test fun introAndCredits() {
        val segments = EmbyMarkers.segments(item(chapter(0, "Chapter"), chapter(300_000_000, "IntroStart"), chapter(900_000_000, "IntroEnd"), chapter(13_000_000_000, "CreditsStart")), id)
        assertEquals(2, segments.size)
        assertEquals(MediaSegmentType.INTRO, segments[0].type)
        assertEquals(300_000_000L, segments[0].startTicks)
        assertEquals(900_000_000L, segments[0].endTicks)
        assertEquals(MediaSegmentType.OUTRO, segments[1].type)
        assertEquals(13_000_000_000L, segments[1].startTicks)
        assertEquals(14_400_000_000L, segments[1].endTicks)
    }

    @Test fun plainChaptersGiveNothing() {
        assertTrue(EmbyMarkers.segments(item(chapter(0, "Chapter"), chapter(600_000_000, "Chapter")), id).isEmpty())
        assertTrue(EmbyMarkers.segments(JSONObject("{}"), id).isEmpty())
    }

    @Test fun halfAnIntroGivesNothing() {
        assertTrue(EmbyMarkers.segments(item(chapter(300_000_000, "IntroStart")), id).isEmpty())
    }
}
