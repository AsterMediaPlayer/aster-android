package com.astermediaplayer.astermediaplayer.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.Audiobook
import com.astermediaplayer.astermediaplayer.data.downloads.DownloadException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

/**
 * What happens when Download is tapped: the first time, the setup (space and quality); for a title with a choice of
 * tracks, which audio and subtitles to save; then the download starts. Errors (no room, not allowed) show as an alert.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadFlow(private val app: AsterApp, private val scope: CoroutineScope) {
    private var setup by mutableStateOf<(() -> Unit)?>(null)
    private var tracksFor by mutableStateOf<BaseItemDto?>(null)
    private var error by mutableStateOf<String?>(null)

    /** Downloads a movie or episode, asking what's needed first. */
    fun download(item: BaseItemDto) = afterSetup {
        if (hasChoice(item)) tracksFor = item else start(item, null, null)
    }

    fun downloadBook(book: Audiobook) = afterSetup {
        scope.launch { guarded { app.downloads.startBook(book, app.repository.canDownload()) } }
    }

    fun retry(id: String) {
        scope.launch { guarded { app.downloads.retry(id, app.repository.canDownload()) } }
    }

    private fun afterSetup(action: () -> Unit) {
        scope.launch {
            if (app.settings.current().downloadsSetUp) action() else setup = action
        }
    }

    private fun start(item: BaseItemDto, audio: Int?, subtitle: Int?) {
        scope.launch { guarded { app.downloads.start(item, app.repository.canDownload(), audio, subtitle) } }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            error = (e as? DownloadException)?.message ?: e.message ?: "Couldn't download."
        }
    }

    @Composable
    fun Dialogs() {
        setup?.let { action ->
            AlertDialog(
                onDismissRequest = { setup = null },
                title = { Text("Set Up Downloads") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Choose how much space downloads may use and the quality they're saved at. You can change these any time in Settings › Downloads.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DownloadOptions()
                    }
                },
                confirmButton = { TextButton(onClick = { scope.launch { app.settings.setDownloadsSetUp(true) }; setup = null; action() }) { Text("Download") } },
                dismissButton = { TextButton(onClick = { setup = null }) { Text("Cancel") } },
            )
            // Starts from a limit that suits the space free.
            LaunchedEffect(Unit) {
                if (app.settings.current().downloadLimitGb == 0) {
                    val free = app.downloads.freeBytes()
                    app.settings.setDownloadLimitGb(if (free > 60_000_000_000) 25 else if (free > 25_000_000_000) 10 else 5)
                }
            }
        }
        tracksFor?.let { item -> TracksDialog(item, onCancel = { tracksFor = null }) { audio, subtitle -> tracksFor = null; start(item, audio, subtitle) } }
        error?.let {
            AlertDialog(onDismissRequest = { error = null }, title = { Text("Couldn't Download") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } })
        }
    }

    @Composable
    private fun TracksDialog(item: BaseItemDto, onCancel: () -> Unit, onChoose: (audio: Int?, subtitle: Int) -> Unit) {
        val source = item.mediaSources?.firstOrNull()
        val streams = source?.mediaStreams ?: item.mediaStreams.orEmpty()
        val audioTracks = streams.filter { it.type == MediaStreamType.AUDIO && it.index >= 0 }
        val subtitleTracks = streams.filter { it.type == MediaStreamType.SUBTITLE && it.index >= 0 }
        var audio by remember { mutableStateOf(source?.defaultAudioStreamIndex ?: audioTracks.firstOrNull()?.index) }
        var subtitle by remember { mutableStateOf(-1) }
        LaunchedEffect(Unit) { if (app.settings.current().downloadSubtitles) subtitle = source?.defaultSubtitleStreamIndex ?: -1 }
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(if (item.seriesName != null) item.seriesName!! else item.name.orEmpty()) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (audioTracks.size > 1) {
                        Heading("Audio")
                        audioTracks.forEach { Choice(trackName(it), detail(it), audio == it.index) { audio = it.index } }
                        Note("A download keeps one audio track, the one it plays in offline.")
                    }
                    if (subtitleTracks.isNotEmpty()) {
                        Heading("Subtitles")
                        Choice("None", null, subtitle == -1) { subtitle = -1 }
                        subtitleTracks.forEach { Choice(trackName(it), detail(it, burned = !it.isTextSubtitleStream), subtitle == it.index) { subtitle = it.index } }
                        Note("Text subtitles are saved alongside the video and can be turned off while watching. Picture-based ones are burned into the video and always show.")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onChoose(audio, subtitle) }) { Text("Download") } },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
    }

    @Composable private fun Heading(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
    @Composable private fun Note(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))

    @Composable
    private fun Choice(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (!detail.isNullOrEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    private fun trackName(s: MediaStream) = s.title ?: s.displayTitle ?: s.language ?: "Track ${s.index}"
    private fun detail(s: MediaStream, burned: Boolean = false) =
        listOfNotNull(s.codec?.uppercase(), s.language, s.channelLayout, if (burned) "Burned in" else null).joinToString(" · ")

    companion object {
        /** Whether a title has anything to choose. */
        fun hasChoice(item: BaseItemDto): Boolean {
            val streams = item.mediaSources?.firstOrNull()?.mediaStreams ?: item.mediaStreams.orEmpty()
            return streams.count { it.type == MediaStreamType.AUDIO } > 1 || streams.any { it.type == MediaStreamType.SUBTITLE }
        }
    }
}

@Composable
fun rememberDownloadFlow(): DownloadFlow {
    val app = LocalContext.current.applicationContext as AsterApp
    val scope = rememberCoroutineScope()
    return remember { DownloadFlow(app, scope) }
}
