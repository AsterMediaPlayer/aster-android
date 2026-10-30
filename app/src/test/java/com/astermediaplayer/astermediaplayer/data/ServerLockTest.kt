package com.astermediaplayer.astermediaplayer.data

import com.astermediaplayer.astermediaplayer.data.emby.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Apple apps' `ServerLockTests`: a change that lets others past a server's lock asks for its password first; one that tightens it doesn't. */
class ServerLockTest {
    private fun server(asks: Boolean = false, lockAfter: Int? = null, kind: ServerKind = ServerKind.Jellyfin) =
        SavedServer(Session("http://192.168.1.240:8096", "t", "u", "peppy", kind, "Server"), asksPasswordToSwitch = asks, lockAfterMinutes = lockAfter)

    @Test fun turningOffTheSwitchPasswordAsksForIt() {
        assertTrue(server(asks = true).loosens(asksToSwitch = false))
        assertFalse(server(asks = false).loosens(asksToSwitch = true))
        assertFalse(server(asks = true).loosens(asksToSwitch = true))
    }

    @Test fun aLongerOrNoLockAsksForIt() {
        val locked = server(lockAfter = 15)
        assertTrue(locked.loosens(lockAfter = null))
        assertTrue(locked.loosens(lockAfter = 60))
        assertFalse(locked.loosens(lockAfter = 5))
        assertFalse(locked.loosens(lockAfter = 15))
        // Nothing to loosen on a server that never locks.
        assertFalse(server().loosens(lockAfter = 5))
    }

    /** Plex has no password of the server's own to ask for, so its lock isn't offered. */
    @Test fun plexIsNeverAsked() {
        assertFalse(server(asks = true, lockAfter = 15, kind = ServerKind.Plex).loosens(lockAfter = null))
        assertFalse(server(asks = true, kind = ServerKind.Plex).loosens(asksToSwitch = false))
    }

    @Test fun aPlexServerCanBeLockedOnlyWithAPinToAsk() {
        fun plex(link: com.astermediaplayer.astermediaplayer.data.plex.PlexLink?) =
            SavedServer(Session("http://192.168.1.240:32400", "t", "u", "peppy", ServerKind.Plex, "Server", link))
        val withPin = com.astermediaplayer.astermediaplayer.data.plex.PlexLink("a", "m", emptyList(), homeUserUuid = "u", homeUserHasPin = true, homeToken = "h")
        assertTrue(plex(withPin).canBeLocked)
        assertEquals("PIN", plex(withPin).credential)
        assertFalse(plex(withPin.copy(homeUserHasPin = false)).canBeLocked)
        assertFalse(plex(null).canBeLocked)
        assertEquals("password", server().credential)
    }

    @Test fun loosenWithAPlexPinAsksForIt() {
        val link = com.astermediaplayer.astermediaplayer.data.plex.PlexLink("a", "m", emptyList(), homeUserUuid = "u", homeUserHasPin = true, homeToken = "h")
        val locked = SavedServer(Session("http://plex", "t", "u", "peppy", ServerKind.Plex, "Server", link), asksPasswordToSwitch = true, lockAfterMinutes = 15)
        assertTrue(locked.loosens(asksToSwitch = false))
        assertTrue(locked.loosens(lockAfter = null))
        // Without a PIN there's nothing to ask for, so nothing to loosen.
        assertFalse(locked.copy(session = locked.session.copy(plex = link.copy(homeUserHasPin = false))).loosens(asksToSwitch = false))
    }
}
