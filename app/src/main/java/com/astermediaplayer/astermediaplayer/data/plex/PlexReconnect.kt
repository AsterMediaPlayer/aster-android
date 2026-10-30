package com.astermediaplayer.astermediaplayer.data.plex

import com.astermediaplayer.astermediaplayer.data.Session
import kotlinx.coroutines.CancellationException

/**
 * A Plex server's saved address can stop answering (this device on another network, the server's address changed): its other
 * addresses are tried, then plex.tv's latest list, and the first that answers takes over. The Kotlin counterpart of the Windows app's
 * `PlexReconnect`.
 */
object PlexReconnect {
    /** How long the saved address has to answer before the others are tried. */
    const val SAVED_ADDRESS_WAIT_SECONDS = 4L

    /**
     * The sign-in with the address that answers now; null when the saved one still does, or none does.
     * [race] is the best address of a list that answers within some seconds (null if none), and [servers] plex.tv's servers for an
     * account token; both are passed in so this can be tried without a network.
     */
    suspend fun findAgain(
        stored: Session,
        race: suspend (connections: List<PlexConnection>, token: String, seconds: Long) -> PlexConnection?,
        servers: suspend (accountToken: String) -> List<PlexResource>,
        overallSeconds: Long = 10,
    ): Session? {
        val link = stored.plex ?: return null
        val token = stored.accessToken
        val saved = PlexConnection(stored.serverUrl, null, null, local = true, relay = false)
        if (race(listOf(saved), token, SAVED_ADDRESS_WAIT_SECONDS) != null) return null

        var connections = link.connections
        var serverToken = token
        var reached = race(connections, token, overallSeconds)
        if (reached == null) {
            // Its addresses may have changed since they were saved: plex.tv's latest list.
            try {
                val server = servers(link.accountToken).firstOrNull { it.clientIdentifier == link.machineId }
                if (server != null) {
                    connections = server.rankedConnections
                    serverToken = server.accessToken ?: token
                    reached = race(connections, serverToken, overallSeconds)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // plex.tv didn't answer either: nothing better to go on.
            }
        }
        val uri = reached?.uri?.trimEnd('/') ?: return null
        if (uri == stored.serverUrl.trimEnd('/')) return null
        return stored.copy(serverUrl = uri, accessToken = serverToken, plex = link.copy(connections = connections))
    }
}
