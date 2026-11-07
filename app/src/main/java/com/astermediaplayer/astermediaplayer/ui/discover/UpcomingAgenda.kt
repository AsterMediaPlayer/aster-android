package com.astermediaplayer.astermediaplayer.ui.discover

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.UpcomingSchedule
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrImages
import com.astermediaplayer.astermediaplayer.ui.components.cardClickable
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import org.jellyfin.sdk.model.api.ImageType
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Upcoming as Discover shows it, as the Apple apps' UpcomingAgenda: each day down the side, big, as a desk calendar shows it,
 * with that day's episodes and requested films beside it as wide pictures. The page takes the focused card's picture, softly,
 * behind everything. Anime, TV shows and films can each be turned off at the top.
 */
@Composable
internal fun UpcomingAgenda(model: UpcomingAgendaModel, header: @Composable () -> Unit, modifier: Modifier) {
    LaunchedEffect(model) { model.start() }
    val repo = (LocalContext.current.applicationContext as AsterApp).repository
    val days = model.days
    var focusedEntry by remember { mutableStateOf<AgendaEntry?>(null) }
    val backdropEntry = focusedEntry ?: days.firstOrNull()?.second?.firstOrNull()
    val backdropUrl = when (val e = backdropEntry) {
        is AgendaEntry.Episode -> repo.imageUrl(e.episode, ImageType.BACKDROP, 1280) ?: repo.imageUrl(e.episode, ImageType.PRIMARY, 1280)
        is AgendaEntry.Film -> SeerrImages.image(e.film.title.backdropPath, "w1280")
        null -> null
    }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        // The focused picture, blurred and darkened, behind the page. Episodes of one show share a picture.
        Crossfade(backdropUrl, animationSpec = tween(600), label = "backdrop") { url ->
            if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.15f; scaleY = 1.15f; alpha = 0.55f }.blur(50.dp))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.85f)))))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = TabBarClearance), verticalArrangement = Arrangement.spacedBy(if (LocalIsTv.current) pt(12f) else 4.dp)) {
            item(key = "header") { Box(Modifier.padding(bottom = 12.dp)) { header() } }
            if (!model.hasAnything) {
                item(key = "empty") {
                    when {
                        model.error != null -> Note("Couldn't load: ${model.error}")
                        model.loaded -> Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CalendarMonth, null, tint = SecondaryText)
                            Text("Nothing coming up", style = Type.title3)
                            Text("Episodes of shows in your library that air soon show up here, and films you've asked for until they're out.", style = Type.callout, color = SecondaryText, textAlign = TextAlign.Center)
                        }
                        else -> Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
            } else {
                if (model.hasBothKinds || model.films.isNotEmpty()) item(key = "switches") { KindSwitches(model) }
                if (days.isEmpty()) item(key = "off") { Note("Everything's turned off above. Turn something on to see what's coming.") }
                items(days, key = { it.first.toString() }) { (day, entries) -> DayRow(day, entries) { focusedEntry = it } }
            }
        }
    }
}

/** Anime, TV Shows and Movies, each on or off. */
@Composable
private fun KindSwitches(model: UpcomingAgendaModel) {
    Row(Modifier.padding(horizontal = discoverInset).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(if (LocalIsTv.current) pt(16f) else 10.dp)) {
        if (model.hasBothKinds) {
            Chip("Anime", model.showsAnime, icon = if (model.showsAnime) Icons.Default.Check else Icons.Default.AutoAwesome) { model.setAnime(!model.showsAnime) }
            Chip("TV Shows", model.showsTv, icon = if (model.showsTv) Icons.Default.Check else Icons.Default.Tv) { model.setTv(!model.showsTv) }
        }
        if (model.films.isNotEmpty()) Chip("Movies", model.showsFilms, icon = if (model.showsFilms) Icons.Default.Check else Icons.Default.Movie) { model.setFilms(!model.showsFilms) }
    }
}

@Composable
private fun DayRow(day: LocalDate, entries: List<AgendaEntry>, onFocus: (AgendaEntry) -> Unit) {
    val tv = LocalIsTv.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        DayStamp(day, Modifier.padding(start = discoverInset).width(if (tv) pt(220f) else 96.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = Lockup.shelfVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing),
        ) {
            items(entries, key = { it.key }) { entry ->
                val m = Modifier.onFocusChanged { if (it.isFocused) onFocus(entry) }
                when (entry) {
                    is AgendaEntry.Episode -> AgendaCard(entry, m)
                    is AgendaEntry.Film -> AgendaFilmCard(entry, m)
                }
            }
        }
    }
}

