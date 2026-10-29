package com.astermediaplayer.astermediaplayer.data.channels

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.ParentalRating
import org.jellyfin.sdk.model.api.UserPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A channel with anything on it the user's parental controls hold back is left out. The numbers are Jellyfin 12's for a
 * user capped at PG: the Sitcoms playlist lists 824 titles but sends 349. Ported from the Apple apps' tests.
 */
class ChannelRestrictionTest {
    private val pg = ContentPolicy(maxParentalRating = 10)
    private val unrestricted = ContentPolicy()
    private val ratings = listOf(
        RatingRank("G", 0),
        RatingRank("PG", 10),
        RatingRank("TV-PG", 10),
        RatingRank("PG-13", 13),
        RatingRank("TV-MA", 17, subScore = 1),
    )

    private fun tags(vararg t: String) = ChannelTags.from(t.toList())

    @Test
    fun aPlaylistMissingTitlesIsHidden() {
        val tags = tags("aster:channel")
        assertTrue(ChannelRestriction.isHeldBack(824, 349, tags, pg, emptyList()))
        assertFalse(ChannelRestriction.isHeldBack(29, 29, tags, pg, emptyList()))
        // The server refused the playlist outright.
        assertTrue(ChannelRestriction.isHeldBack(35, null, tags, pg, emptyList()))
    }

    @Test
    fun anUnrestrictedUserGetsEveryChannel() {
        // A missing file shouldn't cost an administrator the channel.
        assertFalse(ChannelRestriction.isHeldBack(10, 9, tags("aster:channel", "aster:genre=Horror"), unrestricted, ratings))
    }

    @Test
    fun aGenreChannelNeedsRatingsWithinTheCap() {
        assertTrue(ChannelRestriction.isHeldBack(0, 0, tags("aster:channel", "aster:genre=Horror"), pg, ratings))
        assertFalse(ChannelRestriction.isHeldBack(0, 0, tags("aster:channel", "aster:genre=Animation", "aster:rating=G,PG,TV-PG"), pg, ratings))
        assertTrue(ChannelRestriction.isHeldBack(0, 0, tags("aster:channel", "aster:genre=Animation", "aster:rating=PG,PG-13"), pg, ratings))
        // A rating the server doesn't know can't be cleared.
        assertTrue(ChannelRestriction.isHeldBack(0, 0, tags("aster:channel", "aster:genre=Animation", "aster:rating=Kids"), pg, ratings))
    }

    @Test
    fun otherRestrictionsHideGenreChannels() {
        val family = tags("aster:channel", "aster:genre=Animation", "aster:rating=G")
        assertTrue(ChannelRestriction.isHeldBack(0, 0, family, ContentPolicy(maxParentalRating = 10, blockedTags = listOf("gore")), ratings))
        assertTrue(ChannelRestriction.isHeldBack(0, 0, family, ContentPolicy(enableAllFolders = false), ratings))
    }

    @Test
    fun readsTheServersRatingsAndPolicy() {
        val json = Json { ignoreUnknownKeys = true }
        val read = json.decodeFromString(ListSerializer(ParentalRating.serializer()), """[{"Name":"TV-MA","Value":17,"RatingScore":{"score":17,"subScore":1}},{"Name":"PG","Value":10}]""").map(RatingRank::from)
        assertEquals(listOf(17, 10), read.map { it.score })
        assertEquals(listOf(1, null), read.map { it.subScore })
        // The rest of what the server sends, which the SDK requires.
        val flags = listOf(
            "IsAdministrator", "IsHidden", "IsDisabled", "EnableUserPreferenceAccess", "EnableRemoteControlOfOtherUsers", "EnableSharedDeviceControl",
            "EnableRemoteAccess", "EnableLiveTvManagement", "EnableLiveTvAccess", "EnableMediaPlayback", "EnableAudioPlaybackTranscoding",
            "EnableVideoPlaybackTranscoding", "EnablePlaybackRemuxing", "ForceRemoteSourceTranscoding", "EnableContentDeletion", "EnableContentDownloading",
            "EnableSyncTranscoding", "EnableMediaConversion", "EnableAllDevices", "EnableAllChannels", "EnablePublicSharing",
        ).joinToString(",") { "\"$it\":false" }
        val counts = listOf("InvalidLoginAttemptCount", "LoginAttemptsBeforeLockout", "MaxActiveSessions", "RemoteClientBitrateLimit").joinToString(",") { "\"$it\":0" }
        val base = """$flags,$counts,"SyncPlayAccess":"None","AuthenticationProviderId":"a","PasswordResetProviderId":"p","BlockedTags":[],"AllowedTags":[],"BlockUnratedItems":[],"EnableAllFolders":true"""
        val capped = ContentPolicy.from(json.decodeFromString(UserPolicy.serializer(), """{"MaxParentalRating":10,$base}"""))
        assertTrue(capped.restrictsContent && !capped.restrictsBeyondRating)
        assertFalse(ContentPolicy.from(json.decodeFromString(UserPolicy.serializer(), "{$base}")).restrictsContent)
    }
}
