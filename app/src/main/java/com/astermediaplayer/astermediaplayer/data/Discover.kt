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
) {
    companion object {
        fun parse(json: String): TrackingFeatures {
            val root = JSONObject(json)
            val seerr = root.ci("Seerr") as? JSONObject
            return TrackingFeatures(
                seerrEnabled = (seerr?.ci("Enabled") as? Boolean) == true,
                seerrProxy = (seerr?.ci("Proxy") as? String)?.ifEmpty { null },
                services = (root.ci("Services") as? JSONArray).strings(),
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
            when {
                anime -> "Anime"
                mediaType.equals("movie", ignoreCase = true) -> "Film"
                mediaType.equals("tv", ignoreCase = true) -> "Series"
                else -> null
            },
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
