package com.astermediaplayer.astermediaplayer.data

import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/** What a library's title pages found is kept between runs, per server, and read again when it may have changed. */
class SongThemeCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private var clock = 1_000_000L
    private fun cache() = SongThemeCache(java.io.File(folder.root, "song-themes.json")) { clock }

    private val series = UUID.fromString("00000000-0000-0000-0000-0000000018d5")
    private val clip = ThemeClip(
        opening = true, season = 1, number = 1, startMs = 138_000, endMs = 226_000,
        episode = BaseItemDto(id = UUID.fromString("00000000-0000-0000-0000-0000000017ee"), type = BaseItemKind.EPISODE, name = "I'm Yani Neko, Nya", parentIndexNumber = 1, indexNumber = 1),
    )
    private val theme = KnownTheme(isOpening = true, sequence = 1, songTitle = "Nanmonee", artists = listOf("Wasureranneyo"), episodes = "1-")
    private val show = ShowCandidate("yani_neko", 2026, listOf(theme), aniDbIds = listOf(19000, 19001))

    @Test fun clipsComeBackAfterARestart() = runBlocking {
        cache().putClips("http://plex:32400", series, 11, listOf(clip))
        val back = cache().clips("http://plex:32400", series, 11)!!.single()
        assertEquals(clip.copy(episode = back.episode), back)
        assertEquals("I'm Yani Neko, Nya", back.episode.name)
        assertEquals(clip.episode.id, back.episode.id)
    }

    @Test fun aClipKeepsWhatItsCardShowsNotTheRest() = runBlocking {
        val watched = org.jellyfin.sdk.model.api.UserItemDataDto(playbackPositionTicks = 5, playCount = 1, isFavorite = true, played = true, key = "k", itemId = clip.episode.id)
        cache().putClips("s", series, 1, listOf(clip.copy(episode = clip.episode.copy(userData = watched))))
        val back = cache().clips("s", series, 1)!!.single().episode
        assertNull(back.userData)
        assertEquals("I'm Yani Neko, Nya", back.name)
        assertEquals(1, back.indexNumber)
        assertEquals(1, back.parentIndexNumber)
    }

    @Test fun moreEpisodesReadTheChaptersAgain() = runBlocking {
        cache().putClips("http://plex:32400", series, 11, listOf(clip))
        assertNull(cache().clips("http://plex:32400", series, 12))
    }

    @Test fun anotherServerHasItsOwn() = runBlocking {
        cache().putShows("http://plex:32400", mapOf("Chainsmoker Cat" to listOf(show)))
        assertNull(cache().shows("http://other:32400", "Chainsmoker Cat"))
        assertEquals(listOf(show), cache().shows("http://plex:32400", "Chainsmoker Cat"))
    }

    @Test fun clearingForgetsOneServersEntriesOnly() = runBlocking {
        cache().putShows("http://plex:32400", mapOf("A" to listOf(show)))
        cache().putClips("http://plex:32400", series, 3, listOf(clip))
        cache().putShows("http://other:32400", mapOf("A" to listOf(show)))
        cache().clear("http://plex:32400")
        assertNull(cache().shows("http://plex:32400", "A"))
        assertNull(cache().clips("http://plex:32400", series, 3))
        assertEquals(listOf(show), cache().shows("http://other:32400", "A"))
    }

    @Test fun aSeasonsAniDbIdsComeBackToo() = runBlocking {
        cache().putShows("http://jellyfin:8096", mapOf("Beastars" to listOf(show, ShowCandidate("b2", 2021, emptyList()))))
        val back = cache().shows("http://jellyfin:8096", "Beastars")!!
        assertEquals(listOf(19000, 19001), back[0].aniDbIds)
        assertEquals(emptyList<Int>(), back[1].aniDbIds)
    }

    @Test fun aNameIsFoundHoweverItsCased() = runBlocking {
        cache().putShows("s", mapOf("Chainsmoker Cat" to listOf(show)))
        assertEquals(listOf(show), cache().shows("s", " chainsmoker CAT "))
    }

    @Test fun manyNamesAreOneWrite() = runBlocking {
        cache().putShows("s", (1..50).associate { "show $it" to listOf(show) })
        assertEquals(50, cache().let { c -> (1..50).count { c.shows("s", "show $it") != null } })
    }

    @Test fun nothingFoundIsAskedAgainSoonerThanSomething() = runBlocking {
        cache().putShows("s", mapOf("found" to listOf(show), "none" to emptyList()))
        clock += 4L * 24 * 60 * 60 * 1000
        assertNotNull(cache().shows("s", "found"))
        assertNull(cache().shows("s", "none"))
        clock += 30L * 24 * 60 * 60 * 1000
        assertNull(cache().shows("s", "found"))
    }

    @Test fun aDamagedFileIsJustAnEmptyCache() = runBlocking {
        java.io.File(folder.root, "song-themes.json").writeText("{not json")
        assertNull(cache().shows("s", "x"))
        cache().putShows("s", mapOf("x" to listOf(show)))
        assertEquals(listOf(show), cache().shows("s", "x"))
    }

    @Test fun theFileStaysSmall() = runBlocking {
        val cache = cache()
        repeat(1600) { cache.putShows("s", mapOf("show $it" to listOf(show))); clock += 1 }
        assertNull(cache().shows("s", "show 0"))
        assertNotNull(cache().shows("s", "show 1599"))
    }
}
