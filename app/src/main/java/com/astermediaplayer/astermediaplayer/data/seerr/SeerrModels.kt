package com.astermediaplayer.astermediaplayer.data.seerr

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/*
 * Seerr (the joined Overseerr and Jellyseerr) as the Apple apps' Discover uses it (the Swift files in AsterFIN/Seerr, branch
 * aster-tracking): reached through the Aster Discovery and Tracking plugin on the Jellyfin server, as the signed-in person,
 * so Seerr's own permissions and quotas apply and no app signs in to Seerr. Seerr's JSON is camelCase.
 */

/** Where a title stands with Seerr (its MediaStatus). */
enum class SeerrStatus(val code: Int) {
    Unknown(1), Pending(2), Processing(3), PartiallyAvailable(4), Available(5), Blacklisted(6), Deleted(7);

    /** What its badge says; null for a title nobody's asked for. */
    val label: String?
        get() = when (this) {
            Unknown, Deleted -> null
            Pending -> "Requested"
            Processing -> "Processing"
            PartiallyAvailable -> "Partly Available"
            Available -> "Available"
            Blacklisted -> "Not Available"
        }

    /** Whether there's anything left to ask for. */
    val canRequest: Boolean get() = this == Unknown || this == Deleted || this == PartiallyAvailable

    companion object {
        fun of(code: Int?): SeerrStatus? = entries.firstOrNull { it.code == code }
    }
}

