package com.astermediaplayer.astermediaplayer.data.emby

import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real responses from an Emby 4.11 server, through the id translation and into the Jellyfin SDK's models. */
class EmbyDecodeTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/emby/$name")) { "missing $name" }.readBytes().decodeToString()

    private inline fun <reified T : Any> decode(name: String): T = ApiSerializer.decodeResponseBody(EmbyShapes.fill(kotlinx.serialization.json.Json.parseToJsonElement(EmbyIds.forSdk(fixture(name)))).toString())

    @Test fun idsRoundTrip() {
        for (n in listOf("0", "3", "471", "739", "2147483647", "999999999999")) {
            val uuid = EmbyIds.encode(n)!!
            assertEquals(36, uuid.length)
            assertEquals(n, EmbyIds.decode(uuid))
            assertEquals(n, EmbyIds.decode(java.util.UUID.fromString(uuid).toString()))
        }
        assertEquals(null, EmbyIds.encode("007"))
        assertEquals(null, EmbyIds.encode("12345678901234"))
        // Real GUIDs and other text are left as they are.
        assertEquals("58e737fe-d55e-4ef0-be7e-f13673762d91", EmbyIds.decode("58e737fe-d55e-4ef0-be7e-f13673762d91"))
        assertEquals("mediasource_739", EmbyIds.decode("mediasource_739"))
    }

    @Test fun responsesWithoutIdsPassThrough() {
        assertEquals("""{"Items":[],"TotalRecordCount":0}""", EmbyIds.forSdk("""{"Items":[],"TotalRecordCount":0}"""))
    }

    @Test fun publicInfoIsEmby() {
        val info = decode<PublicSystemInfo>("public-info.json")
        assertEquals(ServerKind.Emby, ServerKind.fromProductName(info.productName))
        assertEquals("4.11.0.3", info.version)
    }

    @Test fun publicUsers() { assertTrue(decode<List<UserDto>>("public-users.json").isNotEmpty()) }

    @Test fun authenticationResult() {
        val result = decode<AuthenticationResult>("auth.json")
        assertNotNull(result.accessToken)
        assertEquals("claude", result.user?.name)
    }

    @Test fun views() {
        val views = decode<BaseItemDtoQueryResult>("views.json").items
        assertTrue(views.isNotEmpty())
        assertEquals("3", EmbyIds.decode(views.first().id.toString()))
    }

    @Test fun seriesList() {
        val items = decode<BaseItemDtoQueryResult>("series-list.json").items
        assertTrue(items.isNotEmpty())
        items.forEach { assertNotNull(it.name); assertNotNull(it.type) }
    }

    @Test fun seriesDetail() {
        val item = decode<BaseItemDto>("series-detail.json")
        assertEquals("471", EmbyIds.decode(item.id.toString()))
    }

    @Test fun seasons() { assertTrue(decode<BaseItemDtoQueryResult>("seasons.json").items.isNotEmpty()) }

    @Test fun episodes() {
        val items = decode<BaseItemDtoQueryResult>("episodes.json").items
        assertTrue(items.isNotEmpty())
        val first = items.first()
        assertNotNull(first.seriesId)
        assertNotNull(first.mediaSources)
        assertEquals("471", EmbyIds.decode(first.seriesId.toString()))
    }

    @Test fun episodeDetail() { assertNotNull(decode<BaseItemDto>("episode-detail.json").id) }
    @Test fun latest() { assertTrue(decode<List<BaseItemDto>>("latest.json").isNotEmpty()) }
    @Test fun emptyLists() {
        decode<BaseItemDtoQueryResult>("nextup.json"); decode<BaseItemDtoQueryResult>("resume.json"); decode<BaseItemDtoQueryResult>("similar.json")
    }
    @Test fun genresAndStudios() { decode<BaseItemDtoQueryResult>("genres.json"); decode<BaseItemDtoQueryResult>("studios.json") }

    @Test fun playbackInfo() {
        val info = decode<PlaybackInfoResponse>("playback-info.json")
        assertTrue(info.mediaSources.isNotEmpty())
        assertNotNull(info.playSessionId)
    }

    @Test fun homeResponses() {
        val failures = (1..9).mapNotNull { n ->
            runCatching { decode<BaseItemDtoQueryResult>("home-$n.json") }.exceptionOrNull()?.let { "home-$n: ${it.message?.take(300)}" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** A server with a playlist has a "Playlists" library, whose collection type the SDK's enum doesn't list. */
    @Test fun viewsWithAPlaylistsLibrary() {
        val views = decode<BaseItemDtoQueryResult>("views-with-playlists.json")
        assertEquals(listOf("Shows", "Playlists"), views.items.map { it.name })
    }
}
