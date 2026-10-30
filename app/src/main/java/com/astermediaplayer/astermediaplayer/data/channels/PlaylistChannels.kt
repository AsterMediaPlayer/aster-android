package com.astermediaplayer.astermediaplayer.data.channels

import com.astermediaplayer.astermediaplayer.data.AsterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.LocationType
import java.util.UUID

// The Channels tab's channels: Jellyfin video playlists tagged as channels (see ChannelTags), so every device finds the
// same ones. Each plays its movies and episodes on a schedule worked out from the clock (see ChannelSchedule). The
// Kotlin counterpart of `PlaylistChannels.swift`.

/** The server's id as the schedule sees it: 32 hex digits, lower case, no dashes. */
fun UUID.plain(): String = toString().replace("-", "")

/** A movie, episode or clip as the schedule needs it. */
fun BaseItemDto.toChannelItem(): ChannelItem {
    val date = premiereDate
    return ChannelItem(
        id = id.plain(), name = name, isEpisode = type == BaseItemKind.EPISODE, runTimeTicks = runTimeTicks,
        isVirtual = locationType == LocationType.VIRTUAL, seriesId = seriesId?.plain(), seriesName = seriesName,
        parentIndexNumber = parentIndexNumber, indexNumber = indexNumber,
        // Fixed width, as the server writes it, so dates sort as text the way the Apple apps' do.
        premiereDate = date?.let { "%04d-%02d-%02dT%02d:%02d:%02d.%07dZ".format(it.year, it.monthValue, it.dayOfMonth, it.hour, it.minute, it.second, it.nano / 100) },
        tags = tags, source = this,
    )
}

/** A playlist as a channel: its number and name, the movies and episodes it plays, and how its shows take turns. */
data class PlaylistChannel(
    val playlist: BaseItemDto,
    val number: Int,
    val items: List<ChannelItem>,
    val lineup: ChannelLineup = ChannelLineup(),
    /** The guide shows the playlist's picture as the channel's logo. */
    val showsLogo: Boolean = false,
    /** The players show the playlist's picture in a corner, as a network's bug. */
    val showsBug: Boolean = false,
    val bugCorner: BugCorner = BugCorner.BottomRight,
    val bugSize: Int = 100,
    val bugHideAfter: Int = 0,
    val bugOpacity: Int = ChannelTags.DEFAULT_BUG_OPACITY,
    /** From the `channel-name=` tag; null for the playlist's own name. */
    val customName: String? = null,
    /** The bumpers, promos and interstitials its breaks play; none for a channel without breaks. */
    val breakClips: ChannelBreakClips = ChannelBreakClips.None,
    /** The series' clear logo, when the playlist is all one series' episodes. */
    val seriesLogo: SeriesLogo? = null,
    /** From the `group=` tag; null for none. */
    val group: String? = null,
) {
    data class SeriesLogo(val itemId: UUID, val tag: String)

    val id: String get() = playlist.id.plain()
    val name: String get() = customName ?: playlist.name.orEmpty()

    /** The picture the channel's logo and bug show: its one series' clear logo, else the playlist's picture; null with neither. */
    fun logoUrl(repo: AsterRepository, maxWidth: Int): String? {
        seriesLogo?.let { return repo.imageUrlFor(it.itemId, it.tag, maxWidth, ImageType.LOGO) }
        return playlist.imageTags?.get(ImageType.PRIMARY)?.let { repo.imageUrlFor(playlist.id, it, maxWidth) }
    }

    /** The programme on now, its break included, as the guide shows it. */
    fun airing(at: Seconds = now()): ScheduledProgram? = programs(at, at + 1).firstOrNull()

    /** The programmes, each with the break after it, as the guide shows them. */
    fun programs(from: Seconds, to: Seconds): List<ScheduledProgram> = ChannelSchedule.programs(id, items, lineup, breakClips, from, to)

    /** What's playing now, programme or break clip, and from how far in. */
    fun segment(at: Seconds = now()): ScheduledProgram? = segments(at, at + 1).firstOrNull()

    /** Everything the channel plays, programmes and break clips alike. */
    fun segments(from: Seconds, to: Seconds): List<ScheduledProgram> = ChannelSchedule.segments(id, items, lineup, breakClips, from, to)

    /** In a break: the programme it leads into; null during a programme. */
    fun programmeAfterBreak(at: Seconds = now()): ScheduledProgram? {
        if (segment(at)?.role?.isBreak != true) return null
        val current = airing(at) ?: return null
        return airing(current.end)
    }

    companion object {
        fun now(): Seconds = System.currentTimeMillis() / 1000.0

        /** The clear logo of the one series [items] are all episodes of; null for a playlist with movies, several series, or a series without a logo. */
        fun seriesLogo(items: List<ChannelItem>): SeriesLogo? {
            val first = items.firstOrNull() ?: return null
            val seriesId = first.seriesId ?: return null
            if (!items.all { it.isEpisode && it.seriesId == seriesId }) return null
            val dtos = items.mapNotNull { it.source as? BaseItemDto }
            val logoItem = dtos.firstNotNullOfOrNull { it.parentLogoItemId } ?: return null
            val tag = dtos.firstNotNullOfOrNull { it.parentLogoImageTag } ?: return null
            return SeriesLogo(logoItem, tag)
        }
    }
}

