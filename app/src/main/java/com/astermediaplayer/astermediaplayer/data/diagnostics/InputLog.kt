package com.astermediaplayer.astermediaplayer.data.diagnostics

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * What input reaches the app, for a debug report: the devices the system lists, and the first taps, clicks and key presses with
 * where they came from. Made for a TV with a touchscreen (the Echo Show 15), to tell whether a tap reaches the app at all, and as
 * what: a touch, a mouse click, or a key the system made of it.
 */
object InputLog {
    private const val KEEP_TOUCHES = 40
    private const val KEEP_KEYS = 30
    private const val KEEP_OTHER = 20

    private var touches = 0
    private var keys = 0
    private var madeKeys = 0
    private var other = 0

    /** The input devices, once as the app starts. */
    fun noteDevices(touchTv: Boolean) {
        val devices = InputDevice.getDeviceIds().toList().mapNotNull(InputDevice::getDevice)
        DebugLog.note("Input", "Touch TV layout: $touchTv; ${devices.size} input device(s)")
        devices.forEach { d ->
            DebugLog.note("Input", "Device ${d.id} \"${d.name}\": ${sources(d.sources)}${if (d.isVirtual) ", virtual" else ""}")
        }
    }

    /** A touch or click going to the window; only where it starts (down) is told. */
    fun touch(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN && event.actionMasked != MotionEvent.ACTION_POINTER_DOWN) return
        if (++touches > KEEP_TOUCHES) { if (touches == KEEP_TOUCHES + 1) DebugLog.note("Input", "More taps after this aren't listed"); return }
        DebugLog.note("Input", "Tap #$touches at ${event.x.toInt()},${event.y.toInt()}: ${describe(event)}")
    }

    /** Hovering, a mouse's buttons, a scroll wheel: what isn't a touch but might be what a tap became. */
    fun generic(event: MotionEvent) {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_HOVER_MOVE) return
        if (++other > KEEP_OTHER) return
        DebugLog.note("Input", "Pointer ${MotionEvent.actionToString(action)} at ${event.x.toInt()},${event.y.toInt()}: ${describe(event)}")
    }

    /** A key press (down), and where it came from: a remote, or something the system made. */
    fun key(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return
        val device = event.device
        // Keys the system makes (no device, or a virtual one) are counted apart, so a remote's presses can't crowd them out.
        val made = device == null || device.isVirtual || event.deviceId <= 0
        if (made) { if (++madeKeys > KEEP_KEYS) return }
        else if (++keys > KEEP_KEYS) { if (keys == KEEP_KEYS + 1) DebugLog.note("Input", "More remote keys after this aren't listed"); return }
        DebugLog.note(
            "Input",
            "Key ${KeyEvent.keyCodeToString(event.keyCode)} from ${sources(event.source)}, device ${event.deviceId}" +
                (device?.let { " \"${it.name}\"${if (it.isVirtual) " (virtual)" else ""}" } ?: ""),
        )
    }

    private fun describe(event: MotionEvent): String {
        val tool = when (event.getToolType(0)) {
            MotionEvent.TOOL_TYPE_FINGER -> "finger"
            MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
            MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
            MotionEvent.TOOL_TYPE_ERASER -> "eraser"
            else -> "unknown tool"
        }
        val device = event.device
        return "$tool, from ${sources(event.source)}, device ${event.deviceId}" + (device?.let { " \"${it.name}\"" } ?: "")
    }

    /** An input source's bits as words: "touchscreen, keyboard, dpad". */
    fun sources(bits: Int): String {
        val named = listOf(
            InputDevice.SOURCE_TOUCHSCREEN to "touchscreen", InputDevice.SOURCE_MOUSE to "mouse", InputDevice.SOURCE_TOUCHPAD to "touchpad",
            InputDevice.SOURCE_TOUCH_NAVIGATION to "touch navigation", InputDevice.SOURCE_STYLUS to "stylus", InputDevice.SOURCE_DPAD to "dpad",
            InputDevice.SOURCE_GAMEPAD to "gamepad", InputDevice.SOURCE_JOYSTICK to "joystick", InputDevice.SOURCE_KEYBOARD to "keyboard",
            InputDevice.SOURCE_TRACKBALL to "trackball", InputDevice.SOURCE_ROTARY_ENCODER to "rotary",
        ).filter { (bit, _) -> bit != 0 && bits and bit == bit }.map { it.second }
        return named.joinToString(", ").ifEmpty { "source 0x${Integer.toHexString(bits)}" }
    }
}
