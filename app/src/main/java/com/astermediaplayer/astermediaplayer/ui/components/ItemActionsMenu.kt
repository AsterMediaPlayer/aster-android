package com.astermediaplayer.astermediaplayer.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.emby.ServerKind
import com.astermediaplayer.astermediaplayer.ui.detail.MenuHost
import com.astermediaplayer.astermediaplayer.ui.detail.MenuItem
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.util.UUID

/** What a card's menu does that a card can't do on its own: play a title, or open a page. The app's navigation gives them. */
class ItemActions(val play: (id: UUID, fromBeginning: Boolean) -> Unit, val open: (BaseItemDto) -> Unit)

val LocalItemActions = staticCompositionLocalOf<ItemActions?> { null }

/** The titles a card's long press offers a menu for, as the Apple apps' `ItemActionsMenu.supports`. */
fun hasItemActions(item: BaseItemDto): Boolean = item.type in listOf(
    BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO, BaseItemKind.RECORDING,
)

/**
 * A card's long-press menu, the Apple apps' `ItemActionsMenu`: Play or Resume and Play from Beginning, Go to Series and Go
 * to Season, Mark as Watched or Unwatched, and Add to or Remove from Favorites (not on Plex, which keeps none). [onChange]
 * hands back the item with its new watched or favourite state, for the card to show.
 */
@Composable
internal fun ItemActionsMenu(item: BaseItemDto, expanded: Boolean, onDismiss: () -> Unit, onChange: (BaseItemDto) -> Unit) {
    val actions = LocalItemActions.current
    val repo = (LocalContext.current.applicationContext as AsterApp).repository
    val scope = rememberCoroutineScope()
    MenuHost(expanded, onDismiss) {
        // On a TV the first entry takes the remote.
        var entries = 0
        fun first() = entries++ == 0
        val resume = item.userData?.playbackPositionTicks ?: 0L
        val playsItself = item.type != BaseItemKind.SERIES && item.type != BaseItemKind.SEASON
        if (actions != null && playsItself) {
            MenuItem(if (resume > 0) "Resume" else "Play", Icons.Default.PlayArrow, first = first()) { onDismiss(); actions.play(item.id, false) }
            if (resume > 0) MenuItem("Play from Beginning", Icons.Default.Replay, first = first()) { onDismiss(); actions.play(item.id, true) }
        }
        if (actions != null) {
            // Minimal items are enough: the page reads the rest.
            val seriesId = item.seriesId
            if ((item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.SEASON) && seriesId != null) {
                MenuItem("Go to Series", Icons.Default.Tv, first = first()) { onDismiss(); actions.open(BaseItemDto(id = seriesId, type = BaseItemKind.SERIES, name = item.seriesName)) }
            }
            val seasonId = item.seasonId
            if (item.type == BaseItemKind.EPISODE && seasonId != null) {
                MenuItem("Go to Season", Icons.Default.ViewAgenda, first = first()) { onDismiss(); actions.open(BaseItemDto(id = seasonId, type = BaseItemKind.SEASON, name = item.seasonName)) }
            }
        }
        val played = item.userData?.played == true
        MenuItem(if (played) "Mark as Unwatched" else "Mark as Watched", if (played) Icons.Default.VisibilityOff else Icons.Default.CheckCircle, first = first()) {
            onDismiss()
            scope.launch {
                runCatching { repo.setPlayed(item.id, !played) }.onSuccess {
                    onChange(item.withUserData { it.copy(played = !played, playbackPositionTicks = if (!played) 0L else it.playbackPositionTicks, playedPercentage = if (!played) null else it.playedPercentage) })
                }
            }
        }
        if (repo.serverKind != ServerKind.Plex) {
            val favorite = item.userData?.isFavorite == true
            MenuItem(if (favorite) "Remove from Favorites" else "Add to Favorites", if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, first = first()) {
                onDismiss()
                scope.launch {
                    runCatching { repo.setFavorite(item.id, !favorite) }.onSuccess { onChange(item.withUserData { it.copy(isFavorite = !favorite) }) }
                }
            }
        }
    }
}

private fun BaseItemDto.withUserData(change: (UserItemDataDto) -> UserItemDataDto): BaseItemDto {
    val current = userData ?: UserItemDataDto(playbackPositionTicks = 0, playCount = 0, isFavorite = false, played = false, key = id.toString(), itemId = id)
    return copy(userData = change(current))
}
