package com.astermediaplayer.astermediaplayer.data.plex

import com.astermediaplayer.astermediaplayer.data.Session
import com.astermediaplayer.astermediaplayer.data.emby.ServerKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A Plex server whose saved address stops answering is found at another: its other saved addresses, then plex.tv's latest list. */
class PlexReconnectTest {
    private val home = PlexConnection("http://192.168.1.240:32400", "192.168.1.240", 32400, local = true, relay = false)
    private val away = PlexConnection("https://1-2-3-4.abc.plex.direct:32400", "1.2.3.4", 32400, local = false, relay = false)
    private val moved = PlexConnection("http://192.168.1.50:32400", "192.168.1.50", 32400, local = true, relay = false)

    private fun stored(url: String = home.uri, vararg others: PlexConnection) = Session(
        url, "server-token", "u", "peppy", ServerKind.Plex, "Server",
        PlexLink("account", "machine", listOf(home, away) + others),
    )

    /** What answers, by address; every request is kept, with how long it was given. */
    private class Net(val answering: Set<String>, val listed: List<PlexResource> = emptyList(), val listFails: Boolean = false) {
        val asked = mutableListOf<Pair<List<String>, Long>>()
        var listings = 0
        val race: suspend (List<PlexConnection>, String, Long) -> PlexConnection? = { connections, _, seconds ->
            asked += connections.map { it.uri } to seconds
            connections.firstOrNull { it.uri in answering }
        }
        val servers: suspend (String) -> List<PlexResource> = {
            listings++
            if (listFails) throw PlexHttpException(500)
            listed
        }
    }

    private fun resource(vararg connections: PlexConnection, token: String? = null) =
        PlexResource("Server", "machine", token, true, "1.43", connections.toList())

    @Test fun aSavedAddressThatAnswersIsLeftAlone() = runBlocking {
        val net = Net(answering = setOf(home.uri))
        assertNull(PlexReconnect.findAgain(stored(), net.race, net.servers))
        // Only the saved address was asked, for four seconds.
        assertEquals(listOf(listOf(home.uri) to 4L), net.asked)
        assertEquals(0, net.listings)
    }

    @Test fun anotherSavedAddressTakesOverWhenTheSavedOneIsSilent() = runBlocking {
        val net = Net(answering = setOf(away.uri))
        val found = PlexReconnect.findAgain(stored(), net.race, net.servers)!!
        assertEquals(away.uri, found.serverUrl)
        assertEquals("server-token", found.accessToken)
        assertEquals(listOf(home, away), found.plex!!.connections)
        assertEquals(0, net.listings)
    }

    @Test fun plexTvsLatestListIsTriedWhenNoSavedAddressAnswers() = runBlocking {
        val net = Net(answering = setOf(moved.uri), listed = listOf(resource(moved, token = "fresh-token")))
        val found = PlexReconnect.findAgain(stored(), net.race, net.servers)!!
        assertEquals(moved.uri, found.serverUrl)
        // The server's token on the new list goes with it, and the new addresses are kept for next time.
        assertEquals("fresh-token", found.accessToken)
        assertEquals(listOf(moved), found.plex!!.connections)
        assertEquals("account", found.plex!!.accountToken)
        assertEquals(1, net.listings)
    }

    @Test fun noAddressAnsweringLeavesTheSignInAsItWas() = runBlocking {
        val net = Net(answering = emptySet(), listed = listOf(resource(moved)))
        assertNull(PlexReconnect.findAgain(stored(), net.race, net.servers))
    }

    @Test fun plexTvNotAnsweringEitherIsNotAnError() = runBlocking {
        val net = Net(answering = emptySet(), listFails = true)
        assertNull(PlexReconnect.findAgain(stored(), net.race, net.servers))
    }

    @Test fun aServerMissingFromPlexTvsListIsNotGuessedAt() = runBlocking {
        val other = PlexResource("Someone else's", "another-machine", "t", false, "1", listOf(moved))
        val net = Net(answering = setOf(moved.uri), listed = listOf(other))
        assertNull(PlexReconnect.findAgain(stored(), net.race, net.servers))
    }

    @Test fun theSameAddressAnsweringLaterIsNotAMove() = runBlocking {
        // The saved address missed its four seconds but answers when asked with the rest: nothing to change.
        var first = true
        val race: suspend (List<PlexConnection>, String, Long) -> PlexConnection? = { connections, _, _ -> if (first) { first = false; null } else connections.first() }
        assertNull(PlexReconnect.findAgain(stored(), race, { emptyList() }))
    }

    @Test fun aSignInWithoutPlexDetailsIsLeftAlone() = runBlocking {
        val net = Net(answering = emptySet())
        assertNull(PlexReconnect.findAgain(stored().copy(plex = null), net.race, net.servers))
        assertEquals(0, net.asked.size)
    }
}
