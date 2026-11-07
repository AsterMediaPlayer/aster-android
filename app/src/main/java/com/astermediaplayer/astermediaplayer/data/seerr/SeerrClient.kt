package com.astermediaplayer.astermediaplayer.data.seerr

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** What a failing answer through the plugin means. */
sealed class SeerrException(message: String) : Exception(message) {
    /** The server turned the Jellyfin sign-in down (401). */
    class SignedOut : SeerrException("Your sign-in to the server has run out.")
    class Refused(message: String?) : SeerrException(message ?: "Seerr turned that down.")
    class Http(val code: Int) : SeerrException("Seerr answered $code.")
    /** The server's admin has switched requests off (503). */
    class Off : SeerrException("Requests are turned off on this server.")
    /** The server couldn't reach Seerr (502): never "sign in". */
    class Unreachable : SeerrException("Requests aren't available right now.")
    /** A title the admin keeps out of Discover (404). */
    class NotOnServer(message: String) : SeerrException(message)

    companion object {
        fun from(status: Int, body: String): SeerrException {
            val message = runCatching { JSONObject(body).str("message") }.getOrNull()
            return when {
                status == 401 -> SignedOut()
                status == 404 && message?.contains("Not available on this server", ignoreCase = true) == true -> NotOnServer(message)
                status == 502 -> Unreachable()
                status == 503 -> Off()
                status == 400 || status == 403 || status == 409 -> Refused(message)
                else -> Http(status)
            }
        }
    }
}

/** Seerr's API through the server's plugin: [send] makes a call on one of Seerr's own `/api/v1` paths. */
class SeerrClient(private val send: suspend (method: String, path: String, body: String?) -> Pair<Int, String>) {

    private suspend fun get(path: String): String {
        val (status, body) = send("GET", path, null)
        if (status != 200) throw SeerrException.from(status, body)
        return body
    }

    private suspend fun act(method: String, path: String, body: JSONObject? = null) {
        val (status, reply) = send(method, path, body?.toString())
        if (status !in 200..299) throw SeerrException.from(status, reply)
    }

    suspend fun me(): SeerrUser = JSONObject(get("/auth/me")).let { SeerrUser(it.optInt("id"), it.str("displayName"), it.optInt("permissions")) }

    // MARK: Discover rows

    /** The Discover rows, enabled ones only, in the admin's order. */
    suspend fun sliders(): List<SeerrSlider> = JSONArray(get("/settings/discover")).objects()
        .map { SeerrSlider(it.intOrNull("id"), it.optInt("type"), it.str("title"), it.str("data"), it.optBoolean("enabled", true)) }
        .filter { it.enabled }

    /** A page of titles from any of Discover's lists, people left out, each knowing whether it's a film or a show. */
    suspend fun titles(path: String, kind: SeerrFilter.Kind?): SeerrPage<SeerrTitle> {
        val o = JSONObject(get(path))
        val results = o.optJSONArray("results").objects().map { SeerrTitle.parse(it, kind?.wire) }
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
        return SeerrPage(o.optInt("page", 1), o.optInt("totalPages", 1), results)
    }

    /** Each (TMDB id, kind) as a title, asked side by side, in order; those that fail are left out. */
    suspend fun titlesFor(ids: List<Pair<Int, String>>): List<SeerrTitle> = coroutineScope {
        ids.map { (id, kind) ->
            async { runCatching { SeerrTitle.parse(JSONObject(get(if (kind == "movie") "/movie/$id" else "/tv/$id")), kind) }.getOrNull() }
        }.awaitAll().filterNotNull()
    }

    /** What arrived on the server lately, newest first, as titles. */
    suspend fun recentlyAdded(): List<SeerrTitle> {
        val media = JSONObject(get("/media?filter=allavailable&take=20&skip=0&sort=mediaAdded")).optJSONArray("results").objects()
        return titlesFor(media.mapNotNull { m -> m.str("mediaType")?.let { m.optInt("tmdbId") to it } })
    }

    /**
     * Films in cinemas now, as near as Seerr's filters come (it passes on no theatrical-release filter): out in the last six
     * weeks with some votes, most popular first, less those already on the region's big streaming services.
     */
    suspend fun inTheaters(region: String = defaultRegion(), today: LocalDate = LocalDate.now()): List<SeerrTitle> = coroutineScope {
        val recent = "/discover/movies?sortBy=popularity.desc&primaryReleaseDateGte=${today.minusDays(42)}&primaryReleaseDateLte=$today&voteCountGte=20"
        val services = providers(SeerrFilter.Kind.Movie, region).take(12).map { it.id.toString() }
        val shown = async { pages(recent, 3) }
        val streaming = if (services.isEmpty()) emptySet() else pages(recent + "&watchRegion=$region&watchProviders=${services.joinToString("|")}", 5).map { it.id }.toSet()
        shown.await().filter { it.id !in streaming }
    }

    private suspend fun pages(path: String, upTo: Int): List<SeerrTitle> {
        val found = mutableListOf<SeerrTitle>()
        for (page in 1..upTo) {
            val reply = titles("$path&page=$page", SeerrFilter.Kind.Movie)
            found += reply.results
            if (page >= reply.totalPages) break
        }
        return found
    }

