package com.astermediaplayer.astermediaplayer.data

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What the TV player shows over a paused picture, as the Apple app's Apple TV and Mac player: the title's clear logo (its
 * name when there's none) where its name was, and above the timeline its description and a line of facts. Paused on a Live
 * TV channel, the channel's logo and the programme on now; paused in a Channels-tab channel's break, the channel and what's up
 * next. The Kotlin counterpart of the Apple apps' `AetherPauseLogo` and `AetherPauseDetails`.
 */
data class PauseInfo(
    val logoUrl: String?,
    /** In large type when there's no logo. */
    val name: String,
    /** The programme on a channel, or "Break". */
    val heading: String? = null,
    /** Its episode, or what's up next after a break. */
    val subheading: String? = null,
    val overview: String? = null,
    /** "1950 · G · 1 hr 14 min", "511 NBC · 8:00 – 9:00 PM · 23 min left · TV-PG". */
    val facts: List<String> = emptyList(),
    /** What the stream is (4K, HDR, CC): not for a break, which is between programmes. */
    val showsBadges: Boolean = true,
) {
    companion object {
        private val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        private fun time(instant: Instant) = clock.format(instant.atZone(ZoneId.systemDefault()))

        /** "23 min", "1 hr 52 min"; nothing under a minute. */
        fun duration(seconds: Double): String? {
            val minutes = (seconds / 60).toInt()
            if (minutes <= 0) return null
            return if (minutes >= 60) "${minutes / 60} hr" + (if (minutes % 60 > 0) " ${minutes % 60} min" else "") else "$minutes min"
        }

        /** Minutes until [then], rounded up and never under one: "23 min". */
        private fun until(then: Instant, now: Instant): String? =
            if (!then.isAfter(now)) null else duration((((then.epochSecond - now.epochSecond) + 59) / 60).coerceAtLeast(1) * 60.0)

        /** A movie or an episode (the series' logo and name for an episode); [seconds] is the stream's length when known. */
        fun title(item: BaseItemDto, logoUrl: String?, seconds: Double?): PauseInfo = PauseInfo(
            logoUrl = logoUrl,
            name = (if (item.type == BaseItemKind.EPISODE) item.seriesName else null) ?: item.name.orEmpty(),
            overview = item.overview?.takeIf { it.isNotBlank() },
            facts = listOfNotNull(
                item.productionYear?.toString(), item.officialRating,
                duration(seconds?.takeIf { it > 0 } ?: ((item.runTimeTicks ?: 0L) / 10_000_000.0)),
            ),
        )

        /** A Live TV channel, and the programme on it now when it's known. */
        fun live(channel: BaseItemDto, program: BaseItemDto?, logoUrl: String?, now: Instant = Instant.now()): PauseInfo {
            val start = program?.startDate?.atZone(ZoneId.systemDefault())?.toInstant()
            val end = program?.endDate?.atZone(ZoneId.systemDefault())?.toInstant()
            return PauseInfo(
                logoUrl = logoUrl,
                name = channel.name.orEmpty(),
                heading = program?.name,
                subheading = program?.episodeTitle?.takeIf { it.isNotBlank() && it != program.name },
                overview = program?.overview?.takeIf { it.isNotBlank() },
                facts = listOfNotNull(
                    listOfNotNull(channel.number, channel.name).joinToString(" ").ifBlank { null },
                    start?.let { s -> end?.let { "${time(s)} – ${time(it)}" } ?: time(s) },
                    end?.let { until(it, now) }?.let { "$it left" },
                    program?.officialRating,
                ),
            )
        }

        /**
         * A Channels-tab channel's break: the channel, and the programme it leads into ([nextName], starting at [nextStart]) when the
         * schedule knows it.
         */
        fun channelBreak(
            channelNumber: Int, channelName: String, logoUrl: String?,
            nextName: String?, nextStart: Instant?, nextOverview: String?, now: Instant = Instant.now(),
        ): PauseInfo = PauseInfo(
            logoUrl = logoUrl,
            name = channelName,
            heading = "Break",
            subheading = nextName?.let { name -> "Up next: $name" + (nextStart?.let { " at ${time(it)}" } ?: "") },
            overview = nextOverview?.takeIf { it.isNotBlank() },
            facts = listOfNotNull("$channelNumber $channelName", nextStart?.let { until(it, now) }?.let { "back in $it" }),
            showsBadges = false,
        )
    }
}
