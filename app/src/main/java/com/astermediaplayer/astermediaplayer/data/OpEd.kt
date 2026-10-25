package com.astermediaplayer.astermediaplayer.data

import org.jellyfin.sdk.model.api.BaseItemDto
import org.json.JSONArray
import org.json.JSONObject

// The OP/ED server plugin (`/OpEd/...`) and the chapter-based fallback, the Kotlin counterpart of the Apple app's
// `OpEdTheme`, `ThemeClip` and `OpeningsEndings`.

/** Which version of an opening or ending plays: the TV size, the full song ("OP1-Full"), or its music video ("OP1-MV"). */
enum class ThemeCut(val settingTitle: String, val label: String?) {
    TvSize("TV Size", null),
    Full("Full Version", "Full"),
    MusicVideo("Music Video", "Music Video"),
}

/** `GET /OpEd/Library`: the libraries the plugin publishes its themes in, each null until it exists. */
data class OpEdLibraries(val videosLibraryId: String?, val songsLibraryId: String?)

/** One opening or ending from the plugin. Absent fields are omitted by the server, so most are nullable. */
data class OpEdTheme(
    val id: String,
    val type: String,
    val sequence: Int,
    val version: Int,
    val slug: String,
    val seriesId: String?,
    val seasonId: String?,
    val songTitle: String?,
    val artists: List<String>,
    val playsInEpisode: Boolean?,
    val spoiler: Boolean,
    val nsfw: Boolean,
    /** Which episodes it plays in, as the plugin writes it ("1-11", "2, 5, 8-9"). */
    val episodes: String? = null,
    val videoMp4Url: String?,
    val videoMp4Status: String?,
    val audioM4aUrl: String?,
    val audioM4aStatus: String?,
    /** The MusicVideo item it was published as in the plugin's Music Videos library; null when not. */
    val libraryItemId: String?,
    val hasFullVersion: Boolean,
    val fullVideoMp4Url: String?,
    val fullAudioM4aUrl: String?,
    val fullLibraryItemId: String?,
    val hasMusicVideo: Boolean,
    val mvVideoMp4Url: String?,
    val mvAudioM4aUrl: String?,
    val mvLibraryItemId: String?,
    /** Which version this is, made by [cut] (not from the server). */
    val cut: ThemeCut = ThemeCut.TvSize,
) {
    val isOpening: Boolean get() = type.equals("OP", ignoreCase = true)

    /** What to play: the H.264 MP4 when ready, else the AAC audio. Null when nothing is playable yet. */
    val playablePath: String? get() = videoMp4Url ?: audioM4aUrl

    /** A copy is still being made; worth showing, disabled. */
    val isPreparing: Boolean
        get() = playablePath == null && listOf(videoMp4Status, audioM4aStatus).any { it == "pending" || it == "converting" }

    /** "OP1", "OP1 v2", "ED1". */
    val code: String get() = if (version > 1) "$slug v$version" else slug

    /** "Yuusha — YOASOBI", or just one of them, or null when the plugin has no song details. */
    val songLine: String?
        get() = when {
            songTitle == null -> artists.joinToString(", ").ifEmpty { null }
            artists.isEmpty() -> songTitle
            else -> "$songTitle — ${artists.joinToString(", ")}"
        }

    /** Another version as a theme of its own, or null when it hasn't got that one playable. */
    fun cut(wanted: ThemeCut): OpEdTheme? {
        if (cut != ThemeCut.TvSize) return if (wanted == cut) this else null
        return when (wanted) {
            ThemeCut.TvSize -> this
            ThemeCut.Full ->
                if (fullVideoMp4Url == null && fullAudioM4aUrl == null) null
                else copy(videoMp4Url = fullVideoMp4Url, audioM4aUrl = fullAudioM4aUrl, libraryItemId = fullLibraryItemId, cut = wanted)
            ThemeCut.MusicVideo ->
                if (mvVideoMp4Url == null && mvAudioM4aUrl == null) null
                else copy(videoMp4Url = mvVideoMp4Url, audioM4aUrl = mvAudioM4aUrl, libraryItemId = mvLibraryItemId, cut = wanted)
        }
    }

    /** The version Settings prefers, or the nearest it has: a music video falls back to the full version, then the TV size. */
    fun preferring(preference: ThemeCut): OpEdTheme = when (preference) {
        ThemeCut.TvSize -> this
        ThemeCut.Full -> cut(ThemeCut.Full) ?: this
        ThemeCut.MusicVideo -> cut(ThemeCut.MusicVideo) ?: cut(ThemeCut.Full) ?: this
    }

    companion object {
        fun fromJson(o: JSONObject) = OpEdTheme(
            id = o.optString("Id"),
            type = o.optString("Type"),
            sequence = o.optInt("Sequence", 1),
            version = o.optInt("Version", 1),
            slug = o.optString("Slug"),
            seriesId = o.str("SeriesId"),
            seasonId = o.str("SeasonId"),
            songTitle = o.str("SongTitle"),
            artists = o.optJSONArray("Artists").strings(),
            playsInEpisode = if (o.has("PlaysInEpisode") && !o.isNull("PlaysInEpisode")) o.optBoolean("PlaysInEpisode") else null,
            spoiler = o.optBoolean("Spoiler"),
            nsfw = o.optBoolean("Nsfw"),
            episodes = o.str("Episodes"),
            videoMp4Url = o.str("VideoMp4Url"),
            videoMp4Status = o.str("VideoMp4Status"),
            audioM4aUrl = o.str("AudioM4aUrl"),
            audioM4aStatus = o.str("AudioM4aStatus"),
            libraryItemId = o.str("LibraryItemId"),
            hasFullVersion = o.optBoolean("HasFullVersion"),
            fullVideoMp4Url = o.str("FullVideoMp4Url"),
            fullAudioM4aUrl = o.str("FullAudioM4aUrl"),
            fullLibraryItemId = o.str("FullLibraryItemId"),
            hasMusicVideo = o.optBoolean("HasMusicVideo"),
            mvVideoMp4Url = o.str("MvVideoMp4Url"),
            mvAudioM4aUrl = o.str("MvAudioM4aUrl"),
            mvLibraryItemId = o.str("MvLibraryItemId"),
        )

        private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null

        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }
    }
}

