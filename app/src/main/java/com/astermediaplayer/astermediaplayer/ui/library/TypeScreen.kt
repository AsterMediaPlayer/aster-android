package com.astermediaplayer.astermediaplayer.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.ui.components.ErrorBox
import com.astermediaplayer.astermediaplayer.ui.components.PosterCard
import com.astermediaplayer.astermediaplayer.ui.components.Shelf
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import com.astermediaplayer.astermediaplayer.ui.home.FeaturedBanner
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

enum class LibrarySort(val label: String, val sortBy: List<ItemSortBy>, val order: SortOrder) {
    Name("Name", listOf(ItemSortBy.SORT_NAME), SortOrder.ASCENDING),
    DateAdded("Recently Added", listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME), SortOrder.DESCENDING),
    ReleaseDate("Release Date", listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME), SortOrder.DESCENDING),
    Rating("Rating", listOf(ItemSortBy.COMMUNITY_RATING, ItemSortBy.SORT_NAME), SortOrder.DESCENDING),
}

data class TypeState(
    /** The hero's titles: what's in progress, then (for shows) what's up next, then what's new. Anime is left out. */
    val featured: List<BaseItemDto> = emptyList(),
    val recent: List<BaseItemDto> = emptyList(),
    val heroLoaded: Boolean = false,
    val sort: LibrarySort = LibrarySort.Name,
    val items: List<BaseItemDto> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val hasMore: Boolean get() = items.size < total
    /** By name, every title in letter sections with an index. */
    val sections: List<IndexSection> = if (sort == LibrarySort.Name && !hasMore && items.isNotEmpty()) TitleSections.sections(items) else emptyList()
}

/** The Movies and TV Shows tabs: a hero, what's new, and every title (the Apple apps' `TypeGridView`). */
class TypeViewModel(
    private val repo: AsterRepository,
    val kind: BaseItemKind,
    private val animeLibraryIds: List<UUID>,
) : ViewModel() {
    private val _state = MutableStateFlow(TypeState())
    val state: StateFlow<TypeState> = _state.asStateFlow()
    private var animeIds: Set<UUID> = emptySet()
    private var fetched = 0
    private var skipped = 0
    private var job: Job? = null

    val title: String get() = if (kind == BaseItemKind.MOVIE) "Movies" else "TV Shows"

    init {
        viewModelScope.launch {
            animeIds = repo.itemIds(animeLibraryIds, kind)
            launch { loadHero() }
            reload()
        }
    }

    fun setSort(sort: LibrarySort) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort) }
        viewModelScope.launch { reload() }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || !s.hasMore || s.sort == LibrarySort.Name) return
        job = viewModelScope.launch { fetchPage() }
    }

    private suspend fun reload() {
        job?.cancel()
        fetched = 0
        skipped = 0
        _state.update { it.copy(items = emptyList(), total = 0, error = null) }
        fetchPage()
        // By name the sections need everything.
        while (_state.value.sort == LibrarySort.Name && _state.value.hasMore && _state.value.error == null) fetchPage()
    }

    private suspend fun fetchPage() {
        val sort = _state.value.sort
        _state.update { it.copy(loading = true) }
        try {
            val page = repo.typePage(kind, fetched, PAGE, sort.sortBy, sort.order)
            if (sort != _state.value.sort) return
            fetched += page.items.size
            // Skip anime, which has its own tab. `total` counts it too, so what's left to show is measured without it.
            val shown = page.items.filter { it.id !in animeIds }
            skipped += page.items.size - shown.size
            _state.update {
                // An empty page is the end, whatever the count said.
                val total = if (page.items.isEmpty()) it.items.size else page.total - skipped
                it.copy(items = it.items + shown, total = total, loading = false)
            }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = if (it.items.isEmpty()) e.userMessage() else null) }
        }
    }

    private suspend fun loadHero() {
        try {
            coroutineScope {
                // `latest` filtered to series returns nothing on a Jellyfin server, so the newest shows come from a search sorted by date added.
                val latest = async {
                    if (kind == BaseItemKind.SERIES) repo.recentlyAddedShows(listOf(null), 40)
                    else runCatching { repo.latest(null, 40, listOf(kind)) }.getOrDefault(emptyList())
                }
                val resumed = async { runCatching { repo.resume(30) }.getOrDefault(emptyList()) }
                val next = async { if (kind == BaseItemKind.SERIES) runCatching { repo.nextUp(20) }.getOrDefault(emptyList()) else emptyList() }
                val newest = latest.await().filter { it.id !in animeIds }.take(20)
                val inProgress: List<BaseItemDto>
                val upNext: List<BaseItemDto>
                if (kind == BaseItemKind.MOVIE) {
                    inProgress = resumed.await().filter { it.type == BaseItemKind.MOVIE && it.id !in animeIds }
                    upNext = emptyList()
                } else {
                    inProgress = resumed.await().filter { it.type == BaseItemKind.EPISODE && it.seriesId !in animeIds }
                    upNext = next.await().filter { e -> e.seriesId !in animeIds && inProgress.none { it.id == e.id } }
                }
                val picks = (inProgress.take(if (kind == BaseItemKind.MOVIE) 4 else 3) + upNext.take(3) + newest.take(5)).distinctBy { it.id }
                _state.update { it.copy(recent = newest, featured = picks.take(8), heroLoaded = true) }
            }
        } catch (e: Exception) {
            _state.update { it.copy(heroLoaded = true) }
        }
    }

    private companion object { const val PAGE = 200 }
}

