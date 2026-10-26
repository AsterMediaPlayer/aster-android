package com.astermediaplayer.astermediaplayer.data.plex

import com.astermediaplayer.astermediaplayer.data.emby.EmbyShapes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plex's titles become the app's: replies trimmed from a real Plex Media Server 1.43 (an anime episode, a film, a show and its season). */
class PlexMapperTest {
    private fun metadata(json: String): JSONObject = JSONObject(json).getJSONObject("MediaContainer").getJSONArray("Metadata").getJSONObject(0)

    private val episode = """
    {"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"6139","key":"/library/metadata/6139","parentRatingKey":"6138","grandparentRatingKey":"6137",
     "guid":"tv.plex.agents.custom.shoko://episode/e2255","type":"episode","title":"As of Today, I'm a Bad Girl!","grandparentTitle":"Bad Girl","parentTitle":"Season 1",
     "summary":"Yuu Yuutani is a model first year student.","index":1,"parentIndex":1,"year":2025,"viewOffset":62000,
     "thumb":"/library/metadata/6139/thumb/1792890348","art":"/library/metadata/6137/art/1792894637","parentThumb":"/library/metadata/6138/thumb/1792886180",
     "grandparentThumb":"/library/metadata/6137/thumb/1792894637","grandparentArt":"/library/metadata/6137/art/1792894637",
     "duration":1422428,"originallyAvailableAt":"2025-07-06","addedAt":1788704596,
     "Media":[{"id":8696,"duration":1422428,"bitrate":9007,"width":1920,"height":1080,"audioCodec":"opus","videoCodec":"hevc","videoResolution":"1080","container":"mkv",
       "Part":[{"id":8696,"key":"/library/parts/8696/1788704596/file.mkv","duration":1422428,"container":"mkv","Stream":[
         {"id":71904,"streamType":1,"default":true,"codec":"hevc","index":0,"width":1920,"height":1080,"displayTitle":"1080p"},
         {"id":71905,"streamType":2,"default":true,"codec":"opus","index":1,"channels":2,"languageCode":"eng","displayTitle":"English (OPUS Stereo)"},
         {"id":71907,"streamType":3,"codec":"ass","index":3,"forced":true,"languageCode":"eng","displayTitle":"English Forced"}]}]}],
     "Image":[{"alt":"x","type":"coverPoster","url":"/library/metadata/6137/thumb/1792894637"},{"alt":"x","type":"clearLogo","url":"/library/metadata/6137/clearLogo/1792894637"}],
     "Guid":[{"id":"tmdb://5439847"},{"id":"tvdb://10566016"}],
     "Chapter":[{"id":982,"tag":"Prologue","index":1,"startTimeOffset":1002,"endTimeOffset":135990},{"id":30356,"tag":"Opening","index":2,"startTimeOffset":135990,"endTimeOffset":226000}]}]}}
    """

    /** The mapped title, decoded by the SDK's own model the way the app reads it. */
    private fun dto(m: JSONObject): BaseItemDto = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        .decodeFromJsonElement(BaseItemDto.serializer(), EmbyShapes.fill(PlexMapper.item(m)))

    private fun id(n: Int) = PlexMapper.id(n.toString())

    @Test fun anAnimeEpisode() {
        val item = dto(metadata(episode))
        assertEquals(id(6139), item.id.toString())
        assertEquals(BaseItemKind.EPISODE, item.type)
        assertEquals(id(6137), item.seriesId.toString())
        assertEquals("Bad Girl", item.seriesName)
        assertEquals(id(6138), item.seasonId.toString())
        assertEquals(1, item.indexNumber)
        assertEquals(1, item.parentIndexNumber)
        assertEquals(14_224_280_000L, item.runTimeTicks)
        assertEquals(620_000_000L, item.userData?.playbackPositionTicks)
        assertEquals(false, item.userData?.played)
        // Its own still, then its show's poster, backdrop and logo.
        assertEquals("/library/metadata/6139/thumb/1792890348", item.imageTags?.get(org.jellyfin.sdk.model.api.ImageType.PRIMARY))
        assertNull(item.imageTags?.get(org.jellyfin.sdk.model.api.ImageType.LOGO))
        assertEquals("/library/metadata/6137/thumb/1792894637", item.seriesPrimaryImageTag)
        assertEquals(id(6137), item.parentBackdropItemId.toString())
        assertEquals(listOf("/library/metadata/6137/art/1792894637"), item.parentBackdropImageTags)
        assertEquals("/library/metadata/6137/clearLogo/1792894637", item.parentLogoImageTag)
        assertEquals(mapOf("Tmdb" to "5439847", "Tvdb" to "10566016", "Shoko" to "e2255"), item.providerIds)
        assertEquals(listOf("Prologue", "Opening"), item.chapters?.map { it.name })
        assertEquals(1_359_900_000L, item.chapters?.get(1)?.startPositionTicks)
        // The file's tracks, and the video's width for the HD badge.
        assertEquals(listOf("VIDEO", "AUDIO", "SUBTITLE"), item.mediaStreams?.map { it.type.name })
        assertEquals(true, item.mediaStreams?.get(2)?.isForced)
        assertEquals(1920, item.mediaSources?.firstOrNull()?.mediaStreams?.firstOrNull { it.type.name == "VIDEO" }?.width)
        assertEquals("mkv", item.container)
    }

