package com.astermediaplayer.astermediaplayer.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    @Test fun homeNetworkAddresses() {
        listOf(
            "192.168.1.240:32400", "http://192.168.1.240:8107", "10.0.0.5", "https://172.20.1.2:8920/jellyfin",
            "169.254.3.4", "nas", "http://nas:8096", "jellyfin.local:8096", "http://[fe80::1]:8096", "[fd12:3456::7]:8096",
        ).forEach { assertTrue(it, LocalNetwork.isLocalAddress(it)) }
    }

    @Test fun elsewhere() {
        listOf(
            "jellyfin.example.com", "https://8.8.8.8", "172.32.0.1", "192.169.1.1", "localhost:8096",
            "https://192-168-1-240.abc.plex.direct:32400", "http://[2001:db8::1]:8096", "",
        ).forEach { assertFalse(it, LocalNetwork.isLocalAddress(it)) }
    }
}
