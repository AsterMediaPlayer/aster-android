package com.astermediaplayer.astermediaplayer

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.astermediaplayer.astermediaplayer.ui.theme.isTelevision
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.SessionStore
import com.astermediaplayer.astermediaplayer.data.SettingsStore
import com.astermediaplayer.astermediaplayer.data.channels.ChannelsModel
import com.astermediaplayer.astermediaplayer.data.downloads.DownloadStore
import com.astermediaplayer.astermediaplayer.ui.audiobooks.AudiobookLibrary
import com.astermediaplayer.astermediaplayer.ui.player.AudiobookPlayer
import com.astermediaplayer.astermediaplayer.ui.player.MusicPlayer
import com.astermediaplayer.astermediaplayer.ui.player.ThemePlayer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AsterApp : Application(), SingletonImageLoader.Factory {
    val repository: AsterRepository by lazy { AsterRepository(this, SessionStore(this)) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val settingsSync: com.astermediaplayer.astermediaplayer.data.SettingsSync by lazy { com.astermediaplayer.astermediaplayer.data.SettingsSync(repository, settings) }
    val themePlayer: ThemePlayer by lazy { ThemePlayer(this, repository) }
    val themeDeck: com.astermediaplayer.astermediaplayer.ui.player.ThemeDeck by lazy { com.astermediaplayer.astermediaplayer.ui.player.ThemeDeck(repository, themePlayer) }
    val audiobookLibrary: AudiobookLibrary by lazy { AudiobookLibrary(repository, downloads) }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val downloads: DownloadStore by lazy { DownloadStore(this, repository, settings) }
    val channels: ChannelsModel by lazy { ChannelsModel(repository) }
    /** The Live TV channel playing, full screen or in the Live TV header's mini player. */
    val liveMini: com.astermediaplayer.astermediaplayer.ui.livetv.LiveMini by lazy { com.astermediaplayer.astermediaplayer.ui.livetv.LiveMini(this) }
    /** The video playing, for [com.astermediaplayer.astermediaplayer.ui.player.PlaybackService] to publish as a media session; null with none open. */
    var videoPlayer: androidx.media3.common.Player? = null
    /** A title whose page should start playing once it opens: a banner's Play, so closing the player leaves the viewer on that page. */
    var playOnOpen: java.util.UUID? = null
    val audiobooks: AudiobookPlayer by lazy {
        AudiobookPlayer(this, repository).also { it.onStart = { themePlayer.hide(evenIfShowing = true); music.stop() } }
    }
    val music: MusicPlayer by lazy {
        MusicPlayer(this, repository).also { it.onStart = { themePlayer.hide(evenIfShowing = true); audiobooks.stop() } }
    }

    /** What's playing, as the sleep timer tells songs and chapters apart: the book's chapter, else the song's place in the queue. */
    fun nowPlayingKey(): Any? {
        val book = audiobooks.state.value
        return if (book.book != null) book.book.id to book.chapter?.id else music.state.value.let { it.index to it.current?.id }
    }

    /** Now Playing's sleep timer, for the song or the book playing: it pauses whichever is on. */
    val sleepTimer: com.astermediaplayer.astermediaplayer.ui.player.SleepTimer by lazy {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        com.astermediaplayer.astermediaplayer.ui.player.SleepTimer(scope).also { timer ->
            timer.onFire = { music.pauseForOther(); audiobooks.pauseForOther() }
            music.onMovedByHand = timer::movedByHand
            audiobooks.onMovedByHand = timer::movedByHand
            scope.launch {
                kotlinx.coroutines.flow.combine(music.state, audiobooks.state) { m, b -> nowPlayingKey() to (b.book != null || m.current != null) }.distinctUntilChanged().collect { (playing, loaded) -> timer.playing(playing, loaded) }
            }
        }
    }

    /** What was learned about one server, stopped or cleared, for the server switched to (or none). */
    fun resetForServerChange() {
        music.stop()
        audiobooks.stop()
        themePlayer.hide(evenIfShowing = true)
        channels.reset()
        liveMini.stop()
        audiobookLibrary.reset()
    }

    override fun onCreate() {
        super.onCreate()
        com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog.install(this)
        // The repository asks the setting before it contacts AnimeThemes; switching it back on gets the library's songs fetched.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            settings.settings.map { it.songLookups }.distinctUntilChanged().collect { on ->
                repository.songLookups = on
                if (on) repository.warmAnimeThemesInBackground()
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        // A fade on every image is a lot of frames for a TV's CPU, which has a screen of them arriving at once.
        .crossfade(!isTelevision(context))
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(250L * 1024 * 1024).build() }
        .build()
}
