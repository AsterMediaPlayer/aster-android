package com.astermediaplayer.astermediaplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sleep timer's rules, on a clock that only moves when told. As the Windows app's. */
class SleepTimerTest {
    private var clock = 1_000_000L
    private var fired = 0
    private val timer = SleepTimer(scope = null) { clock }.also { it.onFire = { fired++ } }

    @Test fun minutesRunOutThenPause() {
        timer.setMinutes(15)
        clock += 14 * 60_000L
        timer.check()
        assertEquals(0, fired)
        clock += 60_000L
        timer.check()
        assertEquals(1, fired)
        assertFalse(timer.state.value.isSet)
    }

    @Test fun theEndOfASongPausesAsTheNextBegins() {
        timer.setAtEnd(0 to "a")
        timer.playing(0 to "a", loaded = true)
        assertEquals(0, fired)
        clock += 200_000L
        timer.playing(1 to "b", loaded = true)
        assertEquals(1, fired)
    }

    @Test fun skippingByHandIsntTheEnd() {
        timer.setAtEnd("chapter 3")
        clock += 60_000L
        timer.movedByHand()
        clock += 500L
        timer.playing("chapter 4", loaded = true)
        assertEquals(0, fired)
        assertTrue(timer.state.value.atEnd)
        // The chapter skipped to is the one it now waits out.
        clock += 600_000L
        timer.playing("chapter 5", loaded = true)
        assertEquals(1, fired)
    }

    @Test fun stoppingEndsIt() {
        timer.setMinutes(30)
        timer.playing(null, loaded = false)
        assertFalse(timer.state.value.isSet)
        clock += 31 * 60_000L
        timer.check()
        assertEquals(0, fired)
    }

    @Test fun offEndsIt() {
        timer.setAtEnd("x")
        timer.cancel()
        timer.playing("y", loaded = true)
        assertEquals(0, fired)
    }
}
