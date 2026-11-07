package com.astermediaplayer.astermediaplayer.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.DiscoverItem
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrBrowse
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrCompany
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrFilter
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrGenre
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrImages
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrTitle
import com.astermediaplayer.astermediaplayer.ui.components.cardClickable
import com.astermediaplayer.astermediaplayer.ui.detail.MenuHost
import com.astermediaplayer.astermediaplayer.ui.detail.MenuItem
import com.astermediaplayer.astermediaplayer.ui.theme.ButtonGrey
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/** Where Discover's cards lead: a library title, a Seerr title's page, a genre's or company's grid, or playback. */
class DiscoverNav(
    val openItem: (BaseItemDto) -> Unit,
    val openTitle: (SeerrTitle) -> Unit,
    /** A Discover card's title page: Seerr's with Seerr, else its details and the watchlist. */
    val openCard: (SeerrTitle, com.astermediaplayer.astermediaplayer.data.DiscoverItem) -> Unit,
    val openBrowse: (SeerrBrowse) -> Unit,
    val play: (UUID) -> Unit,
)

val LocalDiscoverNav = staticCompositionLocalOf<DiscoverNav?> { null }

/**
 * What a pushed Seerr page shows, kept here by a key in its route (a title or a filter doesn't fit in one). A page whose key
 * is gone (the app was closed meanwhile) goes back.
 */
object DiscoverRoutes {
    private val titles = mutableMapOf<String, SeerrTitle>()
    private val items = mutableMapOf<String, com.astermediaplayer.astermediaplayer.data.DiscoverItem>()
    private val browses = mutableMapOf<String, SeerrBrowse>()

    fun put(title: SeerrTitle): String = title.key.also { titles[it] = title }
    fun title(key: String): SeerrTitle? = titles[key]
    /** A Discover card's title, with the card kept for its ids and poster: Seerr's page, or without Seerr just its details. */
    fun put(title: SeerrTitle, item: com.astermediaplayer.astermediaplayer.data.DiscoverItem): String =
        "item-${UUID.randomUUID()}".also { titles[it] = title; items[it] = item }
    fun item(key: String): com.astermediaplayer.astermediaplayer.data.DiscoverItem? = items[key]
    fun put(browse: SeerrBrowse): String = UUID.randomUUID().toString().also { browses[it] = browse }
    fun browse(key: String): SeerrBrowse? = browses[key]
}

/**
 * Discover's side margin. On a TV the Apple TV's: its 80-point page margin inside the screen's 80-point safe area, so the
 * rows start 160 points in; on a phone the usual margin.
 */
internal val discoverInset: Dp
    @Composable get() = if (LocalIsTv.current) Lockup.screenPadding + pt(80f) else Lockup.screenPadding

/** A point on the Apple TV, in dp here (see [Lockup.tvUnit]). */
@Composable
internal fun pt(points: Float): Dp = (points * Lockup.tvUnit).dp

/** The Apple apps' chip: grey, lighter (white tint) when on; on a TV white with black text when focused. */
@Composable
internal fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, secondLine: String? = null, onClick: () -> Unit) {
    val tv = LocalIsTv.current
    var focused by remember { mutableStateOf(false) }
    val height = if (tv) pt(if (secondLine != null) 86f else 66f) else if (secondLine != null) 46.dp else 34.dp
    val lit = focused && tv
    val fg = when {
        lit -> Color.Black
        selected -> Color.Black
        else -> Color.White
    }
    Row(
        modifier.height(height).tvFocus(height / 2, lift = 1.1f, ring = false).onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(if (lit) Color.White else if (selected) Color.White.copy(alpha = 0.9f) else ButtonGrey.copy(alpha = if (tv) 0.45f else 0.6f))
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = if (tv) pt(30f) else 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (tv) pt(10f) else 6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(if (tv) pt(28f) else 16.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = Type.calloutSemibold, color = fg, maxLines = 1)
            secondLine?.let { Text(it, style = Type.caption, color = fg.copy(alpha = 0.75f), maxLines = 1) }
        }
    }
}

/** A chip that opens a menu of choices below it, the chosen one ticked. */
@Composable
internal fun ChipMenu(label: String, selected: Boolean, choices: List<String>, chosen: Int, icon: ImageVector? = null, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip(label, selected, icon = icon) { open = true }
        MenuHost(expanded = open, onDismiss = { open = false }, below = true) {
            choices.forEachIndexed { i, text ->
                MenuItem(text, null, first = i == chosen.coerceAtLeast(0), checked = i == chosen) { open = false; onPick(i) }
            }
        }
    }
}

