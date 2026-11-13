package com.astermediaplayer.astermediaplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.astermediaplayer.astermediaplayer.data.AppSettings
import com.astermediaplayer.astermediaplayer.data.SettingsStore
import kotlinx.coroutines.launch

@Composable
internal fun TitlePagesPage(settings: AppSettings, store: SettingsStore, scope: kotlinx.coroutines.CoroutineScope, repo: com.astermediaplayer.astermediaplayer.data.AsterRepository) {
    SSwitch("Theme music", settings.themeMusic) { scope.launch { store.setThemeMusic(it) } }
    SSwitch("Theme videos", settings.themeVideos) { scope.launch { store.setThemeVideos(it) } }
    SNote("Plays a title's theme song quietly, or loops its theme video behind the page, when your library has them.")
    SGroup("Anime")
    SSwitch("Opening plays with sound", settings.openingSound, enabled = settings.themeVideos) { scope.launch { store.setOpeningSound(it) } }
    SNote("Anime with no theme video of its own loops its first opening behind the series page, when Theme videos is on. With sound, it plays quietly, like theme music.")
    SGroup("Song names")
    SSwitch("Look up song names", settings.songLookups) { scope.launch { store.setSongLookups(it) } }
    var refreshing by remember { mutableStateOf<String?>(null) }
    SRow("Refresh songs", value = refreshing, enabled = settings.songLookups && refreshing != "Refreshing…") {
        refreshing = "Refreshing…"
        scope.launch { refreshing = if (runCatching { repo.refreshSongs() }.getOrDefault(false)) "Done" else "Couldn't reach AnimeThemes" }
    }
    SNote("Names the song of an opening or ending from AnimeThemes (animethemes.moe) when your server doesn't. Where it does (Plex, Emby, and Jellyfin without the OP/ED plugin) the names for every anime show are fetched in the background and kept on this device; Refresh songs asks again for all of them. Turn off and Aster doesn't contact AnimeThemes.")
}
