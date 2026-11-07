package com.astermediaplayer.astermediaplayer.data.seerr

import com.astermediaplayer.astermediaplayer.data.DiscoverEntry
import com.astermediaplayer.astermediaplayer.data.DiscoverItem
import com.astermediaplayer.astermediaplayer.data.DiscoverOrder
import com.astermediaplayer.astermediaplayer.data.DiscoverRow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SeerrDiscoverTest {
    private fun row(kind: String) = DiscoverRow(kind, kind, kind, null, listOf(DiscoverItem("t", 2020, "tv", false, null, null, null, mapOf("tmdb" to "1"), null)))
    private fun slider(type: Int) = SeerrSlider(type, type)

    private fun keys(entries: List<DiscoverEntry>) = entries.map {
        when (it) {
            is DiscoverEntry.Tracked -> it.row.kind
            is DiscoverEntry.Slider -> "s${it.slider.type}"
            DiscoverEntry.InTheaters -> "theaters"
        }
    }

    @Test fun orderMixesAsTheContractSays() {
        val rows = listOf(row("trending"), row("recommended"), row("continue"), row("season"), row("watchlist"), row("newkind"))
        val sliders = listOf(slider(1), slider(3), slider(4), slider(5))
        assertEquals(
            listOf("recommended", "continue", "watchlist", "newkind", "s1", "trending", "season", "theaters", "s5"),
            keys(DiscoverOrder.order(rows, sliders, theaters = true)),
        )
    }

    @Test fun withoutSeerrOnlyTheServersRowsInOrder() {
        val rows = listOf(row("trending"), row("recommended"))
        assertEquals(listOf("trending", "recommended"), keys(DiscoverOrder.order(rows, null, theaters = false)))
    }

    @Test fun seerrsTrendingStaysWhenTheServerHasNoneAndWatchlistWhenNoServerWatchlist() {
        assertEquals(listOf("recommended", "s3", "s4", "theaters"), keys(DiscoverOrder.order(listOf(row("recommended")), listOf(slider(3), slider(4)), theaters = true)))
    }

    @Test fun noSeerrTrendingPutsTheServersLast() {
        assertEquals(listOf("s5", "trending"), keys(DiscoverOrder.order(listOf(row("trending")), listOf(slider(5)), theaters = true)))
    }

    @Test fun filterPathAsSeerrWantsIt() {
        val f = SeerrFilter(SeerrFilter.Kind.Movie, sort = SeerrFilter.Sort.Rating, genres = setOf(28, 12), fromYear = 2010, toYear = 2019,
            minimumRating = 7.0, providers = setOf(337, 8), region = "US")
        assertEquals(
            "/discover/movies?page=2&sortBy=vote_average.desc&genre=12%2C28&primaryReleaseDateGte=2010-01-01&primaryReleaseDateLte=2019-12-31" +
                "&voteAverageGte=7&voteCountGte=50&watchProviders=8|337&watchRegion=US",
            f.path(2),
        )
        assertEquals("/discover/tv?page=1&sortBy=original_name.asc", SeerrFilter(SeerrFilter.Kind.Tv, sort = SeerrFilter.Sort.Title).path(1))
    }

    @Test fun animeSwitchSetsAndClearsBoth() {
        val on = SeerrFilter(SeerrFilter.Kind.Tv, genres = setOf(35)).withAnime(true)
        assertTrue(on.isAnime)
        assertEquals(setOf(35, 16), on.genres)
        val off = on.withAnime(false)
        assertFalse(off.isAnime)
        assertEquals(setOf(35), off.genres)
        assertNull(off.language)
        assertEquals(2, on.activeCount)
    }

    @Test fun rowPathsAndNames() {
        assertEquals("/discover/movies?page=1&genre=28%2C12" to SeerrFilter.Kind.Movie, SeerrClient.titlesPath(SeerrSlider(1, 14, data = "28,12")))
        assertEquals("/discover/tv?page=1&watchRegion=GB&watchProviders=8|337" to SeerrFilter.Kind.Tv, SeerrClient.titlesPath(SeerrSlider(1, 21, data = "GB,8|337")))
        assertNull(SeerrClient.titlesPath(SeerrSlider(1, 6)))
        assertEquals("Trending", SeerrClient.sliderTitle(SeerrSlider(1, 4)))
        assertEquals("My Row", SeerrClient.sliderTitle(SeerrSlider(1, 4, title = "My Row")))
        assertEquals("In Theaters", SeerrClient.sliderTitle(SeerrSlider(null, SeerrClient.IN_THEATERS_TYPE)))
    }

    @Test fun arrivalIsTheNextHomeReleaseElseCinema() {
        val today = LocalDate.of(2026, 11, 7)
        fun releases(vararg r: Pair<Int, String>) = JSONObject().put("results", org.json.JSONArray().put(JSONObject().put("iso_3166_1", "US")
            .put("release_dates", org.json.JSONArray().apply { r.forEach { put(JSONObject().put("type", it.first).put("release_date", it.second)) } })))
        assertEquals(LocalDate.of(2026, 12, 1) to "Digital Release", SeerrReleases.arrival(releases(3 to "2026-10-01T00:00:00.000Z", 4 to "2026-12-01T00:00:00.000Z"), "US", today))
        assertNull(SeerrReleases.arrival(releases(4 to "2026-11-01T00:00:00.000Z"), "US", today))
        assertEquals(LocalDate.of(2027, 1, 9) to "In Theaters", SeerrReleases.arrival(releases(3 to "2027-01-09T00:00:00.000Z"), "GB", today))
    }

    @Test fun requestStatesAndFailures() {
        val r = SeerrRequest.parse(JSONObject("""{"id":5,"status":2,"type":"tv","media":{"tmdbId":9,"mediaType":"tv","status":5},"seasons":[{"seasonNumber":1}],"requestedBy":{"id":3,"displayName":"Phil"}}"""))
        assertEquals("Available", r.stateLabel)
        assertEquals(listOf(1), r.seasons)
        assertTrue(SeerrException.from(404, """{"message":"Not available on this server."}""") is SeerrException.NotOnServer)
        assertTrue(SeerrException.from(502, "") is SeerrException.Unreachable)
        assertTrue(SeerrException.from(503, "") is SeerrException.Off)
        assertTrue(SeerrUser(1, null, 16).managesRequests)
        assertFalse(SeerrUser(1, null, 32).managesRequests)
    }

    @Test fun detailsCarryTheTrackingSitesIds() {
        val show = SeerrDetails.parse(JSONObject("""{"name":"Frieren","externalIds":{"imdbId":"tt22248376","tvdbId":424536}}"""))
        assertEquals("tt22248376", show.imdbId)
        assertEquals("424536", show.tvdbId)
        val film = SeerrDetails.parse(JSONObject("""{"title":"Dune","imdbId":"tt1160419","externalIds":{"tvdbId":null}}"""))
        assertEquals("tt1160419", film.imdbId)
        assertNull(film.tvdbId)
    }

    @Test fun featuresSayWhichWatchlistsCanBeAddedTo() {
        val f = com.astermediaplayer.astermediaplayer.data.TrackingFeatures.parse("""{"Services":["simkl"],"Watchlist":["simkl"]}""")
        assertEquals(listOf("simkl"), f.watchlist)
        assertTrue(com.astermediaplayer.astermediaplayer.data.TrackingFeatures.parse("""{"Services":[]}""").watchlist.isEmpty())
    }

    @Test fun libraryProviderIdsBecomeTheTrackingSitesNames() {
        val ids = com.astermediaplayer.astermediaplayer.ui.discover.WatchlistModel.ids(
            mapOf("Tmdb" to "1396", "Imdb" to "tt0903747", "Tvdb" to "81189", "AniList" to "21", "MyAnimeList" to "", "Zap2It" to "x", "AniDB" to null),
        )
        assertEquals(mapOf("tmdb" to "1396", "imdb" to "tt0903747", "tvdb" to "81189", "anilist" to "21"), ids)
    }
}
