package com.astermediaplayer.astermediaplayer.data.plex

import com.astermediaplayer.astermediaplayer.data.emby.EmbyIds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Plex's titles in the shapes the Jellyfin SDK's models read, so every screen built for Jellyfin shows them unchanged. The Kotlin
 * counterpart of the Apple apps' `PlexMapper`. Picture fields hold Plex's own paths (a title's `thumb` and `art`) as their tags, which
 * [PlexApiClient] sends through the server's photo transcoder; times go from milliseconds to ticks (100 ns).
 */
object PlexMapper {
    const val TICKS_PER_MS = 10_000L

    /** A Plex id (a rating key or a library's number) as the UUID the app carries it as; anything that isn't a number is hashed. */
    fun id(plexId: String): String = EmbyIds.encode(plexId) ?: UUID.nameUUIDFromBytes(plexId.toByteArray()).toString()

    /** An id the app carries, as Plex writes it. */
    fun plexId(id: String): String = if (isLibraryId(id)) sectionKey(id) else EmbyIds.decode(id)

    // A library's number and a title's can be the same ("1" is a library, and may be a film), so libraries carry their own range.
    private const val LIBRARY_PREFIX = "00000000-0000-0000-0001-"
    fun libraryId(key: String): String = key.toLongOrNull()?.let { LIBRARY_PREFIX + it.toString(16).padStart(12, '0') } ?: UUID.nameUUIDFromBytes(("section:$key").toByteArray()).toString()
    fun isLibraryId(id: String): Boolean = id.length == 36 && id.startsWith(LIBRARY_PREFIX)
    fun sectionKey(id: String): String = id.substringAfterLast('-').toLong(16).toString()

    /** A library section as a library. */
    fun library(directory: JSONObject): JsonObject = buildJsonObject {
        put("Id", libraryId(directory.optString("key")))
        put("Name", directory.optString("title"))
        put("Type", "CollectionFolder")
        put("IsFolder", true)
        put(
            "CollectionType",
            when (directory.optString("type")) { "movie" -> "movies"; "show" -> "tvshows"; "artist" -> "music"; "photo" -> "photos"; else -> "unknown" },
        )
    }

    fun kind(type: String?): String = when (type) {
        "movie" -> "Movie"
        "show" -> "Series"
        "season" -> "Season"
        "episode" -> "Episode"
        "collection" -> "BoxSet"
        "clip" -> "Video"
        else -> "Video"
    }

    /**
     * A playlist, for Channels: its titles counted, its picture the one Plex makes from their posters, and its channel tags read
     * from its description, where they're kept since Plex has no tags on playlists.
     */
    fun playlist(m: JSONObject): JsonObject {
        val base = item(m)
        val picture = m.str("composite") ?: m.str("thumb")
        return JsonObject(
            base + buildJsonObject {
                put("Type", "Playlist")
                put("IsFolder", true)
                put("MediaType", if (m.optString("playlistType") == "audio") "Audio" else "Video")
                m.intOrNull("leafCount")?.let { put("ChildCount", it) }
                putJsonArray("Tags") { com.astermediaplayer.astermediaplayer.data.channels.ChannelTagParsing.fromDescription(m.str("summary")).forEach { add(JsonPrimitive(it)) } }
                if (picture != null) putJsonObject("ImageTags") { put("Primary", picture) }
            },
        )
    }

    fun item(m: JSONObject): JsonObject = buildJsonObject {
        val kind = kind(m.optString("type"))
        val key = m.optString("ratingKey")
        put("Id", id(key))
        put("Type", kind)
        put("Name", m.optString("title"))
        m.str("originalTitle")?.let { put("OriginalTitle", it) }
        m.str("titleSort")?.let { put("SortName", it) }
        m.str("summary")?.let { put("Overview", it) }
        m.str("tagline")?.let { putJsonArray("Taglines") { add(JsonPrimitive(it)) } }
        m.intOrNull("year")?.let { put("ProductionYear", it) }
        m.str("originallyAvailableAt")?.let { put("PremiereDate", it + "T00:00:00Z") }
        m.longOrNull("addedAt")?.let { put("DateCreated", iso(it)) }
        m.str("contentRating")?.let { put("OfficialRating", it) }
        (m.doubleOrNull("audienceRating") ?: m.doubleOrNull("rating"))?.let { put("CommunityRating", it) }
        m.longOrNull("duration")?.let { put("RunTimeTicks", it * TICKS_PER_MS) }
        m.intOrNull("index")?.let { put("IndexNumber", it) }
        m.arr("Genre")?.let { genres -> putJsonArray("Genres") { genres.objects().forEach { add(JsonPrimitive(it.optString("tag"))) } } }
        m.str("studio")?.let { studio -> putJsonArray("Studios") { add(buildJsonObject { put("Name", studio); put("Id", id(studio)) }) } }
        people(m)?.let { put("People", it) }
        providerIds(m)?.let { put("ProviderIds", it) }
        m.arr("Chapter")?.let { chapters ->
            putJsonArray("Chapters") {
                chapters.objects().forEach { c ->
                    add(buildJsonObject {
                        put("StartPositionTicks", c.optLong("startTimeOffset") * TICKS_PER_MS)
                        c.str("tag")?.let { put("Name", it) }
                    })
                }
            }
        }
        put("IsFolder", kind == "Series" || kind == "Season" || kind == "BoxSet")
        put("MediaType", "Video")

        // The first file and its tracks. A list's titles carry the file's size but not its tracks, so the video's width stands in
        // for the HD or 4K badge.
        val media = m.arr("Media")?.objects()?.firstOrNull()
        if (media != null) {
            media.str("container")?.let { put("Container", it) }
            val streams = media.arr("Part")?.objects()?.firstOrNull()?.arr("Stream")?.objects()?.map { stream(it) }.orEmpty()
            val tracks = streams.ifEmpty {
                listOfNotNull(media.intOrNull("width")?.let { w -> buildJsonObject { put("Type", "Video"); put("Index", 0); put("Width", w); media.intOrNull("height")?.let { put("Height", it) } } })
            }
            put("MediaStreams", JsonArray(tracks))
            putJsonArray("MediaSources") {
                add(buildJsonObject {
                    put("Id", media.arr("Part")?.objects()?.firstOrNull()?.optLong("id")?.toString() ?: media.optLong("id").toString())
                    media.str("container")?.let { put("Container", it) }
                    put("MediaStreams", JsonArray(tracks))
                })
            }
        }

        // Pictures: the poster, the backdrop and the logo; a season's and an episode's fall back to their show's.
        val tags = buildJsonObject {
            m.str("thumb")?.let { put("Primary", it) }
            if (kind != "Episode" && kind != "Season") m.arr("Image")?.objects()?.firstOrNull { it.optString("type") == "clearLogo" }?.str("url")?.let { put("Logo", it) }
        }
        if (tags.isNotEmpty()) put("ImageTags", tags)
        if (kind != "Episode") m.str("art")?.let { art -> putJsonArray("BackdropImageTags") { add(JsonPrimitive(art)) } }

        when (kind) {
            "Episode" -> {
                m.str("grandparentRatingKey")?.let { put("SeriesId", id(it)) }
                m.str("grandparentTitle")?.let { put("SeriesName", it) }
                m.str("parentRatingKey")?.let { put("SeasonId", id(it)); put("ParentId", id(it)) }
                m.str("parentTitle")?.let { put("SeasonName", it) }
                m.intOrNull("parentIndex")?.let { put("ParentIndexNumber", it) }
                m.str("grandparentThumb")?.let { put("SeriesPrimaryImageTag", it) }
                (m.str("grandparentArt") ?: m.str("art"))?.let { art ->
                    m.str("grandparentRatingKey")?.let { put("ParentBackdropItemId", id(it)) }
                    putJsonArray("ParentBackdropImageTags") { add(JsonPrimitive(art)) }
                }
                m.arr("Image")?.objects()?.firstOrNull { it.optString("type") == "clearLogo" }?.str("url")?.let { logo ->
                    m.str("grandparentRatingKey")?.let { put("ParentLogoItemId", id(it)) }
                    put("ParentLogoImageTag", logo)
                }
            }
            "Season" -> {
                m.str("parentRatingKey")?.let { put("SeriesId", id(it)); put("ParentId", id(it)) }
                m.str("parentTitle")?.let { put("SeriesName", it) }
                m.str("parentThumb")?.let { put("SeriesPrimaryImageTag", it) }
                m.str("art")?.let { art ->
                    m.str("parentRatingKey")?.let { put("ParentBackdropItemId", id(it)) }
                    putJsonArray("ParentBackdropImageTags") { add(JsonPrimitive(art)) }
                }
                m.intOrNull("leafCount")?.let { put("ChildCount", it) }
            }
            "Series" -> {
                m.intOrNull("childCount")?.let { put("ChildCount", it) }
                m.intOrNull("leafCount")?.let { put("RecursiveItemCount", it) }
            }
            "BoxSet" -> m.intOrNull("childCount")?.let { put("ChildCount", it) }
        }
        put("UserData", userData(m, kind))
    }

    /** Where it was left, and whether it's been watched: a show or season by its episodes. */
    fun userData(m: JSONObject, kind: String): JsonObject = buildJsonObject {
        put("Key", m.optString("ratingKey"))
        put("IsFavorite", false)
        val leaves = m.intOrNull("leafCount")
        if (leaves != null && (kind == "Series" || kind == "Season")) {
            val watched = m.intOrNull("viewedLeafCount") ?: 0
            put("UnplayedItemCount", maxOf(0, leaves - watched))
            put("Played", leaves > 0 && watched >= leaves)
            put("PlayCount", 0)
        } else {
            val count = m.intOrNull("viewCount") ?: 0
            put("Played", count > 0)
            put("PlayCount", count)
        }
        val offset = m.longOrNull("viewOffset")
        if (offset != null && offset > 0) {
            put("PlaybackPositionTicks", offset * TICKS_PER_MS)
            val duration = m.longOrNull("duration")
            if (duration != null && duration > 0) put("PlayedPercentage", offset.toDouble() / duration * 100)
        } else put("PlaybackPositionTicks", 0)
        m.longOrNull("lastViewedAt")?.let { put("LastPlayedDate", iso(it)) }
    }

    /** The cast first, then who directed and wrote it. */
    private fun people(m: JSONObject): JsonArray? {
        fun person(tag: JSONObject, type: String) = buildJsonObject {
            put("Id", id(tag.opt("id")?.toString() ?: tag.optString("tag")))
            put("Name", tag.optString("tag"))
            tag.str("role")?.let { put("Role", it) }
            put("Type", type)
            tag.str("thumb")?.let { put("PrimaryImageTag", it) }
        }
        val all = m.arr("Role").orEmptyObjects().map { person(it, "Actor") } +
            m.arr("Director").orEmptyObjects().map { person(it, "Director") } +
            m.arr("Writer").orEmptyObjects().map { person(it, "Writer") }
        return if (all.isEmpty()) null else JsonArray(all)
    }

    /** "tmdb://5439847" as Tmdb, and so on; Shoko's own id marks anime. */
    private fun providerIds(m: JSONObject): JsonObject? {
        val ids = linkedMapOf<String, String>()
        m.arr("Guid")?.objects()?.forEach { g ->
            val parts = g.optString("id").split("://")
            if (parts.size != 2) return@forEach
            val name = when (parts[0]) { "tmdb" -> "Tmdb"; "imdb" -> "Imdb"; "tvdb" -> "Tvdb"; else -> parts[0].replaceFirstChar { it.uppercase() } }
            ids[name] = parts[1]
        }
        m.str("guid")?.takeIf { ".shoko://" in it }?.let { ids["Shoko"] = it.substringAfterLast('/') }
        return if (ids.isEmpty()) null else JsonObject(ids.mapValues { JsonPrimitive(it.value) })
    }

    /** A Plex track as a media stream. */
    fun stream(s: JSONObject): JsonObject = buildJsonObject {
        put("Type", when (s.optInt("streamType")) { 1 -> "Video"; 2 -> "Audio"; 3 -> "Subtitle"; else -> "Data" })
        s.intOrNull("index")?.let { put("Index", it) }
        s.str("codec")?.let { put("Codec", it) }
        s.str("languageCode")?.let { put("Language", it) }
        s.str("displayTitle")?.let { put("DisplayTitle", it) }
        s.str("title")?.let { put("Title", it) }
        put("IsDefault", s.optBoolean("default"))
        put("IsForced", s.optBoolean("forced"))
        put("IsExternal", s.has("key"))
        s.intOrNull("width")?.let { put("Width", it) }
        s.intOrNull("height")?.let { put("Height", it) }
        s.intOrNull("channels")?.let { put("Channels", it) }
    }

    fun queryResult(items: List<JsonElement>, total: Int, start: Int = 0): JsonObject = buildJsonObject {
        put("Items", JsonArray(items))
        put("TotalRecordCount", total)
        put("StartIndex", start)
    }

    private fun iso(seconds: Long): String = Instant.ofEpochSecond(seconds).toString()
}

// JSONObject conveniences: Plex omits fields freely, so absent and null both read as missing.
internal fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null
internal fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
internal fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
internal fun JSONObject.doubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
internal fun JSONObject.arr(key: String): JSONArray? = optJSONArray(key)
internal fun JSONArray.objects(): List<JSONObject> = List(length()) { optJSONObject(it) ?: JSONObject() }
internal fun JSONArray?.orEmptyObjects(): List<JSONObject> = this?.objects().orEmpty()
