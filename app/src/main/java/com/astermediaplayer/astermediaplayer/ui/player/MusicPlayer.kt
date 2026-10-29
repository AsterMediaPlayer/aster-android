package com.astermediaplayer.astermediaplayer.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.LyricDto
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import java.util.UUID

enum class RepeatKind { Off, All, One }

data class MusicState(
    val queue: List<BaseItemDto> = emptyList(),
    val index: Int = 0,
    val isPlaying: Boolean = false,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val shuffled: Boolean = false,
    val repeat: RepeatKind = RepeatKind.Off,
    /** Lyrics for the current song, once fetched; null when the server has none. */
    val lyrics: LyricDto? = null,
    val error: String? = null,
) {
    val current: BaseItemDto? get() = queue.getOrNull(index)
    val upNext: List<BaseItemDto> get() = queue.drop(index + 1).take(50)

    /** The lyric line the playhead is on, for synced lyrics. */
    val currentLyricIndex: Int?
        get() {
            val l = lyrics ?: return null
            if (l.lyrics.none { it.start != null }) return null
            val ticks = (position * 10_000_000).toLong() + (l.metadata?.offset ?: 0L)
            return l.lyrics.indexOfLast { (it.start ?: 0L) <= ticks }.takeIf { it >= 0 }
        }
    val lyricsSynced: Boolean get() = lyrics?.lyrics?.any { it.start != null } == true
}

/**
 * Plays a queue of songs through ExoPlayer, published as a media session by [PlaybackService] so the notification, lock
 * screen and headphones control it. Shuffle keeps the song playing and shuffles what follows; turning it off restores the
 * order the songs were handed over in. Plays, with progress and lyrics, as the Apple apps' `MusicPlayer`.
 */
