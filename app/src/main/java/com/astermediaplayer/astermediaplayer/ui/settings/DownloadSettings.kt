package com.astermediaplayer.astermediaplayer.ui.settings

import androidx.compose.runtime.Composable
import com.astermediaplayer.astermediaplayer.data.AppSettings
import com.astermediaplayer.astermediaplayer.data.SettingsStore
import com.astermediaplayer.astermediaplayer.ui.downloads.DownloadOptions
import kotlinx.coroutines.launch

@Composable
internal fun DownloadsPage(settings: AppSettings, store: SettingsStore, scope: kotlinx.coroutines.CoroutineScope) {
    SSwitch("Allow downloads", settings.downloadsEnabled) { scope.launch { store.setDownloadsEnabled(it) } }
    if (settings.downloadsEnabled) DownloadOptions()
}
