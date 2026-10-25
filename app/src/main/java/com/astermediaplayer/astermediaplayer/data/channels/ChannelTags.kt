package com.astermediaplayer.astermediaplayer.data.channels

// A Channel is set up with tags on its Jellyfin playlist, so the server is the one place it lives: every device finds
// the same channels. The Kotlin counterpart of the Apple apps' `ChannelTags`; docs/channel-tags.md in that repo lists
// every tag. "aster:" and the keys and values are read whatever their case (and so is "asterfin:", the app's earlier
// name, which `converted` rewrites). A tag that isn't understood is left alone, and a value that doesn't make sense
// falls back to the default rather than stopping the channel playing.

/** What a genre channel takes from its genres. */
enum class ChannelContent(val raw: String, val title: String, val itemTypes: String) {
    All("all", "Movies & Shows", "Movie,Series"),
    Movies("movies", "Movies", "Movie"),
    Shows("shows", "Shows", "Series");

    companion object { fun of(raw: String) = entries.firstOrNull { it.raw == raw } }
}

/** Which corner of the picture a channel's bug sits in. */
enum class BugCorner(val raw: String, val title: String) {
    TopLeft("top-left", "Top Left"), TopRight("top-right", "Top Right"),
    BottomLeft("bottom-left", "Bottom Left"), BottomRight("bottom-right", "Bottom Right");

    val isTop: Boolean get() = this == TopLeft || this == TopRight
    val isLeft: Boolean get() = this == TopLeft || this == BottomLeft

    companion object { fun of(raw: String) = entries.firstOrNull { it.raw == raw } }
}

data class ShowWeight(val show: String, val weight: Int)

/** Static helpers shared by the schedule and the tag reader. */
object ChannelTagParsing {
    const val PREFIX = "aster:"
    /** What the tags began with when the app was called AsterFIN; still read, and rewritten with [converted]. */
    const val OLD_PREFIX = "asterfin:"

    private fun String.trimWs(): String = trim { it == ' ' || it == '\t' || Character.isSpaceChar(it) }

    /** "aster:Number=101" (or "asterfin:Number=101") → ("number", "101"); null for a tag that isn't Aster's. */
    fun parse(tag: String): Pair<String, String?>? {
        val trimmed = tag.trimWs()
        val start = listOf(PREFIX, OLD_PREFIX).firstOrNull { trimmed.lowercase().startsWith(it) } ?: return null
        val body = trimmed.drop(start.length)
        // Swift's split omits empty pieces, including a leading or trailing one.
        val eq = body.indexOf('=')
        val pieces = (if (eq < 0) listOf(body) else listOf(body.substring(0, eq), body.substring(eq + 1))).filter { it.isNotEmpty() }.map { it.trimWs() }
        val key = pieces.firstOrNull()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return key to pieces.getOrNull(1)
    }

    fun isOff(value: String) = value.lowercase() in setOf("off", "no", "false", "0")

    /** "bumper" or "bumpers", "commercial" or "commercials" and so on, as a kind. */
    private fun kind(word: String): BreakKind? {
        val w = word.lowercase()
        return BreakKind.of(w) ?: BreakKind.of(w + "s")
    }

    fun isFillerPlaylist(tags: List<String>?): Boolean = tags.orEmpty().any { tag ->
        parse(tag)?.let { (key, value) -> key == "filler" && (value?.let { !isOff(it) } ?: true) } ?: false
    }

    /** What a break clip is, from its own tag; null for a clip that doesn't say, which breaks leave out. */
    fun clipKind(tags: List<String>?): BreakKind? {
        for (tag in tags.orEmpty()) parse(tag)?.let { (key, _) -> kind(key)?.let { return it } }
        return null
    }

    /** Where a bumper goes: `aster:bumper=in` only closes a break, `=out` only opens one, and a plain `aster:bumper` goes at either end. */
    fun bumperSide(tags: List<String>?): BumperPlacement {
        for (tag in tags.orEmpty()) {
            val (key, value) = parse(tag) ?: continue
            if (kind(key) != BreakKind.Bumpers) continue
            return value?.let { BumperPlacement.of(it.lowercase()) } ?: BumperPlacement.Both
        }
        return BumperPlacement.Both
    }

    fun sets(key: String, tags: List<String>?): Boolean = tags.orEmpty().any { parse(it)?.first == key }

