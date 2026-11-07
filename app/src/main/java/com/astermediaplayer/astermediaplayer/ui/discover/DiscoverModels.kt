package com.astermediaplayer.astermediaplayer.ui.discover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.DiscoverEntry
import com.astermediaplayer.astermediaplayer.data.DiscoverOrder
import com.astermediaplayer.astermediaplayer.data.DiscoverRow
import com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrClient
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrCompany
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrException
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrFilter
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrGenre
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrProvider
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrRequest
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrRequestFilter
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrRequestOptions
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrSlider
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Discover's sections, as the Apple apps' (DiscoverView.Section). */
enum class DiscoverSection(val label: String) { Discover("Discover"), Upcoming("Upcoming"), Movies("Movies"), Series("Series"), Requests("Requests") }

internal fun noteSeerrFailure(error: Throwable, what: String) {
    DebugLog.note("Seerr", "$what failed: ${error.message}")
}

/**
 * The Discover tab: which sections there are (the server's plugin gives Discover's rows, Seerr through it Movies, Series and
 * Requests, the library Upcoming), and each section's state, kept while titles are opened from it.
 */
class DiscoverViewModel(val repo: AsterRepository) : ViewModel() {
    var checked by mutableStateOf(false); private set
    var pluginPresent by mutableStateOf(false); private set
    var seerr by mutableStateOf<AsterRepository.SeerrAccount?>(null); private set
    var hasUpcoming by mutableStateOf(false); private set
    /** Seerr was on last time: its tabs show from the snapshot until Seerr answers. */
    private var seerrLastTime by mutableStateOf(false)
    /** Seerr's on, or was last time and hasn't answered yet. */
    val hasSeerr: Boolean get() = seerr != null || (seerrLastTime && !answered)
    private var answered by mutableStateOf(false)
    var chosen by mutableStateOf<DiscoverSection?>(null)

    val sections: List<DiscoverSection>
        get() = DiscoverSection.entries.filter {
            when (it) {
                DiscoverSection.Discover -> pluginPresent || hasSeerr
                DiscoverSection.Upcoming -> hasUpcoming || hasSeerr
                else -> hasSeerr
            }
        }

    val shown: DiscoverSection? get() = chosen?.takeIf { it in sections } ?: sections.firstOrNull()

    val rows = DiscoverRowsModel(this)
    val movies = GridModel(viewModelScope, { seerr?.client }, SeerrFilter(SeerrFilter.Kind.Movie))
    val series = GridModel(viewModelScope, { seerr?.client }, SeerrFilter(SeerrFilter.Kind.Tv))
    val requests = RequestsModel(viewModelScope) { seerr }
    val upcoming = UpcomingAgendaModel(repo, viewModelScope) { seerr }

    /** Whether a remote has been put on the page; once only, so coming back from a title keeps focus where it was. */
    var firstFocusGiven = false

    init {
        viewModelScope.launch {
            // Last time's page at once (the Apple apps keep Home so; Discover too here), then the server's in its place.
            restore()
            check()
        }
    }

    private suspend fun restore() {
        val kept = repo.snapshot(SNAPSHOT)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val page = repo.keptDiscoverHome()
        pluginPresent = kept.optBoolean("plugin")
        seerrLastTime = kept.optBoolean("seerr")
        hasUpcoming = kept.optBoolean("upcoming")
        rows.restore(page, kept)
        checked = true
    }

    /** What the server has; again when the app comes back after a while (the repository keeps the answer half an hour). */
    fun check() {
        viewModelScope.launch {
            // Side by side: Seerr's sign-in and the Upcoming check don't wait on each other, and the server's page is asked
            // for the moment the plugin's known to be there.
            val account = async { repo.seerr() }
            val upcoming = async { runCatching { repo.upcoming(1).isNotEmpty() }.getOrDefault(false) }
            pluginPresent = repo.trackingFeatures() != null
            rows.load(account = account)
            seerr = account.await()
            answered = true
            // Seerr's gone since last time: its rows go with it.
            if (seerr == null) rows.dropSeerr()
            hasUpcoming = upcoming.await()
            checked = true
            DebugLog.note("Discover", "Plugin ${if (pluginPresent) "present" else "absent"}; Seerr ${if (seerr != null) "on" else "off"}; upcoming $hasUpcoming")
        }
    }

