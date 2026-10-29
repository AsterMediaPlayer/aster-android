package com.astermediaplayer.astermediaplayer.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

/**
 * A show AnimeThemes names and the themes it has. One name finds a show's seasons, each its own: told apart by their AniDB id, which
 * a season on a server with the Shoko or AniDB provider carries, else by the year they began.
 */
data class ShowCandidate(val slug: String, val year: Int?, val themes: List<KnownTheme>, val aniDbIds: List<Int> = emptyList())

/** How a [AnimeThemesLookup.prefetch] went: the names it asked about, and whether it got to the end (a request failing ends it early). */
data class Warmed(val asked: Int, val complete: Boolean)

/** Where what AnimeThemes said about a name is kept between runs. */
interface ShowStore {
    suspend fun candidates(name: String): List<ShowCandidate>?
    suspend fun put(shows: Map<String, List<ShowCandidate>>)
}

object AnimeThemesLookup {
    private const val BASE = "https://api.animethemes.moe"
    private const val INCLUDE = "animethemes.song.performances.artist,animethemes.animethemeentries"

    /** What each show turned out to have, nothing included: each is asked about once per launch. */
    private val found = ConcurrentHashMap<String, List<KnownTheme>>()

    /** What a show's themes are remembered under: its AniDB id, else its name and the year its season began. */
    fun key(aniDbId: String?, seriesName: String, year: Int?): String = aniDbId?.let { "anidb:$it" } ?: "name:${seriesName.lowercase()}|${year ?: 0}"

    /** The themes of the show an episode is from: by its AniDB id, else by [seriesName] and the year the season began. */
    suspend fun themes(http: OkHttpClient, aniDbId: String?, seriesName: String, year: Int?, store: ShowStore? = null): List<KnownTheme> =
        lookup(http, aniDbId, seriesName, year, store).orEmpty()

    /** [themes], or null when the lookup failed (offline, an error), which isn't the same as a show with none. */
    suspend fun lookup(http: OkHttpClient, aniDbId: String?, seriesName: String, year: Int?, store: ShowStore? = null): List<KnownTheme>? {
        val key = key(aniDbId, seriesName, year)
        found[key]?.let { return it }
        // A season's AniDB id is found among the shows its name found (kept, or got ready in the background) before it's asked by id.
        val themes = if (aniDbId != null) (keptByAniDb(seriesName, aniDbId, store) ?: byAniDb(http, aniDbId)) else byName(http, seriesName, year, store)
        // A failed lookup (offline) isn't remembered, so the next episode tries again.
        if (themes != null) found[key] = themes
        return themes
    }

    /** The themes of the season with this AniDB id among what [name] already found; null when nothing is kept for the name or it isn't there. */
    private suspend fun keptByAniDb(name: String, aniDbId: String, store: ShowStore?): List<KnownTheme>? {
        val id = aniDbId.trim().toIntOrNull() ?: return null
        val key = nameKey(name)
        val shown = shows[key]?.takeIf { it.isCompleted }?.await()
            ?: store?.candidates(name)?.also { shows.putIfAbsent(key, CompletableDeferred(it)) }
        return shown?.firstOrNull { id in it.aniDbIds }?.themes
    }

    private suspend fun byAniDb(http: OkHttpClient, id: String): List<KnownTheme>? {
        val reply = get(http, "/anime", mapOf("filter[has]" to "resources", "filter[site]" to "AniDB", "filter[external_id]" to id, "include" to INCLUDE)) ?: return null
        return reply.optJSONArray("anime")?.optJSONObject(0)?.let(::themes).orEmpty()
    }

    /**
     * The show that began in [year] (or the year before: a season running over New Year began in the earlier one) among those the
     * name finds; with no year, the first found. AnimeThemes names shows in Japanese, so the year, not the name, tells its seasons apart.
     */
    private suspend fun byName(http: OkHttpClient, name: String, year: Int?, store: ShowStore?): List<KnownTheme>? {
        val candidates = candidatesFor(http, name, store) ?: return null
        val pick = if (year != null) candidates.firstOrNull { it.year == year } ?: candidates.firstOrNull { it.year == year - 1 } else candidates.firstOrNull()
        return pick?.themes.orEmpty()
    }

    // MARK: By name, in batches

    private const val GRAPHQL = "https://graphql.animethemes.moe"

    /** Shows to a request: AnimeThemes asks for lookups in batches of 50 where they can be. */
    const val BATCH = 50
    private const val SELECTION = "anime{slug year resources{nodes{site externalId}} animethemes{type sequence song{title{romaji} performances{artist{name{main}}}} animethemeentries{episodes}}}"

    /** What each name found (every season of the show, with its themes), asked of AnimeThemes once and shared by whoever wants it. */
    private val shows = ConcurrentHashMap<String, CompletableDeferred<List<ShowCandidate>?>>()

    /** Forgets what this run has found, for a refresh to ask again. */
    fun reset() { shows.clear(); found.clear() }

    fun nameKey(name: String): String = name.trim().lowercase()

    /** One name, from this run, then [store], then AnimeThemes (alone, as a batch of one); null when AnimeThemes couldn't be asked. */
    private suspend fun candidatesFor(http: OkHttpClient, name: String, store: ShowStore?): List<ShowCandidate>? {
        val key = nameKey(name)
        val mine = CompletableDeferred<List<ShowCandidate>?>()
        shows.putIfAbsent(key, mine)?.let { return it.await() }
        val found = try {
            store?.candidates(name) ?: fetch(http, listOf(name))?.single()?.also { store?.put(mapOf(name to it)) }
        } catch (e: Throwable) {
            shows.remove(key, mine); mine.cancel(); throw e
        }
        if (found == null) shows.remove(key, mine)
        mine.complete(found)
        return found
    }

