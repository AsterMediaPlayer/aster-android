package com.astermediaplayer.astermediaplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.BaseItemDto
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * What a library's title pages have already found, kept in one small JSON file so a show's page doesn't look it up again, on a
 * server with no OP/ED plugin to ask (Plex, Emby, and Jellyfin without the plugin): the openings and endings read from a show's
 * episode chapters, and the songs AnimeThemes names for each show's name, which takes seconds a time (and is asked of it 50 shows
 * to a request, in the background). An entry belongs to the server it was found on, and a show's clips to how many episodes it had,
 * so new episodes read the chapters again. Nothing here is a credential.
 */
class SongThemeCache(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()
    private var entries: JSONObject? = null
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun clips(server: String, seriesId: UUID, version: Int?): List<ThemeClip>? =
        read("$server|clips|$seriesId|${version ?: 0}") { entry ->
            val array = entry.optJSONArray("clips") ?: return@read null
            List(array.length()) { i -> clip(array.getJSONObject(i)) }
        }

    suspend fun putClips(server: String, seriesId: UUID, version: Int?, clips: List<ThemeClip>) =
        write("$server|clips|$seriesId|${version ?: 0}", "clips", clips.isEmpty(), JSONArray(clips.map(::json)))

    /** What AnimeThemes said about [name]: every season of the show with its themes. */
    suspend fun shows(server: String, name: String): List<ShowCandidate>? =
        read("$server|shows|${AnimeThemesLookup.nameKey(name)}") { entry ->
            val array = entry.optJSONArray("shows") ?: return@read null
            List(array.length()) { i -> show(array.getJSONObject(i)) }
        }

    /** Several names at once, in one write of the file. */
    suspend fun putShows(server: String, found: Map<String, List<ShowCandidate>>) {
        if (found.isEmpty()) return
        write(found.entries.associate { (name, shows) -> "$server|shows|${AnimeThemesLookup.nameKey(name)}" to Entry("shows", shows.isEmpty(), JSONArray(shows.map(::json))) })
    }

    private class Entry(val field: String, val empty: Boolean, val value: JSONArray)

    /** Forgets everything kept for [server], so the next visit finds it all again. */
    suspend fun clear(server: String) = lock.withLock {
        val all = load()
        all.keys().asSequence().filter { it.startsWith("$server|") }.toList().forEach { all.remove(it) }
        withContext(Dispatchers.IO) {
            runCatching {
                val temporary = File(file.parentFile, file.name + ".tmp")
                temporary.writeText(JSONObject().put("version", VERSION).put("entries", all).toString())
                if (!temporary.renameTo(file)) { file.delete(); temporary.renameTo(file) }
            }
        }
    }

    private suspend fun <T> read(key: String, decode: (JSONObject) -> T?): T? = lock.withLock {
        val entry = load().optJSONObject(key) ?: return@withLock null
        val age = now() - entry.optLong("at")
        if (age > if (entry.optBoolean("empty")) EMPTY_LIFE_MS else LIFE_MS) return@withLock null
        // An entry this version can't read (the models changed) is just not there.
        runCatching { decode(entry) }.getOrNull()
    }

    private suspend fun write(key: String, field: String, empty: Boolean, value: JSONArray) = write(mapOf(key to Entry(field, empty, value)))

    private suspend fun write(add: Map<String, Entry>) = lock.withLock {
        val all = load()
        val at = now()
        for ((key, entry) in add) all.put(key, JSONObject().put("at", at).put("empty", entry.empty).put(entry.field, entry.value))
        // The oldest go first, so the file stays small.
        if (all.length() > MAX_ENTRIES) {
            val oldest = all.keys().asSequence().sortedBy { all.getJSONObject(it).optLong("at") }.take(all.length() - MAX_ENTRIES).toList()
            oldest.forEach { all.remove(it) }
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val temporary = File(file.parentFile, file.name + ".tmp")
                temporary.writeText(JSONObject().put("version", VERSION).put("entries", all).toString())
                if (!temporary.renameTo(file)) { file.delete(); temporary.renameTo(file) }
            }
        }
    }

    private suspend fun load(): JSONObject = entries ?: withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt("version") == VERSION) root.getJSONObject("entries") else JSONObject()
        }.getOrDefault(JSONObject())
    }.also { entries = it }

    private fun json(clip: ThemeClip) = JSONObject()
        .put("opening", clip.opening).put("season", clip.season).put("number", clip.number)
        .put("startMs", clip.startMs).put("endMs", clip.endMs).apply { clip.picture?.let { put("picture", it) } }
        .put("episode", json.encodeToString(BaseItemDto.serializer(), slim(clip.episode)))

    /**
     * An episode as a clip's card needs it (names, numbers, pictures). Its streams and chapters are most of what Plex sends, and are
     * asked for again when the clip plays.
     */
    private fun slim(episode: BaseItemDto) = episode.copy(mediaSources = null, mediaStreams = null, chapters = null, people = null, userData = null)

    private fun clip(o: JSONObject) = ThemeClip(
        opening = o.getBoolean("opening"), season = o.getInt("season"), number = o.getInt("number"),
        episode = json.decodeFromString(BaseItemDto.serializer(), o.getString("episode")),
        startMs = o.getLong("startMs"), endMs = o.getLong("endMs"),
        picture = if (o.has("picture") && !o.isNull("picture")) o.getString("picture") else null,
    )

    private fun json(theme: KnownTheme) = JSONObject()
        .put("isOpening", theme.isOpening).put("sequence", theme.sequence)
        .put("songTitle", theme.songTitle ?: JSONObject.NULL).put("artists", JSONArray(theme.artists))
        .put("episodes", theme.episodes ?: JSONObject.NULL)

    private fun json(show: ShowCandidate) = JSONObject()
        .put("slug", show.slug).put("year", show.year ?: JSONObject.NULL).put("themes", JSONArray(show.themes.map(::json))).put("aniDb", JSONArray(show.aniDbIds))

    private fun show(o: JSONObject) = ShowCandidate(
        slug = o.getString("slug"), year = if (o.isNull("year")) null else o.getInt("year"),
        themes = o.getJSONArray("themes").let { a -> List(a.length()) { theme(a.getJSONObject(it)) } },
        aniDbIds = o.optJSONArray("aniDb")?.let { a -> List(a.length()) { a.getInt(it) } }.orEmpty(),
    )

    private fun theme(o: JSONObject) = KnownTheme(
        isOpening = o.getBoolean("isOpening"), sequence = o.getInt("sequence"),
        songTitle = if (o.isNull("songTitle")) null else o.getString("songTitle"),
        artists = o.getJSONArray("artists").let { a -> List(a.length()) { a.getString(it) } },
        episodes = if (o.isNull("episodes")) null else o.getString("episodes"),
    )

    private companion object {
        // 2: Emby's "Intro Start" clips end at "Intro End", so clips found before are wrong. 3: Plex clips keep their chapter's picture.
        const val VERSION = 3
        const val MAX_ENTRIES = 1500
        const val LIFE_MS = 30L * 24 * 60 * 60 * 1000
        // "Nothing found" may only be that AnimeThemes or a scan hasn't caught up, so it's asked again sooner.
        const val EMPTY_LIFE_MS = 3L * 24 * 60 * 60 * 1000
    }
}
