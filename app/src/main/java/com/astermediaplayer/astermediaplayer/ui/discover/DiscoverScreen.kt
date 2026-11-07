package com.astermediaplayer.astermediaplayer.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.DiscoverItem
import com.astermediaplayer.astermediaplayer.data.DiscoverPage
import com.astermediaplayer.astermediaplayer.data.DiscoverRow
import com.astermediaplayer.astermediaplayer.ui.components.ErrorBox
import com.astermediaplayer.astermediaplayer.ui.components.Load
import com.astermediaplayer.astermediaplayer.ui.components.LoadingBox
import com.astermediaplayer.astermediaplayer.ui.components.cardClickable
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/**
 * The Discover page, as the server's Aster Discovery and Tracking plugin builds it for the signed-in person: Recommended for
 * You, Because You Watched, On Your Server, their watchlists, trending. One request; the rows are shown in the order given.
 */
class DiscoverViewModel(private val repo: AsterRepository) : ViewModel() {
    private val _state = MutableStateFlow<Load<DiscoverPage>>(Load.Loading)
    val state: StateFlow<Load<DiscoverPage>> = _state.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init { load() }

    /** [fresh]: pull to refresh, which has the server build the page again now. What's showing stays meanwhile. */
    fun load(fresh: Boolean = false) {
        viewModelScope.launch {
            if (fresh) _refreshing.value = true else if (_state.value !is Load.Ready) _state.value = Load.Loading
            try {
                _state.value = Load.Ready(repo.discoverHome(fresh))
            } catch (e: Exception) {
                if (_state.value !is Load.Ready) _state.value = Load.Failed("Discover isn't available right now (${e.userMessage()}).")
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** The server's own title behind a card, to open its page. */
    suspend fun libraryItem(item: DiscoverItem): BaseItemDto? =
        item.inLibrary?.itemId?.let(::parseId)?.let { id -> runCatching { repo.item(id) }.getOrNull() }

    private fun parseId(id: String): UUID? = runCatching {
        UUID.fromString(if (id.length == 32) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id)
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(viewModel: DiscoverViewModel, onItem: (BaseItemDto) -> Unit, onPlay: (UUID) -> Unit, modifier: Modifier = Modifier, topInset: androidx.compose.ui.unit.Dp = 0.dp) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf<DiscoverItem?>(null) }
    // A title on the server opens its page; anything else, what's known of it.
    fun open(item: DiscoverItem) {
        if (item.inLibrary == null) { shown = item; return }
        scope.launch { viewModel.libraryItem(item)?.let(onItem) ?: run { shown = item } }
    }

    when (val s = state) {
        Load.Loading -> LoadingBox(modifier)
        is Load.Failed -> ErrorBox(s.message, { viewModel.load() }, modifier)
        is Load.Ready -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = { viewModel.load(fresh = true) }, modifier = modifier.fillMaxSize()) {
            if (s.value.rows.isEmpty()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp).padding(top = topInset), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Explore, null, tint = SecondaryText, modifier = Modifier.size(48.dp))
                    Text("Nothing to discover yet", style = Type.title3, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        "Watch a few things, or link your Simkl, AniList or MyAnimeList account, and recommendations show up here.",
                        style = Type.callout, color = SecondaryText, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
                val firstCard = remember { FocusRequester() }
                val isTv = LocalIsTv.current
                // A remote lands on the first card, so the page is usable at once.
                LaunchedEffect(Unit) { if (isTv) runCatching { firstCard.requestFocus() } }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = topInset, bottom = TabBarClearance),
                    verticalArrangement = Arrangement.spacedBy(if (isTv) Lockup.sectionSpacing else 20.dp),
                ) {
                    Text("Discover", style = Type.pageTitle, modifier = Modifier.padding(horizontal = Lockup.screenPadding))
                    s.value.rows.forEachIndexed { index, row ->
                        DiscoverShelf(row, ::open, if (index == 0) firstCard else null)
                    }
                }
            }
        }
    }

    shown?.let { item -> DiscoverItemDialog(item, onDismiss = { shown = null }, onPlay = onPlay) }
}

@Composable
private fun DiscoverShelf(row: DiscoverRow, onItem: (DiscoverItem) -> Unit, firstCardFocus: FocusRequester?) {
    Column(Modifier.graphicsLayer(), verticalArrangement = Arrangement.spacedBy(Lockup.shelfTitleSpacing)) {
        Column(Modifier.padding(horizontal = Lockup.screenPadding)) {
            Text(row.title, style = Type.title3)
            row.subtitle?.let { Text(it, style = Type.caption, color = SecondaryText) }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Lockup.screenPadding, vertical = Lockup.shelfVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing),
        ) {
            items(row.items, key = { it.key }) { item ->
                val lead = if (firstCardFocus != null && item === row.items.first()) Modifier.focusRequester(firstCardFocus) else Modifier
                DiscoverCard(item, { onItem(item) }, lead)
            }
        }
    }
}

@Composable
private fun posterUrl(item: DiscoverItem): String? =
    (LocalContext.current.applicationContext as AsterApp).repository.let { repo -> remember(item.poster) { runCatching { repo.discoverPosterUrl(item.poster) }.getOrNull() } }

@Composable
private fun DiscoverCard(item: DiscoverItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.width(Lockup.posterWidth).tvFocus(Lockup.cornerRadius, lift = 1.08f, ring = false, pictureAspect = Lockup.POSTER_ASPECT).cardClickable(onClick),
        verticalArrangement = Arrangement.spacedBy(Lockup.cardLabelSpacing),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(Lockup.POSTER_ASPECT).clip(RoundedCornerShape(Lockup.cornerRadius)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            val url = posterUrl(item)
            if (url == null) {
                Text(item.title, style = Type.callout, textAlign = TextAlign.Center, color = SecondaryText, modifier = Modifier.align(Alignment.Center).padding(12.dp))
            } else {
                AsyncImage(model = url, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (item.inLibrary != null) OnServerBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Column(Modifier.width(Lockup.posterWidth), verticalArrangement = Arrangement.spacedBy(Lockup.cardTextSpacing)) {
            Text(item.title, style = Type.calloutMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let { Text(it, style = Type.caption, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun OnServerBadge(modifier: Modifier = Modifier) {
    Text(
        "On Server",
        style = Type.caption, fontWeight = FontWeight.SemiBold, color = Color.White,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** What's known of a title that isn't on the server (or couldn't be opened there). Requesting it comes with Seerr. */
@Composable
private fun DiscoverItemDialog(item: DiscoverItem, onDismiss: () -> Unit, onPlay: (UUID) -> Unit) {
    val playable = item.inLibrary?.itemId?.let { id -> runCatching { UUID.fromString(if (id.length == 32) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id) }.getOrNull() }
    val close = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (playable != null) {
                Button(onClick = { onDismiss(); onPlay(playable) }) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text("Play", Modifier.padding(start = 6.dp))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(close)) { Text("Close") } },
        title = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                posterUrl(item)?.let { url ->
                    AsyncImage(url, item.title, contentScale = ContentScale.Crop, modifier = Modifier.width(96.dp).aspectRatio(Lockup.POSTER_ASPECT).clip(RoundedCornerShape(8.dp)))
                }
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.subtitle?.let { Text(it, style = Type.caption, color = SecondaryText) }
                    item.overview?.let { Text(it, style = Type.callout) }
                    Text(
                        if (item.inLibrary != null) "On your server." else "Not on your server.",
                        style = Type.caption, color = SecondaryText,
                    )
                }
            }
        },
    )
}