/** What to play for a channel now: the movie, episode or break clip, from how far in, and where a cut-short clip stops. */
data class TunedSegment(val item: ChannelItem, val channelId: String, val startTicks: Long, val cutTicks: Long?, val role: ProgramRole)

/**
 * What's on a channel now, as something to play. The Channels tab registers the channels it loads, and the players ask
 * here what's next when a movie or episode ends, so a channel stays with its schedule.
 */
object ChannelTuner {
    private var channels: Map<String, PlaylistChannel> = emptyMap()
    private var order: List<String> = emptyList()
    /** Seconds before a programme's end that tuning in starts the next one. */
    const val TOO_LATE_TO_JOIN = 60.0

    /** Takes the full list the Channels tab loaded, in order. */
    fun register(loaded: List<PlaylistChannel>) {
        channels = channels + loaded.associateBy { it.id }
        order = loaded.map { it.id }
    }

    /** The channels in number order, for flicking through them. */
    val orderedChannels: List<PlaylistChannel> get() = order.mapNotNull { channels[it] }

    fun channel(id: String): PlaylistChannel? = channels[id]

    /**
     * What's on [channelId] at [date], from as far in as it's got. Just after [finishedItemId] ends, a viewer who
     * paused is a little behind the schedule and it may still be "on"; what follows it then plays from the start,
     * rather than the last minutes of it again.
     */
    fun live(channelId: String, at: Seconds = PlaylistChannel.now(), after: String? = null): TunedSegment? {
        val channel = channels[channelId] ?: return null
        var program = channel.segment(at) ?: return null
        var offset = program.offset(at)
        // Joining with under a minute left (a quarter of a short clip) starts what's next instead: the last seconds
        // can fall after the file's final keyframe, with nothing to start the picture from.
        val almostOver = program.end - at < minOf(TOO_LATE_TO_JOIN, program.duration / 4)
        if (almostOver || (after != null && program.item.id == after)) {
            channel.segments(program.end, program.end + 1).firstOrNull()?.let { program = it; offset = 0.0 }
        }
        // A clip cut short to land on the half hour stops where the schedule says, not at its end.
        val length = (program.item.runTimeTicks ?: 0L) / 10_000_000.0
        val cut = if (program.role.isBreak && program.duration < length - 0.5) (program.duration * 10_000_000).toLong() else null
        return TunedSegment(program.item, channelId, (offset * 10_000_000).toLong(), cut, program.role)
    }
}

/** Finds the playlists tagged as channels and loads their movies and episodes, in channel-number order. */
class ChannelsModel(private val repo: AsterRepository) {
    private val _channels = MutableStateFlow<List<PlaylistChannel>>(emptyList())
    val channels: StateFlow<List<PlaylistChannel>> = _channels.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Each channel's items as last fetched, with the playlist's item count then, so a big playlist isn't fetched again unless it changed. */
    private val itemCache = java.util.concurrent.ConcurrentHashMap<UUID, Pair<Int?, List<BaseItemDto>>>()
    /** Each source channel's titles and what it plays from them, by playlist id, with the query that found them. */
    private class Source(val query: String, val titleIds: List<UUID>, val items: List<BaseItemDto>)
    private val sourceCache = java.util.concurrent.ConcurrentHashMap<UUID, Source>()
    /** Each series' episodes, by series id, shared by every channel playing it. */
    private val episodeCache = java.util.concurrent.ConcurrentHashMap<UUID, List<BaseItemDto>>()

    private class Tagged(val playlist: BaseItemDto, val tags: ChannelTags)

    /** Forgets everything about one server's channels, for another's. */
    fun reset() {
        itemCache.clear(); sourceCache.clear(); episodeCache.clear()
        _channels.value = emptyList(); _loaded.value = false; _error.value = null
        ChannelTuner.register(emptyList())
    }

