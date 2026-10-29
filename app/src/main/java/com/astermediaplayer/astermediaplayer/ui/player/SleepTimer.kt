package com.astermediaplayer.astermediaplayer.ui.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pauses the song or book after a while, or as the song or chapter playing ends: from Now Playing's clock button. Its place is
 * kept as with any pause. Moving on by hand (Next, Previous, a chapter picked) doesn't count as the end. Stopping, or choosing
 * Off, ends it. As the Windows app's; the Apple app has none.
 */
class SleepTimer(private val scope: CoroutineScope?, private val now: () -> Long = System::currentTimeMillis) {
    /** What's set: minutes (running out at [endsAt]), or the end of what's playing, or nothing. */
    data class State(val minutes: Int? = null, val endsAt: Long = 0, val atEnd: Boolean = false) {
        val isSet: Boolean get() = minutes != null || atEnd
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** What the timer does when it runs out: pauses whatever plays. */
    var onFire: () -> Unit = {}

    /** The song or chapter playing as "End of …" was set, or since the last move by hand. */
    private var watching: Any? = null
    private var movedByHandAt = Long.MIN_VALUE / 2
    private var tick: Job? = null

    fun setMinutes(minutes: Int) {
        _state.value = State(minutes = minutes, endsAt = now() + minutes * 60_000L)
        tick?.cancel()
        tick = scope?.launch { while (isActive) { delay(1_000); check() } }
    }

    /** At the end of [playing], the song or chapter on now. */
    fun setAtEnd(playing: Any?) {
        tick?.cancel()
        watching = playing
        _state.value = State(atEnd = true)
    }

    fun cancel() {
        tick?.cancel()
        watching = null
        _state.value = State()
    }

    /** Next, Previous or a chapter picked: the song or chapter that follows is the one to wait out. */
    fun movedByHand() { movedByHandAt = now() }

    /** The minutes ran out: pauses. Called each second while minutes are set. */
    fun check() {
        val s = _state.value
        if (s.minutes != null && now() >= s.endsAt) fire()
    }

    /**
     * What's playing changed: [playing] is the song's place in the queue or the book's chapter, [loaded] whether anything is
     * playing at all (stopped, there's nothing left to pause).
     */
    fun playing(playing: Any?, loaded: Boolean) {
        val s = _state.value
        if (!s.isSet) return
        if (!loaded) { cancel(); return }
        if (!s.atEnd || playing == watching) return
        watching = playing
        if (now() - movedByHandAt > HAND_MOVE_GRACE_MS) fire()
    }

    private fun fire() {
        cancel()
        onFire()
    }

    companion object {
        val minuteChoices = listOf(15, 30, 45, 60)

        /** A song or chapter changing this soon after a move by hand isn't its end. */
        const val HAND_MOVE_GRACE_MS = 3_000L
    }
}
