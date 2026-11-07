package com.astermediaplayer.astermediaplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverTest {
    @Test fun featuresReadInAnyCase() {
        val f = TrackingFeatures.parse("""{"Seerr":{"Enabled":true,"Proxy":"/AsterTracking/Seerr/api/v1"},"Services":["simkl","lastfm"]}""")
        assertTrue(f.seerrEnabled)
        assertEquals("/AsterTracking/Seerr/api/v1", f.seerrProxy)
        assertEquals(listOf("simkl", "lastfm"), f.services)
        val lower = TrackingFeatures.parse("""{"seerr":{"enabled":false},"services":[]}""")
        assertFalse(lower.seerrEnabled)
        assertNull(lower.seerrProxy)
    }

    @Test fun pageKeepsOrderAndDropsEmptyRows() {
        val page = DiscoverPage.parse(
            """{"Rows":[
              {"Id":"r","Kind":"recommended","Title":"Recommended for You","Items":[
                {"Title":"Frieren: Beyond Journey's End","Year":2023,"MediaType":"tv","Anime":true,"Poster":"https://x/p.jpg",
                 "Progress":"Episode 4 of 12","Ids":{"tmdb":"209867","mal":"52991"},
                 "InLibrary":{"ItemId":"0123456789abcdef0123456789abcdef","Name":"Frieren","Type":"Series"}},
                {"Title":"Frieren: Beyond Journey's End","Year":2023,"MediaType":"tv","Ids":{"tmdb":"209867"},
                 "InLibrary":{"ItemId":"0123456789abcdef0123456789abcdef"}}]},
              {"Id":"e","Kind":"trending","Title":"Trending","Items":[]},
              {"Id":"m","Kind":"trending","Title":"Trending","Items":[{"Title":"Dune","Year":2021,"MediaType":"movie","Ids":{"tmdb":"438631"},"InLibrary":null}]}
            ],"Built":"2026-11-07T10:00:00Z"}""",
        )
        assertEquals(listOf("recommended", "trending"), page.rows.map { it.kind })
        val frieren = page.rows[0].items.single()
        assertEquals("Episode 4 of 12", frieren.subtitle)
        assertEquals("209867", frieren.tmdbId)
        assertEquals("Series", frieren.inLibrary?.type)
        val dune = page.rows[1].items.single()
        assertNull(dune.inLibrary)
        assertEquals("2021 · Film", dune.subtitle)
    }

    @Test fun posterIsWebAddressOrServerPath() {
        assertEquals("https://image.tmdb.org/t/p/w500/a.jpg", DiscoverPage.posterUrl("https://image.tmdb.org/t/p/w500/a.jpg", "http://jf:8096"))
        assertEquals("http://jf:8096/Items/abc/Images/Primary?maxHeight=450", DiscoverPage.posterUrl("/Items/abc/Images/Primary?maxHeight=450", "http://jf:8096/"))
        assertNull(DiscoverPage.posterUrl("  ", "http://jf:8096"))
    }
}
