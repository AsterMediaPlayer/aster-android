package com.astermediaplayer.astermediaplayer.ui.settings

import androidx.compose.runtime.Composable
import com.astermediaplayer.astermediaplayer.data.AppSettings
import com.astermediaplayer.astermediaplayer.data.SettingsStore
import com.astermediaplayer.astermediaplayer.data.ThemeCut
import com.astermediaplayer.astermediaplayer.ui.player.Qualities
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import kotlinx.coroutines.launch

@Composable
internal fun PlaybackPage(settings: AppSettings, store: SettingsStore, scope: kotlinx.coroutines.CoroutineScope) {
    SChoice("Default quality", Qualities.map { it.label }, settings.qualityIndex) { scope.launch { store.setQualityIndex(it) } }
    SChoice("Opening and ending version", ThemeCut.entries.map { it.settingTitle }, ThemeCut.entries.indexOf(settings.themeCut)) { scope.launch { store.setThemeCut(ThemeCut.entries[it]) } }
    SSwitch("Skip intros automatically", settings.autoSkipSegments) { scope.launch { store.setAutoSkipSegments(it) } }
    SSwitch("Play the next episode automatically", settings.autoPlayNext) { scope.launch { store.setAutoPlayNext(it) } }
    SSwitch("Song card at openings and endings", settings.themeCards) { scope.launch { store.setThemeCards(it) } }
    SNote("While an opening or ending plays, a card shows the song, its artists and the show; a music video gets one at its start and end.")
    SGroup("Music")
    SSwitch("Save upcoming songs", settings.musicAhead) { scope.launch { store.setMusicAhead(it) } }
    if (LocalIsTv.current) {
        SNote("The next five songs in the queue are saved as they come up, once the song playing is under way, so they start at once and play on if the connection drops. They're kept in the cache, and let go once heard.")
    } else {
        if (settings.musicAhead) SSwitch("Save on cellular data too", settings.musicAheadCellular) { scope.launch { store.setMusicAheadCellular(it) } }
        SNote("The next five songs in the queue are saved as they come up, once the song playing is under way, so they start at once and play on if the connection drops. They're saved only on Wi-Fi or a wired connection unless cellular is chosen too, and never with Data Saver on. They're kept in the cache, and let go once heard.")
    }
    // A phone can lose its signal mid-book; a TV doesn't.
    if (!LocalIsTv.current) {
        SGroup("Audiobooks")
        val choices = listOf(0 to "Off", 15 to "15 minutes", 30 to "30 minutes", 60 to "1 hour", 120 to "2 hours")
        SChoice("Listen Ahead", choices.map { it.second }, choices.indexOfFirst { it.first == settings.listenAheadMinutes }.coerceAtLeast(0)) { scope.launch { store.setListenAheadMinutes(choices[it].first) } }
        SNote("Saves what's coming up in an audiobook as it plays, so it carries on through a spot with no signal. It's kept in the cache, and let go once heard. Downloaded books don't need it.")
    }
}
