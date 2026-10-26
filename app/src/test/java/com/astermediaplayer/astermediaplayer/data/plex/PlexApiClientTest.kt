package com.astermediaplayer.astermediaplayer.data.plex

import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentApi
import org.jellyfin.sdk.api.client.extensions.searchApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** The Plex client, asked in the Jellyfin SDK's own calls and answered from canned Plex replies: what the screens and view models read. */
class PlexApiClientTest {
    /** A bare SDK client: only its properties are read (the Plex client answers the calls). */
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

    private class Call(val method: String, val path: String, val query: Map<String, String?>, val start: Int?, val size: Int?, val session: String?)

    private class Fake(val answers: MutableMap<String, String>) : PlexTransport {
        override val baseUrl = "https://plex.example:32400"
        override val token = "token"
        val calls = mutableListOf<Call>()
        override suspend fun get(path: String, query: Map<String, String?>, start: Int?, size: Int?, session: String?): String {
            calls += Call("GET", path, query, start, size, session)
            return answers[path] ?: throw PlexHttpException(404)
        }
        override suspend fun send(method: String, path: String, query: Map<String, String?>, session: String?) { calls += Call(method, path, query, null, null, session) }
        override fun url(path: String, query: Map<String, String?>) = baseUrl + path + "?" + query.filterValues { it != null }.entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    private val device = PlexDevice("client-1", "Test")
    private val user = UUID.fromString(PlexMapper.id("1"))

    private val sections = """{"MediaContainer":{"Directory":[{"key":"1","type":"movie","title":"Movies"},{"key":"2","type":"show","title":"TV"},{"key":"3","type":"artist","title":"Music"}]}}"""

    private val episode = """
    {"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"6139","type":"episode","title":"As of Today","grandparentTitle":"Bad Girl","grandparentRatingKey":"6137","parentRatingKey":"6138","index":1,"parentIndex":1,
     "duration":1422428,"viewOffset":62000,"thumb":"/library/metadata/6139/thumb/1",
     "Media":[{"id":8696,"duration":1422428,"bitrate":9007,"width":1920,"height":1080,"audioCodec":"opus","videoCodec":"hevc","container":"mkv",
       "Part":[{"id":8696,"key":"/library/parts/8696/1/file.mkv","duration":1422428,"container":"mkv","size":100,"Stream":[
         {"id":71904,"streamType":1,"default":true,"codec":"hevc","index":0,"width":1920,"height":1080,"bitDepth":10},
         {"id":71905,"streamType":2,"default":true,"codec":"opus","index":1,"channels":2,"languageCode":"eng"},
         {"id":71906,"streamType":3,"codec":"srt","index":2,"languageCode":"eng","key":"/library/streams/71906"},
         {"id":71907,"streamType":3,"codec":"pgs","index":3,"languageCode":"eng"}]}]}],
     "Chapter":[{"tag":"Opening","startTimeOffset":135990,"endTimeOffset":226000}],"Marker":[{"type":"credits","startTimeOffset":1300000,"endTimeOffset":1422000}]}]}}
    """

    private fun client(answers: Map<String, String>): Pair<PlexApiClient, Fake> {
        val fake = Fake(answers.toMutableMap())
        return PlexApiClient(Stub(), fake, device, log = {}) to fake
    }

    private fun profile(video: String, audio: String, width: Int = 3840, bitDepth: String? = "10") = DeviceProfile(
        name = "test", maxStreamingBitrate = 120_000_000, maxStaticBitrate = 120_000_000, musicStreamingTranscodingBitrate = 320_000,
        directPlayProfiles = listOf(DirectPlayProfile(container = "mp4,mkv", type = DlnaProfileType.VIDEO, videoCodec = video, audioCodec = audio)),
        transcodingProfiles = emptyList(), containerProfiles = emptyList(), subtitleProfiles = emptyList(),
        codecProfiles = listOf(
            org.jellyfin.sdk.model.api.CodecProfile(
                org.jellyfin.sdk.model.api.CodecType.VIDEO,
                listOfNotNull(
                    org.jellyfin.sdk.model.api.ProfileCondition(org.jellyfin.sdk.model.api.ProfileConditionType.LESS_THAN_EQUAL, org.jellyfin.sdk.model.api.ProfileConditionValue.WIDTH, width.toString(), true),
                    bitDepth?.let { org.jellyfin.sdk.model.api.ProfileCondition(org.jellyfin.sdk.model.api.ProfileConditionType.LESS_THAN_EQUAL, org.jellyfin.sdk.model.api.ProfileConditionValue.VIDEO_BIT_DEPTH, it, false) },
                ),
                emptyList(), "hevc", null, null,
            ),
        ),
    )

