package com.astermediaplayer.astermediaplayer.ui.livetv

import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.ui.components.Badge
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import com.astermediaplayer.astermediaplayer.ui.detail.ActionButton
import com.astermediaplayer.astermediaplayer.ui.detail.IconActionButton
import com.astermediaplayer.astermediaplayer.ui.guide.dayLabel
import com.astermediaplayer.astermediaplayer.ui.guide.timeRange
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.ZoneOffset
import java.util.UUID

data class ProgramState(
    val program: BaseItemDto? = null,
    val working: Boolean = false,
    val message: String? = null,
    val channelFavorite: Boolean = false,
    val error: String? = null,
)

/** One programme from the guide: what it is, when it airs, and the Watch and Record actions (the Apple apps' `ProgramDetailView`). */
class ProgramViewModel(private val repo: AsterRepository, private val id: String) : ViewModel() {
    private val listed = repo.openedPrograms[id]
    private val _state = MutableStateFlow(ProgramState(program = listed))
    val state: StateFlow<ProgramState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            refresh()
            listed?.channelId?.let { channel -> runCatching { repo.item(channel) }.getOrNull()?.let { c -> _state.update { it.copy(channelFavorite = c.userData?.isFavorite == true) } } }
        }
    }

    private suspend fun refresh() {
        try {
            val fetched = repo.liveProgram(id)
            // The full record can leave out image tags the listing had, which would swap the picture for the channel logo.
            val merged = fetched.copy(
                imageTags = fetched.imageTags?.takeIf { it.isNotEmpty() } ?: listed?.imageTags,
                channelPrimaryImageTag = fetched.channelPrimaryImageTag ?: listed?.channelPrimaryImageTag,
            )
            _state.update { it.copy(program = merged, error = null) }
        } catch (e: Exception) {
            if (_state.value.program == null) _state.update { it.copy(error = e.userMessage()) }
        }
    }

    private fun perform(work: suspend () -> Unit) {
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            try { work(); refresh() } catch (e: Exception) { _state.update { it.copy(message = e.message ?: "That didn't work.") } }
            _state.update { it.copy(working = false) }
        }
    }

    fun record() = perform { repo.recordProgram(id) }
    fun cancelRecording(timerId: String) = perform { repo.cancelTimer(timerId) }
    fun recordSeries() = perform { repo.recordSeries(id) }
    fun dismissMessage() = _state.update { it.copy(message = null) }
    fun toggleChannelFavorite() {
        val channel = _state.value.program?.channelId ?: return
        val now = !_state.value.channelFavorite
        _state.update { it.copy(channelFavorite = now) }
        viewModelScope.launch { runCatching { repo.setFavorite(channel, now) } }
    }
    /** After the series options page: the programme's record state may have changed. */
    fun reload() { viewModelScope.launch { refresh() } }
}

@Composable
fun ProgramScreen(viewModel: ProgramViewModel, onClose: () -> Unit, onWatch: (UUID) -> Unit, onSeriesOptions: (String) -> Unit, modifier: Modifier = Modifier) {
    val repo = (LocalContext.current.applicationContext as AsterApp).repository
    val state by viewModel.state.collectAsStateWithLifecycle()
    val program = state.program
    val tv = LocalIsTv.current
    // On a TV the remote starts on the page's main action (Watch), and Back closes the page: no close button to land on.
    val firstAction = remember { FocusRequester() }
    // The buttons aren't placed in the frame the programme arrives in, so a request then finds nothing to focus: try again until it takes.
    if (tv) LaunchedEffect(program != null) {
        if (program == null) return@LaunchedEffect
        repeat(20) {
            if (runCatching { firstAction.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(100)
        }
    }

    Box(modifier.fillMaxSize().background(Color(0xFF141414))) {
        if (program == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { if (state.error != null) Text(state.error!!, color = SecondaryText) else CircularProgressIndicator() }
        } else {
            AsyncImage(model = repo.programImageUrl(program, 1920), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.2f), Color.Black.copy(alpha = 0.75f), Color.Black.copy(alpha = 0.97f)))))
            val start = program.startDate?.atZone(java.time.ZoneId.systemDefault())?.toInstant()?.epochSecond?.toDouble()
            val end = program.endDate?.atZone(java.time.ZoneId.systemDefault())?.toInstant()?.epochSecond?.toDouble()
            val now = System.currentTimeMillis() / 1000.0
            val airing = start != null && end != null && now >= start && now < end
            val future = end != null && end > now
            val channelId = program.channelId

            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().navigationBarsPadding().padding(horizontal = Lockup.screenPadding).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(listOfNotNull(program.channelNumber, program.channelName).joinToString(" "), style = Type.title3, color = SecondaryText)
                Text(program.name.orEmpty(), style = Type.pageTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                program.episodeTitle?.let { Text(it, style = Type.title3) }
                // Too much for one line on a phone: the time, then the badges.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (start != null) Text("${dayLabel(start)}, ${timeRange(start, end)}", style = Type.callout, color = SecondaryText)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (airing) Badge("ON NOW")
                        if (program.isLive == true) Badge("LIVE")
                        if (program.isPremiere == true) Badge("NEW")
                        if (program.isRepeat == true) Badge("REPEAT")
                        program.officialRating?.let { Badge(it) }
                        program.genres?.takeIf { it.isNotEmpty() }?.let { Text(it.take(2).joinToString(", "), style = Type.callout, color = SecondaryText, maxLines = 1) }
                    }
                }
                program.overview?.let { Text(it, style = Type.callout, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 1000.dp)) }
                if (airing && start != null && end != null && end > start) {
                    val fraction = ((now - start) / (end - start)).toFloat().coerceIn(0.03f, 1f)
                    Box(Modifier.widthIn(max = 600.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                        Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(CircleShape).background(Color.White))
                    }
                }
                // What to do with this showing, then the series and the channel.
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Watch (on now) and Watch Channel (later) are never both shown.
                        if (airing && channelId != null) ActionButton("Watch", Icons.Default.PlayArrow, Modifier.focusRequester(firstAction), prominent = true) { onWatch(channelId) }
                        if (!airing && channelId != null) ActionButton("Watch Channel", Icons.Default.Tv, Modifier.focusRequester(firstAction)) { onWatch(channelId) }
                        if (future) {
                            val timer = program.timerId
                            if (timer != null) ActionButton("Cancel Recording", Icons.Default.Cancel) { if (!state.working) viewModel.cancelRecording(timer) }
                            else ActionButton("Record", Icons.Default.RadioButtonChecked, prominent = !airing) { if (!state.working) viewModel.record() }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (future && program.isSeries == true) {
                            val rule = program.seriesTimerId
                            if (rule != null) ActionButton("Series Options", Icons.Default.Tune) { onSeriesOptions(rule) }
                            else ActionButton("Record Series", Icons.Default.FiberManualRecord) { if (!state.working) viewModel.recordSeries() }
                        }
                        if (channelId != null) IconActionButton(if (state.channelFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (state.channelFavorite) "Remove channel from favorites" else "Favorite channel") { viewModel.toggleChannelFavorite() }
                        if (state.working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                    }
                }
            }
        }
        // There's no remote Back button: the page has a way to close.
        if (!tv) IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(Lockup.screenPadding).size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)),
        ) { Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(18.dp)) }
    }
    state.message?.let {
        AlertDialog(onDismissRequest = viewModel::dismissMessage, title = { Text("Live TV") }, text = { Text(it) }, confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } })
    }
}