    suspend fun genres(kind: SeerrFilter.Kind): List<SeerrGenre> = JSONArray(get("/discover/genreslider/${kind.wire}")).objects().map { g ->
        SeerrGenre(g.optInt("id"), g.str("name").orEmpty(), g.optJSONArray("backdrops")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } } }.orEmpty())
    }

    /** Studios (films) or networks (shows), with their logos, as Seerr's own Discover page shows them. */
    suspend fun companies(kind: SeerrFilter.Kind): List<SeerrCompany> = coroutineScope {
        val ids = if (kind == SeerrFilter.Kind.Movie) STUDIO_IDS else NETWORK_IDS
        val route = if (kind == SeerrFilter.Kind.Movie) "studio" else "network"
        ids.map { id -> async { runCatching { JSONObject(get("/$route/$id")).let { SeerrCompany(it.optInt("id"), it.str("name").orEmpty(), it.str("logoPath")) } }.getOrNull() } }
            .awaitAll().filterNotNull()
    }

    suspend fun providers(kind: SeerrFilter.Kind, region: String): List<SeerrProvider> =
        JSONArray(get("/watchproviders/${if (kind == SeerrFilter.Kind.Movie) "movies" else "tv"}?watchRegion=$region")).objects()
            .map { SeerrProvider(it.optInt("id"), it.str("name").orEmpty(), it.str("logoPath"), it.intOrNull("displayPriority")) }
            .sortedBy { it.displayPriority ?: 999 }

    // MARK: Search and Anime

    suspend fun search(query: String): List<SeerrTitle> =
        JSONObject(get("/search?query=${SeerrFilter.encode(query)}&page=1")).optJSONArray("results").objects()
            .map { SeerrTitle.parse(it) }.filter { it.mediaType == "movie" || it.mediaType == "tv" }

    /** Anime starting or airing around now: Japanese animation first airing from the first of last month to six months on, newest first. */
    suspend fun upcomingAnime(today: LocalDate = LocalDate.now()): List<SeerrTitle> {
        val from = today.withDayOfMonth(1).minusMonths(1)
        val to = from.plusMonths(6)
        val found = mutableListOf<SeerrTitle>()
        for (page in 1..2) {
            val reply = titles("/discover/tv?language=server%7Cja&sortBy=first_air_date.desc&firstAirDateGte=$from&firstAirDateLte=$to&genre=16&page=$page", SeerrFilter.Kind.Tv)
            found += reply.results
            if (page >= reply.totalPages) break
        }
        return found
    }

    // MARK: A title

    suspend fun details(title: SeerrTitle): SeerrDetails = SeerrDetails.parse(JSONObject(get(if (title.isMovie) "/movie/${title.id}" else "/tv/${title.id}")))

    /** Asks for a film, or for these seasons of a show; [options] where an admin chose it to go. */
    suspend fun request(title: SeerrTitle, seasons: List<Int>, options: SeerrRequestOptions?) {
        val body = JSONObject().put("mediaType", if (title.isMovie) "movie" else "tv").put("mediaId", title.id)
        if (!title.isMovie) body.put("seasons", JSONArray(seasons))
        options?.addTo(body)
        act("POST", "/request", body)
    }

    // MARK: Requests

    /** A page of requests: everyone's for someone who manages them, else the viewer's own. */
    suspend fun requests(filter: SeerrRequestFilter, mine: Boolean, userId: Int, page: Int = 1): SeerrPage<SeerrRequest> {
        val take = 20
        var path = "/request?take=$take&skip=${(page - 1) * take}&filter=${filter.wire}&sort=added"
        if (mine) path += "&requestedBy=$userId"
        val o = JSONObject(get(path))
        val info = o.optJSONObject("pageInfo")
        return SeerrPage(info?.optInt("page", page) ?: page, info?.optInt("pages", 1) ?: 1, o.optJSONArray("results").objects().map(SeerrRequest::parse))
    }

    suspend fun approve(request: SeerrRequest) = act("POST", "/request/${request.id}/approve")
    suspend fun decline(request: SeerrRequest) = act("POST", "/request/${request.id}/decline")
    /** Withdraws a request not yet approved. */
    suspend fun cancel(request: SeerrRequest) = act("DELETE", "/request/${request.id}")

    /** Changes where a pending request goes (an admin's Edit). */
    suspend fun update(request: SeerrRequest, options: SeerrRequestOptions) {
        val body = JSONObject().put("mediaType", request.kind)
        if (request.kind == "tv") body.put("seasons", JSONArray(request.seasons))
        options.addTo(body)
        act("PUT", "/request/${request.id}", body)
    }

    /** The viewer's approved film requests not out yet, soonest first. */
    suspend fun comingFilms(userId: Int, region: String = defaultRegion(), today: LocalDate = LocalDate.now()): List<SeerrComingFilm> = coroutineScope {
        val requests = requests(SeerrRequestFilter.Approved, mine = true, userId = userId).results
            .filter { it.type == "movie" && it.mediaStatus != SeerrStatus.Available }
        requests.map { r ->
            async {
                runCatching {
                    val o = JSONObject(get("/movie/${r.tmdbId}"))
                    SeerrReleases.arrival(o.optJSONObject("releases"), region, today)?.let { (day, how) -> SeerrComingFilm(SeerrTitle.parse(o, "movie"), day, how) }
                }.getOrNull()
            }
        }.awaitAll().filterNotNull().sortedBy { it.date }
    }

    // MARK: Where a request goes

    /** The Sonarr (tv) or Radarr (movie) servers, the usual ones first, then the 4K ones; only one sort when asked. */
    suspend fun services(kind: SeerrFilter.Kind, fourK: Boolean? = null): List<SeerrService> =
        JSONArray(get(if (kind == SeerrFilter.Kind.Tv) "/service/sonarr" else "/service/radarr")).objects().map(SeerrService::parse)
            .filter { fourK == null || it.is4k == fourK }
            .sortedBy { if (it.is4k) 1 else 0 }

    suspend fun serviceChoices(kind: SeerrFilter.Kind, server: Int): SeerrServiceChoices {
        val o = JSONObject(get(if (kind == SeerrFilter.Kind.Tv) "/service/sonarr/$server" else "/service/radarr/$server"))
        return SeerrServiceChoices(
            profiles = o.optJSONArray("profiles").objects().map { it.optInt("id") to it.str("name").orEmpty() },
            rootFolders = o.optJSONArray("rootFolders").objects().mapNotNull { f -> f.str("path")?.let { it to (if (f.has("freeSpace") && !f.isNull("freeSpace")) f.optLong("freeSpace") else null) } },
            languageProfiles = o.optJSONArray("languageProfiles").objects().map { it.optInt("id") to it.str("name").orEmpty() },
        )
    }

    companion object {
        /** Discover's own row, not one of Seerr's: films in cinemas now. */
        const val IN_THEATERS_TYPE = 1000

        val STUDIO_IDS = listOf(2, 127928, 34, 174, 33, 4, 3, 521, 420, 9993, 41077)
        val NETWORK_IDS = listOf(213, 2739, 1024, 2552, 453, 49, 4353, 2, 19, 6, 16, 56, 1112, 4330)

        fun defaultRegion(): String = java.util.Locale.getDefault().country.ifEmpty { "US" }

        /** A row's name: the admin's, else Seerr's own for a built-in row. */
        fun sliderTitle(slider: SeerrSlider): String = slider.title?.ifEmpty { null } ?: when (slider.type) {
            1 -> "Recently Added"
            2 -> "Recent Requests"
            3 -> "Watchlist"
            4 -> "Trending"
            5 -> "Popular Movies"
            6 -> "Movie Genres"
            7 -> "Upcoming Movies"
            8 -> "Studios"
            9 -> "Popular Series"
            10 -> "Series Genres"
            11 -> "Upcoming Series"
            12 -> "Networks"
            IN_THEATERS_TYPE -> "In Theaters"
            else -> "More to Discover"
        }

        /** The titles in a row of titles; null for a row of genres or companies. */
        fun titlesPath(slider: SeerrSlider): Pair<String, SeerrFilter.Kind?>? {
            // A list of ids ("28,12") needs its commas encoded for Seerr.
            val data = slider.data.orEmpty().replace(",", "%2C")
            return when (slider.type) {
                3 -> "/discover/watchlist?page=1" to null
                4 -> "/discover/trending?page=1" to null
                5 -> "/discover/movies?page=1" to SeerrFilter.Kind.Movie
                7 -> "/discover/movies/upcoming?page=1" to SeerrFilter.Kind.Movie
                9 -> "/discover/tv?page=1" to SeerrFilter.Kind.Tv
                11 -> "/discover/tv/upcoming?page=1" to SeerrFilter.Kind.Tv
                13 -> "/discover/movies?page=1&keywords=$data" to SeerrFilter.Kind.Movie
                14 -> "/discover/movies?page=1&genre=$data" to SeerrFilter.Kind.Movie
                15 -> "/discover/tv?page=1&keywords=$data" to SeerrFilter.Kind.Tv
                16 -> "/discover/tv?page=1&genre=$data" to SeerrFilter.Kind.Tv
                17 -> "/search?query=${SeerrFilter.encode(slider.data.orEmpty())}&page=1" to null
                18 -> "/discover/movies/studio/$data?page=1" to SeerrFilter.Kind.Movie
                19 -> "/discover/tv/network/$data?page=1" to SeerrFilter.Kind.Tv
                20, 21 -> {
                    // "US,8|337": the region, then the services.
                    val parts = slider.data.orEmpty().split(",", limit = 2)
                    val region = if (parts.size == 2) parts[0] else "US"
                    val services = parts.last()
                    if (slider.type == 20) "/discover/movies?page=1&watchRegion=$region&watchProviders=$services" to SeerrFilter.Kind.Movie
                    else "/discover/tv?page=1&watchRegion=$region&watchProviders=$services" to SeerrFilter.Kind.Tv
                }
                else -> null
            }
        }
    }
}
