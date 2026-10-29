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
 * What a Plex library's title pages have already found, kept in one small JSON file so a show's page doesn't look it up again:
 * the openings and endings read from a show's episode chapters (Plex has no OP/ED plugin to ask) and the songs AnimeThemes names
 * for them, which takes seconds a time. An entry belongs to the server it was found on, and a show's clips to how many episodes it had,
 * so new episodes read the chapters again. Nothing here is a credential.
 */
class PlexThemeCache(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
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

    suspend fun themes(server: String, key: String): List<KnownTheme>? =
        read("$server|themes|$key") { entry ->
            val array = entry.optJSONArray("themes") ?: return@read null
            List(array.length()) { i -> theme(array.getJSONObject(i)) }
        }

    suspend fun putThemes(server: String, key: String, themes: List<KnownTheme>) =
        write("$server|themes|$key", "themes", themes.isEmpty(), JSONArray(themes.map(::json)))

    private suspend fun <T> read(key: String, decode: (JSONObject) -> T?): T? = lock.withLock {
        val entry = load().optJSONObject(key) ?: return@withLock null
        val age = now() - entry.optLong("at")
        if (age > if (entry.optBoolean("empty")) EMPTY_LIFE_MS else LIFE_MS) return@withLock null
        // An entry this version can't read (the models changed) is just not there.
        runCatching { decode(entry) }.getOrNull()
    }

    private suspend fun write(key: String, field: String, empty: Boolean, value: JSONArray) = lock.withLock {
        val all = load()
        all.put(key, JSONObject().put("at", now()).put("empty", empty).put(field, value))
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
        .put("startMs", clip.startMs).put("endMs", clip.endMs)
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
    )

    private fun json(theme: KnownTheme) = JSONObject()
        .put("isOpening", theme.isOpening).put("sequence", theme.sequence)
        .put("songTitle", theme.songTitle ?: JSONObject.NULL).put("artists", JSONArray(theme.artists))
        .put("episodes", theme.episodes ?: JSONObject.NULL)

    private fun theme(o: JSONObject) = KnownTheme(
        isOpening = o.getBoolean("isOpening"), sequence = o.getInt("sequence"),
        songTitle = if (o.isNull("songTitle")) null else o.getString("songTitle"),
        artists = o.getJSONArray("artists").let { a -> List(a.length()) { a.getString(it) } },
        episodes = if (o.isNull("episodes")) null else o.getString("episodes"),
    )

    private companion object {
        const val VERSION = 1
        const val MAX_ENTRIES = 400
        const val LIFE_MS = 30L * 24 * 60 * 60 * 1000
        // "Nothing found" may only be that AnimeThemes or a scan hasn't caught up, so it's asked again sooner.
        const val EMPTY_LIFE_MS = 3L * 24 * 60 * 60 * 1000
    }
}
