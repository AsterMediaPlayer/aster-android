package com.astermediaplayer.astermediaplayer

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.SessionStore
import com.astermediaplayer.astermediaplayer.data.SettingsStore
import com.astermediaplayer.astermediaplayer.ui.audiobooks.AudiobookLibrary
import com.astermediaplayer.astermediaplayer.ui.player.AudiobookPlayer
import com.astermediaplayer.astermediaplayer.ui.player.MusicPlayer
import com.astermediaplayer.astermediaplayer.ui.player.ThemePlayer

class AsterApp : Application(), SingletonImageLoader.Factory {
    val repository: AsterRepository by lazy { AsterRepository(this, SessionStore(this)) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val themePlayer: ThemePlayer by lazy { ThemePlayer(this, repository) }
    val audiobookLibrary: AudiobookLibrary by lazy { AudiobookLibrary(repository) }
    val audiobooks: AudiobookPlayer by lazy {
        AudiobookPlayer(this, repository).also { it.onStart = { themePlayer.hide(evenIfShowing = true); music.stop() } }
    }
    val music: MusicPlayer by lazy {
        MusicPlayer(this, repository).also { it.onStart = { themePlayer.hide(evenIfShowing = true); audiobooks.stop() } }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .crossfade(true)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(250L * 1024 * 1024).build() }
        .build()
}
