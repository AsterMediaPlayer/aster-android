package com.astermediaplayer.astermediaplayer.ui.discover

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.astermediaplayer.astermediaplayer.data.DiscoverEntry
import com.astermediaplayer.astermediaplayer.data.DiscoverItem
import com.astermediaplayer.astermediaplayer.data.seerr.RuntimeChoice
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrClient
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrFilter
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrLanguages
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrSlider
import com.astermediaplayer.astermediaplayer.data.seerr.YearChoice
import com.astermediaplayer.astermediaplayer.data.seerrTitle
import com.astermediaplayer.astermediaplayer.ui.components.LoadingBox
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The Discover tab, as the Apple apps' DiscoverView: the library's Upcoming episodes, and with the server's Aster Discovery
 * and Tracking plugin its Discover page, and through it Seerr's films and shows to browse with filters, and the requests.
 */
@Composable
fun DiscoverScreen(viewModel: DiscoverViewModel, modifier: Modifier = Modifier, topInset: Dp = 0.dp) {
    val tv = LocalIsTv.current
    // On a TV the Apple TV's page: a dark grey that warms a little toward the bottom.
    Box(if (tv) Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A2B2E), Color(0xFF37332F)))) else Modifier.fillMaxSize()) {
        DiscoverContent(viewModel, if (tv) Modifier.fillMaxSize() else modifier, if (tv) 0.dp else topInset)
    }
}

@Composable
private fun DiscoverContent(viewModel: DiscoverViewModel, modifier: Modifier, topInset: Dp) {
    if (!viewModel.checked) { LoadingBox(modifier); return }
    val header: @Composable () -> Unit = { DiscoverHeader(viewModel, topInset) }
    when (viewModel.shown) {
        DiscoverSection.Upcoming -> UpcomingAgenda(viewModel.upcoming, header, modifier)
        DiscoverSection.Movies -> DiscoverGrid(viewModel.movies, header, modifier)
        DiscoverSection.Series -> DiscoverGrid(viewModel.series, header, modifier)
        DiscoverSection.Requests -> RequestsList(viewModel.requests, header, modifier)
        DiscoverSection.Discover -> DiscoverRows(viewModel, header, modifier)
        null -> Column(modifier.fillMaxSize()) {
            header()
            Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Explore, null, tint = SecondaryText, modifier = Modifier.size(48.dp))
                Text("Nothing to discover yet", style = Type.title3)
                Text(
                    "Upcoming episodes show here, and with the Aster Discovery and Tracking plugin on your Jellyfin server, recommendations and films and shows to request.",
                    style = Type.callout, color = SecondaryText, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The title and the sections: side by side on a TV, the sections across the full width below the title on a phone. */
@Composable
private fun DiscoverHeader(viewModel: DiscoverViewModel, topInset: Dp) {
    val tv = LocalIsTv.current
    val sections = viewModel.sections
    val selected = sections.indexOf(viewModel.shown).coerceAtLeast(0)
    val picker: @Composable () -> Unit = {
        if (sections.size > 1) SectionPicker(sections.map { it.label }, selected) { viewModel.chosen = sections[it] }
    }
    if (tv) {
        // As the Apple TV's: no title (the menu's chip names the page), the sections centred under it.
        // The first row's title sits close under them, as there: the page's row gap is taken back here.
        val tighten = pt(32f)
        Box(
            Modifier.fillMaxWidth().padding(top = pt(160f)).layout { m, c ->
                val p = m.measure(c)
                layout(p.width, (p.height - tighten.roundToPx()).coerceAtLeast(0)) { p.place(0, 0) }
            },
            contentAlignment = Alignment.TopCenter,
        ) { picker() }
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = discoverInset).padding(top = topInset + 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Discover", style = Type.pageTitle)
            picker()
        }
    }
}

/** Out past a grid's side margins, for a full-width piece inside it. */
internal fun Modifier.bleed(margin: Dp): Modifier = layout { measurable, constraints ->
    val m = margin.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + 2 * m, maxWidth = constraints.maxWidth + 2 * m))
    layout(placeable.width - 2 * m, placeable.height) { placeable.place(-m, 0) }
}

