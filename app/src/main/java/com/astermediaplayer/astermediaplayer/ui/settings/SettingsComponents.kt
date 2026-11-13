package com.astermediaplayer.astermediaplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astermediaplayer.astermediaplayer.ui.detail.MenuHost
import com.astermediaplayer.astermediaplayer.ui.detail.MenuItem
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus

/** The first row of a page that can be pressed takes the remote as the page opens. */
internal class FirstFocus {
    val requester = androidx.compose.ui.focus.FocusRequester()
    private var taken = false
    fun claim(): Boolean { if (taken) return false; taken = true; return true }
}
internal val LocalFirstFocus = androidx.compose.runtime.compositionLocalOf<FirstFocus?> { null }

/** A row's modifier: the page's first pressable one asks for focus when the page opens. */
@Composable
internal fun firstFocusModifier(pressable: Boolean): Modifier {
    val first = LocalFirstFocus.current
    // Decided again when a row becomes pressable (a page still loading has its rows disabled).
    return remember(pressable) { if (pressable && first?.claim() == true) Modifier.focusRequester(first.requester) else Modifier }
}

// --- The kit: a group heading, a row, a switch, a choice, a note; each drawn the TV way or the phone's. ---

@Composable
internal fun SGroup(title: String? = null) {
    if (title == null) return
    if (LocalIsTv.current) TvGroup(title)
    else Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, start = 4.dp))
}

@Composable
internal fun SNote(text: String) {
    if (LocalIsTv.current) TvNote(text)
    else Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
internal fun SRow(
    title: String, value: String? = null, icon: ImageVector? = null, chevron: Boolean = false, enabled: Boolean = true,
    destructive: Boolean = false, subtitle: String? = null, onClick: (() -> Unit)? = null,
) {
    if (LocalIsTv.current) { TvRow(title, value, enabled = enabled, destructive = destructive, modifier = firstFocusModifier(onClick != null && enabled), icon = icon, chevron = chevron, subtitle = subtitle, onClick = onClick); return }
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.padding(end = 14.dp).size(24.dp))
        val titleColor = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant
        if (subtitle != null) Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = titleColor)
        if (value != null) Text(value, Modifier.padding(start = 12.dp).widthIn(max = 220.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (chevron) Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
internal fun SSwitch(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    if (LocalIsTv.current) { TvSwitchRow(title, checked, enabled = enabled, modifier = firstFocusModifier(enabled), onChange = onChange); return }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)).padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
internal fun SChoice(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    if (LocalIsTv.current) { TvChoiceRow(title, options, selected, modifier = firstFocusModifier(true), onPick = onPick); return }
    var open by remember { mutableStateOf(false) }
    Box {
        SRow(title, options.getOrNull(selected).orEmpty(), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, name -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(i) }, trailingIcon = if (i == selected) ({ Icon(Icons.Default.Check, null) }) else null) }
        }
    }
}

@Composable
internal fun TvGroup(title: String) {
    val u = Lockup.tvUnit
    Text(title.uppercase(), style = Type.caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp), color = SecondaryText, modifier = Modifier.padding(top = (26f * u).dp, bottom = (6f * u).dp, start = (8f * u).dp))
}

@Composable
internal fun TvNote(text: String) {
    val u = Lockup.tvUnit
    Text(text, style = Type.caption, color = SecondaryText, modifier = Modifier.padding(horizontal = (8f * u).dp, vertical = (4f * u).dp).widthIn(max = (1200f * u).dp))
}

/** One row of the list: its title on the left and a value on the right, grey, and white with black text when focused. */
@Composable
internal fun TvRow(
    title: String, value: String?, enabled: Boolean = true, destructive: Boolean = false, modifier: Modifier = Modifier,
    icon: ImageVector? = null, chevron: Boolean = false, subtitle: String? = null, fold: Boolean? = null, onClick: (() -> Unit)?,
) {
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
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.padding(end = (20f * u).dp).size((38f * u).dp))
        // A long title wraps in what the value leaves; a long value gives way with an ellipsis, never out of the row.
        if (subtitle != null) Column(Modifier.weight(1f)) {
            Text(title, style = Type.callout, color = tint)
            Text(subtitle, style = Type.caption, color = if (focused) Color.Black.copy(alpha = 0.6f) else SecondaryText)
        } else Text(title, style = Type.callout, color = tint, modifier = Modifier.weight(1f))
        if (value != null) Text(
            value, style = Type.callout, color = if (focused) Color.Black.copy(alpha = 0.6f) else SecondaryText, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = (16f * u).dp).widthIn(max = (900f * u).dp),
        )
        // A row that opens or closes more beneath it, as the Apple apps' folds do.
        if (fold != null) Icon(if (fold) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = if (focused) Color.Black.copy(alpha = 0.5f) else SecondaryText, modifier = Modifier.padding(start = (12f * u).dp).size((34f * u).dp))
        if (chevron) Icon(Icons.Default.ChevronRight, null, tint = if (focused) Color.Black.copy(alpha = 0.5f) else SecondaryText, modifier = Modifier.padding(start = (12f * u).dp).size((34f * u).dp))
    }
}

@Composable
internal fun TvSwitchRow(title: String, checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, subtitle: String? = null, onChange: (Boolean) -> Unit) =
    TvRow(title, if (checked) "On" else "Off", enabled = enabled, modifier = modifier, subtitle = subtitle, onClick = { onChange(!checked) })

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
