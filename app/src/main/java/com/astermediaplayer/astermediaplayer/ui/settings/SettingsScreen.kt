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
import com.astermediaplayer.astermediaplayer.data.ThemeCut
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import com.astermediaplayer.astermediaplayer.ui.player.Qualities
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(session: Session?, store: SettingsStore, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = TabBarClearance), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        Text("Opening and ending version", style = MaterialTheme.typography.bodyLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(ThemeCut.entries) { _, cut ->
                FilterChip(
                    selected = settings.themeCut == cut,
                    onClick = { scope.launch { store.setThemeCut(cut) } },
                    label = { Text(cut.settingTitle) },
                )
            }
        }
        SwitchRow("Skip intros and credits automatically", settings.autoSkipSegments) { scope.launch { store.setAutoSkipSegments(it) } }
        SwitchRow("Draw styled ASS/SSA subtitles (libass)", settings.styledSubtitles) { scope.launch { store.setStyledSubtitles(it) } }
        SwitchRow("Play the next episode automatically", settings.autoPlayNext) { scope.launch { store.setAutoPlayNext(it) } }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Section("Title pages")
        SwitchRow("Theme music", settings.themeMusic) { scope.launch { store.setThemeMusic(it) } }
        SwitchRow("Theme videos", settings.themeVideos) { scope.launch { store.setThemeVideos(it) } }
        Text(
            "Plays a title's theme song quietly, or loops its theme video behind the page, when your library has them.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Section("Anime")
        SwitchRow("Opening plays with sound", settings.openingSound, enabled = settings.themeVideos) { scope.launch { store.setOpeningSound(it) } }
        Text(
            "Anime with no theme video of its own loops its first opening behind the series page, when Theme videos is on. With sound, it plays quietly, like theme music.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
