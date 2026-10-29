package com.astermediaplayer.astermediaplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a chapter clip runs in its stream: Monster Musume's opening, 2:39 into the episode and 90 seconds long. */
class ClipStreamTest {
    @Test fun aDirectStreamUsesTheEpisodesOwnTimes() {
        val direct = ThemePlayer.ClipStream("u", 159_000, 249_000)
        assertEquals(159_000L..249_000L, direct.range(firstPosition = 0))
    }

    @Test fun aJellyfinTranscodeCountsFromZero() {
        val transcode = ThemePlayer.ClipStream("u", 0, 90_000, fromStreamStart = true)
        assertEquals(0L..90_000L, transcode.range(firstPosition = 0))
    }

    /** Emby's transcode keeps the episode's clock: the clip starts where the stream does, not at zero. */
    @Test fun anEmbyTranscodeCountsFromTheClipsPlaceInTheEpisode() {
        val transcode = ThemePlayer.ClipStream("u", 0, 90_000, fromStreamStart = true)
        assertEquals(159_000L..249_000L, transcode.range(firstPosition = 159_000))
    }
}
