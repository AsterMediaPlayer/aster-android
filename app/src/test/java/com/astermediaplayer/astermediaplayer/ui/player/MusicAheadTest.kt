package com.astermediaplayer.astermediaplayer.ui.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Saving upcoming songs (the Apple apps' MusicAhead): which are kept, which let go, and when the connection allows it. */
class MusicAheadTest {
    private val folder: File = Files.createTempDirectory("music-ahead").toFile()
    private val asked = mutableListOf<String>()
    private var connectionOk = true
    private var fails = setOf<String>()
    /** Songs held back from finishing, to see what happens while one comes in. */
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    private fun ahead() = MusicAhead(
        folder, { connectionOk },
        fetch = { id, into, _ -> asked += id; gates[id]?.await(); if (id in fails) false else { into.writeText("song $id"); true } },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), io = Dispatchers.Unconfined,
    )

    @Test fun theNextFiveAreSavedInOrder() {
        val a = ahead()
        a.prepare(listOf("a", "b", "c", "d", "e", "f", "g"), playing = "now")
        assertEquals(listOf("a", "b", "c", "d", "e"), asked)
        assertEquals("song c", a.savedFile("c")!!.readText())
        assertNull(a.savedFile("f"))
        // Nothing half-saved is left behind.
        assertTrue(folder.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun whatsBeenHeardIsLetGo() {
        val a = ahead()
        a.prepare(listOf("a", "b", "c"), playing = "now")
        // "a" is playing now; "b" and "c" follow; nothing else stays.
        a.prepare(listOf("b", "c", "d"), playing = "a")
        assertNotNull(a.savedFile("a"))
        a.prepare(listOf("c", "d"), playing = "b")
        assertNull(a.savedFile("a"))
        assertFalse(File(folder, "a").exists())
        assertEquals(listOf("a", "b", "c", "d"), asked)
    }

    @Test fun aSongThatCantBeSavedStreams() {
        fails = setOf("b")
        val a = ahead()
        a.prepare(listOf("a", "b", "c"), playing = null)
        assertNull(a.savedFile("b"))
        assertNotNull(a.savedFile("c"))
        // It isn't asked for again this time.
        a.prepare(listOf("b", "c"), playing = "a")
        assertEquals(listOf("a", "b", "c"), asked)
        // A saved file that didn't play is let go too, and not saved again.
        a.forget("c")
        assertNull(a.savedFile("c"))
        a.prepare(listOf("c"), playing = "b")
        assertEquals(listOf("a", "b", "c"), asked)
    }

    @Test fun nothingIsSavedOnAConnectionItMayNotUse() {
        connectionOk = false
        val a = ahead()
        a.prepare(listOf("a", "b"), playing = null)
        assertTrue(asked.isEmpty())
        connectionOk = true
        a.prepare(listOf("a", "b"), playing = null)
        assertEquals(listOf("a", "b"), asked)
    }

    @Test fun aSongTheQueueMovedPastWhileItCameIsntKept() {
        gates["a"] = CompletableDeferred()
        val a = ahead()
        a.prepare(listOf("a", "b"), playing = null)
        a.prepare(listOf("b"), playing = "x")
        gates.getValue("a").complete(Unit)
        assertNull(a.savedFile("a"))
        assertNotNull(a.savedFile("b"))
    }

    @Test fun holdingStopsTheDownloadAndItsTakenUpAgain() {
        gates["a"] = CompletableDeferred()
        val a = ahead()
        a.prepare(listOf("a", "b"), playing = null)
        a.hold()
        gates.remove("a")
        a.prepare(listOf("a", "b"), playing = "x")
        assertEquals(listOf("a", "a", "b"), asked)
        assertNotNull(a.savedFile("a"))
        assertTrue(folder.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun clearingRemovesEverySong() {
        val a = ahead()
        a.prepare(listOf("a", "b"), playing = null)
        a.clear()
        assertNull(a.savedFile("a"))
        assertTrue(folder.listFiles()!!.isEmpty())
    }

    @Test fun savedSongsAreFoundAgainNextTime() {
        ahead().prepare(listOf("a"), playing = null)
        File(folder, "z.123.part").writeText("half")
        val again = ahead()
        assertNotNull(again.savedFile("a"))
        assertFalse(File(folder, "z.123.part").exists())
    }

    @Test fun aStreamAddressNamesItsSong() {
        assertEquals("0a1b2c3d4e5f60718293a4b5c6d7e8f9", MusicAhead.songId("/Audio/0A1B2C3D-4E5F-6071-8293-A4B5C6D7E8F9/stream"))
        assertEquals("abc", MusicAhead.songId("/jellyfin/Audio/abc/stream.flac"))
        assertNull(MusicAhead.songId("/Videos/abc/stream"))
        assertNull(MusicAhead.songId("/Audio/"))
        assertNull(MusicAhead.songId(null as String?))
    }

    @Test fun cellularOnlyWhenChosenAndNeverInDataSaver() {
        assertTrue(MusicAhead.allowed(unmetered = true, cellularChosen = false, dataSaver = false))
        assertFalse(MusicAhead.allowed(unmetered = false, cellularChosen = false, dataSaver = false))
        assertTrue(MusicAhead.allowed(unmetered = false, cellularChosen = true, dataSaver = false))
        assertFalse(MusicAhead.allowed(unmetered = false, cellularChosen = true, dataSaver = true))
        assertFalse(MusicAhead.allowed(unmetered = true, cellularChosen = true, dataSaver = true))
    }
}
