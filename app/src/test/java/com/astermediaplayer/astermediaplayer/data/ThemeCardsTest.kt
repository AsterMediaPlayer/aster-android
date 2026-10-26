package com.astermediaplayer.astermediaplayer.data

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ThemeCardsTest {
    private fun theme(extra: String = "") = OpEdTheme.fromJson(JSONObject("""{"Id":"t1","Type":"OP","Sequence":1,"Version":1,"Slug":"OP1","SongTitle":"Yuusha","Artists":["YOASOBI"],"PlaysInEpisode":true$extra}"""))

    private fun episode(chapters: List<Pair<String, Long>>, runtimeSec: Long = 1440, index: Int = 3) = BaseItemDto(
        id = UUID.randomUUID(), type = BaseItemKind.EPISODE, name = "Episode", seriesName = "Frieren", seasonName = "Season 1",
        parentIndexNumber = 1, indexNumber = index, runTimeTicks = runtimeSec * 10_000_000,
        chapters = chapters.map { (name, sec) -> ChapterInfo(startPositionTicks = sec * 10_000_000, name = name, imageDateModified = java.time.LocalDateTime.of(2020, 1, 1, 0, 0)) },
    )

    @Test fun theCardIsUpForTheFirstSecondsAndTheLast() {
        // A 90-second opening that starts three minutes in.
        assertFalse(ThemeCards.visible(120.0, 180.0, 270.0))
        assertTrue(ThemeCards.visible(179.6, 180.0, 270.0))
        assertTrue(ThemeCards.visible(190.0, 180.0, 270.0))
        assertFalse(ThemeCards.visible(200.0, 180.0, 270.0))
        // The closing card, when the theme is long enough for two.
        assertTrue(ThemeCards.visible(260.0, 180.0, 270.0))
        assertFalse(ThemeCards.visible(271.0, 180.0, 270.0))
        // A short one (under three times the card's length) gets only the first.
        assertFalse(ThemeCards.visible(60.0, 30.0, 70.0))
        assertTrue(ThemeCards.visible(35.0, 30.0, 70.0))
        // Without a known end it's only the start.
        assertTrue(ThemeCards.visible(5.0, 0.0, Double.NaN))
        assertFalse(ThemeCards.visible(Double.NaN, 0.0, 10.0))
    }

    @Test fun aPluginThemeNamesTheSong() {
        val info = ThemeInfo.of(theme(""","Episodes":"1-11""""), "Frieren", "Season 1")
        assertEquals("Opening 1", info.kicker)
        assertEquals("Yuusha", info.title)
        assertEquals("YOASOBI", info.artists)
        assertEquals("Frieren", info.series)
        assertEquals("Season 1 · Episodes 1-11", info.detail)
        // A later version and the music video cut say so; a theme with no song title is the show.
        val later = ThemeInfo.of(theme().copy(version = 2, cut = ThemeCut.MusicVideo), "Frieren", null)
        assertEquals("Opening 1 · Version 2 · Music Video", later.kicker)
        val unnamed = ThemeInfo.of(theme().copy(songTitle = null, artists = emptyList()), "Frieren", null)
        assertEquals("Frieren", unnamed.title)
        assertNull(unnamed.series)
        assertNull(unnamed.artists)
    }

    @Test fun anEpisodesOpeningAndEndingComeFromItsChapters() {
        val moments = ThemeCards.forEpisode(episode(listOf("Prologue" to 0, "Opening" to 108, "Part A" to 198, "Ending" to 1347, "Preview" to 1437)), emptyList(), emptyList())
        assertEquals(listOf(108.0 to 198.0, 1347.0 to 1437.0), moments.map { it.startSec to it.endSec })
        // The chapters say where, not which song: the show, and where it's from.
        assertEquals("Opening", moments[0].info.kicker)
        assertEquals("Frieren", moments[0].info.title)
        assertEquals("Season 1 · From S1, E3", moments[0].info.detail)
        assertEquals("Ending", moments[1].info.kicker)
    }

    @Test fun thePluginNamesTheSongWhenItKnowsIt() {
        val moments = ThemeCards.forEpisode(episode(listOf("Opening" to 108, "Part A" to 198)), listOf(theme()), emptyList())
        assertEquals("Yuusha", moments.single().info.title)
        assertEquals("YOASOBI", moments.single().info.artists)
        // A theme that doesn't play in this episode isn't used for it.
        val other = ThemeCards.forEpisode(episode(listOf("Opening" to 108, "Part A" to 198)), listOf(theme().copy(playsInEpisode = false)), emptyList())
        assertEquals("Frieren", other.single().info.title)
    }

    @Test fun skipMarkersPlaceAThemeWhenAsLongAsASongOrThePluginSaysOnePlays() {
        fun marker(type: MediaSegmentType, from: Long, to: Long) = MediaSegmentDto(id = UUID.randomUUID(), itemId = UUID.randomUUID(), type = type, startTicks = from * 10_000_000L, endTicks = to * 10_000_000L)
        val plain = episode(listOf("Chapter 1" to 0, "Chapter 2" to 300))
        // A few-second intro is no song.
        assertTrue(ThemeCards.forEpisode(plain, emptyList(), listOf(marker(MediaSegmentType.INTRO, 10, 30))).isEmpty())
        // One as long as an opening is, with the show named and no song.
        val placed = ThemeCards.forEpisode(plain, emptyList(), listOf(marker(MediaSegmentType.INTRO, 60, 150), marker(MediaSegmentType.OUTRO, 1300, 1390))).map { it.startSec to it.endSec }
        assertEquals(listOf(60.0 to 150.0, 1300.0 to 1390.0), placed)
        // Short, but the plugin says a theme plays: that names it.
        val named = ThemeCards.forEpisode(plain, listOf(theme()), listOf(marker(MediaSegmentType.INTRO, 10, 30)))
        assertEquals("Yuusha", named.single().info.title)
    }

    @Test fun looserChapterNamesCount() {
        val moments = ThemeCards.forEpisode(episode(listOf("Cold Open" to 0, "Theme Song" to 90, "Part A" to 180, "End Credits" to 1350)), emptyList(), emptyList())
        assertEquals(listOf(90.0, 1350.0), moments.map { it.startSec })
    }

    @Test fun aMusicVideoHasOneCardOverItsWhole() {
        val video = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MUSIC_VIDEO, name = "Yuusha (Full)", album = "Frieren", artists = listOf("YOASOBI", ""), runTimeTicks = 200 * 10_000_000L)
        val any = ThemeCards.forMusicVideo(video, null).single()
        assertEquals(0.0 to 200.0, any.startSec to any.endSec)
        assertEquals("Music Video", any.info.kicker)
        assertEquals("Yuusha (Full)", any.info.title)
        assertEquals("YOASOBI", any.info.artists)
        assertEquals("Frieren", any.info.series)
        // One the plugin published says which theme of which show it is, the show written as its album.
        val published = ThemeCards.forMusicVideo(video, theme(), "Season 1").single()
        assertEquals("Opening 1", published.info.kicker)
        assertEquals("Yuusha", published.info.title)
        assertEquals("Frieren", published.info.series)
        // Nothing else gets one.
        assertTrue(ThemeCards.forMusicVideo(video.copy(type = BaseItemKind.MOVIE), null).isEmpty())
    }
}
