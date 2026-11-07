package com.astermediaplayer.astermediaplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

/**
 * A card's tap, and its long press (holding Select on a TV) when it has one. On a TV the focus draws the card's own lift and
 * shadow, so the default highlight over the whole card (label too) is off.
 */
@Composable
internal fun Modifier.cardClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    if (LocalIsTv.current) combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onLongClick = onLongClick, onClick = onClick)
    else combinedClickable(onLongClick = onLongClick, onClick = onClick)

/**
 * A title card with its long-press menu ([ItemActionsMenu]) where the title has one. [content] draws the card from the item
 * as last changed by the menu, so marking it watched or a favourite shows at once.
 */
@Composable
private fun CardWithActions(
    item: BaseItemDto,
    modifier: Modifier,
    customMenu: CardMenu? = null,
    content: @Composable (shown: BaseItemDto, onLongClick: (() -> Unit)?) -> Unit,
) {
    var shown by remember(item) { mutableStateOf(item) }
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        content(shown, if (customMenu != null || hasItemActions(item)) ({ menu = true }) else null)
        when {
            !menu -> {}
            customMenu != null -> com.astermediaplayer.astermediaplayer.ui.detail.MenuHost(expanded = true, onDismiss = { menu = false }) { customMenu { menu = false } }
            else -> ItemActionsMenu(shown, expanded = true, onDismiss = { menu = false }, onChange = { shown = it })
        }
    }
}

/** A page's own long-press menu for a card, in place of [ItemActionsMenu]; it's handed what closes it. */
typealias CardMenu = @Composable androidx.compose.foundation.layout.ColumnScope.(dismiss: () -> Unit) -> Unit

@Composable
private fun repo() = (LocalContext.current.applicationContext as AsterApp).repository

/** Fraction watched, 0..1, or null when there's no resume point. */
fun BaseItemDto.progress(): Float? {
    val position = userData?.playbackPositionTicks ?: return null
    val total = runTimeTicks ?: return null
    if (position <= 0 || total <= 0) return null
    return (position.toFloat() / total).coerceIn(0f, 1f)
}

/** Title shown under a card; episodes lead with their series unless the page is already that series'. */
fun BaseItemDto.cardTitle(showSeriesTitle: Boolean = true): String =
    if (type == BaseItemKind.EPISODE && showSeriesTitle) seriesName ?: name.orEmpty() else name.orEmpty()

/** "S2, E1", or "Episode 3" without a season. */
fun BaseItemDto.episodeCode(): String? {
    val episode = indexNumber ?: return null
    return parentIndexNumber?.let { "S$it, E$episode" } ?: "Episode $episode"
}

fun BaseItemDto.cardSubtitle(showSeriesTitle: Boolean = true): String? = when (type) {
    BaseItemKind.EPISODE -> listOfNotNull(episodeCode(), if (showSeriesTitle) name else null).joinToString(" · ").ifEmpty { null }
    BaseItemKind.MUSIC_VIDEO -> listOfNotNull(album, artists?.filter { it.isNotEmpty() }?.joinToString(", ")?.ifEmpty { null })
        .joinToString(" · ").ifEmpty { null }
    else -> productionYear?.toString()
}

