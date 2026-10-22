package com.astermediaplayer.astermediaplayer.data.channels

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

// What's on a Channel, worked out from the clock: the Kotlin counterpart of the Apple apps' `ChannelSchedule`, which
// it has to agree with to the second. A channel is a playlist of movies and TV episodes, grouped into shows (a series,
// or a single movie). Each day the shows take turns in a shuffled order, and every device shuffles the same way, so an
// Apple TV, an iPhone and this app show the same thing at the same moment with nothing stored and no server involved.
//
// The timeline runs back to back from a fixed start (local midnight, 1 January 2026). Each day picks up where the day
// before ended. A channel can keep its order for a week or a month instead. A show plays its episodes in order and
// carries on from where it got to, like reruns. A channel with break clips has a break after its programmes, dealt from
// the channel's seed and the break's start, so it's the same everywhere too.
//
// Everything that decides an order (hashes, shuffles, sorts by id) is written out exactly as in the Swift, on unsigned
// 64-bit numbers. Ids are the server's 32 hex digits, lower case and without dashes, because sorting and hashing them
// with dashes would order some of them differently.

/** How a channel's shows take turns. */
enum class ChannelMode(val raw: String, val title: String) {
    /** Shows take turns, one episode each. */
    Rotation("rotation", "Rotation"),
    /** Shows take turns, two to four episodes in a row each. */
    Blocks("blocks", "Blocks"),
    /** Every movie and episode on its own, in a new order each day. */
    Shuffle("shuffle", "Shuffle"),
    /** The playlist's own order, straight through and round again. */
    Sequential("sequential", "In Order"),
    /** Everything in the order it first aired, across every show. */
    Airdate("airdate", "Air Date");

    /** Whether the order is dealt again each period; the two fixed orders aren't. */
    val reshuffles: Boolean get() = this != Sequential && this != Airdate

    companion object { fun of(raw: String) = entries.firstOrNull { it.raw == raw } }
}

/** How long a channel keeps one shuffled order before dealing a new one. */
enum class ChannelReshuffle(val raw: String, val title: String) {
    Day("day", "Daily"),
    /** From Monday to Monday. */
    Week("week", "Weekly"),
    /** From the 1st to the 1st. */
    Month("month", "Monthly");

    companion object { fun of(raw: String) = entries.firstOrNull { it.raw == raw } }
}

/** What a break clip is, as its own tag says (`aster:bumper`, `aster:trailer`). */
enum class BreakKind(val raw: String, val title: String) {
    Bumpers("bumpers", "Bumpers"),
    Promos("promos", "Promos"),
    Commercials("commercials", "Commercials"),
    Trailers("trailers", "Trailers"),
    Interstitials("interstitials", "Interstitials");

    companion object { fun of(raw: String) = entries.firstOrNull { it.raw == raw } }
}

/** Where a channel's bumpers go in its breaks. */
enum class BumperPlacement(val raw: String, val title: String) {
    Both("both", "Before & After"),
    /** Only into the break ("We'll be right back"). */
    Out("out", "Before the Break"),
    /** Only out of it ("Now back to…"). */
    In("in", "After the Break");

    companion object { fun of(raw: String) = entries.firstOrNull { it.raw == raw } }
}

/** How a channel's breaks between programmes are made up, from its tags. */
data class ChannelBreaks(
    val bumpersAt: BumperPlacement = BumperPlacement.Both,
    val promoCount: Int = 1,
    /** Trailers in each break. */
    val trailerCount: Int = 1,
    /** Interstitials in each break, without `align` (which fills with them). */
    val interstitialCount: Int = 1,
    /** Minutes of commercials in each break. */
    val minutes: Int = DEFAULT_MINUTES,
    /** A break after every this many programmes, without `align`. */
    val every: Int = 1,
    /** Programmes start on a multiple of this many minutes past the hour, each break running on to the mark; 0 for off. */
    val align: Int = 0,
    /** Lined up, the last filler clip is cut short so programmes start exactly on the mark. */
    val exactAlign: Boolean = false,
) { companion object { const val DEFAULT_MINUTES = 2 } }

