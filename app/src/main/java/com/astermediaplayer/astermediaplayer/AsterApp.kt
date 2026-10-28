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

    /** What was learned about one server, stopped or cleared, for the server switched to (or none). */
    fun resetForServerChange() {
        music.stop()
        audiobooks.stop()
        themePlayer.hide(evenIfShowing = true)
        channels.reset()
        audiobookLibrary.reset()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        // A fade on every image is a lot of frames for a TV's CPU, which has a screen of them arriving at once.
        .crossfade(!isTelevision(context))
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(250L * 1024 * 1024).build() }
        .build()
}
