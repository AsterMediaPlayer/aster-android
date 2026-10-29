package com.astermediaplayer.astermediaplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

// The song card's details from AnimeThemes (animethemes.moe) when the server has no OP/ED plugin to give them: on Plex, on Emby, and
// on Jellyfin without the plugin. The Kotlin counterpart of the Apple apps' `KnownTheme` and `AnimeThemesLookup`.

/** One opening or ending, as AnimeThemes knows it. */
data class KnownTheme(
    val isOpening: Boolean,
    val sequence: Int,
    val songTitle: String?,
    val artists: List<String>,
    /** The episodes it plays in, as AnimeThemes writes them: "1-6, 8-". */
    val episodes: String?,
) {
    /** Whether it plays in episode [number]; any episode when unknown. */
    fun plays(number: Int?): Boolean {
        if (number == null || episodes.isNullOrBlank()) return true
        return ranges(episodes).any { number in it }
    }

    /** How many episodes it plays in; unknown, as good as all of them, so a theme listed for a few episodes comes before one for the whole show. */
    val episodeCount: Int
        get() {
            if (episodes.isNullOrBlank()) return Int.MAX_VALUE
            var total = 0
            for (range in ranges(episodes)) {
                // "8-": every episode from the eighth on.
                if (range.last == Int.MAX_VALUE) return Int.MAX_VALUE
                total += range.count()
            }
            return total
        }

    companion object {
        /** "1-6, 8-" as 1..6 and 8..Int.MAX_VALUE; "7" as 7..7. */
        fun ranges(text: String): List<IntRange> = text.split(",").mapNotNull { part ->
            val bounds = part.split("-").map { it.trim() }
            val low = bounds.first().toIntOrNull() ?: return@mapNotNull null
            if (bounds.size == 1) return@mapNotNull low..low
            val high = bounds[1].toIntOrNull() ?: Int.MAX_VALUE
            if (high >= low) low..high else null
        }
    }
}

object AnimeThemesLookup {
    private const val BASE = "https://api.animethemes.moe"
    private const val INCLUDE = "animethemes.song.performances.artist,animethemes.animethemeentries"

    /** What each show turned out to have, nothing included: each is asked about once per launch. */
    private val found = ConcurrentHashMap<String, List<KnownTheme>>()

    /** The themes of the show an episode is from: by its AniDB id, else by [seriesName] and the year the season began. */
    suspend fun themes(http: OkHttpClient, aniDbId: String?, seriesName: String, year: Int?): List<KnownTheme> {
        val key = aniDbId?.let { "anidb:$it" } ?: "name:${seriesName.lowercase()}|${year ?: 0}"
        found[key]?.let { return it }
        val themes = if (aniDbId != null) byAniDb(http, aniDbId) else byName(http, seriesName, year)
        // A failed lookup (offline) isn't remembered, so the next episode tries again.
        if (themes != null) found[key] = themes
        return themes.orEmpty()
    }

    private suspend fun byAniDb(http: OkHttpClient, id: String): List<KnownTheme>? {
        val reply = get(http, "/anime", mapOf("filter[has]" to "resources", "filter[site]" to "AniDB", "filter[external_id]" to id, "include" to INCLUDE)) ?: return null
        return reply.optJSONArray("anime")?.optJSONObject(0)?.let(::themes).orEmpty()
    }

    /**
     * The show that began in [year] (or the year before: a season running over New Year began in the earlier one) among those the
     * name finds; with no year, the first found. AnimeThemes names shows in Japanese, so the year, not the name, tells its seasons apart.
     */
    private suspend fun byName(http: OkHttpClient, name: String, year: Int?): List<KnownTheme>? {
        val search = get(http, "/search", mapOf("q" to name, "fields[search]" to "anime")) ?: return null
        val candidates = search.optJSONObject("search")?.optJSONArray("anime").objects()
        val pick = if (year != null) candidates.firstOrNull { it.optInt("year") == year } ?: candidates.firstOrNull { it.optInt("year") == year - 1 } else candidates.firstOrNull()
        val slug = pick?.optString("slug")?.ifEmpty { null } ?: return emptyList()
        val reply = get(http, "/anime/$slug", mapOf("include" to INCLUDE)) ?: return null
        return reply.optJSONObject("anime")?.let(::themes).orEmpty()
    }

    /** The openings and endings in an AnimeThemes `anime` object. */
    fun themes(anime: JSONObject): List<KnownTheme> = anime.optJSONArray("animethemes").objects().mapNotNull { theme ->
        val type = theme.optString("type")
        if (type != "OP" && type != "ED") return@mapNotNull null
        val song = theme.optJSONObject("song")
        val artists = song?.optJSONArray("performances").objects().mapNotNull { it.optJSONObject("artist")?.optString("name")?.ifEmpty { null } }
        val episodes = theme.optJSONArray("animethemeentries").objects().firstNotNullOfOrNull { e -> if (e.isNull("episodes")) null else e.optString("episodes").ifEmpty { null } }
        KnownTheme(
            isOpening = type == "OP", sequence = if (theme.isNull("sequence")) 1 else theme.optInt("sequence", 1),
            songTitle = if (song == null || song.isNull("title")) null else song.optString("title").ifEmpty { null },
            artists = artists, episodes = episodes,
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { optJSONObject(it) }.filterNotNull()

    /**
     * Null when the request failed (no connection, an error), so a failure isn't taken for "no themes". AnimeThemes often answers a
     * request with a 5xx or drops it, so a server error or a dropped connection is tried again (once more, after a pause); a 4xx isn't.
     */
    private suspend fun get(http: OkHttpClient, path: String, query: Map<String, String>): JSONObject? = withContext(Dispatchers.IO) {
        val url = (BASE + path).toHttpUrl().newBuilder().apply { query.toSortedMap().forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        for (attempt in 1..ATTEMPTS) {
            val outcome = runCatching {
                patient(http).newCall(request).execute().use { response ->
                    val body = response.body.string()
                    android.util.Log.d("Aster", "AnimeThemes $path -> ${response.code}")
                    when {
                        response.isSuccessful -> JSONObject(body)
                        response.code >= 500 -> RETRY
                        else -> null
                    }
                }
            }.onFailure { android.util.Log.w("Aster", "AnimeThemes $path failed (try $attempt)", it) }
            val result = outcome.getOrDefault(RETRY)
            if (result is JSONObject) return@withContext result
            if (result == null || attempt == ATTEMPTS) return@withContext null
            delay(RETRY_PAUSE_MS * attempt)
        }
        null
    }

    /** AnimeThemes takes 5 to 15 seconds to answer, past the app's usual 10, so its requests are given longer. */
    private fun patient(http: OkHttpClient): OkHttpClient = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    private val RETRY = Any()
    private const val ATTEMPTS = 2
    private const val RETRY_PAUSE_MS = 800L
}
