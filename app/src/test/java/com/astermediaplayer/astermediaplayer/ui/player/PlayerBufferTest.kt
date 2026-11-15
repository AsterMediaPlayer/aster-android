package com.astermediaplayer.astermediaplayer.ui.player

import androidx.media3.exoplayer.DefaultLoadControl
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerBufferTest {
    private val mb = 1024L * 1024

    @Test
    fun anEchoShowsHeapGetsAQuarter() {
        // Fire OS 7 on an Echo Show allows the app 128 MB; media3's default would want about 144 MB.
        assertEquals((32 * mb).toInt(), bufferBytesFor(128 * mb))
    }

    @Test
    fun aLargeHeapStopsAtMedia3sDefault() {
        val ceiling = DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE + DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE
        assertEquals(ceiling, bufferBytesFor(2048 * mb))
    }

    @Test
    fun aTinyHeapStillGetsMedia3sMinimum() {
        assertEquals(DefaultLoadControl.DEFAULT_MIN_BUFFER_SIZE, bufferBytesFor(16 * mb))
    }
}
