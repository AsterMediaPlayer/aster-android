package com.astermediaplayer.astermediaplayer.data

import kotlinx.coroutines.sync.withLock
import android.content.Context
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import com.astermediaplayer.astermediaplayer.data.emby.ServerApiClient
import com.astermediaplayer.astermediaplayer.data.emby.EmbyIds
import com.astermediaplayer.astermediaplayer.data.emby.ServerKind
import com.astermediaplayer.astermediaplayer.data.plex.OkHttpPlexTransport
import com.astermediaplayer.astermediaplayer.data.plex.PlexAccount
import com.astermediaplayer.astermediaplayer.data.plex.PlexApiClient
import com.astermediaplayer.astermediaplayer.data.plex.PlexCompanion
import kotlinx.coroutines.withTimeoutOrNull
import com.astermediaplayer.astermediaplayer.data.plex.PlexReconnect
import com.astermediaplayer.astermediaplayer.data.plex.PlexHomeChoice
import com.astermediaplayer.astermediaplayer.data.plex.PlexHomeUser
import com.astermediaplayer.astermediaplayer.data.plex.PlexConnectionRace
import com.astermediaplayer.astermediaplayer.data.plex.PlexDevice
import com.astermediaplayer.astermediaplayer.data.plex.PlexLink
import com.astermediaplayer.astermediaplayer.data.plex.PlexMapper
import com.astermediaplayer.astermediaplayer.data.plex.PlexPin
import com.astermediaplayer.astermediaplayer.data.plex.PlexResource
import org.jellyfin.sdk.api.client.extensions.artistApi
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.instantMixApi
import org.jellyfin.sdk.api.client.extensions.localizationApi
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
import org.jellyfin.sdk.api.client.extensions.itemUpdateApi
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The one HTTP client for the server's API, the SDK and pictures, as the official Android TV app and Wholphin share theirs: one
 * pool of connections to the server (no handshake per client), and twelve requests at once to it rather than OkHttp's five.
 */
val sharedHttp: OkHttpClient by lazy {
    OkHttpClient.Builder().credentialSafeRedirects()
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 12 })
        .build()
}

/** Thin wrapper over the Jellyfin SDK; the Kotlin counterpart of the app's `JellyfinClient`. */
class AsterRepository(val context: Context, private val store: SessionStore) {
    /** Whether song names may be asked of AnimeThemes (Settings > Title Pages); the app keeps this in step with the setting. */
    @Volatile var songLookups: Boolean = true

    /** What title pages have found where the server has no OP/ED plugin (openings from chapters, their songs), kept between runs. */
    /** The last answer of each screen, by server and user, to show at once while the server's asked again. */
    private val responses = ResponseCache(java.io.File(context.applicationContext.cacheDir, "responses"))

