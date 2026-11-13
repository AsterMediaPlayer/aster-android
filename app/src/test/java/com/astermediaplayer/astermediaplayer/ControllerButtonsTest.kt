package com.astermediaplayer.astermediaplayer

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControllerButtonsTest {
    @Test fun unswappedButtonsAreLeftAlone() {
        listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y)
            .forEach { assertNull(ControllerButtons.remap(it, swapped = false)) }
    }

    @Test fun swappedTheRightButtonOpensAndTheBottomGoesBack() {
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, ControllerButtons.remap(KeyEvent.KEYCODE_BUTTON_B, swapped = true))
        assertEquals(KeyEvent.KEYCODE_BACK, ControllerButtons.remap(KeyEvent.KEYCODE_BUTTON_A, swapped = true))
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, ControllerButtons.remap(KeyEvent.KEYCODE_BUTTON_Y, swapped = true))
        assertEquals(KeyEvent.KEYCODE_BACK, ControllerButtons.remap(KeyEvent.KEYCODE_BUTTON_X, swapped = true))
        // The d-pad and the shoulders are untouched.
        assertNull(ControllerButtons.remap(KeyEvent.KEYCODE_DPAD_UP, swapped = true))
        assertNull(ControllerButtons.remap(KeyEvent.KEYCODE_BUTTON_L1, swapped = true))
    }
}