/** What the page's lazy column lays out, in order. */
private sealed interface Row {
    data object Hero : Row
    data object Header : Row
    data object Recent : Row
    data object AllTitles : Row
    data class Letter(val key: String) : Row
    data class Titles(val id: String, val items: List<BaseItemDto>, val last: Boolean) : Row
    data object Spinner : Row
}

@Composable
fun TypeScreen(
    viewModel: TypeViewModel,
    onItem: (BaseItemDto) -> Unit,
    onPlay: (UUID) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val indexed = state.sections.isNotEmpty()
    val hasHero = state.featured.isNotEmpty()

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Titles per row: the grid's columns are 110 wide with 12 between, in the room the index strip leaves.
        val room = maxWidth - Lockup.screenPadding * 2 - if (indexed) INDEX_ROOM else 0.dp
        val perRow = maxOf(1, ((room + Lockup.shelfSpacing) / (Lockup.posterWidth + Lockup.shelfSpacing)).toInt())

        val rows = buildList<Row> {
            add(Row.Hero); add(Row.Header)
            if (state.recent.isNotEmpty()) add(Row.Recent)
            add(Row.AllTitles)
            if (indexed) {
                state.sections.forEach { section ->
                    add(Row.Letter(section.key))
                    val chunks = section.items.chunked(perRow)
                    chunks.forEachIndexed { i, chunk -> add(Row.Titles("${section.key}-$i", chunk, last = false)) }
                }
            } else {
                state.items.chunked(perRow).forEachIndexed { i, chunk -> add(Row.Titles("row-$i", chunk, last = false)) }
                if (state.hasMore) add(Row.Spinner)
            }
        }

        if (!state.heroLoaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (state.items.isEmpty() && state.error != null) {
            ErrorBox(state.error!!, null)
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(rows, key = { _, row -> rowKey(row) }) { index, row ->
                    when (row) {
                        Row.Hero -> if (hasHero) FeaturedBanner(state.featured, onItem, onPlay)
                        Row.Header -> Row(
                            Modifier.fillMaxWidth().then(if (hasHero) Modifier else Modifier.statusBarsPadding())
                                .padding(horizontal = Lockup.screenPadding).padding(top = if (hasHero) 20.dp else Lockup.screenPadding / 2),
                            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(viewModel.title, style = Type.pageTitle.copy(fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = 1.0.sp))
                            SortMenu(state.sort, viewModel::setSort)
                        }
                        Row.Recent -> Shelf("Recently Added", state.recent, landscape = true, onItem = onItem)
                        Row.AllTitles -> Text(
                            "All ${viewModel.title}", style = Type.title3,
                            modifier = Modifier.padding(horizontal = Lockup.screenPadding).padding(top = 20.dp, bottom = 40.dp),
                        )
                        is Row.Letter -> Text(
                            row.key, style = Type.title3.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = SecondaryText,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Lockup.screenPadding).padding(top = 12.dp, bottom = 20.dp),
                        )
                        is Row.Titles -> {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = Lockup.screenPadding).padding(bottom = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing),
                            ) { row.items.forEach { PosterCard(it, { onItem(it) }) } }
                            if (!indexed && index >= rows.size - 3) viewModel.loadMore()
                        }
                        Row.Spinner -> Box(Modifier.fillMaxWidth().padding(Lockup.pageBottomPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
                item { Spacer(Modifier.height(TabBarClearance)) }
            }
            if (indexed) {
                IndexBar(
                    keys = state.sections.map { it.key },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                ) { key ->
                    val target = rows.indexOfFirst { it is Row.Letter && it.key == key }
                    if (target >= 0) scope.launch { listState.scrollToItem(target) }
                }
            }
        }
    }
}

internal val INDEX_ROOM = 28.dp

private fun rowKey(row: Row): String = when (row) {
    Row.Hero -> "hero"; Row.Header -> "header"; Row.Recent -> "recent"; Row.AllTitles -> "all"
    is Row.Letter -> "letter-${row.key}"; is Row.Titles -> "titles-${row.id}"; Row.Spinner -> "spinner"
}

/** The sort picker next to the title: the current choice in the accent colour, with a menu of the others. */
@Composable
internal fun SortMenu(sort: LibrarySort, onSort: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clip(CircleShape).clickable { open = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(sort.label, style = Type.callout, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Default.UnfoldMore, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LibrarySort.entries.forEach { option ->
                DropdownMenuItem(text = { Text(if (option == sort) "✓ ${option.label}" else option.label) }, onClick = { open = false; onSort(option) })
            }
        }
    }
}

/** The strip of letters down the right edge: tap one, or drag along them. 15dp a letter, as on iOS. */
@Composable
internal fun IndexBar(keys: List<String>, modifier: Modifier = Modifier, jump: (String) -> Unit) {
    val density = LocalDensity.current
    val letterHeight = 15.dp
    var last by remember { mutableStateOf<String?>(null) }
    fun at(y: Float) {
        val index = ((y - with(density) { 6.dp.toPx() }) / with(density) { letterHeight.toPx() }).toInt()
        keys.getOrNull(index)?.let { if (it != last) { last = it; jump(it) } }
    }
    Column(
        modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(vertical = 6.dp)
            .pointerInput(keys) { detectTapGestures { at(it.y + with(density) { 6.dp.toPx() }) } }
            .pointerInput(keys) {
                detectDragGestures(onDragEnd = { last = null }, onDragCancel = { last = null }) { change, _ -> at(change.position.y + with(density) { 6.dp.toPx() }) }
            },
    ) {
        keys.forEach { key ->
            Box(Modifier.width(22.dp).height(letterHeight), contentAlignment = Alignment.Center) {
                Text(key, style = Type.caption2Bold.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
