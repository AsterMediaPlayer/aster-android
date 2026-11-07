package com.astermediaplayer.astermediaplayer.ui.channels

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import org.jellyfin.sdk.model.api.ImageType
import com.astermediaplayer.astermediaplayer.data.channels.ChannelGroups
import com.astermediaplayer.astermediaplayer.ui.guide.GuideDetail
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.channels.PlaylistChannel
import com.astermediaplayer.astermediaplayer.data.channels.ScheduledProgram
import com.astermediaplayer.astermediaplayer.ui.guide.GuideChannel
import com.astermediaplayer.astermediaplayer.ui.guide.GuideProgram
import com.astermediaplayer.astermediaplayer.ui.guide.GuideView
import com.astermediaplayer.astermediaplayer.ui.guide.floorToHalfHour
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import org.jellyfin.sdk.model.api.BaseItemDto

/** How far ahead the Channels guide reaches, like a TV guide's week. */
private const val GUIDE_DAYS = 7

/** The Channels tab: playlists made into TV-style channels, laid out in a guide. */
@Composable
fun ChannelsScreen(onTune: (String) -> Unit, onOpen: (BaseItemDto) -> Unit, modifier: Modifier = Modifier, topInset: Dp = 0.dp, onEdit: (String?) -> Unit = {}) {
    val app = LocalContext.current.applicationContext as AsterApp
    val model = app.channels
    val channels by model.channels.collectAsStateWithLifecycle()
    val loaded by model.loaded.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    // The guide covers a week from when it was made: it's made again when the channels changed, or once it's an hour behind.
    var guideStart by remember { mutableStateOf(floorToHalfHour(PlaylistChannel.now())) }
    var guideChannels by remember { mutableStateOf<List<PlaylistChannel>>(emptyList()) }
    LaunchedEffect(Unit) {
        model.load(app.repository.session?.userName)
        if (guideChannels.isEmpty() || guideChannels != model.channels.value || PlaylistChannel.now() - guideStart > 3600) {
            guideStart = floorToHalfHour(PlaylistChannel.now())
            guideChannels = model.channels.value
        }
    }

    val tv = LocalIsTv.current
    val u = Lockup.tvUnit
    // The guide's group the filter shows; null for all of them.
    var group by remember { mutableStateOf<String?>(null) }
    // Only the administrator can change channels: for them Select on a TV guide's channel opens its settings (as on the Apple TV).
    val admin by produceState(false) { value = app.repository.canEditChannels() }
    // The Apple TV's guide keeps its own margin of 160 points.
    val inset = if (tv) 80.dp * u else 0.dp
    Column(modifier.fillMaxSize().then(if (tv) Modifier else Modifier.statusBarsPadding().padding(top = 8.dp + topInset))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Lockup.screenPadding + inset).padding(top = if (tv) (118f * u).dp else 0.dp, bottom = if (tv) 4.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Channels", style = if (tv) Type.pageTitle.copy(fontSize = (76f * u).sp, lineHeight = (90f * u).sp) else Type.pageTitle.copy(fontSize = 34.sp, lineHeight = 41.sp))
            // Where to change them: shown to everyone, who see the settings read-only.
            EditChannelsButton { onEdit(null) }
        }
        when {
            error != null && channels.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(error!!, color = SecondaryText) }
            !loaded -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            guideChannels.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                Text("Make a video playlist into a channel in Edit Channels, or tag it aster:channel in Jellyfin.", color = SecondaryText, style = Type.callout)
            }
            else -> {
                val groups = remember(guideChannels) { ChannelGroups.names(guideChannels) }
                val rows = remember(guideChannels, group) {
                    guideChannels.filter { group == null || (it.group ?: ChannelGroups.OTHER) == group }.map {
                        GuideChannel(it.id, "${it.number}", it.name, if (it.showsLogo) it.logoUrl(app.repository, 300) else null, group = if (groups.isEmpty()) null else it.group ?: ChannelGroups.OTHER)
                    }
                }
                val guideEnd = guideStart + GUIDE_DAYS * 24 * 3600.0
                // Each row's programmes are worked out from the schedule as the row comes into view.
                val listings = remember(guideChannels, guideStart) { mutableStateMapOf<String, List<GuideProgram>>() }
                val scope = rememberCoroutineScope()
                GuideView(
                    channels = rows, guideStart = guideStart, guideEnd = guideEnd,
                    programs = { listings[it.id] },
                    ensure = { row ->
                        if (row.id !in listings) scope.launch {
                            val channel = guideChannels.firstOrNull { it.id == row.id } ?: return@launch
                            listings[row.id] = withContext(Dispatchers.Default) { channel.programs(guideStart, guideEnd).map { it.toListing() } }
                        }
                    },
                    // What's on now tunes in; anything later opens its page.
                    onProgram = { row, program ->
                        val scheduled = program.payload as ScheduledProgram
                        if (PlaylistChannel.now().let { scheduled.start <= it && it < scheduled.end }) onTune(row.id)
                        else (scheduled.item.source as? BaseItemDto)?.let(onOpen)
                    },
                    onChannel = { if (tv && admin) onEdit(it.id) else onTune(it.id) },
                    bottomPadding = 120.dp,
                    inset = inset,
                    belowTimes = if (tv && groups.isNotEmpty()) ({ TvGroupPills(listOf<String?>(null) + groups, group, Modifier.padding(horizontal = Lockup.screenPadding + inset)) { group = it } }) else null,
                    detail = if (!tv) null else { _, program ->
                        val scheduled = program.payload as? ScheduledProgram
                        val dto = scheduled?.item?.source as? BaseItemDto
                        GuideDetail(
                            title = if (scheduled?.item?.isEpisode == true && scheduled.item.seriesName != null) "${scheduled.item.seriesName}: ${scheduled.item.name.orEmpty()}" else program.title,
                            overview = dto?.overview,
                            imageUrl = dto?.let { app.repository.imageUrl(it, ImageType.PRIMARY, 400) ?: app.repository.seriesPosterUrl(it, 400) },
                        )
                    },
                )
            }
        }
    }
}

