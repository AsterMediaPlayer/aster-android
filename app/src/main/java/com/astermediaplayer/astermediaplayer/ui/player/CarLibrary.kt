package com.astermediaplayer.astermediaplayer.ui.player

import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.R
import com.astermediaplayer.astermediaplayer.data.ArtworkProvider
import com.astermediaplayer.astermediaplayer.data.Audiobook
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

/**
 * What Android Auto (and Android Automotive, and any other media browser) can browse and play: the server's music and
 * audiobooks, laid out as the Apple apps' CarPlay is. Two tabs:
 *  - Music: the albums played last as a row of covers, then Recent, Albums, Artists and Playlists. An album or playlist
 *    opens with Shuffle above its songs; an artist with Shuffle and Instant Mix above their albums.
 *  - Audiobooks: the books under way as a row of covers, then Continue Listening, Series, Recently Added, Authors and
 *    All Books. A book opens its page, Resume (or Play) above its chapters.
 * Playing something here starts it on the app's own music or audiobook player, exactly as the phone's screens do, so
 * the notification, the phone's Now Playing and Jellyfin's progress all carry on as usual. Now Playing's buttons are
 * [CarButtons].
 *
 * Ids: the tabs `music` and `books`; their menus `music/recent`, `music/albums`, `music/artists`, `music/playlists`,
 * `books/continue`, `books/series`, `books/added`, `books/authors`, `books/all`; `album:<id>`, `artist:<id>`,
 * `playlist:<id>`, `author:<name>`, `series:<name>`, `bookpage:<id>`. Playable: `track:<list>:<id>` (the list, `album-<id>`,
 * `playlist-<id>`, `recent` or `one`, is what plays on from the song), `shuffle:<list>` (`album-`, `playlist-` or
 * `artist-<id>`), `mix:<artist id>`, `book:<id>` (from where listening left off) and `chapter:<book id>:<chapter>`.
 */
