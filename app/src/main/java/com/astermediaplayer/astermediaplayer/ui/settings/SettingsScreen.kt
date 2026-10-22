package com.astermediaplayer.astermediaplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astermediaplayer.astermediaplayer.data.AppSettings
import com.astermediaplayer.astermediaplayer.data.Session
import com.astermediaplayer.astermediaplayer.data.SettingsStore
import com.astermediaplayer.astermediaplayer.ui.player.Qualities
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(session: Session?, store: SettingsStore, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Account")
        Text(session?.userName.orEmpty(), style = MaterialTheme.typography.bodyLarge)
        Text(session?.serverUrl.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onSignOut) { Text("Sign out") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Section("Playback")
        Text("Default quality", style = MaterialTheme.typography.bodyLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(Qualities) { index, quality ->
                FilterChip(
                    selected = settings.qualityIndex == index,
                    onClick = { scope.launch { store.setQualityIndex(index) } },
                    label = { Text(quality.label) },
                )
            }
        }
        SwitchRow("Skip intros and credits automatically", settings.autoSkipSegments) { scope.launch { store.setAutoSkipSegments(it) } }
        SwitchRow("Play the next episode automatically", settings.autoPlayNext) { scope.launch { store.setAutoPlayNext(it) } }
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