/** An episode is listed as the series, with the episode's name under it, as a TV guide does. */
private fun ScheduledProgram.toListing(): GuideProgram {
    val item = item
    return if (item.isEpisode && item.seriesName != null) GuideProgram(id, item.seriesName, item.name, start, end, payload = this)
    else GuideProgram(id, item.name.orEmpty(), null, start, end, payload = this)
}

/** The Apple TV guide's row of group filters: All, then each group, the picked one lit. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TvGroupPills(options: List<String?>, selected: String?, modifier: Modifier = Modifier, onPick: (String?) -> Unit) {
    val u = Lockup.tvUnit
    // Focus arriving from the guide or the times lands on the picked pill, not whichever is nearest.
    val pickedPill = remember { FocusRequester() }
    Row(modifier.padding(vertical = (10f * u).dp).focusProperties { enter = { pickedPill } }.focusGroup(), horizontalArrangement = Arrangement.spacedBy((14f * u).dp)) {
        options.forEach { option ->
            var focused by remember { mutableStateOf(false) }
            val picked = option == selected
            Box(
                Modifier.height((66f * u).dp).then(if (picked) Modifier.focusRequester(pickedPill) else Modifier).tvFocus((33f * u).dp, lift = 1.08f, ring = false).onFocusChanged { focused = it.isFocused }
                    .clip(CircleShape).background(if (focused) Color.White else if (picked) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.12f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(option) }.padding(horizontal = (36f * u).dp),
                contentAlignment = Alignment.Center,
            ) { Text(option ?: "All", style = Type.calloutMedium, color = if (focused) Color.Black else Color.White, maxLines = 1) }
        }
    }
}

/** The Apple TV's Edit Channels button, beside the title. */
@Composable
private fun EditChannelsButton(onClick: () -> Unit) {
    val u = Lockup.tvUnit
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.height((68f * u).dp).tvFocus((34f * u).dp, lift = 1.1f, ring = false).onFocusChanged { focused = it.isFocused }
            .clip(CircleShape).background(if (focused) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick).padding(horizontal = (30f * u).dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((12f * u).dp),
    ) {
        val tint = if (focused) Color.Black else Color.White
        Icon(Icons.Default.Tune, null, tint = tint, modifier = Modifier.size((34f * u).dp))
        Text("Edit Channels", style = Type.calloutMedium, color = tint, maxLines = 1)
    }
}
