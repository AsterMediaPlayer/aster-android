package com.astermediaplayer.astermediaplayer.data

import android.content.Context
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.artistApi
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.instantMixApi
import org.jellyfin.sdk.api.client.extensions.lyricApi
import org.jellyfin.sdk.api.client.extensions.playlistApi
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.genreApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.studioApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentApi
import org.jellyfin.sdk.api.client.extensions.searchApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.subtitleApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.AuthenticationResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.json.JSONObject
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.UUID

/** Thin wrapper over the Jellyfin SDK; the Kotlin counterpart of the app's `JellyfinClient`. */
class AsterRepository(context: Context, private val store: SessionStore) {
    private val jellyfin: Jellyfin = createJellyfin {
        this.context = context.applicationContext
        clientInfo = ClientInfo(name = "Aster", version = "0.1")
    }

    private var client: ApiClient? = null
    var session: Session? = null
        private set

    val api: ApiClient get() = checkNotNull(client) { "Not signed in" }
    val userId: UUID get() = UUID.fromString(checkNotNull(session).userId)
    val serverUrl: String get() = checkNotNull(session).serverUrl

    /** Restores the saved sign-in. Returns false when there isn't one. */
    suspend fun restore(): Boolean {
        val saved = store.load() ?: return false
        client = jellyfin.createApi(baseUrl = saved.serverUrl, accessToken = saved.accessToken)
        session = saved
        return true
    }

    suspend fun lastServer(): String? = store.lastServer()

