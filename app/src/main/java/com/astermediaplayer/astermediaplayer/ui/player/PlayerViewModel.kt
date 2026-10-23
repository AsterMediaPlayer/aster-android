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
import com.astermediaplayer.astermediaplayer.data.SubtitleCue
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
    /** Near the end of an episode with another after it: Play Next Episode is on offer. */
    val upNextOffered: Boolean = false,
    val quality: Quality = Qualities.first(),
    val transcoding: Boolean = false,
    val chapters: List<Chapter> = emptyList(),
    /** A TV channel: no resume point and no next episode, and the dial can change channel. */
    val live: Boolean = false,
    /** A Channels-tab channel: the dial changes channel here too. */
    val channel: Boolean = false,
    /** A break's clip is playing, not a programme. */
    val breakClip: Boolean = false,
    /** Playing from a download on the device. */
    val offline: Boolean = false,
    val chapterIndex: Int = -1,
    /** The subtitle menu: Off, the tracks the player can draw, and picture-based ones the server can burn in. */
    val subtitles: List<SubtitleChoice> = emptyList(),
    /** The line of the server's text subtitle track showing now, drawn by the app over the picture. */
    val cueText: String? = null,
    /** Playback speed, 1 for normal. */
    val speed: Float = 1f,
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

    private val downloads = (context.applicationContext as com.astermediaplayer.astermediaplayer.AsterApp).downloads

    private val mediaSourceFactory = DefaultMediaSourceFactory(
        // The server starts its encoder on the first request for a stream, and again after a long seek, so the first
        // segment can take longer than the 8 seconds a data source allows by default; timing out only sent the request
        // round again (a retry), and the viewer waited for each lap.
        DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(HTTP_TIMEOUT_MS).setReadTimeoutMs(HTTP_TIMEOUT_MS)
            .setDefaultRequestProperties(mapOf("X-Emby-Token" to repo.session!!.accessToken)),
        DefaultExtractorsFactory().let { if (assHandler != null) it.withAssMkvSupport(assParserFactory!!, assHandler) else it },
    ).also { if (assParserFactory != null) it.setSubtitleParserFactory(assParserFactory) }

    /** Plays a download from the device alone. */
    private val offlineSourceFactory = downloads.offlineMediaSourceFactory().also { if (assParserFactory != null) it.setSubtitleParserFactory(assParserFactory) }

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
    /** Playing a download from the device: nothing here asks the server. */
    private var offline: com.astermediaplayer.astermediaplayer.data.downloads.DownloadRecord? = null
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

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = refreshSubtitles()
        override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) = refreshSubtitles()

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

    /** The server's picture-based subtitle burned into the video (PGS, VobSub), when the viewer chose one. */
    private var burnedIn: Int? = null
    /** After a burned-in track is taken off, the stream is asked for with none, so the server doesn't burn its default. */
    private var noBurnIn = false
    /** A track to select once the new stream's tracks are known (what was picked while a burned-in track was showing). */
    private var pendingPick: Triple<String?, String?, String?>? = null
    private var currentSource: org.jellyfin.sdk.model.api.MediaSourceInfo? = null

    /**
     * The server's text subtitle tracks for this stream. The app draws these itself from the JSON cue list; they are never
     * handed to the player, which would hold playback until the server had extracted the track, and that can take minutes.
     */
    private var cueTracks: List<MediaStream> = emptyList()
    /** The one of them showing, by the server's stream index. */
    private var cueTrack: Int? = null
    /** Once a subtitle has been chosen (or turned off), a restart of the stream doesn't bring the server's default back. */
    private var subtitleChosen = false
    private val cueCache = HashMap<Pair<String, Int>, List<SubtitleCue>>()
    private var cueJob: Job? = null

    /** The frames shown above the timeline while scrubbing, when the server has made them for this title. */
    private var scrub: com.astermediaplayer.astermediaplayer.data.ScrubPreviewSource? = null
    private var scrubFor: UUID? = null

    /** The picture for a point on the timeline, or null (the bubble then shows just the time). */
    suspend fun scrubFrame(seconds: Double): android.graphics.Bitmap? = scrub?.frame(seconds)

    private fun loadScrubPreview(forItem: UUID, mediaSourceId: String?) {
        if (scrubFor == forItem) return
        scrubFor = forItem
        scrub?.release(); scrub = null
        viewModelScope.launch {
            val dto = repo.trickplayOf(forItem) ?: return@launch
            if (scrubFor == forItem) scrub = com.astermediaplayer.astermediaplayer.data.ScrubPreviewSource.create(repo, dto, mediaSourceId)
        }
    }

    fun setSpeed(speed: Float) {
        player.playbackParameters = androidx.media3.common.PlaybackParameters(speed)
        _state.update { it.copy(speed = speed) }
    }

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
                // A downloaded title plays from the device, from where viewing stopped, unless it had all but finished.
                val saved = if (clip == null && playlistChannelId == null && !forceTranscode) downloads.playable(itemId) else null
                if (saved != null) {
                    val runtime = saved.runTimeTicks
                    val resume = positionMs ?: if (fromBeginning || (runtime > 0 && saved.positionTicks > runtime * 95 / 100)) 0L else saved.positionTicks / TICKS_PER_MS
                    offline = saved
                    segments = saved.segments.map { org.jellyfin.sdk.model.api.MediaSegmentDto(id = java.util.UUID.randomUUID(), itemId = itemId, type = if (it.intro) MediaSegmentType.INTRO else MediaSegmentType.OUTRO, startTicks = it.startTicks, endTicks = it.endTicks) }
                    player.setMediaSource(offlineSourceFactory.createMediaSource(downloads.mediaItem(saved)), resume.coerceAtLeast(0))
                    player.prepare()
                    player.playWhenReady = true
                    _state.update { it.copy(title = saved.title, subtitle = saved.subtitle?.takeIf { _ -> saved.isEpisode }, offline = true) }
                    startTicking()
                    return@launch
                }
                offline = null
                val dto = item ?: repo.item(itemId).also { item = it }
                val isLive = dto.type == BaseItemKind.TV_CHANNEL
                val resumeMs = positionMs ?: clip?.startMs ?: scheduledStartMs ?: if (fromBeginning || isLive) 0L else ((dto.userData?.playbackPositionTicks ?: 0L) / TICKS_PER_MS)
                if (!readOnly && !isLive && item != null && segments.isEmpty()) segments = repo.segments(itemId)
                if (!readOnly && !isLive && nextEpisodeId == null) nextEpisodeId = findNextEpisode(dto)

                // A failed direct play usually means the device can't decode the source (4K, Dolby Vision, ...),
                // so the fallback transcode is capped to something every phone can handle.
                // Once direct play has failed on this device, every restart of the stream (a subtitle change, say) is a transcode too.
                val fallback = forceTranscode || forcedTranscode
                val maxBitrate = if (fallback) minOf(_state.value.quality.maxBitrate, FALLBACK_BITRATE) else _state.value.quality.maxBitrate
                val info = repo.playbackInfo(
                    itemId, resumeMs * TICKS_PER_MS, maxBitrate,
                    mediaSourceId = mediaSourceId,
                    // A burned-in track is made by the server as it encodes, so it can't be a direct play.
                    subtitleStreamIndex = burnedIn ?: if (noBurnIn) -1 else null,
                    forceTranscode = fallback || burnedIn != null,
                )
                scheduledStartMs = null
                val source = info.mediaSources.firstOrNull() ?: error("This item has no playable media")
                mediaSourceId = source.id
                currentSource = source
                if (!isLive && clip == null && playlistChannelId == null) loadScrubPreview(itemId, source.id)
                playSessionId = info.playSessionId
                liveStreamId = source.liveStreamId
                if (isLive && _channels.value.isEmpty()) viewModelScope.launch { _channels.value = runCatching { repo.liveChannels() }.getOrDefault(emptyList()) }

                val direct = source.supportsDirectPlay && !fallback && burnedIn == null
                val url = when {
                    direct -> repo.directStreamUrl(itemId, source.id!!, info.playSessionId, source.container)
                    source.transcodingUrl != null -> repo.absoluteUrl(source.transcodingUrl!!)
                    else -> error("The server can't stream this item to this device")
                }
                playMethod = if (direct) PlayMethod.DIRECT_PLAY else PlayMethod.TRANSCODE

                cueTracks = source.mediaStreams.orEmpty()
                    .filter { it.type == MediaStreamType.SUBTITLE && it.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL && it.deliveryUrl != null }
                cueTrack = when {
                    // The picture already carries a burned-in track, so none is drawn over it.
                    burnedIn != null -> null
                    subtitleChosen -> cueTrack?.takeIf { t -> cueTracks.any { it.index == t } }
                    else -> cueTracks.firstOrNull { it.index == source.defaultSubtitleStreamIndex }?.index
                }
                android.util.Log.i(
                    "Aster",
                    "Subtitles: " + source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.SUBTITLE }
                        .joinToString { "#${it.index} ${it.codec} ${it.language} ${it.deliveryMethod} default=${it.isDefault}" } +
                        " | server default=${source.defaultSubtitleStreamIndex} | drawn by the app=${cueTracks.size}",
                )
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, burnedIn != null || cueTrack != null).build()
                player.setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(dto.name).build())
                        .build(),
                    resumeMs.coerceAtLeast(0),
                )
                player.prepare()
                player.playWhenReady = true
                startCues()
                refreshSubtitles()
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

    // MARK: Subtitles

    /** Starts (or stops) drawing [cueTrack]: its cues are fetched in the background, then the one under the playhead is shown. */
    private fun startCues() {
        cueJob?.cancel()
        if (_state.value.cueText != null) _state.update { it.copy(cueText = null) }
        val track = cueTrack ?: return
        val sourceId = currentSource?.id ?: return
        val id = item?.id ?: itemId
        cueJob = viewModelScope.launch {
            val key = sourceId to track
            val cues = cueCache[key] ?: try {
                repo.subtitleCues(id, sourceId, track)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Aster", "Subtitle track $track failed to load", e)
                return@launch
            }
            cueCache[key] = cues
            refreshSubtitles()
            while (true) {
                val text = SubtitleCue.textAt(cues, player.currentPosition)
                if (text != _state.value.cueText) _state.update { it.copy(cueText = text) }
                delay(100)
            }
        }
    }

    private fun isPicture(stream: org.jellyfin.sdk.model.api.MediaStream) =
        stream.type == MediaStreamType.SUBTITLE && !stream.isTextSubtitleStream

    /**
     * Builds the subtitle menu: Off, the text and picture tracks the player itself can draw (embedded PGS in a direct
     * play too), and, in a transcode, the picture-based tracks the server can burn in, which the player never sees.
     */
    private fun refreshSubtitles() {
        val tracks = player.currentTracks
        val groups = tracks.groups.withIndex().filter { it.value.type == C.TRACK_TYPE_TEXT }
        val textOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val choices = mutableListOf<SubtitleChoice>()
        val drawn = groups.flatMap { (gi, group) -> (0 until group.length).filter { group.isTrackSupported(it) }.map { gi to it } }
        // A picture-based default the server burns in unasked (a transcode, with no choice made).
        val source = currentSource
        val serverBurn = if (burnedIn == null && !noBurnIn && playMethod == PlayMethod.TRANSCODE) source?.defaultSubtitleStreamIndex?.takeIf { d -> source.mediaStreams?.any { it.index == d && isPicture(it) } == true } else null
        val activeBurn = burnedIn ?: serverBurn
        val anyDrawn = drawn.any { (gi, ti) -> groups.first { it.index == gi }.value.isTrackSelected(ti) }
        choices += SubtitleChoice("Off", selected = activeBurn == null && cueTrack == null && (textOff || !anyDrawn), isOff = true)
        for ((gi, ti) in drawn) {
            val group = tracks.groups[gi]
            val format = group.getTrackFormat(ti)
            val label = SubtitleLabels.label(
                format.language, format.label, SubtitleLabels.format(format.sampleMimeType, format.codecs),
                forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0, number = choices.size,
            )
            choices += SubtitleChoice(label, selected = activeBurn == null && cueTrack == null && !textOff && group.isTrackSelected(ti), group = gi, track = ti)
        }
        // The server's text tracks, drawn by the app; the one picked says so while the server is still extracting it.
        for (stream in cueTracks) {
            val loading = cueTrack == stream.index && currentSource?.id?.let { cueCache[it to stream.index] } == null
            choices += SubtitleChoice(
                SubtitleLabels.label(stream.language, stream.title, SubtitleLabels.format(null, stream.codec), forced = stream.isForced, number = stream.index) + if (loading) " · loading…" else "",
                selected = activeBurn == null && cueTrack == stream.index, serverTrack = stream.index,
            )
        }
        if (playMethod == PlayMethod.TRANSCODE) {
            source?.mediaStreams.orEmpty().filter { isPicture(it) }.forEach { stream ->
                choices += SubtitleChoice(
                    SubtitleLabels.label(stream.language, stream.title, SubtitleLabels.format(null, stream.codec), forced = stream.isForced, burnedIn = true, number = stream.index),
                    selected = activeBurn == stream.index, burnIn = stream.index,
                )
            }
        }
        // What was picked while a burned-in track was showing, once the plain stream's tracks have arrived.
        pendingPick?.let { (mime, language, label) ->
            val match = choices.firstOrNull { c -> c.group != null && tracks.groups[c.group].getTrackFormat(c.track).let { f -> f.sampleMimeType == mime && f.language == language && f.label == label } }
            if (match != null) { pendingPick = null; select(match); return }
        }
        if (choices != _state.value.subtitles) _state.update { it.copy(subtitles = choices) }
    }

    /** Takes a choice from the subtitle menu. */
    fun select(choice: SubtitleChoice) {
        val activeBurn = _state.value.subtitles.firstOrNull { it.burnIn != null && it.selected }?.burnIn
        subtitleChosen = true
        when {
            choice.burnIn != null -> {
                if (choice.burnIn == activeBurn) return
                burnedIn = choice.burnIn; noBurnIn = false; cueTrack = null
                start(player.currentPosition)
            }
            // Off, or a track the player or the app draws, while a track is burned in: the picture needs a new stream without it.
            activeBurn != null -> {
                burnedIn = null; noBurnIn = true; cueTrack = choice.serverTrack
                pendingPick = choice.group?.let { g -> player.currentTracks.groups.getOrNull(g)?.getTrackFormat(choice.track)?.let { Triple(it.sampleMimeType, it.language, it.label) } }
                start(player.currentPosition)
            }
            choice.serverTrack != null -> {
                cueTrack = choice.serverTrack
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                startCues()
                refreshSubtitles()
            }
            choice.isOff -> {
                cueTrack = null
                startCues()
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                refreshSubtitles()
            }
            choice.group != null -> {
                val group = player.currentTracks.groups.getOrNull(choice.group) ?: return
                cueTrack = null
                startCues()
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, choice.track)).build()
            }
        }
    }

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
                updateUpNext()
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

    /** From the credits if the server marked them, else the last 30 seconds or 8%, whichever starts later. */
    private fun updateUpNext() {
        val duration = player.duration.takeIf { it > 0 } ?: return
        val runTicks = duration * TICKS_PER_MS
        val creditsStart = segments.firstOrNull { it.type == MediaSegmentType.OUTRO }?.startTicks
        val threshold = creditsStart ?: maxOf(runTicks - 30_000 * TICKS_PER_MS, (runTicks * 0.92).toLong())
        val offer = nextEpisodeId != null && player.currentPosition * TICKS_PER_MS >= threshold
        if (offer != _state.value.upNextOffered) _state.update { it.copy(upNextOffered = offer) }
    }

    /** Plays the next episode now, from Play Next Episode: this one stops here. */
    fun playNextNow() {
        val next = nextEpisodeId ?: return
        reportStopped()
        _playNext.tryEmit(next)
    }

    private fun onEnded() {
        if (currentPlaylistChannel != null) { tuneNext(); return }
        if (readOnly) _finished.tryEmit(Unit)
        reportStopped()
        if (appSettings.autoPlayNext) nextEpisodeId?.let { _playNext.tryEmit(it) }
    }

    private fun reportProgress() {
        offline?.let { downloads.notePosition(it.id, player.currentPosition * TICKS_PER_MS) }
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
        offline?.let { downloads.notePosition(it.id, player.currentPosition * TICKS_PER_MS) }
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
        scrub?.release()
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
        const val HTTP_TIMEOUT_MS = 30_000
    }
}
