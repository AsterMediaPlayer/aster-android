package com.astermediaplayer.astermediaplayer.ui.settings

import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv

@Composable
internal fun AboutPage(open: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    SRow("Aster Media Player", version)
    SNote("A Jellyfin, Emby and Plex client for Android TV, phones and tablets, with DVD and Blu-ray style menus on Jellyfin from the Aster Disc plugin.")
    androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
    AboutLink("Help & Support", Icons.AutoMirrored.Filled.HelpOutline, "https://astermediaplayer.com/support.html")
    AboutLink("Privacy Policy", Icons.Default.PanTool, "https://astermediaplayer.com/privacy.html")
    SRow("Open Source & Licenses", icon = Icons.Default.Description, chevron = true) { open(SettingsPage.OpenSource) }
    var reporting by remember { mutableStateOf(false) }
    SRow("Send Debug Report", icon = Icons.Default.BugReport, chevron = true) { reporting = true }
    SNote("When something goes wrong, send a report and quote its code in a bug report.")
    if (reporting) DebugReportDialog { reporting = false }
}

/** One of Aster's web pages, as on the Apple apps: opened in the browser, or on a TV, which has none, the address to type in elsewhere. */
@Composable
private fun AboutLink(title: String, icon: ImageVector, address: String) {
    val context = LocalContext.current
    if (LocalIsTv.current) SRow(title, address.removePrefix("https://"), icon = icon)
    else SRow(title, icon = icon, chevron = true) {
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(address))) }
    }
}
