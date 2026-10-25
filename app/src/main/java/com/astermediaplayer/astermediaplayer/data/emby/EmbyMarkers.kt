package com.astermediaplayer.astermediaplayer.data.emby

import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.json.JSONObject
import java.util.UUID

/**
 * Emby has no media segments: it marks an intro, and where the credits begin, as chapters (`MarkerType` of IntroStart,
 * IntroEnd and CreditsStart). They become the same Intro and Outro segments Jellyfin serves.
 */
object EmbyMarkers {
    /** Intro from IntroStart to IntroEnd, and credits from CreditsStart to the end; chapters without those marks give nothing. */
    fun segments(item: JSONObject, itemId: UUID): List<MediaSegmentDto> {
        val chapters = item.optJSONArray("Chapters") ?: return emptyList()
        fun mark(type: String): Long? {
            for (i in 0 until chapters.length()) {
                val c = chapters.optJSONObject(i) ?: continue
                if (c.optString("MarkerType").equals(type, ignoreCase = true)) return c.optLong("StartPositionTicks")
            }
            return null
        }
        val runtime = item.optLong("RunTimeTicks", 0).takeIf { it > 0 }
        return buildList {
            val introStart = mark("IntroStart")
            val introEnd = mark("IntroEnd")
            if (introStart != null && introEnd != null && introEnd > introStart) add(MediaSegmentDto(UUID.randomUUID(), itemId, MediaSegmentType.INTRO, introStart, introEnd))
            val creditsStart = mark("CreditsStart")
            if (creditsStart != null && runtime != null && runtime > creditsStart) add(MediaSegmentDto(UUID.randomUUID(), itemId, MediaSegmentType.OUTRO, creditsStart, runtime))
        }
    }
}
