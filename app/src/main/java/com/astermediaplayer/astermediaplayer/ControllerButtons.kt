package com.astermediaplayer.astermediaplayer

import android.view.KeyEvent

/**
 * A game controller's face buttons, swapped for Nintendo's layout (the Apple apps' `Gamepads`, Settings › Playback ›
 * Controllers). Android names the buttons by place (A at the bottom, B on the right, X on the left, Y at the top) and stands
 * in for each with a key: A opens (the centre of the d-pad), B goes back, X opens and Y goes back. Swapped, the pairs trade
 * places: the right button opens and the bottom one goes back.
 */
object ControllerButtons {
    /** The key a button is sent as, or null to leave it as it is. */
    fun remap(keyCode: Int, swapped: Boolean): Int? = if (!swapped) null else when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BACK
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_DPAD_CENTER
        else -> null
    }
}
