package com.astermediaplayer.astermediaplayer.ui.player

import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.OpeningEnding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * The openings and endings the Music tab plays, one after another, and where it's got to: the Kotlin counterpart of the
 * Apple apps' `ThemeDeck`. A plugin theme is its own file; a chapter clip is that stretch of its episode's stream.
 */
@OptIn(UnstableApi::class)
class ThemeDeck(private val repo: AsterRepository, private val themes: ThemePlayer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var current by mutableStateOf<OpeningEnding?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    /** Into the current one, and its length, in milliseconds; a clip's count from the clip's start, not the episode's. */
    var elapsedMs by mutableLongStateOf(0)
        private set
    var durationMs by mutableLongStateOf(0)
        private set
    /** A chapter clip's stream is being asked for. */
    var loading by mutableStateOf(false)
        private set
    /** The player, once something is loaded; the panel draws its picture. */
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    /** The show it was started from, for its picture. */
    var showItem: BaseItemDto? = null
        private set

    private var queue = emptyList<OpeningEnding>()
    private var startMs = 0L
    private var endMs = 0L
    private var load: Job? = null
    private var ticker: Job? = null

    val isActive: Boolean get() = current != null

    /** Whether there's a picture: an MP4 from the plugin, or an episode. */
    val hasVideo: Boolean get() = when (val c = current) {
        is OpeningEnding.Theme -> c.theme.videoMp4Url != null
        is OpeningEnding.Chapter -> true
        null -> false
    }

    /** Plays [entry], then the rest of [queue] after it. */
    fun play(entry: OpeningEnding, queue: List<OpeningEnding>, show: BaseItemDto) {
        val wasActive = isActive
        if (!wasActive) themes.suspend()
        load?.cancel()
        release()
        this.queue = queue
        showItem = show
        current = entry
        elapsedMs = 0
        durationMs = 0
        isPlaying = true
        when (entry) {
            is OpeningEnding.Theme -> {
                val path = entry.theme.playablePath ?: return
                startMs = 0; endMs = Long.MAX_VALUE
                begin(repo.opEdMediaUrl(path), 0)
            }
            is OpeningEnding.Chapter -> {
                val clip = entry.clip
                loading = true
                load = scope.launch {
                    val stream = themes.clipStream(clip)
                    if (current !== entry) return@launch
                    loading = false
                    if (stream == null) { next(); return@launch }
                    startMs = stream.start; endMs = stream.end
                    durationMs = stream.end - stream.start
                    begin(stream.url, stream.start)
                }
            }
        }
    }

    private fun begin(url: String, from: Long) {
        val p = themes.newPlayer().apply {
            setMediaItem(MediaItem.fromUri(url), from)
            prepare()
            playWhenReady = true
        }
        player = p
        ticker = scope.launch {
            while (true) {
                delay(250)
                val position = p.currentPosition
                elapsedMs = (position - startMs).coerceAtLeast(0)
                if (current is OpeningEnding.Theme && p.duration > 0) durationMs = p.duration
                isPlaying = p.playWhenReady && p.playbackState != Player.STATE_ENDED
                // A theme ends with its file; a clip, where its stretch of the episode does.
                if (p.playbackState == Player.STATE_ENDED || (endMs != Long.MAX_VALUE && position >= endMs)) { next(); return@launch }
            }
        }
    }

    fun togglePlaying() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        isPlaying = p.playWhenReady
    }

    /** The next one that plays without asking (not a spoiler or NSFW). */
    fun next() {
        val upcoming = neighbour(after = true)
        val show = showItem
        if (upcoming == null || show == null) stop() else play(upcoming, queue, show)
    }

    /** Back to the start, or a few seconds in, back to the one before. */
    fun previous() {
        val earlier = neighbour(after = false)
        val show = showItem
        if (elapsedMs > 3_000 || earlier == null || show == null) {
            player?.seekTo(startMs)
            elapsedMs = 0
        } else play(earlier, queue, show)
    }

    fun stop() {
        val wasActive = isActive
        load?.cancel()
        release()
        current = null
        isPlaying = false
        loading = false
        elapsedMs = 0
        durationMs = 0
        if (wasActive) themes.resume()
    }

    /** Quiet while the app is in the background. */
    fun pause() { player?.playWhenReady = false; isPlaying = false }

    private fun release() {
        ticker?.cancel()
        ticker = null
        player?.release()
        player = null
    }

    private fun neighbour(after: Boolean): OpeningEnding? {
        val index = queue.indexOf(current).takeIf { it >= 0 } ?: return null
        val candidates = if (after) queue.drop(index + 1) else queue.take(index).reversed()
        return candidates.firstOrNull(::playsUnasked)
    }

    /** Plays without asking: not marked as a spoiler or NSFW, and playable (a chapter clip always is). */
    private fun playsUnasked(entry: OpeningEnding): Boolean = when (entry) {
        is OpeningEnding.Theme -> entry.theme.playablePath != null && !entry.theme.spoiler && !entry.theme.nsfw
        is OpeningEnding.Chapter -> true
    }
}
