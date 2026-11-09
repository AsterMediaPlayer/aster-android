package com.astermediaplayer.astermediaplayer.data

import org.json.JSONArray
import org.json.JSONObject

/*
 * The Aster Discovery and Tracking plugin (routes under /AsterTracking), as the clients' guide in the AsterMediaPlayer
 * organisation's .github repository (docs/aster-tracking-clients.md) lays it out. The server builds each person's whole
 * Discover page; the app shows it as it comes. JSON is PascalCase, read here without regard to case.
 */

/** What `GET /AsterTracking/Features` says the server offers. */
data class TrackingFeatures(
    val seerrEnabled: Boolean,
    /** Seerr's API through the server, as a path on it: "/AsterTracking/Seerr/api/v1". */
    val seerrProxy: String?,
    /** What people can link on this server: "simkl", "anilist", … */
    val services: List<String>,
    /** The services whose watchlist the apps can add to (`/AsterTracking/Watchlist`): "simkl"; empty on an older plugin. */
    val watchlist: List<String> = emptyList(),
    /**
     * Whether the server builds a Discover page (`/AsterTracking/Discover/Home`): the Jellyfin plugin does, and says
     * nothing; Aster for Plex doesn't yet, and says `"Discover": false`.
     */
    val discover: Boolean = true,
) {
    companion object {
        fun parse(json: String): TrackingFeatures {
            val root = JSONObject(json)
            val seerr = root.ci("Seerr") as? JSONObject
            return TrackingFeatures(
                seerrEnabled = (seerr?.ci("Enabled") as? Boolean) == true,
                seerrProxy = (seerr?.ci("Proxy") as? String)?.ifEmpty { null },
                services = (root.ci("Services") as? JSONArray).strings(),
                watchlist = (root.ci("Watchlist") as? JSONArray).strings(),
                discover = root.ci("Discover") as? Boolean ?: true,
            )
        }
    }
}

/** The film or show on this server that a Discover title is. */
data class DiscoverLibraryItem(val itemId: String, val name: String?, val type: String?)

/** A title on the Discover page, from a tracking site, TMDB or the server itself. */
data class DiscoverItem(
    val title: String,
    val year: Int?,
    /** "movie" or "tv", as Seerr and TMDB split titles. */
    val mediaType: String?,
    val anime: Boolean,
    /** A full web address, or a path on the Jellyfin server for its own titles. */
    val poster: String?,
    val overview: String?,
    /** "Episode 4 of 12", on Continue Watching. */
    val progress: String?,
    /** tmdb, tvdb, imdb, anilist, mal, anidb, tmdbSeason… */
    val ids: Map<String, String>,
    val inLibrary: DiscoverLibraryItem?,
) {
    val tmdbId: String? get() = ids.entries.firstOrNull { it.key.equals("tmdb", ignoreCase = true) }?.value?.ifEmpty { null }

    /** The card's second line: its progress, else its year and kind. */
    val subtitle: String?
        get() = progress?.ifEmpty { null } ?: listOfNotNull(
            year?.toString(),
            if (mediaType.equals("movie", ignoreCase = true)) "Film" else "Series",
        ).joinToString(" · ").ifEmpty { null }

    /** A stable key within its row. */
    val key: String get() = inLibrary?.itemId ?: tmdbId?.let { "tmdb:$mediaType:$it" } ?: ids.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }.ifEmpty { "$title|$year" }

    companion object {
        fun parse(o: JSONObject): DiscoverItem {
            val ids = (o.ci("Ids") as? JSONObject)?.let { j -> j.keys().asSequence().mapNotNull { k -> j.opt(k)?.takeIf { it != JSONObject.NULL }?.let { k to it.toString() } }.toMap() }.orEmpty()
            val library = (o.ci("InLibrary") as? JSONObject)?.let { l ->
                (l.ci("ItemId") as? String)?.ifEmpty { null }?.let { DiscoverLibraryItem(it, l.ci("Name") as? String, l.ci("Type") as? String) }
            }
            return DiscoverItem(
                title = (o.ci("Title") as? String).orEmpty(),
                year = (o.ci("Year") as? Number)?.toInt(),
                mediaType = o.ci("MediaType") as? String,
                anime = (o.ci("Anime") as? Boolean) == true,
                poster = (o.ci("Poster") as? String)?.ifEmpty { null },
                overview = (o.ci("Overview") as? String)?.ifEmpty { null },
                progress = (o.ci("Progress") as? String)?.ifEmpty { null },
                ids = ids,
                inLibrary = library,
            )
        }
    }
}

/** A shelf on the Discover page: `continue`, `recommended`, `because`, `library`, `watchlist`, `trending`, `season`. */
data class DiscoverRow(val id: String, val kind: String, val title: String, val subtitle: String?, val items: List<DiscoverItem>)

