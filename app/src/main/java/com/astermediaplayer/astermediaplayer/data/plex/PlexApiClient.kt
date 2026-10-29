package com.astermediaplayer.astermediaplayer.data.plex

import com.astermediaplayer.astermediaplayer.data.emby.EmbyShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import com.astermediaplayer.astermediaplayer.data.credentialSafeRedirects
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What the client asks of a Plex server: the HTTP, so the answers can be canned in a test. */
interface PlexTransport {
    val baseUrl: String
    val token: String

    /** A GET's body. Paging goes by Plex's own headers (as query parameters they're ignored). */
    suspend fun get(path: String, query: Map<String, String?> = emptyMap(), start: Int? = null, size: Int? = null, session: String? = null): String

    /** A request whose answer isn't read. */
    suspend fun send(method: String, path: String, query: Map<String, String?> = emptyMap(), session: String? = null)

    /** A request other than a GET whose answer is read (a playlist made). */
    suspend fun call(method: String, path: String, query: Map<String, String?> = emptyMap()): String

    /** An address on the server, with [query] and the token in it, for a player or picture loader that can't send headers. */
    fun url(path: String, query: Map<String, String?> = emptyMap()): String
}

class OkHttpPlexTransport(override val baseUrl: String, override val token: String, private val device: PlexDevice, private val http: OkHttpClient = DEFAULT) : PlexTransport {
    override fun url(path: String, query: Map<String, String?>): String {
        val builder = (baseUrl.trimEnd('/') + path).toHttpUrl().newBuilder()
        query.forEach { (k, v) -> if (v != null) builder.addQueryParameter(k, v) }
        return builder.build().toString()
    }

    private fun request(method: String, path: String, query: Map<String, String?>, start: Int?, size: Int?, session: String?): Request {
        val builder = Request.Builder().url(url(path, query))
        device.headers(token).forEach { (k, v) -> builder.header(k, v) }
        start?.let { builder.header("X-Plex-Container-Start", it.toString()) }
        size?.let { builder.header("X-Plex-Container-Size", it.toString()) }
        session?.let { builder.header("X-Plex-Session-Identifier", it) }
        return builder.method(method, if (method == "GET") null else ByteArray(0).toRequestBody()).build()
    }

    override suspend fun get(path: String, query: Map<String, String?>, start: Int?, size: Int?, session: String?): String = withContext(Dispatchers.IO) {
        http.newCall(request("GET", path, query, start, size, session)).execute().use { r ->
            if (!r.isSuccessful) throw PlexHttpException(r.code)
            r.body.string()
        }
    }

    override suspend fun send(method: String, path: String, query: Map<String, String?>, session: String?) {
        withContext(Dispatchers.IO) {
            http.newCall(request(method, path, query, null, null, session)).execute().use { r -> if (!r.isSuccessful) throw PlexHttpException(r.code) }
        }
    }

    override suspend fun call(method: String, path: String, query: Map<String, String?>): String = withContext(Dispatchers.IO) {
        http.newCall(request(method, path, query, null, null, null)).execute().use { r ->
            if (!r.isSuccessful) throw PlexHttpException(r.code)
            r.body.string()
        }
    }

    companion object { val DEFAULT: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).credentialSafeRedirects().build() }
}

/**
 * The Jellyfin SDK's client for a Plex Media Server: every call the app makes, answered from Plex and in the shapes the SDK's models
 * read, so the repository and every screen work unchanged (the Apple apps do this with their `MediaServer` protocol; here the seam is the
 * SDK's own client, as for Emby). Calls Plex has no answer to (Live TV, music) get a 404, which the app already treats
 * as a feature the server doesn't have.
 */
