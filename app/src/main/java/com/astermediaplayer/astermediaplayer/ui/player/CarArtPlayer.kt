package com.astermediaplayer.astermediaplayer.ui.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import com.astermediaplayer.astermediaplayer.data.ArtworkProvider

/**
 * The audio player as the media session shows it to other apps. Android Auto and a car's own media app show a cover
 * only from a content address, so the player's covers (the server's pictures, or a downloaded book's file) are handed
 * over through [ArtworkProvider]. The player itself keeps its real addresses, which the app and Cast use.
 *
 * For a book, skipping between its files isn't offered: the car puts back 15 and ahead 30 seconds there instead (see
 * [CarButtons]), as CarPlay does.
 *
 * With nothing loaded it shows as idle, which a car reads as nothing to play and so shows no player. An empty player
 * a car has asked to prepare (as it does on connecting) would otherwise count as finished, and the car would show
 * play and skip buttons with no song.
 */
@UnstableApi
class CarArtPlayer(
    private val context: Context,
    player: Player,
    private val isBook: Boolean = false,
    /** Asked to get ready with nothing loaded: a car connecting. What was playing last is loaded, paused, instead. */
    private val onPrepareEmpty: (() -> Unit)? = null,
) : ForwardingPlayer(player) {
    private val listeners = mutableMapOf<Player.Listener, Player.Listener>()

    override fun getMediaMetadata(): MediaMetadata = rewrite(super.getMediaMetadata())
    override fun getCurrentMediaItem(): MediaItem? = super.getCurrentMediaItem()?.let(::rewrite)
    override fun getMediaItemAt(index: Int): MediaItem = rewrite(super.getMediaItemAt(index))

    override fun getPlaybackState(): Int = shown(super.getPlaybackState())

    override fun prepare() {
        if (super.getMediaItemCount() == 0 && onPrepareEmpty != null) onPrepareEmpty.invoke() else super.prepare()
    }

    override fun getAvailableCommands(): Player.Commands = rewrite(super.getAvailableCommands())
    override fun isCommandAvailable(command: Int): Boolean = !(isBook && command in TRACK_SKIPS) && super.isCommandAvailable(command)

    override fun addListener(listener: Player.Listener) {
        val wrapped = Rewriting(listener)
        listeners[listener] = wrapped
        super.addListener(wrapped)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listeners.remove(listener) ?: listener)
    }

    /** Idle while there's nothing to play (see above), else the player's own state. */
    private fun shown(state: Int): Int = if (super.getMediaItemCount() == 0) Player.STATE_IDLE else state

    private fun rewrite(commands: Player.Commands): Player.Commands =
        if (isBook) commands.buildUpon().removeAll(*TRACK_SKIPS).build() else commands

    private fun rewrite(item: MediaItem): MediaItem {
        val metadata = rewrite(item.mediaMetadata)
        return if (metadata === item.mediaMetadata) item else item.buildUpon().setMediaMetadata(metadata).build()
    }

    private fun rewrite(metadata: MediaMetadata): MediaMetadata {
        val art = metadata.artworkUri ?: return metadata
        val shared = shareable(art) ?: return metadata
        return metadata.buildUpon().setArtworkUri(shared).build()
    }

    /** A cover's address that another app can open, or null when it already can (or it isn't one we can share). */
    private fun shareable(uri: Uri): Uri? = when (uri.scheme?.lowercase()) {
        "http", "https", "file" -> ArtworkProvider.uriFor(context, uri.toString())
        else -> null
    }

    /**
     * Hands every event on, with covers and commands as [rewrite] gives them. Each one is spelled out: the listener's
     * methods are Java default methods, which Kotlin's `by` delegation doesn't forward, and a dropped event (playing,
     * ready) leaves the car showing a song as still loading.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private inner class Rewriting(private val inner: Player.Listener) : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = inner.onEvents(player, events)
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = inner.onTimelineChanged(timeline, reason)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = inner.onMediaItemTransition(mediaItem?.let(::rewrite), reason)
        override fun onTracksChanged(tracks: Tracks) = inner.onTracksChanged(tracks)
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = inner.onMediaMetadataChanged(rewrite(mediaMetadata))
        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = inner.onPlaylistMetadataChanged(mediaMetadata)
        override fun onIsLoadingChanged(isLoading: Boolean) = inner.onIsLoadingChanged(isLoading)
        override fun onLoadingChanged(isLoading: Boolean) = inner.onLoadingChanged(isLoading)
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = inner.onAvailableCommandsChanged(rewrite(availableCommands))
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = inner.onTrackSelectionParametersChanged(parameters)
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = inner.onPlayerStateChanged(playWhenReady, shown(playbackState))
        override fun onPlaybackStateChanged(playbackState: Int) = inner.onPlaybackStateChanged(shown(playbackState))
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = inner.onPlayWhenReadyChanged(playWhenReady, reason)
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = inner.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
        override fun onIsPlayingChanged(isPlaying: Boolean) = inner.onIsPlayingChanged(isPlaying)
        override fun onRepeatModeChanged(repeatMode: Int) = inner.onRepeatModeChanged(repeatMode)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = inner.onShuffleModeEnabledChanged(shuffleModeEnabled)
        override fun onPlayerError(error: PlaybackException) = inner.onPlayerError(error)
        override fun onPlayerErrorChanged(error: PlaybackException?) = inner.onPlayerErrorChanged(error)
        override fun onPositionDiscontinuity(reason: Int) = inner.onPositionDiscontinuity(reason)
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
            inner.onPositionDiscontinuity(oldPosition, newPosition, reason)
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = inner.onPlaybackParametersChanged(playbackParameters)
        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = inner.onSeekBackIncrementChanged(seekBackIncrementMs)
        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = inner.onSeekForwardIncrementChanged(seekForwardIncrementMs)
        override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) = inner.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
        override fun onAudioSessionIdChanged(audioSessionId: Int) = inner.onAudioSessionIdChanged(audioSessionId)
        override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = inner.onAudioAttributesChanged(audioAttributes)
        override fun onVolumeChanged(volume: Float) = inner.onVolumeChanged(volume)
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = inner.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = inner.onDeviceInfoChanged(deviceInfo)
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = inner.onDeviceVolumeChanged(volume, muted)
        override fun onVideoSizeChanged(videoSize: VideoSize) = inner.onVideoSizeChanged(videoSize)
        override fun onSurfaceSizeChanged(width: Int, height: Int) = inner.onSurfaceSizeChanged(width, height)
        override fun onRenderedFirstFrame() = inner.onRenderedFirstFrame()
        override fun onCues(cues: List<Cue>) = inner.onCues(cues)
        override fun onCues(cueGroup: CueGroup) = inner.onCues(cueGroup)
        override fun onMetadata(metadata: Metadata) = inner.onMetadata(metadata)
    }

    private companion object {
        val TRACK_SKIPS = intArrayOf(
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        )
    }
}
