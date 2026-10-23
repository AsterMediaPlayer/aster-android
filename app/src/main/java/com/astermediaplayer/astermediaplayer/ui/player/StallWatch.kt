package com.astermediaplayer.astermediaplayer.ui.player

/**
 * Decides when stalling has gone on long enough to offer a lower quality: one stall that lasts, or stalls that keep coming
 * back. Turning the offer down leaves it alone for a while. The Apple apps' `StallWatch`, with the same numbers.
 */
class StallWatch(
    /** A single stall this long brings the offer up. */
    private val longStallMs: Long = 5_000,
    /** So does this many stalls inside [windowMs]. */
    private val repeats: Int = 3,
    private val windowMs: Long = 60_000,
    /** How long "Keep Trying" holds the offer off. */
    private val quietAfterDeclineMs: Long = 120_000,
) {
    private var stallStart: Long? = null
    private val stalls = ArrayDeque<Long>()
    private var quietUntil: Long? = null

    /** Takes whether playback is held up right now; true when it's time to offer a lower quality. */
    fun note(stalling: Boolean, nowMs: Long): Boolean {
        if (!stalling) {
            stallStart = null
            return false
        }
        if (stallStart == null) {
            stallStart = nowMs
            stalls.addLast(nowMs)
        }
        stalls.removeAll { nowMs - it > windowMs }
        quietUntil?.let { if (nowMs < it) return false }
        val lasting = stallStart?.let { nowMs - it >= longStallMs } ?: false
        return lasting || stalls.size >= repeats
    }

    /** The offer was turned down: hold it off for a while, then count afresh. */
    fun declined(nowMs: Long) {
        quietUntil = nowMs + quietAfterDeclineMs
        stalls.clear()
        if (stallStart != null) stallStart = nowMs
    }
}
