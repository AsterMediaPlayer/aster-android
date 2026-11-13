package com.astermediaplayer.astermediaplayer.data.profiles

import com.astermediaplayer.astermediaplayer.data.SavedServer
import com.astermediaplayer.astermediaplayer.data.Session
import com.astermediaplayer.astermediaplayer.data.emby.ServerKind
import com.astermediaplayer.astermediaplayer.data.plex.PlexLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A household's PIN lock (the Apple apps' ProfileGateTests, lock part): who can be locked, with what, and when it asks on opening. */
class ProfileLockTest {
    private fun server(household: StoredHousehold?, lock: Int? = null, kind: ServerKind = ServerKind.Jellyfin) =
        SavedServer(Session("https://jf.example", "t", "u", "Kids", kind, household = household), lockAfterMinutes = lock)

    @Test fun aProfileLocksWithItsPinOnlyWhenItHasOne() {
        val withPin = server(StoredHousehold("m", isMaster = false, hasPin = true))
        assertTrue(withPin.isHouseholdProfile && withPin.asksProfilePin && withPin.canBeLocked)
        assertEquals("PIN", withPin.credential)
        val withoutPin = server(StoredHousehold("m", isMaster = false))
        assertFalse(withoutPin.canBeLocked)
        assertEquals("PIN", withoutPin.credential)
    }

    @Test fun theMainAccountLocksWithItsPinWhenItHasOneElseItsPassword() {
        val pinned = server(StoredHousehold("m", isMaster = true, hasPin = true))
        assertTrue(pinned.isPinnedMainAccount && pinned.asksProfilePin && pinned.canBeLocked)
        assertFalse(pinned.isHouseholdProfile)
        assertEquals("PIN", pinned.credential)
        val plain = server(StoredHousehold("m", isMaster = true))
        assertFalse(plain.asksProfilePin)
        assertTrue(plain.canBeLocked)
        assertEquals("password", plain.credential)
        // An ordinary sign-in is as it was.
        assertEquals("password", server(null).credential)
        assertTrue(server(null).canBeLocked)
    }

    @Test fun aPlexHomeKeepsItsOwnPinRules() {
        val link = PlexLink("a", "m", emptyList(), homeUserUuid = "u1", homeUserHasPin = true, homeToken = "home")
        val plex = SavedServer(Session("https://plex", "t", "u", "Kids", ServerKind.Plex, plex = link, household = StoredHousehold("m", isMaster = false, hasPin = true)))
        assertFalse(plex.isHouseholdProfile || plex.isPinnedMainAccount)
        assertTrue(plex.canBeLocked)
        assertEquals("PIN", plex.credential)
    }

    @Test fun aPinLockAsksOnOpeningToo() {
        assertTrue(server(StoredHousehold("m", isMaster = false, hasPin = true), lock = 15).locksOnOpening)
        assertTrue(server(StoredHousehold("m", isMaster = true, hasPin = true), lock = 1).locksOnOpening)
        assertFalse(server(StoredHousehold("m", isMaster = false, hasPin = true)).locksOnOpening)
        assertFalse(server(StoredHousehold("m", isMaster = false), lock = 15).locksOnOpening)
        // A password lock waits for time away, as before.
        assertFalse(server(null, lock = 15).locksOnOpening)
    }

    @Test fun aMinuteIsAChoice() {
        assertEquals(listOf(1, 5, 15, 30, 60, 240), SavedServer.LOCK_CHOICES)
        assertEquals("1 Minute", SavedServer.lockTitle(1))
        assertEquals("4 Hours", SavedServer.lockTitle(240))
    }

    @Test fun whetherAProfileHasAPinIsKept() {
        val kept = StoredHousehold("m", isMaster = false, avatarInitial = "K", hasPin = true)
        assertEquals(kept, StoredHousehold.fromJson(kept.toJson()))
        assertFalse(StoredHousehold.fromJson(StoredHousehold("m", isMaster = true).toJson()).hasPin)
    }
}