    /** Accepts "host", "host:8096" or a full URL, and returns the first address that answers as a Jellyfin server. */
    suspend fun probeServer(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "Enter your server address" }
        val candidates = if ("://" in trimmed) listOf(trimmed) else listOf("https://$trimmed", "http://$trimmed")
        var failure: Throwable? = null
        for (candidate in candidates) {
            try {
                val probe = jellyfin.createApi(baseUrl = candidate)
                probe.systemApi.getPublicSystemInfo().content
                client = probe
                return candidate
            } catch (e: Exception) {
                failure = e
            }
        }
        throw IllegalStateException("Couldn't reach a Jellyfin server at $trimmed", failure)
    }

    suspend fun publicUsers(): List<UserDto> = api.userApi.getPublicUsers().content

    suspend fun signIn(serverUrl: String, username: String, password: String) {
        val result = api.authenticationApi.authenticateUserByName(AuthenticateUserByName(username, password)).content
        finishSignIn(serverUrl, result)
    }

    suspend fun quickConnectAvailable(): Boolean =
        runCatching { api.authenticationApi.getQuickConnectEnabled().content }.getOrDefault(false)

    suspend fun quickConnectInitiate(): QuickConnectResult = api.authenticationApi.initiateQuickConnect().content

    suspend fun quickConnectState(secret: String): QuickConnectResult =
        api.authenticationApi.getQuickConnectState(secret).content

    suspend fun signInWithQuickConnect(serverUrl: String, secret: String) {
        val result = api.authenticationApi.authenticateWithQuickConnect(QuickConnectDto(secret)).content
        finishSignIn(serverUrl, result)
    }

    private suspend fun finishSignIn(serverUrl: String, result: AuthenticationResult) {
        val token = checkNotNull(result.accessToken) { "The server didn't return an access token" }
        val user = checkNotNull(result.user) { "The server didn't return a user" }
        val saved = Session(serverUrl, token, user.id.toString(), user.name.orEmpty())
        store.save(saved)
        client = jellyfin.createApi(baseUrl = serverUrl, accessToken = token)
        session = saved
    }

    suspend fun signOut() {
        runCatching { api.sessionApi.reportSessionEnded() }
        store.clearCredentials()
        animeLibraryCache = null
        client = null
        session = null
    }

    // MARK: Browsing

    suspend fun views(): List<BaseItemDto> = api.userViewApi.getUserViews(userId = userId).content.items

    suspend fun resume(limit: Int = 20, parentId: UUID? = null): List<BaseItemDto> = api.libraryApi.getResumeItems(
        GetResumeItemsRequest(
            userId = userId, limit = limit, parentId = parentId, fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES),
            mediaTypes = listOf(org.jellyfin.sdk.model.api.MediaType.VIDEO),
        ),
    ).content.items

    suspend fun nextUp(limit: Int = 20, seriesId: UUID? = null, parentId: UUID? = null): List<BaseItemDto> = api.showApi.getNextUp(
        GetNextUpRequest(userId = userId, limit = limit, seriesId = seriesId, parentId = parentId),
    ).content.items

    /** Episodes of shows in the library that air soon, soonest first; most are placeholders from the show's schedule rather than files. */
    suspend fun upcoming(limit: Int = 100): List<BaseItemDto> = api.showApi.getUpcomingEpisodes(
        org.jellyfin.sdk.model.api.request.GetUpcomingEpisodesRequest(
            userId = userId, limit = limit, fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES),
            enableImageTypes = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB, ImageType.LOGO), imageTypeLimit = 1,
        ),
    ).content.items

    suspend fun latest(parentId: UUID?, limit: Int = 20, kinds: List<BaseItemKind>? = null): List<BaseItemDto> = api.libraryApi.getLatestMedia(
        userId = userId, parentId = parentId, limit = limit, includeItemTypes = kinds,
        fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES),
    ).content

    // MARK: Channel sources

    /** The user's video playlists, with their tags: the channels among them are tagged `aster:channel`. */
    suspend fun videoPlaylists(): List<BaseItemDto> = api.libraryApi.getItems(
        GetItemsRequest(
            userId = userId, recursive = true, includeItemTypes = listOf(BaseItemKind.PLAYLIST),
            mediaTypes = listOf(org.jellyfin.sdk.model.api.MediaType.VIDEO), sortBy = listOf(ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.ASCENDING),
            fields = listOf(ItemFields.TAGS, ItemFields.CHILD_COUNT, ItemFields.OVERVIEW),
        ),
    ).content.items

    /** What a channel's schedule and menus use of each title, and no more, so thousands of episodes stay a small download. */
    private val channelFields = listOf(ItemFields.OVERVIEW, ItemFields.TAGS)
    private val channelImages = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB, ImageType.LOGO)

    /** A playlist's movies, episodes and clips, tagged and ready for the schedule. */
    suspend fun channelPlaylistItems(playlistId: UUID): List<BaseItemDto> = api.playlistApi.getPlaylistItems(
        playlistId = playlistId, userId = userId, fields = channelFields, enableImages = true, enableUserData = false,
        imageTypeLimit = 1, enableImageTypes = channelImages,
    ).content.items

    /** Every item the request finds, 500 at a time. */
    private suspend fun allItems(base: GetItemsRequest): List<BaseItemDto> {
        val found = mutableListOf<BaseItemDto>()
        while (true) {
            val result = api.libraryApi.getItems(base.copy(startIndex = found.size, limit = 500)).content
            found += result.items
            if (result.items.size < 500 || found.size >= result.totalRecordCount) break
        }
        return found
    }

    private fun channelRequest(parentId: UUID? = null) = GetItemsRequest(
        userId = userId, parentId = parentId, fields = channelFields, enableImageTypes = channelImages, imageTypeLimit = 1, enableUserData = false,
    )

    /** The genres, studios and collections of the server's movies and shows, by name, for channels filled from them. */
    suspend fun videoGenres(): List<BaseItemDto> = api.genreApi.getGenres(
        userId = userId, includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), sortBy = listOf(ItemSortBy.SORT_NAME),
    ).content.items

    suspend fun videoStudios(): List<BaseItemDto> = api.studioApi.getStudios(
        userId = userId, includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
    ).content.items

    suspend fun collections(): List<BaseItemDto> = api.libraryApi.getItems(
        GetItemsRequest(userId = userId, recursive = true, includeItemTypes = listOf(BaseItemKind.BOX_SET), sortBy = listOf(ItemSortBy.SORT_NAME)),
    ).content.items

    /**
     * The movies and series in any of [genreIds], or from any of [studioIds] (ids, not names, so a name with "&" in it
     * can't break the request), of [types], under [parentId] (a library) or everywhere, from [years] and with
     * [ratings] when given.
     */
    suspend fun sourceTitles(
        genreIds: List<UUID> = emptyList(), studioIds: List<UUID> = emptyList(), types: List<BaseItemKind>, parentId: UUID?,
        years: IntRange?, ratings: List<String>,
    ): List<BaseItemDto> = allItems(
        channelRequest(parentId).copy(
            recursive = true, includeItemTypes = types, isMissing = false, sortBy = listOf(ItemSortBy.SORT_NAME),
            genreIds = genreIds.takeIf { it.isNotEmpty() }, studioIds = studioIds.takeIf { it.isNotEmpty() },
            years = years?.toList(), officialRatings = ratings.takeIf { it.isNotEmpty() },
        ),
    )

    /** What a collection holds: its movies and series. */
    suspend fun collectionTitles(collectionId: UUID): List<BaseItemDto> = allItems(channelRequest(collectionId))

    /** A series' episodes, as a channel plays them (missing ones left out). */
    suspend fun seriesEpisodes(seriesId: UUID): List<BaseItemDto> = allItems(
        channelRequest(seriesId).copy(recursive = true, includeItemTypes = listOf(BaseItemKind.EPISODE), isMissing = false),
    )

    // MARK: Live TV

    /** The server's TV channels with what's on each now, in the guide's order: favourites first, then by number. */
    suspend fun liveChannels(): List<BaseItemDto> {
        val channels = api.liveTvApi.getLiveTvChannels(
            org.jellyfin.sdk.model.api.request.GetLiveTvChannelsRequest(
                userId = userId, addCurrentProgram = true, limit = 500,
                fields = listOf(ItemFields.OVERVIEW),
            ),
        ).content.items
        return guideOrder(channels)
    }

    private fun guideOrder(channels: List<BaseItemDto>): List<BaseItemDto> = channels.sortedWith(
        compareByDescending<BaseItemDto> { it.userData?.isFavorite == true }
            .thenBy { it.channelNumber?.toDoubleOrNull() ?: Double.MAX_VALUE }
            .thenBy { it.name.orEmpty() },
    )

    /** What's on now across channels, optionally just one kind of programme. */
    suspend fun recommendedPrograms(
        limit: Int = 24, movie: Boolean? = null, sports: Boolean? = null, kids: Boolean? = null, news: Boolean? = null,
    ): List<BaseItemDto> = api.liveTvApi.getRecommendedPrograms(
        org.jellyfin.sdk.model.api.request.GetRecommendedProgramsRequest(
            userId = userId, limit = limit, isAiring = true, isMovie = movie, isSports = sports, isKids = kids, isNews = news,
            enableImageTypes = listOf(ImageType.PRIMARY, ImageType.THUMB, ImageType.BACKDROP),
            fields = listOf(ItemFields.OVERVIEW, ItemFields.CHANNEL_INFO),
        ),
    ).content.items

    suspend fun recordings(limit: Int = 100): List<BaseItemDto> = api.liveTvApi.getRecordings(
        org.jellyfin.sdk.model.api.request.GetRecordingsRequest(userId = userId, limit = limit, fields = listOf(ItemFields.OVERVIEW)),
    ).content.items

    /** A channel's programmes overlapping [from, to] (seconds since 1970), in order, with the pictures and overview the guide's cells and the program page start from. */
    suspend fun livePrograms(channelId: UUID, from: Double, to: Double): List<BaseItemDto> = api.liveTvApi.getLiveTvPrograms(
        org.jellyfin.sdk.model.api.request.GetLiveTvProgramsRequest(
            channelIds = listOf(channelId), userId = userId,
            minEndDate = utc(from), maxStartDate = utc(to), sortBy = listOf(ItemSortBy.START_DATE), limit = 200,
            enableImages = true, imageTypeLimit = 1, enableImageTypes = listOf(ImageType.PRIMARY, ImageType.THUMB),
            fields = listOf(ItemFields.OVERVIEW), enableUserData = false,
        ),
    ).content.items

    private fun utc(seconds: Double): java.time.LocalDateTime = java.time.LocalDateTime.ofEpochSecond(seconds.toLong(), 0, java.time.ZoneOffset.UTC)

    /** The programme last opened from a guide or list, so its page opens with what that already showed while the full record loads. */
    val openedPrograms = HashMap<String, BaseItemDto>()

    /** One programme with everything the server knows of it, and whether it's set to record. */
    suspend fun liveProgram(id: String): BaseItemDto = api.liveTvApi.getProgram(id, userId).content

    suspend fun timers(): List<org.jellyfin.sdk.model.api.TimerInfoDto> =
        api.liveTvApi.getTimers(isScheduled = true).content.items.sortedBy { it.startDate }

    suspend fun seriesTimers(): List<org.jellyfin.sdk.model.api.SeriesTimerInfoDto> = api.liveTvApi.getSeriesTimers().content.items

    suspend fun seriesTimer(id: String): org.jellyfin.sdk.model.api.SeriesTimerInfoDto = api.liveTvApi.getSeriesTimer(id).content

    suspend fun updateSeriesTimer(timer: org.jellyfin.sdk.model.api.SeriesTimerInfoDto) { api.liveTvApi.updateSeriesTimer(timer.id!!, timer) }

    suspend fun cancelTimer(id: String) { api.liveTvApi.cancelTimer(id) }

    suspend fun cancelSeriesTimer(id: String) { api.liveTvApi.cancelSeriesTimer(id) }

    /** Schedules a recording of one programme using the server's defaults, posting them back as the server's own clients do. */
    suspend fun recordProgram(programId: String) = postDefaults(programId, "/LiveTv/Timers")

    /** Records every airing of a series using the server's defaults (one endpoint, whose answer is a series timer). */
    suspend fun recordSeries(programId: String) = postDefaults(programId, "/LiveTv/SeriesTimers")

    private suspend fun postDefaults(programId: String, path: String) = withContext(Dispatchers.IO) {
        val base = serverUrl.trimEnd('/')
        val defaults = http.newCall(Request.Builder().url("$base/LiveTv/Timers/Defaults?programId=$programId").header("Authorization", authorizationHeader()).build())
            .execute().use { r -> if (!r.isSuccessful) error("The server couldn't prepare the recording (${r.code}).") else r.body.string() }
        http.newCall(
            Request.Builder().url(base + path).header("Authorization", authorizationHeader())
                .post(defaults.toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { r -> if (!r.isSuccessful) error("The server couldn't schedule the recording (${r.code}).") }
    }

    /** Lets go of a channel's tuner once playback of it ends. */
    suspend fun closeLiveStream(liveStreamId: String) {
        runCatching { api.mediaInfoApi.closeLiveStream(liveStreamId) }
    }

    /** A channel's logo, which the server draws on its own picture. */
    fun channelLogoUrl(channel: BaseItemDto, maxWidth: Int = 300): String? =
        imageUrl(channel, ImageType.PRIMARY, maxWidth)

    /** A programme's picture: its own, else its thumb or backdrop. */
    fun programImageUrl(program: BaseItemDto, maxWidth: Int = 800): String? =
        imageUrl(program, ImageType.PRIMARY, maxWidth) ?: imageUrl(program, ImageType.THUMB, maxWidth) ?: imageUrl(program, ImageType.BACKDROP, maxWidth)

    // MARK: Music

    /** Albums, playlists or songs across the server, a page at a time. */
    suspend fun musicPage(
        kind: BaseItemKind, startIndex: Int, limit: Int,
        sortBy: List<ItemSortBy> = listOf(ItemSortBy.SORT_NAME), order: SortOrder = SortOrder.ASCENDING,
        favorites: Boolean = false, played: Boolean = false,
    ): ItemPage {
        val result = api.libraryApi.getItems(
            GetItemsRequest(
                userId = userId, recursive = true, includeItemTypes = listOf(kind),
                mediaTypes = if (kind == BaseItemKind.PLAYLIST) listOf(org.jellyfin.sdk.model.api.MediaType.AUDIO) else null,
                sortBy = sortBy, sortOrder = listOf(order), startIndex = startIndex, limit = limit,
                filters = buildList {
                    if (favorites) add(org.jellyfin.sdk.model.api.ItemFilter.IS_FAVORITE)
                    if (played) add(org.jellyfin.sdk.model.api.ItemFilter.IS_PLAYED)
                },
                fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES),
            ),
        ).content
        return ItemPage(result.items, result.totalRecordCount)
    }

    /** Artists credited on albums, paged. */
    suspend fun albumArtists(startIndex: Int, limit: Int = 100): ItemPage {
        val result = api.artistApi.getAlbumArtists(
            userId = userId, startIndex = startIndex, limit = limit,
            fields = listOf(ItemFields.OVERVIEW), sortBy = listOf(ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.ASCENDING),
        ).content
        return ItemPage(result.items, result.totalRecordCount)
    }

    /** An album's songs in disc and track order. */
    suspend fun albumTracks(albumId: UUID): List<BaseItemDto> = api.libraryApi.getItems(
        GetItemsRequest(
            userId = userId, parentId = albumId, includeItemTypes = listOf(BaseItemKind.AUDIO),
            sortBy = listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
        ),
    ).content.items

    /** Albums an artist is credited on, newest first. */
    suspend fun artistAlbums(artistId: UUID): List<BaseItemDto> = api.libraryApi.getItems(
        GetItemsRequest(
            userId = userId, albumArtistIds = listOf(artistId), includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM), recursive = true,
            sortBy = listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.DESCENDING),
        ),
    ).content.items

    /** Every song by an artist, in album order, for Play and Shuffle. */
    suspend fun artistTracks(artistId: UUID, limit: Int = 400): List<BaseItemDto> = api.libraryApi.getItems(
        GetItemsRequest(
            userId = userId, artistIds = listOf(artistId), includeItemTypes = listOf(BaseItemKind.AUDIO), recursive = true,
            sortBy = listOf(ItemSortBy.ALBUM, ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER), limit = limit,
        ),
    ).content.items

    suspend fun playlistItems(playlistId: UUID): List<BaseItemDto> =
        api.playlistApi.getPlaylistItems(playlistId = playlistId, userId = userId).content.items

    /** Songs the server thinks go with an item: a song, album, artist or genre. */
    suspend fun instantMix(itemId: UUID, limit: Int = 100): List<BaseItemDto> =
        api.instantMixApi.getInstantMixFromItem(itemId = itemId, userId = userId, limit = limit).content.items

    /** A song's lyrics: timed lines when a synced file exists, plain lines otherwise; null when the server has none. */
    suspend fun lyrics(itemId: UUID): org.jellyfin.sdk.model.api.LyricDto? =
        runCatching { api.lyricApi.getLyrics(itemId).content }.getOrNull()

    // MARK: Audiobooks

    /** The book libraries on the server. */
    suspend fun bookLibraries(): List<BaseItemDto> = views().filter { it.collectionType == CollectionType.BOOKS }

    /** Every book in [libraries], files grouped into books and authors and series read from the folders. */
    suspend fun audiobooks(libraries: List<BaseItemDto>): List<Audiobook> = coroutineScope {
        libraries.map { library ->
            async {
                val folders = async {
                    api.libraryApi.getItems(
                        GetItemsRequest(
                            userId = userId, parentId = library.id, includeItemTypes = listOf(BaseItemKind.FOLDER), recursive = true,
                            fields = listOf(ItemFields.PARENT_ID), enableUserData = false,
                        ),
                    ).content.items
                }
                val files = api.libraryApi.getItems(
                    GetItemsRequest(
                        userId = userId, parentId = library.id, includeItemTypes = listOf(BaseItemKind.AUDIO_BOOK), recursive = true,
                        fields = Audiobook.Fields, sortBy = listOf(ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.ASCENDING),
                    ),
                ).content.items
                Audiobook.group(files, folders.await())
            }
        }.awaitAll().flatten()
    }

    /** The parts' listening state, fresh from the server. */
    suspend fun audiobookParts(ids: List<UUID>): List<BaseItemDto> =
        api.libraryApi.getItems(GetItemsRequest(userId = userId, ids = ids, fields = Audiobook.Fields)).content.items

    /** A book's cover: its first file's square picture. */
    fun bookCoverUrl(book: Audiobook, maxWidth: Int): String? =
        imageUrl(book.cover, ImageType.PRIMARY, maxWidth) ?: book.cover.albumId?.let { id ->
            book.cover.albumPrimaryImageTag?.let { api.imageApi.getItemImageUrl(id, ImageType.PRIMARY, maxWidth = maxWidth, tag = it) }
        }

    // MARK: Theme media

    /** A title's theme songs and videos, inherited from its series when it has none of its own. */
    suspend fun themeSongs(itemId: UUID): List<BaseItemDto> =
        runCatching { api.libraryApi.getThemeSongs(itemId = itemId, userId = userId, inheritFromParent = true).content.items }.getOrDefault(emptyList())

    suspend fun themeVideos(itemId: UUID): List<BaseItemDto> =
        runCatching { api.libraryApi.getThemeVideos(itemId = itemId, userId = userId, inheritFromParent = true).content.items }.getOrDefault(emptyList())

    fun audioStreamUrl(itemId: UUID): String = api.audioApi.getAudioStreamUrl(itemId = itemId, static = true)

    fun videoStreamUrl(itemId: UUID): String =
        api.videoApi.getVideoStreamUrl(itemId = itemId, static = true, mediaSourceId = itemId.toString())

    // MARK: OP/ED plugin

    private val http = OkHttpClient()

    /** The `MediaBrowser` header the server's own API clients send; plugin endpoints reject a bare token. */
    private fun authorizationHeader(): String {
        val client = api.clientInfo
        val device = api.deviceInfo
        return "MediaBrowser Client=\"${client.name}\", Device=\"${device.name}\", DeviceId=\"${device.id}\", " +
            "Version=\"${client.version}\", Token=\"${checkNotNull(session).accessToken}\""
    }

    /** A GET against the OP/ED plugin; null when the plugin isn't installed, the item is unknown, or the request fails. */
    private suspend fun opEdGet(path: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(serverUrl.trimEnd('/') + path)
                .header("Authorization", authorizationHeader()).build()
            http.newCall(request).execute().use { response ->
                val body = response.body.string()
                android.util.Log.d("Aster", "OpEd $path -> ${response.code} ${body.take(200)}")
                if (response.isSuccessful) JSONObject(body) else null
            }
        }.onFailure { android.util.Log.w("Aster", "OpEd $path failed", it) }.getOrNull()
    }

    /** Openings and endings from the plugin for a series, season or episode. */
    suspend fun opEdThemes(itemId: UUID): List<OpEdTheme>? = opEdGet("/OpEd/Items/$itemId/Themes")?.let { json ->
        val themes = json.optJSONArray("Themes") ?: return@let emptyList()
        List(themes.length()) { OpEdTheme.fromJson(themes.getJSONObject(it)) }
    }

    /** The plugin's published libraries; null without the plugin. */
    suspend fun opEdLibraries(): OpEdLibraries? = opEdGet("/OpEd/Library")?.let {
        OpEdLibraries(
            videosLibraryId = it.optString("VideosLibraryId").ifEmpty { null },
            songsLibraryId = it.optString("SongsLibraryId").ifEmpty { null },
        )
    }

    /**
     * A plugin media path (e.g. `VideoMp4Url`) as a URL the player can open. The plugin's files don't accept the bare
     * token header the server's own streams do, so the token goes in the query, as the Apple apps send it.
     */
    fun opEdMediaUrl(path: String): String {
        val token = android.net.Uri.encode(checkNotNull(session).accessToken)
        val url = absoluteUrl(path)
        return url + (if ('?' in url) "&" else "?") + "ApiKey=$token&api_key=$token"
    }

    /** A library's newest music videos, for a shelf. */
    suspend fun latestMusicVideos(libraryId: UUID, limit: Int = 20): List<BaseItemDto> = runCatching {
        api.libraryApi.getItems(
            GetItemsRequest(
                userId = userId, parentId = libraryId, recursive = true,
                includeItemTypes = listOf(BaseItemKind.MUSIC_VIDEO),
                sortBy = listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.DESCENDING),
                limit = limit,
            ),
        ).content.items
    }.getOrDefault(emptyList())

    /** The openings and endings in a series' episodes' chapter markers: what the plugin falls back to. */
    suspend fun chapterThemes(seriesId: UUID): List<ThemeClip> =
        runCatching { OpeningsEndings.find(episodes(seriesId, null, listOf(ItemFields.CHAPTERS))) }.getOrDefault(emptyList())

    /** An id as Jellyfin writes it either way: lowercase, without dashes. */
    fun plainId(id: String): String = id.lowercase().replace("-", "")

    // MARK: Anime

    private var animeLibraryCache: List<BaseItemDto>? = null

    /**
     * The TV and movie libraries that hold anime. Reading a library's metadata settings needs an admin account,
     * so a few titles in each are sampled: when at least half were matched by Shoko/AniDB, the library is anime.
     */
    suspend fun animeLibraries(views: List<BaseItemDto>): List<BaseItemDto> {
        animeLibraryCache?.let { return it }
        // Nothing to sample yet (the views haven't loaded); don't remember that as "no anime".
        if (views.isEmpty()) return emptyList()
        val found = coroutineScope {
            views.filter { it.collectionType == CollectionType.MOVIES || it.collectionType == CollectionType.TVSHOWS }
                .map { library ->
                    async {
                        val sample = runCatching {
                            api.libraryApi.getItems(
                                GetItemsRequest(
                                    userId = userId, parentId = library.id, recursive = true,
                                    includeItemTypes = listOf(BaseItemKind.SERIES, BaseItemKind.MOVIE),
                                    limit = 5, fields = listOf(ItemFields.PROVIDER_IDS),
                                ),
                            ).content.items
                        }.getOrDefault(emptyList())
                        library to (sample.isNotEmpty() && sample.count { it.isAnime() } * 2 >= sample.size)
                    }
                }
                .awaitAll().filter { it.second }.map { it.first }
        }
        animeLibraryCache = found
        return found
    }

    /** The newest series and films across the anime libraries; a season that got new episodes shows as its series. */
    suspend fun recentlyAddedAnime(libraries: List<BaseItemDto>, limit: Int = 20): List<BaseItemDto> = coroutineScope {
        val all = libraries.map { async { runCatching { latest(it.id, limit) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
        val newest = all.sortedByDescending { it.dateCreated }
        val seriesIds = newest.filter { it.type == BaseItemKind.SEASON }.mapNotNull { it.seriesId }.toSet()
        val series = if (seriesIds.isEmpty()) emptyMap() else runCatching {
            api.libraryApi.getItems(GetItemsRequest(userId = userId, ids = seriesIds.toList(), fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES)))
                .content.items.associateBy { it.id }
        }.getOrDefault(emptyMap())
        val shown = mutableListOf<BaseItemDto>()
        for (item in newest) {
            val entry = if (item.type == BaseItemKind.SEASON) item.seriesId?.let { series[it] } ?: item else item
            if (shown.none { it.id == entry.id }) shown += entry
        }
        shown.take(limit)
    }

    private val libraryKinds = mutableMapOf<UUID, List<BaseItemKind>>()

    suspend fun libraryPage(parentId: UUID, startIndex: Int, limit: Int, kinds: List<BaseItemKind>? = null): ItemPage {
        val result = api.libraryApi.getItems(
            GetItemsRequest(
                userId = userId,
                parentId = parentId,
                recursive = true,
                includeItemTypes = kinds ?: libraryKinds.getOrPut(parentId) {
                    when (runCatching { item(parentId).collectionType }.getOrNull()) {
                        CollectionType.MUSICVIDEOS -> listOf(BaseItemKind.MUSIC_VIDEO)
                        else -> listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.BOX_SET)
                    }
                },
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                startIndex = startIndex,
                limit = limit,
                fields = listOf(ItemFields.OVERVIEW),
            ),
        ).content
        return ItemPage(result.items, result.totalRecordCount)
    }

    /** Every title of a kind across the server, in the order asked for, a page at a time. */
    suspend fun typePage(kind: BaseItemKind, startIndex: Int, limit: Int, sortBy: List<ItemSortBy>, order: SortOrder): ItemPage {
        val result = api.libraryApi.getItems(
            GetItemsRequest(
                userId = userId, recursive = true, includeItemTypes = listOf(kind),
                sortBy = sortBy, sortOrder = listOf(order), startIndex = startIndex, limit = limit,
                fields = listOf(ItemFields.OVERVIEW, ItemFields.SORT_NAME),
            ),
        ).content
        return ItemPage(result.items, result.totalRecordCount)
    }

    /** Just the ids of one kind of title in some libraries (anime's, which Movies and TV Shows leave out). */
    /**
     * A page of what's in one library or collection, as the Apple apps' `LibraryGridView` asks for it: the titles of the
     * library's kind (movies, shows, collections, music videos) wherever they sit in it, and for any other kind of library
     * what's directly inside.
     */
    suspend fun libraryItems(parentId: UUID, type: CollectionType?, startIndex: Int, limit: Int, sortBy: List<ItemSortBy>, order: SortOrder): ItemPage {
        val kind = when (type) {
            CollectionType.MOVIES -> BaseItemKind.MOVIE
            CollectionType.TVSHOWS -> BaseItemKind.SERIES
            CollectionType.BOXSETS -> BaseItemKind.BOX_SET
            // Every video, not the folders (one per show or artist) they're in.
            CollectionType.MUSICVIDEOS -> BaseItemKind.MUSIC_VIDEO
            else -> null
        }
        val result = api.libraryApi.getItems(
            GetItemsRequest(
                userId = userId, parentId = parentId, recursive = kind != null, includeItemTypes = kind?.let { listOf(it) },
                sortBy = sortBy, sortOrder = listOf(order), startIndex = startIndex, limit = limit,
                fields = listOf(ItemFields.OVERVIEW, ItemFields.SORT_NAME),
            ),
        ).content
        return ItemPage(result.items, result.totalRecordCount)
    }

    /** The titles marked as favourites, for the Library page's shelf. */
    suspend fun favoriteTitles(limit: Int = 40): List<BaseItemDto> = api.libraryApi.getItems(
        GetItemsRequest(
            userId = userId, recursive = true, filters = listOf(org.jellyfin.sdk.model.api.ItemFilter.IS_FAVORITE),
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
            sortBy = listOf(ItemSortBy.SORT_NAME), limit = limit, fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES),
        ),
    ).content.items

    suspend fun itemIds(libraryIds: List<UUID>, kind: BaseItemKind): Set<UUID> = coroutineScope {
        libraryIds.map { id ->
            async {
                runCatching {
                    api.libraryApi.getItems(
                        GetItemsRequest(
                            userId = userId, parentId = id, recursive = true, includeItemTypes = listOf(kind),
                            enableImages = false, enableUserData = false, fields = emptyList(),
                        ),
                    ).content.items.map { it.id }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().toSet()
    }

    suspend fun item(id: UUID): BaseItemDto = api.libraryApi.getItem(itemId = id, userId = userId).content

    suspend fun seasons(seriesId: UUID): List<BaseItemDto> =
        api.showApi.getSeasons(seriesId = seriesId, userId = userId).content.items

    suspend fun episodes(seriesId: UUID, seasonId: UUID?, fields: List<ItemFields> = listOf(ItemFields.OVERVIEW)): List<BaseItemDto> = api.showApi.getEpisodes(
        seriesId = seriesId, userId = userId, seasonId = seasonId, fields = fields,
    ).content.items

    /** Extras: behind the scenes, trailers, deleted scenes. */
    suspend fun specialFeatures(id: UUID): List<BaseItemDto> =
        runCatching { api.libraryApi.getSpecialFeatures(itemId = id, userId = userId).content }.getOrDefault(emptyList())

    suspend fun similar(id: UUID, limit: Int = 12): List<BaseItemDto> =
        api.libraryApi.getSimilarItems(itemId = id, userId = userId, limit = limit).content.items

    suspend fun search(term: String, limit: Int = 60): List<BaseItemDto> {
        val hints = api.searchApi.getSearchHints(
            searchTerm = term, userId = userId, limit = limit,
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
        ).content.searchHints
        // Hints are thin; fetch the full items so cards have images and user data.
        if (hints.isEmpty()) return emptyList()
        return api.libraryApi.getItems(
            GetItemsRequest(userId = userId, ids = hints.map { it.itemId }, fields = listOf(ItemFields.OVERVIEW)),
        ).content.items
    }

    suspend fun setFavorite(id: UUID, favorite: Boolean) {
        if (favorite) api.userDataApi.markFavoriteItem(itemId = id, userId = userId)
        else api.userDataApi.unmarkFavoriteItem(itemId = id, userId = userId)
    }

    suspend fun setPlayed(id: UUID, played: Boolean) {
        if (played) api.userDataApi.markPlayedItem(itemId = id, userId = userId)
        else api.userDataApi.markUnplayedItem(itemId = id, userId = userId)
    }

    // MARK: Images

    fun imageUrl(item: BaseItemDto, type: ImageType, maxWidth: Int): String? {
        val tag = item.imageTags?.get(type)
        return when {
            tag != null -> api.imageApi.getItemImageUrl(item.id, type, maxWidth = maxWidth, tag = tag)
            // Episodes and seasons borrow art from their series.
            type == ImageType.PRIMARY && item.seriesPrimaryImageTag != null && item.seriesId != null ->
                api.imageApi.getItemImageUrl(item.seriesId!!, type, maxWidth = maxWidth, tag = item.seriesPrimaryImageTag)
            type == ImageType.LOGO && item.parentLogoItemId != null && item.parentLogoImageTag != null ->
                api.imageApi.getItemImageUrl(item.parentLogoItemId!!, type, maxWidth = maxWidth, tag = item.parentLogoImageTag)
            type == ImageType.BACKDROP && !item.backdropImageTags.isNullOrEmpty() ->
                api.imageApi.getItemImageUrl(item.id, type, imageIndex = 0, maxWidth = maxWidth, tag = item.backdropImageTags!!.first())
            type == ImageType.BACKDROP && item.parentBackdropImageTags?.isNotEmpty() == true && item.parentBackdropItemId != null ->
                api.imageApi.getItemImageUrl(item.parentBackdropItemId!!, type, imageIndex = 0, maxWidth = maxWidth, tag = item.parentBackdropImageTags!!.first())
            else -> null
        }
    }

    /** The series poster for an episode (or season), whose own art is just a still. */
    fun seriesPosterUrl(item: BaseItemDto, maxWidth: Int): String? {
        val seriesId = item.seriesId ?: return null
        val tag = item.seriesPrimaryImageTag ?: return null
        return api.imageApi.getItemImageUrl(seriesId, ImageType.PRIMARY, maxWidth = maxWidth, tag = tag)
    }

    /** An item's primary picture by its id and tag, for art that belongs to another item (a song's album). */
    fun imageUrlFor(id: UUID, tag: String, maxWidth: Int, type: ImageType = ImageType.PRIMARY): String =
        api.imageApi.getItemImageUrl(id, type, maxWidth = maxWidth, tag = tag)

    fun personImageUrl(person: BaseItemPerson, maxWidth: Int): String? =
        person.primaryImageTag?.let { api.imageApi.getItemImageUrl(person.id, ImageType.PRIMARY, maxWidth = maxWidth, tag = it) }

    // MARK: Playback

    suspend fun playbackInfo(
        itemId: UUID,
        startPositionTicks: Long,
        maxBitrate: Int,
        mediaSourceId: String? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        forceTranscode: Boolean = false,
    ): PlaybackInfoResponse = api.mediaInfoApi.getPostedPlaybackInfo(
        itemId,
        PlaybackInfoDto(
            userId = userId,
            maxStreamingBitrate = maxBitrate,
            startTimeTicks = startPositionTicks,
            mediaSourceId = mediaSourceId,
            audioStreamIndex = audioStreamIndex,
            subtitleStreamIndex = subtitleStreamIndex,
            deviceProfile = AndroidDeviceProfile.build(maxBitrate),
            enableDirectPlay = !forceTranscode,
            enableDirectStream = !forceTranscode,
            enableTranscoding = true,
            autoOpenLiveStream = true,
        ),
    ).content

    /** Whether the server lets this user download media ("Allow media downloading" in the user's settings). */
    suspend fun canDownload(): Boolean = runCatching { api.userApi.getCurrentUser().content.policy?.enableContentDownloading }.getOrNull() ?: false

    /** The server's HLS stream for saving a title, at [quality], with the chosen tracks; [subtitleIndex] is burned in, -1 for none. */
    suspend fun downloadInfo(
        itemId: UUID, maxBitrate: Int, maxWidth: Int?, allowHevc: Boolean, mediaSourceId: String?, audioIndex: Int?, subtitleIndex: Int,
    ): PlaybackInfoResponse = api.mediaInfoApi.getPostedPlaybackInfo(
        itemId,
        PlaybackInfoDto(
            userId = userId, maxStreamingBitrate = maxBitrate, startTimeTicks = 0L, mediaSourceId = mediaSourceId,
            audioStreamIndex = audioIndex, subtitleStreamIndex = subtitleIndex,
            deviceProfile = AndroidDeviceProfile.download(maxBitrate, maxWidth, allowHevc),
            enableDirectPlay = false, enableDirectStream = false, enableTranscoding = true, autoOpenLiveStream = false,
        ),
    ).content

    /** What the sign-in token is, for the download manager's requests. */
    val accessToken: String? get() = session?.accessToken

    fun directStreamUrl(itemId: UUID, mediaSourceId: String, playSessionId: String?, container: String?): String =
        api.videoApi.getVideoStreamUrl(
            itemId = itemId,
            container = container,
            static = true,
            mediaSourceId = mediaSourceId,
            playSessionId = playSessionId,
        )

    /** Transcode URLs from the server are relative and already carry the token. */
    fun absoluteUrl(serverRelative: String): String =
        if (serverRelative.startsWith("http")) serverRelative else serverUrl.trimEnd('/') + serverRelative

    fun subtitleUrl(itemId: UUID, mediaSourceId: String, streamIndex: Int, format: String): String =
        api.subtitleApi.getSubtitleUrl(
            routeItemId = itemId, routeMediaSourceId = mediaSourceId, routeIndex = streamIndex, routeFormat = format,
        )

    suspend fun segments(itemId: UUID): List<MediaSegmentDto> = runCatching {
        api.mediaSegmentApi.getItemSegments(
            itemId, listOf(MediaSegmentType.INTRO, MediaSegmentType.OUTRO),
        ).content.items
    }.getOrDefault(emptyList())

    suspend fun reportStart(info: PlaybackStartInfo) {
        runCatching { api.sessionApi.reportPlaybackStart(info) }
    }

    suspend fun reportProgress(info: PlaybackProgressInfo) {
        runCatching { api.sessionApi.reportPlaybackProgress(info) }
    }

    suspend fun reportStopped(info: PlaybackStopInfo) {
        runCatching { api.sessionApi.reportPlaybackStopped(info) }
    }
}

/** Matched by Shoko, which also records the AniDB id: how the app tells anime from other shows and films. */
fun BaseItemDto.isAnime(): Boolean = providerIds?.keys?.any { it == "AniDB" || it.startsWith("Shoko") } == true

data class ItemPage(val items: List<BaseItemDto>, val total: Int)
