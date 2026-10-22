package com.astermediaplayer.astermediaplayer.ui.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.KeepUntil
import org.jellyfin.sdk.model.api.SeriesTimerInfoDto

data class SeriesTimerState(
    val timer: SeriesTimerInfoDto? = null,
    val working: Boolean = false,
    val error: String? = null,
    /** An error from saving or cancelling, over a form that has loaded. */
    val alert: String? = null,
)

/** The options of one series recording rule: which episodes to record, how many to keep, and padding around each airing. */
class SeriesTimerViewModel(private val repo: AsterRepository, private val id: String) : ViewModel() {
    private val _state = MutableStateFlow(SeriesTimerState())
    val state: StateFlow<SeriesTimerState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try { _state.update { it.copy(timer = repo.seriesTimer(id)) } } catch (e: Exception) { _state.update { it.copy(error = e.userMessage()) } }
        }
    }

    fun change(edit: SeriesTimerInfoDto.() -> SeriesTimerInfoDto) = _state.update { s -> s.copy(timer = s.timer?.edit()) }
    fun dismissAlert() = _state.update { it.copy(alert = null) }

    fun save(done: () -> Unit) = work(done) { repo.updateSeriesTimer(_state.value.timer ?: return@work) }
    fun cancelSeries(done: () -> Unit) = work(done) { repo.cancelSeriesTimer(id) }

    private fun work(done: () -> Unit, block: suspend () -> Unit) {
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            try { block(); done() } catch (e: Exception) { _state.update { it.copy(alert = e.userMessage()) } }
            _state.update { it.copy(working = false) }
        }
    }
}

private val keepUpToOptions = listOf("All" to 0, "1" to 1, "3" to 3, "5" to 5, "10" to 10, "20" to 20)
private val keepUntilOptions = listOf("Until deleted" to KeepUntil.UNTIL_DELETED, "Until space is needed" to KeepUntil.UNTIL_SPACE_NEEDED, "Until watched" to KeepUntil.UNTIL_WATCHED)
private val paddingOptions = listOf("None" to 0, "1 min" to 60, "2 min" to 120, "5 min" to 300, "10 min" to 600, "15 min" to 900)

@Composable
fun SeriesTimerScreen(viewModel: SeriesTimerViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val timer = state.timer
    var confirmCancel by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        // No remote Back button: a bar gives a way out.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Cancel", style = Type.headline.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal)) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Lockup.screenPadding).padding(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(timer?.name ?: "Series Recording", style = Type.pageTitle.copy(fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = 1.0.sp))
            timer?.channelName?.let { Text(it, style = Type.callout, color = SecondaryText) }
            when {
                timer != null -> {
                    Group("Record") {
                        ToggleRow("New episodes only", timer.recordNewOnly ?: false) { v -> viewModel.change { copy(recordNewOnly = v) } }
                        ToggleRow("On any channel", timer.recordAnyChannel ?: false) { v -> viewModel.change { copy(recordAnyChannel = v) } }
                        ToggleRow("At any time", timer.recordAnyTime ?: true) { v -> viewModel.change { copy(recordAnyTime = v) } }
                        ToggleRow("Skip episodes already in the library", timer.skipEpisodesInLibrary ?: false) { v -> viewModel.change { copy(skipEpisodesInLibrary = v) } }
                    }
                    Group("Keep") {
                        PickerRow("Keep up to", keepUpToOptions, timer.keepUpTo ?: 0) { v -> viewModel.change { copy(keepUpTo = v) } }
                        PickerRow("Keep recordings", keepUntilOptions, timer.keepUntil ?: KeepUntil.UNTIL_DELETED) { v -> viewModel.change { copy(keepUntil = v) } }
                    }
                    Group("Padding") {
                        PickerRow("Start early", paddingOptions, timer.prePaddingSeconds ?: 0) { v -> viewModel.change { copy(prePaddingSeconds = v) } }
                        PickerRow("End late", paddingOptions, timer.postPaddingSeconds ?: 0) { v -> viewModel.change { copy(postPaddingSeconds = v) } }
                    }
                    Column(Modifier.padding(top = 16.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1C1E))) {
                        Text("Save", style = Type.callout, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().clickable(enabled = !state.working) { viewModel.save(onClose) }.padding(16.dp))
                        Text("Cancel Series Recording", style = Type.callout, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().clickable(enabled = !state.working) { confirmCancel = true }.padding(16.dp))
                    }
                }
                state.error != null -> Text(state.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
                else -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel this series recording?") },
            text = { Text("Scheduled recordings for this series will be removed. Finished recordings are kept.") },
            confirmButton = { TextButton(onClick = { confirmCancel = false; viewModel.cancelSeries(onClose) }) { Text("Cancel Series Recording", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep") } },
        )
    }
    state.alert?.let {
        AlertDialog(onDismissRequest = viewModel::dismissAlert, title = { Text("Series Recording") }, text = { Text(it) }, confirmButton = { TextButton(onClick = viewModel::dismissAlert) { Text("OK") } })
    }
}

/** A titled group of rows on a rounded dark panel, as an iOS form section. */
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), style = Type.caption, color = SecondaryText, modifier = Modifier.padding(start = 16.dp))
        Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1C1E))) { content() }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.callout, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> PickerRow(label: String, options: List<Pair<String, T>>, selected: T, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Type.callout, modifier = Modifier.weight(1f))
            Text(options.firstOrNull { it.second == selected }?.first.orEmpty(), style = Type.callout, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (name, value) -> DropdownMenuItem(text = { Text(if (value == selected) "✓ $name" else name) }, onClick = { open = false; onChange(value) }) }
        }
    }
}
