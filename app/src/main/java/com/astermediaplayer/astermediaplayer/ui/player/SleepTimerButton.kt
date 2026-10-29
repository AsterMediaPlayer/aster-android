package com.astermediaplayer.astermediaplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.ui.theme.ButtonGrey
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.delay

/**
 * Now Playing's sleep timer: a clock button that, while set, counts down on itself (or says what it waits for), and its menu of
 * Off, 15 to 60 minutes, and the end of the song or chapter. [book] says which it's for; [compact] is the top bar's smaller size.
 */
@Composable
fun SleepTimerButton(book: Boolean, compact: Boolean = false) {
    val app = LocalContext.current.applicationContext as AsterApp
    val timer = app.sleepTimer
    val state by timer.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val endTitle = if (book) "End of Chapter" else "End of Song"
    // Ticks the countdown along while minutes are set.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.minutes) { while (state.minutes != null) { now = System.currentTimeMillis(); delay(1_000) } }
    val label = when {
        state.minutes != null -> AudiobookClock.remaining(((state.endsAt - now) / 1000).coerceAtLeast(0))
        state.atEnd -> endTitle
        else -> null
    }
    Box {
        Row(
            Modifier.height(if (compact) 36.dp else 46.dp).widthIn(min = if (compact) 36.dp else 46.dp).clip(CircleShape)
                .background(if (state.isSet) MaterialTheme.colorScheme.primary else if (compact) Color.White.copy(alpha = 0.2f) else ButtonGrey)
                .clickable { open = true }.padding(horizontal = if (label != null) (if (compact) 10.dp else 14.dp) else 0.dp)
                .semantics { contentDescription = if (label != null) "Sleep timer: $label" else "Sleep timer" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            Icon(Icons.Default.Bedtime, null, tint = Color.White, modifier = Modifier.size(if (compact) 18.dp else 22.dp))
            label?.let { Text(it, style = Type.calloutSemibold, color = Color.White, maxLines = 1) }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun item(title: String, checked: Boolean, onPick: () -> Unit) =
                @Composable { DropdownMenuItem(text = { Text((if (checked) "✓ " else "") + title) }, onClick = { open = false; onPick() }) }
            item("Off", !state.isSet) { timer.cancel() }()
            SleepTimer.minuteChoices.forEach { m -> item("$m Minutes", state.minutes == m) { timer.setMinutes(m) }() }
            item(endTitle, state.atEnd) { timer.setAtEnd(app.nowPlayingKey()) }()
        }
    }
}

/** "14:32", "1:00:00". */
private object AudiobookClock {
    fun remaining(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