    /** What [restore] shows next time: which sections there were, and Seerr's rows with their titles. */
    internal fun keep() {
        val o = JSONObject().put("plugin", pluginPresent).put("seerr", seerr != null || !rows.sliders.isNullOrEmpty()).put("upcoming", hasUpcoming)
        rows.keepInto(o)
        repo.keepSnapshot(SNAPSHOT, o.toString())
    }

    private companion object { const val SNAPSHOT = "discover" }
}

/**
 * The Discover page: what the server's plugin built for this person and Seerr's own rows in its admin's order, mixed as the
 * contract says. Seerr's rows don't wait for the server's page: if it isn't back within a second and a half they show alone,
 * and it fills in when it comes.
 */
class DiscoverRowsModel(private val owner: DiscoverViewModel) {
    var tracked by mutableStateOf<List<DiscoverRow>>(emptyList()); private set
    var sliders by mutableStateOf<List<SeerrSlider>?>(null); private set
    var loaded by mutableStateOf(false); private set
    var problem by mutableStateOf<String?>(null); private set
    var refreshing by mutableStateOf(false); private set
    /** Each Seerr row's titles, genres or companies, kept while the tab lives. */
    val content = mutableStateMapOf<String, SliderContent>()
    private var job: Job? = null

    val entries: List<DiscoverEntry> get() = DiscoverOrder.order(tracked, sliders, theaters = owner.hasSeerr)

    /** Rows shown from last time, asked for again once Seerr answers. */
    private val stale = mutableSetOf<String>()

    /** Last time's page: the server's rows, Seerr's rows and their titles. */
    internal fun restore(page: com.astermediaplayer.astermediaplayer.data.DiscoverPage?, kept: JSONObject) {
        tracked = page?.rows.orEmpty()
        val list = kept.optJSONArray("sliders")
        if (list != null) sliders = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map {
            SeerrSlider(if (it.has("id")) it.optInt("id") else null, it.optInt("type"), it.optString("title").ifEmpty { null }, it.optString("data").ifEmpty { null })
        }
        val titles = kept.optJSONObject("titles")
        titles?.keys()?.forEach { key ->
            val array = titles.optJSONArray(key) ?: return@forEach
            content[key] = SliderContent.Titles((0 until array.length()).mapNotNull { array.optJSONObject(it) }.map { SeerrTitle.parse(it) })
            stale += key
        }
        loaded = tracked.isNotEmpty() || !sliders.isNullOrEmpty()
    }

    internal fun dropSeerr() { sliders = null; content.clear(); stale.clear() }

    internal fun keepInto(o: JSONObject) {
        sliders?.let { found ->
            o.put("sliders", JSONArray().apply {
                found.forEach { put(JSONObject().put("type", it.type).apply { it.id?.let { id -> put("id", id) }; it.title?.let { t -> put("title", t) }; it.data?.let { d -> put("data", d) } }) }
            })
        }
        val titles = JSONObject()
        content.forEach { (key, value) -> if (value is SliderContent.Titles && value.titles.isNotEmpty()) titles.put(key, JSONArray().apply { value.titles.take(20).forEach { put(it.toJson()) } }) }
        o.put("titles", titles)
    }

