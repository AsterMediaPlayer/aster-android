package com.astermediaplayer.astermediaplayer.ui.audiobooks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.Audiobook
import com.astermediaplayer.astermediaplayer.data.AudiobookRates
import com.astermediaplayer.astermediaplayer.ui.player.AudiobookPlayer
import com.astermediaplayer.astermediaplayer.ui.theme.ButtonGrey
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type

/** The song or book playing, shown over every tab; opens the full player. */
@Composable
fun AudiobookBar(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as AsterApp
    val music by app.music.state.collectAsStateWithLifecycle()
    music.current?.let { song ->
        BarRow(
            modifier, { com.astermediaplayer.astermediaplayer.ui.music.MusicArt(song, 48.dp, width = 200, shape = RoundedCornerShape(10.dp)) },
            song.name.orEmpty(), song.artists?.joinToString(", ") ?: song.albumArtist.orEmpty(), music.isPlaying,
            onOpen = { app.music.setPresented(true) }, onToggle = { app.music.togglePlayPause() },
        )
        return
    }
    val player = app.audiobooks
    val state by player.state.collectAsStateWithLifecycle()
    val book = state.book ?: return
    BarRow(
        modifier, { AudiobookCover(book, 48.dp, Modifier.clip(RoundedCornerShape(10.dp)), 200) },
        book.title, listOfNotNull(state.chapter?.title, book.author).joinToString(" · "), state.isPlaying,
        onOpen = { player.setPresented(true) }, onToggle = { player.togglePlayPause() },
    )
}

@Composable
private fun BarRow(modifier: Modifier, art: @Composable () -> Unit, title: String, subtitle: String, playing: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xE61C1C1E)).clickable(onClick = onOpen).padding(start = 12.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        art()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.calloutSemibold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = Type.caption, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White,
            modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onToggle),
        )
    }
}

/** The speed button and its menu. */
@Composable
private fun SpeedMenu(player: AudiobookPlayer, rate: Float) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.height(46.dp).widthIn(min = 46.dp).clip(CircleShape).background(if (rate == 1f) ButtonGrey else MaterialTheme.colorScheme.primary)
                .clickable { open = true }.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text(AudiobookRates.label(rate), style = Type.calloutSemibold, color = Color.White) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AudiobookRates.choices.forEach { choice ->
                DropdownMenuItem(text = { Text((if (choice == rate) "✓ " else "") + AudiobookRates.label(choice)) }, onClick = { open = false; player.setRate(choice) })
            }
        }
    }
}

@Composable
private fun Control(icon: ImageVector, label: String, prominent: Boolean = false, active: Boolean = false, onClick: () -> Unit) {
    val size = if (prominent) 64.dp else 46.dp
    Box(
        Modifier.size(size).clip(CircleShape).background(if (prominent) Color.White else if (active) MaterialTheme.colorScheme.primary else ButtonGrey).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (prominent) Color.Black else Color.White, modifier = Modifier.size(if (prominent) 30.dp else 22.dp)) }
}

