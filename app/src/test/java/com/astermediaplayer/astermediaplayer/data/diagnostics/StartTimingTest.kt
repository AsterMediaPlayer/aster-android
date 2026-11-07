package com.astermediaplayer.astermediaplayer.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class StartTimingTest {
    @Test fun stepsAreTimedFromTheOneBefore() {
        var clock = 1_000L
        val t = StartTiming { clock }
        clock += 120; t.mark("item")
        clock += 300; t.mark("server's playback answer")
        t.method = "transcode"
        clock += 10; t.mark("stream asked for")
        clock += 5_000; t.mark("stream ready", once = true)
        clock += 50; t.mark("stream ready", once = true)
        clock += 40; t.mark("first picture")
        assertEquals(
            "Start (transcode) took 5.52 s: item 0.12 s, server's playback answer +0.30 s, stream asked for +0.01 s, stream ready +5.00 s, first picture +0.09 s",
            t.summary(),
        )
    }

    @Test fun leavingEarlySaysHowFarItGot() {
        var clock = 0L
        val t = StartTiming { clock }
        clock += 200; t.mark("item")
        clock += 8_000
        assertEquals("Start left after 8.20 s before the first picture; it had reached: item 0.20 s", t.summary(leftEarly = true))
    }
}
