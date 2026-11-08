package com.astermediaplayer.astermediaplayer.data

import org.json.JSONArray
import org.json.JSONObject

/*
 * Each person's tracking accounts, linked through the server's Aster Discovery and Tracking plugin
 * (docs/aster-tracking-clients.md §3): Simkl, AniList, MyAnimeList, Last.fm, ListenBrainz and Hardcover, linked once
 * for every app. The server sends what's watched and listened to; the apps only link, unlink, and switch sending on or
 * off. The Kotlin counterpart of the Apple apps' TrackingAccounts.swift.
 */

enum class TrackedService(val id: String, val title: String) {
    Simkl("simkl", "Simkl"),
    AniList("anilist", "AniList"),
    MyAnimeList("myanimelist", "MyAnimeList"),
    LastFm("lastfm", "Last.fm"),
    ListenBrainz("listenbrainz", "ListenBrainz"),
    Hardcover("hardcover", "Hardcover");

    /**
     * How linking goes: a code to enter on the service's site (Simkl, Hardcover), approving Aster there (Last.fm), or a
     * code or token pasted back (AniList, MyAnimeList, ListenBrainz).
     */
    enum class Flow { Code, Approve, Paste }

    val flow: Flow
        get() = when (this) {
            Simkl, Hardcover -> Flow.Code
            LastFm -> Flow.Approve
            AniList, MyAnimeList, ListenBrainz -> Flow.Paste
        }

    /** What it keeps track of. */
    val group: String
        get() = when (this) {
            Simkl -> "Films and Shows"
            AniList, MyAnimeList -> "Anime"
            LastFm, ListenBrainz -> "Music"
            Hardcover -> "Audiobooks"
        }

    /** "watch" or "listen to", for the switch's words. */
    val verb: String get() = if (this == LastFm || this == ListenBrainz || this == Hardcover) "listen to" else "watch"

    /** The key it's under in `GET /AsterTracking/Accounts`. */
    internal val key: String
        get() = when (this) {
            Simkl -> "Simkl"
            AniList -> "AniList"
            MyAnimeList -> "MyAnimeList"
            LastFm -> "LastFm"
            ListenBrainz -> "ListenBrainz"
            Hardcover -> "Hardcover"
        }

    companion object {
        /** The groups in the order Settings shows them. */
        val groupOrder = listOf("Anime", "Films and Shows", "Music", "Audiobooks")
    }
}

data class TrackedAccount(
    val available: Boolean?,
    val linked: Boolean,
    val name: String?,
    /** When the link runs out and needs doing again (AniList's year), as ISO 8601. */
    val expires: String?,
    val scrobble: Boolean?,
)

data class SendLogEntry(val at: String?, val service: String?, val title: String?, val result: String?)

data class TrackedAccounts(
    val accounts: Map<TrackedService, TrackedAccount>,
    /** Sends waiting to be tried again. */
    val waiting: Int,
    /** The last sends, newest first. */
    val log: List<SendLogEntry>,
) {
    fun account(service: TrackedService): TrackedAccount? = accounts[service]

    companion object {
        fun parse(json: String): TrackedAccounts {
            val root = JSONObject(json)
            val accounts = TrackedService.entries.mapNotNull { service ->
                val o = root.ci(service.key) as? JSONObject ?: return@mapNotNull null
                service to TrackedAccount(
                    available = o.ci("Available") as? Boolean,
                    linked = o.ci("Linked") == true,
                    name = (o.ci("Name") as? String)?.ifBlank { null },
                    expires = (o.ci("Expires") as? String)?.ifBlank { null },
                    scrobble = o.ci("Scrobble") as? Boolean,
                )
            }.toMap()
            val log = (root.ci("Log") as? JSONArray)?.let { list ->
                (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { o ->
                    SendLogEntry(o.ci("At") as? String, o.ci("Service") as? String, o.ci("Title") as? String, o.ci("Result") as? String)
                }
            }.orEmpty()
            return TrackedAccounts(accounts, (root.ci("Waiting") as? Number)?.toInt() ?: 0, log)
        }
    }
}

/** What starting to link gives: where to go, and what to enter there. */
data class TrackedLink(
    val url: String,
    val code: String?,
    /** The address with the code in it, for a QR code. */
    val completeUrl: String?,
    val paste: Boolean,
    /** Seconds between checks. */
    val interval: Int?,
    val expiresIn: Int?,
) {
    companion object {
        fun parse(json: String): TrackedLink {
            val o = JSONObject(json)
            return TrackedLink(
                url = (o.ci("Url") as? String).orEmpty(),
                code = (o.ci("Code") as? String)?.ifBlank { null },
                completeUrl = (o.ci("CompleteUrl") as? String)?.ifBlank { null },
                paste = o.ci("Paste") == true,
                interval = (o.ci("Interval") as? Number)?.toInt(),
                expiresIn = (o.ci("ExpiresIn") as? Number)?.toInt(),
            )
        }
    }
}

sealed interface TrackedLinkResult {
    data object Linked : TrackedLinkResult
    /** Not entered or approved yet. */
    data object Waiting : TrackedLinkResult
    /** The code ran out or was refused: start again. */
    data object Expired : TrackedLinkResult
    data class Failed(val message: String) : TrackedLinkResult
}

/** Linking failed, in words to show. */
class TrackingException(message: String) : Exception(message) {
    companion object {
        fun noApp(service: TrackedService) =
            TrackingException("This server has no app to link ${service.title} with yet. Its admin can add one in the plugin's settings.")
        fun unreachable(service: TrackedService) = TrackingException("${service.title} couldn't be reached. Try again in a moment.")
        fun status(code: Int) = TrackingException("The server answered $code.")
    }
}
