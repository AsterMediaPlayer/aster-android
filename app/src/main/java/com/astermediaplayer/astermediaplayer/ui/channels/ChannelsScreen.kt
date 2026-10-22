package com.astermediaplayer.astermediaplayer.ui.channels

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import com.astermediaplayer.astermediaplayer.ui.guide.GUIDE_DAYS
import com.astermediaplayer.astermediaplayer.ui.guide.floorToHalfHour
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import org.jellyfin.sdk.model.api.BaseItemDto

/** The Channels tab: playlists made into TV-style channels, laid out in a guide. */
@Composable
fun ChannelsScreen(onTune: (String) -> Unit, onOpen: (BaseItemDto) -> Unit, modifier: Modifier = Modifier, topInset: Dp = 0.dp) {
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

    Column(modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp + topInset)) {
        Text(
            "Channels (Beta)", style = Type.pageTitle.copy(fontSize = 34.sp, lineHeight = 41.sp),
            modifier = Modifier.padding(horizontal = Lockup.screenPadding).padding(bottom = 10.dp),
        )
        when {
            error != null && channels.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(error!!, color = SecondaryText) }
            !loaded -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            guideChannels.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                Text("Make a video playlist into a channel in Edit Channels, or tag it aster:channel in Jellyfin.", color = SecondaryText, style = Type.callout)
            }
            else -> {
                val rows = remember(guideChannels) {
                    guideChannels.map { GuideChannel(it.id, "${it.number}", it.name, if (it.showsLogo) it.logoUrl(app.repository, 300) else null) }
                }
                GuideView(
                    channels = rows, guideStart = guideStart,
                    programs = { row, from, to ->
                        val channel = guideChannels.first { it.id == row.id }
                        channel.programs(from, to).map { it.toListing() }
                    },
                    // What's on now tunes in; anything later opens its page.
                    onProgram = { row, program ->
                        val scheduled = program.payload as ScheduledProgram
                        if (PlaylistChannel.now().let { scheduled.start <= it && it < scheduled.end }) onTune(row.id)
                        else (scheduled.item.source as? BaseItemDto)?.let(onOpen)
                    },
                    onChannel = { onTune(it.id) },
                    bottomPadding = 120.dp,
                )
            }
        }
    }
}

/** An episode is listed as the series, with the episode's name under it, as a TV guide does. */
private fun ScheduledProgram.toListing(): GuideProgram {
    val item = item
    return if (item.isEpisode && item.seriesName != null) GuideProgram(id, item.seriesName, item.name, start, end, this)
    else GuideProgram(id, item.name.orEmpty(), null, start, end, this)
}
