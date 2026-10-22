package com.astermediaplayer.astermediaplayer.ui.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.channels.ChannelTuner
import com.astermediaplayer.astermediaplayer.data.channels.TunedSegment
import com.astermediaplayer.astermediaplayer.data.channels.plain
import com.astermediaplayer.astermediaplayer.data.SettingsStore
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import java.util.UUID

data class Quality(val label: String, val maxBitrate: Int)

val Qualities = listOf(
    Quality("Auto", 120_000_000),
    Quality("20 Mbps", 20_000_000),
    Quality("8 Mbps", 8_000_000),
    Quality("4 Mbps", 4_000_000),
    Quality("2 Mbps", 2_000_000),
)

data class Chapter(val name: String, val startMs: Long)

/** [label] names what the clip is ("Season 1 Opening"), shown in place of the episode's title. */
data class ClipRange(val startMs: Long, val endMs: Long, val label: String? = null)

data class DirectMedia(val path: String, val title: String, val subtitle: String?)

data class SkipPrompt(val label: String, val targetMs: Long)

data class PlayerUiState(
    val title: String = "",
    val subtitle: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val skip: SkipPrompt? = null,
    val quality: Quality = Qualities.first(),
    val transcoding: Boolean = false,
    val chapters: List<Chapter> = emptyList(),
    /** A TV channel: no resume point and no next episode, and the dial can change channel. */
    val live: Boolean = false,
    /** A Channels-tab channel: the dial changes channel here too. */
    val channel: Boolean = false,
    /** A break's clip is playing, not a programme. */
    val breakClip: Boolean = false,
    val chapterIndex: Int = -1,
)