@OptIn(UnstableApi::class)
class MusicPlayer(private val context: Context, private val repo: AsterRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()

    private val _presented = MutableStateFlow(false)
    val presented: StateFlow<Boolean> = _presented.asStateFlow()
    fun setPresented(on: Boolean) { _presented.value = on }

    /** Called when a new queue starts, so audiobooks and page theme music step aside. */
    var onStart: (() -> Unit)? = null

    var player: ExoPlayer? = null
        private set
    private var originalOrder: List<BaseItemDto> = emptyList()
    private var tick: Job? = null
    private var lyricsJob: Job? = null
    private var playSessionId = UUID.randomUUID().toString()
    private var reportedId: UUID? = null
    private var lastReport = 0L

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                com.astermediaplayer.astermediaplayer.data.signedInMedia(checkNotNull(repo.session).accessToken),
            ),
        )
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build().also { it.addListener(listener); player = it }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (_state.value.current != null) report(paused = !isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val exo = player ?: return
            if (playbackState == Player.STATE_ENDED) { reportStopped(played = true); _state.update { it.copy(isPlaying = false, position = 0.0) } }
            else _state.update { it.copy(duration = exo.durationSeconds(it)) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val exo = player ?: return
            reportStopped(played = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            _state.update { it.copy(index = exo.currentMediaItemIndex, position = 0.0, duration = exo.durationSeconds(it), lyrics = null) }
            playSessionId = UUID.randomUUID().toString()
            reportedId = null
            report(paused = false, start = true)
            _state.value.current?.let { fetchLyrics(it) }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            android.util.Log.e("Aster", "Music failed", error)
            _state.update { it.copy(error = "This song can't be played.", isPlaying = false) }
        }
    }

    private fun ExoPlayer.durationSeconds(state: MusicState): Double {
        val ms = duration
        return if (ms != C.TIME_UNSET && ms > 0) ms / 1000.0 else (state.current?.runTimeTicks ?: 0L) / 10_000_000.0
    }

    private fun mediaItem(song: BaseItemDto): MediaItem = MediaItem.Builder()
        .setUri(Uri.parse(repo.audioStreamUrl(song.id)))
        .setMediaId(song.id.toString())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.name).setArtist(song.artists?.joinToString(", ") ?: song.albumArtist).setAlbumTitle(song.album)
                .setArtworkUri(coverUrl(song)?.let(Uri::parse))
                .build(),
        )
        .build()

    private fun coverUrl(song: BaseItemDto): String? =
        repo.imageUrl(song, org.jellyfin.sdk.model.api.ImageType.PRIMARY, 600) ?: song.albumId?.let { id ->
            song.albumPrimaryImageTag?.let { repo.imageUrlFor(id, it, 600) }
        }

    // MARK: Starting

    /** Plays a list of songs from one of them, optionally shuffled (the chosen song still goes first). */
    fun play(tracks: List<BaseItemDto>, startingAt: Int = 0, shuffled: Boolean = false) {
        val songs = tracks.filter { it.type == BaseItemKind.AUDIO }
        if (songs.isEmpty()) return
        onStart?.invoke()
        reportStopped()
        val exo = ensurePlayer()
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        originalOrder = songs
        val start = startingAt.coerceIn(0, songs.size - 1)
        val queue = if (shuffled) listOf(songs[start]) + (songs - songs[start]).shuffled() else songs
        val index = if (shuffled) 0 else start
        _state.value = MusicState(queue = queue, index = index, shuffled = shuffled, repeat = _state.value.repeat)
        exo.repeatMode = _state.value.repeat.toExo()
        exo.setMediaItems(queue.map(::mediaItem), index, 0)
        exo.prepare()
        exo.playWhenReady = true
        _presented.value = true
        playSessionId = UUID.randomUUID().toString()
        reportedId = null
        startTicking()
        report(paused = false, start = true)
        fetchLyrics(queue[index])
    }

    /** Jumps to a song already in the queue. */
    fun skipTo(song: BaseItemDto) {
        val position = _state.value.queue.indexOfFirst { it.id == song.id }
        if (position >= 0) player?.seekTo(position, 0)
    }

    // MARK: Transport

    /** Another kind of audio started: this one steps aside, keeping its place. */
    fun pauseForOther() { player?.pause() }

    fun togglePlayPause() { player?.let { if (it.isPlaying) it.pause() else it.play() } }

    fun next() {
        val exo = player ?: return
        if (exo.hasNextMediaItem()) exo.seekToNextMediaItem()
        else if (_state.value.repeat == RepeatKind.All) exo.seekTo(0, 0)
        else { exo.pause(); exo.seekTo(0) }
    }

    fun previous() {
        val exo = player ?: return
        if (exo.currentPosition > 3000 || !exo.hasPreviousMediaItem()) exo.seekTo(0) else exo.seekToPreviousMediaItem()
    }

    fun seekTo(seconds: Double) {
        player?.seekTo((seconds * 1000).toLong().coerceAtLeast(0))
        refreshPosition()
        report(paused = player?.isPlaying != true)
    }

    fun toggleShuffle() {
        val exo = player ?: return
        val s = _state.value
        val now = s.current ?: return
        val i = exo.currentMediaItemIndex
        // Everything but the song playing is swapped, so it keeps playing without a gap.
        exo.removeMediaItems(i + 1, exo.mediaItemCount)
        exo.removeMediaItems(0, i)
        if (s.shuffled) {
            val at = originalOrder.indexOfFirst { it.id == now.id }.coerceAtLeast(0)
            exo.addMediaItems(0, originalOrder.take(at).map(::mediaItem))
            exo.addMediaItems(originalOrder.drop(at + 1).map(::mediaItem))
            _state.update { it.copy(queue = originalOrder, index = at, shuffled = false) }
        } else {
            val rest = (s.queue - now).shuffled()
            exo.addMediaItems(rest.map(::mediaItem))
            _state.update { it.copy(queue = listOf(now) + rest, index = 0, shuffled = true) }
        }
    }

    fun cycleRepeat() {
        val next = when (_state.value.repeat) { RepeatKind.Off -> RepeatKind.All; RepeatKind.All -> RepeatKind.One; RepeatKind.One -> RepeatKind.Off }
        player?.repeatMode = next.toExo()
        _state.update { it.copy(repeat = next) }
    }

    private fun RepeatKind.toExo() = when (this) { RepeatKind.Off -> Player.REPEAT_MODE_OFF; RepeatKind.All -> Player.REPEAT_MODE_ALL; RepeatKind.One -> Player.REPEAT_MODE_ONE }

    /** Stops and clears the queue. */
    fun stop() {
        if (_state.value.current == null) return
        reportStopped()
        tick?.cancel()
        lyricsJob?.cancel()
        player?.let { it.stop(); it.clearMediaItems() }
        context.stopService(Intent(context, PlaybackService::class.java))
        _state.value = MusicState()
        originalOrder = emptyList()
        _presented.value = false
    }

    // MARK: Lyrics, position and reporting

    private fun fetchLyrics(song: BaseItemDto) {
        lyricsJob?.cancel()
        lyricsJob = scope.launch {
            val fetched = repo.lyrics(song.id)
            if (_state.value.current?.id == song.id) _state.update { it.copy(lyrics = fetched?.takeIf { l -> l.lyrics.isNotEmpty() }) }
        }
    }

    private fun startTicking() {
        tick?.cancel()
        tick = scope.launch {
            while (true) {
                delay(500)
                refreshPosition()
                if (player?.isPlaying == true && System.currentTimeMillis() - lastReport >= 10_000L) report(paused = false)
            }
        }
    }

    private fun refreshPosition() {
        val exo = player ?: return
        _state.update { it.copy(position = exo.currentPosition / 1000.0, duration = exo.durationSeconds(it)) }
    }

    private fun report(paused: Boolean, start: Boolean = false) {
        val song = _state.value.current ?: return
        val exo = player ?: return
        lastReport = System.currentTimeMillis()
        val ticks = exo.currentPosition * 10_000L
        val first = reportedId != song.id
        reportedId = song.id
        scope.launch {
            if (start || first) repo.reportStart(
                PlaybackStartInfo(canSeek = true, itemId = song.id, playSessionId = playSessionId, playMethod = PlayMethod.DIRECT_PLAY, isPaused = paused, isMuted = false, positionTicks = ticks,
                    repeatMode = org.jellyfin.sdk.model.api.RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT),
            ) else repo.reportProgress(
                PlaybackProgressInfo(canSeek = true, itemId = song.id, playSessionId = playSessionId, playMethod = PlayMethod.DIRECT_PLAY, isPaused = paused, isMuted = false, positionTicks = ticks,
                    repeatMode = org.jellyfin.sdk.model.api.RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT),
            )
        }
    }

    /** Reports the song that's ending, so the server records it, without touching the queue. */
    private fun reportStopped(played: Boolean = false) {
        val id = reportedId ?: return
        val song = _state.value.queue.firstOrNull { it.id == id } ?: return
        val exo = player ?: return
        val ticks = if (played) (song.runTimeTicks ?: 0L) else exo.currentPosition * 10_000L
        reportedId = null
        @Suppress("OPT_IN_USAGE")
        kotlinx.coroutines.GlobalScope.launch { repo.reportStopped(PlaybackStopInfo(itemId = song.id, playSessionId = playSessionId, positionTicks = ticks, failed = false)) }
    }
}
