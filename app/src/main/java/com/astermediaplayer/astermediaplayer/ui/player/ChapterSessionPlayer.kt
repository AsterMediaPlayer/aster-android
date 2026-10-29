package com.astermediaplayer.astermediaplayer.ui.player

import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The audiobook player as the media session sees it: the notification, lock screen, headphones and a car's display
 * show the chapter's progress, as the app's own bar does, not the whole file's, and scrubbing there moves within the
 * chapter. Skipping back or ahead carries into the part before or after. As on the Apple apps.
 *
 * [bounds] gives the current chapter's start and end in the part playing, in seconds, or null to show the whole part.
 */
@UnstableApi
class ChapterSessionPlayer(
    player: Player,
    private val bounds: () -> Pair<Double, Double>?,
    private val onSkip: (Int) -> Unit,
) : ForwardingSimpleBasePlayer(player) {

    /** A new chapter: its own bar from zero. */
    fun chapterChanged() = invalidateState()

    override fun getState(): State {
        val state = super.getState()
        val (start, end) = bounds() ?: return state
        val startMs = (start * 1000).toLong()
        val lengthMs = ((end - start) * 1000).toLong().coerceAtLeast(0)
        val index = state.currentMediaItemIndex
        val playlist = state.playlist.mapIndexed { i, item ->
            if (i != index) item
            else item.buildUpon()
                .setDurationUs(lengthMs * 1000)
                .setDefaultPositionUs(0)
                .setPeriods(item.periods.map { it.buildUpon().setDurationUs(lengthMs * 1000).build() })
                .build()
        }
        val exo = player
        return state.buildUpon()
            .setPlaylist(playlist)
            .setContentPositionMs { (exo.currentPosition - startMs).coerceIn(0, lengthMs) }
            .setContentBufferedPositionMs { (exo.bufferedPosition - startMs).coerceIn(0, lengthMs) }
            .build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: @Player.Command Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_BACK -> { onSkip(-AudiobookPlayer.SKIP_BACK_SECONDS.toInt()); return Futures.immediateVoidFuture() }
            Player.COMMAND_SEEK_FORWARD -> { onSkip(AudiobookPlayer.SKIP_FORWARD_SECONDS.toInt()); return Futures.immediateVoidFuture() }
        }
        val start = bounds()?.first
        if (start != null && positionMs != C.TIME_UNSET && mediaItemIndex == player.currentMediaItemIndex) {
            player.seekTo(mediaItemIndex, (start * 1000).toLong() + positionMs)
            return Futures.immediateVoidFuture()
        }
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }
}
