package com.astermediaplayer.astermediaplayer.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.AppSettings
import com.astermediaplayer.astermediaplayer.data.downloads.DownloadQuality
import com.astermediaplayer.astermediaplayer.data.downloads.DownloadStore
import kotlinx.coroutines.launch

/** Storage, quality and network choices for downloads, shared by Settings and the first-download setup. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun DownloadOptions(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as AsterApp
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val records by app.downloads.records.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val used = remember(records) { app.downloads.usedBytes }
    val free = remember(records) { app.downloads.freeBytes() }
    // Limits that fit what the device could hold, plus the one chosen.
    val limits = remember(free, used, settings.downloadLimitGb) {
        val capacity = (free + used) / 1_000_000_000
        (listOf(5, 10, 25, 50, 100, 250).filter { it <= capacity } + listOfNotNull(settings.downloadLimitGb.takeIf { it > 0 })).distinct().sorted()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Storage", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        LabeledValue("Free on this device", DownloadStore.format(free))
        LabeledValue("Used by downloads", DownloadStore.format(used))
        Text("Space for downloads", style = MaterialTheme.typography.bodyLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(limits) { gb -> FilterChip(selected = settings.downloadLimitGb == gb, onClick = { scope.launch { app.settings.setDownloadLimitGb(gb) } }, label = { Text("$gb GB") }) }
            item { FilterChip(selected = settings.downloadLimitGb == 0, onClick = { scope.launch { app.settings.setDownloadLimitGb(0) } }, label = { Text("No limit") }) }
        }
        Caption("A download that would go over this waits for you to make room. With no limit, downloads stop short of filling the device.")

        Text("Quality", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
        DownloadQuality.entries.forEach { q ->
            Row(Modifier.fillMaxWidth().clickable { scope.launch { app.settings.setDownloadQuality(q) } }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.downloadQuality == q, onClick = { scope.launch { app.settings.setDownloadQuality(q) } })
                Column {
                    Text(q.title, style = MaterialTheme.typography.bodyLarge)
                    Text(q.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SwitchLine("Smaller files (HEVC)", settings.downloadHevc) { scope.launch { app.settings.setDownloadHevc(it) } }
        SwitchLine("Include subtitles", settings.downloadSubtitles) { scope.launch { app.settings.setDownloadSubtitles(it) } }
        SwitchLine("Download over cellular", settings.downloadCellular) { scope.launch { app.settings.setDownloadCellular(it) } }
        Caption("HEVC files are about a third smaller, but a server without hardware encoding takes longer to prepare them. Without cellular, downloads wait for Wi-Fi.")
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Caption(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