class PlexApiClient(
    private val inner: ApiClient,
    private val plex: PlexTransport,
    private val device: PlexDevice,
    private val log: (String) -> Unit = { android.util.Log.w("Aster", it) },
) : ApiClient() {
    override val baseUrl: String? get() = inner.baseUrl
    override val accessToken: String? get() = inner.accessToken
    override val clientInfo: ClientInfo get() = inner.clientInfo
    override val deviceInfo: DeviceInfo get() = inner.deviceInfo
    override val httpClientOptions: HttpClientOptions get() = inner.httpClientOptions
    override val webSocket: SocketApi get() = inner.webSocket

    override fun update(baseUrl: String?, accessToken: String?, clientInfo: ClientInfo, deviceInfo: DeviceInfo) = inner.update(baseUrl, accessToken, clientInfo, deviceInfo)

    private val sectionsLock = Mutex()
    private var sectionsCache: List<JSONObject>? = null

    /** How long each title is, in milliseconds, as playback learned it: the timeline wants it. */
    private val durations = ConcurrentHashMap<String, Long>()

    /** A subtitle file's cues, fetched from the server and read. */
    suspend fun subtitleCues(itemId: String, streamIndex: Int): List<com.astermediaplayer.astermediaplayer.data.SubtitleCue> {
        val stream = metadata(PlexMapper.plexId(itemId)).optJSONArray("Media")?.objects()?.firstOrNull()?.optJSONArray("Part")?.objects()?.firstOrNull()
            ?.optJSONArray("Stream")?.objects()?.firstOrNull { it.optInt("streamType") == 3 && it.optInt("index") == streamIndex && it.has("key") }
            ?: error("That subtitle track isn't a file on the Plex server.")
        return PlexSubtitles.cues(plex.get(stream.getString("key")), stream.str("codec"))
    }

    // MARK: Addresses the app builds itself

    override fun createUrl(pathTemplate: String, pathParameters: Map<String, Any?>, queryParameters: Map<String, Any?>, ignorePathParameters: Boolean): String = when (pathTemplate) {
        "/Items/{itemId}/Images/{imageType}", "/Items/{itemId}/Images/{imageType}/{imageIndex}" -> imageUrl(pathParameters, queryParameters)
        "/Videos/{itemId}/stream" -> {
            val part = queryParameters["mediaSourceId"]?.toString().orEmpty()
            plex.url(
                "/library/parts/$part/file",
                mapOf("X-Plex-Token" to plex.token, "X-Plex-Client-Identifier" to device.clientIdentifier, "X-Plex-Session-Identifier" to queryParameters["playSessionId"]?.toString()),
            )
        }
        else -> inner.createUrl(pathTemplate, pathParameters, queryParameters, ignorePathParameters)
    }

    /**
     * `tag` is the picture's path on the server (a `thumb` or `art`), resized by the server's photo transcoder to fit inside the size asked
     * for (the free side is left three times as long, so it never decides). Logos come as PNG, keeping their transparency. A tag that is
     * already an address (a person's photo) is used as it is.
     */
    private fun imageUrl(path: Map<String, Any?>, query: Map<String, Any?>): String {
        val tag = query["tag"]?.toString()
        if (tag != null && tag.startsWith("http")) return tag
        val type = path["imageType"]?.toString().orEmpty().lowercase()
        val id = PlexMapper.plexId(path["itemId"]?.toString().orEmpty())
        val source = if (tag != null && tag.startsWith("/")) tag else "/library/metadata/$id/" + when (type) { "backdrop" -> "art"; "logo" -> "clearLogo"; else -> "thumb" }
        val width = (query["maxWidth"] as? Number)?.toInt()
        val height = (query["maxHeight"] as? Number)?.toInt()
        if (width == null && height == null) return plex.url(source, mapOf("X-Plex-Token" to plex.token))
        return plex.url(
            "/photo/:/transcode",
            mapOf(
                "url" to source, "width" to (width ?: height!! * 3).toString(), "height" to (height ?: width!! * 3).toString(),
                "minSize" to "0", "upscale" to "1", "quality" to "90", "format" to if (type == "logo") "png" else null, "X-Plex-Token" to plex.token,
            ),
        )
    }

    // MARK: Requests

    override suspend fun request(
        method: HttpMethod, pathTemplate: String, pathParameters: Map<String, Any?>, queryParameters: Map<String, Any?>, requestBody: Any?,
    ): RawResponse = withContext(Dispatchers.IO) {
        try {
            val body = requestBody?.let { ApiSerializer.encodeRequestBody(it) }?.let { Json.parseToJsonElement(it) as? JsonObject }
            handle(method, pathTemplate, pathParameters, Query(queryParameters), body) ?: empty(404)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlexHttpException) {
            log("Plex $method $pathTemplate answered ${e.code}")
            empty(e.code)
        } catch (e: Exception) {
            log("Plex $method $pathTemplate failed: $e")
            empty(500)
        }
    }

    private fun empty(status: Int) = RawResponse(ByteArray(0), status, emptyMap())

    private fun ok(json: JsonElement, fill: Boolean = true) = RawResponse((if (fill) EmbyShapes.fill(json) else json).toString().encodeToByteArray(), 200, emptyMap())

    private suspend fun handle(method: HttpMethod, template: String, path: Map<String, Any?>, q: Query, body: JsonObject?): RawResponse? {
        val itemId = path["itemId"]?.toString()?.let(PlexMapper::plexId)
        return when (method to template) {
            HttpMethod.GET to "/UserViews" -> sections().let { ok(PlexMapper.queryResult(it.map(PlexMapper::library), it.size)) }
            HttpMethod.GET to "/Items" -> items(q)
            HttpMethod.GET to "/Items/{itemId}" -> ok(itemOrLibrary(path["itemId"].toString()))
            // Channels: a playlist's titles, its tags kept in its description, and a playlist made from a show.
            HttpMethod.GET to "/Playlists/{playlistId}/Items" -> {
                val found = titles("/playlists/${PlexMapper.plexId(path["playlistId"].toString())}/items")
                ok(PlexMapper.queryResult(found.map(PlexMapper::item), found.size))
            }
            HttpMethod.POST to "/Items/{itemId}" -> saveTags(itemId!!, body)
            HttpMethod.POST to "/Playlists" -> createPlaylist(body)
            HttpMethod.GET to "/Items/Latest" -> ok(JsonArray(latest(q)))
            HttpMethod.GET to "/UserItems/Resume" -> resume(q)
            HttpMethod.GET to "/Shows/NextUp" -> nextUp(q)
            HttpMethod.GET to "/Shows/{seriesId}/Seasons" -> {
                val seasons = titles("/library/metadata/${PlexMapper.plexId(path["seriesId"].toString())}/children").filter { it.optString("type") == "season" }
                ok(PlexMapper.queryResult(seasons.map(PlexMapper::item), seasons.size))
            }
            HttpMethod.GET to "/Shows/{seriesId}/Episodes" -> {
                val series = PlexMapper.plexId(path["seriesId"].toString())
                val season = q.string("seasonId")?.let(PlexMapper::plexId)
                val episodes = titles(if (season != null) "/library/metadata/$season/children" else "/library/metadata/$series/allLeaves")
                ok(PlexMapper.queryResult(episodes.map(PlexMapper::item), episodes.size))
            }
            HttpMethod.GET to "/Items/{itemId}/Similar" -> {
                val found = titles("/library/metadata/$itemId/similar", mapOf("count" to (q.int("limit") ?: 12).toString()))
                ok(PlexMapper.queryResult(found.map(PlexMapper::item), found.size))
            }
            HttpMethod.GET to "/Items/{itemId}/SpecialFeatures" -> ok(JsonArray(titles("/library/metadata/$itemId/extras").map(PlexMapper::item)))
            HttpMethod.GET to "/Search/Hints" -> searchHints(q)
            HttpMethod.POST to "/Items/{itemId}/PlaybackInfo" -> playbackInfo(itemId!!, body)
            HttpMethod.POST to "/Sessions/Playing" -> timeline(body, if (body.bool("IsPaused")) "paused" else "playing")
            HttpMethod.POST to "/Sessions/Playing/Progress" -> timeline(body, if (body.bool("IsPaused")) "paused" else "playing")
            HttpMethod.POST to "/Sessions/Playing/Stopped" -> timeline(body, "stopped")
            HttpMethod.POST to "/Sessions/Logout" -> empty(204)
            HttpMethod.POST to "/UserPlayedItems/{itemId}" -> { plex.send("GET", "/:/scrobble", mapOf("key" to itemId, "identifier" to IDENTIFIER)); played(true) }
            HttpMethod.DELETE to "/UserPlayedItems/{itemId}" -> { plex.send("GET", "/:/unscrobble", mapOf("key" to itemId, "identifier" to IDENTIFIER)); played(false) }
            HttpMethod.GET to "/MediaSegments/{itemId}" -> {
                val segments = PlexPlayback.segments(metadata(itemId!!), itemId)
                ok(PlexMapper.queryResult(segments, segments.size), fill = false)
            }
            HttpMethod.GET to "/Users/Me" -> ok(buildJsonObject {
                put("Name", "Plex")
                put("Id", PlexMapper.id("1"))
                putJsonObject("Policy") { put("IsAdministrator", false) }
            })
            HttpMethod.GET to "/System/Info/Public" -> ok(buildJsonObject { put("ProductName", "Plex Media Server"); put("ServerName", "Plex") }, fill = false)
            HttpMethod.GET to "/Genres", HttpMethod.GET to "/Studios" -> ok(PlexMapper.queryResult(emptyList(), 0))
            else -> null
        }
    }

    private fun played(played: Boolean) = ok(buildJsonObject {
        put("PlaybackPositionTicks", 0); put("PlayCount", if (played) 1 else 0); put("IsFavorite", false); put("Played", played); put("Key", "")
    })

    // MARK: Plex's answers

    private suspend fun container(path: String, query: Map<String, String?> = emptyMap(), start: Int? = null, size: Int? = null): JSONObject =
        JSONObject(plex.get(path, query, start, size)).getJSONObject("MediaContainer")

    private suspend fun titles(path: String, query: Map<String, String?> = emptyMap(), start: Int? = null, size: Int? = null): List<JSONObject> =
        container(path, query, start, size).optJSONArray("Metadata")?.objects().orEmpty()

    private suspend fun metadata(id: String): JSONObject =
        titles("/library/metadata/$id", mapOf("includeGuids" to "1", "includeMarkers" to "1", "includeChapters" to "1")).firstOrNull() ?: throw PlexHttpException(404)

    /** The server's movie and TV libraries, asked once. Music and photos come later on Plex. */
    private suspend fun sections(): List<JSONObject> = sectionsLock.withLock {
        sectionsCache ?: container("/library/sections").optJSONArray("Directory")?.objects().orEmpty()
            .filter { it.optString("type") == "movie" || it.optString("type") == "show" }.also { sectionsCache = it }
    }

    private suspend fun itemOrLibrary(id: String): JsonObject =
        if (PlexMapper.isLibraryId(id)) sections().firstOrNull { PlexMapper.libraryId(it.optString("key")) == id }?.let(PlexMapper::library) ?: throw PlexHttpException(404)
        else runCatching { PlexMapper.item(metadata(PlexMapper.plexId(id))) }.getOrElse { e ->
            // A playlist isn't in the library's metadata: Plex keeps it under /playlists.
            container("/playlists/${PlexMapper.plexId(id)}").optJSONArray("Metadata")?.objects()?.firstOrNull()?.let(PlexMapper::playlist) ?: throw e
        }

    /**
     * The user's playlists of one media type ("video" unless asked): Plex keeps a playlist to the account that made it. It has no
     * tags on playlists, so a channel's are kept in its description (see ChannelTagParsing.fromDescription). As the Windows app.
     */
    private suspend fun playlists(q: Query): RawResponse {
        val type = q.list("mediaTypes").firstOrNull() ?: "video"
        val found = titles("/playlists", mapOf("playlistType" to type))
            .filter { it.optString("playlistType").equals(type, ignoreCase = true) }
            .sortedBy { it.optString("title").lowercase() }
        return ok(PlexMapper.queryResult(found.map(PlexMapper::playlist), found.size))
    }

    /** A channel's tags saved: written into its playlist's description, keeping the description's own words. */
    private suspend fun saveTags(id: String, body: JsonObject?): RawResponse {
        val tags = (body?.get("Tags") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return empty(400)
        val current = container("/playlists/$id").optJSONArray("Metadata")?.objects()?.firstOrNull()?.optString("summary")
        plex.send("PUT", "/playlists/$id", mapOf("summary" to com.astermediaplayer.astermediaplayer.data.channels.ChannelTagParsing.inDescription(current, tags)))
        return empty(204)
    }

    /** A video playlist of the titles asked for, the user's own (a show's episodes, for a channel made from it); its id. */
    private suspend fun createPlaylist(body: JsonObject?): RawResponse {
        val name = (body?.get("Name") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return empty(400)
        val ids = (body["Ids"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.let(PlexMapper::plexId) }.orEmpty()
        val machine = machineIdentifier ?: container("/identity").optString("machineIdentifier").takeIf { it.isNotEmpty() }?.also { machineIdentifier = it } ?: return empty(500)
        val made = JSONObject(plex.call("POST", "/playlists", mapOf(
            "type" to "video", "title" to name, "smart" to "0",
            "uri" to "server://$machine/com.plexapp.plugins.library/library/metadata/${ids.joinToString(",")}",
        ))).getJSONObject("MediaContainer").optJSONArray("Metadata")?.objects()?.firstOrNull()?.optString("ratingKey")?.takeIf { it.isNotEmpty() } ?: return empty(500)
        return ok(buildJsonObject { put("Id", PlexMapper.id(made)) }, fill = false)
    }

    /** The server's own id, which a playlist's titles are named by; asked once. */
    private var machineIdentifier: String? = null

    /** Plex's number for a kind of title, in a section's listing. */
    private fun typeNumber(kind: String): String? = when (kind) { "movie" -> "1"; "series" -> "2"; "season" -> "3"; "episode" -> "4"; "boxset" -> "18"; else -> null }

    private fun sortParameter(q: Query): String? {
        val field = when (q.list("sortBy").firstOrNull()) {
            null -> return null
            "sortname", "name" -> "titleSort"
            "datecreated", "datelastcontentadded" -> "addedAt"
            "premieredate" -> "originallyAvailableAt"
            "productionyear" -> "year"
            "communityrating", "criticrating" -> "audienceRating"
            "random" -> "random"
            "playcount" -> "viewCount"
            "dateplayed" -> "lastViewedAt"
            "indexnumber" -> "index"
            else -> "titleSort"
        }
        return if (q.list("sortOrder").firstOrNull() == "descending") "$field:desc" else field
    }

    /** What Jellyfin's items query asks for, from Plex's libraries: a library, a show's or collection's own titles, or every library holding these kinds. */
    private suspend fun items(q: Query): RawResponse {
        val ids = q.list("ids")
        if (ids.isNotEmpty()) {
            val found = titles("/library/metadata/" + q.rawList("ids").joinToString(",") { PlexMapper.plexId(it) })
            return ok(PlexMapper.queryResult(found.map(PlexMapper::item), found.size))
        }
        if ("playlist" in q.list("includeItemTypes")) return playlists(q)
        // Plex keeps no favourites.
        if ("isfavorite" in q.list("filters")) return ok(PlexMapper.queryResult(emptyList(), 0))
        val kinds = q.list("includeItemTypes")
        val parent = q.string("parentId")
        val sections = sections()
        var parameters = mutableMapOf<String, String?>("sort" to sortParameter(q))
        kinds.singleOrNull()?.let { parameters["type"] = typeNumber(it) }
        if ("isunplayed" in q.list("filters")) parameters["unwatched"] = "1"
        if ("isplayed" in q.list("filters")) parameters["unwatched"] = "0"

        // A show's, season's or collection's own titles.
        if (parent != null && !PlexMapper.isLibraryId(parent)) {
            val id = PlexMapper.plexId(parent)
            val found = if (q.bool("recursive") && "episode" in kinds) titles("/library/metadata/$id/allLeaves") else titles("/library/metadata/$id/children")
            val shown = if (kinds.isEmpty()) found else found.filter { typeNumber(PlexMapper.kind(it.optString("type")).lowercase()) in kinds.mapNotNull(::typeNumber) }
            return ok(PlexMapper.queryResult(shown.map(PlexMapper::item), shown.size))
        }

        // One library, or every library holding these kinds, one after another: a page that runs past the end of one carries on into the next.
        val wanted = if (parent != null) sections.filter { PlexMapper.libraryId(it.optString("key")) == parent } else {
            val types = kinds.mapNotNull { when (it) { "movie" -> "movie"; "series", "season", "episode" -> "show"; else -> null } }.toSet()
            sections.filter { types.isEmpty() || it.optString("type") in types }
        }
        var start = q.int("startIndex") ?: 0
        var remaining = q.int("limit") ?: Int.MAX_VALUE
        val found = mutableListOf<JsonElement>()
        var total = 0
        for (section in wanted) {
            val key = section.optString("key")
            val sectionPath = "/library/sections/$key/all"
            // A library of shows with no kind named lists its shows, not its episodes.
            val query = parameters.toMutableMap().also { if (it["type"] == null) it["type"] = if (section.optString("type") == "movie") "1" else "2" }
            val size = container(sectionPath, query, 0, 0).intOrNull("totalSize") ?: 0
            total += size
            if (remaining <= 0 || start >= size) { start = maxOf(0, start - size); continue }
            val page = titles(sectionPath, query, start, minOf(remaining, size - start))
            found += page.map(PlexMapper::item)
            remaining -= page.size
            start = 0
        }
        return ok(PlexMapper.queryResult(found, total, q.int("startIndex") ?: 0))
    }

    /** Newest first: films by when they were added, shows by their newest episode (as Jellyfin's Recently Added groups episodes by show). */
    private suspend fun latest(q: Query): List<JsonElement> {
        val limit = q.int("limit") ?: 20
        val parent = q.string("parentId")
        val kinds = q.list("includeItemTypes")
        val wanted = sections().filter { s ->
            if (parent != null) PlexMapper.libraryId(s.optString("key")) == parent
            else kinds.isEmpty() || kinds.any { (it == "movie") == (s.optString("type") == "movie") }
        }
        val found = mutableListOf<JSONObject>()
        for (section in wanted) {
            val shows = section.optString("type") == "show"
            found += titles("/library/sections/${section.optString("key")}/all", mapOf("type" to if (shows) "2" else "1", "sort" to if (shows) "episode.addedAt:desc" else "addedAt:desc"), 0, limit)
        }
        return found.take(limit).map(PlexMapper::item)
    }

    /** What's partway through, everywhere or in one library. */
    private suspend fun resume(q: Query): RawResponse {
        val limit = q.int("limit") ?: 20
        val parent = q.string("parentId")
        val hubs = container("/hubs/continueWatching", mapOf("count" to (if (parent == null) limit else limit * 4).toString())).optJSONArray("Hub")?.objects().orEmpty()
        val section = parent?.takeIf { PlexMapper.isLibraryId(it) }?.let(PlexMapper::sectionKey)?.toIntOrNull()
        val inProgress = hubs.flatMap { it.optJSONArray("Metadata")?.objects().orEmpty() }
            .filter { section == null || it.optInt("librarySectionID") == section }
            .filter { (it.longOrNull("viewOffset") ?: 0) > 0 }.take(limit)
        return ok(PlexMapper.queryResult(inProgress.map(PlexMapper::item), inProgress.size))
    }

    /** The next episode to watch: of one show, or across them all (Plex's On Deck, less what's partway through, which Continue Watching has). */
    private suspend fun nextUp(q: Query): RawResponse {
        val limit = q.int("limit") ?: 20
        val series = q.string("seriesId")?.let(PlexMapper::plexId)
        val found = if (series != null) {
            val episodes = titles("/library/metadata/$series/allLeaves")
            listOfNotNull(episodes.firstOrNull { (it.longOrNull("viewOffset") ?: 0) > 0 } ?: episodes.firstOrNull { it.optInt("viewCount") == 0 })
        } else {
            val parent = q.string("parentId")?.takeIf { PlexMapper.isLibraryId(it) }?.let(PlexMapper::sectionKey)
            titles(if (parent != null) "/library/sections/$parent/onDeck" else "/library/onDeck", start = 0, size = limit * 2)
                .filter { it.optString("type") == "episode" && (it.longOrNull("viewOffset") ?: 0) == 0L }.take(limit)
        }
        return ok(PlexMapper.queryResult(found.map(PlexMapper::item), found.size))
    }

    /** Films, shows and episodes, in that order. */
    private suspend fun searchHints(q: Query): RawResponse {
        val term = q.string("searchTerm").orEmpty()
        val hubs = container("/hubs/search", mapOf("query" to term, "limit" to (q.int("limit") ?: 60).toString())).optJSONArray("Hub")?.objects().orEmpty()
        val order = listOf("movie", "show", "episode")
        val hints = hubs.filter { it.optString("type") in order }.sortedBy { order.indexOf(it.optString("type")) }
            .flatMap { it.optJSONArray("Metadata")?.objects().orEmpty() }
        return ok(
            buildJsonObject {
                putJsonArray("SearchHints") {
                    hints.forEach { m ->
                        add(buildJsonObject {
                            put("ItemId", PlexMapper.id(m.optString("ratingKey")))
                            put("Id", PlexMapper.id(m.optString("ratingKey")))
                            put("Name", m.optString("title"))
                            put("Type", PlexMapper.kind(m.optString("type")))
                            put("MediaType", "Video")
                        })
                    }
                }
                put("TotalRecordCount", hints.size)
            },
        )
    }

    // MARK: Playback

    private suspend fun playbackInfo(itemId: String, body: JsonObject?): RawResponse {
        val item = metadata(itemId)
        val media = item.optJSONArray("Media")?.objects()?.firstOrNull() ?: throw PlexHttpException(404)
        val part = media.optJSONArray("Part")?.objects()?.firstOrNull() ?: throw PlexHttpException(404)
        val session = UUID.randomUUID().toString()
        val streams = part.optJSONArray("Stream")?.objects().orEmpty()
        val maxBitrate = body.long("MaxStreamingBitrate")
        val audioIndex = body.int("AudioStreamIndex")
        val subtitleIndex = body.int("SubtitleStreamIndex")
        val profile = body?.get("DeviceProfile") as? JsonObject
        val direct = body.bool("EnableDirectPlay") && PlexPlayback.canDirectPlay(profile, media, part, maxBitrate)
        durations[itemId] = item.longOrNull("duration") ?: media.longOrNull("duration") ?: 0

        // The transcoder plays the part's selected tracks: choose them first when it will be used (Plex's own apps do the same, which
        // also makes them the title's default on the server). Only a picture subtitle is burned in; a text file beside the video is drawn by the app.
        val burnIn = subtitleIndex?.let { i -> streams.firstOrNull { it.optInt("streamType") == 3 && it.optInt("index") == i && !PlexPlayback.isFetchableText(it) } }
        if (!direct && (audioIndex != null || subtitleIndex != null)) {
            val audio = audioIndex?.let { i -> streams.firstOrNull { it.optInt("streamType") == 2 && it.optInt("index") == i } }
            val query = mutableMapOf<String, String?>("subtitleStreamID" to (burnIn?.optInt("id")?.toString() ?: "0"))
            audio?.let { query["audioStreamID"] = it.optInt("id").toString() }
            runCatching { plex.send("PUT", "/library/parts/${part.optInt("id")}", query) }
        }

        val transcode = PlexPlayback.transcodeQuery(
            itemId, session, body.long("StartTimeTicks") ?: 0, maxBitrate ?: 120_000_000, burnIn?.optInt("index"),
            hostIsLocal = plex.baseUrl.let { "plex.direct" in it || "://192.168." in it || "://10." in it },
            token = plex.token, device = device,
        )
        // Plex refuses to start a conversion ("session lacking decision") unless the same session first asked for its decision, with the same settings.
        if (!direct) runCatching { plex.get("/video/:/transcode/universal/decision", transcode) }

        val converting = !direct
        val source = buildJsonObject {
            put("Id", part.optInt("id").toString())
            put("Protocol", "File")
            put("Container", (part.str("container") ?: media.str("container")).orEmpty())
            (part.longOrNull("duration") ?: media.longOrNull("duration"))?.let { put("RunTimeTicks", it * PlexMapper.TICKS_PER_MS) }
            media.longOrNull("bitrate")?.let { put("Bitrate", it * 1000) }
            part.longOrNull("size")?.let { put("Size", it) }
            put("SupportsDirectPlay", direct)
            put("SupportsDirectStream", direct)
            put("SupportsTranscoding", true)
            put("MediaStreams", JsonArray(streams.map { PlexPlayback.playbackStream(it, converting) }))
            (streams.firstOrNull { it.optInt("streamType") == 2 && it.optBoolean("default") } ?: streams.firstOrNull { it.optInt("streamType") == 2 })?.intOrNull("index")?.let { put("DefaultAudioStreamIndex", it) }
            if (!direct) {
                put("TranscodingUrl", plex.url("/video/:/transcode/universal/start.m3u8", transcode))
                put("TranscodingContainer", "ts")
                put("TranscodingSubProtocol", "hls")
            }
        }
        return ok(buildJsonObject { putJsonArray("MediaSources") { add(source) }; put("PlaySessionId", session) })
    }

    /** Progress goes to Plex's timeline, which also keeps the resume point and marks what's finished as watched. */
    private suspend fun timeline(body: JsonObject?, state: String): RawResponse {
        val id = body.str("ItemId")?.let(PlexMapper::plexId) ?: return empty(400)
        runCatching {
            plex.send(
                "GET", "/:/timeline",
                mapOf(
                    "ratingKey" to id, "key" to "/library/metadata/$id", "state" to state,
                    "time" to ((body.long("PositionTicks") ?: 0) / PlexMapper.TICKS_PER_MS).toString(),
                    "duration" to durations[id]?.takeIf { it > 0 }?.toString(), "identifier" to IDENTIFIER,
                ),
                session = body.str("PlaySessionId"),
            )
        }
        return empty(204)
    }

    /** The parameters of a request, as the SDK passes them: ids as UUIDs, kinds and sorts as enums, lists as collections. */
    class Query(private val values: Map<String, Any?>) {
        fun string(key: String): String? = values[key]?.toString()?.takeIf { it.isNotEmpty() }
        fun int(key: String): Int? = (values[key] as? Number)?.toInt() ?: values[key]?.toString()?.toIntOrNull()
        fun bool(key: String): Boolean = values[key]?.toString() == "true"
        fun rawList(key: String): List<String> = (values[key] as? Collection<*>)?.map { it.toString() } ?: listOfNotNull(string(key))

        /** A list's entries lower-cased with their underscores gone ("BOX_SET" and "BoxSet" are both "boxset"). */
        fun list(key: String): List<String> = rawList(key).map { it.lowercase().replace("_", "") }
    }

    companion object { const val IDENTIFIER = "com.plexapp.plugins.library" }
}

private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
private fun JsonObject?.long(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.content?.toLongOrNull()
private fun JsonObject?.int(key: String): Int? = (this?.get(key) as? JsonPrimitive)?.content?.toIntOrNull()
private fun JsonObject?.bool(key: String): Boolean = (this?.get(key) as? JsonPrimitive)?.content == "true"
