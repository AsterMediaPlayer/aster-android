package com.astermediaplayer.astermediaplayer.data.channels

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Every channel has to be on the same programme at the same second on every device. `schedule-golden.json` was made by
 * running the Apple apps' own `ChannelSchedule.swift` and `ChannelTags.swift` (unchanged, with a small stand-in for the
 * item type; see schedule-golden-generator.swift.txt) over these scenarios, so this compares the Kotlin port to it
 * programme by programme: item, role, start and end.
 */
class ChannelScheduleGoldenTest {
    private fun movie(id: String, minutes: Double?): ChannelItem {
        val n = id.drop(1).toInt()
        return ChannelItem(id, "Movie $id", false, minutes?.let { (it * 60 * 10_000_000).toLong() }, premiereDate = "${1980 + n}-0${1 + n % 9}-15T00:00:00.0000000Z")
    }

    private fun episode(series: String, season: Int, number: Int, minutes: Double = 22.0) = ChannelItem(
        "$series-s${season}e$number", "$series ${season}x$number", true, (minutes * 60 * 10_000_000).toLong(),
        seriesId = series, seriesName = series.replaceFirstChar { it.uppercase() }, parentIndexNumber = season, indexNumber = number,
        premiereDate = "${2000 + number + season}-0${1 + (number + season) % 9}-10T00:00:00.0000000Z",
    )

    private fun clip(id: String, seconds: Double, tags: List<String>? = null) = ChannelItem(id, id, false, (seconds * 10_000_000).toLong(), tags = tags)

    private val movies = (0 until 8).map { movie("m$it", 85.0 + it * 10) }
    private val shows: List<ChannelItem> = buildList {
        listOf("alpha", "bravo", "charlie").forEachIndexed { n, series ->
            for (season in 1..2) for (number in 1..6) add(episode(series, season, number, 22.0 + n * 8))
            add(episode(series, 0, 1))
        }
    }
    private val mixed = shows + movies.take(3)

    private val clips = ChannelBreakClips(
        bumpers = listOf(clip("b1", 5.0), clip("b2", 7.0, listOf("aster:bumper=in")), clip("b3", 6.0, listOf("aster:bumper=out")), clip("b4", 4.0, listOf("aster:bumper"))),
        promos = listOf(clip("p1", 20.0), clip("p2", 30.0), clip("p3", 25.0)),
        commercials = listOf(clip("c1", 15.0), clip("c2", 30.0), clip("c3", 45.0), clip("c4", 60.0), clip("c5", 20.0), clip("c6", 90.0)),
        trailers = listOf(clip("t1", 100.0), clip("t2", 130.0)),
        interstitials = listOf(clip("i1", 30.0), clip("i2", 120.0), clip("i3", 300.0), clip("i4", 45.0)),
    )
    private val longOnly = ChannelBreakClips(commercials = listOf(clip("L1", 600.0), clip("L2", 900.0)))
    private val noInter = ChannelBreakClips(bumpers = listOf(clip("b1", 5.0)), promos = listOf(clip("p1", 20.0)), commercials = listOf(clip("c1", 15.0), clip("c2", 30.0)))

    private class Setup(val items: List<ChannelItem>, val lineup: ChannelLineup, val clips: ChannelBreakClips, val channel: String)