/** A movie, episode or break clip as the schedule needs it, with the library item it stands for. */
data class ChannelItem(
    /** The server's id: 32 hex digits, lower case, no dashes. */
    val id: String,
    val name: String?,
    val isEpisode: Boolean,
    val runTimeTicks: Long?,
    val isVirtual: Boolean = false,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val parentIndexNumber: Int? = null,
    val indexNumber: Int? = null,
    /** ISO 8601, so it sorts as text. */
    val premiereDate: String? = null,
    val tags: List<String>? = null,
    /** The library item itself, for pictures and playback. */
    val source: Any? = null,
) {
    val displayTitle: String get() = name.orEmpty()
}

/** The clips a channel's breaks play. */
data class ChannelBreakClips(
    val bumpers: List<ChannelItem> = emptyList(),
    val promos: List<ChannelItem> = emptyList(),
    val commercials: List<ChannelItem> = emptyList(),
    val trailers: List<ChannelItem> = emptyList(),
    val interstitials: List<ChannelItem> = emptyList(),
) {
    val isEmpty: Boolean get() = bumpers.isEmpty() && promos.isEmpty() && commercials.isEmpty() && trailers.isEmpty() && interstitials.isEmpty()

    companion object { val None = ChannelBreakClips() }
}

/** What a scheduled item is to its channel: a programme, or part of a break. */
enum class ProgramRole { Programme, Bumper, Promo, Commercial, Trailer, Interstitial; val isBreak: Boolean get() = this != Programme }

/** How a channel's lineup is dealt, from its tags. */
data class ChannelLineup(
    val mode: ChannelMode = ChannelMode.Rotation,
    val reshuffle: ChannelReshuffle = ChannelReshuffle.Day,
    /** Mixed into the shuffle, so another number deals a fresh lineup; 0 is the channel's original one. */
    val seed: Int = 0,
    /** How many episodes a show plays in a row in Blocks mode. */
    val blocks: IntRange = DEFAULT_BLOCKS,
    /** Shows, by lower-cased name, that take this many turns each round. */
    val weights: Map<String, Int> = emptyMap(),
    /** Titles shorter or longer than these, in seconds, are left out. */
    val minLength: Double? = null,
    val maxLength: Double? = null,
    val breaks: ChannelBreaks = ChannelBreaks(),
) {
    fun allows(length: Double): Boolean = (minLength?.let { length >= it } ?: true) && (maxLength?.let { length <= it } ?: true)

    companion object { val DEFAULT_BLOCKS = 2..4 }
}

/** An instant, as seconds since 1970 (what Swift's `Date` is), so the sums come out the same. */
typealias Seconds = Double

data class ScheduledProgram(
    val channelId: String,
    /** The movie or episode. */
    val item: ChannelItem,
    val start: Seconds,
    val end: Seconds,
    /** A programme, or a clip in a break. In the guide's view a programme's [end] takes in the break after it. */
    val role: ProgramRole = ProgramRole.Programme,
) {
    /** Unique even when an item comes round again. */
    val id: String get() = "$channelId|${start.toLong()}"
    val duration: Double get() = end - start

    /** How far into the item [date] falls. */
    fun offset(date: Seconds): Double = minOf(maxOf(date - start, 0.0), duration)

    fun contains(date: Seconds): Boolean = start <= date && date < end
}

object ChannelSchedule {
    /** An item shorter than this (or with no runtime) isn't scheduled. */
    const val MINIMUM_LENGTH = 60.0
    /** A break clip shorter than this isn't played. */
    const val SHORTEST_CLIP = 1.0
    /** As far as a break can run past its programme. */
    const val LONGEST_BREAK = 2 * 3600.0

    private const val TICKS = 10_000_000.0

