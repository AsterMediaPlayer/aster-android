package com.astermediaplayer.astermediaplayer.ui.settings

import androidx.compose.runtime.Composable
import com.astermediaplayer.astermediaplayer.data.AppSettings
import com.astermediaplayer.astermediaplayer.data.SettingsStore
import kotlinx.coroutines.launch

@Composable
internal fun DiscMenusPage(settings: AppSettings, store: SettingsStore, scope: kotlinx.coroutines.CoroutineScope) {
    SSwitch("Open disc menu when pressing Play", settings.preferDiscMenu) { scope.launch { store.setPreferDiscMenu(it) } }
    SSwitch("Menu music and sounds", settings.discMenuSounds) { scope.launch { store.setDiscMenuSounds(it) } }
    SNote("Titles with a JellyDisc menu on the server show a Disc Menu button on their page. Turn on the first option to start those titles at their menu, like putting in the disc.")
}
