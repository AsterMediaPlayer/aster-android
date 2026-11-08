package com.astermediaplayer.astermediaplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plugin's linked accounts: the page of accounts is read, and each service has its flow and group. */
class TrackingAccountsTest {
    @Test fun accountsAreRead() {
        val accounts = TrackedAccounts.parse(
            """
            {"Simkl":{"Available":true,"Linked":true,"Name":"peppy","Scrobble":true},
             "AniList":{"Available":true,"Linked":false,"Expires":"2027-11-04T12:00:00Z"},
             "LastFm":{"Available":false},
             "Waiting":2,
             "Log":[{"At":"2026-11-04T12:00:00Z","Service":"simkl","Title":"Blue Box","Result":"sent"}]}
            """,
        )
        assertTrue(accounts.account(TrackedService.Simkl)!!.linked)
        assertEquals("peppy", accounts.account(TrackedService.Simkl)!!.name)
        assertFalse(accounts.account(TrackedService.AniList)!!.linked)
        assertEquals("2027-11-04T12:00:00Z", accounts.account(TrackedService.AniList)!!.expires)
        assertEquals(false, accounts.account(TrackedService.LastFm)!!.available)
        assertNull(accounts.account(TrackedService.Hardcover))
        assertEquals(2, accounts.waiting)
        assertEquals("Blue Box", accounts.log.first().title)
    }

    @Test fun eachServiceHasItsFlowAndGroup() {
        assertEquals(TrackedService.Flow.Code, TrackedService.Simkl.flow)
        assertEquals(TrackedService.Flow.Approve, TrackedService.LastFm.flow)
        assertEquals(TrackedService.Flow.Paste, TrackedService.AniList.flow)
        assertEquals("Music", TrackedService.ListenBrainz.group)
        assertEquals("listen to", TrackedService.LastFm.verb)
    }

    @Test fun linkIsRead() {
        val link = TrackedLink.parse("""{"Url":"https://simkl.com/pin","Code":"AB12C","CompleteUrl":"https://simkl.com/pin/AB12C","Interval":5,"ExpiresIn":900}""")
        assertEquals("AB12C", link.code)
        assertEquals(5, link.interval)
        assertFalse(link.paste)
        assertTrue(TrackedLink.parse("""{"Url":"https://anilist.co/api/v2/oauth/authorize","Paste":true}""").paste)
    }
}