    @Test fun onlyMovieAndShowLibrariesAreViews() = runBlocking {
        val (api, _) = client(mapOf("/library/sections" to sections))
        val views = api.userViewApi.getUserViews(userId = user).content.items
        assertEquals(listOf("Movies", "TV"), views.map { it.name })
        assertEquals(listOf(CollectionType.MOVIES, CollectionType.TVSHOWS), views.map { it.collectionType })
    }

    @Test fun everyMovieAcrossLibrariesIsPagedByPlexsHeaders() = runBlocking {
        val page = """{"MediaContainer":{"totalSize":2,"Metadata":[{"ratingKey":"769","type":"movie","title":"The 'Burbs"},{"ratingKey":"770","type":"movie","title":"Heat"}]}}"""
        val (api, fake) = client(mapOf("/library/sections" to sections, "/library/sections/1/all" to page))
        val result = api.libraryApi.getItems(
            GetItemsRequest(userId = user, recursive = true, includeItemTypes = listOf(BaseItemKind.MOVIE), sortBy = listOf(ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.DESCENDING), startIndex = 0, limit = 50),
        ).content
        assertEquals(listOf("The 'Burbs", "Heat"), result.items.map { it.name })
        assertEquals(2, result.totalRecordCount)
        // Only the movie library was asked, sorted by title, descending, with the count read first (size 0) and the page by headers.
        val asked = fake.calls.filter { it.path.endsWith("/all") }
        assertEquals(listOf("/library/sections/1/all", "/library/sections/1/all"), asked.map { it.path })
        assertEquals(0, asked[0].size)
        assertEquals(2, asked[1].size)
        assertEquals("titleSort:desc", asked[1].query["sort"])
        assertEquals("1", asked[1].query["type"])
    }

    @Test fun aLibraryIsListedByItsOwnId() = runBlocking {
        val page = """{"MediaContainer":{"totalSize":1,"Metadata":[{"ratingKey":"1188","type":"show","title":"No Reservations"}]}}"""
        val (api, fake) = client(mapOf("/library/sections" to sections, "/library/sections/2/all" to page))
        val result = api.libraryApi.getItems(
            GetItemsRequest(userId = user, parentId = UUID.fromString(PlexMapper.libraryId("2")), recursive = true, includeItemTypes = listOf(BaseItemKind.SERIES)),
        ).content
        assertEquals(listOf("No Reservations"), result.items.map { it.name })
        assertTrue(fake.calls.none { it.path.contains("/library/sections/1/") })
        assertEquals("2", fake.calls.first { it.path.endsWith("/all") }.query["type"])
    }

    @Test fun aTitlesDetailsComeWithItsTracks() = runBlocking {
        val (api, _) = client(mapOf("/library/metadata/6139" to episode))
        val item = api.libraryApi.getItem(itemId = UUID.fromString(PlexMapper.id("6139")), userId = user).content
        assertEquals("As of Today", item.name)
        assertEquals(620_000_000L, item.userData?.playbackPositionTicks)
        assertEquals(PlexMapper.id("6137"), item.seriesId.toString())
        assertEquals(1920, item.mediaSources?.first()?.mediaStreams?.first { it.type.name == "VIDEO" }?.width)
    }

    @Test fun whatsPartwayThroughResumes() = runBlocking {
        val hub = """{"MediaContainer":{"Hub":[{"hubIdentifier":"home.continue","Metadata":[
            {"ratingKey":"6139","type":"episode","title":"Partway","viewOffset":62000,"duration":1000000,"librarySectionID":2},
            {"ratingKey":"6140","type":"episode","title":"Up next","duration":1000000,"librarySectionID":2}]}]}}"""
        val (api, _) = client(mapOf("/hubs/continueWatching" to hub))
        val result = api.libraryApi.getResumeItems(org.jellyfin.sdk.model.api.request.GetResumeItemsRequest(userId = user, limit = 10)).content
        assertEquals(listOf("Partway"), result.items.map { it.name })
    }