    fun load(fresh: Boolean = false, account: kotlinx.coroutines.Deferred<AsterRepository.SeerrAccount?>? = null) {
        job?.cancel()
        job = owner.viewModelScope.launch {
            if (fresh) refreshing = true
            val repo = owner.repo
            val home = async {
                if (!owner.pluginPresent) null
                else runCatching { repo.discoverHome(fresh) }.onFailure { DebugLog.note("Discover", "The server's page failed: ${it.message}") }.getOrNull()
            }
            var found: List<SeerrSlider>? = null
            problem = null
            (account?.await() ?: owner.seerr)?.let { account ->
                try {
                    found = account.client.sliders()
                } catch (e: Exception) {
                    noteSeerrFailure(e, "Discover rows")
                    problem = (e as? SeerrException)?.message ?: "Seerr's rows couldn't be read."
                }
            }
            if (fresh) { content.clear(); stale.clear() }
            // The server's page, if it's quick; else Seerr's rows alone first. Last time's rows stay up meanwhile.
            val shownBefore = loaded
            val quick = withTimeoutOrNull(1_500) { home.await() }
            if (found != null || !owner.hasSeerr) sliders = found
            if (quick != null || home.isCompleted) {
                quick?.let { tracked = it.rows } ?: run { if (!shownBefore) tracked = emptyList() }
                loaded = true
            } else {
                loaded = shownBefore || found != null
                home.await()?.let { tracked = it.rows }
                loaded = true
            }
            refreshing = false
            owner.keep()
        }
    }

    /** A Seerr row's own content, asked once. */
    fun loadSlider(slider: SeerrSlider) {
        val key = slider.stableId
        val client = owner.seerr?.client ?: return
        // Shown from last time: asked again, in place, without a spinner over it.
        val again = stale.remove(key)
        if (content.containsKey(key) && !again) return
        if (!again) content[key] = SliderContent.Loading
        owner.viewModelScope.launch {
            content[key] = try {
                val kind = sliderKind(slider)
                when (slider.type) {
                    SeerrClient.IN_THEATERS_TYPE -> SliderContent.Titles(client.inTheaters())
                    1 -> SliderContent.Titles(client.recentlyAdded())
                    2 -> {
                        val requests = client.requests(SeerrRequestFilter.All, mine = false, userId = owner.seerr?.userId ?: 0).results
                        SliderContent.Titles(client.titlesFor(requests.map { it.tmdbId to it.kind }.distinct()))
                    }
                    6, 10 -> SliderContent.Genres(client.genres(kind), kind)
                    8, 12 -> SliderContent.Companies(client.companies(kind), kind)
                    else -> SeerrClient.titlesPath(slider)?.let { (path, k) -> SliderContent.Titles(client.titles(path, k).results) } ?: SliderContent.Titles(emptyList())
                }
            } catch (e: Exception) {
                noteSeerrFailure(e, SeerrClient.sliderTitle(slider))
                if (again) content[key] ?: SliderContent.Titles(emptyList()) else SliderContent.Titles(emptyList())
            }
            owner.keep()
        }
    }

    /** Movie genres and studios are films'; the others shows'. */
    private fun sliderKind(slider: SeerrSlider) = if (slider.type == 6 || slider.type == 8) SeerrFilter.Kind.Movie else SeerrFilter.Kind.Tv
}

sealed interface SliderContent {
    data object Loading : SliderContent
    data class Titles(val titles: List<SeerrTitle>) : SliderContent
    data class Genres(val genres: List<SeerrGenre>, val kind: SeerrFilter.Kind) : SliderContent
    data class Companies(val companies: List<SeerrCompany>, val kind: SeerrFilter.Kind) : SliderContent
}

/** Films or shows from Seerr, filtered and sorted, a page at a time as the grid scrolls; and the filters' choices. */
class GridModel(private val scope: CoroutineScope, private val client: () -> SeerrClient?, initial: SeerrFilter) {
    var filter by mutableStateOf(initial); private set
    var cards by mutableStateOf<List<SeerrTitle>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    var genres by mutableStateOf<List<SeerrGenre>>(emptyList()); private set
    var companies by mutableStateOf<List<SeerrCompany>>(emptyList()); private set
    var providers by mutableStateOf<List<SeerrProvider>>(emptyList()); private set
    private var page = 0
    private var totalPages = 1
    private var job: Job? = null
    private var choicesFor: SeerrFilter.Kind? = null
    private var started = false