    /**
     * Gets ready the names that no lookup has found yet, [BATCH] of them to a request and a pause between requests, for a library's
     * shows to be found before their pages are opened. What [store] already holds isn't asked again. A request that fails ends it: the
     * next run goes on from there.
     */
    suspend fun prefetch(http: OkHttpClient, names: List<String>, store: ShowStore?, pauseMs: Long = 4_000): Warmed {
        val toAsk = mutableListOf<String>()
        val seen = HashSet<String>()
        for (name in names) {
            val key = nameKey(name)
            if (key.isEmpty() || !seen.add(key) || shows.containsKey(key)) continue
            val saved = store?.candidates(name)
            if (saved != null) shows.putIfAbsent(key, CompletableDeferred(saved)) else toAsk += name
        }
        var asked = 0
        for ((i, chunk) in toAsk.chunked(BATCH).withIndex()) {
            if (i > 0) delay(pauseMs)
            val answers = fetch(http, chunk) ?: return Warmed(asked, complete = false)
            val got = chunk.zip(answers).mapNotNull { (name, shows) -> shows?.let { name to it } }.toMap()
            store?.put(got)
            for ((name, found) in got) shows.putIfAbsent(nameKey(name), CompletableDeferred(found))
            asked += chunk.size
        }
        return Warmed(asked, complete = true)
    }

    /** The query for [count] names, each its own `search` (`s0`, `s1`...) in the one request; the names go as variables. */
    fun query(count: Int): String =
        "query(" + (0 until count).joinToString(",") { "\$n$it:String!" } + "){" + (0 until count).joinToString(" ") { "s$it:search(search:\$n$it,first:5){$SELECTION}" } + "}"

    /** What each of [count] names found, in order: null for one the reply left out. */
    fun parse(data: JSONObject, count: Int): List<List<ShowCandidate>?> = List(count) { i ->
        data.optJSONObject("s$i")?.optJSONArray("anime")?.let { anime ->
            anime.objects().map { show ->
                ShowCandidate(
                    show.optString("slug"), if (show.isNull("year")) null else show.optInt("year"), show.optJSONArray("animethemes").objects().mapNotNull(::graphTheme),
                    aniDbIds = show.optJSONObject("resources")?.optJSONArray("nodes").objects().filter { it.optString("site") == "ANIDB" && !it.isNull("externalId") }.map { it.optInt("externalId") },
                )
            }
        }
    }

    private fun graphTheme(theme: JSONObject): KnownTheme? {
        val type = theme.optString("type")
        if (type != "OP" && type != "ED") return null
        val song = theme.optJSONObject("song")
        val title = song?.optJSONObject("title")?.optString("romaji")?.ifEmpty { null }
        val artists = song?.optJSONArray("performances").objects().mapNotNull { it.optJSONObject("artist")?.optJSONObject("name")?.optString("main")?.ifEmpty { null } }
        val episodes = theme.optJSONArray("animethemeentries").objects().firstNotNullOfOrNull { e -> if (e.isNull("episodes")) null else e.optString("episodes").ifEmpty { null } }
        return KnownTheme(isOpening = type == "OP", sequence = if (theme.isNull("sequence")) 1 else theme.optInt("sequence", 1), songTitle = title, artists = artists, episodes = episodes)
    }

    private suspend fun fetch(http: OkHttpClient, names: List<String>): List<List<ShowCandidate>?>? {
        val body = JSONObject().put("query", query(names.size)).put("variables", JSONObject().also { v -> names.forEachIndexed { i, n -> v.put("n$i", n) } })
        val request = Request.Builder().url(GRAPHQL).header("Accept", "application/json").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val reply = execute(http, request, "GraphQL x${names.size}", seconds = 90) ?: return null
        // A reply that is only errors (the query is refused) isn't an answer; one with some names missing is.
        val data = reply.optJSONObject("data") ?: return null
        return parse(data, names.size)
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
    private suspend fun get(http: OkHttpClient, path: String, query: Map<String, String>): JSONObject? {
        val url = (BASE + path).toHttpUrl().newBuilder().apply { query.toSortedMap().forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return execute(http, Request.Builder().url(url).header("Accept", "application/json").build(), path)
    }

    private suspend fun execute(http: OkHttpClient, request: Request, label: String, seconds: Long = 30): JSONObject? = withContext(Dispatchers.IO) {
        for (attempt in 1..ATTEMPTS) {
            val outcome = runCatching {
                patient(http, seconds).newCall(request).execute().use { response ->
                    val body = response.body.string()
                    android.util.Log.d("Aster", "AnimeThemes $label -> ${response.code}")
                    when {
                        response.isSuccessful -> JSONObject(body)
                        response.code >= 500 -> RETRY
                        else -> null
                    }
                }
            }.onFailure { android.util.Log.w("Aster", "AnimeThemes $label failed (try $attempt)", it) }
            val result = outcome.getOrDefault(RETRY)
            if (result is JSONObject) return@withContext result
            if (result == null || attempt == ATTEMPTS) return@withContext null
            delay(RETRY_PAUSE_MS * attempt)
        }
        null
    }

    /** AnimeThemes takes 5 to 15 seconds to answer, past the app's usual 10, so its requests are given longer (a batch of 50 shows takes about 10). */
    private fun patient(http: OkHttpClient, seconds: Long): OkHttpClient = http.newBuilder().readTimeout(seconds, TimeUnit.SECONDS).callTimeout(seconds + 15, TimeUnit.SECONDS).build()

    private val RETRY = Any()
    private const val ATTEMPTS = 2
    private const val RETRY_PAUSE_MS = 800L
}
