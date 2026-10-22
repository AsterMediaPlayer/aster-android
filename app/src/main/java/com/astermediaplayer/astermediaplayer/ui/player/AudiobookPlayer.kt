package com.astermediaplayer.astermediaplayer.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.astermediaplayer.astermediaplayer.data.Audiobook
import com.astermediaplayer.astermediaplayer.data.AudiobookChapter
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import java.util.UUID

data class AudiobookState(
    val book: Audiobook? = null,
    /** The part (file) playing. */
    val partIndex: Int = 0,
    val isPlaying: Boolean = false,
    /** Seconds into the part playing, and the part's length. */
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val rate: Float = 1f,
    val loading: Boolean = false,
    val error: String? = null,
) {
    /** The chapter the playhead is in. */
    val chapter: AudiobookChapter?
        get() = book?.chapters?.lastOrNull { it.part < partIndex || (it.part == partIndex && it.startSeconds <= position + 0.5) }

    /** How far into the whole book, and its whole length. */
    val bookElapsed: Double get() = (book?.offsetOfPart(partIndex) ?: 0.0) + position
    val bookDuration: Double get() = book?.duration ?: 0.0

    /** Where the current chapter starts and ends in the part playing, for its progress bar. */
    val chapterBounds: Pair<Double, Double>
        get() {
            val book = book ?: return 0.0 to duration
            val chapter = chapter?.takeIf { it.part == partIndex } ?: return 0.0 to duration
            val next = book.chapters.firstOrNull { it.id == chapter.id + 1 }
            val end = next?.let { if (it.part == partIndex) it.startSeconds else duration } ?: duration
            return chapter.startSeconds to maxOf(end, chapter.startSeconds)
        }
}

/**
 * Plays one audiobook at a time: picks up where Jellyfin says listening left off, moves between chapters and parts,
 * plays at the speed chosen for the book, and skips back 15 seconds or ahead 30 from the app, the notification, the
 * lock screen and headphones (through [PlaybackService], which publishes this player as a media session). Progress goes
 * back to Jellyfin so another device carries on from the same place. The Kotlin counterpart of `AudiobookPlayer`.
 */
