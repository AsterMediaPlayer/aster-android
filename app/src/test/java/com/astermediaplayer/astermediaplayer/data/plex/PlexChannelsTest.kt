package com.astermediaplayer.astermediaplayer.data.plex

import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.client.extensions.itemUpdateApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.playlistApi
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CreatePlaylistDto
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** Channels on Plex: playlists with their tags in their descriptions, read, written and made, through the SDK's own calls. */
class PlexChannelsTest {
    private class Stub : ApiClient() {
        override val baseUrl: String = "https://plex.example:32400"
        override val accessToken: String = "token"
        override val clientInfo = ClientInfo("Aster", "1")
        override val deviceInfo = DeviceInfo("device", "Test")
        override val httpClientOptions = HttpClientOptions()
        override val webSocket: SocketApi get() = error("unused")
        override fun update(baseUrl: String?, accessToken: String?, clientInfo: ClientInfo, deviceInfo: DeviceInfo) {}
        override fun createUrl(pathTemplate: String, pathParameters: Map<String, Any?>, queryParameters: Map<String, Any?>, ignorePathParameters: Boolean) = "unused:$pathTemplate"
        override suspend fun request(method: HttpMethod, pathTemplate: String, pathParameters: Map<String, Any?>, queryParameters: Map<String, Any?>, requestBody: Any?) = RawResponse(ByteArray(0), 404, emptyMap())
    }

    private class Fake(val answers: Map<String, String>) : PlexTransport {
        override val baseUrl = "https://plex.example:32400"
        override val token = "token"
        val sent = mutableListOf<Triple<String, String, Map<String, String?>>>()
        override suspend fun get(path: String, query: Map<String, String?>, start: Int?, size: Int?, session: String?) = answers[path] ?: throw PlexHttpException(404)
        override suspend fun send(method: String, path: String, query: Map<String, String?>, session: String?) { sent += Triple(method, path, query) }
        override suspend fun call(method: String, path: String, query: Map<String, String?>): String {
            sent += Triple(method, path, query)
            return answers["$method $path"] ?: throw PlexHttpException(404)
        }
        override fun url(path: String, query: Map<String, String?>) = baseUrl + path
    }

    private val playlists = """{"MediaContainer":{"size":2,"Metadata":[
        {"ratingKey":"901","type":"playlist","title":"Sitcoms","summary":"Laughs all day. aster:channel, aster:number=7","playlistType":"video","leafCount":60,"composite":"/playlists/901/composite/1"},
        {"ratingKey":"902","type":"playlist","title":"Road Trip","summary":"","playlistType":"audio","leafCount":12}]}}"""

    private fun client(extra: Map<String, String> = emptyMap()): Pair<PlexApiClient, Fake> {
        val fake = Fake(mapOf("/playlists" to playlists) + extra)
        return PlexApiClient(Stub(), fake, PlexDevice("client-1", "Test"), log = {}) to fake
    }

    @Test fun videoPlaylistsComeWithTheirChannelTags() = runBlocking {
        val (api, _) = client()
        val found = api.libraryApi.getItems(
            GetItemsRequest(includeItemTypes = listOf(BaseItemKind.PLAYLIST), mediaTypes = listOf(MediaType.VIDEO), recursive = true, fields = listOf(ItemFields.TAGS)),
        ).content.items
        assertEquals(listOf("Sitcoms"), found.map { it.name })
        assertEquals(BaseItemKind.PLAYLIST, found[0].type)
        assertEquals(listOf("aster:channel", "aster:number=7"), found[0].tags)
        assertEquals(60, found[0].childCount)
    }

    @Test fun aPlaylistsTitlesAreItsItems() = runBlocking {
        val items = """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"6139","type":"episode","title":"As of Today","grandparentTitle":"Bad Girl","index":1,"parentIndex":1,"duration":1422428}]}}"""
        val (api, _) = client(mapOf("/playlists/901/items" to items))
        val titles = api.playlistApi.getPlaylistItems(UUID.fromString(PlexMapper.id("901"))).content.items
        assertEquals(listOf("As of Today"), titles.map { it.name })
        assertEquals(BaseItemKind.EPISODE, titles[0].type)
    }

    @Test fun savedTagsGoIntoTheDescriptionKeepingItsWords() = runBlocking {
        val one = """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"901","type":"playlist","title":"Sitcoms","summary":"Laughs all day. aster:channel, aster:number=7","playlistType":"video","leafCount":60}]}}"""
        val (api, fake) = client(mapOf("/playlists/901" to one))
        val id = UUID.fromString(PlexMapper.id("901"))
        // As the app saves tags: the record read back, its tags replaced.
        val record = api.libraryApi.getItem(itemId = id).content
        api.itemUpdateApi.updateItem(id, record.copy(tags = listOf("aster:channel", "aster:number=4")))
        val (method, path, query) = fake.sent.single()
        assertEquals("PUT", method)
        assertEquals("/playlists/901", path)
        assertEquals("Laughs all day.\n\naster:channel, aster:number=4", query["summary"])
    }

    @Test fun aChannelFromAShowIsAPlaylistOfItsEpisodes() = runBlocking {
        val identity = """{"MediaContainer":{"machineIdentifier":"abc123"}}"""
        val made = """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"950","type":"playlist","title":"Bad Girl"}]}}"""
        val (api, fake) = client(mapOf("/identity" to identity, "POST /playlists" to made))
        val episodes = listOf("6139", "6140").map { UUID.fromString(PlexMapper.id(it)) }
        val result = api.playlistApi.createPlaylist(CreatePlaylistDto(name = "Bad Girl", ids = episodes, users = emptyList(), mediaType = MediaType.VIDEO, isPublic = true)).content
        assertEquals(PlexMapper.id("950"), result.id)
        val (_, _, query) = fake.sent.single()
        assertEquals("server://abc123/com.plexapp.plugins.library/library/metadata/6139,6140", query["uri"])
        assertEquals("video", query["type"])
    }
}