    /**
     * The server's calls run here, away from the main thread: the SDK reads and decodes its answer on whichever thread
     * asked, and a screen's lists of titles decoded on the main thread held up every frame on a TV box.
     */
    private suspend inline fun <T> io(crossinline block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    /** Items the lists have shown, by id, so a title page opens on the card's item at once (see [seen]). */
    private val seenItems = android.util.LruCache<UUID, BaseItemDto>(400)

    /** Remembers an item a card showed, for its page to open on. */
    fun noteSeen(item: BaseItemDto) { seenItems.put(item.id, item) }

    /** The item as a card last showed it, if one did. */
    fun seen(id: UUID): BaseItemDto? = seenItems.get(id)

    /** A screen's last snapshot for this server and user ([name]: "home", "discover"…), or null. */
    suspend fun snapshot(name: String): String? = session?.let { responses.read("${it.serverUrl}|${it.userId}|$name") }

    fun keepSnapshot(name: String, text: String) { session?.let { responses.write("${it.serverUrl}|${it.userId}|$name", text) } }

    private val songThemes = SongThemeCache(java.io.File(context.applicationContext.filesDir, "song-themes.json"))
        // The first version of this kept only Plex's, in a file of its own.
        .also { java.io.File(context.applicationContext.filesDir, "plex-themes.json").delete() }
    private val jellyfin: Jellyfin = createJellyfin {
        this.context = this@AsterRepository.context.applicationContext
        clientInfo = ClientInfo(name = "Aster", version = "0.1")
        // The sign-in goes along a redirect only within the server it was sent to (see CredentialSafeRedirects).
        val signedIn = org.jellyfin.sdk.api.okhttp.OkHttpFactory(sharedHttp)
        apiClientFactory = signedIn
        socketConnectionFactory = signedIn
    }

    private var client: ApiClient? = null

    /** The servers signed in to, in use or not (the Apple apps' `savedServers`). */
    private val _servers = kotlinx.coroutines.flow.MutableStateFlow<List<SavedServer>>(emptyList())
    val savedServers: kotlinx.coroutines.flow.StateFlow<List<SavedServer>> = _servers

    /** The server in use was left for longer than its lock-after: its user's password is asked for to carry on. */
    private val _locked = kotlinx.coroutines.flow.MutableStateFlow(false)
    val locked: kotlinx.coroutines.flow.StateFlow<Boolean> = _locked

    /** What the server just reached calls itself, kept with the sign-in that follows. */
    private var reachedName: String? = null

    val currentServerId: String? get() = session?.let { SavedServer.id(it.serverUrl, it.userId) }
    val currentServer: SavedServer? get() = currentServerId?.let { id -> _servers.value.firstOrNull { it.id == id } }

    /** The kind of server reached, which decides the routes and ids the client speaks (see [ServerApiClient]). */
    var serverKind: ServerKind = ServerKind.Jellyfin
        private set

    /** An id as the server writes it: on Emby and Plex, the number an id the app carries as a UUID stands for. */
    fun wire(id: UUID): String = wire(id.toString())
    fun wire(id: String): String = when (serverKind) {
        ServerKind.Emby -> EmbyIds.decode(id)
        ServerKind.Plex -> com.astermediaplayer.astermediaplayer.data.plex.PlexMapper.plexId(id)
        else -> id
    }

    /** The SDK's client for [url], made to speak Emby when the server is one, and to keep its requests off the main thread. */
    private fun connect(url: String, token: String? = null, kind: ServerKind = serverKind): ApiClient {
        val base = jellyfin.createApi(baseUrl = url, accessToken = token)
        // Plex shares nothing with Jellyfin's API: its own client answers the same calls from Plex's.
        if (kind == ServerKind.Plex && token != null) return PlexApiClient(base, OkHttpPlexTransport(url, token, plexDevice), plexDevice)
        return ServerApiClient(base, kind) { session?.userId }
    }

    private val plexDevice: PlexDevice by lazy { jellyfin.createApi().deviceInfo.let { PlexDevice(it.id, it.name) } }
    private val plexAccount: PlexAccount by lazy { PlexAccount(plexDevice) }

    // MARK: Plex sign-in (plex.tv)

    /** A new link code to show: the viewer enters it at plex.tv/link. */
    suspend fun plexStartLink(): PlexPin = plexAccount.startLink()

    /** The account's token once the code has been entered; null until then. */
    suspend fun plexLinkedToken(pin: PlexPin): String? = plexAccount.linkedToken(pin)

    /** The servers the account can use, its own first. */
    suspend fun plexServers(accountToken: String): List<PlexResource> = plexAccount.servers(accountToken)

    /** The account's Home profiles, for "who's watching"; none when it has no Home. */
    suspend fun plexHomeUsers(token: String): List<PlexHomeUser> = plexAccount.homeUsers(token)

    /** The profile's own token once plex.tv has accepted its PIN (it throws [PlexHttpException] when not). */
    suspend fun plexSwitchUser(user: PlexHomeUser, pin: String?, homeToken: String): String? = plexAccount.switchUser(user.uuid, pin, homeToken)

    /** Signs in to one of the account's servers, at the first of its addresses that answers; [home] is the profile it was signed in as. */
    suspend fun signInWithPlex(resource: PlexResource, accountToken: String, home: PlexHomeChoice? = null) {
        val token = resource.accessToken ?: accountToken
        val connection = PlexConnectionRace.fastest(resource.rankedConnections, token, plexDevice)
            ?: throw IllegalStateException("${resource.name} isn't answering. Check that it's on; away from home, it also needs Remote Access turned on (in Plex's settings for the server, under Remote Access).")
        val (accountId, accountName) = plexAccount.accountName(accountToken)
        val saved = Session(
            connection.uri.trimEnd('/'), token, PlexMapper.id(accountId.toString()), accountName, ServerKind.Plex, resource.name,
            PlexLink(accountToken, resource.clientIdentifier, resource.connections, home?.user?.uuid, home?.user?.hasPin == true, home?.homeToken),
        )
        store.save(saved)
        serverKind = ServerKind.Plex
        client = connect(saved.serverUrl, token, ServerKind.Plex)
        session = saved
        animeLibraryCache = null
        remember(saved)
        _locked.value = false
    }
    var session: Session? = null
        private set

    val api: ApiClient get() = checkNotNull(client) { "Not signed in" }
    val userId: UUID get() = UUID.fromString(checkNotNull(session).userId)
    /** The signed-in user's id as the server writes it, for routes the SDK has no call for. */
    val userIdForServer: String get() = checkNotNull(session).userId
    val serverUrl: String get() = checkNotNull(session).serverUrl

    /** Restores the saved sign-in. Returns false when there isn't one. */
    suspend fun restore(): Boolean {
        val saved = store.load()
        var list = store.loadServers()
        // Signed in before servers were kept: that one is the first.
        if (list.isEmpty() && saved != null) { list = listOf(SavedServer(saved)); store.saveServers(list) }
        _servers.value = list
        if (saved == null) return false
        // A Plex server whose saved address has stopped answering is found at another before anything loads from it.
        val found = if (saved.kind == ServerKind.Plex) withTimeoutOrNull(14_000) { plexFindAgain(saved) } else null
        val current = found ?: saved
        if (found != null) { store.save(found); update(_servers.value.map { if (it.id == SavedServer.id(saved.serverUrl, saved.userId)) it.copy(session = found) else it }) }
        serverKind = current.kind
        client = connect(current.serverUrl, current.accessToken, current.kind)
        session = current
        checkLock()
        return true
    }

    /** The Plex sign-in with the address that answers now, if the saved one doesn't (see [PlexReconnect]); null when it's fine or none does. */
    private suspend fun plexFindAgain(stored: Session): Session? = PlexReconnect.findAgain(
        stored,
        race = { connections, token, seconds -> PlexConnectionRace.fastest(connections, token, plexDevice, timeoutSeconds = seconds) },
        servers = { accountToken -> plexAccount.servers(accountToken) },
    )

    /**
     * Back in the app: a Plex server that has stopped answering where it was saved is looked for at its other addresses, and the one
     * in use moves to the first that answers. True when it moved, for the app to start afresh on it.
     */
    suspend fun refindPlex(): Boolean {
        val s = session?.takeIf { it.kind == ServerKind.Plex } ?: return false
        val fresh = withTimeoutOrNull(14_000) { plexFindAgain(s) } ?: return false
        store.save(fresh)
        client = connect(fresh.serverUrl, fresh.accessToken, fresh.kind)
        session = fresh
        // Its settings go with it to the new address.
        update(_servers.value.map { if (it.id == SavedServer.id(s.serverUrl, s.userId)) it.copy(session = fresh) else it })
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
                val info = probe.systemApi.getPublicSystemInfo().content
                serverKind = ServerKind.fromProductName(info.productName)
                reachedName = info.serverName
                client = connect(candidate, kind = serverKind)
                return candidate
            } catch (e: Exception) {
                failure = e
            }
        }
        throw IllegalStateException("Couldn't reach a Jellyfin or Emby server at $trimmed", failure)
    }

    suspend fun publicUsers(): List<UserDto> = io { api.userApi.getPublicUsers().content }

    suspend fun signIn(serverUrl: String, username: String, password: String) {
        val result = api.authenticationApi.authenticateUserByName(AuthenticateUserByName(username, password)).content
        finishSignIn(serverUrl, result)
    }

    /** Emby has no Quick Connect. */
    suspend fun quickConnectAvailable(): Boolean =
        io { serverKind == ServerKind.Jellyfin && runCatching { api.authenticationApi.getQuickConnectEnabled().content }.getOrDefault(false) }

    suspend fun quickConnectInitiate(): QuickConnectResult = io { api.authenticationApi.initiateQuickConnect().content }

    suspend fun quickConnectState(secret: String): QuickConnectResult =
        io { api.authenticationApi.getQuickConnectState(secret).content }

    suspend fun signInWithQuickConnect(serverUrl: String, secret: String) {
        val result = api.authenticationApi.authenticateWithQuickConnect(QuickConnectDto(secret)).content
        finishSignIn(serverUrl, result)
    }

    private suspend fun finishSignIn(serverUrl: String, result: AuthenticationResult) {
        val token = checkNotNull(result.accessToken) { "The server didn't return an access token" }
        val user = checkNotNull(result.user) { "The server didn't return a user" }
        val saved = Session(serverUrl, token, user.id.toString(), user.name.orEmpty(), serverKind, reachedName)
        store.save(saved)
        client = connect(serverUrl, token)
        session = saved
        remember(saved)
        _locked.value = false
    }

    /** Signs out of the server in use and forgets it; the others stay saved. */
    suspend fun signOut() {
        runCatching { api.sessionApi.reportSessionEnded() }
        responses.clear()
        currentServerId?.let { id -> update(_servers.value.filterNot { it.id == id }) }
        store.clearCredentials()
        animeLibraryCache = null
        client = null
        session = null
        _locked.value = false
    }

    /** Off to sign in to another server: the one in use stays saved and signed in, to switch back to. */
    suspend fun changeServer() {
        store.clearCredentials()
        animeLibraryCache = null
        client = null
        session = null
        _locked.value = false
    }

    /** Back to a saved server, with its saved sign-in or (`fresh`) a new one made from its password. */
    suspend fun switchTo(saved: SavedServer, fresh: Session? = null) {
        val stored = fresh ?: saved.session
        store.save(stored)
        serverKind = stored.kind
        client = connect(stored.serverUrl, stored.accessToken, stored.kind)
        session = stored
        animeLibraryCache = null
        remember(stored)
        _locked.value = false
    }

    /**
     * Checks a saved server's password with the server itself, or a Plex profile's PIN with plex.tv; the fresh sign-in to switch or
     * unlock with.
     */
    suspend fun signInAgain(saved: SavedServer, password: String): Session {
        val s = saved.session
        if (s.kind == ServerKind.Plex) {
            val link = s.plex?.takeIf { it.canAskPin } ?: throw IllegalStateException("Sign in with Plex again to use a PIN.")
            // plex.tv lets a profile switch to itself without a PIN, so an empty one is never sent.
            require(password.isNotEmpty()) { "Enter the PIN." }
            val own = plexAccount.switchUser(link.homeUserUuid!!, password, link.homeToken!!)
            return s.copy(plex = link.copy(accountToken = own ?: link.accountToken))
        }
        val result = connect(s.serverUrl, null, s.kind).authenticationApi.authenticateUserByName(AuthenticateUserByName(s.userName, password)).content
        val token = checkNotNull(result.accessToken) { "The server didn't return an access token" }
        val user = checkNotNull(result.user) { "The server didn't return a user" }
        return Session(s.serverUrl, token, user.id.toString(), user.name.orEmpty(), s.kind, s.serverName, household = s.household)
    }

    /** The password was right: carry on with the fresh sign-in. */
    suspend fun unlock(fresh: Session) {
        store.save(fresh)
        client = connect(fresh.serverUrl, fresh.accessToken, fresh.kind)
        session = fresh
        remember(fresh)
        _locked.value = false
    }

    /**
     * Keeps the sign-in a password check just made (Jellyfin ends the one it replaces) in place of the one saved, and for the
     * server in use carries on with it, as the Apple apps do.
     */
    suspend fun keepSignIn(fresh: Session) {
        if (SavedServer.id(fresh.serverUrl, fresh.userId) == currentServerId) {
            store.save(fresh)
            client = connect(fresh.serverUrl, fresh.accessToken, fresh.kind)
            session = fresh
        }
        remember(fresh)
    }

    // MARK: Household profiles (Bonfire on Jellyfin, a Plex Home)

    /** The Bonfire plugin's requests, with the sign-in in use; a server that can't be reached throws, a refusal comes back as an answer. */
    private suspend fun bonfireSend(method: String, path: String, body: String?): com.astermediaplayer.astermediaplayer.data.profiles.BonfireAnswer = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(serverUrl.trimEnd('/') + com.astermediaplayer.astermediaplayer.data.profiles.Bonfire.BASE_PATH + path).header("Authorization", authorizationHeader())
        if (method == "GET") builder.get() else builder.method(method, (body ?: "{}").toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).execute().use { r ->
            com.astermediaplayer.astermediaplayer.data.profiles.BonfireAnswer(r.code, r.body.string(), r.header(com.astermediaplayer.astermediaplayer.data.profiles.Bonfire.ERROR_HEADER))
        }
    }

    private val plexHomeAccess by lazy {
        com.astermediaplayer.astermediaplayer.data.profiles.PlexHomeAccess(
            homeUsers = { plexAccount.homeUsers(it) },
            switchUser = { uuid, pin, home -> plexAccount.switchUser(uuid, pin, home) },
            servers = { plexAccount.servers(it) },
            accountName = { plexAccount.accountName(it) },
        )
    }

    /** The household service for the server in use, if it could have one: Bonfire on Jellyfin, the Plex Home signed in through. */
    fun profileProvider(): com.astermediaplayer.astermediaplayer.data.profiles.ProfileProvider? {
        val s = session ?: return null
        return when (s.kind) {
            ServerKind.Jellyfin -> com.astermediaplayer.astermediaplayer.data.profiles.BonfireProfiles(::bonfireSend, s.serverUrl, s.serverName)
            ServerKind.Plex -> com.astermediaplayer.astermediaplayer.data.profiles.PlexHomeProfiles.of(plexHomeAccess, s)
            else -> null
        }
    }

    /**
     * A sign-in already saved for [profile] on the server in use, to switch to without asking the server. It never stands in for a
     * PIN: the picker only reuses it where none is asked.
     */
    fun reusableSession(profile: com.astermediaplayer.astermediaplayer.data.profiles.HouseholdProfile): Session? {
        val s = session ?: return null
        return _servers.value.map { it.session }.firstOrNull { saved ->
            if (s.kind == ServerKind.Plex) saved.plex?.machineId == s.plex?.machineId && saved.plex?.homeUserUuid == profile.rawId
            else saved.serverUrl == s.serverUrl && com.astermediaplayer.astermediaplayer.data.profiles.HouseholdProfile.plainId(saved.userId) == profile.id
        }
    }

    /** The sign-in in use learned which household it belongs to (it was made before this was kept, or through the sign-in screen). */
    suspend fun noteHousehold(household: com.astermediaplayer.astermediaplayer.data.profiles.StoredHousehold) {
        val s = session ?: return
        if (s.household == household) return
        val updated = s.copy(household = household)
        session = updated
        store.save(updated)
        remember(updated)
    }

    suspend fun setAsksPasswordToSwitch(id: String, asks: Boolean) =
        update(_servers.value.map { if (it.id == id) it.copy(asksPasswordToSwitch = asks) else it })

    suspend fun setLockAfter(id: String, minutes: Int?) =
        update(_servers.value.map { if (it.id == id) it.copy(lockAfterMinutes = minutes) else it })

    /** Forgets a saved server (not the one in use), signing it out. */
    suspend fun removeServer(id: String) {
        if (id == currentServerId) return
        val gone = _servers.value.firstOrNull { it.id == id } ?: return
        update(_servers.value.filterNot { it.id == id })
        runCatching {
            val s = gone.session
            val api = connect(s.serverUrl, s.accessToken, s.kind)
            api.sessionApi.reportSessionEnded()
        }
    }

    /** The app left the screen: the clock for the lock starts. */
    suspend fun noteLeftApp() = store.noteLeft(System.currentTimeMillis())

    /** The app is back (or just opened): locks the server in use if it was away past its lock-after. */
    suspend fun checkLock() {
        if (session == null) return
        val leftAt = store.leftAt()
        store.clearLeft()
        val minutes = currentServer?.lockAfterMinutes ?: return
        if (_locked.value || leftAt == null) return
        if (System.currentTimeMillis() - leftAt >= minutes * 60_000L) _locked.value = true
    }

    /** Adds or updates a server in the saved list, keeping its password settings. */
    private suspend fun remember(stored: Session) {
        val id = SavedServer.id(stored.serverUrl, stored.userId)
        val list = _servers.value
        update(if (list.any { it.id == id }) list.map { if (it.id == id) it.copy(session = stored) else it } else list + SavedServer(stored))
    }

    private suspend fun update(list: List<SavedServer>) {
        _servers.value = list
        store.saveServers(list)
    }

    // MARK: Browsing

    suspend fun views(): List<BaseItemDto> = io { api.userViewApi.getUserViews(userId = userId).content.items }

    /**
     * What a shelf card or a hero page shows of a title: its overview and genres, and the video's width for the HD or 4K badge.
     * The width alone, not the media sources or streams: every audio and subtitle track of 150 titles made Home a megabyte,
     * three quarters of it streams, slow to send, to read and to keep.
     */
    private val listFields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.WIDTH, ItemFields.HEIGHT)

    /** One picture of each kind a card or hero shows, as the Apple apps ask on every list, not every backdrop a title has. */
    private val listImages = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB, ImageType.LOGO)

    suspend fun resume(limit: Int = 20, parentId: UUID? = null): List<BaseItemDto> = io { api.libraryApi.getResumeItems(
        GetResumeItemsRequest(
            userId = userId, limit = limit, parentId = parentId, fields = listFields,
            mediaTypes = listOf(org.jellyfin.sdk.model.api.MediaType.VIDEO), enableImageTypes = listImages, imageTypeLimit = 1,
        ),
    ).content.items }

    suspend fun nextUp(limit: Int = 20, seriesId: UUID? = null, parentId: UUID? = null): List<BaseItemDto> = io { api.showApi.getNextUp(
        GetNextUpRequest(userId = userId, limit = limit, seriesId = seriesId, parentId = parentId, fields = listFields, enableImageTypes = listImages, imageTypeLimit = 1),
    ).content.items }

    /** Episodes of shows in the library that air soon, soonest first; most are placeholders from the show's schedule rather than files. */
    suspend fun upcoming(limit: Int = 100): List<BaseItemDto> = io { api.showApi.getUpcomingEpisodes(
        org.jellyfin.sdk.model.api.request.GetUpcomingEpisodesRequest(
            userId = userId, limit = limit, fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES),
            enableImageTypes = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB, ImageType.LOGO), imageTypeLimit = 1,
        ),
    ).content.items }

    suspend fun latest(parentId: UUID?, limit: Int = 20, kinds: List<BaseItemKind>? = null): List<BaseItemDto> = io { api.libraryApi.getLatestMedia(
        userId = userId, parentId = parentId, limit = limit, includeItemTypes = kinds,
        fields = listFields, enableImageTypes = listImages, imageTypeLimit = 1,
    ).content }

    /**
     * The newest series, by when they were added, across these libraries (everywhere when [libraryIds] holds null). `latest`
     * filtered to series returns nothing on a Jellyfin server, so this is an ordinary search sorted by date added.
     */
    suspend fun recentlyAddedShows(libraryIds: List<UUID?>, limit: Int = 20): List<BaseItemDto> = io { coroutineScope {
        libraryIds.map { parent ->
            async {
                runCatching {
                    api.libraryApi.getItems(
                        GetItemsRequest(
                            userId = userId, parentId = parent, recursive = true, includeItemTypes = listOf(BaseItemKind.SERIES),
                            sortBy = listOf(ItemSortBy.DATE_CREATED), sortOrder = listOf(SortOrder.DESCENDING), limit = limit,
                            fields = listFields + ItemFields.DATE_CREATED, enableImageTypes = listImages, imageTypeLimit = 1,
                        ),
                    ).content.items
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().sortedByDescending { it.dateCreated }.take(limit)
    } }

    /** The movies and shows a person is in, the newest first, for their page. */
    suspend fun filmography(personId: UUID): List<BaseItemDto> = io { api.libraryApi.getItems(
        GetItemsRequest(
            userId = userId, recursive = true, includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), personIds = listOf(personId),
            sortBy = listOf(ItemSortBy.PREMIERE_DATE), sortOrder = listOf(SortOrder.DESCENDING), fields = listFields,
        ),
    ).content.items }

    /** The official ratings of the server's movies and shows, the most used first, for a channel's rating filter. */
    suspend fun videoRatings(): List<String> = allItems(
        GetItemsRequest(
            userId = userId, recursive = true, includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
            enableImages = false, enableUserData = false,
        ),
    ).mapNotNull { it.officialRating?.takeIf(String::isNotEmpty) }.groupingBy { it }.eachCount().entries
        .sortedWith(compareBy({ -it.value }, { it.key })).map { it.key }

    /** Every show on the server, by name, for New Channel's From a Show. */
    suspend fun seriesList(): List<BaseItemDto> = allItems(
        GetItemsRequest(
            userId = userId, recursive = true, includeItemTypes = listOf(BaseItemKind.SERIES), sortBy = listOf(ItemSortBy.SORT_NAME),
            enableImages = false, enableUserData = false,
        ),
    )

    /** The server's user names, for restricting a channel to some of them; only an administrator may list them. */
    suspend fun userNames(): List<String> =
        io { runCatching { api.userApi.getUsers().content.mapNotNull { it.name } }.getOrDefault(emptyList()).sortedWith(String.CASE_INSENSITIVE_ORDER) }

    /** A new public video playlist holding [itemIds], as a channel's playlist; its id. */
    suspend fun createVideoPlaylist(name: String, itemIds: List<UUID>): UUID =
        io { api.playlistApi.createPlaylist(
            org.jellyfin.sdk.model.api.CreatePlaylistDto(name = name, ids = itemIds, users = emptyList(), userId = userId, mediaType = org.jellyfin.sdk.model.api.MediaType.VIDEO, isPublic = true),
        ).content.id.let { id ->
            // The server answers with 32 hex digits, no dashes.
            UUID.fromString(if (id.length == 32) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id)
        } }

    /** A new private video playlist holding [itemIds], from Add to Playlist. Emby reads the details from the query, not a body. */
    suspend fun createPlaylist(name: String, itemIds: List<UUID>) = io {
        if (serverKind == ServerKind.Emby) {
            serverRequest("POST", "/Playlists?Name=${android.net.Uri.encode(name)}&Ids=${itemIds.joinToString(",") { wire(it) }}&UserId=${checkNotNull(session).userId}&MediaType=Video")
        } else {
            api.playlistApi.createPlaylist(
                org.jellyfin.sdk.model.api.CreatePlaylistDto(name = name, ids = itemIds, users = emptyList(), userId = userId, mediaType = org.jellyfin.sdk.model.api.MediaType.VIDEO, isPublic = false),
            )
        }
    }

    suspend fun addToPlaylist(playlistId: UUID, itemIds: List<UUID>) {
        serverRequest("POST", "/Playlists/${wire(playlistId)}/Items?ids=${itemIds.joinToString(",") { wire(it) }}&userId=${checkNotNull(session).userId}")
    }

    /** The version of the server in use, for Account. */
    suspend fun serverVersion(): String? = io { runCatching { api.systemApi.getPublicSystemInfo().content.version }.getOrNull() }

    /** Whether this account can set up channels: the administrator, or anyone on Plex, where a playlist is its maker's own. */
    suspend fun canEditChannels(): Boolean = serverKind == ServerKind.Plex || isAdministrator()

    /** Whether this account is the server's administrator, who alone can change the tags that make a playlist a channel. */
    suspend fun isAdministrator(): Boolean =
        io { runCatching { api.userApi.getCurrentUser().content.policy?.isAdministrator == true }.getOrDefault(false) }

    /** The parental controls on this account, read fresh so an administrator's change counts on the next visit. */
    suspend fun contentPolicy(): com.astermediaplayer.astermediaplayer.data.channels.ContentPolicy? =
        io { api.userApi.getCurrentUser().content.policy?.let { com.astermediaplayer.astermediaplayer.data.channels.ContentPolicy.from(it) } }

    /** The ratings the server knows and how they rank. */
    suspend fun parentalRatings(): List<com.astermediaplayer.astermediaplayer.data.channels.RatingRank> =
        io { api.localizationApi.getParentalRatings().content.map { com.astermediaplayer.astermediaplayer.data.channels.RatingRank.from(it) } }

    /** The user's settings on the server (Jellyfin's or Emby's), as it sent them; null on Plex, which has none. */
    suspend fun userConfiguration(): org.json.JSONObject? {
        if (serverKind == ServerKind.Plex) return null
        return org.json.JSONObject(serverRequest("GET", "/Users/$userIdForServer")).optJSONObject("Configuration")
    }

    /** Changes the user's settings on the server: [configuration] is posted whole, so the fields not changed stay as they were. */
    suspend fun updateUserConfiguration(configuration: org.json.JSONObject) {
        val path = if (serverKind == ServerKind.Emby) "/Users/$userIdForServer/Configuration" else "/Users/Configuration?userId=$userIdForServer"
        serverRequest("POST", path, configuration.toString())
    }

    /**
     * Sets an item's tags, as the Apple apps do: its own record read back with the tags replaced and posted, so everything
     * else on it stays as it was. The server refuses anyone but an administrator.
     */
    suspend fun setTags(itemId: UUID, tags: List<String>) = io {
        val record = api.libraryApi.getItem(itemId = itemId, userId = userId).content
        api.itemUpdateApi.updateItem(itemId, record.copy(tags = tags))
    }

    /**
     * Renames channel tags from the app's earlier name, "asterfin:…", to "aster:…" on every playlist that still has them,
     * as the Apple apps do when an administrator signs in. Old tags are read meanwhile, so a failure here only leaves them.
     */
    suspend fun convertOldChannelTags() {
        if (serverKind != ServerKind.Jellyfin || !isAdministrator()) return
        for (playlist in runCatching { videoPlaylists() }.getOrDefault(emptyList())) {
            val tags = com.astermediaplayer.astermediaplayer.data.channels.ChannelTagParsing.converted(playlist.tags) ?: continue
            runCatching { setTags(playlist.id, tags) }
        }
    }

    // MARK: Channel sources

    /** The user's video playlists, with their tags: the channels among them are tagged `aster:channel`. */
    suspend fun videoPlaylists(): List<BaseItemDto> = io { playlists(
        org.jellyfin.sdk.model.api.MediaType.VIDEO,
        GetItemsRequest(
            userId = userId, recursive = true, includeItemTypes = listOf(BaseItemKind.PLAYLIST),
            sortBy = listOf(ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.ASCENDING),
            fields = listOf(ItemFields.TAGS, ItemFields.CHILD_COUNT, ItemFields.OVERVIEW),
        ),
    ) }

    /**
     * The playlists [request] finds of one [mediaType]. Emby gives a playlist no media type, and asked for one drops the playlist
     * filter and sends everything (people, genres, studios), so there each playlist's kind is its first entry's; an empty one is
     * either. As the Windows app.
     */
    private suspend fun playlists(mediaType: org.jellyfin.sdk.model.api.MediaType, request: GetItemsRequest): List<BaseItemDto> {
        if (serverKind != ServerKind.Emby) return io { api.libraryApi.getItems(request.copy(mediaTypes = listOf(mediaType))).content.items }
        val found = io { api.libraryApi.getItems(request.copy(mediaTypes = null, startIndex = null, limit = null)).content.items.filter { it.type == BaseItemKind.PLAYLIST } }
        return kotlinx.coroutines.coroutineScope {
            found.map { playlist ->
                async {
                    val first = runCatching { api.playlistApi.getPlaylistItems(playlistId = playlist.id, userId = userId, limit = 1).content.items.firstOrNull() }.getOrNull()
                    playlist.takeIf { first == null || first.mediaType == mediaType }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** What a channel's schedule and menus use of each title, and no more, so thousands of episodes stay a small download. */
    private val channelFields = listOf(ItemFields.OVERVIEW, ItemFields.TAGS)
    private val channelImages = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB, ImageType.LOGO)

    /** A playlist's movies, episodes and clips, tagged and ready for the schedule. */
    suspend fun channelPlaylistItems(playlistId: UUID): List<BaseItemDto> = io { api.playlistApi.getPlaylistItems(
        playlistId = playlistId, userId = userId, fields = channelFields, enableImages = true, enableUserData = false,
        imageTypeLimit = 1, enableImageTypes = channelImages,
    ).content.items }

    /** Every item the request finds, 500 at a time. */
    private suspend fun allItems(base: GetItemsRequest): List<BaseItemDto> {
        val found = mutableListOf<BaseItemDto>()
        while (true) {
            val result = io { api.libraryApi.getItems(base.copy(startIndex = found.size, limit = 500)).content }
            found += result.items
            if (result.items.size < 500 || found.size >= result.totalRecordCount) break
        }
        return found
    }

    private fun channelRequest(parentId: UUID? = null) = GetItemsRequest(
        userId = userId, parentId = parentId, fields = channelFields, enableImageTypes = channelImages, imageTypeLimit = 1, enableUserData = false,
    )

    /** The genres, studios and collections of the server's movies and shows, by name, for channels filled from them. */
    suspend fun videoGenres(): List<BaseItemDto> = io { api.genreApi.getGenres(
        userId = userId, includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), sortBy = listOf(ItemSortBy.SORT_NAME),
    ).content.items }

    suspend fun videoStudios(): List<BaseItemDto> = io { api.studioApi.getStudios(
        userId = userId, includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
    ).content.items }

    suspend fun collections(): List<BaseItemDto> = io { api.libraryApi.getItems(
        GetItemsRequest(userId = userId, recursive = true, includeItemTypes = listOf(BaseItemKind.BOX_SET), sortBy = listOf(ItemSortBy.SORT_NAME)),
    ).content.items }

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
        val channels = io { api.liveTvApi.getLiveTvChannels(
            org.jellyfin.sdk.model.api.request.GetLiveTvChannelsRequest(
                userId = userId, addCurrentProgram = true, limit = 500,
                fields = listOf(ItemFields.OVERVIEW),
            ),
        ).content.items }
        return guideOrder(withAiringNow(channels))
    }

    /**
     * The server's `currentProgram` on a channel can be hours stale, so what's on now comes from the programmes airing this
     * minute, as the guide has it: a channel with none shows nothing. A failed request leaves the channels as they were.
     */
    private suspend fun withAiringNow(channels: List<BaseItemDto>): List<BaseItemDto> {
        if (channels.isEmpty()) return channels
        val now = utc(System.currentTimeMillis() / 1000.0)
        val airing = runCatching {
            api.liveTvApi.getLiveTvPrograms(
                org.jellyfin.sdk.model.api.request.GetLiveTvProgramsRequest(
                    channelIds = channels.map { it.id }, userId = userId, minEndDate = now, maxStartDate = now,
                    sortBy = listOf(ItemSortBy.START_DATE), limit = 1000, enableImages = true, imageTypeLimit = 1,
                    enableImageTypes = listOf(ImageType.PRIMARY, ImageType.THUMB), fields = listOf(ItemFields.OVERVIEW), enableUserData = false,
                ),
            ).content.items
        }.getOrNull() ?: return channels
        val byChannel = airing.filter { it.channelId != null }.groupBy { it.channelId }.mapValues { (_, list) -> list.first() }
        return channels.map { it.copy(currentProgram = byChannel[it.id]) }
    }

    private fun guideOrder(channels: List<BaseItemDto>): List<BaseItemDto> = channels.sortedWith(
        compareByDescending<BaseItemDto> { it.userData?.isFavorite == true }
            .thenBy { it.channelNumber?.toDoubleOrNull() ?: Double.MAX_VALUE }
            .thenBy { it.name.orEmpty() },
    )

    /** The programme on [channelId] now, for the paused player. */
    suspend fun airingNow(channelId: UUID): BaseItemDto? = io { runCatching {
        api.liveTvApi.getLiveTvPrograms(
            org.jellyfin.sdk.model.api.request.GetLiveTvProgramsRequest(
                userId = userId, channelIds = listOf(channelId), isAiring = true, limit = 1,
                fields = listOf(ItemFields.OVERVIEW),
            ),
        ).content.items.firstOrNull()
    }.getOrNull() }

    /** What's on now across channels, optionally just one kind of programme. */
    suspend fun recommendedPrograms(
        limit: Int = 24, movie: Boolean? = null, sports: Boolean? = null, kids: Boolean? = null, news: Boolean? = null,
    ): List<BaseItemDto> = io { api.liveTvApi.getRecommendedPrograms(
        org.jellyfin.sdk.model.api.request.GetRecommendedProgramsRequest(
            userId = userId, limit = limit, isAiring = true, isMovie = movie, isSports = sports, isKids = kids, isNews = news,
            enableImageTypes = listOf(ImageType.PRIMARY, ImageType.THUMB, ImageType.BACKDROP),
            fields = listOf(ItemFields.OVERVIEW, ItemFields.CHANNEL_INFO),
        ),
    ).content.items }

    suspend fun recordings(limit: Int = 100): List<BaseItemDto> = io { api.liveTvApi.getRecordings(
        org.jellyfin.sdk.model.api.request.GetRecordingsRequest(userId = userId, limit = limit, fields = listOf(ItemFields.OVERVIEW)),
    ).content.items }

    /** A channel's programmes overlapping [from, to] (seconds since 1970), in order, with the pictures and overview the guide's cells and the program page start from. */
    suspend fun livePrograms(channelId: UUID, from: Double, to: Double): List<BaseItemDto> = io { api.liveTvApi.getLiveTvPrograms(
        org.jellyfin.sdk.model.api.request.GetLiveTvProgramsRequest(
            channelIds = listOf(channelId), userId = userId,
            minEndDate = utc(from), maxStartDate = utc(to), sortBy = listOf(ItemSortBy.START_DATE), limit = 200,
            enableImages = true, imageTypeLimit = 1, enableImageTypes = listOf(ImageType.PRIMARY, ImageType.THUMB),
            fields = listOf(ItemFields.OVERVIEW), enableUserData = false,
        ),
    ).content.items }

    /** A moment as the SDK wants a date: the device's own zone, which it sends to the server as the instant it is. */
    private fun utc(seconds: Double): java.time.LocalDateTime = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(seconds.toLong()), java.time.ZoneId.systemDefault())

    /** One sheet of a title's trickplay frames (see `TrickplayInfo`); null when the server can't give it. */
    suspend fun trickplaySheet(itemId: UUID, width: Int, index: Int, mediaSourceId: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${serverUrl.trimEnd('/')}/Videos/${wire(itemId)}/Trickplay/$width/$index.jpg?MediaSourceId=$mediaSourceId"
            http.newCall(Request.Builder().url(url).header("Authorization", authorizationHeader()).build()).execute().use { r ->
                if (r.isSuccessful) r.body.bytes() else null
            }
        }.getOrNull()
    }

    /** An Emby title's scrub thumbnails as a BIF file (see `BifFile`), or null when the server can't give it. */
    suspend fun embyBif(itemId: UUID, mediaSourceId: String, width: Int = 320): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${serverUrl.trimEnd('/')}/Videos/${wire(itemId)}/index.bif?MediaSourceId=$mediaSourceId&Width=$width"
            http.newCall(Request.Builder().url(url).header("Authorization", authorizationHeader()).build()).execute().use { r ->
                // A long film's file runs to tens of megabytes; one far bigger than that isn't worth holding.
                if (r.isSuccessful && r.body.contentLength() <= 80_000_000L) r.body.bytes() else null
            }
        }.getOrNull()
    }

    /** A title's trickplay listing, which the plain item fetch leaves out. */
    suspend fun trickplayOf(id: UUID): BaseItemDto? = io { runCatching {
        api.libraryApi.getItems(GetItemsRequest(userId = userId, ids = listOf(id), fields = listOf(ItemFields.TRICKPLAY))).content.items.firstOrNull()
    }.getOrNull() }

    /** The programme last opened from a guide or list, so its page opens with what that already showed while the full record loads. */
    val openedPrograms = HashMap<String, BaseItemDto>()

    /** One programme with everything the server knows of it, and whether it's set to record. */
    suspend fun liveProgram(id: String): BaseItemDto = io { api.liveTvApi.getProgram(id, userId).content }

    suspend fun timers(): List<org.jellyfin.sdk.model.api.TimerInfoDto> =
        io { api.liveTvApi.getTimers(isScheduled = true).content.items.sortedBy { it.startDate } }

    suspend fun seriesTimers(): List<org.jellyfin.sdk.model.api.SeriesTimerInfoDto> = io { api.liveTvApi.getSeriesTimers().content.items }

    suspend fun seriesTimer(id: String): org.jellyfin.sdk.model.api.SeriesTimerInfoDto = io { api.liveTvApi.getSeriesTimer(id).content }

    suspend fun updateSeriesTimer(timer: org.jellyfin.sdk.model.api.SeriesTimerInfoDto) = io { api.liveTvApi.updateSeriesTimer(timer.id!!, timer) }

    suspend fun cancelTimer(id: String) = io { api.liveTvApi.cancelTimer(id) }

    suspend fun cancelSeriesTimer(id: String) = io { api.liveTvApi.cancelSeriesTimer(id) }

    /** Schedules a recording of one programme using the server's defaults, posting them back as the server's own clients do. */
    suspend fun recordProgram(programId: String) = postDefaults(programId, "/LiveTv/Timers")

    /** Records every airing of a series using the server's defaults (one endpoint, whose answer is a series timer). */
    suspend fun recordSeries(programId: String) = postDefaults(programId, "/LiveTv/SeriesTimers")

    private suspend fun postDefaults(programId: String, path: String) = withContext(Dispatchers.IO) {
        val base = serverUrl.trimEnd('/')
        val defaults = http.newCall(Request.Builder().url("$base/LiveTv/Timers/Defaults?programId=${wire(programId)}").header("Authorization", authorizationHeader()).build())
            .execute().use { r -> if (!r.isSuccessful) error("The server couldn't prepare the recording (${r.code}).") else r.body.string() }
        http.newCall(
            Request.Builder().url(base + path).header("Authorization", authorizationHeader())
                .post(defaults.toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { r -> if (!r.isSuccessful) error("The server couldn't schedule the recording (${r.code}).") }
    }

    /** Lets go of a channel's tuner once playback of it ends. */
    suspend fun closeLiveStream(liveStreamId: String) = io {
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
        // Emby's playlists are told apart by their entries (see playlists), so its page is cut from the whole list here.
        if (kind == BaseItemKind.PLAYLIST && serverKind == ServerKind.Emby) {
            val all = playlists(
                org.jellyfin.sdk.model.api.MediaType.AUDIO,
                GetItemsRequest(userId = userId, recursive = true, includeItemTypes = listOf(kind), sortBy = sortBy, sortOrder = listOf(order), fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES)),
            )
            return ItemPage(all.drop(startIndex).take(limit), all.size)
        }
        val result = io { api.libraryApi.getItems(
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
        ).content }
        return ItemPage(result.items, result.totalRecordCount)
    }

    /** Artists credited on albums, paged. */
    suspend fun albumArtists(startIndex: Int, limit: Int = 100): ItemPage {
        val result = io { api.artistApi.getAlbumArtists(
            userId = userId, startIndex = startIndex, limit = limit,
            fields = listOf(ItemFields.OVERVIEW), sortBy = listOf(ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.ASCENDING),
        ).content }
        return ItemPage(result.items, result.totalRecordCount)
    }

    /** An album's songs in disc and track order. */
    suspend fun albumTracks(albumId: UUID): List<BaseItemDto> = io { api.libraryApi.getItems(
        GetItemsRequest(
            userId = userId, parentId = albumId, includeItemTypes = listOf(BaseItemKind.AUDIO),
            sortBy = listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
        ),
    ).content.items }

    /** Albums an artist is credited on, newest first. */
    suspend fun artistAlbums(artistId: UUID): List<BaseItemDto> = io { api.libraryApi.getItems(
        GetItemsRequest(
            userId = userId, albumArtistIds = listOf(artistId), includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM), recursive = true,
            sortBy = listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.DESCENDING),
        ),
    ).content.items }

    /** Every song by an artist, in album order, for Play and Shuffle. */
    suspend fun artistTracks(artistId: UUID, limit: Int = 400): List<BaseItemDto> = io { api.libraryApi.getItems(
        GetItemsRequest(
            userId = userId, artistIds = listOf(artistId), includeItemTypes = listOf(BaseItemKind.AUDIO), recursive = true,
            sortBy = listOf(ItemSortBy.ALBUM, ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER), limit = limit,
        ),
    ).content.items }

    suspend fun playlistItems(playlistId: UUID): List<BaseItemDto> =
        io { api.playlistApi.getPlaylistItems(playlistId = playlistId, userId = userId).content.items }

    /** Takes entries out of a playlist, by their place in it ([BaseItemDto.playlistItemId]), not the titles' own ids. */
    suspend fun removeFromPlaylist(playlistId: UUID, entryIds: List<String>) {
        serverRequest("DELETE", "/Playlists/${wire(playlistId)}/Items?entryIds=${entryIds.joinToString(",")}")
    }

    /** Moves a playlist's entry to [newIndex]. */
    suspend fun movePlaylistItem(playlistId: UUID, entryId: String, newIndex: Int) {
        serverRequest("POST", "/Playlists/${wire(playlistId)}/Items/$entryId/Move/$newIndex")
    }

    /** Songs the server thinks go with an item: a song, album, artist or genre. */
    suspend fun instantMix(itemId: UUID, limit: Int = 100): List<BaseItemDto> =
        io { api.instantMixApi.getInstantMixFromItem(itemId = itemId, userId = userId, limit = limit).content.items }

    /** A song's lyrics: timed lines when a synced file exists, plain lines otherwise; null when the server has none. */
    suspend fun lyrics(itemId: UUID): org.jellyfin.sdk.model.api.LyricDto? = io { runCatching {
        if (serverKind == ServerKind.Emby) embyLyrics(itemId) else api.lyricApi.getLyrics(itemId).content
    }.getOrNull() }

    /**
     * Emby has no lyrics route: it lists a song's lyrics (an .lrc beside it, or the file's own tag) as a subtitle stream,
     * fetched as text, a timed .lrc first, as the Apple apps do.
     */
    private suspend fun embyLyrics(itemId: UUID): org.jellyfin.sdk.model.api.LyricDto? {
        val song = org.json.JSONObject(serverRequest("GET", "/Users/${checkNotNull(session).userId}/Items/${wire(itemId)}?Fields=MediaSources,MediaStreams"))
        val sources = song.optJSONArray("MediaSources")
        val candidates = buildList {
            for (i in 0 until (sources?.length() ?: 0)) {
                val source = sources!!.getJSONObject(i)
                val streams = source.optJSONArray("MediaStreams") ?: song.optJSONArray("MediaStreams") ?: continue
                for (j in 0 until streams.length()) {
                    val stream = streams.getJSONObject(j)
                    if (stream.optString("Type") == "Subtitle" && stream.has("Index")) add(source.optString("Id") to stream)
                }
            }
        }
        val (sourceId, stream) = candidates.firstOrNull { it.second.optString("Codec").equals("lrc", ignoreCase = true) } ?: candidates.firstOrNull() ?: return null
        val format = stream.optString("Codec").lowercase().ifEmpty { "lrc" }
        return Lrc.parse(serverRequest("GET", "/Items/${wire(itemId)}/$sourceId/Subtitles/${stream.getInt("Index")}/Stream.$format"))
    }

    // MARK: Audiobooks

    /** The book libraries on the server. */
    suspend fun bookLibraries(): List<BaseItemDto> = views().filter { it.collectionType == CollectionType.BOOKS }

    /** Every book in [libraries], files grouped into books and authors and series read from the folders. */
    suspend fun audiobooks(libraries: List<BaseItemDto>): List<Audiobook> = io { coroutineScope {
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
    } }

    /** Items by id, in the order asked for (any the server no longer has are left out). */
    suspend fun items(ids: List<UUID>): List<BaseItemDto> {
        if (ids.isEmpty()) return emptyList()
        val found = ids.chunked(100).flatMap { chunk -> io { api.libraryApi.getItems(GetItemsRequest(userId = userId, ids = chunk)).content.items } }
        val byId = found.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /** The parts' listening state, fresh from the server. */
    suspend fun audiobookParts(ids: List<UUID>): List<BaseItemDto> =
        io { api.libraryApi.getItems(GetItemsRequest(userId = userId, ids = ids, fields = Audiobook.Fields)).content.items }

    /** A book's cover: its first file's square picture. */
    fun bookCoverUrl(book: Audiobook, maxWidth: Int): String? =
        imageUrl(book.cover, ImageType.PRIMARY, maxWidth) ?: book.cover.albumId?.let { id ->
            book.cover.albumPrimaryImageTag?.let { api.imageApi.getItemImageUrl(id, ImageType.PRIMARY, maxWidth = maxWidth, tag = it) }
        }

    // MARK: Theme media

    /** A title's theme songs and videos, inherited from its series when it has none of its own. */
    suspend fun themeSongs(itemId: UUID): List<BaseItemDto> =
        io { runCatching { api.libraryApi.getThemeSongs(itemId = itemId, userId = userId, inheritFromParent = true).content.items }.getOrDefault(emptyList()) }

    suspend fun themeVideos(itemId: UUID): List<BaseItemDto> =
        io { runCatching { api.libraryApi.getThemeVideos(itemId = itemId, userId = userId, inheritFromParent = true).content.items }.getOrDefault(emptyList()) }

    fun audioStreamUrl(itemId: UUID): String = api.audioApi.getAudioStreamUrl(itemId = itemId, static = true)

    fun videoStreamUrl(itemId: UUID): String =
        api.videoApi.getVideoStreamUrl(itemId = itemId, static = true, mediaSourceId = itemId.toString())

    // MARK: Server routes the SDK has no call for (plugins, repositories, restart)

    /** A request to a server route outside the SDK; the body of a success, an exception for anything else. */
    suspend fun serverRequest(method: String, path: String, jsonBody: String? = null): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(serverUrl.trimEnd('/') + path).header("Authorization", authorizationHeader())
        if (method == "GET") builder.get()
        else builder.method(method, (jsonBody ?: "").toRequestBody((if (jsonBody != null) "application/json" else "text/plain").toMediaType()))
        http.newCall(builder.build()).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) error("the server said ${r.code}")
            body
        }
    }

    /** Whether the server answers `path`: true for a reply, false for a 404 (a plugin that isn't there), null when it can't tell. */
    suspend fun serverHas(path: String): Boolean? {
        if (serverKind != ServerKind.Jellyfin) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(serverUrl.trimEnd('/') + path).header("Authorization", authorizationHeader()).build()).execute().use { r ->
                    if (r.code == 404) false else if (r.isSuccessful) true else null
                }
            }.getOrNull()
        }
    }

    // MARK: Discover and tracking (the Aster Discovery and Tracking plugin, at /AsterTracking)

    /** Where `/AsterTracking` was found for each server, or not, and when it was looked for. */
    private val trackingBases = java.util.concurrent.ConcurrentHashMap<String, Pair<String?, Long>>()

    /**
     * Where `/AsterTracking` is: the server itself on Jellyfin, where it's the plugin; on Plex, which takes no plugins, the
     * Aster for Plex container beside it, found among the server's addresses (see [PlexCompanion]); null on Emby, or on a
     * Plex server without one. A Plex server's addresses are asked of plex.tv again, as the container's is added to them
     * after sign-in; a server without one is looked at again after ten minutes.
     */
    private suspend fun trackingBase(): String? {
        if (serverKind == ServerKind.Jellyfin) return serverUrl
        if (serverKind != ServerKind.Plex) {
            com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog.note("Tracking", "None on a $serverKind server")
            return null
        }
        val server = currentServerId ?: return null
        trackingBases[server]?.let { (found, at) -> if (found != null || System.currentTimeMillis() - at < 10 * 60_000L) return found }
        val current = session ?: return null
        val link = current.plex
        if (link == null) {
            com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog.note("Tracking", "No plex.tv link for this sign-in, so no addresses to look for Aster for Plex at")
            trackingBases[server] = null to System.currentTimeMillis()
            return null
        }
        val connections = runCatching { plexAccount.servers(link.accountToken).firstOrNull { it.clientIdentifier == link.machineId }?.connections }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: link.connections
        val found = runCatching { PlexCompanion.find(PlexCompanion.candidates(connections), current.accessToken, plexDevice) }.getOrNull()
        trackingBases[server] = found to System.currentTimeMillis()
        com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog.note("Tracking", if (found != null) "Aster for Plex at $found" else "No Aster for Plex among the server's addresses")
        return found
    }

    /** A request to `/AsterTracking` [path] at [base], signed in as the server wants: Plex's token for the container, Jellyfin's header for the plugin. */
    private fun trackingRequest(base: String, path: String): Request.Builder {
        val builder = Request.Builder().url(base.trimEnd('/') + path)
        return if (serverKind == ServerKind.Plex) builder.header("X-Plex-Token", checkNotNull(session).accessToken).header("Accept", "application/json")
        else builder.header("Authorization", authorizationHeader())
    }

    /** A GET or other call on `/AsterTracking`; the body, or throws on anything but success. */
    private suspend fun trackingCall(method: String, path: String, jsonBody: String? = null): String = withContext(Dispatchers.IO) {
        val base = trackingBase() ?: error("no Aster tracking on this server")
        val builder = trackingRequest(base, path)
        if (method == "GET") builder.get() else builder.method(method, (jsonBody ?: "").toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) error("the server said ${r.code}")
            body
        }
    }

    /** The last `/AsterTracking/Features` this session had a 200 for, by server and user; kept through a later failure. */
    private val trackingFeaturesByServer = java.util.concurrent.ConcurrentHashMap<String, TrackingFeatures>()
    /** When each server was last asked, so coming back to the app after a long while asks again. */
    private val trackingFeaturesAskedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * What the plugin offers on this server, or null without it: a 404, a Plex or Emby server. Anything else (another status,
     * no answer) counts as no plugin too, unless this session had a 200 from it before, which then stands.
     */
    suspend fun trackingFeatures(maxAgeMs: Long = 30 * 60_000L): TrackingFeatures? {
        if (serverKind == ServerKind.Emby) return null
        val server = currentServerId ?: return null
        val asked = trackingFeaturesAskedAt[server]
        if (asked != null && System.currentTimeMillis() - asked < maxAgeMs) return trackingFeaturesByServer[server]
        // One request for everyone asking at once (the menu, Discover and Seerr all ask at launch).
        return trackingFeaturesLock.withLock { trackingFeaturesNow(server, maxAgeMs) }
    }

    private val trackingFeaturesLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun trackingFeaturesNow(server: String, maxAgeMs: Long): TrackingFeatures? {
        val asked = trackingFeaturesAskedAt[server]
        if (asked != null && System.currentTimeMillis() - asked < maxAgeMs) return trackingFeaturesByServer[server]
        return withContext(Dispatchers.IO) {
            val base = trackingBase()
            if (base == null) {
                trackingFeaturesAskedAt[server] = System.currentTimeMillis()
                trackingFeaturesByServer.remove(server)
                return@withContext null
            }
            val answer = runCatching {
                http.newCall(trackingRequest(base, "/AsterTracking/Features").build()).execute().use { r ->
                    com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog.note("Tracking", "Features on a $serverKind server at $base: ${r.code}")
                    when {
                        r.isSuccessful -> Result.success(TrackingFeatures.parse(r.body.string()))
                        r.code == 404 -> Result.success(null)
                        else -> Result.failure(IllegalStateException("the server said ${r.code}"))
                    }
                }
            }.getOrElse { Result.failure(it) }
            trackingFeaturesAskedAt[server] = System.currentTimeMillis()
            answer.fold(
                onSuccess = { features ->
                    if (features != null) trackingFeaturesByServer[server] = features else trackingFeaturesByServer.remove(server)
                    features
                },
                onFailure = {
                    android.util.Log.w("Aster", "AsterTracking features: $it")
                    trackingFeaturesByServer[server]
                },
            )
        }
    }

    /** The signed-in person's Discover page, built by the server; [fresh] has it built again now (pull to refresh). */
    suspend fun discoverHome(fresh: Boolean = false): DiscoverPage {
        val text = trackingCall("GET", "/AsterTracking/Discover/Home" + if (fresh) "?fresh=true" else "")
        return withContext(Dispatchers.Default) { DiscoverPage.parse(text) }.also { keepSnapshot("discover-home", text) }
    }

    /** The server's Discover page as it was last time, to show while it's asked again. */
    suspend fun keptDiscoverHome(): DiscoverPage? = snapshot("discover-home")?.let { withContext(Dispatchers.Default) { runCatching { DiscoverPage.parse(it) }.getOrNull() } }

    /** A Discover poster's address: a web one as it is, a path on the server with the sign-in on it (pictures can't send headers). */
    fun discoverPosterUrl(poster: String?): String? {
        val url = DiscoverPage.posterUrl(poster, serverUrl) ?: return null
        return if (url.startsWith(serverUrl.trimEnd('/'))) withToken(url) else url
    }

    /** Seerr as the signed-in person reaches it through the plugin: who they are there, kept per server; see [seerr]. */
    data class SeerrAccount(val client: com.astermediaplayer.astermediaplayer.data.seerr.SeerrClient, val userId: Int, val name: String, val managesRequests: Boolean)

    private val seerrAccounts = java.util.concurrent.ConcurrentHashMap<String, SeerrAccount>()

    /**
     * Seerr through the server's plugin, or null when requests are off (no plugin, or Seerr not turned on). Its user is asked
     * once per server; a Seerr briefly out of reach still gives the account, to try again as pages ask.
     */
    suspend fun seerr(): SeerrAccount? {
        val features = trackingFeatures() ?: return null
        if (!features.seerrEnabled) return null
        val server = currentServerId ?: return null
        seerrAccounts[server]?.let { return it }
        val base = seerrBase(features.seerrProxy)
        val client = com.astermediaplayer.astermediaplayer.data.seerr.SeerrClient { method, path, body -> seerrSend(base, method, path, body) }
        return try {
            val me = client.me()
            SeerrAccount(client, me.id, me.displayName.orEmpty(), me.managesRequests).also { seerrAccounts[server] = it }
        } catch (e: com.astermediaplayer.astermediaplayer.data.seerr.SeerrException.Off) {
            null
        } catch (e: Exception) {
            com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog.note("Seerr", "Its user couldn't be read: ${e.message}")
            SeerrAccount(client, 0, "", false)
        }
    }

    /**
     * Where a title stands on one of the person's watchlists: [on] is null when it couldn't be told. [fits] is whether the
     * service can hold the title at all (AniList and MyAnimeList only anime); null from a plugin before 0.9.9, Simkl only.
     */
    data class WatchlistState(val service: String, val linked: Boolean, val on: Boolean?, val fits: Boolean? = null) {
        /** The service's own name: Simkl, AniList, MyAnimeList. */
        val name: String get() = nameOf(service)

        companion object {
            fun nameOf(service: String): String = when (service.lowercase()) {
                "simkl" -> "Simkl"
                "anilist" -> "AniList"
                "myanimelist" -> "MyAnimeList"
                else -> service.replaceFirstChar { it.uppercase() }
            }
        }
    }

    /**
     * Where a title stands on the watchlists the server can add to (the plugin's `/AsterTracking/Watchlist`), linked ones
     * and not; empty without the plugin or on one too old to add to watchlists.
     */
    suspend fun watchlistState(mediaType: String, ids: Map<String, String>): List<WatchlistState> {
        val features = trackingFeatures() ?: return emptyList()
        if (features.watchlist.isEmpty()) return emptyList()
        val query = (listOf("mediaType" to mediaType) + ids.filterValues { it.isNotBlank() }.toList())
            .joinToString("&") { (k, v) -> "$k=${android.net.Uri.encode(v)}" }
        return runCatching {
            val reply = JSONObject(trackingCall("GET", "/AsterTracking/Watchlist?$query"))
            val list = reply.ci("Services") as? org.json.JSONArray ?: return@runCatching emptyList()
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { o ->
                WatchlistState((o.ci("Service") as? String).orEmpty(), o.ci("Linked") == true, o.ci("On") as? Boolean, o.ci("Fits") as? Boolean)
            }.filter { state -> features.watchlist.any { it.equals(state.service, ignoreCase = true) } }
        }.onFailure { android.util.Log.w("Aster", "Watchlist state: $it") }.getOrDefault(emptyList())
    }

    /** A service's watchlist can't hold the title after all (400, 404): it's no longer offered for it. */
    class WatchlistUnsupported : Exception()

    /** Puts a title on a service's watchlist ([on]) or takes it off; whether it's on it now. Throws with words to show. */
    suspend fun setWatchlist(service: String, on: Boolean, mediaType: String, anime: Boolean, ids: Map<String, String>, title: String?, year: Int?): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("MediaType", mediaType).put("Anime", anime)
                .put("Ids", JSONObject(ids.filterValues { it.isNotBlank() }))
            title?.let { body.put("Title", it) }
            year?.let { body.put("Year", it) }
            val base = trackingBase() ?: error("This server has no Aster tracking.")
            val request = trackingRequest(base, "/AsterTracking/Watchlist/" + android.net.Uri.encode(service))
                .method(if (on) "POST" else "DELETE", body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { r ->
                val text = r.body.string()
                if (r.isSuccessful) return@use runCatching { JSONObject(text).ci("On") as? Boolean }.getOrNull() ?: on
                // 400 or 404: nothing the service can do for this title, so it isn't offered.
                if (r.code == 400 || r.code == 404) throw WatchlistUnsupported()
                // The plugin's problems carry their reason in `detail` ("It's on your AniList as Watching already.").
                val detail = runCatching { JSONObject(text).ci("detail") as? String }.getOrNull()?.takeIf { it.isNotBlank() }
                if (detail != null) error(detail)
                val name = WatchlistState.nameOf(service)
                error(when (r.code) {
                    409 -> "Link $name to your account first."
                    410 -> "$name needs linking again."
                    422 -> "$name doesn't know this title."
                    502 -> "$name couldn't be reached. Try again."
                    else -> "The server said ${r.code}."
                })
            }
        }

    /** One call on the plugin's tracking accounts: the status and the body, whatever the status. */
    private suspend fun trackingSend(method: String, path: String, body: JSONObject? = null): Pair<Int, String> = withContext(Dispatchers.IO) {
        val base = trackingBase() ?: return@withContext 404 to ""
        val builder = trackingRequest(base, "/AsterTracking/Accounts" + path)
        if (method == "GET") builder.get()
        else builder.method(method, (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).execute().use { r -> r.code to r.body.string() }
    }

    /** What the person has linked through the plugin, and its last sends. */
    suspend fun trackedAccounts(): TrackedAccounts {
        val (status, text) = trackingSend("GET", "")
        if (status != 200) throw TrackingException.status(status)
        return TrackedAccounts.parse(text)
    }

    /** Starts linking [service]: where to go, and the code to enter there (or that a code is to be pasted back). */
    suspend fun startTrackingLink(service: TrackedService): TrackedLink {
        val (status, text) = trackingSend("POST", "/${service.id}/Link")
        return when (status) {
            200 -> TrackedLink.parse(text)
            409 -> throw TrackingException.noApp(service)
            502 -> throw TrackingException.unreachable(service)
            else -> throw TrackingException.status(status)
        }
    }

    /** Asks whether the code's been entered or Aster approved, or hands over what was [pasted]. */
    suspend fun completeTrackingLink(service: TrackedService, pasted: String? = null): TrackedLinkResult {
        val (status, _) = trackingSend("POST", "/${service.id}/Link/Complete", pasted?.let { JSONObject().put("Code", it) })
        return when (status) {
            200 -> TrackedLinkResult.Linked
            202 -> TrackedLinkResult.Waiting
            400 -> TrackedLinkResult.Failed("Paste the code the sign-in page showed.")
            409 -> TrackedLinkResult.Failed(TrackingException.noApp(service).message.orEmpty())
            410 -> TrackedLinkResult.Expired
            502 -> TrackedLinkResult.Failed(TrackingException.unreachable(service).message.orEmpty())
            else -> TrackedLinkResult.Failed(TrackingException.status(status).message.orEmpty())
        }
    }

    /** Whether the server sends what the person watches or listens to, to [service]. */
    suspend fun setTrackingScrobble(service: TrackedService, on: Boolean) {
        val (status, _) = trackingSend("PUT", "/${service.id}/Settings", JSONObject().put("Scrobble", on))
        if (status !in 200..299) throw TrackingException.status(status)
    }

    suspend fun unlinkTracking(service: TrackedService) {
        val (status, _) = trackingSend("DELETE", "/${service.id}")
        if (status !in 200..299) throw TrackingException.status(status)
    }

    /** "{server}/AsterTracking/Seerr": where Seerr's `/api/v1/…` paths go on. */
    private fun seerrBase(proxy: String?): String {
        var path = proxy ?: "/AsterTracking/Seerr/api/v1"
        if (path.endsWith("/api/v1")) path = path.removeSuffix("/api/v1")
        path = path.trimEnd('/')
        return serverUrl.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
    }

    /** One call on Seerr's API through the plugin: its own paths, with the Jellyfin authorization and no Seerr cookie. */
    private suspend fun seerrSend(base: String, method: String, path: String, body: String?): Pair<Int, String> = withContext(Dispatchers.IO) {
        val auth = authorizationHeader()
        val builder = Request.Builder().url("$base/api/v1$path").header("Accept", "application/json")
            .header("Authorization", auth).header("X-Emby-Authorization", auth)
        if (method == "GET") builder.get()
        else builder.method(method, (body ?: "").toRequestBody("application/json".toMediaType()))
        try {
            http.newCall(builder.build()).execute().use { r -> r.code to r.body.string() }
        } catch (e: java.io.IOException) {
            throw com.astermediaplayer.astermediaplayer.data.seerr.SeerrException.Unreachable()
        }
    }

    /** The anime libraries' series ids, for telling anime episodes from others. */
    suspend fun animeSeriesIds(): Set<UUID> = io { runCatching {
        val libraries = animeLibraries(views())
        libraries.flatMap { lib ->
            api.libraryApi.getItems(GetItemsRequest(userId = userId, parentId = lib.id, includeItemTypes = listOf(BaseItemKind.SERIES), recursive = true, enableImages = false)).content.items.map { it.id }
        }.toSet()
    }.getOrDefault(emptySet()) }

    // MARK: Disc menus (the Aster Disc plugin)

    /** The resolved disc menu for a movie, series or season; null when the server has none (a 404 is "nothing to show", never an error). */
    suspend fun discMenu(itemId: UUID): com.astermediaplayer.astermediaplayer.data.disc.DiscMenuDocument? {
        if (serverKind != ServerKind.Jellyfin) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(serverUrl.trimEnd('/') + "/DiscMenus/${wire(itemId)}/Menu")
                    .header("Authorization", authorizationHeader()).build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    com.astermediaplayer.astermediaplayer.data.disc.DiscMenuDocument.parse(response.body.string())
                }
            }.onFailure { android.util.Log.w("Aster", "Disc menu $itemId failed: $it") }.getOrNull()
        }
    }

    /** A picture or sound a menu ships with (`asset:folder/file`); the route needs no sign-in. */
    fun discAssetUrl(segments: List<String>): String =
        serverUrl.trimEnd('/') + "/DiscMenus/Assets/" + segments.joinToString("/") { android.net.Uri.encode(it) }

    fun discFanartUrl(itemId: UUID, fanartId: String): String = withToken("${serverUrl.trimEnd('/')}/DiscMenus/Fanart/${wire(itemId)}/$fanartId")

    fun chapterImageUrl(itemId: UUID, index: Int, stamp: Long?): String =
        withToken("${serverUrl.trimEnd('/')}/Items/${wire(itemId)}/Images/Chapter/$index?maxWidth=480" + (stamp?.let { "&tag=$it" } ?: ""))

    /** A clip's own picture, where the server made one for its chapter (Plex), else the episode's still. */
    fun clipPictureUrl(clip: ThemeClip, maxWidth: Int): String? {
        val picture = clip.picture
        if (picture != null && serverKind == ServerKind.Plex) {
            // A chapter's picture is fetched as it is, by its path: it's small already, and Plex's photo transcoder refuses it.
            return serverUrl.trimEnd('/') + picture + "?X-Plex-Token=" + android.net.Uri.encode(checkNotNull(session).accessToken)
        }
        return imageUrl(clip.episode, ImageType.PRIMARY, maxWidth)
    }

    /** A backdrop or other picture of an item at full width, for a menu's background. */
    fun itemImageUrl(itemId: UUID, type: String, index: Int): String =
        withToken("${serverUrl.trimEnd('/')}/Items/${wire(itemId)}/Images/$type/$index?maxWidth=1920")

    /** The URL with the sign-in token in it unless it carries one, for a Chromecast, which can't send headers. */
    fun withApiKey(url: String): String = if (url.contains("api_key=", ignoreCase = true) || url.contains("ApiKey=", ignoreCase = true)) url else withToken(url)

    private fun withToken(url: String): String {
        val token = android.net.Uri.encode(checkNotNull(session).accessToken)
        return url + (if ('?' in url) "&" else "?") + "ApiKey=$token"
    }

    // MARK: OP/ED plugin

    private val http = sharedHttp

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
    suspend fun opEdThemes(itemId: UUID): List<OpEdTheme>? = opEdGet("/OpEd/Items/${wire(itemId)}/Themes")?.let { json ->
        val themes = json.optJSONArray("Themes") ?: return@let emptyList()
        List(themes.length()) { OpEdTheme.fromJson(themes.getJSONObject(it)) }
    }

    /**
     * An episode's openings and endings from AnimeThemes, for a server without the OP/ED plugin: its season's AniDB id finds the show
     * exactly where the server has one (Jellyfin with Shoko), else its name and the year the season began.
     */
    suspend fun knownThemes(episode: BaseItemDto): List<KnownTheme> {
        if (!songLookups) return emptyList()
        val series = episode.seriesName ?: return emptyList()
        val season = episode.seasonId?.let { runCatching { item(it) }.getOrNull() }
        val aniDb = season?.providerIds?.entries?.firstOrNull { it.key.equals("AniDB", ignoreCase = true) }?.value
        val year = season?.productionYear ?: season?.premiereDate?.year ?: episode.premiereDate?.year ?: episode.productionYear
        return AnimeThemesLookup.themes(http, aniDb, series, year, showStore())
    }

    /** What AnimeThemes said about each show, kept between runs for this server. */
    private fun showStore(): ShowStore? = session?.serverUrl?.let { server ->
        object : ShowStore {
            override suspend fun candidates(name: String) = songThemes.shows(server, name)
            override suspend fun put(shows: Map<String, List<ShowCandidate>>) = songThemes.putShows(server, shows)
        }
    }

    /**
     * The songs AnimeThemes names for every show in the anime [libraries], asked 50 shows to a request in the background, so a show's
     * page finds them kept. Only on a server with no OP/ED plugin (which names them itself), and only what isn't kept yet is asked, so
     * it's quick after the first time.
     */
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var warming: String? = null
    private var animeLibraryNow: List<BaseItemDto> = emptyList()

    /**
     * [warmAnimeThemes] in the background, outliving the screen that asked, once for each server a run signs in to. With no libraries
     * it uses the ones last given, which is how switching the setting back on starts it.
     */
    fun warmAnimeThemesInBackground(libraries: List<BaseItemDto>? = null) {
        if (libraries != null) animeLibraryNow = libraries
        if (!songLookups || animeLibraryNow.isEmpty()) return
        val server = session?.serverUrl ?: return
        synchronized(this) { if (warming == server) return; warming = server }
        val given = animeLibraryNow
        background.launch { runCatching { warmAnimeThemes(given) } }
    }

    /**
     * Settings > Title Pages > Refresh Songs: forgets what was kept for this server (and this run's answers) and asks AnimeThemes again
     * for every show. False when AnimeThemes couldn't be reached all the way through; the rest is kept.
     */
    suspend fun refreshSongs(): Boolean {
        val server = session?.serverUrl
        if (server != null) songThemes.clear(server)
        AnimeThemesLookup.reset()
        if (!songLookups || server == null || animeLibraryNow.isEmpty()) return true
        synchronized(this) { warming = server }
        return warmAnimeThemes(animeLibraryNow)
    }

    private suspend fun warmAnimeThemes(libraries: List<BaseItemDto>): Boolean {
        val store = showStore() ?: return true
        // The OP/ED plugin names the songs itself.
        if (runCatching { opEdLibraries() }.getOrNull() != null) return true
        val names = libraries.filter { it.collectionType == CollectionType.TVSHOWS }.flatMap { library ->
            val shows = mutableListOf<String>()
            while (true) {
                val page = runCatching { libraryItems(library.id, library.collectionType, shows.size, 300, listOf(ItemSortBy.SORT_NAME), SortOrder.ASCENDING) }.getOrNull() ?: break
                shows += page.items.mapNotNull { it.name }
                if (page.items.isEmpty() || shows.size >= page.total) break
            }
            shows
        }
        val warmed = AnimeThemesLookup.prefetch(http, names, store)
        android.util.Log.d("Aster", "AnimeThemes warmed: ${names.size} shows, ${warmed.asked} asked${if (warmed.complete) "" else ", stopped early"}")
        return warmed.complete
    }

    /** The theme a published music video (or song) is, from the OP/ED plugin; null for any other item, or without the plugin. */
    suspend fun opEdThemeForLibraryItem(itemId: UUID): OpEdTheme? =
        if (serverKind != ServerKind.Jellyfin) null else opEdGet("/OpEd/LibraryItems/${wire(itemId)}/Theme")?.let { OpEdTheme.fromJson(it) }

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
    suspend fun latestMusicVideos(libraryId: UUID, limit: Int = 20): List<BaseItemDto> = io { runCatching {
        api.libraryApi.getItems(
            GetItemsRequest(
                userId = userId, parentId = libraryId, recursive = true,
                includeItemTypes = listOf(BaseItemKind.MUSIC_VIDEO),
                sortBy = listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.DESCENDING),
                limit = limit,
            ),
        ).content.items
    }.getOrDefault(emptyList()) }

    /** The openings and endings in a series' episodes' chapter markers: what the plugin falls back to. */
    suspend fun chapterThemes(seriesId: UUID, episodeCount: Int? = null): List<ThemeClip> {
        val server = session?.serverUrl
        if (server != null) songThemes.clips(server, seriesId, episodeCount)?.let { return it }
        val found = runCatching { OpeningsEndings.find(episodes(seriesId, null, listOf(ItemFields.CHAPTERS))) }
        // Read again when the show has more episodes; a failed read isn't kept.
        if (server != null) found.getOrNull()?.let { songThemes.putClips(server, seriesId, episodeCount, it) }
        return found.getOrDefault(emptyList())
    }

    /** An id as Jellyfin writes it either way: lowercase, without dashes. */
    fun plainId(id: String): String = id.lowercase().replace("-", "")

    // MARK: Anime

    private var animeLibraryCache: List<BaseItemDto>? = null

    /**
     * The TV and movie libraries that hold anime. Reading a library's metadata settings needs an admin account,
     * so a few titles in each are sampled: when at least half were matched by Shoko/AniDB (or tagged as anime), or the library is named for anime, it is anime.
     */
    suspend fun animeLibraries(views: List<BaseItemDto>): List<BaseItemDto> {
        animeLibraryCache?.let { return it }
        // Nothing to sample yet (the views haven't loaded); don't remember that as "no anime".
        if (views.isEmpty()) return emptyList()
        val found = coroutineScope {
            views.filter { it.collectionType == CollectionType.MOVIES || it.collectionType == CollectionType.TVSHOWS }
                .map { library ->
                    async(Dispatchers.IO) {
                        val sample = runCatching {
                            api.libraryApi.getItems(
                                GetItemsRequest(
                                    userId = userId, parentId = library.id, recursive = true,
                                    includeItemTypes = listOf(BaseItemKind.SERIES, BaseItemKind.MOVIE),
                                    limit = 5, fields = listOf(ItemFields.PROVIDER_IDS),
                                ),
                            ).content.items
                        }.getOrDefault(emptyList())
                        library to (sample.isNotEmpty() && (sample.count { isAnimeTitle(it) } * 2 >= sample.size ||
                            // Libraries without Shoko matches (and Plex's patchy genre tags) are still often named for what they hold.
                            library.name?.contains("anime", ignoreCase = true) == true))
                    }
                }
                .awaitAll().filter { it.second }.map { it.first }
        }
        animeLibraryCache = found
        return found
    }

    /** The newest series and films across the anime libraries; a season that got new episodes shows as its series. */
    suspend fun recentlyAddedAnime(libraries: List<BaseItemDto>, limit: Int = 20): List<BaseItemDto> = io { coroutineScope {
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
    } }

    private val libraryKinds = mutableMapOf<UUID, List<BaseItemKind>>()

    suspend fun libraryPage(parentId: UUID, startIndex: Int, limit: Int, kinds: List<BaseItemKind>? = null): ItemPage {
        val result = io { api.libraryApi.getItems(
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
        ).content }
        return ItemPage(result.items, result.totalRecordCount)
    }

    /** Every title of a kind across the server, in the order asked for, a page at a time. */
    suspend fun typePage(kind: BaseItemKind, startIndex: Int, limit: Int, sortBy: List<ItemSortBy>, order: SortOrder): ItemPage {
        val result = io { api.libraryApi.getItems(
            GetItemsRequest(
                userId = userId, recursive = true, includeItemTypes = listOf(kind),
                sortBy = sortBy, sortOrder = listOf(order), startIndex = startIndex, limit = limit,
                fields = listOf(ItemFields.OVERVIEW, ItemFields.SORT_NAME), enableImageTypes = listImages, imageTypeLimit = 1,
            ),
        ).content }
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
        val result = io { api.libraryApi.getItems(
            GetItemsRequest(
                userId = userId, parentId = parentId, recursive = kind != null, includeItemTypes = kind?.let { listOf(it) },
                sortBy = sortBy, sortOrder = listOf(order), startIndex = startIndex, limit = limit,
                fields = listOf(ItemFields.OVERVIEW, ItemFields.SORT_NAME), enableImageTypes = listImages, imageTypeLimit = 1,
            ),
        ).content }
        return ItemPage(result.items, result.totalRecordCount)
    }

    /** The titles marked as favourites, for the Library page's shelf. */
    suspend fun favoriteTitles(limit: Int = 40): List<BaseItemDto> = io { api.libraryApi.getItems(
        GetItemsRequest(
            userId = userId, recursive = true, filters = listOf(org.jellyfin.sdk.model.api.ItemFilter.IS_FAVORITE),
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
            sortBy = listOf(ItemSortBy.SORT_NAME), limit = limit, fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES),
            enableImageTypes = listImages, imageTypeLimit = 1,
        ),
    ).content.items }

    suspend fun itemIds(libraryIds: List<UUID>, kind: BaseItemKind): Set<UUID> = io { coroutineScope {
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
    } }

    suspend fun item(id: UUID): BaseItemDto = io { api.libraryApi.getItem(itemId = id, userId = userId).content }

    suspend fun seasons(seriesId: UUID): List<BaseItemDto> =
        io { api.showApi.getSeasons(seriesId = seriesId, userId = userId, enableImageTypes = listImages, imageTypeLimit = 1).content.items }

    suspend fun episodes(seriesId: UUID, seasonId: UUID?, fields: List<ItemFields> = listOf(ItemFields.OVERVIEW)): List<BaseItemDto> = io { api.showApi.getEpisodes(
        seriesId = seriesId, userId = userId, seasonId = seasonId, fields = fields, enableImageTypes = listImages, imageTypeLimit = 1,
    ).content.items }

    /** Extras: behind the scenes, trailers, deleted scenes. */
    suspend fun specialFeatures(id: UUID): List<BaseItemDto> =
        io { runCatching { api.libraryApi.getSpecialFeatures(itemId = id, userId = userId).content }.getOrDefault(emptyList()) }

    suspend fun similar(id: UUID, limit: Int = 12): List<BaseItemDto> =
        io { api.libraryApi.getSimilarItems(itemId = id, userId = userId, limit = limit, fields = listFields).content.items }

    suspend fun search(term: String, limit: Int = 60): List<BaseItemDto> {
        val hints = io { api.searchApi.getSearchHints(
            searchTerm = term, userId = userId, limit = limit,
            includeItemTypes = listOf(
                BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE,
                BaseItemKind.MUSIC_ALBUM, BaseItemKind.MUSIC_ARTIST, BaseItemKind.AUDIO, BaseItemKind.PLAYLIST,
            ),
        ).content.searchHints }
        // Hints are thin; fetch the full items so cards have images and user data.
        if (hints.isEmpty()) return emptyList()
        return io { api.libraryApi.getItems(
            GetItemsRequest(userId = userId, ids = hints.map { it.itemId }, fields = listFields, enableImageTypes = listImages, imageTypeLimit = 1),
        ).content.items }
    }

    suspend fun setFavorite(id: UUID, favorite: Boolean) = io {
        if (favorite) api.userDataApi.markFavoriteItem(itemId = id, userId = userId)
        else api.userDataApi.unmarkFavoriteItem(itemId = id, userId = userId)
    }

    suspend fun setPlayed(id: UUID, played: Boolean) = io {
        if (played) api.userDataApi.markPlayedItem(itemId = id, userId = userId)
        else api.userDataApi.markUnplayedItem(itemId = id, userId = userId)
    }

    // MARK: Images

    /**
     * A picture on the server at [maxWidth]: cards (800 wide or less) at quality 80, which is about 40% smaller than the
     * server's 90 with no difference to see at that size (as the Apple apps ask); larger art at 90.
     */
    private fun picture(itemId: UUID, imageType: ImageType, imageIndex: Int? = null, maxWidth: Int, tag: String?): String =
        api.imageApi.getItemImageUrl(itemId, imageType, imageIndex = imageIndex, maxWidth = maxWidth, tag = tag, quality = if (maxWidth <= 800) 80 else 90)

    fun imageUrl(item: BaseItemDto, type: ImageType, maxWidth: Int): String? {
        val tag = item.imageTags?.get(type)
        return when {
            tag != null -> picture(item.id, type, maxWidth = maxWidth, tag = tag)
            // Episodes and seasons borrow art from their series.
            type == ImageType.PRIMARY && item.seriesPrimaryImageTag != null && item.seriesId != null ->
                picture(item.seriesId!!, type, maxWidth = maxWidth, tag = item.seriesPrimaryImageTag)
            type == ImageType.LOGO && item.parentLogoItemId != null && item.parentLogoImageTag != null ->
                picture(item.parentLogoItemId!!, type, maxWidth = maxWidth, tag = item.parentLogoImageTag)
            type == ImageType.BACKDROP && !item.backdropImageTags.isNullOrEmpty() ->
                picture(item.id, type, imageIndex = 0, maxWidth = maxWidth, tag = item.backdropImageTags!!.first())
            type == ImageType.BACKDROP && item.parentBackdropImageTags?.isNotEmpty() == true && item.parentBackdropItemId != null ->
                picture(item.parentBackdropItemId!!, type, imageIndex = 0, maxWidth = maxWidth, tag = item.parentBackdropImageTags!!.first())
            else -> null
        }
    }

    /** The series poster for an episode (or season), whose own art is just a still. */
    fun seriesPosterUrl(item: BaseItemDto, maxWidth: Int): String? {
        val seriesId = item.seriesId ?: return null
        val tag = item.seriesPrimaryImageTag ?: return null
        return picture(seriesId, ImageType.PRIMARY, maxWidth = maxWidth, tag = tag)
    }

    /** An item's primary picture by its id and tag, for art that belongs to another item (a song's album). */
    fun imageUrlFor(id: UUID, tag: String, maxWidth: Int, type: ImageType = ImageType.PRIMARY): String =
        picture(id, type, maxWidth = maxWidth, tag = tag)

    fun personImageUrl(person: BaseItemPerson, maxWidth: Int): String? =
        person.primaryImageTag?.let { picture(person.id, ImageType.PRIMARY, maxWidth = maxWidth, tag = it) }

    // MARK: Playback

    suspend fun playbackInfo(
        itemId: UUID,
        startPositionTicks: Long,
        maxBitrate: Int,
        mediaSourceId: String? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        forceTranscode: Boolean = false,
        /** A fresh encode that copies nothing from the file, the last stream tried when the others won't play. */
        mostCompatible: Boolean = false,
        cast: Boolean = false,
    ): PlaybackInfoResponse = io { api.mediaInfoApi.getPostedPlaybackInfo(
        itemId,
        PlaybackInfoDto(
            userId = userId,
            maxStreamingBitrate = maxBitrate,
            startTimeTicks = startPositionTicks,
            mediaSourceId = mediaSourceId,
            audioStreamIndex = audioStreamIndex,
            subtitleStreamIndex = subtitleStreamIndex,
            deviceProfile = if (cast) AndroidDeviceProfile.cast(maxBitrate) else AndroidDeviceProfile.build(maxBitrate, copyHevc = !forceTranscode),
            enableDirectPlay = !forceTranscode,
            enableDirectStream = !forceTranscode,
            enableTranscoding = true,
            autoOpenLiveStream = true,
            allowVideoStreamCopy = !mostCompatible,
            allowAudioStreamCopy = !mostCompatible,
        ),
    ).content }

    /** Whether the server lets this user download media ("Allow media downloading" in the user's settings). */
    suspend fun canDownload(): Boolean = io { runCatching { api.userApi.getCurrentUser().content.policy?.enableContentDownloading }.getOrNull() ?: false }

    /** The server's HLS stream for saving a title, at [quality], with the chosen tracks; [subtitleIndex] is burned in, -1 for none. */
    suspend fun downloadInfo(
        itemId: UUID, maxBitrate: Int, maxWidth: Int?, allowHevc: Boolean, mediaSourceId: String?, audioIndex: Int?, subtitleIndex: Int,
    ): PlaybackInfoResponse = io { api.mediaInfoApi.getPostedPlaybackInfo(
        itemId,
        PlaybackInfoDto(
            userId = userId, maxStreamingBitrate = maxBitrate, startTimeTicks = 0L, mediaSourceId = mediaSourceId,
            audioStreamIndex = audioIndex, subtitleStreamIndex = subtitleIndex,
            deviceProfile = AndroidDeviceProfile.download(maxBitrate, maxWidth, allowHevc),
            enableDirectPlay = false, enableDirectStream = false, enableTranscoding = true, autoOpenLiveStream = false,
        ),
    ).content }

    /** What the sign-in token is, for the download manager's requests. */
    val accessToken: String? get() = session?.accessToken

    fun directStreamUrl(itemId: UUID, mediaSourceId: String, playSessionId: String?, container: String?): String =
        directStreamUrlRaw(itemId, mediaSourceId, playSessionId, container).also { android.util.Log.i("Aster", "direct stream: ${it.replace(session?.accessToken.orEmpty(), "***")}") }

    private fun directStreamUrlRaw(itemId: UUID, mediaSourceId: String, playSessionId: String?, container: String?): String =
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

    /** The patient client for subtitle extraction: the server's first request for a track inside an MKV can take minutes. */
    private val subtitleHttp by lazy { http.newBuilder().readTimeout(10, java.util.concurrent.TimeUnit.MINUTES).callTimeout(10, java.util.concurrent.TimeUnit.MINUTES).build() }

    /**
     * The cues of a text subtitle track, converted by the server to its JSON form (what Jellyfin's web player reads).
     * The first request for a track inside an MKV makes the server extract it, which can take minutes; later ones come
     * from its cache. Called in the background, never in the way of playback.
     */
    suspend fun subtitleCues(itemId: UUID, mediaSourceId: String, streamIndex: Int): List<SubtitleCue> = withContext(Dispatchers.IO) {
        (client as? PlexApiClient)?.let { return@withContext it.subtitleCues(itemId.toString(), streamIndex) }
        val url = "${serverUrl.trimEnd('/')}/Videos/${wire(itemId)}/$mediaSourceId/Subtitles/$streamIndex/Stream.js"
        subtitleHttp.newCall(Request.Builder().url(url).header("Authorization", authorizationHeader()).build()).execute().use { response ->
            check(response.isSuccessful) { "The server answered ${response.code} for subtitle track $streamIndex" }
            SubtitleCue.parse(response.body.string())
        }
    }

    fun subtitleUrl(itemId: UUID, mediaSourceId: String, streamIndex: Int, format: String): String =
        api.subtitleApi.getSubtitleUrl(
            routeItemId = itemId, routeMediaSourceId = mediaSourceId, routeIndex = streamIndex, routeFormat = format,
        )

    suspend fun segments(itemId: UUID): List<MediaSegmentDto> = runCatching {
        // Emby marks them as chapters, which the SDK's chapter model drops, so the item is read raw.
        if (serverKind == ServerKind.Emby) {
            val item = org.json.JSONObject(serverRequest("GET", "/Users/${checkNotNull(session).userId}/Items/${wire(itemId)}?Fields=Chapters"))
            return@runCatching com.astermediaplayer.astermediaplayer.data.emby.EmbyMarkers.segments(item, itemId)
        }
        api.mediaSegmentApi.getItemSegments(
            itemId, listOf(MediaSegmentType.INTRO, MediaSegmentType.OUTRO, MediaSegmentType.RECAP, MediaSegmentType.PREVIEW, MediaSegmentType.COMMERCIAL),
        ).content.items
    }.getOrDefault(emptyList())

    suspend fun reportStart(info: PlaybackStartInfo) = io {
        runCatching { api.sessionApi.reportPlaybackStart(info) }
    }

    suspend fun reportProgress(info: PlaybackProgressInfo) = io {
        runCatching { api.sessionApi.reportPlaybackProgress(info) }
    }

    suspend fun reportStopped(info: PlaybackStopInfo) = io {
        runCatching { api.sessionApi.reportPlaybackStopped(info) }
    }
}

/**
 * Whether a title is anime. Jellyfin's Shoko match is the sign; Emby and Plex may have no Shoko, write the provider ids in
 * their own case ("anidb"), and often have only the genre.
 */
fun AsterRepository.isAnimeTitle(item: BaseItemDto): Boolean =
    item.isAnime() || (serverKind != ServerKind.Jellyfin &&
        (item.providerIds?.keys?.any { it.lowercase() in setOf("anidb", "anilist", "myanimelist") } == true ||
            item.genres?.any { it.equals("Anime", ignoreCase = true) } == true))

/** Matched by Shoko, which also records the AniDB id: how the app tells anime from other shows and films. */
fun BaseItemDto.isAnime(): Boolean = providerIds?.keys?.any { it == "AniDB" || it.startsWith("Shoko") } == true

data class ItemPage(val items: List<BaseItemDto>, val total: Int)
