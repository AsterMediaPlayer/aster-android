package com.astermediaplayer.astermediaplayer.data

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** What the TV player says while paused, as the Apple app's Apple TV player. */
class PauseInfoTest {
    private fun item(type: BaseItemKind, name: String, block: BaseItemDto.() -> BaseItemDto = { this }) =
        BaseItemDto(id = UUID.randomUUID(), type = type, name = name).block()

    @Test fun durations() {
        assertNull(PauseInfo.duration(59.0))
        assertEquals("23 min", PauseInfo.duration(23 * 60.0))
        assertEquals("1 hr", PauseInfo.duration(3600.0))
        assertEquals("1 hr 52 min", PauseInfo.duration(112 * 60.0 + 30))
    }

    @Test fun aMovieShowsYearRatingAndLength() {
        val movie = item(BaseItemKind.MOVIE, "Cinderella") { copy(productionYear = 1950, officialRating = "G", overview = "A glass slipper.", runTimeTicks = 74 * 60 * 10_000_000L) }
        val info = PauseInfo.title(movie, "logo", seconds = null)
        assertEquals("Cinderella", info.name)
        assertEquals(listOf("1950", "G", "1 hr 14 min"), info.facts)
        assertEquals("A glass slipper.", info.overview)
        // The stream's own length wins over the library's when it's known.
        assertEquals("1 hr 15 min", PauseInfo.title(movie, null, seconds = 75 * 60.0).facts.last())
    }

    @Test fun anEpisodeGoesByItsSeries() {
        val episode = item(BaseItemKind.EPISODE, "Halloween IV") { copy(seriesName = "Brooklyn Nine-Nine") }
        assertEquals("Brooklyn Nine-Nine", PauseInfo.title(episode, null, null).name)
    }

    @Test fun aLiveChannelTellsOfTheProgrammeOnNow() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.of(2026, 10, 29, 19, 18).atZone(zone).toInstant()
        val channel = item(BaseItemKind.TV_CHANNEL, "CBS") { copy(number = "311") }
        val program = item(BaseItemKind.PROGRAM, "Let's Make a Deal") {
            copy(
                episodeTitle = "Let's Make a Deal", overview = "Costumed contestants.", officialRating = "TVPG",
                startDate = LocalDateTime.of(2026, 10, 29, 19, 0), endDate = LocalDateTime.of(2026, 10, 29, 20, 0),
            )
        }
        val info = PauseInfo.live(channel, program, "logo", now)
        assertEquals("Let's Make a Deal", info.heading)
        // An episode title that only repeats the programme's isn't shown twice.
        assertNull(info.subheading)
        assertEquals("311 CBS", info.facts[0])
        assertEquals("42 min left", info.facts[2])
        assertEquals("TVPG", info.facts[3])
    }

    @Test fun aBreakSaysWhatsUpNextAndWhenItsBack() {
        val now = Instant.ofEpochSecond(1_000_000)
        val info = PauseInfo.channelBreak(103, "Hellaverse", null, "Hazbin Hotel · S1, E2", now.plusSeconds(90), "Charlie's plan.", now)
        assertEquals("Break", info.heading)
        assertEquals(listOf("103 Hellaverse", "back in 2 min"), info.facts)
        assertEquals(true, info.subheading!!.startsWith("Up next: Hazbin Hotel · S1, E2 at "))
        assertFalse(info.showsBadges)
    }
}
