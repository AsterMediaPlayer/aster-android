package com.astermediaplayer.astermediaplayer.ui.discover

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.ui.detail.MenuHost
import com.astermediaplayer.astermediaplayer.ui.detail.MenuItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A title's place on the person's linked watchlists, through the Aster Discovery and Tracking plugin: Simkl's Plan to
 * Watch for any film or show (the library's, Discover's, Seerr's), and AniList's Planning and MyAnimeList's Plan to Watch
 * for anime (plugin 0.9.9). [services] is empty, and the button hidden, until the server says which of them it can add
 * to, the person has linked, and can hold the title. With one, the button is a toggle; with more, a menu of each.
 */
class WatchlistModel(private val repo: AsterRepository, private val scope: CoroutineScope) {
    data class Title(val mediaType: String, val anime: Boolean, val ids: Map<String, String>, val name: String?, val year: Int?)

    /** The services offered for this title, with where it stands on each (false where it couldn't be told). */
    var services by mutableStateOf<List<AsterRepository.WatchlistState>>(emptyList()); private set
    var working by mutableStateOf(false); private set
    /** What went wrong the last time, in words to show; cleared by the next try. */
    var problem by mutableStateOf<String?>(null)
    private var title: Title? = null

    val shown: Boolean get() = services.isNotEmpty()
    /** On the watchlist of any of them. */
    val on: Boolean get() = services.any { it.on == true }
    val label: String get() = if (on) "On Watchlist" else "Add to Watchlist"

    /** Asks where [title] stands; a title with no ids the sites know is left without a button. */
    suspend fun load(title: Title) {
        services = emptyList()
        if (title.ids.isEmpty()) return
        this.title = title
        services = repo.watchlistState(title.mediaType, title.ids)
            .filter { it.linked && it.fits != false }
            .map { if (it.on == null) it.copy(on = false) else it }
    }

    /** The button with one service; with more, the menu shows them and [toggle] takes the one picked. */
    fun toggle() { services.singleOrNull()?.let(::toggle) }

    fun toggle(service: AsterRepository.WatchlistState) {
        val title = title ?: return
        if (working) return
        scope.launch {
            working = true
            problem = null
            try {
                val on = repo.setWatchlist(service.service, service.on != true, title.mediaType, title.anime, title.ids, title.name, title.year)
                services = services.map { if (it.service == service.service) it.copy(on = on) else it }
            } catch (e: AsterRepository.WatchlistUnsupported) {
                services = services.filter { it.service != service.service }
            } catch (e: Exception) {
                problem = e.message ?: "${service.name} couldn't be reached. Try again."
            } finally {
                working = false
            }
        }
    }

    companion object {
        /** Jellyfin's provider ids ("Tmdb", "Imdb", "Tvdb", "AniDB", "AniList", "MyAnimeList"…) as the plugin names them. */
        fun ids(providerIds: Map<String, String?>?): Map<String, String> = buildMap {
            providerIds.orEmpty().forEach { (key, value) ->
                val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
                val name = when (key.lowercase()) {
                    "tmdb" -> "tmdb"; "imdb" -> "imdb"; "tvdb" -> "tvdb"; "anidb" -> "anidb"; "anilist" -> "anilist"
                    "myanimelist", "mal" -> "mal"; "simkl" -> "simkl"
                    else -> null
                }
                if (name != null) put(name, v)
            }
        }
    }
}

/**
 * The watchlist's button, as [button] draws it, pressed: with one service it adds or takes off; with more it opens a menu
 * with each ("Add to AniList", "On Simkl" ✓). Nothing while no service is offered.
 */
@Composable
internal fun WatchlistButton(model: WatchlistModel, button: @Composable (onClick: () -> Unit) -> Unit) {
    if (!model.shown) return
    var open by remember { mutableStateOf(false) }
    Box {
        button { if (model.services.size == 1) model.toggle() else open = true }
        MenuHost(expanded = open, onDismiss = { open = false }) {
            model.services.forEachIndexed { index, service ->
                val on = service.on == true
                MenuItem(if (on) "On ${service.name}" else "Add to ${service.name}", null, first = index == 0, checked = on) {
                    open = false
                    model.toggle(service)
                }
            }
        }
    }
}
