package com.astermediaplayer.astermediaplayer.data

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ChapterInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class OpeningsEndingsTest {
    private fun episode(vararg chapters: Pair<String, Long>) = BaseItemDto(
        id = UUID.randomUUID(), type = BaseItemKind.EPISODE, parentIndexNumber = 1, runTimeTicks = 14_400_000_000L,
        chapters = chapters.map { (name, seconds) -> ChapterInfo(startPositionTicks = seconds * 10_000_000, name = name, imagePath = null, imageDateModified = java.time.LocalDateTime.MIN, imageTag = null) },
    )

    @Test fun embyDetectedIntroIsAnOpening() {
        val clips = OpeningsEndings.find(listOf(episode("Chapter 1" to 0, "Intro Start" to 62, "Intro End" to 120, "Chapter 2" to 600)))
        assertEquals(1, clips.size)
        assertEquals(true, clips[0].opening)
        assertEquals(62_000L, clips[0].startMs)
        assertEquals(120_000L, clips[0].endMs)
    }

    /** An Emby episode as it came back: a "Marker" chapter shares the start of "Intro Start", so the opening ends at "Intro End", not at the next chapter. */
    @Test fun embyIntroEndsAtIntroEndWhateverChapterIsBetween() {
        val clips = OpeningsEndings.find(listOf(episode(
            "Marker 1" to 0, "Intro Start" to 34, "Marker 2" to 34, "Intro End" to 94, "Marker 3" to 94,
            "Marker 4" to 244, "Marker 5" to 534, "Marker 6" to 661,
        )))
        assertEquals(1, clips.size)
        assertEquals(true, clips[0].opening)
        assertEquals(34_000L, clips[0].startMs)
        assertEquals(94_000L, clips[0].endMs)
    }

    @Test fun embyIntroWithNoIntroEndEndsAtTheNextChapter() {
        val clips = OpeningsEndings.find(listOf(episode("Chapter 1" to 0, "Intro Start" to 62, "Chapter 2" to 150)))
        assertEquals(listOf(62_000L to 150_000L), clips.map { it.startMs to it.endMs })
    }

    @Test fun namedOpeningsStillWork() {
        val clips = OpeningsEndings.find(listOf(episode("Prologue" to 0, "OP" to 90, "Part A" to 180, "ED" to 1300, "Preview" to 1390)))
        assertEquals(listOf(true, false), clips.map { it.opening })
    }

    /** A clip gets its song: Beastars on AnimeThemes has one opening for the whole season and endings that take turns by episode. */
    @Test fun aClipGetsTheSongOfItsEpisode() {
        val known = listOf(
            KnownTheme(isOpening = true, sequence = 1, songTitle = "Wild Side", artists = listOf("ALI"), episodes = null),
            KnownTheme(isOpening = false, sequence = 1, songTitle = "Le zoo", artists = listOf("YOASOBI"), episodes = "2, 5, 8-9"),
            KnownTheme(isOpening = false, sequence = 2, songTitle = "Nemureru Honoo", artists = emptyList(), episodes = "3, 7, 10"),
        )
        fun clip(opening: Boolean, number: Int) = ThemeClip(opening, 1, 1, BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE, indexNumber = number), 0, 90_000)
        assertEquals("Wild Side", OpeningsEndings.song(clip(true, 4), known)?.songTitle)
        assertEquals("Le zoo", OpeningsEndings.song(clip(false, 2), known)?.songTitle)
        assertEquals("Nemureru Honoo", OpeningsEndings.song(clip(false, 3), known)?.songTitle)
        // An ending AnimeThemes doesn't place in that episode: no song, rather than a wrong one.
        assertEquals(null, OpeningsEndings.song(clip(false, 1), known))
        // The card names the song and who sings it.
        val named = clip(false, 2).let { it.copy(song = OpeningsEndings.song(it, known)) }
        assertEquals("Le zoo", named.songTitle)
        assertEquals("YOASOBI", named.artists)
        assertEquals("Le zoo", ThemeInfo.of(named, "Beastars").title)
    }
}
