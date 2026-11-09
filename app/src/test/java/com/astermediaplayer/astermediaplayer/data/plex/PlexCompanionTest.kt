package com.astermediaplayer.astermediaplayer.data.plex

import com.astermediaplayer.astermediaplayer.data.TrackingFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexCompanionTest {
    @Test
    fun aPlexDirectAddressIsAlsoTriedAsPlainHttp() {
        // As plex.tv lists a container added to Custom server access URLs as http://192.168.1.240:8107.
        val connections = listOf(
            PlexConnection("https://24-40-118-66.abc.plex.direct:12248", "24.40.118.66", 12248, local = false, relay = false),
            PlexConnection("https://192-168-1-240.abc.plex.direct:8107", "192.168.1.240", 8107, local = true, relay = false),
            PlexConnection("https://45-33-90-120.abc.plex.direct:8443", "45.33.90.120", 8443, local = false, relay = true),
            PlexConnection("https://aster.example.com", "aster.example.com", 443, local = false, relay = false),
        )

        assertEquals(
            listOf(
                "https://192-168-1-240.abc.plex.direct:8107", "http://192.168.1.240:8107",
                "https://24-40-118-66.abc.plex.direct:12248", "http://24.40.118.66:12248",
                "https://aster.example.com",
            ),
            PlexCompanion.candidates(connections),
        )
    }

    @Test
    fun discoverIsOnUnlessTheServerSaysOtherwise() {
        assertTrue(TrackingFeatures.parse("""{"Seerr":{"Enabled":false},"Services":["simkl"],"Watchlist":["simkl"]}""").discover)
        assertFalse(TrackingFeatures.parse("""{"Seerr":{"Enabled":false},"Services":["simkl"],"Watchlist":["simkl"],"Discover":false}""").discover)
    }
}