    /** Where every channel's timeline begins: local midnight, 1 January 2026. */
    private fun epoch(zone: ZoneId): ZonedDateTime = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone)

    private fun Seconds.toZoned(zone: ZoneId): ZonedDateTime {
        val whole = floor(this).toLong()
        return Instant.ofEpochSecond(whole, ((this - whole) * 1e9).toLong()).atZone(zone)
    }

    private fun ZonedDateTime.seconds(): Seconds = toEpochSecond() + nano / 1e9

    /** The channel's programmes overlapping [from] to [to], each taking in the break after it, as a guide shows them. */
    fun programs(
        channelId: String, items: List<ChannelItem>, lineup: ChannelLineup, breaks: ChannelBreakClips = ChannelBreakClips.None,
        from: Seconds, to: Seconds, zone: ZoneId = ZoneId.systemDefault(),
    ): List<ScheduledProgram> {
        if (breaks.isEmpty) return segments(channelId, items, lineup, breaks, from, to, zone)
        // From far enough back to catch the programme whose break is on at `from`, and on far enough to find where the
        // last one's break ends.
        val raw = segments(channelId, items, lineup, breaks, from - LONGEST_BREAK, to + LONGEST_BREAK, zone)
        val folded = mutableListOf<ScheduledProgram>()
        raw.forEachIndexed { index, segment ->
            if (segment.role != ProgramRole.Programme) return@forEachIndexed
            val next = raw.drop(index + 1).firstOrNull { it.role == ProgramRole.Programme }
            val end = next?.start ?: raw.lastOrNull()?.end ?: segment.end
            folded += ScheduledProgram(segment.channelId, segment.item, segment.start, end)
        }
        return folded.filter { it.end > from && it.start < to }
    }

    /** Everything the channel plays overlapping [from] to [to], programmes and break clips alike, in order. */
    fun segments(
        channelId: String, items: List<ChannelItem>, lineup: ChannelLineup, breaks: ChannelBreakClips = ChannelBreakClips.None,
        from: Seconds, to: Seconds, zone: ZoneId = ZoneId.systemDefault(),
    ): List<ScheduledProgram> {
        val found = mutableListOf<ScheduledProgram>()
        walk(channelId, items, lineup, breaks, from, to, zone) { if (it.end > from && it.start < to) found += it }
        return found
    }

    /** The programme on the channel at [date]. */
    fun airing(channelId: String, items: List<ChannelItem>, lineup: ChannelLineup, at: Seconds, zone: ZoneId = ZoneId.systemDefault()): ScheduledProgram? =
        programs(channelId, items, lineup, from = at, to = at + 1, zone = zone).firstOrNull()

    // MARK: Shows

    /** An item with the seconds it runs for. */
    private class Entry(val item: ChannelItem, val length: Double)

    /** A series' episodes in order, or a single movie. */
    private class Show(val id: String, val episodes: List<Entry>) {
        /** The series' or movie's name, for `weight=`. */
        val name: String
            get() {
                val item = episodes.firstOrNull()?.item ?: return ""
                return if (item.isEpisode) item.seriesName ?: item.displayTitle else item.displayTitle
            }
    }

    private fun length(item: ChannelItem): Double = (item.runTimeTicks ?: 0L) / TICKS

    /**
     * The items grouped into shows, sorted by id so devices that list the playlist in another order still shuffle the
     * same list. Specials (season 0), missing episodes and items without a runtime are left out.
     */
    private fun shows(items: List<ChannelItem>, mode: ChannelMode): List<Show> {
        when (mode) {
            ChannelMode.Sequential -> {
                // One "show": the whole playlist in its own order, specials and all.
                val seen = mutableSetOf<String>()
                val entries = items.mapNotNull { item ->
                    val l = length(item)
                    if (l < MINIMUM_LENGTH || item.isVirtual || !seen.add(item.id)) null else Entry(item, l)
                }
                return if (entries.isEmpty()) emptyList() else listOf(Show("playlist", entries))
            }
            ChannelMode.Shuffle ->
                // Every item its own "show", so episodes mix across series.
                return shows(items, ChannelMode.Rotation).flatMap { show -> show.episodes.map { Show(it.item.id, listOf(it)) } }.sortedBy { it.id }
            ChannelMode.Airdate -> {
                // One "show": everything by when it first aired, specials left out as in Rotation; undated last.
                val entries = shows(items, ChannelMode.Rotation).flatMap { it.episodes }.sortedWith { a, b ->
                    val left = a.item.premiereDate ?: "~"
                    val right = b.item.premiereDate ?: "~"
                    if (left != right) return@sortedWith left.compareTo(right)
                    val c = compareValuesBy(a.item, b.item, { it.seriesName ?: "" }, { it.parentIndexNumber ?: 0 }, { it.indexNumber ?: 0 })
                    if (c != 0) c else a.item.id.compareTo(b.item.id)
                }
                return if (entries.isEmpty()) emptyList() else listOf(Show("airdate", entries))
            }
            ChannelMode.Rotation, ChannelMode.Blocks -> Unit
        }
        val seen = mutableSetOf<String>()
        val series = LinkedHashMap<String, MutableList<Entry>>()
        val shows = mutableListOf<Show>()
        for (item in items) {
            val l = length(item)
            if (l < MINIMUM_LENGTH || item.isVirtual || !seen.add(item.id)) continue
            val entry = Entry(item, l)
            if (item.isEpisode && item.seriesId != null) {
                if ((item.parentIndexNumber ?: 1) == 0) continue
                series.getOrPut(item.seriesId) { mutableListOf() }.add(entry)
            } else shows += Show(item.id, listOf(entry))
        }
        for ((id, episodes) in series) {
            val ordered = episodes.sortedWith { a, b ->
                val c = compareValuesBy(a.item, b.item, { it.parentIndexNumber ?: 1 }, { it.indexNumber ?: Int.MAX_VALUE })
                if (c != 0) c else a.item.id.compareTo(b.item.id)
            }
            shows += Show(id, ordered)
        }
        return shows.sortedBy { it.id }
    }

    // MARK: Building the timeline

    /** Where the walk has got to at the start of a period: enough to carry on from there without going back to the epoch. */
    private class WalkState(
        var periodStart: ZonedDateTime,
        /** When the period's first programme starts (after the one crossing into it). */
        var time: Seconds,
        /** Periods since the epoch. */
        var period: ULong,
        /** The show that played last, by its place in the list. */
        var lastShow: Int?,
        /** The next episode of each show, counted from its first. */
        val pointers: IntArray,
        /** Programmes since the last break, for `break-every`. */
        var sinceBreak: Int = 0,
    ) {
        fun copy() = WalkState(periodStart, time, period, lastShow, pointers.copyOf(), sinceBreak)
    }

    /** The latest day-start state reached for each channel and list, so asking again later resumes there. */
    private val checkpoints = ConcurrentHashMap<String, WalkState>()

    /** Lays the timeline out up to [end], handing each programme to [visit] as it goes. Programmes before [from] may be skipped. */
    private fun walk(
        channelId: String, items: List<ChannelItem>, lineup: ChannelLineup, clips: ChannelBreakClips,
        from: Seconds, end: Seconds, zone: ZoneId, visit: (ScheduledProgram) -> Unit,
    ) {
        val limited = if (lineup.minLength == null && lineup.maxLength == null) items else items.filter { lineup.allows(length(it)) }
        val shows = shows(limited, lineup.mode)
        if (shows.isEmpty()) return
        val pool = BreakPool(clips)
        // How many turns each show takes a round: 1 unless `weight=` says.
        val turns = if (lineup.weights.isEmpty()) emptyList() else shows.map { maxOf(1, lineup.weights[it.name.lowercase()] ?: 1) }

        val channelSeed = seed(channelId)
        val base = if (lineup.seed == 0) channelSeed else mix(channelSeed xor lineup.seed.toLong().toULong())
        val blocks = lineup.blocks
        val key = listOf(
            channelId, lineup.mode.raw, lineup.reshuffle.raw, "${lineup.seed}", "${blocks.first}-${blocks.last}", zone.id,
            lineup.weights.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" },
            "${lineup.minLength?.let { swiftDouble(it) } ?: "0"}-${lineup.maxLength?.let { swiftDouble(it) } ?: "0"}",
            "${seed(shows.flatMap { s -> s.episodes.map { it.item.id } }.joinToString(","))}",
        ).joinToString("|") + (if (pool.isEmpty) "" else "|" + pool.key(lineup.breaks))
        val start = epoch(zone)
        val saved = checkpoints[key]
        var state = saved?.takeIf { it.time <= from }?.copy() ?: WalkState(start, start.seconds(), 0UL, null, IntArray(shows.size))
        var latest: WalkState? = null

        while (state.time < end) {
            val periodEnd = endOfPeriod(state.periodStart, lineup.reshuffle) ?: return
            val periodEndSeconds = periodEnd.seconds()
            if (state.time <= from) latest = state.copy()
            val periodSeed = base xor (state.period * 0x9E3779B97F4A7C15UL)
            var order = shuffled(shows.indices.toList(), periodSeed)
            if (turns.any { it > 1 }) order = weighted(order, turns, periodSeed)
            // A new period never opens with the show that just played.
            if (order.size > 1 && order[0] == state.lastShow) {
                val t = order[0]; order = order.toMutableList().also { it[0] = it[order.size - 1]; it[order.size - 1] = t }
            }
            // The shows go round this order until the period ends, so in a week or a month the turns carry on across midnight.
            var turn = 0
            while (state.time < periodEndSeconds && state.time < end) {
                val index = order[turn % order.size]
                val show = shows[index]
                val size = if (lineup.mode == ChannelMode.Blocks)
                    minOf(show.episodes.size, blocks.first + (mix(periodSeed xor ((turn + 1).toULong() * 0xD1B54A32D192ED03UL)) % blocks.count().toULong()).toInt())
                else 1
                var played = 0
                while (played < size && state.time < periodEndSeconds && state.time < end) {
                    val entry = show.episodes[state.pointers[index] % show.episodes.size]
                    val finish = state.time + entry.length
                    visit(ScheduledProgram(channelId, entry.item, state.time, finish))
                    state.time = finish
                    state.pointers[index] += 1
                    played += 1
                    if (!pool.isEmpty) {
                        state.sinceBreak += 1
                        // Lined up, every programme needs its break to reach the mark.
                        if (lineup.breaks.align > 0 || state.sinceBreak >= lineup.breaks.every) {
                            state.sinceBreak = 0
                            for (clip in pool.deal(state.time, lineup.breaks, base, zone)) {
                                visit(ScheduledProgram(channelId, clip.item, clip.start, clip.end, clip.role))
                                state.time = clip.end
                            }
                        }
                    }
                }
                state.lastShow = index
                turn += 1
            }
            state.periodStart = periodEnd
            state.period += 1UL
            // The period's last programme can run past the next one's end only when it's longer than a period; skip the periods it covers.
            while (true) {
                val next = endOfPeriod(state.periodStart, lineup.reshuffle) ?: break
                if (next.seconds() > state.time) break
                state.periodStart = next
                state.period += 1UL
            }
        }

        val l = latest
        if (l != null && l.time > (saved?.time ?: Double.NEGATIVE_INFINITY)) checkpoints[key] = l
    }

    /** Swift prints a whole-number Double as "5400.0"; the checkpoint key only has to be stable, but this keeps it alike. */
    private fun swiftDouble(v: Double): String = if (v == floor(v) && abs(v) < 1e15) "${v.toLong()}.0" else v.toString()

    // MARK: Breaks

    /** A channel's break clips that can play (a second or longer, and on the server), sorted by id so every device deals from the same list. */
    private class BreakPool(clips: ChannelBreakClips) {
        val bumpers: List<Entry>
        /** The bumpers that can open a break, and close one: all but those tagged for the other end. */
        val openers: List<Entry>
        val closers: List<Entry>
        val promos: List<Entry>
        val commercials: List<Entry>
        val trailers: List<Entry>
        val interstitials: List<Entry>

        init {
            fun playable(items: List<ChannelItem>): List<Entry> {
                val seen = mutableSetOf<String>()
                return items.mapNotNull { item ->
                    val l = (item.runTimeTicks ?: 0L) / TICKS
                    if (l < SHORTEST_CLIP || item.isVirtual || !seen.add(item.id)) null else Entry(item, l)
                }.sortedBy { it.item.id }
            }
            bumpers = playable(clips.bumpers)
            openers = bumpers.filter { ChannelTagParsing.bumperSide(it.item.tags) != BumperPlacement.In }
            closers = bumpers.filter { ChannelTagParsing.bumperSide(it.item.tags) != BumperPlacement.Out }
            promos = playable(clips.promos)
            commercials = playable(clips.commercials)
            trailers = playable(clips.trailers)
            interstitials = playable(clips.interstitials)
        }

        val isEmpty: Boolean get() = bumpers.isEmpty() && promos.isEmpty() && commercials.isEmpty() && trailers.isEmpty() && interstitials.isEmpty()

        /** For the checkpoint key: the clips and how breaks use them. */
        fun key(breaks: ChannelBreaks): String {
            val ids = listOf(openers, closers, promos, commercials, trailers, interstitials).joinToString(";") { l -> l.joinToString(",") { it.item.id } }
            return "${breaks.bumpersAt.raw}-${breaks.promoCount}-${breaks.trailerCount}-${breaks.interstitialCount}-${breaks.minutes}-${breaks.every}-${breaks.align}-${seed(ids)}"
        }

        class Clip(val item: ChannelItem, val role: ProgramRole, val start: Seconds, val end: Seconds)

        private class Part(val entry: Entry, val role: ProgramRole, val length: Double)

        /**
         * Whole clips of every kind the channel has (bumpers only those for either end) filling as much of [seconds] as
         * they can without going over: chosen at random while there's room for any, then the longest that still fits,
         * so the gap left is as small as the clips allow. Grouped as a break runs: commercials, promos, trailers,
         * interstitials, bumpers.
         */
        private fun bestFill(seconds: Double, previous: String?, next: () -> ULong): List<Part> {
            val order = listOf(ProgramRole.Commercial, ProgramRole.Promo, ProgramRole.Trailer, ProgramRole.Interstitial, ProgramRole.Bumper)
            val pool: List<Pair<Entry, ProgramRole>> = commercials.map { it to ProgramRole.Commercial } + promos.map { it to ProgramRole.Promo } +
                trailers.map { it to ProgramRole.Trailer } + interstitials.map { it to ProgramRole.Interstitial } +
                bumpers.filter { ChannelTagParsing.bumperSide(it.item.tags) == BumperPlacement.Both }.map { it to ProgramRole.Bumper }
            val longest = pool.maxOfOrNull { it.first.length } ?: return emptyList()
            val chosen = mutableListOf<Pair<Entry, ProgramRole>>()
            var remaining = seconds
            var last = previous
            while (chosen.size < 2000) {
                var fitting = pool.filter { it.first.length <= remaining && it.first.item.id != last }
                if (fitting.isEmpty()) fitting = pool.filter { it.first.length <= remaining }
                if (fitting.isEmpty()) break
                val clip = if (remaining > longest) fitting[(next() % fitting.size.toULong()).toInt()]
                else {
                    // Swift's max(by:) keeps the first of equals and only replaces on strictly greater.
                    var best = fitting[0]
                    for (candidate in fitting.drop(1)) {
                        val less = if (best.first.length != candidate.first.length) best.first.length < candidate.first.length
                        else candidate.first.item.id < best.first.item.id
                        if (less) best = candidate
                    }
                    best
                }
                chosen += clip
                remaining -= clip.first.length
                last = clip.first.item.id
            }
            return chosen.withIndex().sortedWith(compareBy({ order.indexOf(it.value.second) }, { it.index })).map { Part(it.value.first, it.value.second, it.value.first.length) }
        }

        /**
         * The break after a programme ending at [start]: a bumper out, commercials filling `minutes` with whole clips,
         * promos, trailers, then interstitials, and a bumper back in. Lined up on a mark, the rest of the way to it is
         * filled with every kind of clip the channel has, whole, as near to the mark as they get without passing it
         * (best effort); or, exactly, with interstitials (commercials, then promos, then bumpers on a channel without
         * any), the last cut short to land on it.
         */
        fun deal(start: Seconds, breaks: ChannelBreaks, seed: ULong, zone: ZoneId): List<Clip> {
            var random = mix(seed xor 0x2545F4914F6CDD1DUL xor floor(start + 0.5).toLong().toULong())
            fun next(): ULong { random = mix(random); return random }
            /** One of [pool], not [avoiding] where there's a choice. */
            fun pick(pool: List<Entry>, avoiding: String?): Entry {
                val first = pool[(next() % pool.size.toULong()).toInt()]
                if (pool.size <= 1 || first.item.id != avoiding) return first
                return pool[(pool.indexOfFirst { it.item.id == first.item.id } + 1) % pool.size]
            }
            /** Clips of [pool] filling [seconds]: whole ones, the last cut short to fill it exactly with [cutting]; without, at least one even when none is short enough. */
            fun fill(pool: List<Entry>, role: ProgramRole, seconds: Double, cutting: Boolean, after: String?): List<Part> {
                val result = mutableListOf<Part>()
                var remaining = seconds
                var previous = after
                val shortest = pool.minOfOrNull { it.length } ?: Double.POSITIVE_INFINITY
                while (remaining >= SHORTEST_CLIP && result.size < 2000) {
                    var clip = pick(pool, previous)
                    if (clip.length > remaining && shortest <= remaining) {
                        // Another that fits, where one does.
                        clip = pool.firstOrNull { it.length <= remaining && it.item.id != previous } ?: pool.first { it.length <= remaining }
                    }
                    if (clip.length <= remaining) {
                        result += Part(clip, role, clip.length)
                        remaining -= clip.length
                    } else if (cutting) {
                        result += Part(clip, role, remaining)
                        remaining = 0.0
                    } else {
                        if (result.isEmpty()) result += Part(clip, role, clip.length)
                        break
                    }
                    previous = clip.item.id
                }
                return result
            }

            val parts = mutableListOf<Part>()
            fun add(entry: Entry, role: ProgramRole) { parts += Part(entry, role, entry.length) }
            if (openers.isNotEmpty() && breaks.bumpersAt != BumperPlacement.In) add(pick(openers, null), ProgramRole.Bumper)
            if (commercials.isNotEmpty() && breaks.minutes > 0) {
                parts += fill(commercials, ProgramRole.Commercial, (breaks.minutes * 60).toDouble(), cutting = false, after = parts.lastOrNull()?.entry?.item?.id)
            }
            if (promos.isNotEmpty()) repeat(breaks.promoCount) { add(pick(promos, parts.lastOrNull()?.entry?.item?.id), ProgramRole.Promo) }
            // Whole, never cut short or used to fill to the mark.
            if (trailers.isNotEmpty()) repeat(breaks.trailerCount) { add(pick(trailers, parts.lastOrNull()?.entry?.item?.id), ProgramRole.Trailer) }
            val closing = if (closers.isNotEmpty() && breaks.bumpersAt != BumperPlacement.Out) pick(closers, parts.firstOrNull()?.entry?.item?.id) else null

            var mark: Seconds? = null
            if (breaks.align > 0 && !breaks.exactAlign) {
                val used = parts.sumOf { it.length } + (closing?.length ?: 0.0)
                val target = nextMark(start + used, breaks.align, zone)
                parts += bestFill(target - start - used, parts.lastOrNull()?.entry?.item?.id) { next() }
            } else if (breaks.align > 0) {
                val used = parts.sumOf { it.length } + (closing?.length ?: 0.0)
                val target = nextMark(start + used, breaks.align, zone)
                // Bumpers fill only when nothing else can, and never one meant for one end of a break.
                val filler: Pair<List<Entry>, ProgramRole> = when {
                    interstitials.isNotEmpty() -> interstitials to ProgramRole.Interstitial
                    commercials.isNotEmpty() -> commercials to ProgramRole.Commercial
                    promos.isNotEmpty() -> promos to ProgramRole.Promo
                    else -> bumpers.filter { ChannelTagParsing.bumperSide(it.item.tags) == BumperPlacement.Both } to ProgramRole.Bumper
                }
                if (filler.first.isNotEmpty()) {
                    parts += fill(filler.first, filler.second, target - start - used, cutting = true, after = parts.lastOrNull()?.entry?.item?.id)
                    mark = target
                }
            } else if (interstitials.isNotEmpty()) {
                repeat(breaks.interstitialCount) { add(pick(interstitials, parts.lastOrNull()?.entry?.item?.id), ProgramRole.Interstitial) }
            }
            if (closing != null) add(closing, ProgramRole.Bumper)

            val clips = mutableListOf<Clip>()
            var time = start
            for (part in parts) {
                val end = time + part.length
                clips += Clip(part.entry.item, part.role, time, end)
                time = end
            }
            // Exactly on the mark, whatever the sums came to.
            val last = clips.lastOrNull()
            if (mark != null && last != null && abs(last.end - mark) < 1) clips[clips.size - 1] = Clip(last.item, last.role, last.start, mark)
            return clips
        }
    }

    /** The first time at or after [date] that's a multiple of [minutes] past local midnight: :00 and :30 for 30. */
    private fun nextMark(date: Seconds, minutes: Int, zone: ZoneId): Seconds {
        val midnight = date.toZoned(zone).toLocalDate().atStartOfDay(zone).seconds()
        val step = minutes * 60.0
        val steps = ceil((date - midnight) / step)
        return midnight + steps * step
    }

    /** When the period starting at [start] ends: the next midnight, the next Monday's, or the next 1st's. */
    private fun endOfPeriod(start: ZonedDateTime, reshuffle: ChannelReshuffle): ZonedDateTime? = when (reshuffle) {
        ChannelReshuffle.Day -> start.plusDays(1)
        ChannelReshuffle.Week -> start.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(start.zone)
        ChannelReshuffle.Month -> start.toLocalDate().with(TemporalAdjusters.firstDayOfNextMonth()).atStartOfDay(start.zone)
    }

    /** The round with each show as many times as its turns, shuffled again and spread so a show never follows itself where that can be helped. */
    private fun weighted(order: List<Int>, turns: List<Int>, seed: ULong): List<Int> {
        val round = shuffled(order.flatMap { i -> List(turns[i]) { i } }, mix(seed xor 0xA0761D6478BD642FUL)).toMutableList()
        if (round.size <= 2) return round
        for (index in 1 until round.size) {
            if (round[index] == round[index - 1]) {
                val swap = (index + 1 until round.size).firstOrNull { round[it] != round[index - 1] }
                if (swap != null) { val t = round[index]; round[index] = round[swap]; round[swap] = t }
            }
        }
        return round
    }

    // MARK: Shuffling the same way everywhere

    /** FNV-1a of the text: a seed that doesn't change between launches or devices. */
    fun seed(text: String): ULong {
        var hash = 0xCBF29CE484222325UL
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toULong() and 0xFFUL)
            hash *= 0x100000001B3UL
        }
        return hash
    }

    /** SplitMix64's output step: a well-mixed number from any seed. */
    fun mix(value: ULong): ULong {
        var z = value + 0x9E3779B97F4A7C15UL
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9UL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBUL
        return z xor (z shr 31)
    }

    /** Fisher–Yates with SplitMix64, written out so the order is the same everywhere. */
    fun <T> shuffled(items: List<T>, seed: ULong): List<T> {
        val result = items.toMutableList()
        var state = seed
        fun next(): ULong {
            state += 0x9E3779B97F4A7C15UL
            var z = state
            z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9UL
            z = (z xor (z shr 27)) * 0x94D049BB133111EBUL
            return z xor (z shr 31)
        }
        if (result.size <= 1) return result
        for (i in result.size - 1 downTo 1) {
            val j = (next() % (i + 1).toULong()).toInt()
            val t = result[i]; result[i] = result[j]; result[j] = t
        }
        return result
    }
}