    /** [tags] with every "asterfin:" tag renamed "aster:", the rest as they are; null when there's none to rename. */
    fun converted(tags: List<String>?): List<String>? {
        if (tags == null) return null
        var changed = false
        val renamed = tags.map { tag ->
            val trimmed = tag.trimWs()
            if (!trimmed.lowercase().startsWith(OLD_PREFIX)) tag else { changed = true; PREFIX + trimmed.drop(OLD_PREFIX.length) }
        }
        return if (changed) renamed else null
    }

    internal fun yearRange(value: String): IntRange? {
        val parts = value.split("-").map { it.trimWs().toIntOrNull() }
        if (parts.size !in 1..2) return null
        val first = parts.first() ?: return null
        val last = parts.last() ?: return null
        val low = minOf(first, last); val high = maxOf(first, last)
        if (low < 1880 || high > 2200) return null
        return low..high
    }

    /** "30", "30m" or ":30" → 30; "off" → 0; null for anything else. */
    internal fun minutes(value: String): Int? {
        var text = value.lowercase().trimWs()
        if (isOff(text)) return 0
        if (text.startsWith(":")) text = text.drop(1)
        for (unit in listOf("minutes", "minute", "mins", "min", "m")) if (text.endsWith(unit)) { text = text.dropLast(unit.length).trimWs(); break }
        return text.toIntOrNull()
    }

    /** "Friends:2" → Friends, 2; null unless the show is named and the weight at least 1. A weight over 5 counts as 5. */
    internal fun showWeight(value: String): ShowWeight? {
        val colon = value.lastIndexOf(':')
        if (colon < 0) return null
        val show = value.substring(0, colon).trimWs()
        val weight = value.substring(colon + 1).trimWs().toIntOrNull() ?: return null
        if (show.isEmpty() || weight < 1) return null
        return ShowWeight(show, minOf(weight, ChannelTags.HEAVIEST_WEIGHT))
    }

    /** "Horror, Thriller" into the list, once each whatever the case; with [splitting] off, the whole value is one name. */
    internal fun collect(value: String?, into: MutableList<String>, splitting: Boolean = true) {
        val pieces = if (splitting) (value ?: "").split(",").filter { it.isNotEmpty() } else listOf(value ?: "")
        for (piece in pieces) {
            val name = piece.trimWs()
            if (name.isEmpty() || into.any { it.equals(name, ignoreCase = true) }) continue
            into += name
        }
    }

    internal fun seconds(value: String): Int? = value.lowercase().trim { it == 's' || it == ' ' }.toIntOrNull()
    internal fun percent(value: String): Int? = value.trim { it == '%' || it == ' ' }.toIntOrNull()

    /** "3-6" → 3..6, "4" → 4..4; null unless it's 1 to the longest block, low to high. */
    internal fun blockRange(value: String): IntRange? {
        val parts = value.split("-").map { it.trimWs().toIntOrNull() }
        if (parts.size !in 1..2) return null
        val low = parts.first() ?: return null
        val high = parts.last() ?: return null
        if (low < 1 || low > high || high > ChannelTags.LONGEST_BLOCK) return null
        return low..high
    }
}