/** An opening or ending found in an episode's chapters: a stretch of that episode that plays on its own. */
data class ThemeClip(
    val opening: Boolean,
    val season: Int,
    /** 1 for the season's first opening (or ending), 2 for the next... */
    val number: Int,
    val episode: BaseItemDto,
    val startMs: Long,
    val endMs: Long,
) {
    val title: String
        get() = (if (number > 1) "Season $season " else "Season $season ") + (if (opening) "Opening" else "Ending") + if (number > 1) " $number" else ""

    val seconds: Int get() = ((endMs - startMs) / 1000).toInt()
}

/** What the Openings & Endings row and the Watch OP / Watch ED buttons play. */
sealed interface OpeningEnding {
    data class Theme(val theme: OpEdTheme) : OpeningEnding
    data class Chapter(val clip: ThemeClip) : OpeningEnding

    val isOpening: Boolean get() = when (this) { is Theme -> theme.isOpening; is Chapter -> clip.opening }
}

/**
 * Openings and endings in episodes' chapters. Fansub and Blu-ray releases usually mark them ("OP", "Opening", "ED",
 * "Ending"...). Chapters say where an OP is, not which song it is, so a new clip is kept each time the length changes
 * noticeably: a rough sign the song changed.
 */
object OpeningsEndings {
    private val openingWords = setOf("op", "opening")
    private val endingWords = setOf("ed", "ending")
    // "Intro" and "Outro"/"Credits" are used only when an episode has no definite one, since many releases call the cold open "Intro".
    private val looseOpeningWords = setOf("intro")
    private val looseEndingWords = setOf("outro", "credits")

    /** Clips shorter or longer than this aren't really an OP or ED, so they're left out. */
    private val durationRange = 30_000L..210_000L
    private const val SAME_SONG_SLACK_MS = 3_000L
    private const val TICKS_PER_MS = 10_000L

    private class Mark(val opening: Boolean, val definite: Boolean)

    /** Episodes must be in airing order, as `/Shows/{id}/Episodes` returns them. Specials (season 0) are skipped. */
    fun find(episodes: List<BaseItemDto>): List<ThemeClip> {
        val clips = mutableListOf<ThemeClip>()
        val kept = mutableMapOf<String, MutableList<ThemeClip>>()
        for (episode in episodes) {
            val season = episode.parentIndexNumber?.takeIf { it > 0 } ?: continue
            val chapters = episode.chapters?.sortedBy { it.startPositionTicks }?.takeIf { it.isNotEmpty() } ?: continue
            val marks = chapters.map { mark(it.name) }
            val hasDefiniteOpening = marks.any { it?.opening == true && it.definite }
            val hasDefiniteEnding = marks.any { it?.opening == false && it.definite }
            chapters.forEachIndexed { index, chapter ->
                val mark = marks[index] ?: return@forEachIndexed
                // An "Intro" beside a real "OP" is the cold open.
                if (!mark.definite && (if (mark.opening) hasDefiniteOpening else hasDefiniteEnding)) return@forEachIndexed
                val start = chapter.startPositionTicks / TICKS_PER_MS
                val end = (if (index + 1 < chapters.size) chapters[index + 1].startPositionTicks else episode.runTimeTicks ?: 0L) / TICKS_PER_MS
                if (end - start !in durationRange) return@forEachIndexed
                val earlier = kept.getOrPut("$season-${mark.opening}") { mutableListOf() }
                if (earlier.any { kotlin.math.abs((end - start) - (it.endMs - it.startMs)) <= SAME_SONG_SLACK_MS }) return@forEachIndexed
                val clip = ThemeClip(mark.opening, season, earlier.size + 1, episode, start, end)
                clips += clip
                earlier += clip
            }
        }
        // Season by season, each season's openings before its endings.
        return clips.sortedWith(compareBy({ it.season }, { if (it.opening) 0 else 1 }, { it.number }))
    }

    private fun mark(name: String?): Mark? {
        // Emby's intro detection writes "Intro Start" and "Intro End" chapters: the start is a definite opening, ending where the
        // "Intro End" chapter begins, and the end itself is nothing.
        val lower = name?.lowercase()?.trim()
        if (lower == "intro start") return Mark(opening = true, definite = true)
        if (lower == "intro end") return null
        val first = name?.lowercase()?.split(Regex("[^\\p{L}]+"))?.firstOrNull { it.isNotEmpty() } ?: return null
        return when (first) {
            in openingWords -> Mark(opening = true, definite = true)
            in endingWords -> Mark(opening = false, definite = true)
            in looseOpeningWords -> Mark(opening = true, definite = false)
            in looseEndingWords -> Mark(opening = false, definite = false)
            else -> null
        }
    }
}
