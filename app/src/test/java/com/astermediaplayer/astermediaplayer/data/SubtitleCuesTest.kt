package com.astermediaplayer.astermediaplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The same cases as the Apple apps' `SubtitleTests`: decoding Jellyfin's JSON cue list, cleaning cue text, and finding the cue under the playhead. */
class SubtitleCuesTest {
    @Test fun parsesJellyfinTrackEvents() {
        val json = """
            {"TrackEvents":[
              {"Id":"2","Text":"Second","StartPositionTicks":50000000,"EndPositionTicks":70000000},
              {"Id":"1","Text":"<i>First</i> line\r\nnext","StartPositionTicks":10000000,"EndPositionTicks":30000000},
              {"Id":"3","Text":"   ","StartPositionTicks":80000000,"EndPositionTicks":90000000}
            ]}
        """.trimIndent()
        val cues = SubtitleCue.parse(json)
        assertEquals(2, cues.size)
        assertEquals(SubtitleCue(1000, 3000, "First line\nnext"), cues[0])
        assertEquals(SubtitleCue(5000, 7000, "Second"), cues[1])
    }

    @Test fun anEmptyOrMissingEventListIsNoCues() {
        assertEquals(emptyList<SubtitleCue>(), SubtitleCue.parse("""{"TrackEvents":[]}"""))
        assertEquals(emptyList<SubtitleCue>(), SubtitleCue.parse("{}"))
    }

    @Test fun cleansMarkupAndEntities() {
        assertEquals("Tom & Jerry\n\"Hi\"", SubtitleCue.plainText("{\\an8}<b>Tom &amp; Jerry</b><br/>&quot;Hi&quot;"))
        assertEquals("one\ntwo", SubtitleCue.plainText("one\\Ntwo"))
        assertEquals("<tag>", SubtitleCue.plainText("<c.yellow>&lt;tag&gt;</c>"))
    }

    @Test fun leavesOutDrawings() {
        // A sign's box: only shape commands, so nothing to show.
        assertEquals("", SubtitleCue.plainText("{\\an7\\pos(336,172)\\blur1\\p1}m 278 -116 l 278 201 -276 203"))
        // Text, a drawing, then text again once \p0 ends it.
        assertEquals("Before after", SubtitleCue.plainText("Before{\\p1}m 0 0 l 10 0 10 10{\\p0} after"))
        // \pos and \pbo are not drawing mode.
        assertEquals("Sign text", SubtitleCue.plainText("{\\pos(10,20)\\pbo5}Sign text"))
        // The last \p in a block wins.
        assertEquals("Shown", SubtitleCue.plainText("{\\p1\\p0}Shown"))
    }

    @Test fun findsCuesUnderThePlayhead() {
        val cues = listOf(
            SubtitleCue(1000, 3000, "A"),
            SubtitleCue(2500, 4000, "B"),
            SubtitleCue(10_000, 12_000, "C"),
        )
        assertNull(SubtitleCue.textAt(cues, 500))
        assertEquals("A", SubtitleCue.textAt(cues, 1000))
        assertEquals("A\nB", SubtitleCue.textAt(cues, 2700))
        assertEquals("B", SubtitleCue.textAt(cues, 3500))
        assertNull(SubtitleCue.textAt(cues, 5000))
        assertEquals("C", SubtitleCue.textAt(cues, 11_000))
        assertNull(SubtitleCue.textAt(cues, 12_000))
        assertNull(SubtitleCue.textAt(emptyList(), 1000))
    }
}
