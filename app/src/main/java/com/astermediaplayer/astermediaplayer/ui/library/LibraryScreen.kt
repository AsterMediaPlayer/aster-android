package com.astermediaplayer.astermediaplayer.ui.library

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.ui.BackButton
import com.astermediaplayer.astermediaplayer.ui.components.ErrorBox
import com.astermediaplayer.astermediaplayer.ui.components.LandscapeCard
import com.astermediaplayer.astermediaplayer.ui.components.PosterCard
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

data class LibraryState(
    val sort: LibrarySort = LibrarySort.Name,
    val items: List<BaseItemDto> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    /** Home and music videos are wide pictures; everything else is posters. */
    val landscape: Boolean = false,
) {
    val hasMore: Boolean get() = items.size < total
    /** By name, the library comes in letter sections with an index; it's loaded whole for that. */
    val sections: List<IndexSection> = if (sort == LibrarySort.Name && !hasMore && items.isNotEmpty()) TitleSections.sections(items) else emptyList()
}

/** Everything inside one library or collection, as a grid with sort options (the Apple apps' `LibraryGridView`). */
class LibraryViewModel(private val repo: AsterRepository, private val libraryId: UUID) : ViewModel() {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    private var type: CollectionType? = null
    private var fetched = 0
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val library = runCatching { repo.item(libraryId) }.getOrNull()
            type = library?.collectionType
            _state.update { it.copy(landscape = type == CollectionType.HOMEVIDEOS || type == CollectionType.MUSICVIDEOS) }
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

    fun retry() = viewModelScope.launch { reload() }

    private suspend fun reload() {
        job?.cancel()
        fetched = 0
        _state.update { it.copy(items = emptyList(), total = 0, error = null, loading = true) }
        fetchPage()
        // By name the sections need everything.
        while (_state.value.sort == LibrarySort.Name && _state.value.hasMore && _state.value.error == null) fetchPage()
    }

    private suspend fun fetchPage() {
        val sort = _state.value.sort
        _state.update { it.copy(loading = true) }
        try {
            val page = repo.libraryItems(libraryId, type, fetched, PAGE, sort.sortBy, sort.order)
            if (sort != _state.value.sort) return
            fetched += page.items.size
            _state.update {
                // An empty page is the end, whatever the count said.
                val total = if (page.items.isEmpty()) it.items.size else page.total
                it.copy(items = (it.items + page.items).distinctBy { item -> item.id }, total = total, loading = false)
            }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = if (it.items.isEmpty()) e.userMessage() else null) }
        }
    }

    private companion object { const val PAGE = 100 }
}

private sealed interface GridRow {
    data object Header : GridRow
    data class Letter(val key: String) : GridRow
    data class Titles(val id: String, val items: List<BaseItemDto>) : GridRow
    data object Spinner : GridRow
}

@Composable
fun LibraryScreen(
    title: String,
    viewModel: LibraryViewModel,
    onItem: (BaseItemDto) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val indexed = state.sections.isNotEmpty()

    Box(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Titles per row: the cards' width with the gaps between, in the room the index strip leaves.
            val cardWidth = if (state.landscape) Lockup.landscapeWidth else Lockup.posterWidth
            val room = maxWidth - Lockup.screenPadding * 2 - if (indexed) INDEX_ROOM else 0.dp
            val perRow = maxOf(1, ((room + Lockup.shelfSpacing) / (cardWidth + Lockup.shelfSpacing)).toInt())

            val rows = buildList<GridRow> {
                add(GridRow.Header)
                if (indexed) {
                    state.sections.forEach { section ->
                        add(GridRow.Letter(section.key))
                        section.items.chunked(perRow).forEachIndexed { i, chunk -> add(GridRow.Titles("${section.key}-$i", chunk)) }
                    }
                } else {
                    state.items.chunked(perRow).forEachIndexed { i, chunk -> add(GridRow.Titles("row-$i", chunk)) }
                    if (state.hasMore || (state.loading && state.items.isNotEmpty())) add(GridRow.Spinner)
                }
            }

            if (state.items.isEmpty() && state.error != null) {
                ErrorBox(state.error!!, viewModel::retry)
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows, key = { _, row -> rowKey(row) }) { index, row ->
                        when (row) {
                            GridRow.Header -> Column {
                                Row(
                                    Modifier.fillMaxWidth().statusBarsPadding().padding(top = 56.dp + Lockup.screenPadding / 2).padding(horizontal = Lockup.screenPadding),
                                    verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(title, style = Type.pageTitle.copy(fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = 1.0.sp), modifier = Modifier.weight(1f))
                                    SortMenu(state.sort, viewModel::setSort)
                                }
                                if (state.items.isEmpty() && !state.loading) {
                                    Column(Modifier.fillMaxWidth().height(320.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Text("Nothing here yet", style = Type.title3, color = SecondaryText)
                                    }
                                } else Spacer(Modifier.height(20.dp))
                            }
                            is GridRow.Letter -> Text(
                                row.key, style = Type.title3.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = SecondaryText,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = Lockup.screenPadding).padding(top = 12.dp, bottom = 20.dp),
                            )
                            is GridRow.Titles -> {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = Lockup.screenPadding).padding(bottom = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing),
                                ) { row.items.forEach { if (state.landscape) LandscapeCard(it, { onItem(it) }) else PosterCard(it, { onItem(it) }) } }
                                if (!indexed && index >= rows.size - 3) viewModel.loadMore()
                            }
                            GridRow.Spinner -> Box(Modifier.fillMaxWidth().padding(Lockup.pageBottomPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        }
                    }
                    item { Spacer(Modifier.height(TabBarClearance)) }
                }
                if (state.loading && state.items.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                if (indexed) {
                    IndexBar(
                        keys = state.sections.map { it.key },
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                    ) { key ->
                        val target = rows.indexOfFirst { it is GridRow.Letter && it.key == key }
                        if (target >= 0) scope.launch { listState.scrollToItem(target) }
                    }
                }
            }
        }
        if (onBack != null) BackButton(onBack)
    }
}

private fun rowKey(row: GridRow): String = when (row) {
    GridRow.Header -> "header"; is GridRow.Letter -> "letter-${row.key}"; is GridRow.Titles -> "titles-${row.id}"; GridRow.Spinner -> "spinner"
}
