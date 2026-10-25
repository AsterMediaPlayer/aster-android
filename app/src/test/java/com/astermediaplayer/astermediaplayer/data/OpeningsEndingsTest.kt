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

    @Test fun namedOpeningsStillWork() {
        val clips = OpeningsEndings.find(listOf(episode("Prologue" to 0, "OP" to 90, "Part A" to 180, "ED" to 1300, "Preview" to 1390)))
        assertEquals(listOf(true, false), clips.map { it.opening })
    }
}
