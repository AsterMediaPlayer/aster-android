package com.astermediaplayer.astermediaplayer.data

import android.content.Context
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    suspend fun latest(parentId: UUID, limit: Int = 20, kinds: List<BaseItemKind>? = null): List<BaseItemDto> = api.libraryApi.getLatestMedia(
        userId = userId, parentId = parentId, limit = limit, includeItemTypes = kinds,
        fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES),
    ).content

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

    suspend fun libraryPage(parentId: UUID, startIndex: Int, limit: Int, kinds: List<BaseItemKind>? = null): ItemPage {
        val result = api.libraryApi.getItems(
            GetItemsRequest(
                userId = userId,
                parentId = parentId,
                recursive = true,
                includeItemTypes = kinds ?: listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.BOX_SET),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                startIndex = startIndex,
                limit = limit,
                fields = listOf(ItemFields.OVERVIEW),
            ),
        ).content
        return ItemPage(result.items, result.totalRecordCount)
    }

    suspend fun item(id: UUID): BaseItemDto = api.libraryApi.getItem(itemId = id, userId = userId).content

    suspend fun seasons(seriesId: UUID): List<BaseItemDto> =
        api.showApi.getSeasons(seriesId = seriesId, userId = userId).content.items

    suspend fun episodes(seriesId: UUID, seasonId: UUID?): List<BaseItemDto> = api.showApi.getEpisodes(
        seriesId = seriesId, userId = userId, seasonId = seasonId,
        fields = listOf(ItemFields.OVERVIEW),
    ).content.items

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
