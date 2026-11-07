package com.astermediaplayer.astermediaplayer.data.seerr

import java.time.LocalDate

/** How the Movies and Series grids are narrowed and sorted, as Seerr's own Discover filters write it. */
data class SeerrFilter(
    val kind: Kind,
    val sort: Sort = Sort.Popular,
    val genres: Set<Int> = emptySet(),
    val fromYear: Int? = null,
    val toYear: Int? = null,
    /** Out of ten. */
    val minimumRating: Double? = null,
    /** Minutes. */
    val minimumRuntime: Int? = null,
    val maximumRuntime: Int? = null,
    /** ISO 639-1, as TMDB's original language. */
    val language: String? = null,
    /** A studio (films) or network (shows). */
    val company: SeerrCompany? = null,
    val providers: Set<Int> = emptySet(),
    val region: String = java.util.Locale.getDefault().country.ifEmpty { "US" },
    /** Keyword ids, comma-separated, from a custom row. */
    val keywords: String? = null,
) {
    enum class Kind(val wire: String) { Movie("movie"), Tv("tv") }

    enum class Sort(val label: String) {
        Popular("Most Popular"), Rating("Highest Rated"), Newest("Newest"), Oldest("Oldest"), Title("Title");

        fun value(kind: Kind): String {
            val date = if (kind == Kind.Movie) "primary_release_date" else "first_air_date"
            return when (this) {
                Popular -> "popularity.desc"
                Rating -> "vote_average.desc"
                Newest -> "$date.desc"
                Oldest -> "$date.asc"
                Title -> if (kind == Kind.Movie) "title.asc" else "original_name.asc"
            }
        }
    }

    /** Anime: the Animation genre, originally in Japanese. */
    val isAnime: Boolean get() = ANIMATION in genres && language == "ja"

    /** Turning Anime on sets both; off takes both away, leaving any other genres chosen. */
    fun withAnime(on: Boolean): SeerrFilter =
        if (on) copy(genres = genres + ANIMATION, language = "ja")
        else copy(genres = genres - ANIMATION, language = if (language == "ja") null else language)

    /** How many filters are set. */
    val activeCount: Int
        get() = listOf(
            genres.isNotEmpty(), fromYear != null || toYear != null, minimumRating != null,
            minimumRuntime != null || maximumRuntime != null, language != null, company != null, providers.isNotEmpty(), keywords != null,
        ).count { it }

    /** The `/discover/movies` or `/discover/tv` query for [page]. */
    fun path(page: Int): String {
        val items = mutableListOf("page" to page.toString(), "sortBy" to sort.value(kind))
        if (genres.isNotEmpty()) items += "genre" to genres.sorted().joinToString(",")
        val gte = if (kind == Kind.Movie) "primaryReleaseDateGte" else "firstAirDateGte"
        val lte = if (kind == Kind.Movie) "primaryReleaseDateLte" else "firstAirDateLte"
        fromYear?.let { items += gte to "$it-01-01" }
        toYear?.let { items += lte to "$it-12-31" }
        minimumRating?.let {
            items += "voteAverageGte" to (if (it % 1.0 == 0.0) it.toInt().toString() else it.toString())
            // Without a floor of votes, a film three people rated tops the list.
            items += "voteCountGte" to "50"
        }
        minimumRuntime?.let { items += "withRuntimeGte" to it.toString() }
        maximumRuntime?.let { items += "withRuntimeLte" to it.toString() }
        language?.let { items += "language" to it }
        company?.let { items += (if (kind == Kind.Movie) "studio" else "network") to it.id.toString() }
        if (providers.isNotEmpty()) {
            items += "watchProviders" to providers.sorted().joinToString("|")
            items += "watchRegion" to region
        }
        keywords?.let { items += "keywords" to it }
        // Seerr turns away a bare "," (it wants "%2C", which TMDB reads as "and"); "|" ("or", for services) it takes.
        val query = items.joinToString("&") { (k, v) -> "$k=" + encode(v).replace("%7C", "|") }
        return "/discover/${if (kind == Kind.Movie) "movies" else "tv"}?$query"
    }

    companion object {
        /** TMDB's Animation genre. */
        const val ANIMATION = 16

        fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

/** The Released choices. */
enum class YearChoice(val label: String) {
    Any("Any Year"), Upcoming("Not Out Yet"), ThisYear("This Year"), Twenties("2020s"), Tens("2010s"),
    Noughties("2000s"), Nineties("1990s"), Eighties("1980s"), Older("Before 1980");

    fun range(year: Int = LocalDate.now().year): Pair<Int?, Int?> = when (this) {
        Any -> null to null
        Upcoming -> year + 1 to null
        ThisYear -> year to year
        Twenties -> 2020 to 2029
        Tens -> 2010 to 2019
        Noughties -> 2000 to 2009
        Nineties -> 1990 to 1999
        Eighties -> 1980 to 1989
        Older -> null to 1979
    }

    companion object {
        fun of(filter: SeerrFilter) = entries.firstOrNull { it.range() == (filter.fromYear to filter.toYear) } ?: Any
    }
}

/** The Length choices. */
enum class RuntimeChoice(val label: String, val range: Pair<Int?, Int?>) {
    Any("Any Length", null to null), Short("Under 30 min", null to 30), Hour("30–60 min", 30 to 60),
    Ninety("Under 1½ hours", null to 90), Long("1½–2 hours", 90 to 120), Epic("Over 2 hours", 120 to null);

    companion object {
        fun of(filter: SeerrFilter) = entries.firstOrNull { it.range == (filter.minimumRuntime to filter.maximumRuntime) } ?: Any
    }
}

/** The languages offered, by TMDB's code. */
val SeerrLanguages = listOf(
    "en" to "English", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "fr" to "French",
    "es" to "Spanish", "de" to "German", "it" to "Italian", "hi" to "Hindi", "pt" to "Portuguese",
    "sv" to "Swedish", "da" to "Danish", "no" to "Norwegian", "th" to "Thai", "tr" to "Turkish",
)
