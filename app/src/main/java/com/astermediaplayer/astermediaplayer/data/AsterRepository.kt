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
        client = null
        session = null
    }

    // MARK: Browsing

    suspend fun views(): List<BaseItemDto> = api.userViewApi.getUserViews(userId = userId).content.items

    suspend fun resume(limit: Int = 20): List<BaseItemDto> = api.libraryApi.getResumeItems(
        GetResumeItemsRequest(userId = userId, limit = limit, mediaTypes = listOf(org.jellyfin.sdk.model.api.MediaType.VIDEO)),
    ).content.items

    suspend fun nextUp(limit: Int = 20, seriesId: UUID? = null): List<BaseItemDto> = api.showApi.getNextUp(
        GetNextUpRequest(userId = userId, limit = limit, seriesId = seriesId),
    ).content.items

    suspend fun latest(parentId: UUID, limit: Int = 20): List<BaseItemDto> = api.libraryApi.getLatestMedia(
        userId = userId, parentId = parentId, limit = limit,
    ).content

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
            type == ImageType.BACKDROP && !item.backdropImageTags.isNullOrEmpty() ->
                api.imageApi.getItemImageUrl(item.id, type, imageIndex = 0, maxWidth = maxWidth, tag = item.backdropImageTags!!.first())
            type == ImageType.BACKDROP && item.parentBackdropImageTags?.isNotEmpty() == true && item.parentBackdropItemId != null ->
                api.imageApi.getItemImageUrl(item.parentBackdropItemId!!, type, imageIndex = 0, maxWidth = maxWidth, tag = item.parentBackdropImageTags!!.first())
            else -> null
        }
    }

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

data class ItemPage(val items: List<BaseItemDto>, val total: Int)
