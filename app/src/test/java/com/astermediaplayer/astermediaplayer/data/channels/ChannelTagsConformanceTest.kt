package com.astermediaplayer.astermediaplayer.data.channels

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Channel tags against channel-tags.json, the same file the Apple apps and the Jellyfin dashboard plugin run: playlists'
 * tags in, whether they make a channel, and every tag a save writes back, in order.
 */
class ChannelTagsConformanceTest {
    private fun cases(): JSONArray = org.json.JSONObject(javaClass.getResource("/channels/channel-tags.json")!!.readText()).getJSONArray("cases")

    private fun strings(a: JSONArray) = List(a.length()) { a.getString(it) }

    @Test fun theVectorsAreThere() = assertTrue(cases().length() >= 10)

    @Test
    fun tagsAreReadAndWrittenAsTheVectorsSay() {
        val cases = cases()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val tags = strings(c.getJSONArray("tags"))
            val written = strings(c.getJSONArray("written"))
            val read = ChannelTags.from(tags)
            assertEquals(name, c.getBoolean("isChannel"), read.isChannel)
            assertEquals(name, written, read.applied(tags))
            // Writing is settled: what was written writes the same again.
            assertEquals("$name (again)", written, ChannelTags.from(written).applied(written))
        }
    }
}
