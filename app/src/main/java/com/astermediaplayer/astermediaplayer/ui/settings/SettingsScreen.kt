package com.astermediaplayer.astermediaplayer.ui.settings

import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import com.astermediaplayer.astermediaplayer.ui.detail.MenuHost
import com.astermediaplayer.astermediaplayer.ui.detail.MenuItem
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
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
import com.astermediaplayer.astermediaplayer.ui.downloads.DownloadOptions
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import com.astermediaplayer.astermediaplayer.ui.player.Qualities
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(session: Session?, store: SettingsStore, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    if (LocalIsTv.current) { TvSettings(session, settings, store, onSignOut, modifier); return }

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
        Section("Downloads")
        SwitchRow("Allow downloads", settings.downloadsEnabled) { scope.launch { store.setDownloadsEnabled(it) } }
        if (settings.downloadsEnabled) DownloadOptions()
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

/** Settings on a TV, in the Apple TV's list style (its `SettingsView`): a large title, then groups of rows that turn white when focused. */
@Composable
private fun TvSettings(session: Session?, settings: AppSettings, store: SettingsStore, onSignOut: () -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val u = Lockup.tvUnit
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = (90f * u).dp).padding(top = (40f * u).dp, bottom = (80f * u).dp),
        verticalArrangement = Arrangement.spacedBy((6f * u).dp),
    ) {
        Text("Settings", style = Type.pageTitle.copy(fontSize = (76f * u).sp, lineHeight = (90f * u).sp), modifier = Modifier.padding(bottom = (18f * u).dp))

        TvGroup("Account")
        TvValueRow("Signed in as", session?.userName.orEmpty())
        TvValueRow("Server", session?.serverUrl.orEmpty())
        TvButtonRow("Sign out", destructive = true, onClick = onSignOut)

        TvGroup("Playback")
        TvChoiceRow("Default quality", Qualities.map { it.label }, settings.qualityIndex) { scope.launch { store.setQualityIndex(it) } }
        TvChoiceRow("Opening and ending version", ThemeCut.entries.map { it.settingTitle }, ThemeCut.entries.indexOf(settings.themeCut)) { scope.launch { store.setThemeCut(ThemeCut.entries[it]) } }
        TvSwitchRow("Skip intros and credits automatically", settings.autoSkipSegments) { scope.launch { store.setAutoSkipSegments(it) } }
        TvSwitchRow("Draw styled ASS/SSA subtitles (libass)", settings.styledSubtitles) { scope.launch { store.setStyledSubtitles(it) } }
        TvSwitchRow("Play the next episode automatically", settings.autoPlayNext) { scope.launch { store.setAutoPlayNext(it) } }

        TvGroup("Title pages")
        TvSwitchRow("Theme music", settings.themeMusic) { scope.launch { store.setThemeMusic(it) } }
        TvSwitchRow("Theme videos", settings.themeVideos) { scope.launch { store.setThemeVideos(it) } }
        TvNote("Plays a title's theme song quietly, or loops its theme video behind the page, when your library has them.")

        TvGroup("Anime")
        TvSwitchRow("Opening plays with sound", settings.openingSound, enabled = settings.themeVideos) { scope.launch { store.setOpeningSound(it) } }
        TvNote("Anime with no theme video of its own loops its first opening behind the series page, when Theme videos is on. With sound, it plays quietly, like theme music.")
    }
}

@Composable
internal fun TvGroup(title: String) {
    val u = Lockup.tvUnit
    Text(title.uppercase(), style = Type.caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp), color = SecondaryText, modifier = Modifier.padding(top = (26f * u).dp, bottom = (6f * u).dp, start = (8f * u).dp))
}

@Composable
private fun TvNote(text: String) {
    val u = Lockup.tvUnit
    Text(text, style = Type.caption, color = SecondaryText, modifier = Modifier.padding(horizontal = (8f * u).dp, vertical = (4f * u).dp).widthIn(max = (1200f * u).dp))
}

/** One row of the list: its title on the left and a value on the right, grey, and white with black text when focused. */
@Composable
internal fun TvRow(title: String, value: String?, enabled: Boolean = true, destructive: Boolean = false, modifier: Modifier = Modifier, onClick: (() -> Unit)?) {
    val u = Lockup.tvUnit
    var focused by remember { mutableStateOf(false) }
    val tint = when {
        focused -> if (destructive) Color(0xFFD70015) else Color.Black
        !enabled -> Color.White.copy(alpha = 0.4f)
        destructive -> Color(0xFFFF453A)
        else -> Color.White
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = (82f * u).dp).then(if (onClick != null) Modifier.tvFocus((14f * u).dp, lift = 1.02f, ring = false).onFocusChanged { focused = it.isFocused } else Modifier)
            .clip(RoundedCornerShape((14f * u).dp)).background(if (focused) Color.White else Color.White.copy(alpha = 0.1f))
            .then(if (onClick != null && enabled) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick) else if (onClick != null) Modifier.focusable() else Modifier)
            .padding(horizontal = (28f * u).dp, vertical = (14f * u).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Type.callout, color = tint, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = Type.callout, color = if (focused) Color.Black.copy(alpha = 0.6f) else SecondaryText, maxLines = 1)
    }
}

@Composable private fun TvValueRow(title: String, value: String) = TvRow(title, value, onClick = null)

@Composable private fun TvButtonRow(title: String, destructive: Boolean = false, onClick: () -> Unit) = TvRow(title, null, destructive = destructive, onClick = onClick)

@Composable
internal fun TvSwitchRow(title: String, checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) =
    TvRow(title, if (checked) "On" else "Off", enabled = enabled, modifier = modifier, onClick = { onChange(!checked) })

/** A row showing the current choice; OK opens the glass menu of them below it. */
@Composable
internal fun TvChoiceRow(title: String, options: List<String>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TvRow(title, options.getOrNull(selected).orEmpty(), modifier = modifier, onClick = { open = true })
        MenuHost(expanded = open, onDismiss = { open = false }, below = true) {
            options.forEachIndexed { i, name -> MenuItem(name, null, first = i == selected, checked = i == selected) { open = false; onPick(i) } }
        }
    }
}