    @Test fun seasonsAndEpisodesOfAShow() = runBlocking {
        val seasons = """{"MediaContainer":{"Directory":[{"title":"All episodes","key":"/library/metadata/6357/allLeaves"}],"Metadata":[
            {"ratingKey":"6358","type":"season","title":"Season 1","index":1,"parentRatingKey":"6357","leafCount":12,"viewedLeafCount":0}]}}"""
        val episodes = """{"MediaContainer":{"Metadata":[{"ratingKey":"6139","type":"episode","title":"One","index":1,"parentIndex":1},{"ratingKey":"6140","type":"episode","title":"Two","index":2,"parentIndex":1}]}}"""
        val (api, fake) = client(mapOf("/library/metadata/6357/children" to seasons, "/library/metadata/6358/children" to episodes))
        val series = UUID.fromString(PlexMapper.id("6357"))
        assertEquals(listOf("Season 1"), api.showApi.getSeasons(seriesId = series, userId = user).content.items.map { it.name })
        val list = api.showApi.getEpisodes(seriesId = series, userId = user, seasonId = UUID.fromString(PlexMapper.id("6358"))).content.items
        assertEquals(listOf("One", "Two"), list.map { it.name })
        assertTrue(fake.calls.any { it.path == "/library/metadata/6358/children" })
    }

    @Test fun searchFindsTitlesThroughTheirHints() = runBlocking {
        val hubs = """{"MediaContainer":{"Hub":[
            {"type":"episode","Metadata":[{"ratingKey":"6139","type":"episode","title":"An episode"}]},
            {"type":"movie","Metadata":[{"ratingKey":"769","type":"movie","title":"The 'Burbs"}]},
            {"type":"artist","Metadata":[{"ratingKey":"5","type":"artist","title":"Not video"}]}]}}"""
        val (api, _) = client(mapOf("/hubs/search" to hubs))
        val hints = api.searchApi.getSearchHints(searchTerm = "burbs", userId = user, limit = 10).content.searchHints
        // Films, shows and episodes, in that order, and nothing else.
        assertEquals(listOf("The 'Burbs", "An episode"), hints.map { it.name })
        assertEquals(PlexMapper.id("769"), hints.first().itemId.toString())
    }

    @Test fun aFileThatPlaysAsItIsGetsNoTranscode() = runBlocking {
        val (api, fake) = client(mapOf("/library/metadata/6139" to episode))
        val info = api.mediaInfoApi.getPostedPlaybackInfo(
            UUID.fromString(PlexMapper.id("6139")),
            PlaybackInfoDto(userId = user, maxStreamingBitrate = 120_000_000, startTimeTicks = 0, deviceProfile = profile("h264,hevc", "aac,opus"), enableDirectPlay = true, enableDirectStream = true, enableTranscoding = true),
        ).content
        val source = info.mediaSources.single()
        assertEquals("8696", source.id)
        assertTrue(source.supportsDirectPlay)
        assertNull(source.transcodingUrl)
        // The tracks: the in-file picture subtitle is left to the player, the file beside the video is fetched.
        val subs = source.mediaStreams.orEmpty().filter { it.type.name == "SUBTITLE" }
        assertEquals(listOf("EXTERNAL", "EMBED"), subs.map { it.deliveryMethod?.name })
        assertEquals("/library/streams/71906", subs[0].deliveryUrl)
        assertTrue(fake.calls.none { it.path.contains("transcode") })
    }