data class ChannelTags(
    val isChannel: Boolean = false,
    val number: Int? = null,
    val mode: ChannelMode = ChannelMode.Rotation,
    val showsLogo: Boolean = false,
    val reshuffle: ChannelReshuffle = ChannelReshuffle.Day,
    val seed: Int = 0,
    val blocks: IntRange = ChannelLineup.DEFAULT_BLOCKS,
    val showsBug: Boolean = false,
    val bugCorner: BugCorner = BugCorner.BottomRight,
    /** The bug's size, as a percentage of the usual. */
    val bugSize: Int = 100,
    /** Seconds the bug shows after tuning in or a programme starting; 0 for always. */
    val bugHideAfter: Int = 0,
    /** How solid the bug is, in percent. */
    val bugOpacity: Int = DEFAULT_BUG_OPACITY,
    /** Genres whose titles the channel plays too, any of them. */
    val genres: List<String> = emptyList(),
    val content: ChannelContent = ChannelContent.All,
    /** Libraries, by name, a genre channel takes from; none for every library. */
    val libraries: List<String> = emptyList(),
    /** Studios or networks, and collections, whose titles the channel plays too, by name. */
    val networks: List<String> = emptyList(),
    val collections: List<String> = emptyList(),
    val years: IntRange? = null,
    val ratings: List<String> = emptyList(),
    /** Shows that take more turns, by name: "Friends" 2 plays twice as often. */
    val weights: List<ShowWeight> = emptyList(),
    val minLength: Int? = null,
    val maxLength: Int? = null,
    /** Playlists, by name, of the clips the breaks between programmes play. */
    val fillerPlaylists: List<String> = emptyList(),
    val fillerTypes: Set<BreakKind> = BreakKind.entries.toSet(),
    val breaks: ChannelBreaks = ChannelBreaks(),
    /** The users, by Jellyfin user name, who get the channel; none for everyone. */
    val users: List<String> = emptyList(),
    /** Null for the playlist's own name. */
    val name: String? = null,
    /** The guide's group for the channel (Kids, Anime, Movies); null for none. */
    val group: String? = null,
) {
    /** Whether the channel fills itself from genres, networks or collections. */
    val hasSources: Boolean get() = genres.isNotEmpty() || networks.isNotEmpty() || collections.isNotEmpty()

    /** Whether the channel has breaks between its programmes. */
    val hasBreaks: Boolean get() = fillerPlaylists.isNotEmpty() && fillerTypes.isNotEmpty()

    /** Whether [userName] gets the channel: everyone does unless it names users. */
    fun isShown(userName: String?): Boolean {
        if (users.isEmpty()) return true
        if (userName == null) return false
        return users.any { it.equals(userName, ignoreCase = true) }
    }

    /** How the channel's lineup is dealt. */
    val lineup: ChannelLineup
        get() = ChannelLineup(
            mode = mode, reshuffle = reshuffle, seed = seed, blocks = blocks,
            weights = weights.associate { it.show.lowercase() to it.weight }.let { m ->
                // The first of two weights for one show wins, as Dictionary(uniquingKeysWith:) keeping the first.
                val first = LinkedHashMap<String, Int>(); for (w in weights) first.putIfAbsent(w.show.lowercase(), w.weight); first
            },
            minLength = minLength?.let { it * 60.0 }, maxLength = maxLength?.let { it * 60.0 }, breaks = breaks,
        )

    /**
     * [tags] with Aster's own replaced by every one of these settings, defaults included; every other tag is kept, in
     * its place. A playlist that stops being a channel is marked `channel=off` and keeps the rest.
     */
    fun applied(to: List<String>?): List<String> {
        val result = to.orEmpty().filter { tag -> ChannelTagParsing.parse(tag)?.let { (key, _) -> key !in KEYS } ?: true }.toMutableList()
        val on = { flag: Boolean -> if (flag) "on" else "off" }
        val p = ChannelTagParsing.PREFIX
        result += p + if (isChannel) "channel" else "channel=off"
        number?.let { result += "${p}number=$it" }
        result += "${p}mode=${mode.raw}"
        result += "${p}reshuffle=${reshuffle.raw}"
        result += p + if (blocks.count() == 1) "blocks=${blocks.first}" else "blocks=${blocks.first}-${blocks.last}"
        result += "${p}seed=$seed"
        result += "${p}logo=${on(showsLogo)}"
        result += "${p}bug=${on(showsBug)}"
        result += "${p}bug-location=${bugCorner.raw}"
        result += "${p}bug-size=$bugSize%"
        result += p + if (bugHideAfter > 0) "bug-hide=$bugHideAfter" else "bug-hide=off"
        result += "${p}bug-opacity=$bugOpacity%"
        genres.forEach { result += "${p}genre=$it" }
        result += "${p}type=${content.raw}"
        libraries.forEach { result += "${p}library=$it" }
        networks.forEach { result += "${p}network=$it" }
        collections.forEach { result += "${p}collection=$it" }
        years?.let { result += p + if (it.count() == 1) "years=${it.first}" else "years=${it.first}-${it.last}" }
        ratings.forEach { result += "${p}rating=$it" }
        weights.forEach { result += "${p}weight=${it.show}:${it.weight}" }
        minLength?.let { result += "${p}min-length=$it" }
        maxLength?.let { result += "${p}max-length=$it" }
        // The break settings only mean something with clips to play.
        if (hasBreaks) {
            fillerPlaylists.forEach { result += "${p}filler-playlists=$it" }
            result += p + "filler-types=" + (if (fillerTypes.size == BreakKind.entries.size) "all" else BreakKind.entries.filter { it in fillerTypes }.joinToString(",") { it.raw })
            result += "${p}bumpers-at=${breaks.bumpersAt.raw}"
            result += "${p}break-length=${breaks.minutes}"
            result += "${p}promo-count=${breaks.promoCount}"
            result += "${p}trailer-count=${breaks.trailerCount}"
            result += "${p}interstitial-count=${breaks.interstitialCount}"
            result += "${p}break-every=${breaks.every}"
            result += p + if (breaks.align > 0) "align=${breaks.align}" else "align=off"
            result += p + "align-fit=" + if (breaks.exactAlign) "exact" else "best"
        }
        if (users.isNotEmpty()) result += p + "user=" + users.joinToString(",")
        name?.let { result += "${p}channel-name=$it" }
        group?.let { result += "${p}group=$it" }
        return result
    }

    companion object {
        const val DEFAULT_BUG_OPACITY = 45
        const val MOST_PROMOS = 5
        const val LONGEST_BREAK = 30
        const val LONGEST_BREAK_GAP = 10
        val ALIGNMENTS = listOf(5, 10, 15, 20, 30, 60)
        const val HEAVIEST_WEIGHT = 5
        val LENGTHS = 1..600
        const val LONGEST_BLOCK = 20
        val BUG_SIZES = 25..300
        val BUG_OPACITIES = 5..100
        val BUG_HIDE_TIMES = 1..3600

        /** The keys this reads and writes; other `aster:` tags are kept as they are. */
        private val KEYS = setOf(
            "channel", "number", "mode", "logo", "reshuffle", "seed", "blocks", "bug", "bug-location", "bug-size", "bug-hide", "bug-opacity",
            "genre", "type", "library", "network", "collection", "years", "rating", "weight", "min-length", "max-length",
            "filler-playlists", "filler-playlist", "filler-types", "filler-type", "bumpers-at", "promo-count", "trailer-count",
            "interstitial-count", "break-length", "break-every", "align", "align-fit", "user", "users", "channel-name", "group",
        )

        /** Reads a playlist's tags. */
        fun from(tags: List<String>?): ChannelTags {
            var isChannel = false; var number: Int? = null; var mode = ChannelMode.Rotation; var showsLogo = false
            var reshuffle = ChannelReshuffle.Day; var seed = 0; var blocks = ChannelLineup.DEFAULT_BLOCKS
            var showsBug = false; var bugCorner = BugCorner.BottomRight; var bugSize = 100; var bugHideAfter = 0; var bugOpacity = DEFAULT_BUG_OPACITY
            val genres = mutableListOf<String>(); var content = ChannelContent.All; val libraries = mutableListOf<String>()
            val networks = mutableListOf<String>(); val collections = mutableListOf<String>()
            var years: IntRange? = null; val ratings = mutableListOf<String>(); val weights = mutableListOf<ShowWeight>()
            var minLength: Int? = null; var maxLength: Int? = null
            val fillerPlaylists = mutableListOf<String>(); var fillerTypes = BreakKind.entries.toSet()
            var breaks = ChannelBreaks(); val users = mutableListOf<String>(); var name: String? = null; var group: String? = null

            for (tag in tags.orEmpty()) {
                val (key, value) = ChannelTagParsing.parse(tag) ?: continue
                when (key) {
                    "channel" -> isChannel = value?.let { !ChannelTagParsing.isOff(it) } ?: true
                    "number" -> value?.toIntOrNull()?.takeIf { it > 0 }?.let { number = it }
                    "mode" -> mode = value?.let { ChannelMode.of(it.lowercase()) } ?: ChannelMode.Rotation
                    "logo" -> showsLogo = value?.let { !ChannelTagParsing.isOff(it) } ?: true
                    "reshuffle" -> reshuffle = value?.let { ChannelReshuffle.of(it.lowercase()) } ?: ChannelReshuffle.Day
                    "seed" -> seed = value?.toIntOrNull()?.let { maxOf(it, 0) } ?: 0
                    "blocks" -> blocks = value?.let(ChannelTagParsing::blockRange) ?: ChannelLineup.DEFAULT_BLOCKS
                    "bug" -> showsBug = value?.let { !ChannelTagParsing.isOff(it) } ?: true
                    "bug-location" -> bugCorner = value?.let { BugCorner.of(it.lowercase()) } ?: BugCorner.BottomRight
                    "bug-size" -> bugSize = value?.let(ChannelTagParsing::percent)?.takeIf { it in BUG_SIZES } ?: 100
                    "bug-opacity" -> bugOpacity = value?.let(ChannelTagParsing::percent)?.takeIf { it in BUG_OPACITIES } ?: DEFAULT_BUG_OPACITY
                    "bug-hide" -> bugHideAfter = value?.let(ChannelTagParsing::seconds)?.takeIf { it in BUG_HIDE_TIMES } ?: 0
                    "genre" -> ChannelTagParsing.collect(value, genres)
                    "type" -> content = value?.let { ChannelContent.of(it.lowercase()) } ?: ChannelContent.All
                    "library" -> ChannelTagParsing.collect(value, libraries)
                    // Studio names can have commas: one per tag.
                    "network" -> ChannelTagParsing.collect(value, networks, splitting = false)
                    "collection" -> ChannelTagParsing.collect(value, collections, splitting = false)
                    "years" -> years = value?.let(ChannelTagParsing::yearRange)
                    "rating" -> ChannelTagParsing.collect(value, ratings)
                    "weight" -> value?.let(ChannelTagParsing::showWeight)?.let { w ->
                        weights.removeAll { it.show.equals(w.show, ignoreCase = true) }
                        weights += w
                    }
                    "min-length" -> minLength = value?.toIntOrNull()?.takeIf { it in LENGTHS }
                    "max-length" -> maxLength = value?.toIntOrNull()?.takeIf { it in LENGTHS }
                    "filler-playlists", "filler-playlist" -> ChannelTagParsing.collect(value, fillerPlaylists, splitting = false)
                    "filler-types", "filler-type" -> {
                        // Kinds it doesn't know are ignored; none it does (or "all") means all.
                        val kinds = (value ?: "").split(",").mapNotNull { k ->
                            val w = k.trim { it == ' ' || it == '\t' }.lowercase()
                            BreakKind.of(w) ?: BreakKind.of(w + "s")
                        }.toSet()
                        fillerTypes = if (kinds.isEmpty()) BreakKind.entries.toSet() else kinds
                    }
                    "bumpers-at" -> breaks = breaks.copy(bumpersAt = value?.let { BumperPlacement.of(it.lowercase()) } ?: BumperPlacement.Both)
                    "promo-count" -> breaks = breaks.copy(promoCount = value?.toIntOrNull()?.takeIf { it in 0..MOST_PROMOS } ?: 1)
                    "trailer-count" -> breaks = breaks.copy(trailerCount = value?.toIntOrNull()?.takeIf { it in 0..MOST_PROMOS } ?: 1)
                    "interstitial-count" -> breaks = breaks.copy(interstitialCount = value?.toIntOrNull()?.takeIf { it in 0..MOST_PROMOS } ?: 1)
                    "break-length" -> breaks = breaks.copy(minutes = value?.let(ChannelTagParsing::minutes)?.takeIf { it in 0..LONGEST_BREAK } ?: ChannelBreaks.DEFAULT_MINUTES)
                    "break-every" -> breaks = breaks.copy(every = value?.toIntOrNull()?.takeIf { it in 1..LONGEST_BREAK_GAP } ?: 1)
                    "align-fit" -> breaks = breaks.copy(exactAlign = value?.lowercase() == "exact")
                    "align" -> breaks = breaks.copy(align = value?.let(ChannelTagParsing::minutes)?.takeIf { it in ALIGNMENTS } ?: 0)
                    "user", "users" -> ChannelTagParsing.collect(value, users)
                    "channel-name" -> name = value?.takeIf { it.isNotEmpty() }
                    "group" -> group = value?.trim()?.takeIf { it.isNotEmpty() }
                }
            }
            return ChannelTags(
                isChannel, number, mode, showsLogo, reshuffle, seed, blocks, showsBug, bugCorner, bugSize, bugHideAfter, bugOpacity,
                genres, content, libraries, networks, collections, years, ratings, weights, minLength, maxLength,
                fillerPlaylists, fillerTypes, breaks, users, name, group,
            )
        }

        /**
         * Channel numbers for a set of channels: their own where they have one, then the rest after the highest of
         * those, in name order. Two channels asking for the same number both keep it.
         */
        fun numbers(channels: List<Triple<String, String, Int?>>): Map<String, Int> {
            val result = LinkedHashMap<String, Int>()
            var next = (channels.mapNotNull { it.third }.maxOrNull() ?: 0) + 1
            for ((id, _, number) in channels) if (number != null) result[id] = number
            val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
            for ((id, _, _) in channels.filter { it.third == null }.sortedWith { a, b -> collator.compare(a.second, b.second) }) {
                result[id] = next
                next += 1
            }
            return result
        }
    }
}
