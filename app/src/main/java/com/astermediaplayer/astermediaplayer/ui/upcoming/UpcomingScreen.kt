package com.astermediaplayer.astermediaplayer.ui.upcoming

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.UpcomingSchedule
import com.astermediaplayer.astermediaplayer.ui.components.ErrorBox
import com.astermediaplayer.astermediaplayer.ui.components.Load
import com.astermediaplayer.astermediaplayer.ui.components.LoadingBox
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import com.astermediaplayer.astermediaplayer.ui.home.FeaturedBanner
import com.astermediaplayer.astermediaplayer.ui.home.HeroAction
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import java.time.LocalDate
import java.util.UUID

/** The Upcoming page: the days with episodes airing and, over them, the hero of the next ones. */
class UpcomingViewModel(private val repo: AsterRepository) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<Pair<LocalDate, List<BaseItemDto>>>>>(Load.Loading)
    val state: StateFlow<Load<List<Pair<LocalDate, List<BaseItemDto>>>>> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            // What's showing stays while it reloads.
            if (_state.value !is Load.Ready) _state.value = Load.Loading
            _state.value = try {
                Load.Ready(UpcomingSchedule.days(repo.upcoming()))
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
        }
    }
}

@Composable
fun UpcomingScreen(viewModel: UpcomingViewModel, onItem: (BaseItemDto) -> Unit, onPlay: (UUID) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        Load.Loading -> LoadingBox(modifier)
        is Load.Failed -> ErrorBox(s.message, viewModel::load, modifier)
        is Load.Ready -> if (s.value.isEmpty()) {
            Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.CalendarMonth, null, tint = SecondaryText, modifier = Modifier.size(48.dp))
                Text("Nothing coming up", style = Type.title3, modifier = Modifier.padding(top = 8.dp))
                Text("Episodes of shows in your library that air soon show up here.", style = Type.callout, color = SecondaryText, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
        } else {
            val featured = s.value.flatMap { it.second }
            val dayByEpisode = s.value.flatMap { (day, episodes) -> episodes.map { it.id to day } }.toMap()
            Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = TabBarClearance), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                FeaturedBanner(
                    featured, onItem,
                    tag = { e -> dayByEpisode[e.id]?.let { UpcomingSchedule.countdown(it) } },
                    action = { e -> if (UpcomingSchedule.isAvailable(e)) HeroAction("Watch Now", Icons.Default.PlayArrow) else HeroAction("Go to Show", Icons.Default.Tv) },
                    destination = UpcomingSchedule::destination,
                )
                s.value.forEach { (day, episodes) ->
                    Column(verticalArrangement = Arrangement.spacedBy(Lockup.shelfTitleSpacing)) {
                        Text(UpcomingSchedule.heading(day), style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Lockup.screenPadding, vertical = Lockup.shelfVerticalPadding),
                            horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing),
                        ) {
                            items(episodes, key = { it.id }) { episode -> UpcomingCard(episode) { onItem(UpcomingSchedule.destination(episode)) } }
                        }
                    }
                }
            }
        }
    }
}

/** One upcoming episode: the show's picture, its logo or name, and the episode, marked when its file is already on the server. */
@Composable
private fun UpcomingCard(episode: BaseItemDto, onClick: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as AsterApp).repository
    val picture = repo.imageUrl(episode, ImageType.BACKDROP, 800) ?: repo.imageUrl(episode, ImageType.PRIMARY, 800)
    val logo = repo.imageUrl(episode, ImageType.LOGO, 400)
    val title = episode.seriesName ?: episode.name.orEmpty()
    Box(
        Modifier.width(Lockup.landscapeWidth).height(Lockup.landscapeWidth / Lockup.LANDSCAPE_ASPECT)
            .clip(RoundedCornerShape(Lockup.cornerRadius)).background(Color(0xFF1C1C1E)).clickable(onClick = onClick),
    ) {
        AsyncImage(model = picture, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (logo != null) {
                Box(Modifier.widthIn(max = Lockup.landscapeWidth * 0.55f).height(Lockup.landscapeWidth / Lockup.LANDSCAPE_ASPECT * 0.28f)) {
                    AsyncImage(model = logo, contentDescription = title, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.fillMaxSize())
                }
            } else {
                Text(title, style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                UpcomingSchedule.episodeLine(episode), style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = Color.White.copy(alpha = 0.85f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (UpcomingSchedule.isAvailable(episode)) {
            Text(
                "Available", style = Type.caption2Bold, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