    @Test fun aFileTheDeviceCantPlayIsConverted() = runBlocking {
        val (api, fake) = client(mapOf("/library/metadata/6139" to episode, "/video/:/transcode/universal/decision" to "{}"))
        // No HEVC in this device's profile.
        val info = api.mediaInfoApi.getPostedPlaybackInfo(
            UUID.fromString(PlexMapper.id("6139")),
            PlaybackInfoDto(userId = user, maxStreamingBitrate = 20_000_000, startTimeTicks = 600_0000000, deviceProfile = profile("h264", "aac,opus"), enableDirectPlay = true, enableDirectStream = true, enableTranscoding = true),
        ).content
        val source = info.mediaSources.single()
        assertFalse(source.supportsDirectPlay)
        val url = source.transcodingUrl!!
        assertTrue(url.startsWith("https://plex.example:32400/video/:/transcode/universal/start.m3u8?"))
        assertTrue("maxVideoBitrate=20000" in url)
        assertTrue("offset=600.0" in url)
        assertTrue("X-Plex-Token=token" in url)
        // Plex wants the decision asked first, in the same session.
        assertEquals(1, fake.calls.count { it.path == "/video/:/transcode/universal/decision" })
        // The picture subtitle is a burn-in choice now; the file beside the video is still drawn by the app.
        assertEquals(listOf("EXTERNAL", "ENCODE"), source.mediaStreams.orEmpty().filter { it.type.name == "SUBTITLE" }.map { it.deliveryMethod?.name })
    }

    @Test fun aTenBitPictureTooDeepForTheDeviceIsConverted() = runBlocking {
        val (api, _) = client(mapOf("/library/metadata/6139" to episode, "/video/:/transcode/universal/decision" to "{}"))
        val info = api.mediaInfoApi.getPostedPlaybackInfo(
            UUID.fromString(PlexMapper.id("6139")),
            PlaybackInfoDto(userId = user, maxStreamingBitrate = 120_000_000, startTimeTicks = 0, deviceProfile = profile("h264,hevc", "aac,opus", bitDepth = "8"), enableDirectPlay = true, enableDirectStream = true, enableTranscoding = true),
        ).content
        assertFalse(info.mediaSources.single().supportsDirectPlay)
    }

    @Test fun aRetryAfterAFailedPlayIsAlwaysConverted() = runBlocking {
        val (api, _) = client(mapOf("/library/metadata/6139" to episode, "/video/:/transcode/universal/decision" to "{}"))
        val info = api.mediaInfoApi.getPostedPlaybackInfo(
            UUID.fromString(PlexMapper.id("6139")),
            PlaybackInfoDto(userId = user, maxStreamingBitrate = 120_000_000, startTimeTicks = 0, deviceProfile = profile("h264,hevc", "aac,opus"), enableDirectPlay = false, enableDirectStream = false, enableTranscoding = true),
        ).content
        assertFalse(info.mediaSources.single().supportsDirectPlay)
        assertNotNull(info.mediaSources.single().transcodingUrl)
    }

    @Test fun skipMarkersComeFromPlexOrFromChapters() = runBlocking {
        val (api, _) = client(mapOf("/library/metadata/6139" to episode))
        val segments = api.mediaSegmentApi.getItemSegments(UUID.fromString(PlexMapper.id("6139")), listOf(MediaSegmentType.INTRO, MediaSegmentType.OUTRO)).content.items
        // Plex Pass's credits marker is there, so chapters aren't read.
        assertEquals(listOf(MediaSegmentType.OUTRO), segments.map { it.type })
        assertEquals(1300000L * 10_000, segments.single().startTicks)
        val chaptersOnly = episode.replace(""","Marker":[{"type":"credits","startTimeOffset":1300000,"endTimeOffset":1422000}]""", "")
        val (second, _) = client(mapOf("/library/metadata/6139" to chaptersOnly))
        val fromChapters = second.mediaSegmentApi.getItemSegments(UUID.fromString(PlexMapper.id("6139")), listOf(MediaSegmentType.INTRO, MediaSegmentType.OUTRO)).content.items
        assertEquals(listOf(MediaSegmentType.INTRO), fromChapters.map { it.type })
        assertEquals(226000L * 10_000, fromChapters.single().endTicks)
    }

