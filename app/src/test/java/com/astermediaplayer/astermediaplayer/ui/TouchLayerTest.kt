package com.astermediaplayer.astermediaplayer.ui

import com.astermediaplayer.astermediaplayer.ui.theme.isAmazonDevice
import com.astermediaplayer.astermediaplayer.ui.theme.usesTouchLayer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchLayerTest {
    @Test fun echoShowGetsNoTouchControls() {
        assertTrue(isAmazonDevice("Amazon"))
        assertFalse(usesTouchLayer(tv = true, touchscreen = true, amazon = true))
    }

    @Test fun otherTouchTvsKeepThem() {
        assertFalse(isAmazonDevice("Google"))
        assertTrue(usesTouchLayer(tv = true, touchscreen = true, amazon = false))
        assertFalse(usesTouchLayer(tv = true, touchscreen = false, amazon = false))
        assertFalse(usesTouchLayer(tv = false, touchscreen = true, amazon = false))
    }
}
