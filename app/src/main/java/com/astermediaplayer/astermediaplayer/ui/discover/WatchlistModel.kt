package com.astermediaplayer.astermediaplayer.ui.discover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A title's place on the person's linked watchlist (Simkl's Plan to Watch, through the Aster Discovery and Tracking plugin),
 * for any film or show: the library's, Discover's, Seerr's. [state] is null, and the button hidden, until the server says
 * it can add to a watchlist the person has linked.
 */
class WatchlistModel(private val repo: AsterRepository, private val scope: CoroutineScope) {
    data class Title(val mediaType: String, val anime: Boolean, val ids: Map<String, String>, val name: String?, val year: Int?)

    var state by mutableStateOf<AsterRepository.WatchlistState?>(null); private set
    var working by mutableStateOf(false); private set
    /** What went wrong the last time, in words to show; cleared by the next try. */
    var problem by mutableStateOf<String?>(null)
    private var title: Title? = null

    val on: Boolean get() = state?.on == true

    /** Asks where [title] stands; a title with no ids the sites know is left without a button. */
    suspend fun load(title: Title) {
        if (title.ids.isEmpty()) return
        this.title = title
        state = repo.watchlistState(title.mediaType, title.ids).firstOrNull { it.linked }
    }

    fun toggle() {
        val current = state ?: return
        val title = title ?: return
        if (working) return
        scope.launch {
            working = true
            try {
                val on = repo.setWatchlist(current.service, current.on != true, title.mediaType, title.anime, title.ids, title.name, title.year)
                state = current.copy(on = on)
                problem = null
            } catch (e: Exception) {
                problem = e.message ?: "The watchlist couldn't be changed."
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
