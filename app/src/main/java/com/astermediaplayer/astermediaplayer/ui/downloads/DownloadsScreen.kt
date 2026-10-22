package com.astermediaplayer.astermediaplayer.ui.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.AppSettings
import com.astermediaplayer.astermediaplayer.data.downloads.*
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import java.util.UUID

private val Group = Color(0xFF1C1C1E)

/** Everything downloaded or downloading, newest first. Finished titles play from the device, so this works with no connection. */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(onPlay: (UUID) -> Unit, onBook: (UUID) -> Unit, modifier: Modifier = Modifier, topInset: Dp = 0.dp) {
    val app = LocalContext.current.applicationContext as AsterApp
    val store = app.downloads
    val records by store.records.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val flow = rememberDownloadFlow()
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var confirmSelected by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }

    val videos = records.filter { it.kind == DownloadKind.Video }
    val books = records.filter { it.kind == DownloadKind.Book }
    LaunchedEffect(records.isEmpty()) { if (records.isEmpty()) { selecting = false; selection = emptySet() } }
    val summary = remember(records, settings.downloadLimitGb) {
        listOfNotNull(
            "Using ${DownloadStore.format(store.usedBytes)}" + if (settings.downloadLimitGb > 0) " of ${DownloadStore.format(settings.downloadLimitGb * 1_000_000_000L)}" else "",
            "${DownloadStore.format(store.freeBytes())} free on this device",
        ).joinToString(" · ")
    }

    @Composable
    fun Row(record: DownloadRecord) {
        var menu by remember { mutableStateOf(false) }
        Box {
            DownloadRow(
                record, selecting, record.id in selection,
                Modifier.combinedClickable(
                    onClick = {
                        when {
                            selecting -> selection = if (record.id in selection) selection - record.id else selection + record.id
                            record.kind == DownloadKind.Book -> onBook(UUID.fromString(record.id))
                            record.state == DownloadState.Finished -> onPlay(UUID.fromString(record.id))
                            record.state == DownloadState.Failed -> flow.retry(record.id)
                        }
                    },
                    onLongClick = { if (!selecting) menu = true },
                ),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (record.state == DownloadState.Failed) DropdownMenuItem(text = { Text("Resume Download") }, onClick = { menu = false; flow.retry(record.id) })
                DropdownMenuItem(text = { Text(if (record.state == DownloadState.Downloading) "Cancel Download" else "Remove Download") }, onClick = { menu = false; store.remove(record.id) })
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(top = 8.dp + topInset, bottom = 140.dp)) {
            item {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = Lockup.screenPadding).padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
                    Text("Downloads", style = Type.pageTitle.copy(fontSize = 34.sp, lineHeight = 41.sp), modifier = Modifier.weight(1f))
                    if (records.isNotEmpty()) TextButton(onClick = { selecting = !selecting; selection = emptySet() }) { Text(if (selecting) "Done" else "Select", style = Type.headline) }
                }
            }
            if (records.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 120.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Icon(Icons.Default.ArrowCircleDown, null, tint = SecondaryText, modifier = Modifier.size(48.dp))
                        Text("No Downloads", style = Type.title3)
                        Text("Download a movie or episode from the ⋯ button on its page, or an audiobook from its page, to have it without a connection.", style = Type.callout, color = SecondaryText, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            } else {
                if (videos.isNotEmpty()) section(if (books.isNotEmpty()) "Movies & TV" else null, videos, if (books.isEmpty()) summary else null) { Row(it) }
                if (books.isNotEmpty()) section("Audiobooks", books, summary) { Row(it) }
            }
        }
        if (selecting) {
            androidx.compose.foundation.layout.Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xFF111111)).navigationBarsPadding().padding(horizontal = Lockup.screenPadding, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { confirmAll = true }) { Text("Delete All", color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { confirmSelected = true }, enabled = selection.isNotEmpty()) {
                    Text(if (selection.isEmpty()) "Delete" else "Delete (${selection.size})", color = if (selection.isEmpty()) SecondaryText else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    flow.Dialogs()
    if (confirmSelected) {
        val bytes = records.filter { it.id in selection }.sumOf { if (it.state == DownloadState.Finished) it.bytesOnDisk else (it.expectedBytes * it.progress).toLong() }
        AlertDialog(
            onDismissRequest = { confirmSelected = false },
            title = { Text(if (selection.size == 1) "Delete this download?" else "Delete ${selection.size} downloads?") },
            text = { Text("This frees ${DownloadStore.format(bytes)}. Any of them still downloading stop.") },
            confirmButton = { TextButton(onClick = { store.remove(selection); selection = emptySet(); selecting = false; confirmSelected = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmSelected = false }) { Text("Cancel") } },
        )
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Delete all downloads?") },
            text = { Text("This frees ${DownloadStore.format(store.usedBytes)} and stops any downloads in progress.") },
            confirmButton = { TextButton(onClick = { store.removeAll(); selection = emptySet(); selecting = false; confirmAll = false }) { Text("Delete All", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
}

/** A grouped section: a small header, the rows in a rounded group, and a footer. */
private fun androidx.compose.foundation.lazy.LazyListScope.section(header: String?, records: List<DownloadRecord>, footer: String?, row: @Composable (DownloadRecord) -> Unit) {
    if (header != null) item { Text(header.uppercase(), style = Type.caption, color = SecondaryText, modifier = Modifier.padding(start = 32.dp, top = 12.dp, bottom = 6.dp)) }
    item {
        Column(Modifier.padding(horizontal = Lockup.screenPadding).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Group)) {
            records.forEachIndexed { i, r ->
                row(r)
                if (i < records.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), color = Color.White.copy(alpha = 0.12f))
            }
        }
    }
    if (footer != null) item { Text(footer, style = Type.caption, color = SecondaryText, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 12.dp)) }
}

/** A download's artwork, title and state. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun DownloadRow(record: DownloadRecord, selecting: Boolean, selected: Boolean, modifier: Modifier) {
    val app = LocalContext.current.applicationContext as AsterApp
    val art = remember(record.id, record.state) { app.downloads.artwork(record.id) }
    val book = record.kind == DownloadKind.Book
    androidx.compose.foundation.layout.Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selecting) Checkbox(checked = selected, onCheckedChange = null)
        Box(Modifier.size(if (book) 63.dp else 112.dp, 63.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            if (art != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else androidx.compose.material3.Icon(if (book) Icons.Default.Book else if (record.isEpisode) Icons.Default.Tv else Icons.Default.Movie, null, tint = SecondaryText)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(record.title, style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            (if (book) record.author else record.subtitle)?.let { Text(it, style = Type.callout.copy(fontSize = 15.sp, lineHeight = 20.sp), color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            when (record.state) {
                DownloadState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(progress = { record.progress.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = Color.White.copy(alpha = 0.2f), gapSize = 0.dp, drawStopIndicator = {})
                    Text(
                        (if (record.waiting) "Waiting for network · " else "Downloading · ") + "${(record.progress * 100).toInt()}% of about ${DownloadStore.format(record.expectedBytes)}",
                        style = Type.caption, color = SecondaryText,
                    )
                }
                DownloadState.Finished -> Text(
                    listOfNotNull(DownloadStore.format(record.bytesOnDisk), record.quality?.let { q -> DownloadQuality.entries.firstOrNull { it.name == q }?.title }, record.audioTitle).joinToString(" · "),
                    style = Type.caption, color = SecondaryText,
                )
                DownloadState.Failed -> Text("${record.errorMessage ?: "Download failed."} Tap to resume.", style = Type.caption, color = Color(0xFFFF453A), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
