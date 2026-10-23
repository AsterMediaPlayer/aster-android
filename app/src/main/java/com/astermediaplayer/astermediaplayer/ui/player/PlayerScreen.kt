package com.astermediaplayer.astermediaplayer.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.delay
import kotlin.math.max
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
import androidx.compose.material.icons.filled.Subtitles
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
import com.astermediaplayer.astermediaplayer.data.channels.ChannelTuner

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
    var qualityMenu by remember { mutableStateOf(false) }
    var chapterMenu by remember { mutableStateOf(false) }
    var subtitleMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val exo = viewModel.player
    val clock = rememberPlayerClock(exo)
    val scope = rememberCoroutineScope()

    // The controls come up on a tap and fade a few seconds after the last touch; they stay up while paused or a menu is open.
    var touchedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var visible by remember { mutableStateOf(true) }
    var scrubbing by remember { mutableStateOf(false) }
    val menuOpen = subtitleMenu || qualityMenu || chapterMenu || speedMenu
    val controlsVisible = visible || !clock.playing
    fun reveal() { visible = true; touchedAt = System.currentTimeMillis() }
    LaunchedEffect(touchedAt, clock.playing, menuOpen, scrubbing, visible) {
        if (visible && clock.playing && !menuOpen && !scrubbing) {
            delay(5000)
            visible = false
        }
    }
    // Fill the screen (cropping the picture) or fit it in.
    var fills by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    // Skips in a row add up from where the last one was headed, not from where playback still is.
    var lastTarget by remember { mutableLongStateOf(0L) }
    var lastSkipAt by remember { mutableLongStateOf(0L) }
    fun skip(seconds: Int) {
        val now = System.currentTimeMillis()
        val from = if (now - lastSkipAt < 1500) lastTarget else exo.currentPosition
        val end = if (exo.duration > 0) exo.duration - 1000 else Long.MAX_VALUE
        val to = (from + seconds * 1000L).coerceIn(0L, max(end, 0L))
        exo.seekTo(to)
        lastTarget = to; lastSkipAt = now
        reveal()
    }
    // Back and forward 10 are for a title; a channel keeps to its schedule.
    val canSeek = !state.live && !state.channel

    LaunchedEffect(viewModel) { viewModel.playNext.collect(onPlayNext) }
    LaunchedEffect(viewModel) { viewModel.finished.collect { onBack() } }

    // Landscape, full-screen, screen-on while playing; everything is restored when leaving.
    DisposableEffect(activity) {
        val window = activity?.window
        // Play Next brings the next player up before the last one is gone, so the orientation to
        // come back to is the one from before the first of them.
        if (PlayerWindow.users++ == 0) PlayerWindow.previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            if (--PlayerWindow.users > 0) return@onDispose
            PlayerWindow.previousOrientation?.let { activity?.requestedOrientation = it }
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
                    // The controls are ours, drawn below, in the Apple apps' layout.
                    useController = false
                    viewModel.assHandler?.let { subtitleView?.addView(AssSubtitleView(ctx, it)) }
                }
            },
            update = { it.resizeMode = if (fills) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT },
            modifier = Modifier.fillMaxSize(),
        )

        // A tap on the picture shows or hides the controls; a double-tap on either side skips 10 seconds; a pinch fills the screen or fits it.
        Box(
            Modifier.fillMaxSize().pinchToFill { fills = it; reveal() }.pointerInput(canSeek) {
                detectTapGestures(
                    onTap = { if (controlsVisible && clock.playing) visible = false else reveal() },
                    onDoubleTap = { at ->
                        if (canSeek) {
                            val forward = at.x > size.width / 2
                            skip(if (forward) 10 else -10)
                            flash = forward to System.currentTimeMillis()
                        }
                    },
                )
            },
        )
        flash?.let { (forward, at) ->
            SkipFlash(forward)
            LaunchedEffect(at) { delay(700); if (flash?.second == at) flash = null }
        }

        // The server's text subtitle, drawn by the app under the controls; the player's own captions and libass draw in the video view.
        state.cueText?.let { SubtitleOverlay(it) }

        if (state.loading && state.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        val shade by animateFloatAsState(if (controlsVisible) 1f else 0f, tween(250), label = "controls")
        if (shade > 0f) Box(Modifier.fillMaxSize().alpha(shade)) {
            // Darkens the edges so white text reads over a bright picture.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.35f to Color.Transparent, 0.65f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.subtitle?.let { Text(it, style = Type.headline, color = Color.White.copy(alpha = 0.7f), maxLines = 1) }
                        Text(state.title, style = Type.title3.copy(fontSize = 22.sp, lineHeight = 28.sp), color = Color.White, maxLines = 1)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (state.live) {
                    Row(
                        Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color.Red))
                        Text("LIVE", style = Type.callout.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp), color = Color.White)
                    }
                } else {
                    Timeline(clock, canScrub = canSeek, onScrubbing = { scrubbing = it; if (it) reveal() }, onSeek = { exo.seekTo(it); reveal() }, preview = viewModel::scrubFrame)
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    BarIcon(if (fills) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, if (fills) "Fit to Screen" else "Fill Screen") { fills = !fills; reveal() }
                    Spacer(Modifier.weight(1f))
                    if (!state.live && state.chapters.isNotEmpty()) Box {
                        BarIcon(Icons.Default.Bookmarks, "Chapters") { chapterMenu = true }
                        DropdownMenu(expanded = chapterMenu, onDismissRequest = { chapterMenu = false }) {
                            state.chapters.forEachIndexed { index, chapter ->
                                DropdownMenuItem(
                                    text = { Text((if (index == state.chapterIndex) "✓ " else "") + chapter.name) },
                                    onClick = { chapterMenu = false; viewModel.seekToChapter(chapter); reveal() },
                                )
                            }
                        }
                    }
                    if (!state.live) Box {
                        BarIcon(Icons.Default.Speed, "Speed") { speedMenu = true }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                            PlaybackSpeeds.forEach { speed ->
                                DropdownMenuItem(
                                    text = { Text((if (speed == state.speed) "✓ " else "") + speedTitle(speed)) },
                                    onClick = { speedMenu = false; viewModel.setSpeed(speed); reveal() },
                                )
                            }
                        }
                    }
                    if (state.subtitles.size > 1) Box {
                        BarIcon(Icons.Default.Subtitles, "Subtitles") { subtitleMenu = true }
                        DropdownMenu(expanded = subtitleMenu, onDismissRequest = { subtitleMenu = false }) {
                            state.subtitles.forEach { choice ->
                                DropdownMenuItem(
                                    text = { Text(if (choice.selected) "✓ ${choice.label}" else choice.label) },
                                    onClick = { subtitleMenu = false; viewModel.select(choice); reveal() },
                                )
                            }
                        }
                    }
                    Box {
                        BarIcon(Icons.Default.HighQuality, "Quality") { qualityMenu = true }
                        DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                            Qualities.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(if (option == state.quality) "✓ ${option.label}" else option.label) },
                                    onClick = { qualityMenu = false; viewModel.setQuality(option); reveal() },
                                )
                            }
                        }
                    }
                }
            }
            CenterButtons(clock.playing, canSkip = canSeek, onSkip = ::skip, onToggle = { if (exo.playWhenReady) exo.pause() else exo.play(); reveal() }, modifier = Modifier.align(Alignment.Center))
        }

        if (state.live) {
            val channels by viewModel.channels.collectAsStateWithLifecycle()
            val channelId by viewModel.channelId.collectAsStateWithLifecycle()
            val repo = (LocalContext.current.applicationContext as com.astermediaplayer.astermediaplayer.AsterApp).repository
            val dial = remember(channels) { channels.map { it.toDial(repo) } }
            ChannelDialOverlay(
                channels = dial, playingId = channelId.toString(), controlsShown = controlsVisible,
                // The controls stay up while the dial turns, and go once a channel's tuned, the dial with them.
                onActivity = { reveal() },
                onTune = { id -> viewModel.switchChannel(java.util.UUID.fromString(id)) },
                onTuned = { visible = false },
            )
        } else if (state.channel) {
            val playing by viewModel.playingPlaylistChannel.collectAsStateWithLifecycle()
            val repo = (LocalContext.current.applicationContext as com.astermediaplayer.astermediaplayer.AsterApp).repository
            // What's on each channel is worked out fresh as the dial opens, so it's what's on now.
            val dial = remember(playing) { ChannelTuner.orderedChannels.map { it.toDial(repo) } }
            ChannelDialOverlay(
                channels = dial, playingId = playing.orEmpty(), controlsShown = controlsVisible,
                onActivity = { reveal() },
                onTune = { id -> viewModel.switchPlaylistChannel(id) },
                onTuned = { visible = false },
            )
            ChannelBug(playing, state.breakClip, state.title)
        }

        // Skip Intro / Skip Credits, or Play Next Episode near the end, in the lower right while one
        // applies: for 5 seconds as it comes up, and again whenever the controls do.
        val offerKey = state.skip?.label ?: if (state.upNextOffered) "next" else null
        var offerFresh by remember { mutableStateOf(false) }
        LaunchedEffect(offerKey) {
            offerFresh = offerKey != null
            if (offerKey != null) {
                delay(5000)
                offerFresh = false
            }
        }
        if (offerKey != null && (offerFresh || controlsVisible)) {
            val skipPrompt = state.skip
            Button(
                onClick = if (skipPrompt != null) viewModel::skip else viewModel::playNextNow,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = if (controlsVisible) 150.dp else 32.dp),
            ) {
                Icon(if (skipPrompt != null) Icons.Default.FastForward else Icons.Default.SkipNext, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(skipPrompt?.label ?: "Play Next Episode")
            }
        }

        state.error?.let { message ->
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message, color = Color.White)
                Button(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) { Text("Close") }
            }
        }
    }
}

/** How many player screens are up at once (two, briefly, on Play Next) and the orientation from before them. */
private object PlayerWindow {
    var users = 0
    var previousOrientation: Int? = null
}

/** A bottom-row control: just its icon, as the Apple apps' icon-only buttons. */
@Composable
private fun BarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

/** The speeds offered. */
val PlaybackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** "1×", "1.25×". */
fun speedTitle(speed: Float): String = (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else "%.2f".format(speed).trimEnd('0')) + "×"