    /** The first page and the choices, once. */
    fun start() {
        if (started || client() == null) return
        started = true
        reload()
    }

    fun set(new: SeerrFilter) {
        if (new == filter) return
        filter = new
        reload()
    }

    private fun reload() {
        job?.cancel()
        cards = emptyList()
        page = 0
        totalPages = 1
        loading = false
        loadMore()
        loadChoices()
    }

    /** Near the end: the next page. Seerr's pages may come back short (the admin's blocks), so paging goes by its page count. */
    fun loadMore() {
        val c = client() ?: return
        if (loading || page >= totalPages) return
        loading = true
        val asked = filter
        job = scope.launch {
            try {
                val reply = c.titles(asked.path(page + 1), asked.kind)
                if (asked != filter) return@launch
                val seen = cards.map { it.key }.toMutableSet()
                cards = cards + reply.results.filter { seen.add(it.key) }
                page = reply.page
                totalPages = minOf(reply.totalPages, 500)
                failed = false
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                noteSeerrFailure(e, "browse")
                failed = cards.isEmpty()
            } finally {
                if (asked == filter) loading = false
            }
        }
    }

    private fun loadChoices() {
        val c = client() ?: return
        if (choicesFor == filter.kind) return
        choicesFor = filter.kind
        val kind = filter.kind
        scope.launch {
            genres = runCatching { c.genres(kind) }.getOrDefault(emptyList())
            companies = runCatching { c.companies(kind) }.getOrDefault(emptyList())
            // The services people have, not all three hundred.
            providers = runCatching { c.providers(kind, filter.region) }.getOrDefault(emptyList()).take(40)
        }
    }
}

/** Requests and where each stands: everyone's for whoever manages them (Approve, Edit, Decline), else the viewer's own. */
class RequestsModel(private val scope: CoroutineScope, private val account: () -> AsterRepository.SeerrAccount?) {
    var filter by mutableStateOf(SeerrRequestFilter.All); private set
    var mine by mutableStateOf(false); private set
    var manages by mutableStateOf(false); private set
    var requests by mutableStateOf<List<SeerrRequest>>(emptyList()); private set
    val titles = mutableStateMapOf<String, SeerrTitle>()
    var loading by mutableStateOf(false); private set
    var working by mutableStateOf<Int?>(null); private set
    var problem by mutableStateOf<String?>(null); private set
    private var started = false

    fun start() { if (!started) { started = true; load() } }
    fun showFilter(f: SeerrRequestFilter) { filter = f; load() }
    fun showMine(m: Boolean) { mine = m; load() }
    val userId: Int get() = account()?.userId ?: 0

    fun load() {
        val a = account() ?: return
        loading = true
        scope.launch {
            try {
                manages = a.managesRequests
                val page = a.client.requests(filter, mine = mine || !manages, userId = a.userId)
                requests = page.results
                problem = null
                val missing = page.results.filter { !titles.containsKey(it.titleKey) }.map { it.tmdbId to it.kind }.distinct()
                a.client.titlesFor(missing).forEach { titles[it.key] = it }
            } catch (e: Exception) {
                noteSeerrFailure(e, "requests")
                problem = e.message
            } finally {
                loading = false
            }
        }
    }

    enum class Action { Approve, Decline, Cancel }

    fun act(request: SeerrRequest, action: Action) {
        val a = account() ?: return
        working = request.id
        scope.launch {
            try {
                when (action) {
                    Action.Approve -> a.client.approve(request)
                    Action.Decline -> a.client.decline(request)
                    Action.Cancel -> a.client.cancel(request)
                }
                load()
            } catch (e: Exception) {
                noteSeerrFailure(e, "request $action")
                problem = e.message
            } finally {
                working = null
            }
        }
    }

    suspend fun update(request: SeerrRequest, options: SeerrRequestOptions) {
        account()?.client?.update(request, options)
        load()
    }
}