// MARK: - Discover rows

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverRows(viewModel: DiscoverViewModel, header: @Composable () -> Unit, modifier: Modifier) {
    val rows = viewModel.rows
    val nav = LocalDiscoverNav.current
    val scope = rememberCoroutineScope()
    var cantOpen by remember { mutableStateOf<String?>(null) }
    // A title from the server's page: Seerr's page by its TMDB id, with the Request button; or, with requests off, its place in the library.
    fun open(item: DiscoverItem) {
        val title = item.seerrTitle()
        val library = item.inLibrary
        // Without Seerr (or a title it can't know), the card's own page: what Discover said of it, and Add to Watchlist.
        val card = title ?: com.astermediaplayer.astermediaplayer.data.seerr.SeerrTitle(
            id = 0, mediaType = if (item.mediaType.equals("movie", ignoreCase = true)) "movie" else "tv", name = item.title, overview = item.overview,
            firstAirDate = item.year?.let { "$it-01-01" },
        )
        when {
            viewModel.seerr != null && title != null -> nav?.openCard?.invoke(title, item)
            library != null -> scope.launch {
                val found = parseId(library.itemId)?.let { runCatching { viewModel.repo.item(it) }.getOrNull() }
                if (found != null) nav?.openItem?.invoke(found) else cantOpen = item.title
            }
            item.ids.isNotEmpty() -> nav?.openCard?.invoke(card, item)
            else -> cantOpen = item.title
        }
    }
    val firstCard = remember { FocusRequester() }
    val tv = LocalIsTv.current
    LaunchedEffect(rows.loaded) {
        if (tv && rows.loaded && !viewModel.firstFocusGiven) { viewModel.firstFocusGiven = true; runCatching { firstCard.requestFocus() } }
    }
    PullToRefreshBox(isRefreshing = rows.refreshing, onRefresh = { rows.load(fresh = true) }, modifier = modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = TabBarClearance),
            verticalArrangement = Arrangement.spacedBy(if (tv) Lockup.sectionSpacing else 20.dp),
        ) {
            item(key = "header") { header() }
            val entries = rows.entries
            if (!rows.loaded) item(key = "loading") { Box(Modifier.fillMaxWidth().padding(vertical = 120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            val problem = rows.problem
            if (rows.loaded && entries.isEmpty()) {
                item(key = "empty") { Note(problem ?: "Nothing to suggest yet. Watch something and link your accounts in the plugin's dashboard, and Discover learns what you like.") }
            } else if (rows.loaded && problem != null) {
                item(key = "problem") { Note(problem) }
            }
            entries.forEachIndexed { index, entry ->
                item(key = entry.key) {
                    val focus = if (index == 0) firstCard else null
                    when (entry) {
                        is DiscoverEntry.Tracked -> DiscoverShelf(
                            entry.row.title, entry.row.items.filter { it.tmdbId != null || it.inLibrary != null || it.ids.isNotEmpty() }, { it.key }, null, focus,
                        ) { item, m -> TrackedLockup(item, { open(item) }, m) }
                        is DiscoverEntry.Slider -> SliderRow(viewModel, entry.slider, focus)
                        DiscoverEntry.InTheaters -> SliderRow(viewModel, SeerrSlider(null, SeerrClient.IN_THEATERS_TYPE), focus)
                    }
                }
            }
        }
    }
    cantOpen?.let { name ->
        AlertDialog(onDismissRequest = { cantOpen = null }, confirmButton = { TextButton({ cantOpen = null }) { Text("OK") } },
            title = { Text(name) }, text = { Text("This title can't be opened here.") })
    }
}

/** One of Seerr's rows: titles, genres, or studios and networks. */
@Composable
private fun SliderRow(viewModel: DiscoverViewModel, slider: SeerrSlider, firstFocus: FocusRequester?) {
    // Again once Seerr has answered: a row shown from last time is asked for afresh then.
    LaunchedEffect(slider.stableId, viewModel.seerr) { viewModel.rows.loadSlider(slider) }
    val title = SeerrClient.sliderTitle(slider)
    when (val c = viewModel.rows.content[slider.stableId]) {
        is SliderContent.Genres -> DiscoverShelf(title, c.genres, { "g${it.id}" }, firstFocus = firstFocus) { g, m -> GenreTile(g, c.kind, m) }
        is SliderContent.Companies -> DiscoverShelf(title, c.companies, { "c${it.id}" }, firstFocus = firstFocus) { co, m -> CompanyTile(co, c.kind, m) }
        // Trending leads, art first.
        is SliderContent.Titles -> if (slider.type == 4) DiscoverShelf(title, c.titles, { it.key }, firstFocus = firstFocus) { t, m -> SeerrBackdropLockup(t, m) }
            else DiscoverShelf(title, c.titles, { it.key }, firstFocus = firstFocus) { t, m -> SeerrTitleLockup(t, m) }
        else -> {}
    }
}

internal fun parseId(id: String): UUID? = runCatching {
    UUID.fromString(if (id.length == 32) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id)
}.getOrNull()

// MARK: - Movies and Series

/** Films or shows from Seerr, filtered and sorted, a page at a time as the grid scrolls. */
@Composable
internal fun DiscoverGrid(model: GridModel, header: @Composable () -> Unit, modifier: Modifier) {
    LaunchedEffect(model) { model.start() }
    val tv = LocalIsTv.current
    LazyVerticalGrid(
        GridCells.Adaptive(Lockup.posterWidth), modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = discoverInset, end = discoverInset, bottom = TabBarClearance),
        horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing), verticalArrangement = Arrangement.spacedBy(if (tv) Lockup.gridRowSpacing else 20.dp),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.bleed(discoverInset)) { header() } }
        item(key = "filters", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.bleed(discoverInset)) { FilterBar(model) } }
        if (model.cards.isEmpty() && !model.loading) {
            item(key = "none", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.bleed(discoverInset)) { Note(if (model.failed) "Seerr didn't answer." else "Nothing matches these filters.") } }
        }
        itemsIndexed(model.cards, key = { _, t -> t.key }) { index, title ->
            // Near the end: the next page.
            if (index >= model.cards.size - 6) LaunchedEffect(model.cards.size) { model.loadMore() }
            SeerrTitleLockup(title)
        }
        if (model.loading) item(key = "more", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
    }
}

