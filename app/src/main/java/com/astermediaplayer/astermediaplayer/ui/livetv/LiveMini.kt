package com.astermediaplayer.astermediaplayer.ui.livetv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.ui.player.PlayerViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import java.util.UUID

/**
 * The Live TV channel playing, full screen or in the header's mini player, like the Channels and YouTube TV apps. It outlives
 * the full-screen player, so closing that while the channel plays leaves it going in the Live TV header; selecting it there goes
 * full screen again. Closing full screen while paused, the mini player's close, or leaving Live TV ends it. The Kotlin
 * counterpart of the Apple apps' `live` on their Live TV page.
 */
class LiveMini(private val app: AsterApp) {
    /** The channel's player, kept here rather than by the full-screen player's page. */
    var player: PlayerViewModel? by mutableStateOf(null)
        private set

    /** Whether the full-screen player is showing it. */
    var fullScreen by mutableStateOf(false)

    /**
     * Live TV opened a page over itself (the channel full screen, a programme's page): its going away doesn't end the channel.
     * Set back when Live TV shows again.
     */
    var away = false

    /** Plays [channelId]: on the channel already playing, a change of channel; otherwise a new player. */
    fun tune(channelId: UUID): PlayerViewModel {
        player?.let { it.switchChannel(channelId); it.player.play(); return it }
        return PlayerViewModel(app, app.repository, app.settings, channelId).also { player = it }
    }

    /** Ends the channel and closes the server's live stream. */
    fun stop() {
        player?.release()
        player = null
        fullScreen = false
    }
}

/** The channel's picture in the Live TV header, beside the title; selecting it goes full screen. On a phone, an x ends it. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@androidx.compose.runtime.Composable
fun LiveMiniPlayer(player: PlayerViewModel, onExpand: () -> Unit, onClose: () -> Unit) {
    val tv = com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv.current
    val width = if (tv) (352f * com.astermediaplayer.astermediaplayer.ui.theme.Lockup.tvUnit).dp else 160.dp
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(if (tv) 12.dp else 8.dp)
    // Put away with the app, it pauses, as the full-screen player does. The activity's lifecycle, not the page's: a page opened
    // over Live TV (the channel full screen) stops the page but must leave the channel playing.
    val lifecycle = (androidx.activity.compose.LocalActivity.current as? androidx.lifecycle.LifecycleOwner)?.lifecycle ?: androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, player) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.width(width).aspectRatio(16f / 9f)
            .then(if (tv) androidx.compose.ui.Modifier.tvFocus(12.dp) else androidx.compose.ui.Modifier)
            .clip(shape).background(androidx.compose.ui.graphics.Color.Black)
            .clickable(onClickLabel = "Watch full screen", onClick = onExpand)
            .semantics { contentDescription = "Now playing. Select to watch full screen." },
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    useController = false
                    this.player = player.player
                }
            },
            modifier = androidx.compose.ui.Modifier.matchParentSize(),
            // Taken back from the full-screen player each time this shows.
            update = { it.player = player.player },
            onRelease = { it.player = null },
        )
        if (!tv) {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(4.dp).size(24.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                    .clickable(onClickLabel = "Stop", onClick = onClose),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Close, "Stop watching", tint = androidx.compose.ui.graphics.Color.White, modifier = androidx.compose.ui.Modifier.size(16.dp))
            }
        }
    }
}
