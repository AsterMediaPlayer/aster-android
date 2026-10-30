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
         {"protocol":"https","address":"203.0.113.30","port":8443,"uri":"https://203-0-113-30.f.plex.direct:8443","local":false,"relay":true,"IPv6":false},
         {"protocol":"https","address":"203.0.113.20","port":13041,"uri":"https://203-0-113-20.f.plex.direct:13041","local":false,"relay":false,"IPv6":false},
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
                "https://203-0-113-20.f.plex.direct:13041",
                "https://203-0-113-30.f.plex.direct:8443",
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

    @Test fun aLinkCodeBeforeAndAfter() {
        val waiting = PlexAccount.parsePin(JSONObject("""{"id":123,"code":"ABCD","clientIdentifier":"c","authToken":null,"expiresIn":1800}"""))
        assertEquals("ABCD", waiting.code)
        // A JSON null is no token (org.json would read it as the text "null").
        assertEquals(null, waiting.authToken)
        assertEquals(1800, waiting.expiresInSeconds)
        val linked = PlexAccount.parsePin(JSONObject("""{"id":123,"code":"ABCD","authToken":"tok","expiresIn":1700}"""))
        assertEquals("tok", linked.authToken)
    }

    @Test fun nullsInAServerListAreNothing() {
        val servers = PlexAccount.parseResources("""[{"name":"S","provides":"server","clientIdentifier":"c","accessToken":null,"productVersion":null,"owned":true,"connections":[{"uri":"http://a:1","address":null,"port":null,"local":true,"relay":false}]}]""")
        assertEquals(null, servers.single().accessToken)
        assertEquals(null, servers.single().connections.single().address)
    }

    private val home = """{"id":1,"name":"Berryman","users":[
        {"id":214934,"uuid":"5fc3161f66ce9f5e","title":"peppy6582","protected":true,"admin":true,"restricted":false},
        {"id":166937416,"uuid":"0572256652940cc0","title":"Lady","protected":true,"admin":false,"restricted":true},
        {"id":166937264,"uuid":"d064789a0670d4dd","title":"Lana","protected":false,"admin":false,"restricted":true},
        {"id":5,"title":"No uuid"}]}"""

    @Test fun homeProfilesSayWhichHaveAPin() {
        val people = PlexAccount.parseHomeUsers(home)
        assertEquals(listOf("peppy6582", "Lady", "Lana"), people.map { it.title })
        assertEquals(listOf(true, true, false), people.map { it.hasPin })
        assertEquals(listOf(true, false, false), people.map { it.admin })
        assertEquals("0572256652940cc0", people[1].uuid)
    }

    @Test fun anAccountWithoutAHomeHasNoProfiles() {
        assertEquals(emptyList<PlexHomeUser>(), PlexAccount.parseHomeUsers("""{"id":1}"""))
    }

    @Test fun aLinkKeepsTheProfileItWasSignedInAs() {
        val link = PlexLink("own", "machine", emptyList(), homeUserUuid = "5fc3161f66ce9f5e", homeUserHasPin = true, homeToken = "home")
        val back = PlexLink.fromJson(org.json.JSONObject(link.toJson().toString()))
        assertEquals(link, back)
        assertEquals(true, back.canAskPin)
    }

    @Test fun aLinkSavedBeforeProfilesWasKeptHasNoPinToAsk() {
        val old = PlexLink.fromJson(org.json.JSONObject("""{"accountToken":"a","machineId":"m","connections":[]}"""))
        assertEquals(null, old.homeUserUuid)
        assertEquals(false, old.canAskPin)
    }

    @Test fun aPinNeedsAProfileThatHasOneAndTheHomesToken() {
        assertEquals(false, PlexLink("a", "m", emptyList(), homeUserUuid = "u", homeUserHasPin = false, homeToken = "h").canAskPin)
        assertEquals(false, PlexLink("a", "m", emptyList(), homeUserUuid = "u", homeUserHasPin = true, homeToken = null).canAskPin)
        assertEquals(false, PlexLink("a", "m", emptyList(), homeUserUuid = null, homeUserHasPin = true, homeToken = "h").canAskPin)
    }
}