    @Test fun progressGoesToThePlexTimeline() = runBlocking {
        val (api, fake) = client(mapOf("/library/metadata/6139" to episode, "/video/:/transcode/universal/decision" to "{}"))
        val id = UUID.fromString(PlexMapper.id("6139"))
        // The title's length is learned when playback starts, for the timeline.
        api.mediaInfoApi.getPostedPlaybackInfo(id, PlaybackInfoDto(userId = user, deviceProfile = profile("h264", "aac"), enableDirectPlay = true, enableDirectStream = true, enableTranscoding = true))
        api.sessionApi.reportPlaybackStopped(PlaybackStopInfo(itemId = id, positionTicks = 600_000_0000, failed = false, playSessionId = "session-1"))
        val stop = fake.calls.last { it.path == "/:/timeline" }
        assertEquals("stopped", stop.query["state"])
        assertEquals("6139", stop.query["ratingKey"])
        assertEquals("600000", stop.query["time"])
        assertEquals("1422428", stop.query["duration"])
        assertEquals("session-1", stop.session)
        api.sessionApi.reportPlaybackStart(PlaybackStartInfo(itemId = id, canSeek = true, isPaused = true, isMuted = false, positionTicks = 0, playMethod = PlayMethod.DIRECT_PLAY, repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT))
        assertEquals("paused", fake.calls.last { it.path == "/:/timeline" }.query["state"])
    }

    @Test fun watchedMarksUseScrobble() = runBlocking {
        val (api, fake) = client(emptyMap())
        val id = UUID.fromString(PlexMapper.id("6139"))
        api.userDataApi.markPlayedItem(itemId = id, userId = user)
        api.userDataApi.markUnplayedItem(itemId = id, userId = user)
        assertEquals(listOf("/:/scrobble", "/:/unscrobble"), fake.calls.map { it.path })
        assertEquals("6139", fake.calls[0].query["key"])
    }

    @Test fun picturesAndFilesAreAddressedOnThePlexServer() {
        val (api, _) = client(emptyMap())
        val id = UUID.fromString(PlexMapper.id("6139"))
        // A tag that is a path on the server goes through the photo transcoder, resized with its free side left long.
        val poster = api.imageApi.getItemImageUrl(id, ImageType.PRIMARY, maxWidth = 300, tag = "/library/metadata/6139/thumb/1")
        assertTrue("/photo/:/transcode?" in poster)
        assertTrue("url=/library/metadata/6139/thumb/1" in poster)
        assertTrue("width=300" in poster && "height=900" in poster)
        assertTrue("X-Plex-Token=token" in poster)
        // A logo stays a PNG; one with no tag is the title's own.
        val logo = api.imageApi.getItemImageUrl(id, ImageType.LOGO, maxWidth = 400)
        assertTrue("url=/library/metadata/6139/clearLogo" in logo && "format=png" in logo)
        // A person's photo is already an address.
        assertEquals("https://metadata-static.plex.tv/people/a.jpg", api.imageApi.getItemImageUrl(id, ImageType.PRIMARY, maxWidth = 100, tag = "https://metadata-static.plex.tv/people/a.jpg"))
        val file = api.videoApi.getVideoStreamUrl(itemId = id, static = true, mediaSourceId = "8696", playSessionId = "s1")
        assertTrue(file.startsWith("https://plex.example:32400/library/parts/8696/file?"))
        assertTrue("X-Plex-Token=token" in file && "X-Plex-Session-Identifier=s1" in file)
    }

    @Test fun aSubtitleFileIsFetchedAndRead() = runBlocking {
        val srt = "1\n00:00:01,000 --> 00:00:02,500\nHello <i>there</i>\n\n2\n00:00:03,000 --> 00:00:04,000\nBye\n"
        val (api, _) = client(mapOf("/library/metadata/6139" to episode, "/library/streams/71906" to srt))
        val cues = api.subtitleCues(PlexMapper.id("6139"), 2)
        assertEquals(listOf("Hello there", "Bye"), cues.map { it.text })
        assertEquals(1000L, cues[0].startMs)
        assertEquals(2500L, cues[0].endMs)
    }

    @Test fun whatPlexCantDoIsNotFound() = runBlocking {
        val (api, _) = client(emptyMap())
        val failure = runCatching { api.libraryApi.getThemeSongs(itemId = UUID.fromString(PlexMapper.id("1")), userId = user) }.exceptionOrNull()
        assertNotNull(failure)
    }
}