/** Discover's sections: a segmented control, wide beside the title on a TV, the full width under it on a phone. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun SectionPicker(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val tv = LocalIsTv.current
    // Coming in from above or below, focus lands on the section that's showing, as on the Apple TV.
    val requesters = remember(labels.size) { List(labels.size) { FocusRequester() } }
    Row(
        modifier.focusProperties { enter = { requesters.getOrNull(selected) ?: FocusRequester.Default } }.focusGroup()
            .then(if (tv) Modifier.width(pt(220f * labels.size)) else Modifier.widthIn(max = 560.dp).fillMaxWidth())
            .height(if (tv) pt(92f) else 32.dp).clip(CircleShape)
            .then(if (tv) Modifier.background(Color.Black.copy(alpha = 0.32f)).border(pt(2f), Color.White.copy(alpha = 0.1f), CircleShape) else Modifier.background(Color.White.copy(alpha = 0.19f)))
            .padding(if (tv) pt(10f) else 2.dp),
    ) {
        labels.forEachIndexed { index, label ->
            var focused by remember { mutableStateOf(false) }
            Box(
                Modifier.weight(1f).fillMaxSize().focusRequester(requesters[index]).onFocusChanged { focused = it.isFocused }.clip(CircleShape)
                    .background(if (focused) Color.White else if (index == selected) Color.White.copy(alpha = if (tv) 0.3f else 0.21f) else Color.Transparent)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = if (tv) Type.calloutMedium else Type.segmented, color = if (focused) Color.Black else Color.White, maxLines = 1) }
        }
    }
}

/** A titled row, as the library's shelves. */
@Composable
internal fun <T> DiscoverShelf(title: String, items: List<T>, key: (T) -> String, subtitle: String? = null, firstFocus: FocusRequester? = null, card: @Composable (T, Modifier) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.graphicsLayer(), verticalArrangement = Arrangement.spacedBy(Lockup.shelfTitleSpacing)) {
        Column(Modifier.padding(horizontal = discoverInset)) {
            Text(title, style = Type.title3)
            subtitle?.let { Text(it, style = Type.caption, color = SecondaryText) }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = discoverInset, vertical = Lockup.shelfVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing),
        ) {
            items(items, key = key) { item ->
                card(item, if (firstFocus != null && item === items.first()) Modifier.focusRequester(firstFocus) else Modifier)
            }
        }
    }
}