    /** The scenarios of the generator, by name. */
    private fun setup(name: String): Setup {
        fun s(items: List<ChannelItem>, lineup: ChannelLineup = ChannelLineup(), clips: ChannelBreakClips = ChannelBreakClips.None, channel: String = "channel-a") = Setup(items, lineup, clips, channel)
        val align = Regex("align-(best|exact)-(\\d+)").matchEntire(name)
        if (align != null) return s(shows, ChannelLineup(breaks = ChannelBreaks(align = align.groupValues[2].toInt(), exactAlign = align.groupValues[1] == "exact")), clips)
        return when (name) {
            "movies-rotation", "epoch-start", "dst-fall", "far-future", "kolkata" -> s(movies)
            "movies-other-channel" -> s(movies, channel = "channel-b")
            "movies-reversed" -> s(movies.reversed())
            "shows-rotation", "dst-spring" -> s(shows)
            "shows-blocks" -> s(shows, ChannelLineup(mode = ChannelMode.Blocks))
            "shows-blocks-long" -> s(shows, ChannelLineup(mode = ChannelMode.Blocks, blocks = 3..6))
            "mixed-rotation" -> s(mixed)
            "mixed-shuffle" -> s(mixed, ChannelLineup(mode = ChannelMode.Shuffle))
            "mixed-sequential" -> s(mixed, ChannelLineup(mode = ChannelMode.Sequential))
            "mixed-airdate" -> s(mixed, ChannelLineup(mode = ChannelMode.Airdate))
            "week", "week-boundary", "auckland-week" -> s(shows, ChannelLineup(reshuffle = ChannelReshuffle.Week))
            "month", "month-boundary" -> s(mixed, ChannelLineup(reshuffle = ChannelReshuffle.Month))
            "seed2" -> s(movies, ChannelLineup(seed = 2))
            "seed7-blocks" -> s(shows, ChannelLineup(seed = 7, mode = ChannelMode.Blocks))
            "weights" -> s(shows, ChannelLineup(weights = mapOf("alpha" to 3, "bravo" to 2)))
            "weights-blocks" -> s(shows, ChannelLineup(weights = mapOf("charlie" to 4), mode = ChannelMode.Blocks))
            "length-limits" -> s(mixed, ChannelLineup(minLength = 30 * 60.0, maxLength = 110 * 60.0))
            "breaks-default" -> s(shows, clips = clips)
            "breaks-every2" -> s(shows, ChannelLineup(breaks = ChannelBreaks(every = 2)), clips)
            "breaks-minutes5-promos3" -> s(shows, ChannelLineup(breaks = ChannelBreaks(minutes = 5, promoCount = 3, trailerCount = 2, interstitialCount = 3)), clips)
            "breaks-bumpers-out" -> s(shows, ChannelLineup(breaks = ChannelBreaks(bumpersAt = BumperPlacement.Out)), clips)
            "breaks-bumpers-in" -> s(shows, ChannelLineup(breaks = ChannelBreaks(bumpersAt = BumperPlacement.In)), clips)
            "align-nointer-best" -> s(shows, ChannelLineup(breaks = ChannelBreaks(align = 30)), noInter)
            "align-nointer-exact" -> s(shows, ChannelLineup(breaks = ChannelBreaks(align = 30, exactAlign = true)), noInter)
            "align-long-only" -> s(shows, ChannelLineup(breaks = ChannelBreaks(align = 30)), longOnly)
            "align-long-exact" -> s(shows, ChannelLineup(breaks = ChannelBreaks(align = 30, exactAlign = true)), longOnly)
            "breaks-movies" -> s(movies, clips = clips)
            "align-movies-30" -> s(movies, ChannelLineup(breaks = ChannelBreaks(align = 30)), clips)
            "breaks-mixed-shuffle" -> s(mixed, ChannelLineup(mode = ChannelMode.Shuffle, breaks = ChannelBreaks(align = 15)), clips)
            "breaks-kolkata" -> s(shows, ChannelLineup(breaks = ChannelBreaks(align = 30)), clips)
            else -> error("No setup for $name")
        }
    }

    private fun format(v: Double) = "%.3f".format(v)

    private fun describe(array: JSONArray, index: Int): String {
        val row = array.getJSONArray(index)
        return "[${format(row.getDouble(0))}, ${format(row.getDouble(1))}, ${row.getString(2)}, ${row.getString(3)}]"
    }

    private fun check(name: String, what: String, expected: JSONArray, actual: List<ScheduledProgram>) {
        assertEquals("$name: number of $what", expected.length(), actual.size)
        for (i in actual.indices) {
            val a = actual[i]
            val got = "[${format(a.start)}, ${format(a.end)}, ${a.item.id}, ${a.role.name.lowercase()}]"
            val want = describe(expected, i)
            assertEquals("$name: $what #$i", want.replace("\"", ""), got)
        }
    }

    @Test
    fun matchesTheSwiftScheduleProgrammeForProgramme() {
        val golden = JSONArray(javaClass.getResource("/channels/schedule-golden.json")!!.readText())
        assertTrue(golden.length() >= 50)
        var checked = 0
        for (i in 0 until golden.length()) {
            val scenario: JSONObject = golden.getJSONObject(i)
            val name = scenario.getString("name")
            val setup = setup(name)
            val zone = ZoneId.of(scenario.getString("tz"))
            val from = scenario.getDouble("from")
            val to = scenario.getDouble("to")
            val segments = ChannelSchedule.segments(setup.channel, setup.items, setup.lineup, setup.clips, from, to, zone)
            val programs = ChannelSchedule.programs(setup.channel, setup.items, setup.lineup, setup.clips, from, to, zone)
            check(name, "segments", scenario.getJSONArray("segments"), segments)
            check(name, "programs", scenario.getJSONArray("programs"), programs)
            checked += segments.size
        }
        assertTrue("checked $checked segments", checked > 10_000)
    }
}