/** The full-screen audiobook player: cover, chapter progress, skips, speed and the chapter list. */
@Composable
fun AudiobookPlayerScreen(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as AsterApp
    val player = app.audiobooks
    val state by player.state.collectAsStateWithLifecycle()
    val book = state.book
    var showChapters by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Double?>(null) }

    LaunchedEffect(book == null) { if (book == null) player.setPresented(false) }
    Box(modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        if (book != null) {
            AsyncImage(model = app.downloads.cover(book, 400), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(100.dp))
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val stacked = maxHeight >= 560.dp
                val coverSize = minOf(maxWidth - 48.dp, maxHeight * 0.42f, 420.dp).coerceAtLeast(0.dp)
                Column(
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 60.dp, bottom = 24.dp).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (stacked) {
                        if (showChapters) {
                            Row(Modifier.fillMaxWidth().widthIn(max = 560.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                AudiobookCover(book, 96.dp, Modifier.clip(RoundedCornerShape(14.dp)), 400)
                                Info(book, state, 20.sp, centered = false)
                            }
                        } else {
                            AudiobookCover(book, coverSize, Modifier.shadow(20.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)), 900)
                            Info(book, state, 24.sp, centered = true)
                        }
                    } else {
                        Info(book, state, 22.sp, centered = true)
                    }
                    Progress(state, scrub, { scrub = it }, player)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Control(Icons.Default.SkipPrevious, "Previous Chapter") { player.previousChapter() }
                        Control(Icons.Default.Replay, "Back 15 Seconds") { player.skip(-AudiobookPlayer.SKIP_BACK_SECONDS.toInt()) }
                        Control(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "Pause" else "Play", prominent = true) { player.togglePlayPause() }
                        Control(Icons.Default.Forward30, "Ahead 30 Seconds") { player.skip(AudiobookPlayer.SKIP_FORWARD_SECONDS.toInt()) }
                        Control(Icons.Default.SkipNext, "Next Chapter") { player.nextChapter() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        SpeedMenu(player, state.rate)
                        com.astermediaplayer.astermediaplayer.ui.player.SleepTimerButton(book = true)
                        if (book.chapters.isNotEmpty()) Control(Icons.AutoMirrored.Filled.List, "Chapters", active = showChapters) { showChapters = !showChapters }
                    }
                    state.error?.let { Text(it, style = Type.caption, color = Color.Red) }
                    if (showChapters) {
                        val list = rememberLazyListState()
                        LaunchedEffect(Unit) { state.chapter?.id?.let { list.scrollToItem(it) } }
                        LazyColumn(Modifier.fillMaxWidth().widthIn(max = 700.dp).weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(book.chapters, key = { it.id }) { chapter ->
                                ChapterRow(chapter.title, Audiobook.clock(book.offsetOfPart(chapter.part) + chapter.startSeconds), state.chapter?.id == chapter.id) { player.jump(chapter) }
                            }
                        }
                    }
                }
            }
        }
        // Close, and Stop, which ends it: the player closes and the bar goes away.
        Box(
            Modifier.statusBarsPadding().padding(Lockup.screenPadding).size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).clickable { player.setPresented(false) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.KeyboardArrowDown, "Close", tint = Color.White) }
        Box(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(Lockup.screenPadding).size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).clickable { player.stop() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Close, "Stop", tint = Color.White, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun Info(book: Audiobook, state: com.astermediaplayer.astermediaplayer.ui.player.AudiobookState, titleSize: androidx.compose.ui.unit.TextUnit, centered: Boolean) {
    Column(Modifier.fillMaxWidth().widthIn(max = 560.dp), horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(book.title, style = Type.pageTitle.copy(fontSize = titleSize, lineHeight = titleSize * 1.2f), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = if (centered) TextAlign.Center else TextAlign.Start)
        book.author?.let { Text(it, style = Type.title3, color = Color.White) }
        state.chapter?.let { Text(it.title, style = Type.callout, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** The chapter's progress, and how much of the book is left. */
@Composable
private fun Progress(state: com.astermediaplayer.astermediaplayer.ui.player.AudiobookState, scrub: Double?, onScrub: (Double?) -> Unit, player: AudiobookPlayer) {
    val (start, end) = state.chapterBounds
    val length = end - start
    val playing = (state.position - start).coerceIn(0.0, maxOf(length, 0.0))
    // While the bar's dragged, the times show where it's going.
    val into = scrub?.let { it * length } ?: playing
    val bookLeft = ((state.bookDuration - (state.bookElapsed - playing + into)).coerceAtLeast(0.0)) / state.rate
    var width by remember { mutableStateOf(1) }
    Column(Modifier.fillMaxWidth().widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(28.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }
                .pointerInput(length) {
                    detectDragGestures(
                        onDragEnd = { scrub?.let { player.seekTo(start + it * length) }; onScrub(null) },
                        onDragCancel = { onScrub(null) },
                    ) { change, _ -> onScrub((change.position.x / width).toDouble().coerceIn(0.0, 1.0)) }
                }
                .pointerInput(length) { detectTapGestures { player.seekTo(start + (it.x / width).toDouble().coerceIn(0.0, 1.0) * length) } },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth((if (length > 0) into / length else 0.0).toFloat().coerceIn(0.01f, 1f)).height(6.dp).clip(CircleShape).background(Color.White))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Audiobook.clock(into), style = Type.caption, color = SecondaryText)
            Text((Audiobook.lengthText(bookLeft) ?: "0 min") + " left", style = Type.caption, color = SecondaryText)
            Text("-" + Audiobook.clock((length - into).coerceAtLeast(0.0)), style = Type.caption, color = SecondaryText)
        }
    }
}