@OptIn(UnstableApi::class)
class AudiobookPlayer(private val context: Context, private val repo: AsterRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(AudiobookState())
    val state: StateFlow<AudiobookState> = _state.asStateFlow()

    /** Whether the full-screen player is up. */
    private val _presented = MutableStateFlow(false)
    val presented: StateFlow<Boolean> = _presented.asStateFlow()
    fun setPresented(on: Boolean) { _presented.value = on }

    /** Called when a book starts, so music and page theme music step aside. */
    var onStart: (() -> Unit)? = null

    private val rates = context.getSharedPreferences("audiobook_rates", Context.MODE_PRIVATE)

    /** The player, created with the first book and shared with the media session service. */
    var player: ExoPlayer? = null
        private set
    private var tick: Job? = null
    private var playSessionId = UUID.randomUUID().toString()
    private var lastReport = 0L
    private var reportedPart: UUID? = null

    private fun rateFor(id: UUID): Float = rates.getFloat(id.toString(), 1f)

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                (context.applicationContext as com.astermediaplayer.astermediaplayer.AsterApp).downloads.cacheDataSourceFactory,
            ),
        )
        .setSeekBackIncrementMs(SKIP_BACK_SECONDS * 1000)
        .setSeekForwardIncrementMs(SKIP_FORWARD_SECONDS * 1000)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        .setHandleAudioBecomingNoisy(true)
        // Holds the Wi-Fi radio and the CPU while a book plays with the screen off.
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build().also { exo ->
            player = exo
            exo.addListener(listener)
        }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (_state.value.book != null) report(paused = !isPlaying, saving = true)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val exo = player ?: return
            _state.update { it.copy(loading = playbackState == Player.STATE_BUFFERING, duration = exo.durationSeconds(it)) }
            if (playbackState == Player.STATE_ENDED) bookEnded()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val exo = player ?: return
            // The part that was playing is done: its place is saved, and the next one starts being reported.
            reportStopped(played = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            _state.update { it.copy(partIndex = exo.currentMediaItemIndex, position = 0.0, duration = exo.durationSeconds(it)) }
            playSessionId = UUID.randomUUID().toString()
            reportedPart = null
            report(paused = false, start = true)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            android.util.Log.e("Aster", "Audiobook failed", error)
            _state.update { it.copy(error = "This book can't be played.", isPlaying = false, loading = false) }
        }
    }

    private fun ExoPlayer.durationSeconds(state: AudiobookState): Double {
        val ms = duration
        return if (ms != C.TIME_UNSET && ms > 0) ms / 1000.0 else state.book?.partDurations?.getOrNull(currentMediaItemIndex) ?: 0.0
    }

    // MARK: Starting

    /** Plays [book]: from [chapter] when given, else from where listening left off. The book already playing carries on. */
    fun play(book: Audiobook, chapter: AudiobookChapter? = null) {
        _presented.value = true
        val current = _state.value.book
        if (current?.id == book.id) {
            if (chapter != null) jump(chapter)
            if (player?.isPlaying != true) player?.play()
            return
        }
        onStart?.invoke()
        reportStopped()
        val exo = ensurePlayer()
        // The service that publishes the session (and the notification) starts with the first book.
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))

        val items = book.parts.map { part ->
            MediaItem.Builder()
                .setUri(Uri.parse(repo.audioStreamUrl(part.id)))
                .setMediaId(part.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(book.title).setArtist(book.author).setAlbumTitle(book.series)
                        .setArtworkUri(repo.bookCoverUrl(book, 600)?.let(Uri::parse))
                        .build(),
                )
                .build()
        }
        val start = chapter?.let { it.part to it.startSeconds } ?: book.resumePoint ?: (0 to 0.0)
        val rate = rateFor(book.id)
        _state.value = AudiobookState(book = book, partIndex = start.first, position = start.second, rate = rate, loading = true)
        playSessionId = UUID.randomUUID().toString()
        reportedPart = null
        exo.setMediaItems(items, start.first, (start.second * 1000).toLong())
        exo.playbackParameters = PlaybackParameters(rate, 1f)
        exo.prepare()
        exo.playWhenReady = true
        startTicking()
        report(paused = false, start = true)
    }

    // MARK: Transport

    /** Another kind of audio started: this one steps aside, keeping its place. */
    fun pauseForOther() { player?.pause() }

    fun togglePlayPause() { player?.let { if (it.isPlaying) it.pause() else it.play() } }

    /** Back or ahead by [seconds], into the part before or after when it runs past this one's start or end. */
    fun skip(seconds: Int) {
        val exo = player ?: return
        val book = _state.value.book ?: return
        val target = exo.currentPosition + seconds * 1000L
        val index = exo.currentMediaItemIndex
        when {
            target < 0 && index > 0 -> exo.seekTo(index - 1, (book.partDurations[index - 1] * 1000 + target).toLong().coerceAtLeast(0))
            target > exo.duration && exo.duration > 0 && index + 1 < book.parts.size -> exo.seekTo(index + 1, target - exo.duration)
            else -> exo.seekTo(target.coerceAtLeast(0))
        }
        refreshPosition()
    }

    /** Moves within the part playing. */
    fun seekTo(seconds: Double) {
        player?.seekTo((seconds * 1000).toLong().coerceAtLeast(0))
        refreshPosition()
        report(paused = player?.isPlaying != true, saving = true)
    }

    fun jump(chapter: AudiobookChapter) {
        player?.seekTo(chapter.part, (chapter.startSeconds * 1000).toLong())
        refreshPosition()
    }

    fun nextChapter() {
        val s = _state.value
        val next = s.book?.chapters?.firstOrNull { it.id == (s.chapter?.id ?: -1) + 1 } ?: return
        jump(next)
    }

    /** The start of this chapter, or the one before when just into it. */
    fun previousChapter() {
        val s = _state.value
        val book = s.book ?: return
        val current = s.chapter
        if (current == null) { seekTo(0.0); return }
        val into = if (current.part == s.partIndex) s.position - current.startSeconds else s.position
        if (into > 3 || current.id == 0) jump(current)
        else book.chapters.firstOrNull { it.id == current.id - 1 }?.let { jump(it) }
    }

    fun setRate(rate: Float) {
        val book = _state.value.book ?: return
        rates.edit().putFloat(book.id.toString(), rate).apply()
        player?.playbackParameters = PlaybackParameters(rate, 1f)
        _state.update { it.copy(rate = rate) }
    }

    /** Stops and lets go of the book, reporting where it got to. */
    fun stop() {
        if (_state.value.book == null) return
        reportStopped()
        tick?.cancel()
        player?.let { it.stop(); it.clearMediaItems() }
        context.stopService(Intent(context, PlaybackService::class.java))
        _state.value = AudiobookState()
        _presented.value = false
    }

    private fun bookEnded() {
        reportStopped(played = true)
        player?.pause()
        _state.update { it.copy(isPlaying = false, position = it.duration) }
    }

    // MARK: Position and reporting

    private fun startTicking() {
        tick?.cancel()
        tick = scope.launch {
            while (true) {
                delay(500)
                refreshPosition()
                if (player?.isPlaying == true && System.currentTimeMillis() - lastReport >= REPORT_INTERVAL_MS) report(paused = false)
            }
        }
    }

    private fun refreshPosition() {
        val exo = player ?: return
        _state.update { it.copy(position = exo.currentPosition / 1000.0, partIndex = exo.currentMediaItemIndex, duration = exo.durationSeconds(it)) }
    }

    private fun report(paused: Boolean, start: Boolean = false, saving: Boolean = false) {
        val book = _state.value.book ?: return
        val exo = player ?: return
        val part = book.parts.getOrNull(exo.currentMediaItemIndex) ?: return
        lastReport = System.currentTimeMillis()
        val ticks = exo.currentPosition * 10_000L
        val first = reportedPart != part.id
        reportedPart = part.id
        scope.launch {
            if (start || first) {
                repo.reportStart(
                    PlaybackStartInfo(
                        canSeek = true, itemId = part.id, playSessionId = playSessionId, playMethod = PlayMethod.DIRECT_PLAY,
                        isPaused = paused, isMuted = false, positionTicks = ticks,
                        repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT,
                    ),
                )
            } else {
                repo.reportProgress(
                    PlaybackProgressInfo(
                        canSeek = true, itemId = part.id, playSessionId = playSessionId, playMethod = PlayMethod.DIRECT_PLAY,
                        isPaused = paused, isMuted = false, positionTicks = ticks,
                        repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT,
                    ),
                )
            }
        }
    }

    /** Reports the part that's ending, so the server keeps its position. */
    private fun reportStopped(played: Boolean = false) {
        val book = _state.value.book ?: return
        val id = reportedPart ?: return
        val part = book.parts.firstOrNull { it.id == id } ?: return
        val exo = player ?: return
        val ticks = if (played) (part.runTimeTicks ?: 0L) else exo.currentPosition * 10_000L
        reportedPart = null
        val info = PlaybackStopInfo(itemId = part.id, playSessionId = playSessionId, positionTicks = ticks, failed = false)
        // The caller's scope may be going away (the app is being put away), so this has its own.
        @Suppress("OPT_IN_USAGE")
        kotlinx.coroutines.GlobalScope.launch { repo.reportStopped(info) }
    }

    companion object {
        const val SKIP_BACK_SECONDS = 15L
        const val SKIP_FORWARD_SECONDS = 30L
        private const val REPORT_INTERVAL_MS = 10_000L
    }
}