/** A film or show as Seerr lists it (from TMDB). */
data class SeerrTitle(
    val id: Int,
    /** "movie" or "tv". */
    val mediaType: String?,
    val title: String? = null,
    val name: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String? = null,
    val status: SeerrStatus = SeerrStatus.Unknown,
    val status4k: SeerrStatus = SeerrStatus.Unknown,
) {
    val isMovie: Boolean get() = mediaType == "movie"
    val displayTitle: String get() = title ?: name ?: ""
    val year: String? get() = (releaseDate ?: firstAirDate)?.takeIf { it.length >= 4 }?.take(4)
    val posterUrl: String? get() = SeerrImages.image(posterPath, "w342")
    /** Films and shows share TMDB ids, so a row holding both tells them apart by kind too. */
    val key: String get() = "${mediaType ?: "tv"}-$id"

    /** As Seerr sends it, for [parse] to read back: how Discover keeps a row between launches. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("mediaType", mediaType)
        title?.let { put("title", it) }; name?.let { put("name", it) }
        releaseDate?.let { put("releaseDate", it) }; firstAirDate?.let { put("firstAirDate", it) }
        posterPath?.let { put("posterPath", it) }; backdropPath?.let { put("backdropPath", it) }
        overview?.let { put("overview", it) }
        put("mediaInfo", JSONObject().put("status", status.code).put("status4k", status4k.code))
    }

    companion object {
        fun parse(o: JSONObject, kind: String? = null): SeerrTitle {
            val info = o.optJSONObject("mediaInfo")
            return SeerrTitle(
                id = o.optInt("id"),
                mediaType = kind ?: o.str("mediaType"),
                title = o.str("title"), name = o.str("name"),
                releaseDate = o.str("releaseDate"), firstAirDate = o.str("firstAirDate"),
                posterPath = o.str("posterPath"), backdropPath = o.str("backdropPath"),
                overview = o.str("overview"),
                status = SeerrStatus.of(info?.optInt("status")) ?: SeerrStatus.Unknown,
                status4k = SeerrStatus.of(info?.optInt("status4k")) ?: SeerrStatus.Unknown,
            )
        }
    }
}

/** A page of anything Seerr pages. */
data class SeerrPage<T>(val page: Int, val totalPages: Int, val results: List<T>)

/** One of Seerr's Discover rows, as its admin arranged them. */
data class SeerrSlider(val id: Int?, val type: Int, val title: String? = null, val data: String? = null, val enabled: Boolean = true) {
    val stableId: String get() = "$type|${id ?: 0}|${data.orEmpty()}"
}

/** The Seerr user behind the sign-in: who, and whether they manage requests. */
data class SeerrUser(val id: Int, val displayName: String?, val permissions: Int) {
    /** Seerr's ADMIN (2) or MANAGE_REQUESTS (16) permission. */
    val managesRequests: Boolean get() = permissions and (2 or 16) != 0
}

/** A request, as Seerr lists them. */
data class SeerrRequest(
    val id: Int,
    /** 1 pending approval, 2 approved, 3 declined, 4 failed, 5 completed. */
    val status: Int,
    val type: String?,
    val createdAt: String?,
    val is4k: Boolean,
    val tmdbId: Int,
    val mediaType: String?,
    val mediaStatus: SeerrStatus?,
    val seasons: List<Int>,
    val requestedById: Int?,
    val requestedByName: String?,
    val serverId: Int?,
    val profileId: Int?,
    val rootFolder: String?,
    val languageProfileId: Int?,
) {
    val isPending: Boolean get() = status == 1
    val kind: String get() = mediaType ?: type ?: "movie"
    val titleKey: String get() = "$kind-$tmdbId"

    /** Where it stands, as Seerr's own list says it. */
    val stateLabel: String
        get() = when (status) {
            1 -> "Pending Approval"
            3 -> "Declined"
            4 -> "Failed"
            else -> when (mediaStatus) {
                SeerrStatus.Available -> "Available"
                SeerrStatus.PartiallyAvailable -> "Partly Available"
                SeerrStatus.Processing -> "Processing"
                else -> if (status == 5) "Completed" else "Approved"
            }
        }

    /** The day it was made, from "2026-11-01T12:31:29.000Z". */
    val createdDay: LocalDate?
        get() = createdAt?.let { runCatching { java.time.Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull() }

    companion object {
        fun parse(o: JSONObject): SeerrRequest {
            val media = o.optJSONObject("media") ?: JSONObject()
            val by = o.optJSONObject("requestedBy")
            return SeerrRequest(
                id = o.optInt("id"), status = o.optInt("status"), type = o.str("type"), createdAt = o.str("createdAt"),
                is4k = o.optBoolean("is4k", false),
                tmdbId = media.optInt("tmdbId"), mediaType = media.str("mediaType"), mediaStatus = SeerrStatus.of(media.optInt("status")),
                seasons = o.optJSONArray("seasons").objects().map { it.optInt("seasonNumber") },
                requestedById = by?.optInt("id"), requestedByName = by?.str("displayName"),
                serverId = o.intOrNull("serverId"), profileId = o.intOrNull("profileId"), rootFolder = o.str("rootFolder"),
                languageProfileId = o.intOrNull("languageProfileId"),
            )
        }
    }
}

data class SeerrGenre(val id: Int, val name: String, val backdrops: List<String>)
data class SeerrCompany(val id: Int, val name: String, val logoPath: String?)
data class SeerrProvider(val id: Int, val name: String, val logoPath: String?, val displayPriority: Int?)

/** The requests list's choices, as Seerr's own. */
enum class SeerrRequestFilter(val wire: String, val label: String) {
    All("all", "All"), Pending("pending", "Pending"), Approved("approved", "Approved"),
    Processing("processing", "Processing"), Available("available", "Available"), Declined("unavailable", "Declined"),
}

/** A grid to open: a filter, with what to call it. */
data class SeerrBrowse(val title: String, val filter: SeerrFilter)

/** A Sonarr (shows) or Radarr (films) server set up in Seerr, with its defaults, the anime ones included. */
data class SeerrService(
    val id: Int, val name: String, val is4k: Boolean, val isDefault: Boolean,
    val activeProfileId: Int?, val activeDirectory: String?, val activeLanguageProfileId: Int?,
    val activeAnimeProfileId: Int?, val activeAnimeDirectory: String?, val activeAnimeLanguageProfileId: Int?,
) {
    companion object {
        fun parse(o: JSONObject) = SeerrService(
            id = o.optInt("id"), name = o.str("name").orEmpty(), is4k = o.optBoolean("is4k", false), isDefault = o.optBoolean("isDefault", false),
            activeProfileId = o.intOrNull("activeProfileId"), activeDirectory = o.str("activeDirectory"),
            activeLanguageProfileId = o.intOrNull("activeLanguageProfileId"), activeAnimeProfileId = o.intOrNull("activeAnimeProfileId"),
            activeAnimeDirectory = o.str("activeAnimeDirectory"), activeAnimeLanguageProfileId = o.intOrNull("activeAnimeLanguageProfileId"),
        )
    }
}

/** A server's choices: its quality profiles, folders and (Sonarr's) language profiles. */
data class SeerrServiceChoices(
    val profiles: List<Pair<Int, String>>,
    val rootFolders: List<Pair<String, Long?>>,
    val languageProfiles: List<Pair<Int, String>>,
)

/** Where a request goes: the server, quality, folder and language, and whether it's for the 4K copy. */
data class SeerrRequestOptions(
    val serverId: Int,
    val profileId: Int? = null,
    val rootFolder: String? = null,
    val languageProfileId: Int? = null,
    val is4k: Boolean = false,
) {
    fun addTo(body: JSONObject) {
        body.put("serverId", serverId)
        body.put("is4k", is4k)
        profileId?.let { body.put("profileId", it) }
        rootFolder?.let { body.put("rootFolder", it) }
        languageProfileId?.let { body.put("languageProfileId", it) }
    }

    companion object {
        /** The server's defaults, its anime ones for an anime show. */
        fun defaults(server: SeerrService, anime: Boolean) = SeerrRequestOptions(
            serverId = server.id,
            is4k = server.is4k,
            profileId = (if (anime) server.activeAnimeProfileId else null) ?: server.activeProfileId,
            rootFolder = (if (anime) server.activeAnimeDirectory?.ifEmpty { null } else null) ?: server.activeDirectory,
            languageProfileId = (if (anime) server.activeAnimeLanguageProfileId else null) ?: server.activeLanguageProfileId,
        )
    }
}

/** A film's or show's full details from Seerr. */
data class SeerrDetails(
    val title: String?, val name: String?, val tagline: String?, val overview: String?,
    val backdropPath: String?, val posterPath: String?, val releaseDate: String?, val firstAirDate: String?,
    val status: String?, val runtime: Int?, val numberOfSeasons: Int?, val numberOfEpisodes: Int?,
    val genres: List<String>, val networks: List<String>, val productionCompanies: List<String>, val createdBy: List<String>,
    val keywords: List<String>, val cast: List<CastMember>, val directors: List<String>,
    /** Seasons (not specials): number and episode count. */
    val seasons: List<Pair<Int, Int?>>,
    val seasonStatus: Map<Int, SeerrStatus>,
    val mediaStatus: SeerrStatus?, val mediaStatus4k: SeerrStatus?,
    val originalTitle: String?, val originalName: String?, val originalLanguage: String?,
    val voteAverage: Double?,
    /** The first trailer, else teaser (new anime often have only a teaser): its site, key and address. */
    val trailerSite: String?, val trailerKey: String?, val trailerUrl: String?,
    /** IMDb's id ("tt…") and TheTVDB's, from TMDB's external ids, for the tracking sites. */
    val imdbId: String? = null, val tvdbId: String? = null,
) {
    data class CastMember(val id: Int, val name: String, val character: String?, val profilePath: String?) {
        val photoUrl: String? get() = SeerrImages.image(profilePath, "w185")
    }

    /** Its own title, in its own language. */
    val original: String? get() = originalName ?: originalTitle
    /** A film's directors, else a show's creators. */
    val makers: List<String> get() = (directors.ifEmpty { createdBy }).distinct()
    /** Japanese animation, or tagged anime on TMDB (as Seerr decides when to use Sonarr's anime folder). */
    val isAnime: Boolean get() = (originalLanguage == "ja" && "Animation" in genres) || keywords.any { it.equals("anime", ignoreCase = true) }

    /** The trailer's YouTube id, when it's on YouTube. */
    val trailerYouTubeId: String?
        get() {
            if (trailerSite != null && trailerSite != "YouTube") return null
            return trailerKey ?: trailerUrl?.let { runCatching { android.net.Uri.parse(it).getQueryParameter("v") }.getOrNull() }
        }

    /** When it first came out, or comes out. */
    val date: LocalDate? get() = (releaseDate ?: firstAirDate)?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Seasons with where each stands, requests included. */
    fun seasonList(): List<SeasonState> = seasons.map { (n, eps) -> SeasonState(n, eps, seasonStatus[n] ?: SeerrStatus.Unknown) }

    data class SeasonState(val number: Int, val episodes: Int?, val status: SeerrStatus) {
        val open: Boolean get() = status == SeerrStatus.Unknown || status == SeerrStatus.Deleted
    }

    companion object {
        fun parse(o: JSONObject): SeerrDetails {
            val info = o.optJSONObject("mediaInfo")
            val credits = o.optJSONObject("credits")
            val external = o.optJSONObject("externalIds")
            val videos = o.optJSONArray("relatedVideos").objects()
            val trailer = videos.firstOrNull { it.str("type") == "Trailer" } ?: videos.firstOrNull { it.str("type") == "Teaser" }
            return SeerrDetails(
                title = o.str("title"), name = o.str("name"), tagline = o.str("tagline"), overview = o.str("overview"),
                backdropPath = o.str("backdropPath"), posterPath = o.str("posterPath"),
                releaseDate = o.str("releaseDate"), firstAirDate = o.str("firstAirDate"),
                status = o.str("status"), runtime = o.intOrNull("runtime"),
                numberOfSeasons = o.intOrNull("numberOfSeasons"), numberOfEpisodes = o.intOrNull("numberOfEpisodes"),
                genres = o.optJSONArray("genres").names(), networks = o.optJSONArray("networks").names(),
                productionCompanies = o.optJSONArray("productionCompanies").names(), createdBy = o.optJSONArray("createdBy").names(),
                keywords = o.optJSONArray("keywords").names(),
                cast = credits?.optJSONArray("cast").objects().map { CastMember(it.optInt("id"), it.str("name").orEmpty(), it.str("character"), it.str("profilePath")) },
                directors = credits?.optJSONArray("crew").objects().filter { it.str("job") == "Director" }.mapNotNull { it.str("name") },
                seasons = o.optJSONArray("seasons").objects().map { it.optInt("seasonNumber") to it.intOrNull("episodeCount") }.filter { it.first > 0 },
                seasonStatus = info?.optJSONArray("seasons").objects().associate { it.optInt("seasonNumber") to (SeerrStatus.of(it.optInt("status")) ?: SeerrStatus.Unknown) },
                mediaStatus = SeerrStatus.of(info?.optInt("status")), mediaStatus4k = SeerrStatus.of(info?.optInt("status4k")),
                originalTitle = o.str("originalTitle"), originalName = o.str("originalName"), originalLanguage = o.str("originalLanguage"),
                voteAverage = if (o.has("voteAverage") && !o.isNull("voteAverage")) o.optDouble("voteAverage") else null,
                trailerSite = trailer?.str("site"), trailerKey = trailer?.str("key"), trailerUrl = trailer?.str("url"),
                imdbId = (external?.str("imdbId") ?: o.str("imdbId"))?.ifBlank { null },
                tvdbId = external?.intOrNull("tvdbId")?.takeIf { it > 0 }?.toString(),
            )
        }
    }
}

/** A film the viewer asked for and got approved, not out yet: when it arrives and how. */
data class SeerrComingFilm(val title: SeerrTitle, val date: LocalDate, val how: String)

object SeerrReleases {
    /**
     * When a film can be watched at home and how, from TMDB's release dates (Seerr's `releases`): the first digital (4) or disc
     * (5) release still ahead; null once one's past. With none known, its cinema release (1–3) if that's ahead.
     */
    fun arrival(releases: JSONObject?, region: String, today: LocalDate): Pair<LocalDate, String>? {
        val countries = releases?.optJSONArray("results").objects()
        val country = countries.firstOrNull { it.str("iso_3166_1") == region } ?: countries.firstOrNull { it.str("iso_3166_1") == "US" }
        val dated = country?.optJSONArray("release_dates").objects().mapNotNull { r ->
            val day = r.str("release_date")?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            r.optInt("type") to day
        }
        val home = dated.filter { it.first == 4 || it.first == 5 }
        if (home.any { !it.second.isAfter(today) }) return null
        home.filter { it.second.isAfter(today) }.minByOrNull { it.second }?.let { return it.second to (if (it.first == 4) "Digital Release" else "On Disc") }
        dated.filter { it.first in 1..3 && it.second.isAfter(today) }.minByOrNull { it.second }?.let { return it.second to "In Theaters" }
        return null
    }
}

object SeerrImages {
    /** TMDB's picture at a size. */
    fun image(path: String?, width: String): String? = path?.takeIf { it.isNotEmpty() }?.let { "https://image.tmdb.org/t/p/$width$it" }

    /** A company's logo, white on the dark page. */
    fun logo(path: String?): String? = path?.takeIf { it.isNotEmpty() }?.let { "https://image.tmdb.org/t/p/w500_filter(duotone,ffffff,bababa)$it" }
}

internal fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name).ifEmpty { null }
internal fun JSONObject.intOrNull(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONArray?.names(): List<String> = objects().mapNotNull { it.str("name") }
