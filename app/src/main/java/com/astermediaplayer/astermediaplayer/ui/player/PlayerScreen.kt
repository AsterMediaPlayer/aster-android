package com.astermediaplayer.astermediaplayer.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import java.util.UUID

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onPlayNext: (UUID) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    var controlsVisible by remember { mutableStateOf(true) }
    var qualityMenu by remember { mutableStateOf(false) }
    var chapterMenu by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    LaunchedEffect(viewModel) { viewModel.playNext.collect(onPlayNext) }
    LaunchedEffect(viewModel) { viewModel.finished.collect { onBack() } }

    // Landscape, full-screen, screen-on while playing; everything is restored when leaving.
    DisposableEffect(activity) {
        val window = activity?.window
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            if (previous != null) activity.requestedOrientation = previous
            if (window != null) WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Pause when the app goes to the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.player.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    player = viewModel.player
                    keepScreenOn = true
                    playerView = this
                    setShowSubtitleButton(true)
                    viewModel.assHandler?.let { subtitleView?.addView(AssSubtitleView(ctx, it)) }
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { controlsVisible = it == android.view.View.VISIBLE })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (state.loading && state.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        if (controlsVisible) {
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopStart).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(state.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    state.subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium) }
                }
                if (state.chapters.isNotEmpty()) Box {
                    IconButton(onClick = { chapterMenu = true }) { Icon(Icons.Default.Bookmarks, "Chapters", tint = Color.White) }
                    DropdownMenu(expanded = chapterMenu, onDismissRequest = { chapterMenu = false }) {
                        state.chapters.forEachIndexed { index, chapter ->
                            DropdownMenuItem(
                                text = { Text((if (index == state.chapterIndex) "✓ " else "") + chapter.name) },
                                onClick = { chapterMenu = false; viewModel.seekToChapter(chapter) },
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { qualityMenu = true }) { Icon(Icons.Default.HighQuality, "Quality", tint = Color.White) }
                    DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                        Qualities.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(if (option == state.quality) "✓ ${option.label}" else option.label) },
                                onClick = { qualityMenu = false; viewModel.setQuality(option) },
                            )
                        }
                    }
                }
            }
        }

        if (state.live) {
            val channels by viewModel.channels.collectAsStateWithLifecycle()
            val channelId by viewModel.channelId.collectAsStateWithLifecycle()
            ChannelDialOverlay(
                channels = channels, playingId = channelId, controlsShown = controlsVisible,
                // The controls stay up while the dial turns, and go once a channel's tuned, the dial with them.
                onActivity = { playerView?.showController() },
                onTune = { viewModel.switchChannel(it) },
                onTuned = { playerView?.hideController() },
            )
        }

        state.skip?.let { prompt ->
            Button(
                onClick = viewModel::skip,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = if (controlsVisible) 96.dp else 32.dp),
            ) { Text(prompt.label) }
        }

        state.error?.let { message ->
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message, color = Color.White)
                Button(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) { Text("Close") }
            }
        }
    }
}
