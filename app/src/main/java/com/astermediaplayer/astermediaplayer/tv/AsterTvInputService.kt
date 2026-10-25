package com.astermediaplayer.astermediaplayer.tv

import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.net.Uri
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.channels.ChannelTuner
import com.astermediaplayer.astermediaplayer.ui.player.Qualities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/**
 * Aster's channels as an Android TV input: the system's live guide lists them (see [TvGuide]) and, tuned to one, asks for a
 * picture. What's on is the same as in the Channels tab, joined part-way in where the schedule has it.
 */
class AsterTvInputService : TvInputService() {
    override fun onCreateSession(inputId: String): Session = AsterSession(this)

    private class AsterSession(private val context: Context) : Session(context) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var player: ExoPlayer? = null
        private var surface: Surface? = null
        private var tuning: Job? = null
        private var tuned: Uri? = null

        override fun onRelease() { tuning?.cancel(); player?.release(); player = null; scope.cancel() }

        override fun onSetSurface(surface: Surface?): Boolean { this.surface = surface; player?.setVideoSurface(surface); return true }

        override fun onSetStreamVolume(volume: Float) { player?.volume = volume }

        override fun onSetCaptionEnabled(enabled: Boolean) {}

        override fun onTune(channelUri: Uri): Boolean {
            tuned = channelUri
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
            tuning?.cancel()
            tuning = scope.launch { runCatching { play(channelUri) }.onFailure { notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN) } }
            return true
        }

        private fun channelIdOf(uri: Uri): String? =
            context.contentResolver.query(uri, arrayOf(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

        private suspend fun play(channelUri: Uri) {
            val app = context.applicationContext as AsterApp
            val repo = app.repository
            TvGuide.ensureChannels(context)
            val channelId = channelIdOf(channelUri) ?: error("unknown channel")
            // What's on now, from as far in as it's got (the next programme from its start when this one is nearly over).
            val live = ChannelTuner.live(channelId) ?: error("nothing on")
            val itemId = (live.item.source as? BaseItemDto)?.id ?: parse(live.item.id)
            val startMs = live.startTicks / 10_000L
            val info = repo.playbackInfo(itemId, live.startTicks, Qualities.first().maxBitrate)
            val source = info.mediaSources.firstOrNull() ?: error("nothing to play")
            val url = when {
                source.supportsDirectPlay -> repo.directStreamUrl(itemId, source.id!!, info.playSessionId, source.container)
                source.transcodingUrl != null -> repo.absoluteUrl(source.transcodingUrl!!)
                else -> error("can't stream")
            }
            val exo = player ?: ExoPlayer.Builder(context).setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("X-Emby-Token" to repo.session!!.accessToken))),
            ).build().also { p ->
                player = p
                p.setVideoSurface(surface)
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) notifyVideoAvailable()
                        // What was on has finished: tune again, which joins what's on now.
                        if (state == Player.STATE_ENDED) tuned?.let { onTune(it) }
                    }
                    override fun onPlayerError(error: PlaybackException) { notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN) }
                })
            }
            exo.setMediaItem(MediaItem.fromUri(url), startMs.coerceAtLeast(0))
            exo.prepare()
            exo.playWhenReady = true
        }

        private fun parse(id: String): UUID =
            UUID.fromString(if (id.length == 32) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id)
    }
}
