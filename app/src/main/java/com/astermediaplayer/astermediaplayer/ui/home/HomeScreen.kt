package com.astermediaplayer.astermediaplayer.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.ui.components.ErrorBox
import com.astermediaplayer.astermediaplayer.ui.components.Load
import com.astermediaplayer.astermediaplayer.ui.components.LoadingBox
import com.astermediaplayer.astermediaplayer.ui.components.Shelf
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType

data class HomeContent(
    val views: List<BaseItemDto>,
    val resume: List<BaseItemDto>,
    val nextUp: List<BaseItemDto>,
    val latest: List<Pair<BaseItemDto, List<BaseItemDto>>>,
)

class HomeViewModel(private val repo: AsterRepository) : ViewModel() {
    private val _state = MutableStateFlow<Load<HomeContent>>(Load.Loading)
    val state: StateFlow<Load<HomeContent>> = _state.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init { load() }

    fun load(refresh: Boolean = false) {
        viewModelScope.launch {
            if (refresh) _refreshing.value = true else _state.value = Load.Loading
            _state.value = try {
                coroutineScope {
                    val views = repo.views()
                    val resume = async { repo.resume() }
                    val nextUp = async { repo.nextUp() }
                    val latest = views
                        .filter { it.collectionType in setOf(CollectionType.MOVIES, CollectionType.TVSHOWS) }
                        .map { view -> async { view to runCatching { repo.latest(view.id) }.getOrDefault(emptyList()) } }
                    Load.Ready(HomeContent(views, resume.await(), nextUp.await(), latest.map { it.await() }))
                }
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
            _refreshing.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onItem: (BaseItemDto) -> Unit,
    onLibrary: (BaseItemDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    when (val s = state) {
        Load.Loading -> LoadingBox(modifier)
        is Load.Failed -> ErrorBox(s.message, { viewModel.load() }, modifier)
        is Load.Ready -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = { viewModel.load(refresh = true) }, modifier = modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Shelf("Continue Watching", s.value.resume, landscape = true, onItem = onItem)
                Shelf("Next Up", s.value.nextUp, landscape = true, onItem = onItem)
                Shelf("My Libraries", s.value.views, landscape = true, onItem = onLibrary)
                s.value.latest.forEach { (view, items) -> Shelf("Latest in ${view.name}", items, onItem = onItem) }
            }
        }
    }
}