@UnstableApi
class CarLibrary(
    private val app: AsterApp, private val scope: CoroutineScope, private val buttons: CarButtons, private val lastPlayed: LastPlayed,
) : MediaLibrarySession.Callback {
    private val repo get() = app.repository
    private val restoring = Mutex()

    // MARK: Connecting

    /** Offers Now Playing's own buttons to whoever connects (the car, the notification). */
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        val base = super.onConnect(session, controller)
        return runCatching {
            // Media3's defaults spelled out: the default result leaves its lists empty to be filled in later, and a
            // car given those as they are can neither browse (no library) nor play.
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            buttons.commands.commands.forEach { commands.add(it) }
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build())
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setMediaButtonPreferences(buttons.current())
                .build()
        }.getOrElse {
            android.util.Log.w("Aster", "[Car] Now Playing's buttons not offered to ${controller.packageName}", it)
            base
        }
    }

    override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> =
        Futures.immediateFuture(SessionResult(if (buttons.handle(customCommand.customAction)) SessionResult.RESULT_SUCCESS else SessionError.ERROR_NOT_SUPPORTED))

    // MARK: Browsing

    /**
     * Answered at once: the car asks for the root with the main thread waiting, so it can't wait for anything that
     * runs there. The saved sign-in is restored meanwhile, before the car asks for the lists.
     */
    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
        android.util.Log.i("Aster", "[Car] ${browser.packageName} connects")
        scope.launch { signedIn() }
        val extras = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            // Searching from the car's search button and by voice.
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
        }
        return Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Aster"), LibraryParams.Builder().setExtras(extras).build()))
    }

    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
        future {
            val known = (tabs() + musicMenu() + bookMenuAll()).firstOrNull { it.mediaId == mediaId }
            if (known != null) LibraryResult.ofItem(known, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

    override fun onGetChildren(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        if (!signedIn() || repo.locked.value) return@future LibraryResult.ofError(askToSignIn(session), params ?: LibraryParams.Builder().build())
        val all = runCatching { children(parentId) }.getOrElse {
            android.util.Log.w("Aster", "[Car] $parentId failed", it)
            listOf(message("Couldn't reach the server", it.message))
        }
        // Android Auto asks for everything at once; other browsers page.
        val pageOf = if (pageSize >= all.size) all else all.drop(page * pageSize).take(pageSize)
        LibraryResult.ofItemList(pageOf, params)
    }

    /**
     * Not signed in (or the profile is locked): the error the car shows in place of the lists, with a button that opens
     * Aster on the phone, as cars do for an app that needs signing in to (Media3 hands it to the car, and clears it
     * when a list next loads). Once signed in, the car is told to load the lists again.
     */
    private fun askToSignIn(session: MediaLibrarySession): SessionError {
        val locked = repo.session != null
        val open = android.app.PendingIntent.getActivity(
            app, 0, android.content.Intent(app, com.astermediaplayer.astermediaplayer.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val extras = Bundle().apply {
            putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, "Open Aster")
            putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, open)
        }
        val text = if (locked) "Unlock Aster on your phone to play your music and audiobooks." else "Sign in to Aster on your phone to play your music and audiobooks."
        val error = SessionError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, text, extras)
        if (waiting) return error
        waiting = true
        scope.launch {
            while (repo.session == null || repo.locked.value) kotlinx.coroutines.delay(2_000)
            waiting = false
            session.notifyChildrenChanged(ROOT, 2, null)
            (tabs() + musicMenu() + bookMenuAll()).forEach { session.notifyChildrenChanged(it.mediaId, Int.MAX_VALUE, null) }
        }
        return error
    }
    private var waiting = false

    private suspend fun children(parentId: String): List<MediaItem> {
        val (kind, rest) = parentId.substringBefore(':') to parentId.substringAfter(':', "")
        return when (kind) {
            ROOT -> tabs()

            // Music
            MUSIC -> recentAlbumsRow() + musicMenu()
            "$MUSIC/recent" -> {
                val played = songs(recentSongs(20), "recent").map { it.inGroup("Recently Played") }
                val added = repo.latest(null, 20, listOf(BaseItemKind.MUSIC_ALBUM)).map { album(it).inGroup("Recently Added") }
                played + added
            }
            "$MUSIC/albums" -> repo.musicPage(BaseItemKind.MUSIC_ALBUM, 0, LIMIT).items.map(::album)
            "$MUSIC/artists" -> repo.albumArtists(0, LIMIT).items.map { artist(it).inGroup(letter(it.name)) }
            "$MUSIC/playlists" -> repo.musicPage(BaseItemKind.PLAYLIST, 0, LIMIT).items.map(::playlist)
            "album" -> withShuffle("album-$rest", songs(repo.albumTracks(UUID.fromString(rest)), "album-$rest"))
            "playlist" -> withShuffle("playlist-$rest", songs(repo.playlistItems(UUID.fromString(rest)), "playlist-$rest"))
            "artist" -> listOf(
                action("shuffle:artist-$rest", "Shuffle", R.drawable.ic_car_shuffle),
                action("mix:$rest", "Instant Mix", R.drawable.ic_car_mix),
            ) + repo.artistAlbums(UUID.fromString(rest)).map { album(it).inGroup("Albums") }

            // Audiobooks
            BOOKS -> continueRow() + bookMenu()
            "$BOOKS/continue" -> books().continueListening.map(::book)
            "$BOOKS/added" -> books().recentlyAdded.map(::bookPage)
            "$BOOKS/all" -> books().books.map(::bookPage)
            "$BOOKS/authors" -> books().authors.map { shelf("author:${it.name}", it.name, it.books) }
            "$BOOKS/series" -> books().series.map { shelf("series:${it.name}", it.name, it.books) }
            "author" -> books().authors.firstOrNull { it.name == rest }?.books.orEmpty().map(::bookPage)
            "series" -> books().series.firstOrNull { it.name == rest }?.books.orEmpty().map(::bookPage)
            "bookpage" -> books().book(UUID.fromString(rest))?.let(::bookPageItems).orEmpty()
            else -> emptyList()
        }
    }

    private fun tabs() = listOf(
        folder(MUSIC, "Music", icon = R.drawable.ic_car_music),
        folder(BOOKS, "Audiobooks", icon = R.drawable.ic_car_headphones),
    )

    private fun musicMenu() = listOf(
        folder("$MUSIC/recent", "Recent", icon = R.drawable.ic_car_recent),
        folder("$MUSIC/albums", "Albums", icon = R.drawable.ic_car_albums),
        folder("$MUSIC/artists", "Artists", icon = R.drawable.ic_car_artists, children = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM),
        folder("$MUSIC/playlists", "Playlists", icon = R.drawable.ic_car_playlists),
    )

    /** Audiobooks' menu, leaving out shelves the library has nothing on, as CarPlay does. */
    private suspend fun bookMenu(): List<MediaItem> {
        val library = books()
        return bookMenuAll().filter {
            when (it.mediaId) {
                "$BOOKS/continue" -> library.continueListening.isNotEmpty()
                "$BOOKS/series" -> library.series.isNotEmpty()
                "$BOOKS/authors" -> library.authors.isNotEmpty()
                else -> true
            }
        }
    }

    private fun bookMenuAll() = listOf(
        folder("$BOOKS/continue", "Continue Listening", icon = R.drawable.ic_car_continue),
        folder("$BOOKS/series", "Series", icon = R.drawable.ic_car_series),
        folder("$BOOKS/added", "Recently Added", icon = R.drawable.ic_car_book),
        folder("$BOOKS/authors", "Authors", icon = R.drawable.ic_car_authors),
        folder("$BOOKS/all", "All Books", icon = R.drawable.ic_car_all_books),
    )

    /** The albums of the songs played last, as a row of covers, as CarPlay's Music tab starts. */
    private suspend fun recentAlbumsRow(): List<MediaItem> {
        val songs = runCatching { recentSongs(40) }.getOrDefault(emptyList())
        return songs.filter { it.albumId != null }.distinctBy { it.albumId }.take(8).map { song ->
            val albumId = song.albumId!!
            val art = song.albumPrimaryImageTag?.let { repo.imageUrlFor(albumId, it, ART) } ?: repo.imageUrl(song, ImageType.PRIMARY, ART)
            MediaItem.Builder().setMediaId("album:$albumId").setMediaMetadata(
                MediaMetadata.Builder().setTitle(song.album ?: song.name).setSubtitle(song.albumArtist ?: song.artists?.firstOrNull())
                    .setArtworkUri(art(art)).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                    .setExtras(Bundle().apply {
                        putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, "Recently Played")
                        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                    })
                    .build(),
            ).build()
        }
    }

    /** The books under way as a row of covers, each resuming where it left off. */
    private suspend fun continueRow(): List<MediaItem> = books().continueListening.take(8).map { book ->
        book(book).let { item ->
            val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
                putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, "Continue Listening")
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            }
            item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
        }
    }

    /** A book's page: Resume (or Play) with its author and length, then its chapters to start from. */
    private fun bookPageItems(book: Audiobook): List<MediaItem> {
        val started = book.progress > 0 && !book.isFinished
        val start = action("book:${book.id}", if (started) "Resume" else "Play", R.drawable.ic_car_play,
            subtitle = listOfNotNull(book.author, book.lengthText).joinToString(" · ").ifEmpty { null })
        val chapters = if (book.chapters.size > 1) book.chapters.take(LIMIT - 1).map { chapter ->
            val at = book.offsetOfPart(chapter.part) + chapter.startSeconds
            MediaItem.Builder().setMediaId("chapter:${book.id}:${chapter.id}").setMediaMetadata(
                MediaMetadata.Builder().setTitle(chapter.title).setSubtitle(clock(at)).setArtworkUri(resource(R.drawable.ic_car_blank))
                    .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                    .setExtras(Bundle().apply { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, "Chapters") })
                    .build(),
            ).build()
        } else emptyList()
        return listOf(start) + chapters
    }

    /** Shuffle, then the songs. */
    private fun withShuffle(list: String, songs: List<MediaItem>) =
        if (songs.isEmpty()) songs else listOf(action("shuffle:$list", "Shuffle", R.drawable.ic_car_shuffle)) + songs

    private suspend fun recentSongs(limit: Int) = repo.musicPage(BaseItemKind.AUDIO, 0, limit, listOf(ItemSortBy.DATE_PLAYED), SortOrder.DESCENDING, played = true).items

    /** The audiobook library, fetched the first time the car asks. */
    private suspend fun books() = app.audiobookLibrary.let { library ->
        if (!library.state.value.loaded) library.load()
        library.state.value
    }

    // MARK: Searching ("Hey Google, play … on Aster")

    override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
        scope.launch { session.notifySearchResultChanged(browser, query, search(query).size, params) }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        val all = search(query)
        LibraryResult.ofItemList(if (pageSize >= all.size) all else all.drop(page * pageSize).take(pageSize), params)
    }

    private suspend fun search(query: String): List<MediaItem> {
        if (!signedIn() || query.isBlank()) return emptyList()
        val found = runCatching { repo.search(query, 40) }.getOrDefault(emptyList())
        val music = found.mapNotNull {
            when (it.type) {
                BaseItemKind.AUDIO -> song(it, "one")
                BaseItemKind.MUSIC_ALBUM -> album(it)
                BaseItemKind.MUSIC_ARTIST -> artist(it)
                BaseItemKind.PLAYLIST -> playlist(it)
                else -> null
            }
        }
        val words = query.lowercase()
        val books = runCatching { books().books }.getOrDefault(emptyList())
            .filter { it.title.lowercase().contains(words) || it.author?.lowercase()?.contains(words) == true || it.series?.lowercase()?.contains(words) == true }
            .take(20).map(::book)
        return books + music
    }

    // MARK: Playing

    /**
     * Something picked in the car. It starts on the app's own player, which [PlaybackService] then puts behind this
     * session, so nothing is handed back for the session to set on its player: the failed result is how a session
     * declines to (Media3 then leaves the player alone).
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val first = mediaItems.firstOrNull() ?: return declined()
        scope.launch {
            runCatching {
                if (!signedIn()) return@runCatching
                val query = first.requestMetadata.searchQuery
                if (first.mediaId.isNotEmpty()) play(first.mediaId) else if (query != null) playSearch(query)
            }.onFailure { android.util.Log.w("Aster", "[Car] Couldn't play ${first.mediaId}", it) }
        }
        return declined()
    }

    override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
        Futures.immediateFailedFuture(UnsupportedOperationException("Pick something to play instead"))

    /**
     * Play pressed (or the car connecting, which asks to get ready) with nothing loaded: what was playing last comes
     * back where it left off, as Google asks of car media apps. Pressing play plays it; getting ready only loads it,
     * paused, so the car shows it without starting anything by itself. Like a pick in the car, it starts on the app's
     * own player, and nothing is handed back for the session to set.
     */
    override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        resume(play = isForPlayback)
        return declined()
    }

    /**
     * What was playing last, back where it left off: played, or with [play] false only loaded, paused. Also for a car
     * connecting, whose request to get ready reaches the player rather than [onPlaybackResumption] (see [CarArtPlayer]).
     */
    fun resume(play: Boolean) {
        val isForPlayback = play
        scope.launch {
            runCatching {
                if (!signedIn()) return@runCatching
                // Something may have started meanwhile (a pick in the car, the phone).
                if (app.music.state.value.current != null || app.audiobooks.state.value.book != null) return@runCatching
                when (val last = lastPlayed.load()) {
                    is LastPlayed.Songs -> {
                        val songs = repo.items(last.ids)
                        val playing = last.ids[last.index]
                        val at = songs.indexOfFirst { it.id == playing }
                        if (songs.isNotEmpty()) app.music.play(songs, at.coerceAtLeast(0), positionMs = if (at >= 0) last.positionMs else 0, playWhenReady = isForPlayback)
                    }
                    is LastPlayed.Book -> books().book(last.id)?.let { book ->
                        app.audiobooks.play(app.audiobookLibrary.refresh(book), playWhenReady = isForPlayback)
                    }
                    null -> Unit
                }
            }.onFailure { android.util.Log.w("Aster", "[Car] Couldn't pick up where listening left off", it) }
        }
    }

    private fun declined(): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        Futures.immediateFailedFuture(UnsupportedOperationException("Started on the app's own player"))

    private suspend fun play(mediaId: String) {
        val kind = mediaId.substringBefore(':')
        val rest = mediaId.substringAfter(':', "")
        when (kind) {
            "track" -> {
                val id = UUID.fromString(rest.substringAfterLast(':'))
                val songs = list(rest.substringBeforeLast(':')) ?: listOf(repo.item(id))
                val at = songs.indexOfFirst { it.id == id }
                if (at >= 0) app.music.play(songs, at) else app.music.play(listOf(repo.item(id)))
            }
            "shuffle" -> list(rest)?.takeIf { it.isNotEmpty() }?.let { app.music.play(it, shuffled = true) }
            "mix" -> repo.instantMix(UUID.fromString(rest)).takeIf { it.isNotEmpty() }?.let { app.music.play(it) }
            "album" -> app.music.play(repo.albumTracks(UUID.fromString(rest)))
            "playlist" -> app.music.play(repo.playlistItems(UUID.fromString(rest)))
            "artist" -> app.music.play(repo.artistTracks(UUID.fromString(rest)), shuffled = true)
            "book" -> {
                val book = books().book(UUID.fromString(rest)) ?: return
                // Where listening left off, fresh from the server: the phone may have moved on since the list loaded.
                app.audiobooks.play(app.audiobookLibrary.refresh(book))
            }
            "chapter" -> {
                val book = books().book(UUID.fromString(rest.substringBefore(':'))) ?: return
                val chapter = book.chapters.firstOrNull { it.id == rest.substringAfter(':').toIntOrNull() } ?: return
                app.audiobooks.play(book, chapter)
            }
        }
    }

    /** The songs of a list a song was picked from (`album-<id>`, `playlist-<id>`, `artist-<id>`, `recent`). */
    private suspend fun list(list: String): List<BaseItemDto>? = when {
        list.startsWith("album-") -> repo.albumTracks(UUID.fromString(list.removePrefix("album-")))
        list.startsWith("playlist-") -> repo.playlistItems(UUID.fromString(list.removePrefix("playlist-")))
        list.startsWith("artist-") -> repo.artistTracks(UUID.fromString(list.removePrefix("artist-")))
        list == "recent" -> recentSongs(20)
        else -> null
    }

    /** "Play <query>": a book whose title or author matches, else the server's best match, else a mix of it. */
    private suspend fun playSearch(query: String) {
        val all = search(query)
        val pick = all.firstOrNull { it.mediaId.startsWith("book:") }
            ?: all.firstOrNull { it.mediaId.startsWith("album:") || it.mediaId.startsWith("playlist:") || it.mediaId.startsWith("artist:") || it.mediaId.startsWith("track:") }
        if (pick != null) play(pick.mediaId)
        // "Play some music": no words to go on, so everything, shuffled.
        else if (query.isBlank()) app.music.play(repo.musicPage(BaseItemKind.AUDIO, 0, 200, listOf(ItemSortBy.RANDOM)).items, shuffled = true)
    }

    // MARK: Items

    /**
     * A row that opens a list. [children] is how the list it opens is laid out: rows, or a grid (artists).
     */
    private fun folder(id: String, title: String, subtitle: String? = null, art: Uri? = null, @DrawableRes icon: Int? = null, children: Int = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setArtworkUri(art ?: icon?.let(::resource))
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(Bundle().apply {
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, children)
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                })
                .build(),
        ).build()

    /** A row that plays at once, with an icon: Shuffle, Instant Mix, Resume. */
    private fun action(id: String, title: String, @DrawableRes icon: Int, subtitle: String? = null): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setArtworkUri(resource(icon))
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MIXED)
                .build(),
        ).build()

    /** A line that can't be opened, for telling the driver what's wrong. */
    private fun message(title: String, subtitle: String?): MediaItem =
        MediaItem.Builder().setMediaId("message:$title").setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setIsBrowsable(false).setIsPlayable(false).build(),
        ).build()

    private fun album(item: BaseItemDto): MediaItem = MediaItem.Builder().setMediaId("album:${item.id}").setMediaMetadata(
        MediaMetadata.Builder().setTitle(item.name).setSubtitle(item.albumArtist ?: item.artists?.firstOrNull()).setArtist(item.albumArtist)
            .setArtworkUri(art(repo.imageUrl(item, ImageType.PRIMARY, ART)))
            .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
            .setExtras(Bundle().apply { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM) })
            .build(),
    ).build()

    private fun artist(item: BaseItemDto): MediaItem = MediaItem.Builder().setMediaId("artist:${item.id}").setMediaMetadata(
        MediaMetadata.Builder().setTitle(item.name).setArtworkUri(art(repo.imageUrl(item, ImageType.PRIMARY, ART)))
            .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
            .setExtras(Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            })
            .build(),
    ).build()

    private fun playlist(item: BaseItemDto): MediaItem = MediaItem.Builder().setMediaId("playlist:${item.id}").setMediaMetadata(
        MediaMetadata.Builder().setTitle(item.name).setSubtitle(item.childCount?.let { "$it songs" })
            .setArtworkUri(art(repo.imageUrl(item, ImageType.PRIMARY, ART)) ?: resource(R.drawable.ic_car_playlists))
            .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
            .setExtras(Bundle().apply { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM) })
            .build(),
    ).build()

    private fun songs(items: List<BaseItemDto>, list: String) = items.filter { it.type == BaseItemKind.AUDIO }.map { song(it, list) }

    private fun song(item: BaseItemDto, list: String): MediaItem = MediaItem.Builder().setMediaId("track:$list:${item.id}").setMediaMetadata(
        MediaMetadata.Builder().setTitle(item.name).setArtist(item.artists?.joinToString(", ") ?: item.albumArtist)
            .setSubtitle(item.artists?.firstOrNull() ?: item.albumArtist).setAlbumTitle(item.album)
            .setArtworkUri(art(repo.imageUrl(item, ImageType.PRIMARY, ART) ?: item.albumId?.let { id -> item.albumPrimaryImageTag?.let { repo.imageUrlFor(id, it, ART) } }))
            .setDurationMs(item.runTimeTicks?.let { it / 10_000 })
            .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build(),
    ).build()

    /** A book that plays from where listening left off, with the car's progress bar under its cover. */
    private fun book(book: Audiobook): MediaItem = MediaItem.Builder().setMediaId("book:${book.id}").setMediaMetadata(
        MediaMetadata.Builder().setTitle(book.title).setArtist(book.author).setSubtitle(book.author)
            .setArtworkUri(bookArt(book))
            .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setExtras(progressExtras(book))
            .build(),
    ).build()

    /** A book that opens its page (Resume and chapters), as CarPlay's lists of books do. */
    private fun bookPage(book: Audiobook): MediaItem = MediaItem.Builder().setMediaId("bookpage:${book.id}").setMediaMetadata(
        MediaMetadata.Builder().setTitle(book.title).setArtist(book.author).setSubtitle(book.author)
            .setArtworkUri(bookArt(book))
            .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setExtras(progressExtras(book).apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            })
            .build(),
    ).build()

    /** An author or series: its name, how many books, and the first one's cover. */
    private fun shelf(id: String, name: String, books: List<Audiobook>): MediaItem =
        folder(id, name, if (books.size == 1) "1 book" else "${books.size} books", art = books.firstOrNull()?.let(::bookArt))

    /**
     * How far into a book, for the car's bar under its cover. Nothing for a book not started: a car marks those with a
     * "new" dot, which CarPlay doesn't.
     */
    private fun progressExtras(book: Audiobook) = Bundle().apply {
        if (book.isFinished) putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED)
        else if (book.progress > 0) {
            putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)
            putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, book.progress)
        }
    }

    /** Puts an item under a heading in its list ("Recently Played", "Chapters", an artist's first letter). */
    private fun MediaItem.inGroup(title: String): MediaItem {
        val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, title) }
        return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }

    /** The heading an artist goes under: their first letter, or # for a number or symbol. */
    private fun letter(name: String?): String = name?.trim()?.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() }?.toString() ?: "#"

    /** 1:02:03, or 2:03 under an hour. */
    private fun clock(seconds: Double): String {
        val s = seconds.toLong()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun bookArt(book: Audiobook): Uri? = art(repo.bookCoverUrl(book, ART))

    /** Cars read browse pictures only from content addresses, so the server's are fetched through [ArtworkProvider]. */
    private fun art(url: String?): Uri? = url?.let { ArtworkProvider.uriFor(app, it) }

    /**
     * One of the app's own icons, which cars read from its resources. By name (`…/drawable/ic_car_music`): a car's
     * media app looks the name up, and reads a bare number as no icon at all.
     */
    private fun resource(@DrawableRes id: Int): Uri = Uri.Builder().scheme(android.content.ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(app.packageName).appendPath(app.resources.getResourceTypeName(id)).appendPath(app.resources.getResourceEntryName(id)).build()

    // MARK: Signing in

    /** The saved sign-in, restored when the car starts the app with no screen open. */
    private suspend fun signedIn(): Boolean {
        if (repo.session != null) return true
        return restoring.withLock { repo.session != null || runCatching { repo.restore() }.getOrDefault(false) }
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        scope.launch { runCatching { block() }.onSuccess { result.set(it) }.onFailure { result.setException(it) } }
        return result
    }

    companion object {
        const val ROOT = "root"
        const val MUSIC = "music"
        const val BOOKS = "books"
        private const val LIMIT = 500
        private const val ART = 400
    }
}
