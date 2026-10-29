package com.astermediaplayer.astermediaplayer.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.OpEdTheme
import com.astermediaplayer.astermediaplayer.data.OpeningEnding
import com.astermediaplayer.astermediaplayer.data.ThemeClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/**
 * Theme music and theme videos on title pages: a quiet looping song and a muted looping backdrop video, faded in and
 * out as the viewer moves between pages. Episodes and seasons share their series' theme without a restart. The Kotlin
 * counterpart of the Apple apps' `ThemePlayer`.
 */
@OptIn(UnstableApi::class)
class ThemePlayer(private val context: Context, private val repo: AsterRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The item (or series) whose theme is loaded, so a page can tell whether the current video belongs to it. */
    private val _key = MutableStateFlow<String?>(null)
    val key: StateFlow<String?> = _key.asStateFlow()

    /** The looping theme video, once one has proven playable. */
    private val _video = MutableStateFlow<ExoPlayer?>(null)
    val video: StateFlow<ExoPlayer?> = _video.asStateFlow()

    /** Whether the video has drawn its first frame, so the still backdrop can fade away. */
    private val _videoReady = MutableStateFlow(false)
    val videoReady: StateFlow<Boolean> = _videoReady.asStateFlow()

    private var music: ExoPlayer? = null
    private var loadJob: Job? = null
    private var hideJob: Job? = null
    private var loopJob: Job? = null
    private var suspended = false

    /** Which opening is standing in as the theme video. Null while the title's own theme video plays (or none does). */
    private var fallbackId: String? = null
    /** The music playing is an opening's song standing in, not the title's theme song. */
    private var musicIsOpening = false

    /** An anime opening (video or song) is standing in as the theme. */
    val isShowingOpening: Boolean get() = fallbackId != null

    private val pagesShowing = mutableMapOf<String, Int>()

    /** Starts the theme for a title. Pages pass their own toggles. */
    fun show(item: BaseItemDto, wantsMusic: Boolean, wantsVideo: Boolean) {
        if (!wantsMusic && !wantsVideo) {
            hide(evenIfShowing = true)
            return
        }
        val themeKey = themeKey(item)
        hideJob?.cancel()
        hideJob = null
        if (themeKey == _key.value) {
            resume()
            return
        }
        release()
        _key.value = themeKey
        loadJob = scope.launch {
            val song = if (wantsMusic) repo.themeSongs(item.id).firstOrNull() else null
            val themeVideo = if (wantsVideo) repo.themeVideos(item.id).firstOrNull() else null
            if (_key.value != themeKey) return@launch
            song?.let { startMusic(repo.audioStreamUrl(it.id)) }
            themeVideo?.let { startVideo(repo.videoStreamUrl(it.id), themeKey, muted = true) }
        }
    }

    /**
     * For a series with no theme video of its own: loops an opening behind the page instead. Waits for the server's
     * theme video first, which always wins. With [sound] the opening plays at theme-music volume and replaces any
     * theme song. [onlyIfNone] leaves an opening that's already standing in alone (nothing new for this season).
     */
    fun showOpening(entry: OpeningEnding, item: BaseItemDto, sound: Boolean, onlyIfNone: Boolean = false) {
        val themeKey = themeKey(item)
        scope.launch {
            loadJob?.join()
            if (_key.value != themeKey || (onlyIfNone && isShowingOpening)) return@launch
            when (entry) {
                is OpeningEnding.Theme -> showPluginOpening(entry.theme, themeKey, sound)
                is OpeningEnding.Chapter -> showChapterOpening(entry.clip, themeKey, sound)
            }
        }
    }

    private suspend fun showPluginOpening(theme: OpEdTheme, themeKey: String, sound: Boolean) {
        if (!canShowFallback(theme.id)) return
        val video = theme.videoMp4Url
        val audio = theme.audioM4aUrl
        when {
            video != null -> startFallbackVideo(theme.id, repo.opEdMediaUrl(video), themeKey, sound, loop = null)
            sound && audio != null -> {
                // Only the song plays, replacing any theme song and any opening video standing in.
                swapOutVideo()
                stopMusic()
                fallbackId = theme.id
                startMusic(repo.opEdMediaUrl(audio))
                musicIsOpening = true
            }
        }
    }

    private suspend fun showChapterOpening(clip: ThemeClip, themeKey: String, sound: Boolean) {
        val id = "${clip.episode.id}-${clip.startMs}"
        if (!canShowFallback(id)) return
        val stream = clipStream(clip) ?: return
        if (_key.value != themeKey || !canShowFallback(id)) return
        startFallbackVideo(id, stream.url, themeKey, sound, loop = stream)
    }

    /**
     * An episode's stream for a chapter clip, and the stretch of it to play, in the stream's own time. A transcode's times
     * count from wherever the server starts it ([fromStreamStart]): 0 on Jellyfin, the clip's own place in the episode on Emby.
     */
    class ClipStream(val url: String, val start: Long, val end: Long, val fromStreamStart: Boolean = false) {
        /** Where the clip runs, once the player's first position says where the stream begins. */
        fun range(firstPosition: Long): LongRange = if (fromStreamStart) firstPosition..(firstPosition + end - start) else start..end
    }

    /**
     * Episodes are mostly MKV, so the server's stream (usually a cheap remux) is used unless the file plays as is.
     * A direct stream plays between the clip's own times; a transcode starts at the clip.
     */
    suspend fun clipStream(clip: ThemeClip): ClipStream? {
        val info = runCatching {
            repo.playbackInfo(clip.episode.id, clip.startMs * 10_000L, CLIP_BITRATE, subtitleStreamIndex = -1)
        }.getOrNull() ?: return null
        val source = info.mediaSources.firstOrNull() ?: return null
        return if (source.supportsDirectPlay) {
            ClipStream(repo.directStreamUrl(clip.episode.id, source.id!!, info.playSessionId, source.container), clip.startMs, clip.endMs)
        } else {
            val path = source.transcodingUrl ?: return null
            ClipStream(repo.absoluteUrl(path), 0L, clip.endMs - clip.startMs, fromStreamStart = true)
        }
    }

    /** Nothing standing in yet, or another opening that may be swapped for this one; never the title's own theme video. */
    private fun canShowFallback(id: String): Boolean {
        if (fallbackId == id) return false
        return _video.value == null || fallbackId != null
    }

    private suspend fun startFallbackVideo(id: String, url: String, themeKey: String, sound: Boolean, loop: ClipStream?) {
        swapOutVideo()
        fallbackId = id
        // The opening is the song now (two at once would clash), and a previous opening's song gives way to the new one.
        if (sound || musicIsOpening) {
            stopMusic()
            musicIsOpening = false
        }
        startVideo(url, themeKey, muted = !sound, loop = loop)
    }

    private fun swapOutVideo() {
        loopJob?.cancel()
        _video.value?.release()
        _video.value = null
        _videoReady.value = false
    }

    /** A title page for [item] is on screen. */
    fun pageAppeared(item: BaseItemDto) {
        val themeKey = themeKey(item)
        pagesShowing[themeKey] = (pagesShowing[themeKey] ?: 0) + 1
        hideJob?.cancel()
        hideJob = null
    }

    /** A title page for [item] went away; the theme fades once none is left. */
    fun pageDisappeared(item: BaseItemDto) {
        val themeKey = themeKey(item)
        val left = (pagesShowing[themeKey] ?: 1) - 1
        if (left > 0) pagesShowing[themeKey] = left else pagesShowing.remove(themeKey)
        if (left <= 0) hide()
    }

    /**
     * Fades the theme out. Delayed slightly so going from a series to one of its episodes keeps the music going instead
     * of restarting it. With [evenIfShowing] (the toggles went off) it goes regardless of pages.
     */
    fun hide(evenIfShowing: Boolean = false) {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(400)
            // A page for this theme came (back) in the meantime.
            if (!evenIfShowing && _key.value?.let { pagesShowing.containsKey(it) } == true) return@launch
            release()
        }
    }

    /** Quiets the theme while the app is in the background or a video is up. */
    fun suspend() {
        suspended = true
        music?.pause()
        _video.value?.pause()
    }

    fun resume() {
        suspended = false
        music?.play()
        _video.value?.play()
    }

    private fun release() {
        loadJob?.cancel()
        loadJob = null
        stopMusic()
        swapOutVideo()
        fallbackId = null
        musicIsOpening = false
        _key.value = null
    }

    private fun themeKey(item: BaseItemDto): String = (item.seriesId ?: item.id).toString()

    // MARK: Players

    /** A player that signs its requests like the app's others. */
    fun newPlayer(): ExoPlayer = ExoPlayer.Builder(context, PlayerViewModel.renderersFactory(context))
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("X-Emby-Token" to repo.session!!.accessToken)),
            ),
        )
        // A few seconds ahead is enough for a song or a clip. ExoPlayer's own default holds up to about 130 MB of video in the
        // app's memory, so the opening behind a page and one played from the Music row ran a phone out of memory.
        .setLoadControl(
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(5_000, 15_000, 1_000, 2_000)
                .setTargetBufferBytes(16 * 1024 * 1024)
                .build(),
        )
        .build()

    private fun startMusic(url: String) {
        val player = newPlayer().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            volume = VOLUME
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = !suspended
        }
        music = player
    }

    private fun stopMusic() {
        music?.release()
        music = null
    }

    /** With [loop], plays that clip of the media over and over; otherwise the whole thing. Video that can't play is dropped. */
    private fun startVideo(url: String, themeKey: String, muted: Boolean, loop: ClipStream? = null) {
        if (_key.value != themeKey) return
        val player = newPlayer().apply {
            volume = if (muted) 0f else VOLUME
            repeatMode = if (loop == null) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            setMediaItem(MediaItem.fromUri(url), loop?.start ?: 0L)
            prepare()
            playWhenReady = !suspended
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (_video.value === this@apply) _videoReady.value = true
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Only videos the device can decode are used; anything else leaves the still backdrop in place.
                    android.util.Log.w("Aster", "Theme video failed", error)
                    if (_video.value === this@apply) swapOutVideo()
                }
            })
        }
        _video.value = player
        loopJob?.cancel()
        if (loop != null) {
            loopJob = scope.launch {
                // Where the stream begins is known once it's ready; until then there's nothing to loop back to.
                var range: LongRange? = null
                while (true) {
                    delay(250)
                    if (range == null) { if (player.playbackState == Player.STATE_READY) range = loop.range(player.currentPosition) else continue }
                    if (player.currentPosition >= range.last) player.seekTo(range.first)
                }
            }
        }
    }

    private companion object {
        /** As quiet as theme music. */
        const val VOLUME = 0.35f
        /** A cheap stream: it only loops behind the page. */
        const val CLIP_BITRATE = 8_000_000
    }
}
