package com.astermediaplayer.astermediaplayer.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import java.util.UUID
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.max

private enum class TvMode { Hidden, Transport, Panel, Channels }

private enum class PanelTab(val title: String) { Chapters("Chapters"), Audio("Audio"), Subtitles("Subtitles"), Quality("Quality"), Speed("Speed") }

private class TvOption(val title: String, val checked: Boolean, val onPick: () -> Unit)

/**
 * The player's controls on a TV, worked like the Apple TV app's (`AetherTVControls`) and the system player's:
 * - OK (or Play/Pause) pauses and plays; the timeline shows while paused and for 5 seconds after any press.
 * - Left and right jump 10 seconds. Up shows the timeline.
 * - Down opens the panel: Chapters, Audio, Subtitles, Quality and Speed, with the choices under them. It closes on Back, or after
 *   8 seconds left alone.
 * - Skip Intro / Skip Credits / Play Next Episode show lower right while one applies, and OK takes it.
 * - Back steps out one level at a time: the panel, the controls, then the player.
 *
 * One invisible view holds focus while the panel is closed; the panel's pills hold it while it's open.
 */
@Composable
fun TvPlayerControls(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    clock: PlayerClock,
    canSeek: Boolean,
    /** Plays a title from the library on its own, off the channel (the flick's Go to Episode). */
    onGoTo: (UUID) -> Unit = {},
    /** Told how far up the controls reach (zero while they're hidden), so the song card sits above them. */
    onControlsHeight: (androidx.compose.ui.unit.Dp) -> Unit = {},
    /** Leaves the player: the touch screen's close button (the remote has Back). */
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val exo = viewModel.player
    val focusManager = LocalFocusManager.current
    var mode by remember { mutableStateOf(TvMode.Transport) }
    // A TV with a touchscreen (the Echo Show 15): a tap brings the controls, which get buttons to press and a timeline to drag.
    val touch = com.astermediaplayer.astermediaplayer.ui.theme.LocalTouchTv.current
    var scrubbing by remember { mutableStateOf(false) }
    // How far up the controls reach, for the song card to sit above them.
    val u = com.astermediaplayer.astermediaplayer.ui.theme.Lockup.tvUnit
    LaunchedEffect(mode) {
        onControlsHeight(when (mode) { TvMode.Hidden -> 0.dp; TvMode.Transport -> (380f * u).dp; TvMode.Panel -> (430f * u).dp; TvMode.Channels -> (440f * u).dp })
    }
    var tab by remember { mutableStateOf(PanelTab.Quality) }
    // Counts presses, so each one restarts the fade.
    var activity by remember { mutableIntStateOf(0) }
    val surface = remember { FocusRequester() }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.astermediaplayer.astermediaplayer.AsterApp
    val playsChannels = state.live || state.channel
    // The channel row, while it's open.
    var flick by remember { mutableStateOf<TvFlickModel?>(null) }
    val liveChannelId by viewModel.channelId.collectAsStateWithLifecycle()
    val playingPlaylist by viewModel.playingPlaylistChannel.collectAsStateWithLifecycle()
    val liveChannels by viewModel.channels.collectAsStateWithLifecycle()
    val tabFocus = remember { PanelTab.entries.associateWith { FocusRequester() } }

    val tabs = buildList {
        if (!state.live && state.chapters.size > 1) add(PanelTab.Chapters)
        if (state.audio.size > 1) add(PanelTab.Audio)
        if (state.subtitles.size > 1) add(PanelTab.Subtitles)
        add(PanelTab.Quality)
        if (!state.live) add(PanelTab.Speed)
    }
    val activeTab = if (tab in tabs) tab else tabs.first()

    fun show() { if (mode == TvMode.Hidden) mode = TvMode.Transport; activity++ }

    // Jumps in a row add up from where the last one was headed, not from where playback still is.
    var lastTarget by remember { mutableLongStateOf(0L) }
    var lastJumpAt by remember { mutableLongStateOf(0L) }
    fun jump(seconds: Int) {
        if (!canSeek) { show(); return }
        val now = System.currentTimeMillis()
        val from = if (now - lastJumpAt < 1500) lastTarget else exo.currentPosition
        val end = if (exo.duration > 0) exo.duration - 1000 else Long.MAX_VALUE
        val to = (from + seconds * 1000L).coerceIn(0L, max(end, 0L))
        exo.seekTo(to)
        lastTarget = to; lastJumpAt = now
        show()
    }

    // Skip Intro / Skip Credits, or Play Next Episode near the end: for 5 seconds as it comes up, then again with the timeline.
    val offerKey = state.skip?.label ?: if (state.upNextOffered) "next" else null
    var offerFresh by remember { mutableStateOf(false) }
    LaunchedEffect(offerKey) {
        offerFresh = offerKey != null
        if (offerKey != null) { delay(5000); offerFresh = false }
    }
    val showsSkip = offerKey != null && mode != TvMode.Panel && (offerFresh || mode == TvMode.Transport)

    fun togglePlay() { if (exo.playWhenReady) exo.pause() else exo.play(); show() }
    fun select() {
        if (showsSkip) { if (state.skip != null) viewModel.skip() else viewModel.playNextNow() } else togglePlay()
    }
    fun openPanel() { mode = TvMode.Panel; activity++ }
    fun openChannels() {
        flick = if (state.live) TvFlickModel.live(liveChannels, app.repository, liveChannelId.toString()) else TvFlickModel.playlists(app.repository, playingPlaylist.orEmpty())
        mode = TvMode.Channels; activity++
    }
    fun closeChannels() { if (mode == TvMode.Channels) mode = TvMode.Hidden }
    // OK on the row: Go to Episode plays what's on by itself; otherwise the picked channel is tuned (picking the one on just closes).
    fun changeChannel() {
        val model = flick ?: return closeChannels()
        val picked = model.selectedChannel
        if (model.onGoTo) picked?.now?.itemId?.let(onGoTo)
        else if (picked != null && picked.id != model.currentId) {
            if (state.live) viewModel.switchChannel(UUID.fromString(picked.id)) else viewModel.switchPlaylistChannel(picked.id)
        }
        closeChannels()
    }

    // A paused picture keeps its timeline, as the system player does.
    LaunchedEffect(clock.playing) { if (!clock.playing) show() }
    LaunchedEffect(mode, activity, clock.playing, scrubbing) {
        when (mode) {
            TvMode.Transport -> if (clock.playing && !scrubbing) { delay(5000); mode = TvMode.Hidden }
            TvMode.Panel -> { delay(8000); mode = if (clock.playing) TvMode.Hidden else TvMode.Transport }
            TvMode.Channels -> { delay(8000); closeChannels() }
            TvMode.Hidden -> Unit
        }
    }
    // The tabs hold focus while the panel is open; the surface, the rest of the time. Opened by touch, nothing in the panel takes
    // focus: a focused tab follows focus, so a tap on a choice would first land focus on the next tab over and change the choices.
    LaunchedEffect(mode) {
        if (mode == TvMode.Panel) { if (!touch) runCatching { tabFocus.getValue(activeTab).requestFocus() } } else runCatching { surface.requestFocus() }
    }
    // Back steps back one level; with the controls already away it falls through to leaving the player.
    BackHandler(enabled = mode != TvMode.Hidden) { mode = if (mode == TvMode.Panel) TvMode.Transport else TvMode.Hidden }

    Box(
        modifier.fillMaxSize()
            // A tap on the picture shows the controls, or puts them (and the panel) away.
            .then(
                if (!touch) Modifier
                else Modifier.pointerInput(Unit) {
                    detectTapGestures { mode = if (mode == TvMode.Hidden) TvMode.Transport else TvMode.Hidden; activity++ }
                },
            )
            .focusRequester(surface)
            .onKeyEvent { event ->
                if (mode == TvMode.Panel || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (mode == TvMode.Channels) {
                    val model = flick
                    when (event.key) {
                        Key.DirectionLeft -> { model?.move(-1); activity++ }
                        Key.DirectionRight -> { model?.move(1); activity++ }
                        Key.DirectionUp -> { model?.pickGoTo(true); activity++ }
                        Key.DirectionDown -> if (model?.onGoTo == true) { model.pickGoTo(false); activity++ } else closeChannels()
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> changeChannel()
                        else -> return@onKeyEvent false
                    }
                    return@onKeyEvent true
                }
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> select()
                    Key.MediaPlayPause -> togglePlay()
                    Key.MediaPlay -> { exo.play(); show() }
                    Key.MediaPause -> { exo.pause(); show() }
                    Key.DirectionLeft, Key.MediaRewind -> jump(-10)
                    Key.DirectionRight, Key.MediaFastForward -> jump(10)
                    Key.DirectionUp -> if (playsChannels) openChannels() else show()
                    Key.DirectionDown -> openPanel()
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusable(mode != TvMode.Panel),
    ) {
        val paused = !clock.playing
        // Paused with the controls up: the logo and the details panel, as on the Apple TV. Asked afresh each pause (a channel's
        // programme moves on).
        val pauseShown = paused && mode == TvMode.Transport && !state.loading
        var pauseInfo by remember { mutableStateOf<com.astermediaplayer.astermediaplayer.data.PauseInfo?>(null) }
        LaunchedEffect(pauseShown) { pauseInfo = if (pauseShown) viewModel.pauseInfo() else null }
        val pausePanel = pauseInfo?.takeIf { pauseShown }
        if (mode == TvMode.Channels) flick?.let { TvChannelFlick(it) }
        // Darkens the foot of the picture so white text reads over it, further up in the panel.
        val shade by animateFloatAsState(if (mode == TvMode.Hidden || mode == TvMode.Channels) 0f else 1f, label = "tvShade")
        if (shade > 0f) {
            val start = if (mode == TvMode.Panel || paused) 0.25f else 0.5f
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = shade }.background(
                    Brush.verticalGradient(start to Color.Transparent, 1f to Color.Black.copy(alpha = if (mode == TvMode.Panel || paused) 0.9f else 0.65f)),
                ),
            )
        }
        AnimatedVisibility(pausePanel != null, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            // And the top, behind the logo.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.35f to Color.Transparent)))
        }
        AnimatedVisibility(pausePanel != null, Modifier.align(Alignment.TopStart).padding(horizontal = 48.dp, vertical = 32.dp), enter = fadeIn(), exit = fadeOut()) {
            pauseInfo?.let { TvPauseLogo(it) }
        }
        AnimatedVisibility(mode != TvMode.Hidden && mode != TvMode.Channels, Modifier.align(Alignment.BottomStart), enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (mode == TvMode.Panel) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        tabs.forEach { t ->
                            TvPill(
                                t.title, Modifier.focusRequester(tabFocus.getValue(t)), current = t == activeTab,
                                onFocus = { tab = t; activity++ },
                                // By remote, OK steps down to the choices; by touch, a tap only picks the tab.
                                onClick = { if (touch) { tab = t; activity++ } else focusManager.moveFocus(FocusDirection.Down) },
                            )
                        }
                    }
                    val options = panelOptions(activeTab, state, viewModel, onChosen = { activity++ }, onSeeked = { mode = TvMode.Transport })
                    LazyRow(
                        Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                            // Up from the choices goes back to their tab, wherever the choice sits.
                            if (event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown) {
                                runCatching { tabFocus.getValue(activeTab).requestFocus() }
                                true
                            } else false
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(options) { option -> TvPill(option.title, checked = option.checked, onFocus = { activity++ }, onClick = option.onPick) }
                    }
                } else {
                    if (pausePanel != null) TvPausePanel(pausePanel, streamBadges(exo, hasSubtitles = state.subtitles.size > 1))
                    else                     Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.subtitle?.let { Text(it, style = Type.callout, color = Color.White.copy(alpha = 0.75f), maxLines = 1) }
                        Text(state.title, style = Type.title3.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1)
                    }
                    if (state.live) {
                        Row(
                            Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Color.Red))
                            Text("LIVE", style = Type.callout.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp), color = Color.White)
                        }
                    } else {
                        // Dragged by touch, with the frame at the finger; the remote's Left and Right do it otherwise.
                        Timeline(
                            clock, canScrub = touch && canSeek, onScrubbing = { scrubbing = it; activity++ },
                            onSeek = { exo.seekTo(it); activity++ }, preview = if (touch) viewModel::scrubFrame else ({ null }),
                        )
                    }
                    // The tabs as labels; they only become buttons when the panel opens, so they never take focus from the surface. By
                    // touch, one opens the panel at itself.
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        tabs.forEach { t -> TvPill(t.title, onClick = if (touch) ({ tab = t; openPanel() }) else null) }
                    }
                }
            }
        }
        // By touch: back and ahead 10 seconds and play or pause in the middle, and a way out at the top.
        AnimatedVisibility(touch && mode == TvMode.Transport, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                TouchButton(Icons.Default.Close, "Close", 56.dp, Modifier.align(Alignment.TopEnd).padding(24.dp), onClick = onClose)
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (canSeek) TouchButton(Icons.Default.Replay10, "Back 10 seconds", 72.dp) { jump(-10) }
                    TouchButton(if (clock.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (clock.playing) "Pause" else "Play", 96.dp) { togglePlay() }
                    if (canSeek) TouchButton(Icons.Default.Forward10, "Ahead 10 seconds", 72.dp) { jump(10) }
                }
            }
        }
        if (showsSkip && mode != TvMode.Channels) {
            val skipPrompt = state.skip
            TvPill(
                skipPrompt?.label ?: "Play Next Episode", Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = if (mode == TvMode.Transport) 160.dp else 32.dp),
                icon = if (skipPrompt != null) Icons.Default.FastForward else Icons.Default.SkipNext, highlighted = true,
            )
        }
    }
}

