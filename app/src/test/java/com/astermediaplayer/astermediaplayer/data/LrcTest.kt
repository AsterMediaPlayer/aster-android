package com.astermediaplayer.astermediaplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Apple apps' LRC cases (`EmbyExtrasTests`): Emby serves a song's lyrics as an LRC subtitle stream. */
class LrcTest {
    @Test fun readsTimedLrc() {
        val lyrics = Lrc.parse("[ar:Someone]\n[ti:A Song]\n[00:12.50]First line\n[00:05.00][01:02.25]Chorus")!!
        assertTrue(lyrics.metadata.isSynced == true)
        assertEquals(listOf("Chorus", "First line", "Chorus"), lyrics.lyrics.map { it.text })
        assertEquals(listOf(50_000_000L, 125_000_000L, 622_500_000L), lyrics.lyrics.map { it.start })
    }

    @Test fun anLrcOffsetShowsLinesSooner() {
        assertEquals(15_000_000L, Lrc.parse("[offset:+500]\n[00:02.00]Line")!!.lyrics.first().start)
    }

    @Test fun plainTextIsUnsynced() {
        val lyrics = Lrc.parse("\nOne\nTwo\n\n")!!
        assertTrue(lyrics.metadata.isSynced == false)
        assertEquals(listOf("One", "Two"), lyrics.lyrics.map { it.text })
        assertNull(Lrc.parse("[ar:Only tags]\n"))
    }
}