@OptIn(UnstableApi::class)
class PlayerViewModel(
    context: Context,
    private val repo: AsterRepository,
    private val settings: SettingsStore,
    initialItemId: UUID,
    /** Plays just this stretch of the item (an opening or ending found in the chapters). */
    private val clip: ClipRange? = null,
    /** Plays this file from the OP/ED plugin instead of a library item. */
    private val direct: DirectMedia? = null,
    /** Start over even when there's a resume point. */
    private val fromBeginning: Boolean = false,
    /** A Channels-tab channel (by playlist id): what plays is whatever its schedule says is on, then what follows. */
    val playlistChannelId: String? = null,
    /** Where in the item the schedule has it, and where a clip cut short stops, in milliseconds. */
    startAtMs: Long? = null,
    cutAtMs: Long? = null,
) : ViewModel() {
    private var scheduledStartMs: Long? = startAtMs
    private var currentPlaylistChannel: String? = playlistChannelId
    private val _playlistChannelId = MutableStateFlow(playlistChannelId)
    /** The Channels-tab channel playing now (it changes with the dial). */
    val playingPlaylistChannel: StateFlow<String?> = _playlistChannelId.asStateFlow()

    private var cutMs: Long? = cutAtMs
    /** The item playing; changes when a live channel is switched. */
    private var itemId = initialItemId
    private var liveStreamId: String? = null

    /** The channels the dial goes through, in the guide's order, and the one playing. Empty off live TV. */
    private val _channels = MutableStateFlow<List<BaseItemDto>>(emptyList())
    val channels: StateFlow<List<BaseItemDto>> = _channels.asStateFlow()
    private val _channelId = MutableStateFlow(initialItemId)
    val channelId: StateFlow<UUID> = _channelId.asStateFlow()

    /** A clip or a plugin file isn't the viewer watching the item, so nothing is reported to the server. */
    private val readOnly = clip != null || direct != null || playlistChannelId != null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _playNext = MutableSharedFlow<UUID>(extraBufferCapacity = 1)
    /** Emits the next episode's id when the current one finishes. */
    val playNext: SharedFlow<UUID> = _playNext.asSharedFlow()

    private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when a clip or plugin file has played to its end. */
    val finished: SharedFlow<Unit> = _finished.asSharedFlow()

    private var appSettings = runBlocking { settings.current() }

    /** libass draws ASS/SSA the way the release intended: positioned signs, vector drawings and embedded fonts. Null when turned off in Settings. */
    val assHandler: AssHandler? =
        if (appSettings.styledSubtitles) AssHandler(AssRenderType.OVERLAY_OPEN_GL, AssHandlerConfig(maxRenderPixels = 1920 * 1080)) else null
    private val assParserFactory = assHandler?.let { AssSubtitleParserFactory(it) }

    private val mediaSourceFactory = DefaultMediaSourceFactory(
        DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("X-Emby-Token" to repo.session!!.accessToken)),
        DefaultExtractorsFactory().let { if (assHandler != null) it.withAssMkvSupport(assParserFactory!!, assHandler) else it },
    ).also { if (assParserFactory != null) it.setSubtitleParserFactory(assParserFactory) }

    val player: ExoPlayer = ExoPlayer.Builder(
        context.applicationContext,
        renderersFactory(context).let { if (assHandler != null) AssRenderersFactory(assHandler, it) else it },
    )
        .setMediaSourceFactory(mediaSourceFactory)
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 90_000, 2_500, 5_000).build())
        .setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private var item: BaseItemDto? = null
    private var mediaSourceId: String? = null
    private var playSessionId: String? = null
    private var playMethod = PlayMethod.DIRECT_PLAY
    private var segments: List<MediaSegmentDto> = emptyList()
    private var nextEpisodeId: UUID? = null
    private var tickJob: Job? = null
    private var reported = false
    private var stopped = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) _state.update { it.copy(loading = false) }
            if (playbackState == Player.STATE_BUFFERING && player.currentPosition == 0L) _state.update { it.copy(loading = true) }
            if (playbackState == Player.STATE_ENDED) onEnded()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = reportProgress()

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) reportProgress()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // Direct play can fail on codecs this device lacks; retry once through the server's transcode.
            if (playMethod == PlayMethod.DIRECT_PLAY && _state.value.quality == Qualities.first() && !forcedTranscode) {
                forcedTranscode = true
                start(player.currentPosition, forceTranscode = true)
            } else {
                android.util.Log.e("Aster", "Playback failed", error)
                _state.update { it.copy(loading = false, error = "This video couldn't be played on this device (${error.errorCodeName})") }
            }
        }
    }
    private var forcedTranscode = false
    private var autoSkipped: SkipPrompt? = null

    init {
        player.addListener(listener)
        assHandler?.init(player)
        viewModelScope.launch {
            Qualities.getOrNull(appSettings.qualityIndex)?.let { q -> _state.update { it.copy(quality = q) } }
            start(null)
        }
    }

    private fun start(positionMs: Long?, forceTranscode: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                if (direct != null) {
                    player.setMediaItem(MediaItem.fromUri(repo.opEdMediaUrl(direct.path)))
                    player.prepare()
                    player.playWhenReady = true
                    _state.update { it.copy(title = direct.title, subtitle = direct.subtitle) }
                    startTicking()
                    return@launch
                }
                val dto = item ?: repo.item(itemId).also { item = it }
                val isLive = dto.type == BaseItemKind.TV_CHANNEL
                val resumeMs = positionMs ?: clip?.startMs ?: scheduledStartMs ?: if (fromBeginning || isLive) 0L else ((dto.userData?.playbackPositionTicks ?: 0L) / TICKS_PER_MS)
                if (!readOnly && !isLive && item != null && segments.isEmpty()) segments = repo.segments(itemId)
                if (!readOnly && !isLive && nextEpisodeId == null) nextEpisodeId = findNextEpisode(dto)

                // A failed direct play usually means the device can't decode the source (4K, Dolby Vision, ...),
                // so the fallback transcode is capped to something every phone can handle.
                val maxBitrate = if (forceTranscode) minOf(_state.value.quality.maxBitrate, FALLBACK_BITRATE) else _state.value.quality.maxBitrate
                val info = repo.playbackInfo(
                    itemId, resumeMs * TICKS_PER_MS, maxBitrate,
                    mediaSourceId = mediaSourceId,
                    forceTranscode = forceTranscode,
                )
                scheduledStartMs = null
                val source = info.mediaSources.firstOrNull() ?: error("This item has no playable media")
                mediaSourceId = source.id
                playSessionId = info.playSessionId
                liveStreamId = source.liveStreamId
                if (isLive && _channels.value.isEmpty()) viewModelScope.launch { _channels.value = runCatching { repo.liveChannels() }.getOrDefault(emptyList()) }

                val direct = source.supportsDirectPlay && !forceTranscode
                val url = when {
                    direct -> repo.directStreamUrl(itemId, source.id!!, info.playSessionId, source.container)
                    source.transcodingUrl != null -> repo.absoluteUrl(source.transcodingUrl!!)
                    else -> error("The server can't stream this item to this device")
                }
                playMethod = if (direct) PlayMethod.DIRECT_PLAY else PlayMethod.TRANSCODE

                val subtitles = source.mediaStreams.orEmpty()
                    .filter { it.type == MediaStreamType.SUBTITLE && it.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL && it.deliveryUrl != null }
                    .map { subtitleConfiguration(it, selected = it.index == source.defaultSubtitleStreamIndex) }
                android.util.Log.i(
                    "Aster",
                    "Subtitles: " + source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.SUBTITLE }
                        .joinToString { "#${it.index} ${it.codec} ${it.language} ${it.deliveryMethod} default=${it.isDefault}" } +
                        " | server default=${source.defaultSubtitleStreamIndex} | external=${subtitles.size}",
                )
                player.setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .setSubtitleConfigurations(subtitles)
                        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(dto.name).build())
                        .build(),
                    resumeMs.coerceAtLeast(0),
                )
                player.prepare()
                player.playWhenReady = true
                _state.update {
                    it.copy(
                        title = clip?.label ?: dto.name.orEmpty(),
                        subtitle = when {
                            currentPlaylistChannel != null -> ChannelTuner.channel(currentPlaylistChannel!!)?.let { c -> "${c.number} ${c.name}" }
                            dto.type == BaseItemKind.EPISODE -> dto.seriesName
                            else -> null
                        },
                        transcoding = !direct,
                        live = isLive,
                        channel = playlistChannelId != null,
                        breakClip = playlistChannelId != null && dto.type != BaseItemKind.MOVIE && dto.type != BaseItemKind.EPISODE,
                        chapters = (if (readOnly) emptyList() else dto.chapters.orEmpty()).mapIndexed { i, c ->
                            Chapter(c.name?.takeIf { n -> n.isNotBlank() } ?: "Chapter ${i + 1}", c.startPositionTicks / TICKS_PER_MS)
                        },
                    )
                }
                if (!reported && !readOnly) {
                    reported = true
                    repo.reportStart(
                        PlaybackStartInfo(
                            canSeek = true, itemId = itemId, mediaSourceId = source.id, playSessionId = info.playSessionId, liveStreamId = liveStreamId,
                            playMethod = playMethod, isPaused = false, isMuted = false, positionTicks = resumeMs * TICKS_PER_MS,
                            repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT,
                        ),
                    )
                }
                startTicking()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    private fun subtitleConfiguration(stream: MediaStream, selected: Boolean): MediaItem.SubtitleConfiguration {
        val mime = when (stream.codec?.lowercase()) {
            "vtt", "webvtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        return MediaItem.SubtitleConfiguration.Builder(Uri.parse(repo.absoluteUrl(stream.deliveryUrl!!)))
            .setMimeType(mime)
            .setId((SUBTITLE_ID_BASE + stream.index).toString())
            .setLanguage(stream.language)
            .setLabel(stream.displayTitle ?: stream.title ?: stream.language)
            .setSelectionFlags(if (selected || stream.isDefault) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
    }

    private suspend fun findNextEpisode(dto: BaseItemDto): UUID? {
        val seriesId = dto.seriesId
        if (dto.type != BaseItemKind.EPISODE || seriesId == null) return null
        val episodes = runCatching { repo.episodes(seriesId, null) }.getOrNull() ?: return null
        val index = episodes.indexOfFirst { it.id == dto.id }
        return episodes.getOrNull(index + 1)?.id
    }

    /** What a channel's schedule has on after this: the next programme or break clip. */
    private fun tuneNext() {
        val channel = currentPlaylistChannel ?: return
        val finished = item?.id?.plain()
        val next = ChannelTuner.live(channel, after = finished) ?: run { _state.update { it.copy(error = "Nothing is on this channel right now.") }; return }
        playSegment(next)
    }

    private fun playSegment(segment: TunedSegment) {
        val dto = segment.item.source as? BaseItemDto ?: return
        reportStopped()
        player.stop()
        item = null
        mediaSourceId = null
        reported = false
        stopped = false
        forcedTranscode = false
        itemId = dto.id
        scheduledStartMs = segment.startTicks / TICKS_PER_MS
        cutMs = segment.cutTicks?.let { it / TICKS_PER_MS }
        start(null)
    }

    /** Tunes to another Channels-tab channel (by playlist id) from the dial: what's on it now, from as far in as it's got. */
    fun switchPlaylistChannel(channelId: String) {
        if (channelId == currentPlaylistChannel) return
        val segment = ChannelTuner.live(channelId) ?: return
        currentPlaylistChannel = channelId
        _playlistChannelId.value = channelId
        playSegment(segment)
    }

    /** Tunes to another channel without leaving the player: the one on is let go, the new one opens in its place. */
    fun switchChannel(channelId: UUID) {
        if (channelId == itemId) return
        reportStopped()
        player.stop()
        item = null
        mediaSourceId = null
        reported = false
        stopped = false
        forcedTranscode = false
        itemId = channelId
        _channelId.value = channelId
        start(null)
    }

    fun setQuality(quality: Quality) {
        if (quality == _state.value.quality) return
        _state.update { it.copy(quality = quality) }
        forcedTranscode = false
        start(player.currentPosition)
    }

    fun seekToChapter(chapter: Chapter) = player.seekTo(chapter.startMs)

    fun skip() {
        _state.value.skip?.let { player.seekTo(it.targetMs) }
    }

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = viewModelScope.launch {
            var sinceReport = 0
            while (true) {
                delay(500)
                updateSkipPrompt()
                updateChapter()
                if (clip != null && player.currentPosition >= clip.endMs) {
                    player.pause()
                    _finished.tryEmit(Unit)
                    break
                }
                // A break clip cut short to land on the half hour stops where the schedule says.
                cutMs?.let { cut -> if (currentPlaylistChannel != null && player.currentPosition >= cut) { tuneNext(); return@let } }
                sinceReport += 500
                if (sinceReport >= PROGRESS_INTERVAL_MS) {
                    sinceReport = 0
                    reportProgress()
                }
            }
        }
    }

    private fun updateChapter() {
        val chapters = _state.value.chapters
        if (chapters.isEmpty()) return
        val index = chapters.indexOfLast { it.startMs <= player.currentPosition }
        if (index != _state.value.chapterIndex) _state.update { it.copy(chapterIndex = index) }
    }

    private fun updateSkipPrompt() {
        val positionTicks = player.currentPosition * TICKS_PER_MS
        val segment = segments.firstOrNull {
            positionTicks >= it.startTicks && positionTicks < it.endTicks - 1_000 * TICKS_PER_MS
        }
        val prompt = segment?.let {
            SkipPrompt(
                label = if (it.type == MediaSegmentType.INTRO) "Skip Intro" else "Skip Credits",
                targetMs = it.endTicks / TICKS_PER_MS,
            )
        }
        if (prompt != null && appSettings.autoSkipSegments && prompt != autoSkipped) {
            autoSkipped = prompt
            player.seekTo(prompt.targetMs)
            return
        }
        if (prompt != _state.value.skip) _state.update { it.copy(skip = prompt) }
    }

    private fun onEnded() {
        if (currentPlaylistChannel != null) { tuneNext(); return }
        if (readOnly) _finished.tryEmit(Unit)
        reportStopped()
        if (appSettings.autoPlayNext) nextEpisodeId?.let { _playNext.tryEmit(it) }
    }

    private fun reportProgress() {
        if (!reported || stopped) return
        val info = PlaybackProgressInfo(
            canSeek = true, itemId = itemId, mediaSourceId = mediaSourceId, playSessionId = playSessionId, liveStreamId = liveStreamId,
            playMethod = playMethod, isPaused = !player.isPlaying, isMuted = false,
            positionTicks = player.currentPosition * TICKS_PER_MS,
            repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT,
        )
        viewModelScope.launch { repo.reportProgress(info) }
    }

    private fun reportStopped() {
        if (!reported || stopped) return
        stopped = true
        val info = PlaybackStopInfo(
            itemId = itemId, mediaSourceId = mediaSourceId, playSessionId = playSessionId, liveStreamId = liveStreamId,
            positionTicks = player.currentPosition * TICKS_PER_MS, failed = false,
        )
        val live = liveStreamId
        // viewModelScope is cancelled by the time onCleared runs, so the stop report needs its own scope.
        kotlinx.coroutines.GlobalScope.launch {
            repo.reportStopped(info)
            // A channel's tuner is let go, so the server doesn't hold it open.
            if (live != null) repo.closeLiveStream(live)
        }
    }

    override fun onCleared() {
        reportStopped()
        tickJob?.cancel()
        player.removeListener(listener)
        player.release()
        assHandler?.release()
    }

    companion object {
        /**
         * Falls back to another decoder when the first one fails. Emulators expose a host-GPU codec that is flaky,
         * so there Android's own software decoders are tried first.
         */
        fun renderersFactory(context: Context) = DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector { mimeType, secure, tunneling ->
                val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                if (isEmulator) decoders.sortedBy { if (it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.")) 0 else 1 }
                else decoders
            }

        val isEmulator: Boolean
            get() = android.os.Build.HARDWARE.let { it.contains("ranchu") || it.contains("goldfish") } ||
                android.os.Build.FINGERPRINT.contains("generic")

        const val TICKS_PER_MS = 10_000L
        const val PROGRESS_INTERVAL_MS = 10_000
        /** libass needs external subtitle ids that can't collide with the container's own track ids. */
        const val SUBTITLE_ID_BASE = 128
        const val FALLBACK_BITRATE = 8_000_000
    }
}