/** A round button over the picture, for a TV's touchscreen. */
@Composable
private fun TouchButton(icon: ImageVector, label: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.55f)) }
}

/** The choices under a tab. */
private fun panelOptions(tab: PanelTab, state: PlayerUiState, viewModel: PlayerViewModel, onChosen: () -> Unit, onSeeked: () -> Unit): List<TvOption> = when (tab) {
    PanelTab.Chapters -> state.chapters.mapIndexed { index, chapter ->
        TvOption(chapter.name, index == state.chapterIndex) { viewModel.seekToChapter(chapter); onSeeked() }
    }
    PanelTab.Audio -> state.audio.map { choice -> TvOption(choice.label, choice.selected) { viewModel.selectAudio(choice); onChosen() } }
    PanelTab.Subtitles -> state.subtitles.map { choice -> TvOption(choice.label, choice.selected) { viewModel.select(choice); onChosen() } }
    PanelTab.Quality -> Qualities.map { q -> TvOption(q.label, q == state.quality) { viewModel.setQuality(q); onChosen() } }
    PanelTab.Speed -> PlaybackSpeeds.map { s -> TvOption(speedTitle(s), s == state.speed) { viewModel.setSpeed(s); onChosen() } }
}

/**
 * A tab or a choice in the panel, as the Apple TV app's `PanelPill`: dark glass, lit white when focused so the picture
 * still shows through. With no [onClick] it is only a label and never takes focus.
 */
@Composable
private fun TvPill(
    title: String,
    modifier: Modifier = Modifier,
    checked: Boolean = false,
    /** The tab whose choices are showing. */
    current: Boolean = false,
    icon: ImageVector? = null,
    /** Drawn as if focused (the skip button, which OK takes). */
    highlighted: Boolean = false,
    onFocus: () -> Unit = {},
    onClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val lit = focused || highlighted
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "tvPill")
    val tint = if (lit) Color.Black else Color.White
    Row(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (onClick != null) Modifier.onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
                else Modifier,
            )
            .clip(CircleShape)
            .background(if (lit) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = if (current) 0.28f else 0.14f))
            .then(if (onClick != null) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (checked) Icon(Icons.Default.Check, null, tint = tint, modifier = Modifier.size(18.dp))
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Text(title, style = Type.callout.copy(fontWeight = FontWeight.Medium), color = tint, maxLines = 1)
    }
}