/** `GET /AsterTracking/Discover/Home`: the rows in the order to show them. */
data class DiscoverPage(val rows: List<DiscoverRow>, val built: String?) {
    companion object {
        fun parse(json: String): DiscoverPage {
            val root = JSONObject(json)
            val rows = (root.ci("Rows") as? JSONArray).objects().mapIndexed { i, r ->
                DiscoverRow(
                    id = (r.ci("Id") as? String)?.ifEmpty { null } ?: "row$i",
                    kind = (r.ci("Kind") as? String).orEmpty(),
                    title = (r.ci("Title") as? String).orEmpty(),
                    subtitle = (r.ci("Subtitle") as? String)?.ifEmpty { null },
                    // The same title twice in a row would trip the shelf's keys.
                    items = (r.ci("Items") as? JSONArray).objects().map(DiscoverItem::parse).filter { it.title.isNotEmpty() }.distinctBy { it.key },
                )
            }.filter { it.items.isNotEmpty() }
            return DiscoverPage(rows, root.ci("Built") as? String)
        }

        /**
         * Where a `Poster` points: a full http(s) address as it is, else a path on the server, put on its address. Only
         * http and https count as full: a path like "/Items/…" must never be taken for a file address.
         */
        fun posterUrl(poster: String?, serverUrl: String): String? {
            val p = poster?.trim()?.ifEmpty { null } ?: return null
            if (p.startsWith("http://", ignoreCase = true) || p.startsWith("https://", ignoreCase = true)) return p
            if (p.startsWith("//")) return "https:$p"
            return serverUrl.trimEnd('/') + (if (p.startsWith("/")) p else "/$p")
        }
    }
}

/** A key's value, whatever its case: the server writes PascalCase, and nothing promises it always will. */
internal fun JSONObject.ci(name: String): Any? {
    if (has(name)) return opt(name).takeIf { it != JSONObject.NULL }
    val key = keys().asSequence().firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
    return opt(key).takeIf { it != JSONObject.NULL }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }

/** One row of Discover, whichever it comes from. */
sealed interface DiscoverEntry {
    val key: String
    data class Tracked(val row: DiscoverRow) : DiscoverEntry { override val key get() = "tracked-${row.id}" }
    data class Slider(val slider: com.astermediaplayer.astermediaplayer.data.seerr.SeerrSlider) : DiscoverEntry { override val key get() = "slider-${slider.stableId}" }
    data object InTheaters : DiscoverEntry { override val key get() = "theaters" }
}

object DiscoverOrder {
    /**
     * The page in the contract's order, as the Apple and Linux apps do it. The server's own rows `continue`, `recommended`,
     * `because`, `library` and `watchlist` come first (then any of a kind not known yet); then Seerr's sliders in their admin's
     * order, less its Watchlist (type 3, Plex's only) when the server has its own; and the server's `trending` and `season` in
     * the place of Seerr's Trending (type 4), followed by In Theaters. Seerr's Trending stays when the server has none. With no
     * Seerr, only the server's rows, in the order given.
     */
    fun order(rows: List<DiscoverRow>, sliders: List<com.astermediaplayer.astermediaplayer.data.seerr.SeerrSlider>?, theaters: Boolean): List<DiscoverEntry> {
        val shown = rows.filter { it.items.isNotEmpty() }
        if (sliders == null) return shown.map(DiscoverEntry::Tracked)
        val leading = setOf("continue", "recommended", "because", "library", "watchlist")
        val kept = leading + setOf("trending", "season")
        val out = shown.filter { it.kind in leading }.map<DiscoverRow, DiscoverEntry>(DiscoverEntry::Tracked).toMutableList()
        out += shown.filter { it.kind !in kept }.map(DiscoverEntry::Tracked)
        val hasWatchlist = shown.any { it.kind == "watchlist" }
        val hasTrending = shown.any { it.kind == "trending" }
        val trending = shown.filter { it.kind == "trending" || it.kind == "season" }.map(DiscoverEntry::Tracked)
        for (slider in sliders) {
            if (slider.type == 3 && hasWatchlist) continue
            if (slider.type == 4) {
                if (hasTrending) out += trending else out += DiscoverEntry.Slider(slider)
                if (theaters) out += DiscoverEntry.InTheaters
                continue
            }
            out += DiscoverEntry.Slider(slider)
        }
        // No Trending among Seerr's rows: the server's go last.
        if (sliders.none { it.type == 4 }) out += trending
        return out
    }
}

/** The title as Seerr knows it, by its TMDB id, for its page. */
fun DiscoverItem.seerrTitle(): com.astermediaplayer.astermediaplayer.data.seerr.SeerrTitle? {
    val id = tmdbId?.toIntOrNull() ?: return null
    val movie = mediaType.equals("movie", ignoreCase = true)
    return com.astermediaplayer.astermediaplayer.data.seerr.SeerrTitle(
        id = id, mediaType = if (movie) "movie" else "tv",
        title = if (movie) title else null, name = if (movie) null else title,
        releaseDate = if (movie) year?.let { "$it-01-01" } else null, firstAirDate = if (movie) null else year?.let { "$it-01-01" },
        overview = overview,
    )
}