    /** Loads the channels [userName] gets (`aster:user=`), every one for null. */
    suspend fun load(userName: String? = null, force: Boolean = false) = withContext(Dispatchers.Default) {
        // A playlist's titles are JSON the SDK reads where it's called: seconds of work for a long one on a TV box, so never the main
        // thread. One load at a time, so the Channels page and Edit Channels opened over it don't fetch everything twice.
        loading.withLock { loadNow(userName, force) }
    }

    private val loading = kotlinx.coroutines.sync.Mutex()

    private suspend fun loadNow(userName: String?, force: Boolean) {
        _error.value = null
        try {
            // Read each time, so an administrator's change counts on the next visit.
            val policy = repo.contentPolicy()
            val playlists = repo.videoPlaylists()
            val tagged = taggedChannels(playlists).filter { userName == null || it.tags.isShown(userName) }
            val numbers = ChannelTags.numbers(tagged.map { Triple(it.playlist.id.plain(), it.tags.name ?: it.playlist.name.orEmpty(), it.tags.number) })
            // The playlists of break clips the channels name, fetched with the channels' own.
            val fillers = tagged.associate { it.playlist.id to fillerPlaylists(it.tags, playlists) }
            val wanted = (tagged.map { it.playlist } + fillers.values.flatten()).distinctBy { it.id }
            val stale = wanted.filter { force || itemCache[it.id] == null || itemCache[it.id]?.first != it.childCount }
            val fetched = coroutineScope {
                stale.map { p -> async { p.id to runCatching { repo.channelPlaylistItems(p.id) }.getOrNull() } }.awaitAll()
            }.toMap()
            for (p in stale) fetched[p.id]?.let { itemCache[p.id] = p.childCount to it }
            // Genres, networks and collections as sources (on Plex, answered from its libraries' own lists, by name).
            loadSources(tagged, force)
            // The ratings' ranks, only needed to clear a restricted user's genre, network or collection channel.; if the server won't
            // list them, none is ranked and those channels stay held back, rather than the whole tab failing.
            val ratings = if (policy?.restrictsContent == true && tagged.any { it.tags.hasSources && it.tags.ratings.isNotEmpty() }) runCatching { repo.parentalRatings() }.getOrDefault(emptyList()) else emptyList()

            val built = tagged.mapNotNull { entry ->
                // A channel with anything on it the user's parental controls hold back is left out altogether.
                if (policy != null && ChannelRestriction.isHeldBack(entry.playlist.childCount, itemCache[entry.playlist.id]?.second?.size, entry.tags, policy, ratings)) return@mapNotNull null
                val own = itemCache[entry.playlist.id]?.second.orEmpty()
                val fromSources = if (entry.tags.hasSources) sourceCache[entry.playlist.id]?.items.orEmpty() else emptyList()
                val items = merged(own, fromSources).filter { it.type == BaseItemKind.MOVIE || it.type == BaseItemKind.EPISODE }.map { it.toChannelItem() }
                val number = numbers[entry.playlist.id.plain()]
                if (items.isEmpty() || number == null) return@mapNotNull null
                val clips = if (entry.tags.hasBreaks)
                    breakClips(fillers[entry.playlist.id].orEmpty().flatMap { itemCache[it.id]?.second.orEmpty() }, entry.tags.fillerTypes)
                else ChannelBreakClips.None
                PlaylistChannel(
                    playlist = entry.playlist, number = number, items = items, lineup = entry.tags.lineup,
                    showsLogo = entry.tags.showsLogo, showsBug = entry.tags.showsBug, bugCorner = entry.tags.bugCorner, bugSize = entry.tags.bugSize,
                    bugHideAfter = entry.tags.bugHideAfter, bugOpacity = entry.tags.bugOpacity, customName = entry.tags.name,
                    breakClips = clips, seriesLogo = PlaylistChannel.seriesLogo(items), group = entry.tags.group,
                )
            }.sortedWith(compareBy<PlaylistChannel>({ it.number }, { it.name })).let(ChannelGroups::ordered)
            _channels.value = built
            ChannelTuner.register(built)
        } catch (e: Exception) {
            if (_channels.value.isEmpty()) _error.value = e.message ?: "Couldn't load"
        }
        _loaded.value = true
    }

