package com.astermediaplayer.astermediaplayer.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A debug report's lines with what signs in, where the server is and who's using it taken out. All values here are made up. */
class RedactTest {
    private fun clean(s: String, vararg names: String) = Redact.text(s, names.toList())

    @Test fun tokensInAddressesAndHeaders() {
        val line = "GET https://media.example.org:8920/Videos/1/stream?api_key=0123456789abcdef0123456789abcdef&Static=true"
        val out = clean(line)
        assertEquals("GET https://<server>/Videos/1/stream?api_key=<hidden>&Static=true", out)
        assertEquals("X-Emby-Token: <hidden>", clean("X-Emby-Token: abcdef0123456789"))
        assertEquals("…?X-Plex-Token=<hidden>&x=1", clean("…?X-Plex-Token=AbC-dEf_123&x=1"))
        assertEquals("""{"AccessToken":"<hidden>","User":1}""", clean("""{"AccessToken":"feedfacefeedface","User":1}"""))
        assertEquals("""MediaBrowser Client="Aster", DeviceId="<hidden>", Token="<hidden>"""", clean("""MediaBrowser Client="Aster", DeviceId="abc123", Token="secret-value""""))
        assertEquals("Authorization: Bearer <hidden>", clean("Authorization: Bearer abcdefghijklmnop"))
        assertEquals("""{"Username":"x","Pw":"<hidden>"}""", clean("""{"Username":"x","Pw":"hunter22"}"""))
    }

    @Test fun addressesAndHosts() {
        assertEquals("http://<server>/emby/System/Info", clean("http://192.0.2.10:8096/emby/System/Info"))
        assertEquals("connecting to <server>", clean("connecting to 198-51-100-7.0123456789abcdef0123456789abcdef.plex.direct"))
        assertEquals("from <ip> and <ip>", clean("from 203.0.113.5 and 2001:db8:0:0:0:0:0:1"))
        assertEquals("mail <email>", clean("mail someone@example.com"))
    }

    @Test fun libraryPathsKeepOnlyTheirKind() {
        assertEquals(""""Path":"<path>.mkv"""", clean(""""Path":"/mnt/Anime/Some Show (2023)/Season 1/S01E01.mkv""""))
        assertEquals("opened <path>.m4b", clean("""opened D:\Books\An Author\A Book.m4b"""))
    }

    @Test fun theUsersNameGoesWhereverItIs() {
        assertEquals("Signed in as <name> on <name>'s server", clean("Signed in as Jordan on jordan's server", "Jordan"))
        // Only as a whole word: a longer word that holds it stays.
        assertEquals("Jordanian food", clean("Jordanian food", "Jordan"))
    }

    /** A server called "Server" is no clue to whose it is, and a placeholder put in already isn't touched. */
    @Test fun genericNamesAndPlaceholdersStay() {
        assertEquals("GET http://<server>/Items: the server's settings", clean("GET http://192.0.2.1:8096/Items: the server's settings", "Server"))
    }

    @Test fun theAccountsIdGoesWithAndWithoutDashes() {
        val id = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
        assertEquals("/Users/<name>/Views?userId=<name>", clean("/Users/$id/Views?userId=${id.replace("-", "")}", id))
    }

    @Test fun whatsUsefulStays() {
        val line = "Playback failed: ERROR_CODE_DECODING_FAILED, video hevc 3840x2160 10-bit, audio truehd 7.1, direct play, item 4a1f"
        assertEquals(line, clean(line))
        val out = clean("E/ExoPlayerImplInternal: Playback error at 00:12:31.4")
        assertTrue(out.contains("00:12:31.4"))
        assertFalse(clean("version 0.10.4 on Fire OS 8.1.0.3").contains("<ip>"))
    }
}
