package com.astermediaplayer.astermediaplayer.ui.discover

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrBrowse
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import kotlinx.coroutines.launch

/** A genre's, studio's or network's titles, with their filters. */
class SeerrBrowseViewModel(repo: AsterRepository, val browse: SeerrBrowse) : ViewModel() {
    private var account: AsterRepository.SeerrAccount? = null
    val grid = GridModel(viewModelScope, { account?.client }, browse.filter)

    init {
        viewModelScope.launch { account = repo.seerr(); grid.start() }
    }
}

/** A pushed grid: a genre's, a studio's or network's, with its filters (the Apple apps' SeerrBrowsePage). */
@Composable
fun SeerrBrowsePage(viewModel: SeerrBrowseViewModel, topInset: Dp = 56.dp) {
    val tv = LocalIsTv.current
    DiscoverGrid(viewModel.grid, header = {
        Text(viewModel.browse.title, style = Type.pageTitle, modifier = Modifier.padding(horizontal = discoverInset).padding(top = if (tv) pt(60f) else topInset))
    }, modifier = Modifier)
}