    /**
     * Fetches what each channel's genres, networks and collections hold, where it has any: their movies and series
     * (any of them), narrowed by its years, ratings and type, and its libraries for genres and networks. A series
     * brings all its episodes. A channel whose titles haven't changed keeps what it had.
     */
    private suspend fun loadSources(tagged: List<Tagged>, force: Boolean) {
        val sourceChannels = tagged.filter { it.tags.hasSources }
        if (sourceChannels.isEmpty()) return
        if (force) episodeCache.clear()
        val (genreIds, studioIds, collectionIds, libraryIds) = coroutineScope {
            val genres = async { if (sourceChannels.any { it.tags.genres.isNotEmpty() }) runCatching { repo.videoGenres() }.getOrDefault(emptyList()) else emptyList() }
            val studios = async { if (sourceChannels.any { it.tags.networks.isNotEmpty() }) runCatching { repo.videoStudios() }.getOrDefault(emptyList()) else emptyList() }
            val collections = async { if (sourceChannels.any { it.tags.collections.isNotEmpty() }) runCatching { repo.collections() }.getOrDefault(emptyList()) else emptyList() }
            val libraries = async { runCatching { repo.views() }.getOrDefault(emptyList()) }
            listOf(idsByName(genres.await()), idsByName(studios.await()), idsByName(collections.await()), idsByName(libraries.await()))
        }

        // First the titles of every channel, which is quick.
        val titles: Map<UUID, List<BaseItemDto>?> = coroutineScope {
            sourceChannels.map { entry ->
                async {
                    val tags = entry.tags
                    val genres = tags.genres.mapNotNull { genreIds[it.lowercase()] }
                    val studios = tags.networks.mapNotNull { studioIds[it.lowercase()] }
                    val collections = tags.collections.mapNotNull { collectionIds[it.lowercase()] }
                    // Libraries the server doesn't know are ignored; none left means all.
                    val chosen = tags.libraries.mapNotNull { libraryIds[it.lowercase()] }
                    val parents: List<UUID?> = chosen.ifEmpty { listOf(null) }
                    val types = tags.content.itemTypes.split(",").map { if (it == "Movie") BaseItemKind.MOVIE else BaseItemKind.SERIES }
                    try {
                        var found = emptyList<BaseItemDto>()
                        for (parent in parents) {
                            if (genres.isNotEmpty()) found = merged(found, repo.sourceTitles(genreIds = genres, types = types, parentId = parent, years = tags.years, ratings = tags.ratings))
                            if (studios.isNotEmpty()) found = merged(found, repo.sourceTitles(studioIds = studios, types = types, parentId = parent, years = tags.years, ratings = tags.ratings))
                        }
                        // A collection is filtered here, as Jellyfin's filters don't reach into it.
                        for (collection in collections) found = merged(found, repo.collectionTitles(collection).filter { passes(it, tags) })
                        entry.playlist.id to found
                    } catch (e: Exception) { entry.playlist.id to null }
                }
            }.awaitAll().toMap()
        }

        // Then the episodes of each series not already known, a few at a time.
        val wanted = titles.values.filterNotNull().flatten().filter { it.type == BaseItemKind.SERIES }.map { it.id }.toSet()
        val missing = wanted - episodeCache.keys
        val gate = Semaphore(6)
        val fetched: Map<UUID, List<BaseItemDto>> = coroutineScope {
            missing.map { id -> async { gate.withPermit { id to runCatching { repo.seriesEpisodes(id) }.getOrNull() } } }.awaitAll()
        }.mapNotNull { (id, eps) -> eps?.let { id to it } }.toMap()
        episodeCache.putAll(fetched)

        for (entry in sourceChannels) {
            // A fetch that failed keeps what was there.
            val found = titles[entry.playlist.id] ?: continue
            val tags = entry.tags
            val query = listOf(tags.genres, tags.networks, tags.collections, tags.libraries, tags.ratings, listOf(tags.content.itemTypes, tags.years?.let { "${it.first}-${it.last}" } ?: ""))
                .joinToString("/") { it.joinToString("|") }
            val ids = found.map { it.id }
            val previous = sourceCache[entry.playlist.id]
            if (previous != null && previous.query == query && previous.titleIds == ids && found.none { it.type == BaseItemKind.SERIES && fetched[it.id] != null }) continue
            val items = found.flatMap { title -> if (title.type == BaseItemKind.SERIES) episodeCache[title.id].orEmpty() else listOf(title) }
            sourceCache[entry.playlist.id] = Source(query, ids, items)
        }
    }

