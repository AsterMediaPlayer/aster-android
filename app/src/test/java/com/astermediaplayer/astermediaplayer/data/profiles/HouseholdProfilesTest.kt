package com.astermediaplayer.astermediaplayer.data.profiles

import com.astermediaplayer.astermediaplayer.data.Session
import com.astermediaplayer.astermediaplayer.data.emby.ServerKind
import com.astermediaplayer.astermediaplayer.data.plex.PlexHomeUser
import com.astermediaplayer.astermediaplayer.data.plex.PlexLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Bonfire's household and Plex Home's, as the picker sees them (the Apple apps' HouseholdProfileTests and ProfileGateTests). */
class HouseholdProfilesTest {
    private val master = "8E3CDFA5-79A8-4BB9-BD9A-0E96B7DC974A"
    private val kids = "1b2c3d4e-5f60-4718-9a0b-1c2d3e4f5a6b"
    private val other = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val server = "https://jf.example"

    // The plugin's own pages write camelCase; through Jellyfin's serializer it's PascalCase. One of each, and a linked household's.
    private val listJson = """
        [
          {"profileUserId":"$master","profileName":"Bard","requiresPin":true,"hasPin":true,"isMaster":true},
          {"ProfileUserId":"$kids","ProfileName":"Kids","AvatarInitial":"K","AvatarColor":"#FF8800","HasPin":false,"MasterUserId":"$master","ProfileImage":"kids.png"},
          {"profileUserId":"$other","profileName":"Neighbour","isBonfire":true,"profileImage":"https://example.com/pic.jpg"}
        ]
    """.trimIndent()

    private val client = BonfireClient({ _, _, _ -> BonfireAnswer(200, "", null) }, server)

    private fun profiles(currentUserId: String) = Bonfire.parseList(listJson).map { BonfireProfiles.profile(it, client, currentUserId) }

    @Test fun theListReadsEitherSpelling() {
        val records = Bonfire.parseList(listJson)
        assertEquals(3, records.size)
        assertEquals("Bard", records[0].profileName)
        assertEquals(true, records[0].requiresPin)
        assertEquals("Kids", records[1].profileName)
        assertEquals("#FF8800", records[1].avatarColor)
        assertEquals(true, records[2].isBonfire)
        assertEquals("https://example.com/pic.jpg", records[2].profileImage)
    }

    @Test fun theCurrentProfileIsFoundEitherWayTheIdIsWritten() {
        // The session keeps the SDK's lowercase dashed form; the plugin writes upper case.
        val list = profiles(master.lowercase())
        assertEquals(listOf(true, false, false), list.map { it.isCurrent })
        assertEquals("8e3cdfa579a84bb9bd9a0e96b7dc974a", list[0].id)
        assertEquals(master, list[0].rawId)
        assertEquals("8e3cdfa579a84bb9bd9a0e96b7dc974a", list[1].masterUserId)
        assertEquals("8e3cdfa579a84bb9bd9a0e96b7dc974a", list[1].storedHousehold.masterUserId)
        assertFalse(list[1].storedHousehold.isMaster)
        assertTrue(list[0].storedHousehold.isMaster)
        assertEquals(listOf(true, false, false), profiles("8e3cdfa579a84bb9bd9a0e96b7dc974a").map { it.isCurrent })
    }

    @Test fun picturesComeFromWhereverTheyAre() {
        val list = profiles(master)
        assertNull(list[0].imageUrl)
        assertEquals("B", list[0].initial)
        assertEquals(Bonfire.DEFAULT_AVATAR_COLOR, list[0].colorHex)
        assertEquals("$server/plugins/profiles/image/$kids?size=thumb", list[1].imageUrl)
        assertEquals("K", list[1].initial)
        assertEquals("https://example.com/pic.jpg", list[2].imageUrl)
        assertTrue(list[2].isOtherHousehold)
        assertEquals("N", list[2].initial)
    }

    @Test fun aSwitchResultReadsEitherSpelling() {
        assertEquals("abc" to kids, Bonfire.parseSwitch("""{"activeProfileToken":"abc","jellyfinUserId":"$kids"}"""))
        assertEquals("def" to kids, Bonfire.parseSwitch("""{"ActiveProfileToken":"def","JellyfinUserId":"$kids"}"""))
        assertEquals(true, Bonfire.parseAskOnStartup("""{"AskOnStartup":true}"""))
        assertNull(Bonfire.parseAskOnStartup("""{}"""))
    }

    @Test fun aSwitchedProfileIsKeptUnderTheDashedIdTheSignInScreenGives() {
        assertEquals("1b2c3d4e-5f60-4718-9a0b-1c2d3e4f5a6b", HouseholdProfile.dashedId("1B2C3D4E5F6047189A0B1C2D3E4F5A6B"))
        assertEquals(kids, HouseholdProfile.dashedId(kids))
        assertEquals("not-an-id", HouseholdProfile.dashedId("not-an-id"))
    }

    @Test fun theWayToAProfileNeverSkipsItsPin() {
        val list = profiles(master)
        val saved = Session(server, "t", kids, "Kids")
        assertEquals(ProfileSwitchPlan.AlreadyCurrent, ProfileSwitchPlan.make(list[0], null))
        assertEquals(ProfileSwitchPlan.ReuseStored(saved), ProfileSwitchPlan.make(list[1], saved))
        assertEquals(ProfileSwitchPlan.SwitchNow, ProfileSwitchPlan.make(list[1], null))
        // A locked profile with a saved sign-in still asks.
        val locked = list[0].copy(isCurrent = false)
        assertEquals(ProfileSwitchPlan.NeedsPin, ProfileSwitchPlan.make(locked, saved))
    }

    @Test fun theServersRefusalsSayWhatTheyMean() {
        fun refusal(status: Int, code: String?, pinSent: Boolean = false, body: String = ""): Exception? =
            runCatching { Bonfire.check(BonfireAnswer(status, body, code), pinSent) }.exceptionOrNull() as Exception?
        assertNull(refusal(200, null))
        assertTrue(refusal(400, "PIN_WRONG") is ProfileSwitchException.WrongPin)
        assertTrue(refusal(400, "main_pin_required") is ProfileSwitchException.PinNeeded)
        assertTrue(refusal(429, "too_many_attempts") is ProfileSwitchException.RateLimited)
        assertEquals("Back at noon", refusal(503, "maintenance", body = "Back at noon")?.message)
        // Before 1.9, no header: the status has to do.
        assertTrue(refusal(400, null, pinSent = true) is ProfileSwitchException.WrongPin)
        assertTrue(refusal(400, null, pinSent = false) is ProfileSwitchException.Failed)
        assertTrue(refusal(429, null) is ProfileSwitchException.RateLimited)
        assertEquals("The server answered 500.", refusal(500, null)?.message)
    }

    @Test fun aPlexHomeUserBecomesAProfile() {
        val link = PlexLink("acct", "machine", emptyList(), homeUserUuid = "u2", homeUserHasPin = false, homeToken = "home")
        val locked = PlexHomeProfiles.profile(PlexHomeUser("u1", "Bard", hasPin = true, admin = true, thumb = "https://plex.tv/users/1/avatar"), link, "u2")
        assertTrue(locked.requiresPin && locked.hasPin && locked.isMaster && !locked.isCurrent)
        assertEquals("https://plex.tv/users/1/avatar", locked.imageUrl)
        assertEquals("machine", locked.storedHousehold.masterUserId)
        val open = PlexHomeProfiles.profile(PlexHomeUser("u2", "kids", hasPin = false, admin = false), link, "u2")
        assertTrue(!open.requiresPin && open.isCurrent && open.imageUrl == null && open.initial == "K")
    }

    @Test fun aPlexSignInSwitchesOnlyWithTheHomesToken() {
        val access = PlexHomeAccess({ emptyList() }, { _, _, _ -> null }, { emptyList() }, { 1L to "x" })
        val before = Session("https://plex.example", "t", "id", "Bard", ServerKind.Plex, plex = PlexLink("acct", "machine", emptyList()))
        assertNull(PlexHomeProfiles.of(access, before))
        val home = before.copy(plex = PlexLink("acct", "machine", emptyList(), homeUserUuid = "u1", homeToken = "home"))
        assertTrue(PlexHomeProfiles.of(access, home) != null)
    }

    @Test fun theLaunchGateDecidesFromWhatsKept() {
        val household = StoredHousehold("8e3cdfa579a84bb9bd9a0e96b7dc974a", isMaster = true)
        val inHousehold = Session(server, "t", master.lowercase(), "Bard", household = household)
        val ordinary = Session(server, "t", master.lowercase(), "Bard")
        val plexHome = Session("https://plex.example", "t", "id", "Bard", ServerKind.Plex, plex = PlexLink("acct", "m", emptyList(), homeUserUuid = "u1", homeToken = "home"))

        assertFalse(ProfileGate.shouldAsk(inHousehold, ProfileGateMode.Never, cachedAskOnStartup = true))
        assertTrue(ProfileGate.shouldAsk(inHousehold, ProfileGateMode.Always, cachedAskOnStartup = null))
        assertTrue(ProfileGate.shouldAsk(plexHome, ProfileGateMode.Always, cachedAskOnStartup = null))
        assertFalse(ProfileGate.shouldAsk(ordinary, ProfileGateMode.Always, cachedAskOnStartup = true))
        // As the server is set: only what the household said last time, and a Plex Home has no such setting.
        assertTrue(ProfileGate.shouldAsk(inHousehold, ProfileGateMode.Server, cachedAskOnStartup = true))
        assertFalse(ProfileGate.shouldAsk(inHousehold, ProfileGateMode.Server, cachedAskOnStartup = false))
        assertFalse(ProfileGate.shouldAsk(inHousehold, ProfileGateMode.Server, cachedAskOnStartup = null))
        assertFalse(ProfileGate.shouldAsk(plexHome, ProfileGateMode.Server, cachedAskOnStartup = true))
        assertEquals("$server|8e3cdfa579a84bb9bd9a0e96b7dc974a", inHousehold.householdKey)
        assertNull(ordinary.householdKey)
    }

    @Test fun aProfileAndItsHouseholdSurviveTheCache() {
        val list = profiles(master)
        list.forEach { assertEquals(it, HouseholdProfile.fromJson(it.toJson())) }
        val bare = StoredHousehold("m", isMaster = false)
        assertEquals(bare, StoredHousehold.fromJson(bare.toJson()))
        if (ProfileGateMode.of("nonsense") != ProfileGateMode.Server) fail("an unknown setting reads as the server's")
    }
}
