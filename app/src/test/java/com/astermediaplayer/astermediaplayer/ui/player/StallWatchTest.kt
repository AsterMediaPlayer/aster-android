package com.astermediaplayer.astermediaplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The same cases as the Apple apps' `StreamQualityTests`: a lower quality is offered after one stall that lasts, or stalls that keep coming back, never for a short blip, and not again for a while once "Keep Trying" has been chosen. */
class StallWatchTest {
    /** Feeds the watch (stalling or not, at seconds from the start) and returns its answers in order. */
    private fun answers(samples: List<Pair<Boolean, Double>>, declineAt: Double? = null): List<Boolean> {
        val watch = StallWatch()
        return samples.map { (stalling, at) ->
            val now = (at * 1000).toLong()
            watch.note(stalling, now).also { if (declineAt != null && at == declineAt) watch.declined(now) }
        }
    }

    @Test fun aShortBlipIsLeftAlone() {
        assertEquals(listOf(false, false, false), answers(listOf(true to 0.0, true to 2.0, false to 3.0)))
    }

    @Test fun oneLongStallBringsTheOffer() {
        assertEquals(listOf(false, false, true), answers(listOf(true to 0.0, true to 4.9, true to 5.0)))
    }

    @Test fun stallsThatKeepComingBackBringTheOffer() {
        // The third stall inside a minute.
        assertEquals(
            listOf(false, false, false, false, true),
            answers(listOf(true to 0.0, false to 1.0, true to 20.0, false to 21.0, true to 45.0)),
        )
    }

    @Test fun oldStallsStopCounting() {
        val result = answers(listOf(true to 0.0, false to 1.0, true to 70.0, false to 71.0, true to 140.0, false to 141.0))
        assertFalse(result.contains(true))
    }

    @Test fun keepTryingHoldsTheOfferOff() {
        val result = answers(
            listOf(
                true to 0.0, true to 6.0, // offered, then Keep Trying
                true to 60.0, false to 61.0, // still bad, but inside the quiet two minutes
                true to 130.0, true to 136.0, // after it, a fresh long stall offers again
            ),
            declineAt = 6.0,
        )
        assertEquals(listOf(false, true, false, false, false, true), result)
    }
}
