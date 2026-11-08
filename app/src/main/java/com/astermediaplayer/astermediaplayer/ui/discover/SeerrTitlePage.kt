package com.astermediaplayer.astermediaplayer.ui.discover

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrDetails
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrException
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrFilter
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrImages
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrRequestOptions
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrStatus
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrTitle
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A Seerr title's page: its details, where it and its seasons stand, and asking for it. */
class SeerrTitleViewModel(
    private val repo: AsterRepository,
    val title: SeerrTitle,
    /** The Discover card it was opened from, with the ids and poster the server gave it. */
    val item: com.astermediaplayer.astermediaplayer.data.DiscoverItem? = null,
) : ViewModel() {
    var details by mutableStateOf<SeerrDetails?>(null); private set
    var status by mutableStateOf(title.status); private set
    var status4k by mutableStateOf(title.status4k); private set
    var seasons by mutableStateOf<List<SeerrDetails.SeasonState>>(emptyList()); private set
    var chosen by mutableStateOf<Set<Int>>(emptySet())
    var working by mutableStateOf(false); private set
    var problem by mutableStateOf<String?>(null); private set
    /** The admin's blocks: this title's page answers "Not available on this server". */
    var blocked by mutableStateOf<String?>(null); private set
    var managesRequests by mutableStateOf(false); private set
    var has4KServer by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    /** Whether Seerr is there to ask for it; without it the page is the title's details and the watchlist. */
    var hasSeerr by mutableStateOf(false); private set
    val watchlist = WatchlistModel(repo, viewModelScope)
    /** The Discover card's poster, for a title Seerr hasn't given one. */
    val itemPoster: String? = item?.poster?.let { runCatching { repo.discoverPosterUrl(it) }.getOrNull() }

    /** Seasons nobody's asked for yet. */
    val open: List<Int> get() = seasons.filter { it.open }.map { it.number }

    init {
        viewModelScope.launch {
            val account = repo.seerr()
            // Without Seerr, or a title Seerr can't know (no TMDB id): what Discover said of it.
            if (account == null || title.id <= 0) { loaded = true; loadWatchlist(); return@launch }
            hasSeerr = true
            managesRequests = account.managesRequests
            if (managesRequests && title.isMovie) has4KServer = runCatching { account.client.services(SeerrFilter.Kind.Movie, fourK = true).isNotEmpty() }.getOrDefault(false)
            try {
                val found = account.client.details(title)
                details = found
                found.mediaStatus?.let { status = it }
                status4k = found.mediaStatus4k ?: SeerrStatus.Unknown
                if (!title.isMovie) {
                    seasons = found.seasonList()
                    chosen = open.toSet()
                }
            } catch (e: SeerrException.NotOnServer) {
                blocked = e.message
            } catch (e: Exception) {
                noteSeerrFailure(e, "title ${title.key}")
                problem = e.message
            }
            loaded = true
            loadWatchlist()
        }
    }

    /** The ids the tracking sites know it by: Discover's, TMDB's, and IMDb's and TheTVDB's once the details have them. */
    private fun trackingIds(): Map<String, String> = buildMap {
        item?.ids?.forEach { (k, v) -> if (v.isNotBlank()) put(k.lowercase(), v) }
        if (title.id > 0) put("tmdb", title.id.toString())
        details?.imdbId?.let { put("imdb", it) }
        details?.tvdbId?.let { put("tvdb", it) }
    }

    private suspend fun loadWatchlist() {
        watchlist.load(WatchlistModel.Title(
            title.mediaType ?: "tv", details?.isAnime ?: item?.anime ?: false, trackingIds(),
            details?.title ?: details?.name ?: title.displayTitle.ifEmpty { item?.title }, title.year?.toIntOrNull() ?: item?.year,
        ))
    }

    fun toggle(season: Int) { chosen = if (season in chosen) chosen - season else chosen + season }

    val requestTitle: String
        get() = when {
            title.isMovie -> "Request"
            chosen.size == open.size -> "Request All Seasons"
            chosen.size == 1 -> "Request 1 Season"
            else -> "Request ${chosen.size} Seasons"
        }

    /** Asks for it; [options] where an admin chose it to go. Throws for the destination panel to show; the page shows it too. */
    suspend fun request(options: SeerrRequestOptions? = null) {
        val account = repo.seerr() ?: return
        working = true
        try {
            account.client.request(title, chosen.sorted(), options)
            problem = null
            // A 4K request leaves the usual copy as it was.
            if (options?.is4k == true) { status4k = SeerrStatus.Pending; return }
            status = SeerrStatus.Pending
            seasons = seasons.map { if (it.number in chosen) it.copy(status = SeerrStatus.Pending) else it }
            chosen = emptySet()
        } catch (e: Exception) {
            problem = e.message
            throw e
        } finally {
            working = false
        }
    }

    fun requestNow() { viewModelScope.launch { runCatching { request() } } }
}

