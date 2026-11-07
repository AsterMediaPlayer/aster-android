package com.astermediaplayer.astermediaplayer.data

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.LocationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import org.junit.Test

/** The same cases as the Apple apps' `UpcomingTests`: air dates land on the right day, days are grouped in order, and the wording reads naturally. */
class UpcomingScheduleTest {
    /** Central time, where the server's dates were checked (05:00Z is midnight). */
    private val chicago = ZoneId.of("America/Chicago")
    private val ids = HashMap<String, UUID>()
    private fun uuid(key: String) = ids.getOrPut(key) { UUID.nameUUIDFromBytes(key.toByteArray()) }

    private fun episode(id: String, series: String, season: Int, number: Int, name: String, airs: String, location: LocationType = LocationType.VIRTUAL) = BaseItemDto(
        id = uuid(id), name = name, type = BaseItemKind.EPISODE, seriesName = series, seriesId = uuid("series-$series"),
        parentIndexNumber = season, indexNumber = number, premiereDate = airs.takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it) }, locationType = location,
    )

    private fun day(text: String) = LocalDate.parse(text)

    @Test fun airDateReadsJellyfinsSevenDecimals() {
        val item = episode("a", "Blue Box", 2, 3, "Episode 3", "2026-10-18T05:00:00")
        val date = UpcomingSchedule.airDate(item)
        assertNotNull(date)
        assertEquals(day("2026-10-18"), date!!.atZone(chicago).toLocalDate())
    }

    @Test fun groupsByLocalDayInOrder() {
        val items = listOf(
            episode("c", "Blue Box", 2, 3, "Episode 3", "2026-10-18T05:00:00"),
            episode("b", "Reincarnated as a Sword", 2, 3, "Episode 3", "2026-10-15T05:00:00"),
            episode("a", "Reincarnated as a Sword", 2, 2, "Jean du Vix Knows His Stuff", "2026-10-15T05:00:00"),
            episode("d", "Ranma1/2 (2024)", 3, 3, "Episode 3", "2026-10-18T05:00:00"),
        )
        val days = UpcomingSchedule.days(items, chicago)
        assertEquals(listOf(day("2026-10-15"), day("2026-10-18")), days.map { it.first })
        assertEquals(listOf(uuid("a"), uuid("b")), days[0].second.map { it.id })
        assertEquals(listOf(uuid("c"), uuid("d")), days[1].second.map { it.id })
    }

    @Test fun midnightUtcIsTheDayAsWrittenNotTheEveningBefore() {
        val items = listOf(
            episode("a", "MobLand", 2, 8, "We Don't Talk Anymore", "2026-11-06T00:00:00"),
            episode("b", "Blue Box", 2, 6, "Episode 6", "2026-11-08T00:00:00"),
        )
        assertEquals(listOf(day("2026-11-06"), day("2026-11-08")), UpcomingSchedule.days(items, chicago).map { it.first })
    }

    @Test fun countdownWording() {
        val now = day("2026-10-13").atStartOfDay(chicago).toInstant().plusSeconds(10 * 3600)
        fun c(text: String) = UpcomingSchedule.countdown(day(text), now, chicago)
        assertEquals("Today", c("2026-10-13"))
        assertEquals("Tomorrow", c("2026-10-14"))
        assertEquals("In 2 days", c("2026-10-15"))
        assertEquals("In 1 week", c("2026-10-20"))
        assertEquals("In 2 weeks", c("2026-10-27"))
    }

    @Test fun headingNamesTodayAndTomorrow() {
        val now: Instant = day("2026-10-14").atStartOfDay(chicago).toInstant().plusSeconds(3600)
        assertEquals("Today · Wednesday, October 14", UpcomingSchedule.heading(day("2026-10-14"), now, chicago, Locale.US))
        assertEquals("Tomorrow · Thursday, October 15", UpcomingSchedule.heading(day("2026-10-15"), now, chicago, Locale.US))
        assertEquals("Sunday, October 18", UpcomingSchedule.heading(day("2026-10-18"), now, chicago, Locale.US))
    }

    @Test fun episodeLineDropsPlaceholderTitles() {
        assertEquals("S2 · E3", UpcomingSchedule.episodeLine(episode("a", "Blue Box", 2, 3, "Episode 3", "")))
        assertEquals("S3 · E9 · The Blade", UpcomingSchedule.episodeLine(episode("b", "Bleach", 3, 9, "The Blade", "")))
    }

    @Test fun availableEpisodesOpenThemselvesOthersTheirShow() {
        val early = episode("a", "Sword", 2, 2, "Jean", "", LocationType.FILE_SYSTEM)
        val placeholder = episode("b", "Sword", 2, 3, "Episode 3", "")
        assertEquals(uuid("a"), UpcomingSchedule.destination(early).id)
        assertEquals(uuid("series-Sword"), UpcomingSchedule.destination(placeholder).id)
        assertEquals(BaseItemKind.SERIES, UpcomingSchedule.destination(placeholder).type)
    }
}