    @Test fun aFilmWithItsCast() {
        val item = dto(metadata("""
        {"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"769","type":"movie","title":"The 'Burbs","titleSort":"Burbs","year":1989,"duration":6082560,
         "thumb":"/library/metadata/769/thumb/1792876534","art":"/library/metadata/769/art/1792876534","contentRating":"PG","rating":5.7,"audienceRating":7.1,
         "studio":"Imagine Entertainment","tagline":"Paranoia Begins At Home","addedAt":1791657480,"originallyAvailableAt":"1989-02-17","viewCount":2,
         "Genre":[{"id":1,"tag":"Comedy"}],"Role":[{"id":798,"tag":"Tom Hanks","role":"Ray Peterson","thumb":"https://metadata-static.plex.tv/people/a.jpg"}],
         "Director":[{"id":900,"tag":"Joe Dante"}]}]}}
        """))
        assertEquals(BaseItemKind.MOVIE, item.type)
        assertEquals("Burbs", item.sortName)
        assertEquals("PG", item.officialRating)
        assertEquals(7.1f, item.communityRating)
        assertEquals(listOf("Comedy"), item.genres)
        assertEquals("Imagine Entertainment", item.studios?.first()?.name)
        assertEquals(listOf("Paranoia Begins At Home"), item.taglines)
        assertEquals(true, item.userData?.played)
        assertEquals(listOf("/library/metadata/769/art/1792876534"), item.backdropImageTags)
        assertEquals(listOf("Tom Hanks"), item.people?.filter { it.type.name == "ACTOR" }?.map { it.name })
        assertEquals(id(798), item.people?.first()?.id.toString())
        assertEquals("Director", item.people?.last()?.type?.name?.lowercase()?.replaceFirstChar { it.uppercase() })
    }

    @Test fun aShowAndItsSeasonCountWhatsLeft() {
        val show = dto(metadata("""
        {"MediaContainer":{"Metadata":[{"ratingKey":"1188","type":"show","title":"No Reservations","leafCount":134,"viewedLeafCount":134,"childCount":8,
         "thumb":"/library/metadata/1188/thumb/1","art":"/library/metadata/1188/art/1"}]}}
        """))
        assertEquals(BaseItemKind.SERIES, show.type)
        assertEquals(8, show.childCount)
        assertEquals(true, show.userData?.played)
        assertEquals(0, show.userData?.unplayedItemCount)

        val season = dto(metadata("""
        {"MediaContainer":{"Metadata":[{"ratingKey":"1189","type":"season","title":"Season 1","index":1,"parentRatingKey":"1188","parentTitle":"No Reservations",
         "parentThumb":"/library/metadata/1188/thumb/1","leafCount":13,"viewedLeafCount":4,"thumb":"/library/metadata/1189/thumb/1"}]}}
        """))
        assertEquals(BaseItemKind.SEASON, season.type)
        assertEquals(id(1188), season.seriesId.toString())
        assertEquals(9, season.userData?.unplayedItemCount)
        assertEquals(false, season.userData?.played)
        assertEquals("/library/metadata/1188/thumb/1", season.seriesPrimaryImageTag)
    }

    @Test fun sectionsBecomeLibraries() {
        val directories = JSONObject("""{"MediaContainer":{"Directory":[{"key":"1","type":"movie","title":"Movies","agent":"tv.plex.agents.movie"},{"key":"4","type":"show","title":"Anime","agent":"tv.plex.agents.custom.shoko"}]}}""")
            .getJSONObject("MediaContainer").getJSONArray("Directory")
        val libraries = directories.objects().map { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromJsonElement(BaseItemDto.serializer(), EmbyShapes.fill(PlexMapper.library(it))) }
        assertEquals(listOf(CollectionType.MOVIES, CollectionType.TVSHOWS), libraries.map { it.collectionType })
        assertEquals(listOf(BaseItemKind.COLLECTION_FOLDER, BaseItemKind.COLLECTION_FOLDER), libraries.map { it.type })
        assertEquals(PlexMapper.libraryId("4"), libraries[1].id.toString())
        assertEquals("4", PlexMapper.plexId(libraries[1].id.toString()))
        // A library and a title can share a number; their ids still differ.
        assertTrue(PlexMapper.libraryId("4") != id(4))
    }

    /** A show's season list (Beastars, three seasons) carries an "All episodes" Directory with no type; the seasons still come through. */
    @Test fun aSeasonListWithItsAllEpisodesLink() {
        val container = JSONObject("""
        {"MediaContainer":{"size":3,"key":"6357","title2":"Beastars","Directory":[{"leafCount":48,"thumb":"/library/metadata/6357/thumb/1","viewedLeafCount":0,"key":"/library/metadata/6357/allLeaves","title":"All episodes"}],
         "Metadata":[{"ratingKey":"6358","key":"/library/metadata/6358/children","parentRatingKey":"6357","type":"season","title":"Season 1","parentTitle":"Beastars","index":1,"leafCount":12,"viewedLeafCount":0},
          {"ratingKey":"11377","key":"/library/metadata/11377/children","parentRatingKey":"6357","type":"season","title":"Season 2","parentTitle":"Beastars","index":2,"leafCount":12,"viewedLeafCount":0},
          {"ratingKey":"11352","key":"/library/metadata/11352/children","parentRatingKey":"6357","type":"season","title":"Season 3","parentTitle":"Beastars","index":3,"leafCount":24,"viewedLeafCount":0}]}}
        """).getJSONObject("MediaContainer")
        val seasons = container.getJSONArray("Metadata").objects().map(::dto)
        assertEquals(listOf(id(6358), id(11377), id(11352)), seasons.map { it.id.toString() })
        assertTrue(seasons.all { it.type == BaseItemKind.SEASON })
    }

    @Test fun idsRoundTrip() {
        assertEquals("6139", PlexMapper.plexId(PlexMapper.id("6139")))
        // Anything that isn't a number still gets one stable UUID.
        assertEquals(PlexMapper.id("Imagine Entertainment"), PlexMapper.id("Imagine Entertainment"))
    }
}