/** A day as a desk calendar shows it: "THU", "14", "November", "Tomorrow". */
@Composable
private fun DayStamp(day: LocalDate, modifier: Modifier) {
    val tv = LocalIsTv.current
    val today = day == LocalDate.now()
    Column(modifier) {
        Text(day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(), style = Type.caption.copy(fontWeight = FontWeight.Black, letterSpacing = 2.sp),
            color = if (today) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f))
        Text(day.dayOfMonth.toString(), style = Type.pageTitle.copy(fontSize = if (tv) (86f * Lockup.tvUnit).sp else 40.sp, lineHeight = if (tv) (96f * Lockup.tvUnit).sp else 44.sp), color = Color.White)
        Text(day.month.getDisplayName(TextStyle.FULL, Locale.getDefault()), style = Type.calloutMedium, color = Color.White)
        Text(UpcomingSchedule.countdown(day), style = Type.caption, color = SecondaryText, modifier = Modifier.padding(top = 4.dp))
    }
}

private val cardWidthPhone = 260.dp

/** An episode on the way, as a wide picture of its show: the logo (or name), which episode, and "Available" when it's out early. */
@Composable
private fun AgendaCard(entry: AgendaEntry.Episode, modifier: Modifier) {
    val repo = (LocalContext.current.applicationContext as AsterApp).repository
    val nav = LocalDiscoverNav.current
    val tv = LocalIsTv.current
    val episode = entry.episode
    val width = if (tv) pt(560f) else cardWidthPhone
    val title = episode.seriesName ?: episode.name.orEmpty()
    Box(
        modifier.width(width).height(width * 9 / 16).tvFocus(Lockup.cornerRadius, lift = 1.08f, ring = false, pictureAspect = 16f / 9f)
            .clip(RoundedCornerShape(Lockup.cornerRadius)).background(Color(0xFF262626))
            .cardClickable(onClick = { nav?.openItem?.invoke(UpcomingSchedule.destination(episode)) }),
    ) {
        AsyncImage(repo.imageUrl(episode, ImageType.BACKDROP, 1000) ?: repo.imageUrl(episode, ImageType.PRIMARY, 1000), title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Column(Modifier.align(Alignment.BottomStart).padding(if (tv) pt(22f) else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val logo = repo.imageUrl(episode, ImageType.LOGO, 500)
            if (logo != null) AsyncImage(logo, title, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.widthIn(max = width * 0.5f).heightIn(max = width * 9 / 16 * 0.26f))
            else Text(title, style = Type.title3.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(UpcomingSchedule.episodeLine(episode), style = Type.calloutSemibold, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (UpcomingSchedule.isAvailable(episode)) Badge("▶ Available", Modifier.align(Alignment.TopEnd).padding(6.dp), accent = true)
    }
}

/** A film the viewer asked for, on the day it arrives: its picture, its name, and how. Opens its Seerr page. */
@Composable
private fun AgendaFilmCard(entry: AgendaEntry.Film, modifier: Modifier) {
    val nav = LocalDiscoverNav.current
    val tv = LocalIsTv.current
    val film = entry.film
    val width = if (tv) pt(560f) else cardWidthPhone
    Box(
        modifier.width(width).height(width * 9 / 16).tvFocus(Lockup.cornerRadius, lift = 1.08f, ring = false, pictureAspect = 16f / 9f)
            .clip(RoundedCornerShape(Lockup.cornerRadius)).background(Color(0xFF262626))
            .cardClickable(onClick = { nav?.openTitle?.invoke(film.title) }),
    ) {
        AsyncImage(SeerrImages.image(film.title.backdropPath, "w780") ?: film.title.posterUrl, film.title.displayTitle, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Column(Modifier.align(Alignment.BottomStart).padding(if (tv) pt(22f) else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(film.title.displayTitle, style = Type.title3.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(film.how, style = Type.calloutSemibold, color = Color.White.copy(alpha = 0.85f))
        }
        Badge("✓ Requested", Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}