/** A small capsule on a picture's corner. */
@Composable
internal fun Badge(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Text(
        text, style = Type.caption2Bold, color = Color.White, maxLines = 1,
        modifier = modifier.padding(8.dp).clip(CircleShape)
            .background(if (accent) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** A poster card: its picture (or a stand-in), a corner badge, and two lines under it. */
@Composable
internal fun PosterLockup(url: String?, title: String, subtitle: String?, badge: String?, movie: Boolean, modifier: Modifier = Modifier, width: Dp = Lockup.posterWidth, labels: Boolean = true, onClick: () -> Unit) {
    Column(
        modifier.width(width).tvFocus(Lockup.cornerRadius, lift = 1.08f, ring = false, pictureAspect = Lockup.POSTER_ASPECT).cardClickable(onClick),
        verticalArrangement = Arrangement.spacedBy(Lockup.cardLabelSpacing),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(Lockup.POSTER_ASPECT).clip(RoundedCornerShape(Lockup.cornerRadius)).background(Color(0xFF262626))) {
            if (url == null) {
                Icon(if (movie) Icons.Default.Movie else Icons.Default.Tv, null, tint = SecondaryText, modifier = Modifier.align(Alignment.Center).size(40.dp))
            } else {
                AsyncImage(model = url, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            badge?.let { Badge(it, Modifier.align(Alignment.TopEnd)) }
        }
        if (labels) Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(Lockup.cardTextSpacing)) {
            Text(title, style = Type.calloutMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = Type.caption, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** A title to request, as a library item looks; it opens its title page. */
@Composable
internal fun SeerrTitleLockup(title: SeerrTitle, modifier: Modifier = Modifier, width: Dp = Lockup.posterWidth, labels: Boolean = true) {
    val nav = LocalDiscoverNav.current
    PosterLockup(
        title.posterUrl, title.displayTitle, listOfNotNull(title.year, if (title.isMovie) "Film" else "Series").joinToString(" · "),
        if (labels) title.status.label else null, title.isMovie, modifier, width, labels,
    ) { nav?.openTitle?.invoke(title) }
}

/** A title from the server's page, like a library title: "On Server" when it's there to play. */
@Composable
internal fun TrackedLockup(item: DiscoverItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val repo = (LocalContext.current.applicationContext as AsterApp).repository
    val url = remember(item.poster) { runCatching { repo.discoverPosterUrl(item.poster) }.getOrNull() }
    PosterLockup(url, item.title, item.subtitle, if (item.inLibrary != null) "On Server" else null, item.mediaType.equals("movie", true), modifier, onClick = onClick)
}

/** A title as a wide picture with its name on it, for Trending. */
@Composable
internal fun SeerrBackdropLockup(title: SeerrTitle, modifier: Modifier = Modifier) {
    val nav = LocalDiscoverNav.current
    val tv = LocalIsTv.current
    val width = if (tv) pt(640f) else 300.dp
    Box(
        modifier.width(width).aspectRatio(16f / 9f).tvFocus(Lockup.cornerRadius, lift = 1.08f, ring = false, pictureAspect = 16f / 9f)
            .clip(RoundedCornerShape(Lockup.cornerRadius)).background(Color(0xFF262626)).cardClickable(onClick = { nav?.openTitle?.invoke(title) }),
    ) {
        AsyncImage(SeerrImages.image(title.backdropPath, "w780") ?: title.posterUrl, title.displayTitle, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        Column(Modifier.align(Alignment.BottomStart).padding(if (tv) pt(24f) else 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title.displayTitle, style = Type.headline, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(title.year, if (title.isMovie) "Film" else "Series", title.status.label).joinToString(" · "), style = Type.caption, color = Color.White.copy(alpha = 0.75f))
        }
    }
}

/** A genre: one of its titles' pictures under its name; opens its grid. */
@Composable
internal fun GenreTile(genre: SeerrGenre, kind: SeerrFilter.Kind, modifier: Modifier = Modifier) {
    val nav = LocalDiscoverNav.current
    Box(
        modifier.width(Lockup.landscapeWidth).aspectRatio(Lockup.LANDSCAPE_ASPECT).tvFocus(Lockup.cornerRadius, lift = 1.08f, ring = false, pictureAspect = Lockup.LANDSCAPE_ASPECT)
            .clip(RoundedCornerShape(Lockup.cornerRadius)).background(Color(0xFF262626))
            .cardClickable(onClick = { nav?.openBrowse?.invoke(SeerrBrowse("${genre.name} ${if (kind == SeerrFilter.Kind.Movie) "Movies" else "Series"}", SeerrFilter(kind, genres = setOf(genre.id)))) }),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(SeerrImages.image(genre.backdrops.firstOrNull(), "w780"), genre.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        Text(genre.name, style = Type.title3.copy(fontWeight = FontWeight.Bold), color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
    }
}

/** A studio or network by its logo; opens its grid. */
@Composable
internal fun CompanyTile(company: SeerrCompany, kind: SeerrFilter.Kind, modifier: Modifier = Modifier) {
    val nav = LocalDiscoverNav.current
    val tv = LocalIsTv.current
    Box(
        modifier.width(Lockup.landscapeWidth).aspectRatio(Lockup.LANDSCAPE_ASPECT).tvFocus(Lockup.cornerRadius, lift = 1.08f, ring = false, pictureAspect = Lockup.LANDSCAPE_ASPECT)
            .clip(RoundedCornerShape(Lockup.cornerRadius)).background(Color(0xFF292929))
            .cardClickable(onClick = { nav?.openBrowse?.invoke(SeerrBrowse(company.name, SeerrFilter(kind, company = company))) }),
        contentAlignment = Alignment.Center,
    ) {
        val logo = SeerrImages.logo(company.logoPath)
        if (logo != null) AsyncImage(logo, company.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(if (tv) pt(40f) else 18.dp))
        else Text(company.name, style = Type.headline, color = Color.White, textAlign = TextAlign.Center)
    }
}

/** A list to pick from in a panel: ticks for many ([multi]), or one to pick and go. Done closes it. */
@Composable
internal fun PickDialog(title: String, rows: List<Pair<String, Boolean>>, multi: Boolean, onToggle: (Int) -> Unit, onDismiss: () -> Unit) {
    val tv = LocalIsTv.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = if (tv) pt(900f) else 420.dp).fillMaxWidth().heightIn(max = if (tv) pt(900f) else 560.dp)
                .clip(RoundedCornerShape(if (tv) pt(40f) else 24.dp)).background(Color(0xF21C1C1E)).padding(if (tv) pt(30f) else 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = Type.title3, color = Color.White, modifier = Modifier.padding(bottom = 4.dp))
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(rows.size) { i ->
                    val (label, on) = rows[i]
                    PickRow(label, on, if (i == 0) Modifier.focusRequester(first) else Modifier) { onToggle(i); if (!multi) onDismiss() }
                }
            }
            if (multi) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { Chip("Done", selected = true) { onDismiss() } }
        }
    }
}

@Composable
private fun PickRow(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tv = LocalIsTv.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().height(if (tv) pt(70f) else 48.dp).onFocusChanged { focused = it.isFocused }.clip(CircleShape)
            .background(if (focused) Color.White else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val tint = if (focused) Color.Black else Color.White
        Box(Modifier.size(22.dp)) { if (on) Icon(Icons.Default.Check, null, tint = tint) }
        Text(label, style = Type.callout, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A grey line of text where a section has nothing, or something went wrong. */
@Composable
internal fun Note(text: String, modifier: Modifier = Modifier, color: Color = SecondaryText) {
    Text(text, style = Type.callout, color = color, modifier = modifier.padding(horizontal = discoverInset, vertical = 40.dp))
}
