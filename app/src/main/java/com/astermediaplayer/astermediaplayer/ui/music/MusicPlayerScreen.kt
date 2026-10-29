package com.astermediaplayer.astermediaplayer.ui.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.Audiobook
import com.astermediaplayer.astermediaplayer.ui.player.MusicState
import com.astermediaplayer.astermediaplayer.ui.player.RepeatKind
import com.astermediaplayer.astermediaplayer.ui.theme.*

@Composable
private fun Control(icon: ImageVector, label: String, prominent: Boolean = false, active: Boolean = false, onClick: () -> Unit) {
    val size = if (prominent) 64.dp else 46.dp
    Box(
        Modifier.size(size).clip(CircleShape).background(if (prominent) Color.White else if (active) MaterialTheme.colorScheme.primary else ButtonGrey).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (prominent) Color.Black else Color.White, modifier = Modifier.size(if (prominent) 30.dp else 22.dp)) }
}

/** The full-screen music player: artwork, progress, shuffle, skips, repeat and lyrics, with Up Next or the lyrics below. */
@Composable
fun MusicPlayerScreen(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as AsterApp
    val player = app.music
    val state by player.state.collectAsStateWithLifecycle()
    val song = state.current
    var showLyrics by remember { mutableStateOf(false) }
    val lyricsShowing = showLyrics && state.lyrics != null
    var scrub by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(song == null) { if (song == null) player.setPresented(false) }

    Box(modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        if (song != null) {
            val repo = app.repository
            AsyncImage(
                model = repo.imageUrl(song, org.jellyfin.sdk.model.api.ImageType.PRIMARY, 400) ?: song.albumId?.let { id -> song.albumPrimaryImageTag?.let { repo.imageUrlFor(id, it, 400) } },
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(100.dp),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val screenWidth = maxWidth
                val screenHeight = maxHeight
                Column(
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 60.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (lyricsShowing) {
                            // A compact header: small cover beside the song.
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                MusicArt(song, 96.dp, width = 400, shape = RoundedCornerShape(14.dp))
                                Info(song, 20.sp, centered = false)
                            }
                        } else {
                            MusicArt(song, minOf(screenWidth - 48.dp, screenHeight * 0.36f, 340.dp).coerceAtLeast(0.dp), Modifier.shadow(20.dp, RoundedCornerShape(14.dp)), 900, RoundedCornerShape(14.dp))
                            Info(song, 24.sp, centered = true)
                        }
                        Progress(state, scrub, { scrub = it }) { player.seekTo(it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Control(Icons.Default.Shuffle, "Shuffle", active = state.shuffled) { player.toggleShuffle() }
                            Control(Icons.Default.SkipPrevious, "Previous") { player.previous() }
                            Control(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "Pause" else "Play", prominent = true) { player.togglePlayPause() }
                            Control(Icons.Default.SkipNext, "Next") { player.next() }
                            Control(if (state.repeat == RepeatKind.One) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat", active = state.repeat != RepeatKind.Off) { player.cycleRepeat() }
                            if (state.lyrics != null) Control(Icons.Default.FormatQuote, "Lyrics", active = showLyrics) { showLyrics = !showLyrics }
                        }
                        state.error?.let { Text(it, style = Type.caption, color = Color.Red) }
                    }
                    if (lyricsShowing) LyricsPanel(state, Modifier.weight(1f).fillMaxWidth())
                    else if (state.upNext.isNotEmpty()) UpNext(state) { player.skipTo(it) }
                }
            }
        }
        // Close, and Stop, which ends it: the player closes and the bar goes away.
        Box(Modifier.statusBarsPadding().padding(Lockup.screenPadding).size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).clickable { player.setPresented(false) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.KeyboardArrowDown, "Close", tint = Color.White)
        }
        // The sleep timer beside Stop: the controls row has no room left on a phone.
        Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = Lockup.screenPadding, end = Lockup.screenPadding + 48.dp)) {
            com.astermediaplayer.astermediaplayer.ui.player.SleepTimerButton(book = false, compact = true)
        }
        Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(Lockup.screenPadding).size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).clickable { player.stop() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, "Stop", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Info(song: org.jellyfin.sdk.model.api.BaseItemDto, size: androidx.compose.ui.unit.TextUnit, centered: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(song.name.orEmpty(), style = Type.pageTitle.copy(fontSize = size, lineHeight = size * 1.2f), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = if (centered) TextAlign.Center else TextAlign.Start)
        song.artistText()?.let { Text(it, style = Type.title3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        song.album?.let { Text(it, style = Type.callout, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun Progress(state: MusicState, scrub: Double?, onScrub: (Double?) -> Unit, seek: (Double) -> Unit) {
    val shown = scrub?.let { it * state.duration } ?: state.position
    var width by remember { mutableStateOf(1) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(28.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }
                .pointerInput(state.duration) {
                    detectDragGestures(onDragEnd = { scrub?.let { seek(it * state.duration) }; onScrub(null) }, onDragCancel = { onScrub(null) }) { change, _ -> onScrub((change.position.x / width).toDouble().coerceIn(0.0, 1.0)) }
                }
                .pointerInput(state.duration) { detectTapGestures { seek((it.x / width).toDouble().coerceIn(0.0, 1.0) * state.duration) } },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth((if (state.duration > 0) shown / state.duration else 0.0).toFloat().coerceIn(0.01f, 1f)).height(6.dp).clip(CircleShape).background(Color.White))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Audiobook.clock(shown), style = Type.caption, color = SecondaryText)
            Text("-" + Audiobook.clock((state.duration - shown).coerceAtLeast(0.0)), style = Type.caption, color = SecondaryText)
        }
    }
}

@Composable
private fun UpNext(state: MusicState, onSkip: (org.jellyfin.sdk.model.api.BaseItemDto) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Lockup.shelfTitleSpacing)) {
        Text("Up Next", style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding))
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = Lockup.screenPadding), horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing)) {
            items(state.upNext.size) { i -> SquareLockup(state.upNext[i], 90.dp) { onSkip(state.upNext[i]) } }
        }
    }
}

/** Lyrics under the player: synced lines highlight and scroll with the song, unsynced lyrics read as a plain block. */
@Composable
private fun LyricsPanel(state: MusicState, modifier: Modifier = Modifier) {
    val lyrics = state.lyrics ?: return
    val list = rememberLazyListState()
    val current = state.currentLyricIndex
    LaunchedEffect(current) { current?.let { list.animateScrollToItem(it, scrollOffset = -200) } }
    LazyColumn(
        modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.12f to Color.Black, 0.88f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        },
        state = list, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        itemsIndexed(lyrics.lyrics) { index, line ->
            Text(
                line.text.ifEmpty { " " }, style = Type.title3.copy(fontWeight = if (index == current) FontWeight.SemiBold else FontWeight.Normal),
                color = if (index == current || !state.lyricsSynced) Color.White else Color.White.copy(alpha = 0.4f),
            )
        }
    }
}