    companion object {
        /** Whether a title from a collection fits a channel's years, ratings and type. */
        fun passes(item: BaseItemDto, tags: ChannelTags): Boolean {
            when (item.type) {
                BaseItemKind.MOVIE -> if (tags.content == ChannelContent.Shows) return false
                BaseItemKind.SERIES -> if (tags.content == ChannelContent.Movies) return false
                else -> return false
            }
            tags.years?.let { years -> val year = item.productionYear ?: return false; if (year !in years) return false }
            if (tags.ratings.isNotEmpty()) {
                val rating = item.officialRating?.lowercase() ?: return false
                if (tags.ratings.none { it.lowercase() == rating }) return false
            }
            return true
        }

        /** The filler playlists (tagged `aster:filler`) a channel's `filler-playlists=` tags name, matched by name whatever the case. */
        fun fillerPlaylists(tags: ChannelTags, playlists: List<BaseItemDto>): List<BaseItemDto> {
            val fillers = playlists.filter { ChannelTagParsing.isFillerPlaylist(it.tags) }
            return tags.fillerPlaylists.mapNotNull { name -> fillers.firstOrNull { it.name.orEmpty().equals(name, ignoreCase = true) } }
        }

        /** Break clips sorted by what each one's own tag says it is, keeping only the kinds the channel asks for; a clip with no kind tag is left out. */
        fun breakClips(items: List<BaseItemDto>, types: Set<BreakKind>): ChannelBreakClips {
            val bumpers = mutableListOf<ChannelItem>(); val promos = mutableListOf<ChannelItem>(); val commercials = mutableListOf<ChannelItem>()
            val trailers = mutableListOf<ChannelItem>(); val interstitials = mutableListOf<ChannelItem>()
            for (item in items) {
                val kind = ChannelTagParsing.clipKind(item.tags)?.takeIf { it in types } ?: continue
                val clip = item.toChannelItem()
                when (kind) {
                    BreakKind.Bumpers -> bumpers += clip; BreakKind.Promos -> promos += clip; BreakKind.Commercials -> commercials += clip
                    BreakKind.Trailers -> trailers += clip; BreakKind.Interstitials -> interstitials += clip
                }
            }
            return ChannelBreakClips(bumpers, promos, commercials, trailers, interstitials)
        }

        /** Lower-cased names to ids, the first of any name used twice. */
        fun idsByName(items: List<BaseItemDto>): Map<String, UUID> {
            val ids = LinkedHashMap<String, UUID>()
            for (item in items) ids.putIfAbsent(item.name.orEmpty().lowercase(), item.id)
            return ids
        }

        /** A channel's own playlist items, then its sources' titles that aren't already among them. */
        fun merged(own: List<BaseItemDto>, extra: List<BaseItemDto>): List<BaseItemDto> {
            val seen = own.map { it.id }.toMutableSet()
            return own + extra.filter { seen.add(it.id) }
        }

        private fun taggedChannels(playlists: List<BaseItemDto>): List<Tagged> = playlists.mapNotNull { p ->
            val tags = ChannelTags.from(p.tags)
            if (tags.isChannel) Tagged(p, tags) else null
        }
    }

    private fun taggedChannels(playlists: List<BaseItemDto>) = Companion.taggedChannels(playlists)
    private fun fillerPlaylists(tags: ChannelTags, playlists: List<BaseItemDto>) = Companion.fillerPlaylists(tags, playlists)
    private fun breakClips(items: List<BaseItemDto>, types: Set<BreakKind>) = Companion.breakClips(items, types)
    private fun idsByName(items: List<BaseItemDto>) = Companion.idsByName(items)
    private fun merged(own: List<BaseItemDto>, extra: List<BaseItemDto>) = Companion.merged(own, extra)
    private fun passes(item: BaseItemDto, tags: ChannelTags) = Companion.passes(item, tags)
}

/**
 * Channels in named groups, like a cable guide's (the Apple apps' `ChannelGrouping`): a group goes in order of its lowest channel
 * number, its channels by number within it, and channels without a group go under "Other" once any channel has one. Until some
 * channel has a group there are none, and everything stays as it was.
 */
object ChannelGroups {
    const val OTHER = "Other"

    /** [channels] (given by number) in group order, with each group's channels staying in the order given. */
    fun ordered(channels: List<PlaylistChannel>): List<PlaylistChannel> {
        val names = names(channels)
        if (names.isEmpty()) return channels
        val rank = names.withIndex().associate { (i, n) -> n to i }
        return channels.sortedBy { rank[it.group ?: OTHER] ?: 0 }
    }

    /** The groups in order: by their lowest channel number (channels come by number); empty when no channel has a group. */
    fun names(channels: List<PlaylistChannel>): List<String> =
        if (channels.none { it.group != null }) emptyList() else channels.map { it.group ?: OTHER }.distinct()
}
