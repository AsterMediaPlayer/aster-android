package com.astermediaplayer.astermediaplayer.data.profiles

import com.astermediaplayer.astermediaplayer.data.Session
import com.astermediaplayer.astermediaplayer.ui.profiles.PinCode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adding and changing a profile (the Apple apps' ProfileCreationTests): the form's checks match the plugin's own rules, the
 * server's lists become the form's choices, and a draft goes to the server as the plugin takes it, a change keeping what isn't changed.
 */
class ProfileCreationTest {
    private val server = "https://media.example"
    private val client = BonfireClient({ _, _, _ -> BonfireAnswer(200, "", null) }, server)

    private fun body(draft: ProfileDraft): JSONObject = Bonfire.request(draft)

    @Test fun theFormChecksWhatTheServerWould() {
        var draft = ProfileDraft()
        assertEquals("Enter a name for the profile.", draft.validationMessage(""))
        draft = draft.copy(name = "a".repeat(33))
        assertTrue(draft.validationMessage("")!!.contains("at most 32"))
        draft = draft.copy(name = "Kids <3")
        assertTrue(draft.validationMessage("")!!.contains("letters, numbers"))
        draft = draft.copy(name = "  Kids' Room  ")
        assertNull(draft.validationMessage(""))
        assertEquals("Kids' Room", draft.trimmedName)
        assertEquals("K", draft.initial)
        assertEquals("A PIN is 4 to 8 digits.", draft.copy(pin = "123").validationMessage("123"))
        assertEquals("A PIN is 4 to 8 digits.", draft.copy(pin = "12a4").validationMessage("12a4"))
        assertEquals("A PIN is 4 to 8 digits.", draft.copy(pin = "123456789").validationMessage("123456789"))
        assertEquals("The PINs don't match.", draft.copy(pin = "2468").validationMessage("2469"))
        assertNull(draft.copy(pin = "2468").validationMessage("2468"))
        // Taking the PIN away: whatever's in the field doesn't matter.
        assertNull(draft.copy(pin = "1", removePin = true).validationMessage(""))
    }

    @Test fun theServersListsBecomeChoices() {
        val avatars = Bonfire.parseAvatars(
            """{"AllowCustomUploads":true,"StarterAvatarsEnabled":true,"Avatars":[
              {"Id":"lib-1","DisplayName":"Our Dog","Url":"/plugins/profiles/avatars/lib-1","IsStarter":false},
              {"Id":"starter-fox","DisplayName":"Fox","IsStarter":true}]}""",
            client::avatarUrl,
        )
        assertEquals(listOf("Our Dog", "Fox"), avatars.map { it.name })
        assertEquals("$server/plugins/profiles/avatars/starter-fox?size=thumb", avatars[1].url)
        // Libraries by plain id, as the profile's own list is read.
        val libraries = Bonfire.parseLibraries("""[{"Id":"F1A2","Name":"Films","CollectionType":"movies"},{"id":"k1","name":"Kids","collectionType":"tvshows"}]""")
        assertEquals(listOf("f1a2", "k1"), libraries.map { it.id })
        assertEquals("tvshows", libraries[1].collectionType)
        // Youngest first, labels joined.
        val (ratings, country) = Bonfire.parseRatings("""{"Country":"GB","Ratings":[{"Score":12,"Labels":["12","12A"]},{"Score":0,"Labels":["U"]}]}""")
        assertEquals(listOf("U", "12 / 12A"), ratings.map { it.label })
        assertEquals(listOf(0, 12), ratings.map { it.score })
        assertEquals("GB", country)
    }

    @Test fun aListTheServerCantGiveLeavesTheFormUp() = runBlocking {
        val failing = BonfireProfiles({ _, path, _ -> if (path == "/libraries") BonfireAnswer(200, """[{"Id":"k1","Name":"Kids"}]""", null) else BonfireAnswer(500, "", null) }, server, null)
        val options = failing.creationChoices()
        assertTrue(options.avatars.isEmpty() && options.ratings.isEmpty())
        assertEquals(listOf("k1"), options.libraries.map { it.id })
    }

    @Test fun aNewProfileGoesAsThePluginTakesIt() {
        val b = body(
            ProfileDraft(
                name = " Kids ", pin = "2468", bypassPinOnLocalNetwork = true, avatarId = "starter-fox", colorHex = "#FF6B35",
                ratingScore = 12, libraryIds = setOf("k1", "f1"), masterPin = "1234",
            ),
        )
        assertFalse(b.has("profileId"))
        assertEquals("Kids", b.getString("profileName"))
        assertEquals("2468", b.getString("pin"))
        assertTrue(b.getBoolean("bypassPinOnLocalNetwork"))
        assertEquals("starter-fox", b.getString("avatarLibraryId"))
        assertEquals("#FF6B35", b.getString("avatarColor"))
        assertEquals("12", b.getString("maxParentalRating"))
        assertEquals(listOf("f1", "k1"), b.getJSONArray("enabledFolders").let { a -> List(a.length()) { a.getString(it) } })
        assertEquals("1234", b.getString("masterPin"))
        // No PIN: none sent, and nothing about skipping it; every library: the key left out, which the plugin reads as all.
        val open = body(ProfileDraft(name = "Guest"))
        listOf("pin", "bypassPinOnLocalNetwork", "enabledFolders", "maxParentalRating", "masterPin", "avatarLibraryId").forEach { assertFalse(it, open.has(it)) }
        assertEquals(Bonfire.DEFAULT_AVATAR_COLOR, open.getString("avatarColor"))
    }

    /** A change says which profile, keeps the PIN and picture unless told otherwise, and sends the rating and libraries as they are. */
    @Test fun aChangeKeepsWhatIsntChanged() {
        var draft = ProfileDraft(
            profileId = "11111111-2222-3333-4444-555555555555", name = "Kids", colorHex = "#FF6B35", ratingScore = 12,
            libraryIds = setOf("k1"), bypassPinOnLocalNetwork = true,
        )
        val kept = body(draft)
        assertEquals("11111111-2222-3333-4444-555555555555", kept.getString("profileId"))
        assertFalse(kept.has("pin"))
        assertFalse(kept.has("avatarLibraryId"))
        assertEquals("12", kept.getString("maxParentalRating"))
        assertEquals("k1", kept.getJSONArray("enabledFolders").getString(0))
        assertTrue(kept.getBoolean("bypassPinOnLocalNetwork"))
        assertEquals("9876", body(draft.copy(pin = "9876")).getString("pin"))
        val removed = body(draft.copy(removePin = true))
        assertEquals("", removed.getString("pin"))
        assertFalse(removed.has("bypassPinOnLocalNetwork"))
        draft = draft.copy(avatarId = "starter-cat", ratingScore = null, libraryIds = null)
        val widened = body(draft)
        assertEquals("starter-cat", widened.getString("avatarLibraryId"))
        assertFalse(widened.has("maxParentalRating"))
        assertFalse(widened.has("enabledFolders"))
    }

    @Test fun aProfileIsFilledInAsItIs() = runBlocking {
        val records = Bonfire.parseList(
            """[{"ProfileUserId":"11111111-2222-3333-4444-555555555555","ProfileName":"Kids","AvatarColor":"#FF6B35","HasPin":true,"RequiresPin":true,
                 "IsMaster":false,"EnabledFolders":["F1A2-0000","k1"],"AllLibraries":false,"BypassPinOnLocalNetwork":true,"MasterUserId":"8E3CDFA5-79A8-4BB9-BD9A-0E96B7DC974A"},
                {"ProfileUserId":"22222222-2222-3333-4444-555555555555","ProfileName":"Guest","IsMaster":false,"EnabledFolders":[],"AllLibraries":true},
                {"ProfileUserId":"8E3CDFA5-79A8-4BB9-BD9A-0E96B7DC974A","ProfileName":"Bard","IsMaster":true,"MaxSubProfiles":5}]""",
        )
        val kids = BonfireProfiles.profile(records[0], client, "x")
        assertEquals(listOf("f1a20000", "k1"), kids.enabledFolders)
        assertEquals(false, kids.allLibraries)
        assertEquals(true, kids.bypassPinOnLocalNetwork)
        assertEquals(true, BonfireProfiles.profile(records[1], client, "x").allLibraries)
        assertEquals(5, BonfireProfiles.profile(records[2], client, "x").maxProfiles)

        // The rating limit comes from the profile's own Jellyfin record; the rest from the list.
        val provider = BonfireProfiles({ _, _, _ -> BonfireAnswer(200, "", null) }, server, null) { JSONObject("""{"Policy":{"MaxParentalRating":12}}""") }
        val draft = provider.draft(kids)
        assertEquals("11111111-2222-3333-4444-555555555555", draft.profileId)
        assertEquals("Kids", draft.name)
        assertEquals("#FF6B35", draft.colorHex)
        assertEquals(setOf("f1a20000", "k1"), draft.libraryIds)
        assertEquals(12, draft.ratingScore)
        assertTrue(draft.bypassPinOnLocalNetwork)
        assertNull(provider.draft(BonfireProfiles.profile(records[1], client, "x")).libraryIds)
    }

    @Test fun aCreatedProfileIsItsPlainId() = runBlocking {
        val provider = BonfireProfiles({ _, _, _ -> BonfireAnswer(200, """{"ProfileUserId":"11111111-2222-3333-4444-555555555555","ProfileName":"Kids"}""", null) }, server, null)
        assertEquals("11111111222233334444555555555555", provider.createProfile(ProfileDraft(name = "Kids")))
    }

    @Test fun onlyTheMainAccountManagesItsHousehold() {
        val records = Bonfire.parseList(
            """[{"profileUserId":"aaaaaaaa-0000-0000-0000-000000000001","profileName":"Bard","isMaster":true},
                {"profileUserId":"aaaaaaaa-0000-0000-0000-000000000002","profileName":"Kids"},
                {"profileUserId":"aaaaaaaa-0000-0000-0000-000000000003","profileName":"Neighbour","isBonfire":true}]""",
        )
        val provider = BonfireProfiles({ _, _, _ -> BonfireAnswer(200, "", null) }, server, null)
        fun state(current: String) = HouseholdProfiles.State(provider = provider, profiles = records.map { BonfireProfiles.profile(it, client, current) })
        val asMaster = state("aaaaaaaa-0000-0000-0000-000000000001")
        assertTrue(asMaster.canCreateProfiles)
        assertTrue(asMaster.canEdit(asMaster.profiles[1]))
        assertFalse(asMaster.canEdit(asMaster.profiles[2]))
        assertEquals("Bard", asMaster.master?.name)
        val asKids = state("aaaaaaaa-0000-0000-0000-000000000002")
        assertFalse(asKids.canCreateProfiles)
        // A household of one opens the picker for its main account, to add the first profile.
        assertTrue(HouseholdProfiles.State(provider = provider, profiles = asMaster.profiles.take(1)).isAvailable)
        assertFalse(HouseholdProfiles.State(provider = provider, profiles = asKids.profiles.drop(1).take(1)).isAvailable)
        // A Plex Home switches but doesn't make profiles.
        val plex = PlexHomeProfiles.of(PlexHomeAccess({ emptyList() }, { _, _, _ -> null }, { emptyList() }, { 1L to "x" }),
            Session("https://plex", "t", "id", "Bard", plex = com.astermediaplayer.astermediaplayer.data.plex.PlexLink("a", "m", emptyList(), "u1", false, "home")))
        assertNull(plex?.creation)
    }

    @Test fun aPinIsFourToEightDigits() {
        assertEquals("1234", PinCode.digits("12a3-4"))
        assertEquals("12345678", PinCode.digits("1234567890"))
        assertFalse(PinCode.isComplete("123"))
        assertTrue(PinCode.isComplete("1234"))
        assertTrue(PinCode.isComplete("12345678"))
        // Boxes: one past what's typed, from four to eight.
        assertEquals(listOf(4, 4, 4, 4, 5, 8, 8), listOf("", "1", "12", "123", "1234", "1234567", "12345678").map(PinCode::boxCount))
    }
}
