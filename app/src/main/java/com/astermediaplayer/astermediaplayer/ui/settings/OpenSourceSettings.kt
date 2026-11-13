package com.astermediaplayer.astermediaplayer.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astermediaplayer.astermediaplayer.data.OpenSourceComponent
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus

/** Open Source & Licenses on its own, for the welcome screen, as on the Apple apps; Back steps out of a license, then closes it. */
@Composable
fun OpenSourceLicenses(onClose: () -> Unit) {
    val stack = remember { mutableStateListOf<SettingsPage>(SettingsPage.OpenSource) }
    val page = stack.last()
    BackHandler { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onClose() }
    SettingsFrame(page, Modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        when (page) {
            is SettingsPage.License -> LicensePage(page.name)
            else -> OpenSourcePage { stack.add(it) }
        }
    }
}

/**
 * Open Source & Licenses, as on the Apple apps: Aster's own license, then every open-source component built into the app, what it
 * does here and its license, each opening its license in full.
 */
@Composable
internal fun OpenSourcePage(open: (SettingsPage) -> Unit) {
    val aster = OpenSourceComponent.aster
    SGroup("Aster")
    ComponentRow(aster) { open(SettingsPage.License(aster.name)) }
    SNote("Aster is open source, under the Mozilla Public License 2.0. You're free to use, change and share it; changed files of Aster you share must stay under the same license. Its source is at ${aster.url}.")
    SNote("The Aster name and icon aren't part of the license. Aster isn't affiliated with or endorsed by Jellyfin, Emby or Plex.")
    OpenSourceComponent.groups.forEach { (title, components) ->
        SGroup(title)
        components.forEach { component -> ComponentRow(component) { open(SettingsPage.License(component.name)) } }
    }
    SGroup(" ")
    SNote("Each component keeps its own license; select one to read it in full. Where a license is the LGPL, you may replace that component with your own build of it: the source of each, and of Aster, is at the address shown.")
    SNote("Casting to a Chromecast uses Google's Cast SDK, which comes under Google's own terms rather than an open-source license.")
}

/** A component's name, what it does, and its license; grey, and white with black text when focused on a TV. */
@Composable
private fun ComponentRow(component: OpenSourceComponent, onClick: () -> Unit) {
    val tv = LocalIsTv.current
    if (tv) {
        val u = Lockup.tvUnit
        var focused by remember { mutableStateOf(false) }
        val secondary = if (focused) Color.Black.copy(alpha = 0.6f) else SecondaryText
        Row(
            firstFocusModifier(true).fillMaxWidth().tvFocus((14f * u).dp, lift = 1.02f, ring = false).onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape((14f * u).dp)).background(if (focused) Color.White else Color.White.copy(alpha = 0.1f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .padding(horizontal = (28f * u).dp, vertical = (16f * u).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy((4f * u).dp)) {
                Text(component.name, style = Type.callout, color = if (focused) Color.Black else Color.White)
                Text(component.role, style = Type.caption, color = secondary)
                Text(component.license, style = Type.caption, color = secondary)
            }
            Icon(Icons.Default.ChevronRight, null, tint = secondary, modifier = Modifier.padding(start = (12f * u).dp).size((34f * u).dp))
        }
    } else {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(component.name, style = MaterialTheme.typography.titleSmall)
                Text(component.role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(component.license, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** A component's license in full: its license's name, its address, any notice, then each text, a paragraph at a time. */
@Composable
internal fun LicensePage(name: String) {
    val context = LocalContext.current
    val component = remember(name) { OpenSourceComponent.all.firstOrNull { it.name == name } } ?: return
    // In paragraphs, so a TV remote can scroll through them one at a time.
    val paragraphs = remember(component) {
        component.files.flatMap { file ->
            val text = OpenSourceComponent.text(context, file) ?: "This license text is missing from the app; it's at ${component.url}."
            text.replace("\r\n", "\n").split("\n\n").map { it.trim('\n') }.filter { it.isNotBlank() }
        }
    }
    SRow("License", component.license)
    SRow("Source", component.url.removePrefix("https://"))
    component.note?.let { SNote(it) }
    SGroup(" ")
    val tv = LocalIsTv.current
    paragraphs.forEach { paragraph ->
        if (tv) {
            val u = Lockup.tvUnit
            var focused by remember { mutableStateOf(false) }
            Text(
                paragraph, style = Type.caption.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = if (focused) Color.White else SecondaryText,
                modifier = firstFocusModifier(true).fillMaxWidth().clip(RoundedCornerShape((10f * u).dp))
                    .background(if (focused) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                    .onFocusChanged { focused = it.isFocused }.focusable().padding(horizontal = (8f * u).dp, vertical = (4f * u).dp),
            )
        } else {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(paragraph, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp), modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}