private enum class Picking { Genres, Company, Providers }

/** Sort, and each filter as a chip that says what it's set to. */
@Composable
private fun FilterBar(model: GridModel) {
    val f = model.filter
    val movie = f.kind == SeerrFilter.Kind.Movie
    var picking by remember { mutableStateOf<Picking?>(null) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = discoverInset, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (LocalIsTv.current) pt(20f) else 10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        // Anime: Animation from Japan, in one press.
        Chip("Anime", f.isAnime, icon = if (f.isAnime) Icons.Default.Check else Icons.Default.AutoAwesome) { model.set(f.withAnime(!f.isAnime)) }
        ChipMenu(f.sort.label, false, SeerrFilter.Sort.entries.map { it.label }, f.sort.ordinal, Icons.AutoMirrored.Filled.Sort) { model.set(f.copy(sort = SeerrFilter.Sort.entries[it])) }
        Chip(genreLabel(model), f.genres.isNotEmpty()) { picking = Picking.Genres }
        val year = YearChoice.of(f)
        ChipMenu(year.label, f.fromYear != null || f.toYear != null, YearChoice.entries.map { it.label }, year.ordinal) {
            val (from, to) = YearChoice.entries[it].range()
            model.set(f.copy(fromYear = from, toYear = to))
        }
        val ratings = listOf<Double?>(null, 6.0, 7.0, 8.0)
        ChipMenu(f.minimumRating?.let { "${it.toInt()}+ Stars" } ?: "Any Rating", f.minimumRating != null, ratings.map { r -> r?.let { "${it.toInt()}+ Stars" } ?: "Any Rating" }, ratings.indexOf(f.minimumRating)) {
            model.set(f.copy(minimumRating = ratings[it]))
        }
        val runtime = RuntimeChoice.of(f)
        ChipMenu(runtime.label, f.minimumRuntime != null || f.maximumRuntime != null, RuntimeChoice.entries.map { it.label }, runtime.ordinal) {
            val (min, max) = RuntimeChoice.entries[it].range
            model.set(f.copy(minimumRuntime = min, maximumRuntime = max))
        }
        val languages = listOf<Pair<String?, String>>(null to "Any Language") + SeerrLanguages
        ChipMenu(SeerrLanguages.firstOrNull { it.first == f.language }?.second ?: "Any Language", f.language != null, languages.map { it.second }, languages.indexOfFirst { it.first == f.language }) {
            model.set(f.copy(language = languages[it].first))
        }
        Chip(f.company?.name ?: if (movie) "Any Studio" else "Any Network", f.company != null) { picking = Picking.Company }
        Chip(providerLabel(model), f.providers.isNotEmpty()) { picking = Picking.Providers }
        if (f.activeCount > 0) Chip("Clear", false, icon = Icons.Default.Close) { model.set(SeerrFilter(f.kind)) }
    }
    when (picking) {
        Picking.Genres -> PickDialog("Genres", model.genres.map { it.name to (it.id in model.filter.genres) }, multi = true, onToggle = { i ->
            val id = model.genres[i].id
            val g = model.filter.genres
            model.set(model.filter.copy(genres = if (id in g) g - id else g + id))
        }, onDismiss = { picking = null })
        Picking.Company -> PickDialog(
            if (movie) "Studio" else "Network",
            listOf((if (movie) "Any Studio" else "Any Network") to (f.company == null)) + model.companies.map { it.name to (f.company?.id == it.id) },
            multi = false, onToggle = { i -> model.set(model.filter.copy(company = if (i == 0) null else model.companies[i - 1])) }, onDismiss = { picking = null },
        )
        Picking.Providers -> PickDialog("Streaming On", model.providers.map { it.name to (it.id in model.filter.providers) }, multi = true, onToggle = { i ->
            val id = model.providers[i].id
            val p = model.filter.providers
            model.set(model.filter.copy(providers = if (id in p) p - id else p + id))
        }, onDismiss = { picking = null })
        null -> {}
    }
}

private fun genreLabel(model: GridModel): String {
    val chosen = model.genres.filter { it.id in model.filter.genres }.map { it.name }
    return when (chosen.size) {
        0 -> if (model.filter.genres.isEmpty()) "Any Genre" else "${model.filter.genres.size} Genres"
        1 -> chosen[0]
        else -> "${chosen.size} Genres"
    }
}

private fun providerLabel(model: GridModel): String {
    val chosen = model.providers.filter { it.id in model.filter.providers }.map { it.name }
    return when (chosen.size) {
        0 -> if (model.filter.providers.isEmpty()) "Any Service" else "${model.filter.providers.size} Services"
        1 -> chosen[0]
        else -> "${chosen.size} Services"
    }
}
