package com.astermediaplayer.astermediaplayer.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.channels.BugCorner
import com.astermediaplayer.astermediaplayer.data.channels.ChannelTuner
import kotlinx.coroutines.delay

/**
 * A channel's logo, faint in a corner while it plays, as a TV network's bug. Shown for a channel tagged `aster:bug=on`
 * with a logo, in the corner and at the size its `bug-location=` and `bug-size=` tags ask for. It steps aside for a
 * break's clips, as a network's bug does for the commercials, and comes back with the next programme. The Kotlin
 * counterpart of `ChannelBug`.
 */
@Composable
fun ChannelBug(channelId: String?, breakClip: Boolean, programKey: String) {
    val app = LocalContext.current.applicationContext as AsterApp
    val channel = channelId?.let(ChannelTuner::channel)?.takeIf { it.showsBug } ?: return
    val url = channel.logoUrl(app.repository, 512) ?: return
    // Faded out, `bug-hide=` seconds after tuning in or a programme starting.
    var faded by remember(channel.id, programKey) { mutableStateOf(false) }
    LaunchedEffect(channel.id, programKey) {
        faded = false
        if (channel.bugHideAfter > 0) { delay(channel.bugHideAfter * 1000L); faded = true }
    }
    val alpha by animateFloatAsState(if (faded || breakClip) 0f else channel.bugOpacity / 100f, tween(if (faded) 1000 else 600), label = "bug")
    val width = 128.dp * (channel.bugSize / 100f)
    val corner = channel.bugCorner
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // From the picture's edges, as fractions of its size, so the bug keeps clear of the edge on any screen.
        val insetX = maxWidth * 0.094f
        val insetY = maxHeight * 0.12f
        val x = if (corner.isLeft) insetX else maxWidth - insetX - width
        val y = if (corner.isTop) insetY else maxHeight - insetY - width
        AsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Fit,
            alignment = when (corner) { BugCorner.TopLeft -> Alignment.TopStart; BugCorner.TopRight -> Alignment.TopEnd; BugCorner.BottomLeft -> Alignment.BottomStart; BugCorner.BottomRight -> Alignment.BottomEnd },
            modifier = Modifier.offset(x, y).size(width).alpha(alpha),
        )
    }
}