/**
 * A title the server hasn't got, as Aster's own title pages show one (the Apple apps' SeerrTitlePage): the art full bleed
 * behind the page, the name large over it, what it is, and Request where Play would be; a show's seasons to pick, its details,
 * and its cast. A new show with no backdrop yet: its poster, blurred, fills the screen, and stands beside the name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeerrTitlePage(viewModel: SeerrTitleViewModel) {
    val tv = LocalIsTv.current
    val title = viewModel.title
    val details = viewModel.details
    val context = LocalContext.current
    var choosingDestination by remember { mutableStateOf<Boolean?>(null) } // true for the 4K copy
    var noYouTube by remember { mutableStateOf(false) }
    val backdrop = SeerrImages.image(details?.backdropPath ?: title.backdropPath, if (tv) "original" else "w1280")
    val poster = SeerrImages.image(details?.posterPath ?: title.posterPath, "w500") ?: viewModel.itemPoster
    val postersOnly = backdrop == null
    val firstButton = remember { FocusRequester() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (postersOnly) {
            if (poster != null) AsyncImage(poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.3f; scaleY = 1.3f; alpha = 0.85f }.blur(60.dp))
        } else {
            AsyncImage(backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.1f), Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.97f)))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.7f), 0.5f to Color.Transparent)))

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(36.dp)) {
            item(key = "header") {
                Box(Modifier.fillMaxWidth().heightIn(min = if (tv) pt(900f) else 520.dp), contentAlignment = Alignment.BottomStart) {
                    Header(viewModel, postersOnly, poster, firstButton, onRequest = { fourK ->
                        if (viewModel.managesRequests) choosingDestination = fourK else viewModel.requestNow()
                    }, onTrailer = {
                        val opened = openTrailer(context, details?.trailerYouTubeId, details?.trailerUrl)
                        if (!opened) noYouTube = true
                    })
                }
            }
            if (!title.isMovie && viewModel.seasons.isNotEmpty()) item(key = "seasons") { SeasonChips(viewModel) }
            if (details != null) item(key = "details") { DetailsSection(title, details) }
            val cast = details?.cast.orEmpty().take(20)
            if (cast.isNotEmpty()) item(key = "cast") {
                DiscoverShelf("Cast", cast, { "p${it.id}" }) { person, m -> CastPortrait(person, m) }
            }
        }
    }
    LaunchedEffect(viewModel.loaded) { if (tv && viewModel.loaded) runCatching { firstButton.requestFocus() } }

    choosingDestination?.let { fourK ->
        DestinationDialog(
            kind = if (title.isMovie) SeerrFilter.Kind.Movie else SeerrFilter.Kind.Tv, anime = details?.isAnime == true,
            fourK = if (fourK) true else null, confirmTitle = if (fourK) "Request in 4K" else viewModel.requestTitle,
            onDismiss = { choosingDestination = null },
        ) { options -> viewModel.request(options) }
    }
    if (noYouTube) AlertDialog(
        onDismissRequest = { noYouTube = false }, confirmButton = { TextButton({ noYouTube = false }) { Text("OK") } },
        title = { Text("YouTube Needed") }, text = { Text("The trailer plays in the YouTube app. Install it, then try again.") },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(viewModel: SeerrTitleViewModel, postersOnly: Boolean, poster: String?, firstButton: FocusRequester, onRequest: (fourK: Boolean) -> Unit, onTrailer: () -> Unit) {
    val tv = LocalIsTv.current
    val title = viewModel.title
    val details = viewModel.details
    Row(
        Modifier.padding(horizontal = discoverInset).padding(top = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.Bottom,
    ) {
        if (postersOnly && poster != null) {
            val w = if (tv) pt(300f) else 150.dp
            AsyncImage(poster, null, contentScale = ContentScale.Crop, modifier = Modifier.width(w).aspectRatio(2f / 3f).shadow(30.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)))
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(details?.title ?: details?.name ?: title.displayTitle.ifEmpty { viewModel.item?.title.orEmpty() }, style = Type.pageTitle.copy(fontSize = if (tv) (76f * Lockup.tvUnit).sp else 40.sp, lineHeight = if (tv) (86f * Lockup.tvUnit).sp else 46.sp), color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            details?.tagline?.ifEmpty { null }?.let { Text(it, style = Type.title3.copy(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Normal), color = SecondaryText) }
            Text(metadata(title, details).joinToString("  ·  "), style = Type.callout, color = SecondaryText)
            premiereLine(title, details)?.let { line ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.CalendarMonth, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text(line, style = Type.calloutSemibold, color = Color.White)
                }
            }
            val summary = listOf(details?.overview, title.overview).firstOrNull { !it.isNullOrBlank() }
            if (summary != null) Text(summary, style = Type.callout.copy(fontSize = if (tv) (29f * Lockup.tvUnit).sp else 16.sp), color = Color.White, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = if (tv) pt(1100f) else 640.dp))
            else if (details != null) Text("No description yet.", style = Type.callout, color = SecondaryText)
            viewModel.blocked?.let { Text(it, style = Type.headline, color = Color.White) }
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(if (tv) pt(44f) else 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                var first = true
                fun lead(): Modifier = if (first) { first = false; Modifier.focusRequester(firstButton) } else Modifier
                val status = viewModel.status
                if (viewModel.blocked == null && viewModel.hasSeerr) {
                    if (status.canRequest && (title.isMovie || viewModel.open.isNotEmpty() || viewModel.seasons.isEmpty())) {
                        Chip(viewModel.requestTitle, true, lead(), icon = Icons.Default.Add, enabled = !viewModel.working && (title.isMovie || viewModel.seasons.isEmpty() || viewModel.chosen.isNotEmpty())) { onRequest(false) }
                    } else status.label?.let { label -> StatusLabel(label, if (status == SeerrStatus.Available) Icons.Default.CheckCircle else Icons.Default.Schedule) }
                    // An admin's 4K copy of a film, kept apart by Seerr: asked for even when the usual copy is already there.
                    if (title.isMovie && viewModel.managesRequests && viewModel.has4KServer) {
                        if (viewModel.status4k.canRequest) Chip("Request in 4K", false, lead(), icon = Icons.Default.HighQuality, enabled = !viewModel.working) { onRequest(true) }
                        else viewModel.status4k.label?.let { StatusLabel("4K $it", Icons.Default.HighQuality) }
                    }
                }
                if (details?.trailerYouTubeId != null || details?.trailerUrl != null) Chip("Trailer", false, lead(), icon = Icons.Default.PlayCircle) { onTrailer() }
                val watchlist = viewModel.watchlist
                WatchlistButton(watchlist) { press ->
                    Chip(
                        watchlist.label, false, lead(),
                        icon = if (watchlist.on) Icons.Default.BookmarkAdded else Icons.Default.BookmarkAdd, enabled = !watchlist.working, onClick = press,
                    )
                }
                if (viewModel.working) CircularProgressIndicator(Modifier.size(24.dp))
            }
            (viewModel.watchlist.problem ?: viewModel.problem)?.let { Text(it, style = Type.callout, color = Color(0xFFFF6961)) }
        }
    }
}

@Composable
private fun StatusLabel(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = Color.White)
        Text(text, style = Type.headline, color = Color.White)
    }
}

/** "2027  ·  Animation, Comedy  ·  Network  ·  1 Season  ·  In Production". */
private fun metadata(title: SeerrTitle, details: SeerrDetails?): List<String> {
    val parts = mutableListOf<String>()
    title.year?.let { parts += it }
    details?.genres?.takeIf { it.isNotEmpty() }?.let { parts += it.take(3).joinToString(", ") }
    details?.networks?.firstOrNull()?.let { parts += it }
    val runtime = details?.runtime
    if (title.isMovie && runtime != null && runtime > 0) parts += if (runtime >= 60) "${runtime / 60} hr ${runtime % 60} min" else "$runtime min"
    details?.numberOfSeasons?.takeIf { it > 0 }?.let { parts += if (it == 1) "1 Season" else "$it Seasons" }
    details?.status?.takeIf { it.isNotEmpty() && it != "Released" }?.let { parts += it }
    return parts
}

