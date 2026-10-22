package com.astermediaplayer.astermediaplayer.data

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.LocationType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Dates and wording for the Upcoming page, kept apart from the screen so they can be tested; the Kotlin counterpart of
 * the Apple apps' `UpcomingSchedule`. Most upcoming episodes are placeholders Jellyfin makes from a show's schedule, with
 * no picture or description of their own and a date but no real air time, so the page speaks in days ("Tomorrow",
 * "Thursday"), never clock times.
 */
object UpcomingSchedule {
    /**
     * When an episode airs. Jellyfin gives the air date as the viewer's local midnight written in UTC (05:00Z is midnight
     * in Chicago), so the day it lands on in the viewer's time zone is the day it airs.
     */
    fun airDate(episode: BaseItemDto): Instant? = episode.premiereDate?.toInstant(ZoneOffset.UTC)

    /** Episodes in airing order, gathered by the local day they air. */
    fun days(episodes: List<BaseItemDto>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<LocalDate, List<BaseItemDto>>> {
        val dated = episodes.mapNotNull { e -> airDate(e)?.let { it to e } }
            .sortedWith(
                compareBy<Pair<Instant, BaseItemDto>>({ it.first }, { it.second.seriesName.orEmpty() }, { it.second.parentIndexNumber ?: 0 }, { it.second.indexNumber ?: 0 }),
            )
        val days = mutableListOf<Pair<LocalDate, MutableList<BaseItemDto>>>()
        for ((instant, episode) in dated) {
            val day = instant.atZone(zone).toLocalDate()
            if (days.isNotEmpty() && days.last().first == day) days.last().second += episode else days += day to mutableListOf(episode)
        }
        return days
    }

    /** "Today", "Tomorrow", "In 3 days", then "In 2 weeks". */
    fun countdown(day: LocalDate, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), day)
        return when {
            days < 1 -> "Today"
            days == 1L -> "Tomorrow"
            days in 2..6 -> "In $days days"
            else -> { val weeks = days / 7; if (weeks == 1L) "In 1 week" else "In $weeks weeks" }
        }
    }

    /** A day's heading: "Tomorrow · Thursday, October 15", or just the weekday and date further out. */
    fun heading(day: LocalDate, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val date = day.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", locale))
        val today = now.atZone(zone).toLocalDate()
        return when (day) { today -> "Today · $date"; today.plusDays(1) -> "Tomorrow · $date"; else -> date }
    }

    /** "S2 · E3 · The Blade". A placeholder's title ("Episode 3") only repeats the number, so it's left out. */
    fun episodeLine(episode: BaseItemDto): String {
        val parts = mutableListOf<String>()
        episode.parentIndexNumber?.let { parts += "S$it" }
        episode.indexNumber?.let { parts += "E$it" }
        val name = episode.name
        if (!name.isNullOrEmpty() && name != "Episode ${episode.indexNumber ?: -1}") parts += name
        return parts.joinToString(" · ")
    }

    /** Whether the episode's file is already on the server (out early). */
    fun isAvailable(episode: BaseItemDto): Boolean = episode.locationType == LocationType.FILE_SYSTEM

    /** What a card or the hero opens: the episode when it can be watched, else its show. */
    fun destination(episode: BaseItemDto): BaseItemDto {
        if (isAvailable(episode)) return episode
        val seriesId = episode.seriesId ?: return episode
        return BaseItemDto(id = seriesId, name = episode.seriesName, type = BaseItemKind.SERIES)
    }
}
