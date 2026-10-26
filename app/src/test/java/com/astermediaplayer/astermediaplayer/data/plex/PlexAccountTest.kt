package com.astermediaplayer.astermediaplayer.data.plex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** plex.tv's replies (shapes from a real account, tokens and addresses replaced): servers with their addresses ranked, and a saved sign-in. */
class PlexAccountTest {
    private val resources = """
    [
      {"name":"Living Room","product":"Plex for Apple TV","provides":"client,player","clientIdentifier":"atv","connections":[]},
      {"name":"Friend's Server","product":"Plex Media Server","productVersion":"1.25.4.5487","provides":"server","clientIdentifier":"friend",
       "accessToken":"shared-token","owned":false,"home":false,
       "connections":[
         {"protocol":"https","address":"173.230.133.75","port":8443,"uri":"https://173-230-133-75.f.plex.direct:8443","local":false,"relay":true,"IPv6":false},
         {"protocol":"https","address":"66.78.154.177","port":13041,"uri":"https://66-78-154-177.f.plex.direct:13041","local":false,"relay":false,"IPv6":false},
         {"protocol":"https","address":"192.168.40.240","port":32400,"uri":"https://192-168-40-240.f.plex.direct:32400","local":true,"relay":false,"IPv6":false}
       ]},
      {"name":"Test Server","product":"Plex Media Server","productVersion":"1.43.5.11029","provides":"server","clientIdentifier":"4d09e81d",
       "accessToken":"own-token","owned":true,"home":false,
       "connections":[
         {"protocol":"https","address":"192.168.1.240","port":32400,"uri":"https://192-168-1-240.o.plex.direct:32400","local":true,"relay":false,"IPv6":false},
         {"protocol":"https","address":"172.18.0.1","port":32400,"uri":"https://172-18-0-1.o.plex.direct:32400","local":true,"relay":false,"IPv6":false}
       ]}
    ]
    """

    @Test fun onlyServersAreListedOwnFirst() {
        val servers = PlexAccount.parseResources(resources)
        assertEquals(listOf("Test Server", "Friend's Server"), servers.map { it.name })
        assertEquals("own-token", servers[0].accessToken)
        assertEquals("shared-token", servers[1].accessToken)
        assertTrue(servers[0].owned)
        assertFalse(servers[1].owned)
    }

    @Test fun addressesRankHomeThenInternetThenRelay() {
        val friend = PlexAccount.parseResources(resources)[1]
        assertEquals(
            listOf(
                "https://192-168-40-240.f.plex.direct:32400",
                // The same home address, plainly, in case the secure name's lookup fails.
                "http://192.168.40.240:32400",
                "https://66-78-154-177.f.plex.direct:13041",
                "https://173-230-133-75.f.plex.direct:8443",
            ),
            friend.rankedConnections.map { it.uri },
        )
    }

    @Test fun aSavedSignInKeepsItsAddresses() {
        val server = PlexAccount.parseResources(resources)[0]
        val link = PlexLink("account-token", server.clientIdentifier, server.connections)
        val back = PlexLink.fromJson(JSONObject(link.toJson().toString()))
        assertEquals(link, back)
        assertEquals(listOf(true, true), back.connections.map { it.local })
    }

    @Test fun headersNameThisDevice() {
        val headers = PlexDevice("client-1", "Pixel").headers("tok")
        assertEquals("tok", headers["X-Plex-Token"])
        assertEquals("client-1", headers["X-Plex-Client-Identifier"])
        assertEquals("application/json", headers["Accept"])
        assertEquals(null, PlexDevice("client-1", "Pixel").headers(null)["X-Plex-Token"])
    }
}