/** "Premieres 10 January 2027", for what isn't out yet. */
private fun premiereLine(title: SeerrTitle, details: SeerrDetails?): String? {
    val date = details?.date ?: return null
    if (!date.isAfter(LocalDate.now())) return null
    return "${if (title.isMovie) "Releases" else "Premieres"} ${date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))}"
}

/** Each season as a chip: the open ones to pick, the rest saying why not. */
@Composable
private fun SeasonChips(viewModel: SeerrTitleViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Seasons", style = Type.title3, color = Color.White, modifier = Modifier.padding(horizontal = discoverInset))
        LazyRow(contentPadding = PaddingValues(horizontal = discoverInset, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(if (LocalIsTv.current) pt(14f) else 10.dp)) {
            items(viewModel.seasons, key = { it.number }) { season ->
                val free = season.open
                Chip(
                    "Season ${season.number}", free && season.number in viewModel.chosen,
                    Modifier.graphicsLayer { alpha = if (free) 1f else 0.55f }, enabled = free,
                    secondLine = if (free) season.episodes?.let { "$it episodes" } ?: " " else season.status.label.orEmpty(),
                ) { viewModel.toggle(season.number) }
            }
        }
    }
}

/** What the library's Details tab shows of a title, as far as it's known. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsSection(title: SeerrTitle, details: SeerrDetails) {
    val tv = LocalIsTv.current
    val facts = mutableListOf<Pair<String, String>>()
    details.original?.takeIf { it != (details.title ?: details.name) }?.let { facts += "Original Title" to it }
    details.date?.let { facts += (if (title.isMovie) "Release" else "Premiere") to it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) }
    details.status?.takeIf { it.isNotEmpty() }?.let { facts += "Status" to it }
    if (details.networks.isNotEmpty()) facts += (if (details.networks.size == 1) "Network" else "Networks") to shortList(details.networks)
    if (details.productionCompanies.isNotEmpty()) facts += "Studio" to details.productionCompanies.take(3).joinToString(", ")
    if (details.makers.isNotEmpty()) facts += (if (title.isMovie) "Director" else "Created by") to details.makers.take(3).joinToString(", ")
    details.numberOfEpisodes?.takeIf { it > 0 }?.let { facts += "Episodes" to it.toString() }
    if (details.genres.isNotEmpty()) facts += "Genres" to details.genres.joinToString(", ")
    if (details.keywords.isNotEmpty()) facts += "Keywords" to details.keywords.take(8).joinToString(", ")
    Column(
        // Reachable by the remote, so the page scrolls down to it.
        Modifier.padding(horizontal = discoverInset).then(if (tv) Modifier.focusable() else Modifier),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Details", style = Type.title3, color = Color.White)
        details.voteAverage?.takeIf { it > 0 }?.let { Text("TMDB  ${String.format(java.util.Locale.US, "%.1f", it)}", style = Type.calloutSemibold, color = Color.White) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            facts.forEach { (label, value) ->
                Column(Modifier.width(if (tv) pt(360f) else 200.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label.uppercase(), style = Type.caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp), color = SecondaryText)
                    Text(value, style = Type.callout, color = Color.White)
                }
            }
        }
    }
}

/** The first three, and how many more: a show on every local station in Japan lists dozens. */
internal fun shortList(names: List<String>, kept: Int = 3): String =
    if (names.size <= kept + 1) names.joinToString(", ") else names.take(kept).joinToString(", ") + " and ${names.size - kept} more"

