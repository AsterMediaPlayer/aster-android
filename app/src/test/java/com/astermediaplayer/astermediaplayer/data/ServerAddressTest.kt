package com.astermediaplayer.astermediaplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Apple apps' `ServerAddressTests`: which addresses are plain http:// over the internet, and get the warning. */
class ServerAddressTest {
    @Test fun plainHttpToTheInternetWarns() {
        listOf("http://myhost.ddns.net:8096", "http://jellyfin.example.com", "http://73.12.40.8:8096", "http://172.32.0.5:8096").forEach {
            assertTrue(it, ServerAddress.isUnencryptedRemote(it))
        }
    }

    @Test fun httpsOrTheHomeNetworkDoesNot() {
        listOf(
            "https://myhost.ddns.net", "http://192.168.1.240:8096", "http://10.0.0.4:8096", "http://172.20.1.2:8096",
            "http://100.101.102.103:8096", "http://nas.local:8096", "http://jellyfin:8096", "http://localhost:8096", "http://[fd12::1]:8096",
        ).forEach { assertFalse(it, ServerAddress.isUnencryptedRemote(it)) }
    }
}
