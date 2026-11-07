package com.astermediaplayer.astermediaplayer.ui.discover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.UpcomingSchedule
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrComingFilm
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType
import java.time.LocalDate
import java.util.UUID

/** One thing on a day: an episode, or a film the viewer asked for. */
sealed interface AgendaEntry {
    val key: String
    data class Episode(val episode: BaseItemDto) : AgendaEntry { override val key get() = episode.id.toString() }
    data class Film(val film: SeerrComingFilm) : AgendaEntry { override val key get() = "film-${film.title.id}" }
}

/**
 * Upcoming as Discover shows it, as the Apple apps' UpcomingAgenda: each day with its episodes and the films the viewer asked
 * for (with Seerr) on the day they arrive. Anime, other shows and films can each be turned off, and stay so.
 */
class UpcomingAgendaModel(
    private val repo: AsterRepository,
    private val scope: CoroutineScope,
    private val seerr: () -> AsterRepository.SeerrAccount?,
) {
    var episodeDays by mutableStateOf<List<Pair<LocalDate, List<BaseItemDto>>>>(emptyList()); private set
    var films by mutableStateOf<List<SeerrComingFilm>>(emptyList()); private set
    var animeSeries by mutableStateOf<Set<UUID>>(emptySet()); private set
    /** The library has both kinds of show, so their switches are worth showing. */
    var hasBothKinds by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    private val prefs = repo.context.getSharedPreferences("discover", android.content.Context.MODE_PRIVATE)
    var showsAnime by mutableStateOf(prefs.getBoolean("upcomingAnime", true)); private set
    var showsTv by mutableStateOf(prefs.getBoolean("upcomingShows", true)); private set
    var showsFilms by mutableStateOf(prefs.getBoolean("upcomingFilms", true)); private set
    private var started = false

    fun setAnime(on: Boolean) { showsAnime = on; prefs.edit().putBoolean("upcomingAnime", on).apply() }
    fun setTv(on: Boolean) { showsTv = on; prefs.edit().putBoolean("upcomingShows", on).apply() }
    fun setFilms(on: Boolean) { showsFilms = on; prefs.edit().putBoolean("upcomingFilms", on).apply() }

    val hasAnything: Boolean get() = episodeDays.isNotEmpty() || films.isNotEmpty()

    /** The days with their episodes and films, less the kinds turned off, and days left empty by that. */
    val days: List<Pair<LocalDate, List<AgendaEntry>>>
        get() {
            val byDay = sortedMapOf<LocalDate, MutableList<AgendaEntry>>()
            for ((day, episodes) in episodeDays) {
                byDay.getOrPut(day) { mutableListOf() } += episodes
                    .filter { if (it.seriesId in animeSeries) showsAnime else showsTv }
                    .map { AgendaEntry.Episode(it) }
            }
            if (showsFilms) films.forEach { byDay.getOrPut(it.date) { mutableListOf() } += AgendaEntry.Film(it) }
            return byDay.filter { it.value.isNotEmpty() }.map { it.key to it.value.toList() }
        }

    fun start() { if (!started) { started = true; reload() } }

    fun reload() {
        scope.launch {
            val comingFilms = async { seerr()?.let { a -> runCatching { a.client.comingFilms(a.userId) }.getOrNull() } ?: films }
            try {
                val views = repo.views()
                val anime = repo.animeLibraries(views)
                val animeIds = anime.map { it.id }.toSet()
                hasBothKinds = anime.isNotEmpty() && views.any { it.collectionType == CollectionType.TVSHOWS && it.id !in animeIds }
                // From today: the server's list starts the day before.
                val today = LocalDate.now()
                episodeDays = UpcomingSchedule.days(repo.upcoming()).filter { !it.first.isBefore(today) }
                if (anime.isNotEmpty()) animeSeries = repo.animeSeriesIds()
                error = null
            } catch (e: Exception) {
                error = e.userMessage()
            }
            films = comingFilms.await()
            loaded = true
        }
    }
}