/** The Apple apps' unwatched-episode dot. */
@Composable
private fun UnplayedBadge(modifier: Modifier = Modifier) {
    Box(modifier.size(16.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
}

/** The Apple apps' progress bar: a white capsule on a faint track. */
@Composable
private fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxWidth(progress.coerceAtLeast(0.03f)).height(6.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun CardLabel(title: String, subtitle: String?, width: Dp) {
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(Lockup.cardTextSpacing)) {
        Text(title, style = Type.calloutMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, style = Type.caption, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
fun PosterCard(item: BaseItemDto, onClick: () -> Unit, modifier: Modifier = Modifier) = CardWithActions(item, modifier) { item, onLongClick ->
    val url = posterUrl(repo(), item)
    Column(Modifier.width(Lockup.posterWidth).tvFocus(Lockup.cornerRadius, lift = 1.08f, ring = false, pictureAspect = Lockup.POSTER_ASPECT).cardClickable(onClick, onLongClick), verticalArrangement = Arrangement.spacedBy(Lockup.cardLabelSpacing)) {
        Box(Modifier.fillMaxWidth().aspectRatio(Lockup.POSTER_ASPECT).clip(RoundedCornerShape(Lockup.cornerRadius))) {
            AsyncImage(model = url, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            item.progress()?.let { ProgressBar(it, Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp).padding(bottom = 10.dp)) }
        }
        CardLabel(item.cardTitle(), item.cardSubtitle(), Lockup.posterWidth)
    }
}

/** 16:9 card for Continue Watching, Up Next and episode rows, using the episode still or the backdrop. */
@Composable
fun LandscapeCard(
    item: BaseItemDto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showSeriesTitle: Boolean = true,
    /** What its long press offers, when not the usual menu. */
    menu: CardMenu? = null,
) = CardWithActions(item, modifier, menu) { item, onLongClick ->
    val repo = repo()
    val url = landscapeUrl(repo, item)
    Column(Modifier.width(Lockup.landscapeWidth).tvFocus(Lockup.cornerRadius, lift = 1.08f, ring = false, pictureAspect = Lockup.LANDSCAPE_ASPECT).cardClickable(onClick, onLongClick), verticalArrangement = Arrangement.spacedBy(Lockup.cardLabelSpacing)) {
        Box(Modifier.fillMaxWidth().aspectRatio(Lockup.LANDSCAPE_ASPECT).clip(RoundedCornerShape(Lockup.cornerRadius))) {
            AsyncImage(model = url, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            item.progress()?.let { ProgressBar(it, Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp).padding(bottom = 12.dp)) }
            if (item.type == BaseItemKind.EPISODE && item.userData?.played != true && item.progress() == null) {
                UnplayedBadge(Modifier.align(Alignment.TopEnd).padding(12.dp))
            }
        }
        CardLabel(item.cardTitle(showSeriesTitle), item.cardSubtitle(showSeriesTitle), Lockup.landscapeWidth)
    }
}

/** A poster card's picture: what [PosterCard] shows, and what a shelf warms ahead. */
internal fun posterUrl(repo: AsterRepository, item: BaseItemDto): String? = repo.imageUrl(item, ImageType.PRIMARY, 400)

/** A landscape card's picture: the episode's still, else the backdrop. */
internal fun landscapeUrl(repo: AsterRepository, item: BaseItemDto): String? =
    if (item.type == BaseItemKind.EPISODE && item.imageTags?.containsKey(ImageType.PRIMARY) == true) {
        repo.imageUrl(item, ImageType.PRIMARY, 600)
    } else {
        repo.imageUrl(item, ImageType.BACKDROP, 600) ?: repo.imageUrl(item, ImageType.PRIMARY, 600)
    }

/** A titled row of cards, as the Apple apps' `Shelf`. Every row has the same title size, gaps and padding. */
@Composable
fun Shelf(
    title: String,
    items: List<BaseItemDto>,
    landscape: Boolean = false,
    modifier: Modifier = Modifier,
    /** False on a show's own page, where an episode's card needn't name the show. */
    showSeriesTitle: Boolean = true,
    listState: LazyListState = rememberLazyListState(),
    /** Takes focus when asked: the first card, for a remote that has just picked the tab this shelf is in. */
    firstCardFocus: FocusRequester? = null,
    onItem: (BaseItemDto) -> Unit,
) {
    if (items.isEmpty()) return
    // The cards past the edge, into the disk cache before they're scrolled to (as the Apple apps warm a page's posters).
    val context = LocalContext.current
    LaunchedEffect(items, landscape) {
        val repo = (context.applicationContext as AsterApp).repository
        ImageWarmer.warm(context, items.drop(4).mapNotNull { if (landscape) landscapeUrl(repo, it) else posterUrl(repo, it) })
    }
    // Each shelf is its own layer, so a focus ring moving in one redraws that shelf and not the whole page.
    Column(modifier.graphicsLayer(), verticalArrangement = Arrangement.spacedBy(Lockup.shelfTitleSpacing)) {
        Text(title, style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding))
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = Lockup.screenPadding, vertical = Lockup.shelfVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing),
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                val lead = if (index == 0 && firstCardFocus != null) Modifier.focusRequester(firstCardFocus) else Modifier
                if (landscape) LandscapeCard(item, { onItem(item) }, lead, showSeriesTitle = showSeriesTitle) else PosterCard(item, { onItem(item) }, lead)
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onRetry != null) Button(onClick = onRetry) { Text("Try again") }
        }
    }
}
