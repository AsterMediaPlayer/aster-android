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
    /** The episode ended and this one is next: its card holds the screen. */
    val upNext: BaseItemDto? = null,
    /** Seconds until the card plays it, while autoplay is on. */
    val countdown: Int? = null,
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
    /** The audio menu: the file's tracks in a direct play, the server's in a converted stream. */
    val audio: List<AudioChoice> = emptyList(),
    /** The subtitle menu: Off, the tracks the player can draw, and picture-based ones the server can burn in. */
    val subtitles: List<SubtitleChoice> = emptyList(),
    /** The line of the server's text subtitle track showing now, drawn by the app over the picture. */
    val cueText: String? = null,
    /** Playback keeps stalling: the lower qualities on offer, highest first; null while there's no offer. */
    val qualityOffer: List<Quality>? = null,
    /** Playback speed, 1 for normal. */
    val speed: Float = 1f,
    /** The Chromecast playing this, by name; null while it plays on this device. */
    val castingTo: String? = null,
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
    /** A disc menu's line of items: what plays after this one (an empty line plays nothing after it); null for the usual next episode. */
    private val followedBy: List<UUID>? = null,
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
        com.astermediaplayer.astermediaplayer.data.signedInMedia(repo.session!!.accessToken, HTTP_TIMEOUT_MS.toLong()),
        DefaultExtractorsFactory().let { if (assHandler != null) it.withAssMkvSupport(assParserFactory!!, assHandler) else it },
    ).also { if (assParserFactory != null) it.setSubtitleParserFactory(assParserFactory) }

    /** Plays a download from the device alone. */
    private val offlineSourceFactory = downloads.offlineMediaSourceFactory().also { if (assParserFactory != null) it.setSubtitleParserFactory(assParserFactory) }

    val localPlayer: ExoPlayer = ExoPlayer.Builder(
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

    /** Chromecast, on a phone with Google Play services; a TV box has none to offer. */
    val cast: CastSupport? =
        if (readOnly || context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) null else CastSupport.create(context)
    private var casting = false
    /** Where the picture was when last seen playing: a Chromecast is gone by the time its session reports closing. */
    private var lastPositionMs = 0L

    private val _activePlayer = MutableStateFlow<Player>(localPlayer)
    /** The player that has the picture: this device's, or the Chromecast's while a session is open. */
    val activePlayer: StateFlow<Player> = _activePlayer.asStateFlow()
    val player: Player get() = _activePlayer.value

    private val app = context.applicationContext as com.astermediaplayer.astermediaplayer.AsterApp

    /**
     * This device's player as the media session sees it (the notification, the lock screen, headphones, a watch). A channel
     * or live TV has no skipping or scrubbing there, as in the Apple apps, so everyone stays on the same moment.
     */
    private val sessionPlayer = object : androidx.media3.common.ForwardingPlayer(localPlayer) {
        override fun getAvailableCommands(): Player.Commands {
            val commands = super.getAvailableCommands()
            if (!_state.value.live && !_state.value.channel) return commands
            return commands.buildUpon().removeAll(
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            ).build()
        }

        override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
    }
    private var published = false

    /** Hands the video to the media session, once what it is (a channel or not) is known. */
    private fun publishSession() {
        if (published) return
        published = true
        app.videoPlayer = sessionPlayer
        app.startService(android.content.Intent(app, PlaybackService::class.java))
    }

    private fun withdrawSession() {
        if (!published) return
        published = false
        if (app.videoPlayer === sessionPlayer) app.videoPlayer = null
        runCatching { app.startService(android.content.Intent(app, PlaybackService::class.java)) }
    }

    private var item: BaseItemDto? = null
    /** Playing a download from the device: nothing here asks the server. */
    private var offline: com.astermediaplayer.astermediaplayer.data.downloads.DownloadRecord? = null
    private var mediaSourceId: String? = null
    private var playSessionId: String? = null
    private var playMethod = PlayMethod.DIRECT_PLAY
    private var segments: List<MediaSegmentDto> = emptyList()

    private val _themeMoments = MutableStateFlow<List<com.astermediaplayer.astermediaplayer.data.ThemeMoment>>(emptyList())
    /** The song cards for what's playing: an episode's openings and endings, or a music video's whole. */
    val themeMoments: StateFlow<List<com.astermediaplayer.astermediaplayer.data.ThemeMoment>> = _themeMoments.asStateFlow()

    /**
     * The episode's OP and ED chapters give the cards at once (an opening can start the episode); the plugin's themes fill in the song
     * when they come. A music video's card covers all of it. Never in the way of playback.
     */
    private fun loadThemeCards(dto: BaseItemDto) {
        _themeMoments.value = emptyList()
        if (!appSettings.themeCards) return
        viewModelScope.launch {
            when (dto.type) {
                BaseItemKind.EPISODE -> {
                    _themeMoments.value = com.astermediaplayer.astermediaplayer.data.ThemeCards.forEpisode(dto, emptyList(), segments)
                    android.util.Log.d("Aster", "Song cards: ${_themeMoments.value.size} from ${dto.chapters.orEmpty().size} chapters (${dto.chapters.orEmpty().joinToString { "${it.name.orEmpty()} ${it.startPositionTicks / 10_000_000}s" }}) and ${segments.size} markers (${segments.joinToString { "${it.type} ${it.startTicks / 10_000_000}-${it.endTicks / 10_000_000}s" }}); season ${dto.parentIndexNumber}")
                    // The plugin names the song; without it (no answer, as on Plex and Emby), AnimeThemes does, for anime.
                    val plugin = runCatching { repo.opEdThemes(dto.id) }.getOrNull()
                    val anime = _themeMoments.value.isNotEmpty() || dto.genres.orEmpty().any { it.equals("Anime", ignoreCase = true) }
                    val known = if (plugin == null && anime) runCatching { repo.knownThemes(dto) }.getOrNull().orEmpty() else emptyList()
                    if (!plugin.isNullOrEmpty() || known.isNotEmpty()) _themeMoments.value = com.astermediaplayer.astermediaplayer.data.ThemeCards.forEpisode(dto, plugin.orEmpty(), segments, known)
                }
                // A clip of a theme is its episode's own stretch, so it has the episode's card; a break's music video isn't a theme.
                BaseItemKind.MUSIC_VIDEO -> if (clip == null) {
                    val theme = runCatching { repo.opEdThemeForLibraryItem(dto.id) }.getOrNull()
                    val season = theme?.seasonId?.let { id -> runCatching { repo.item(parseUuidLoose(id)).name }.getOrNull() }
                    _themeMoments.value = com.astermediaplayer.astermediaplayer.data.ThemeCards.forMusicVideo(dto, theme, season)
                }
                else -> Unit
            }
        }
    }

    /** The plugin writes ids with or without dashes. */
    private fun parseUuidLoose(id: String): UUID =
        UUID.fromString(if (id.length == 32) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id)
    private var nextEpisodeId: UUID? = null
    private var tickJob: Job? = null
    private var reported = false
    private var stopped = false

    /** How long this start is taking, step by step, for a debug report. Null once it has been told. */
    private var startTiming: com.astermediaplayer.astermediaplayer.data.diagnostics.StartTiming? = null

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            startTiming?.let { it.mark("first picture"); com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog.note("Playback", it.summary()) }
            startTiming = null
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) startTiming?.mark("stream ready", once = true)
            if (playbackState == Player.STATE_READY) { hasStarted = true; seeking = false; _state.update { it.copy(loading = false) } }
            if (playbackState == Player.STATE_BUFFERING && player.currentPosition == 0L) _state.update { it.copy(loading = true) }
            if (playbackState == Player.STATE_ENDED) onEnded()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = reportProgress()

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).sampleMimeType to (g.isTrackSupported(it) to g.isTrackSelected(it)) } }
            android.util.Log.d("Aster", "Text tracks (mime, supported, selected): $text")
            refreshSubtitles()
            refreshAudio()
        }
        // What the player decodes for the picture-based tracks (PGS, VobSub), for checking they are being drawn.
        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            val pictures = cueGroup.cues.count { it.bitmap != null }
            if (pictures > 0) android.util.Log.d("Aster", "Picture subtitle: $pictures cue(s) at ${cueGroup.presentationTimeUs / 1000} ms, first ${cueGroup.cues.first { it.bitmap != null }.bitmap!!.let { "${it.width}x${it.height}" }}")
        }
        override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) = refreshSubtitles()

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) { seeking = true; reportProgress() }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            startTiming?.let { it.mark("error"); com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog.note("Playback", it.summary()) }
            startTiming = null
            com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog.lastPlayback += ("error" to error.errorCodeName)
            com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog.note(
                "Playback",
                "Failed: ${error.errorCodeName} (${error.cause?.javaClass?.simpleName ?: "no cause"}: ${error.cause?.message ?: error.message}) while ${playMethod}" +
                    if (forcedTranscode) ", already transcoding" else "",
            )
            // The streams tried in turn, each asking the server for something safer, as on the Apple apps.
            when {
                // A channel that fails to start (a tuner still being let go, an encode not ready) gets one fresh stream.
                _state.value.live && !liveRetried -> {
                    liveRetried = true
                    val live = liveStreamId
                    liveStreamId = null
                    viewModelScope.launch {
                        if (live != null) runCatching { repo.closeLiveStream(live) }
                        start(null)
                    }
                }
                // Direct play can fail on codecs this device lacks: the server's transcode.
                playMethod == PlayMethod.DIRECT_PLAY && !forcedTranscode -> {
                    forcedTranscode = true
                    start(player.currentPosition, forceTranscode = true)
                }
                // A transcode that copied the file's video or audio can still fail: a fresh H.264 and AAC encode.
                playMethod == PlayMethod.TRANSCODE && !mostCompatible && offline == null -> {
                    forcedTranscode = true
                    mostCompatible = true
                    start(player.currentPosition, forceTranscode = true)
                }
                else -> {
                    android.util.Log.e("Aster", "Playback failed", error)
                    _state.update { it.copy(loading = false, error = "This video couldn't be played on this device (${error.errorCodeName})") }
                }
            }
        }
    }
    private var forcedTranscode = false

    /** One playback attempt, for a debug report: what's played, from what file, and how (direct, or the server's transcode and why). */
    private fun playbackSummary(dto: BaseItemDto, source: org.jellyfin.sdk.model.api.MediaSourceInfo, url: String): String {
        val streams = source.mediaStreams.orEmpty()
        val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }
        val audio = streams.filter { it.type == MediaStreamType.AUDIO }
        val reasons = Regex("TranscodeReasons=([^&]+)").find(url)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog.lastPlayback = buildMap {
            put("type", dto.type.toString())
            put("method", if (playMethod == PlayMethod.DIRECT_PLAY) "direct play" else "transcode")
            reasons?.let { put("transcodeReasons", it) }
            source.container?.let { put("container", it) }
            video?.let { v ->
                v.codec?.let { put("videoCodec", it) }
                put("resolution", "${v.width}x${v.height}")
                v.bitDepth?.let { put("bitDepth", it.toString()) }
                (v.videoRangeType ?: v.videoRange)?.let { put("videoRange", it.toString()) }
            }
            if (audio.isNotEmpty()) put("audioCodec", audio.joinToString("/") { "${it.codec} ${it.channels ?: "?"}ch" })
            source.bitrate?.let { put("bitrateKbps", (it / 1000).toString()) }
            if (_state.value.live) put("live", "true")
            if (offline != null) put("download", "true")
            if (cast?.isConnected == true) put("casting", "true")
        }
        return buildList {
            add("${dto.type}")
            add(if (playMethod == PlayMethod.DIRECT_PLAY) "direct play" else "transcode" + (reasons?.let { " ($it)" } ?: ""))
            source.container?.let { add("container $it") }
            video?.let { add("video ${it.codec} ${it.width}x${it.height} ${it.bitDepth ?: "?"}-bit ${it.videoRangeType ?: it.videoRange ?: ""}".trim()) }
            if (audio.isNotEmpty()) add("audio " + audio.joinToString("/") { "${it.codec} ${it.channels ?: "?"}ch" })
            source.bitrate?.let { add("${it / 1000} kbps") }
            if (_state.value.live) add("live")
            if (offline != null) add("from a download")
            if (cast?.isConnected == true) add("casting")
        }.joinToString(", ")
    }
    /** The last stream tried: one encoded afresh as H.264 and AAC, copying nothing from the file. */
    private var mostCompatible = false
    private var liveRetried = false

    /** From the error's Retry: the stream asked for again from where it stopped, starting over with the best one. */
    fun retry() {
        val at = if (hasStarted) player.currentPosition else null
        resetFallback()
        start(at)
    }

    private fun resetFallback() {
        forcedTranscode = false
        mostCompatible = false
        liveRetried = false
    }

    /** Intros already skipped automatically, so seeking back into one plays it. */
    private val autoSkipped = mutableSetOf<UUID>()

    /** The server's picture-based subtitle burned into the video (PGS, VobSub), when the viewer chose one. */
    private var burnedIn: Int? = null
    /** After a burned-in track is taken off, the stream is asked for with none, so the server doesn't burn its default. */
    private var noBurnIn = false
    /** A track to select once the new stream's tracks are known (what was picked while a burned-in track was showing). */
    private var pendingPick: Triple<String?, String?, String?>? = null
    private var currentSource: org.jellyfin.sdk.model.api.MediaSourceInfo? = null
    /** The audio track chosen, by the server's index; null for the server's default, which follows the viewer's preferred language. */
    private var audioIndex: Int? = null
    /** Whether that track (or the default) has been selected in this stream's tracks yet; a converted stream carries only it. */
    private var audioApplied = false

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

    private val stallWatch = StallWatch()
    /** Playback has got going on this stream, so buffering from here on is a stall and not the start. */
    private var hasStarted = false
    /** A seek is in hand: the buffering after it is the viewer's doing, not the connection's. */
    private var seeking = false

    /** The frames shown above the timeline while scrubbing, when the server has made them for this title. */
    private var scrub: com.astermediaplayer.astermediaplayer.data.ScrubPreview? = null
    private var scrubFor: UUID? = null

    /** The picture for a point on the timeline, or null (the bubble then shows just the time). */
    suspend fun scrubFrame(seconds: Double): android.graphics.Bitmap? = scrub?.frame(seconds)

    private fun loadScrubPreview(forItem: UUID, mediaSourceId: String?) {
        if (scrubFor == forItem) return
        scrubFor = forItem
        scrub?.release(); scrub = null
        // Emby keeps its scrub frames in a BIF file; Jellyfin lists trickplay sheets on the item.
        if (repo.serverKind == com.astermediaplayer.astermediaplayer.data.emby.ServerKind.Emby) {
            mediaSourceId?.let { scrub = com.astermediaplayer.astermediaplayer.data.BifScrubPreview(repo, forItem, it) }
            return
        }
        viewModelScope.launch {
            val dto = repo.trickplayOf(forItem) ?: return@launch
            if (scrubFor == forItem) scrub = com.astermediaplayer.astermediaplayer.data.ScrubPreviewSource.create(repo, dto, mediaSourceId)
        }
    }

    fun setSpeed(speed: Float) {
        player.playbackParameters = androidx.media3.common.PlaybackParameters(speed)
        _state.update { it.copy(speed = speed) }
    }

    /** Moves playback between this device and the Chromecast, from where it is, with the stream the new screen can play. */
    private fun setCasting(on: Boolean) {
        val support = cast ?: return
        if (on == casting) return
        val position = if (on) player.currentPosition else lastPositionMs
        player.removeListener(listener)
        player.stop()
        casting = on
        _activePlayer.value = if (on) support.player else localPlayer
        player.addListener(listener)
        _state.update { it.copy(castingTo = if (on) support.deviceName else null) }
        start(position)
    }

    init {
        localPlayer.addListener(listener)
        assHandler?.init(localPlayer)
        cast?.let { support ->
            support.onSessionChanged = ::setCasting
            if (support.isConnected) {
                casting = true
                localPlayer.removeListener(listener)
                _activePlayer.value = support.player
                support.player.addListener(listener)
                _state.update { it.copy(castingTo = support.deviceName) }
            }
        }
        viewModelScope.launch {
            Qualities.getOrNull(appSettings.qualityIndex)?.let { q -> _state.update { it.copy(quality = q) } }
            start(null)
        }
    }

    private fun start(positionMs: Long?, forceTranscode: Boolean = false) {
        viewModelScope.launch {
            hasStarted = false
            audioApplied = false
            startTiming = com.astermediaplayer.astermediaplayer.data.diagnostics.StartTiming()
            _state.update { it.copy(loading = true, error = null, qualityOffer = null) }
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
                val saved = if (clip == null && playlistChannelId == null && !forceTranscode && !casting) downloads.playable(itemId) else null
                if (saved != null) {
                    val runtime = saved.runTimeTicks
                    val resume = positionMs ?: if (fromBeginning || (runtime > 0 && saved.positionTicks > runtime * 95 / 100)) 0L else saved.positionTicks / TICKS_PER_MS
                    offline = saved
                    segments = saved.segments.map { org.jellyfin.sdk.model.api.MediaSegmentDto(id = java.util.UUID.randomUUID(), itemId = itemId, type = if (it.intro) MediaSegmentType.INTRO else MediaSegmentType.OUTRO, startTicks = it.startTicks, endTicks = it.endTicks) }
                    localPlayer.setMediaSource(offlineSourceFactory.createMediaSource(downloads.mediaItem(saved)), resume.coerceAtLeast(0))
                    player.prepare()
                    player.playWhenReady = true
                    _state.update { it.copy(title = saved.title, subtitle = saved.subtitle?.takeIf { _ -> saved.isEpisode }, offline = true) }
                    startTicking()
                    return@launch
                }
                offline = null
                val dto = item ?: repo.item(itemId).also { item = it }
                startTiming?.mark("item")
                val isLive = dto.type == BaseItemKind.TV_CHANNEL
                val resumeMs = positionMs ?: clip?.startMs ?: scheduledStartMs ?: if (fromBeginning || isLive) 0L else ((dto.userData?.playbackPositionTicks ?: 0L) / TICKS_PER_MS)
                if (!readOnly && !isLive && item != null && segments.isEmpty()) segments = repo.segments(itemId)
                if (!readOnly && !isLive && nextEpisodeId == null) nextEpisodeId = if (followedBy != null) followedBy.firstOrNull() else findNextEpisode(dto)

                // A failed direct play usually means the device can't decode the source (4K, Dolby Vision, ...),
                // so the fallback transcode is capped to something every phone can handle.
                // Once direct play has failed on this device, every restart of the stream (a subtitle change, say) is a transcode too.
                val fallback = forceTranscode || forcedTranscode
                val maxBitrate = if (fallback) minOf(_state.value.quality.maxBitrate, FALLBACK_BITRATE) else _state.value.quality.maxBitrate
                val info = repo.playbackInfo(
                    itemId, resumeMs * TICKS_PER_MS, maxBitrate,
                    mediaSourceId = mediaSourceId,
                    audioStreamIndex = audioIndex,
                    // A burned-in track is made by the server as it encodes, so it can't be a direct play.
                    subtitleStreamIndex = burnedIn ?: if (noBurnIn) -1 else null,
                    forceTranscode = fallback || burnedIn != null,
                    mostCompatible = mostCompatible,
                    cast = casting,
                )
                startTiming?.mark("server's playback answer")
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
                }.let { if (casting) repo.withApiKey(it) else it }
                playMethod = if (direct) PlayMethod.DIRECT_PLAY else PlayMethod.TRANSCODE
                startTiming?.method = if (direct) "direct play" else "transcode"
                com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog.note("Playback", playbackSummary(dto, source, url))

                val externalText = source.mediaStreams.orEmpty()
                    .filter { it.type == MediaStreamType.SUBTITLE && it.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL && it.deliveryUrl != null }
                // A Chromecast draws the text tracks itself, from WebVTT files handed over with the stream, so the app draws none.
                cueTracks = if (casting) emptyList() else externalText
                cueTrack = when {
                    casting -> null
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
                val castDefault = if (casting && burnedIn == null && !noBurnIn) externalText.firstOrNull { it.index == source.defaultSubtitleStreamIndex }?.index else null
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, if (casting) castDefault == null else burnedIn != null || cueTrack != null).build()
                player.setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder().setTitle(dto.name).setSubtitle(if (dto.type == BaseItemKind.EPISODE) dto.seriesName else null)
                                // For the notification and the lock screen.
                                .setArtist(if (dto.type == BaseItemKind.EPISODE) dto.seriesName else null)
                                .setArtworkUri(repo.imageUrl(dto, org.jellyfin.sdk.model.api.ImageType.PRIMARY, 600)?.let(android.net.Uri::parse)).build(),
                        )
                        .setSubtitleConfigurations(
                            if (!casting) emptyList() else externalText.map {
                                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(repo.withApiKey(repo.absoluteUrl(it.deliveryUrl!!.replace(Regex("/Stream\\.[A-Za-z0-9]+"), "/Stream.vtt")))))
                                    .setMimeType(androidx.media3.common.MimeTypes.TEXT_VTT)
                                    .setLanguage(it.language)
                                    .setLabel(it.displayTitle ?: it.title ?: it.language)
                                    .setSelectionFlags(if (it.index == castDefault) C.SELECTION_FLAG_DEFAULT else 0)
                                    .build()
                            },
                        )
                        .build(),
                    // A Chromecast refuses a stream that starts part-way in, but takes a seek once it's playing.
                    if (casting) 0L else resumeMs.coerceAtLeast(0),
                )
                if (casting && resumeMs > 0) seekWhenReady(resumeMs)
                startTiming?.mark("stream asked for")
                player.prepare()
                player.playWhenReady = true
                startCues()
                refreshSubtitles()
                refreshAudio()
                loadThemeCards(dto)
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

    /** What the TV player shows while paused (see [com.astermediaplayer.astermediaplayer.data.PauseInfo]); asked afresh each pause. */
    suspend fun pauseInfo(): com.astermediaplayer.astermediaplayer.data.PauseInfo? {
        val dto = item ?: return null
        val s = _state.value
        val channel = currentPlaylistChannel?.let { ChannelTuner.channel(it) }
        return when {
            s.breakClip && channel != null -> {
                val next = channel.programmeAfterBreak()
                val source = next?.item?.source as? BaseItemDto
                val name = next?.item?.let { i -> if (i.isEpisode) listOfNotNull(i.seriesName ?: i.name, source?.let(::episodeCode)).joinToString(" · ") else i.name }
                com.astermediaplayer.astermediaplayer.data.PauseInfo.channelBreak(
                    channel.number, channel.name, channel.logoUrl(repo, 900),
                    name, next?.start?.let { java.time.Instant.ofEpochSecond(it.toLong()) }, source?.overview,
                )
            }
            s.live -> com.astermediaplayer.astermediaplayer.data.PauseInfo.live(dto, repo.airingNow(dto.id), repo.channelLogoUrl(dto, 900))
            else -> com.astermediaplayer.astermediaplayer.data.PauseInfo.title(
                dto, repo.imageUrl(dto, org.jellyfin.sdk.model.api.ImageType.LOGO, 900),
                player.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET && it > 0 }?.let { it / 1000.0 },
            )
        }
    }

    private fun episodeCode(item: BaseItemDto): String? = com.astermediaplayer.astermediaplayer.data.downloads.DownloadStore.episodeCode(item)

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
        resetFallback()
        audioIndex = null
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
        resetFallback()
        audioIndex = null
        itemId = channelId
        _channelId.value = channelId
        start(null)
    }

    fun setQuality(quality: Quality) {
        if (quality == _state.value.quality) return
        _state.update { it.copy(quality = quality) }
        resetFallback()
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

    // MARK: Audio

    private fun serverAudio(): List<MediaStream> =
        currentSource?.mediaStreams.orEmpty().filter { it.type == MediaStreamType.AUDIO && !it.isExternal }

    /**
     * Builds the audio menu. A direct play has every track in the file, which the player switches itself; they line up with
     * the server's in order, so as they arrive the one chosen, else the server's default (which follows the viewer's
     * preferred language), is selected, as the Apple apps do. A converted stream carries one track, so its menu is the server's.
     */
    private fun refreshAudio() {
        val streams = serverAudio()
        val choices = if (playMethod == PlayMethod.DIRECT_PLAY || offline != null) {
            val groups = player.currentTracks.groups.withIndex().filter { it.value.type == C.TRACK_TYPE_AUDIO }
            // The same tracks in the same order; when the counts differ they can't be matched up.
            val matched = offline == null && groups.size == streams.size
            if (matched && !audioApplied && groups.isNotEmpty()) {
                audioApplied = true
                val wanted = audioIndex ?: currentSource?.defaultAudioStreamIndex
                val group = groups.getOrNull(streams.indexOfFirst { it.index == wanted })?.value
                if (group != null && group.isSupported && !group.isSelected) {
                    // The menu is built again when the player has switched.
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
                    return
                }
            }
            groups.mapIndexedNotNull { n, (gi, group) ->
                if (!group.isSupported) return@mapIndexedNotNull null
                val format = group.getTrackFormat(0)
                val stream = if (matched) streams[n] else null
                AudioChoice(
                    stream?.displayTitle ?: AudioLabels.label(format.language, format.label, AudioLabels.codec(format.sampleMimeType), format.channelCount.takeIf { it > 0 }, n + 1),
                    selected = group.isSelected, group = gi, serverIndex = stream?.index,
                )
            }
        } else {
            val current = audioIndex ?: currentSource?.defaultAudioStreamIndex
            streams.mapIndexed { n, stream ->
                AudioChoice(
                    stream.displayTitle ?: AudioLabels.label(stream.language, stream.title, stream.codec?.uppercase(), stream.channels, n + 1),
                    selected = stream.index == current, serverIndex = stream.index,
                )
            }
        }
        if (choices != _state.value.audio) _state.update { it.copy(audio = choices) }
    }

    /** Takes a choice from the audio menu: switched by the player in a direct play, a new stream from here in a converted one. */
    fun selectAudio(choice: AudioChoice) {
        if (choice.selected) return
        // Kept, so a restart of the stream (a subtitle burned in, a fallback) keeps the track.
        audioIndex = choice.serverIndex ?: audioIndex
        val group = choice.group?.let { player.currentTracks.groups.getOrNull(it) }
        when {
            group != null -> player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
            choice.serverIndex != null -> start(player.currentPosition)
        }
    }

    fun skip() {
        _state.value.skip?.let { player.seekTo(it.targetMs) }
    }

    /** Seeks to [positionMs] the first time the player is ready, then lets go. */
    private fun seekWhenReady(positionMs: Long) {
        val target = player
        target.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_READY) return
                target.removeListener(this)
                target.seekTo(positionMs)
            }
        })
    }

    private fun startTicking() {
        publishSession()
        if (tickJob?.isActive == true) return
        tickJob = viewModelScope.launch {
            var sinceReport = 0
            while (true) {
                delay(500)
                if (player.playbackState == Player.STATE_READY) lastPositionMs = player.currentPosition
                updateSkipPrompt()
                updateUpNext()
                updateStall()
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
                label = skipLabel(it.type),
                targetMs = it.endTicks / TICKS_PER_MS,
            )
        }
        // Only intros skip themselves, as on the Apple apps: skipping the credits would pass the Up Next offer.
        if (segment != null && appSettings.autoSkipSegments && segment.type == MediaSegmentType.INTRO && autoSkipped.add(segment.id)) {
            player.seekTo(segment.endTicks / TICKS_PER_MS)
            return
        }
        if (prompt != _state.value.skip) _state.update { it.copy(skip = prompt) }
    }

    private fun skipLabel(type: MediaSegmentType): String = when (type) {
        MediaSegmentType.INTRO -> "Skip Intro"
        MediaSegmentType.OUTRO -> "Skip Credits"
        MediaSegmentType.RECAP -> "Skip Recap"
        MediaSegmentType.PREVIEW -> "Skip Preview"
        MediaSegmentType.COMMERCIAL -> "Skip Ad"
        else -> "Skip"
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
        countdownJob?.cancel()
        reportStopped()
        _playNext.tryEmit(next)
    }

    /**
     * Four times a second in the Apple apps, twice here: whether playback is being held up, for the offer of a lower
     * quality. Not the start, not a seek, and nothing for a download or a channel, where there's no quality to lower.
     */
    private fun updateStall() {
        if (!hasStarted || seeking || readOnly || _state.value.offline || _state.value.live || _state.value.qualityOffer != null) return
        val stalling = player.playWhenReady && player.playbackState == Player.STATE_BUFFERING
        if (stallWatch.note(stalling, android.os.SystemClock.elapsedRealtime())) {
            val lower = Qualities.filter { it.maxBitrate < _state.value.quality.maxBitrate }.take(3)
            if (lower.isNotEmpty()) _state.update { it.copy(qualityOffer = lower) }
        }
    }

    /** Plays on from here at the quality picked from the stall offer. */
    fun chooseOfferedQuality(quality: Quality) {
        _state.update { it.copy(qualityOffer = null) }
        setQuality(quality)
    }

    /** Stays as it is; the offer holds off for a while. */
    fun keepTrying() {
        _state.update { it.copy(qualityOffer = null) }
        stallWatch.declined(android.os.SystemClock.elapsedRealtime())
    }

    private fun onEnded() {
        if (currentPlaylistChannel != null) { tuneNext(); return }
        if (readOnly) _finished.tryEmit(Unit)
        reportStopped()
        val next = nextEpisodeId ?: return
        // A line of titles from a disc menu plays straight on, as a disc does.
        if (followedBy != null) { if (appSettings.autoPlayNext) _playNext.tryEmit(next); return }
        // The next episode's card holds the screen, counting down to play it when autoplay is on, as on the Apple apps.
        viewModelScope.launch {
            val episode = runCatching { repo.item(next) }.getOrNull()
            if (episode == null) { if (appSettings.autoPlayNext) _playNext.tryEmit(next); return@launch }
            val autoplay = appSettings.autoPlayNext
            _state.update { it.copy(upNext = episode, countdown = if (autoplay) UP_NEXT_SECONDS else null, upNextOffered = false, skip = null) }
            if (autoplay) countdownJob = viewModelScope.launch {
                for (remaining in UP_NEXT_SECONDS - 1 downTo 0) {
                    delay(1_000)
                    _state.update { it.copy(countdown = remaining) }
                }
                playNextNow()
            }
        }
    }

    private var countdownJob: Job? = null

    /** Done on the Up Next card: no next episode, and the player closes. */
    fun dismissUpNext() {
        countdownJob?.cancel()
        _state.update { it.copy(upNext = null, countdown = null) }
        _finished.tryEmit(Unit)
    }

    private fun reportProgress() {
        offline?.let { downloads.notePosition(it.id, player.currentPosition * TICKS_PER_MS) }
        if (!reported || stopped) return
        val info = PlaybackProgressInfo(
            canSeek = true, itemId = itemId, mediaSourceId = mediaSourceId, playSessionId = playSessionId, liveStreamId = liveStreamId,
            playMethod = playMethod, isPaused = !player.isPlaying, isMuted = false,
            positionTicks = player.currentPosition * TICKS_PER_MS,
            // Which track is playing, so the server's dashboard shows it.
            audioStreamIndex = _state.value.audio.firstOrNull { it.selected }?.serverIndex,
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

    /** Ends a player no screen owns (Live TV's mini player keeps one), as leaving its screen would. */
    fun release() {
        onCleared()
        viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    override fun onCleared() {
        // Left before the picture came: how far the start had got, which is the story of a start that never finished.
        startTiming?.let { com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog.note("Playback", it.summary(leftEarly = true)) }
        startTiming = null
        scrub?.release()
        reportStopped()
        withdrawSession()
        tickJob?.cancel()
        player.removeListener(listener)
        cast?.release()
        localPlayer.release()
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
        /** How long the Up Next card counts down, as on the Apple apps. */
        const val UP_NEXT_SECONDS = 10
        const val HTTP_TIMEOUT_MS = 30_000
    }
}