/** A cast member: their photo in a circle, their name and part. Focusable on a TV, so the remote reaches the row. */
@Composable
private fun CastPortrait(person: SeerrDetails.CastMember, modifier: Modifier) {
    val tv = LocalIsTv.current
    val size = if (tv) pt(160f) else 96.dp
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.width(size + 24.dp).onFocusChanged { focused = it.isFocused }.then(if (tv) Modifier.focusable() else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(size).graphicsLayer { val s = if (focused) 1.12f else 1f; scaleX = s; scaleY = s }.clip(CircleShape).background(Color(0xFF333333))
                .then(if (focused) Modifier.border(4.dp, Color.White, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val photo = person.photoUrl
            if (photo != null) AsyncImage(photo, person.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Default.Person, null, tint = SecondaryText, modifier = Modifier.size(size / 2))
        }
        Text(person.name, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        person.character?.ifEmpty { null }?.let { Text(it, style = Type.caption, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * Opens a trailer in YouTube's app: a watch address ("https://www.youtube.com/watch?v=…"), which every YouTube app takes.
 * YouTube for Android TV turns down the phone app's "vnd.youtube:…" link (it isn't a hierarchical address) and opens on its
 * home screen instead. Google TV's YouTube first, then Fire TV's, then the phone app's; else whatever opens the address.
 */
private fun openTrailer(context: android.content.Context, youTubeId: String?, url: String?): Boolean {
    val address = youTubeId?.let { "https://www.youtube.com/watch?v=${android.net.Uri.encode(it)}" } ?: url ?: return false
    fun view(pkg: String?) = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { if (pkg != null) setPackage(pkg) }
    val apps = if (youTubeId != null) listOf("com.google.android.youtube.tv", "com.amazon.firetv.youtube", "com.google.android.youtube") else emptyList()
    for (pkg in apps + listOf<String?>(null)) {
        try {
            context.startActivity(view(pkg))
            return true
        } catch (_: ActivityNotFoundException) {
        }
    }
    return false
}
