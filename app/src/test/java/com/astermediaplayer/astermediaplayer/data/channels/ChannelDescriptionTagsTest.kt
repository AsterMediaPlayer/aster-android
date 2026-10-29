package com.astermediaplayer.astermediaplayer.data.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plex keeps a channel's tags in its playlist's description. Ported from the Windows app's tests. */
class ChannelDescriptionTagsTest {
    @Test fun tagsAreReadFromADescription() {
        assertEquals(listOf("aster:channel", "aster:number=7"), ChannelTagParsing.fromDescription("aster:channel, aster:number=7"))
        // A comma inside a value stays in it.
        assertEquals(
            listOf("aster:channel", "aster:user=alice,bob", "aster:channel-name=Laugh, Cry, Repeat", "aster:filler-types=bumpers,promos"),
            ChannelTagParsing.fromDescription("aster:channel, aster:user=alice,bob, aster:channel-name=Laugh, Cry, Repeat,aster:filler-types=bumpers,promos"),
        )
        // The description's own words, before them or on lines of their own, are left out.
        assertEquals(
            listOf("ASTER:Channel", "asterfin:number=3", "aster:group=Kids"),
            ChannelTagParsing.fromDescription("Cozy shows for Sunday mornings. ASTER:Channel, asterfin:number=3\r\n\nMore words, about it.\naster:group=Kids"),
        )
        assertTrue(ChannelTagParsing.fromDescription("A playlist about disaster: movies").isEmpty())
        assertTrue(ChannelTagParsing.fromDescription(null).isEmpty())

        val tags = ChannelTags.from(ChannelTagParsing.fromDescription("aster:channel, aster:number=7, aster:mode=shuffle"))
        assertTrue(tags.isChannel)
        assertEquals(7, tags.number)
    }

    /** Writing a channel's tags into a description keeps its own words, and reads back as the same tags. */
    @Test fun tagsAreWrittenIntoADescription() {
        assertEquals("aster:channel, aster:number=2", ChannelTagParsing.inDescription(null, listOf("aster:channel", "aster:number=2")))
        val written = ChannelTagParsing.inDescription("Cozy shows.\r\nFor Sundays. aster:channel\n\naster:number=1", listOf("aster:channel", "aster:user=alice,bob", "aster:number=4"))
        assertEquals("Cozy shows.\nFor Sundays.\n\naster:channel, aster:user=alice,bob, aster:number=4", written)
        assertEquals(listOf("aster:channel", "aster:user=alice,bob", "aster:number=4"), ChannelTagParsing.fromDescription(written))
        // No tags left: just the words.
        assertEquals("Cozy shows.", ChannelTagParsing.inDescription("Cozy shows.\n\naster:channel", emptyList()))
    }
}
