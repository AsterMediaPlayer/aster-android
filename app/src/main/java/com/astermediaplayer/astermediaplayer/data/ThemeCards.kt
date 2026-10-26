package com.astermediaplayer.astermediaplayer.data

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType

// The card shown over the video while an opening or ending plays, or at the start and end of a music video: what the song is, who sings
// it and which show it's from. The Kotlin counterpart of the Apple apps' `ThemeInfo`, `ThemeMoment` and `ThemeInfoOverlay`'s timing.

/** What the card says. */
data class ThemeInfo(
    /** "Opening 1", "Ending 2 · Version 2". */
    val kicker: String,
    /** The song, or the show when the song is unknown. */
    val title: String,
    val artists: String? = null,
    val series: String? = null,
    /** "Season 2 · Episodes 1–12", "Season 1 · From S1, E3". */
    val detail: String? = null,
) {
    companion object {
        /** A theme the OP/ED plugin knows: the song and artists, the show it's from and the episodes it plays in. */
        fun of(theme: OpEdTheme, series: String, seasonName: String?): ThemeInfo {
            var kicker = "${if (theme.isOpening) "Opening" else "Ending"} ${theme.sequence}"
            if (theme.version > 1) kicker += " · Version ${theme.version}"
            when (theme.cut) {
                ThemeCut.TvSize -> Unit
                ThemeCut.Full -> kicker += " · Full Version"
                ThemeCut.MusicVideo -> kicker += " · Music Video"
            }
            val parts = listOfNotNull(seasonName, theme.episodes?.let { "Episodes $it" })
            return ThemeInfo(
                kicker = kicker, title = theme.songTitle ?: series,
                artists = theme.artists.takeIf { it.isNotEmpty() }?.joinToString(", "),
                series = if (theme.songTitle == null) null else series,
                detail = parts.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            )
        }

        /** An opening or ending found in an episode's chapters, which say where but not which song. */
        fun of(clip: ThemeClip, series: String): ThemeInfo = ThemeInfo(
            kicker = (if (clip.opening) "Opening" else "Ending") + if (clip.number > 1) " ${clip.number}" else "",
            title = series,
            detail = listOfNotNull("Season ${clip.season}", com.astermediaplayer.astermediaplayer.data.downloads.DownloadStore.episodeCode(clip.episode)?.let { "From $it" }).joinToString(" · "),
        )
    }
}

/** A stretch of the video, in seconds, that has a card. */
data class ThemeMoment(val startSec: Double, val endSec: Double, val info: ThemeInfo)

object ThemeCards {
    /** How long the card stays: for the first seconds of a theme and, when it's long enough for two, its last. */
    const val SHOW_FOR = 16.0

    /** How long a marked intro or credits is to count as a song when nothing says a theme plays. */
    private val SONG_LENGTH = 60.0..210.0

    /** Whether the card is up at [seconds] in a theme from [start] to [end] (NaN when the end isn't known). */
    fun visible(seconds: Double, start: Double, end: Double): Boolean {
        if (!seconds.isFinite()) return false
        // Only inside the theme: an episode's opening comes after its start.
        val inside = seconds >= start - 0.5 && (!end.isFinite() || seconds <= end)
        val atStart = inside && seconds - start < SHOW_FOR
        // The closing card only when the theme is long enough for two.
        val atEnd = inside && end.isFinite() && end - start > SHOW_FOR * 3 && end - seconds < SHOW_FOR
        return atStart || atEnd
    }

    /**
     * Where an episode's openings and endings are, and what the card says. Placed by its chapters, best effort: the usual names, then
     * looser ones ("Theme", "Titles", "End Credits"); with none, by the server's intro and credits markers when they're as long as a
     * song (so a show's few-second intro gets no card), or at any length when the plugin says a theme plays in it. The plugin names
     * the song when it knows it.
     */
    fun forEpisode(episode: BaseItemDto, plugin: List<OpEdTheme>, segments: List<MediaSegmentDto>): List<ThemeMoment> {
        if (episode.type != BaseItemKind.EPISODE) return emptyList()
        val series = episode.seriesName ?: episode.name.orEmpty()
        val playing = plugin.filter { it.playsInEpisode == true }
        class Found(val opening: Boolean, val start: Double, val end: Double, val clip: ThemeClip?)
        val found = OpeningsEndings.find(listOf(episode)).map { Found(it.opening, it.startMs / 1000.0, it.endMs / 1000.0, it) }.toMutableList()
        if (found.isEmpty()) {
            for (s in segments) {
                if (s.type != MediaSegmentType.INTRO && s.type != MediaSegmentType.OUTRO) continue
                val length = (s.endTicks - s.startTicks) / 10_000_000.0
                if (length <= 0 || (playing.isEmpty() && length !in SONG_LENGTH)) continue
                found += Found(s.type == MediaSegmentType.INTRO, s.startTicks / 10_000_000.0, s.endTicks / 10_000_000.0, null)
            }
        }
        return found.map { f ->
            val theme = playing.firstOrNull { it.isOpening == f.opening }
            val info = when {
                theme != null -> ThemeInfo.of(theme, series, episode.seasonName)
                f.clip != null -> ThemeInfo.of(f.clip, series)
                else -> ThemeInfo(if (f.opening) "Opening" else "Ending", series, detail = com.astermediaplayer.astermediaplayer.data.downloads.DownloadStore.episodeCode(episode))
            }
            ThemeMoment(f.start, f.end, info)
        }
    }

    /**
     * A music video is one song from start to finish, so its card covers all of it: shown as it starts and again as it ends. One the
     * OP/ED plugin published says which theme of which show it is; any other (an AMV, say) says what the server knows of it.
     */
    fun forMusicVideo(item: BaseItemDto, theme: OpEdTheme?, seasonName: String? = null): List<ThemeMoment> {
        if (item.type != BaseItemKind.MUSIC_VIDEO) return emptyList()
        val ticks = item.runTimeTicks?.takeIf { it > 0 } ?: return emptyList()
        val end = ticks / 10_000_000.0
        val info = if (theme != null) {
            // The plugin writes the show as the album.
            ThemeInfo.of(theme, item.album ?: item.name.orEmpty(), seasonName)
        } else {
            ThemeInfo(
                kicker = "Music Video", title = item.name.orEmpty(),
                artists = item.artists?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.joinToString(", "),
                series = item.album,
            )
        }
        return listOf(ThemeMoment(0.0, end, info))
    }
}
