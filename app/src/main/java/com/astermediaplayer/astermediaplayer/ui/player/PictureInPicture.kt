package com.astermediaplayer.astermediaplayer.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational

/**
 * Picture in picture on a phone, as on the iPhone: a video playing when the app leaves the screen carries on in a
 * floating window. Its controls are the media session's ([PlaybackService]), so a channel's window has no skipping.
 */
object PictureInPicture {
    /** What the window opens with when the viewer leaves the app; null while there's no video playing. */
    private var params: PictureInPictureParams? = null

    /** Offers the window for a video of [aspect] playing, or takes the offer back with null. */
    fun offer(activity: Activity, aspect: Rational?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val builder = PictureInPictureParams.Builder()
        if (aspect != null) builder.setAspectRatio(clamped(aspect))
        // Android 12 opens the window by itself as the app leaves; before that it's asked for in [onUserLeaveHint].
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(aspect != null).setSeamlessResizeEnabled(true)
        val built = builder.build()
        params = built.takeIf { aspect != null }
        runCatching { activity.setPictureInPictureParams(built) }
    }

    /** The Home button or another app, before Android 12: a video playing goes into the window. */
    fun onUserLeaveHint(activity: Activity) {
        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.O until Build.VERSION_CODES.S) return
        params?.let { runCatching { activity.enterPictureInPictureMode(it) } }
    }

    /** The system takes ratios from 1:2.39 to 2.39:1; a wider or taller picture is shown letterboxed in the nearest. */
    private fun clamped(aspect: Rational): Rational {
        val widest = Rational(239, 100)
        val tallest = Rational(100, 239)
        return when {
            aspect.toFloat() > widest.toFloat() -> widest
            aspect.toFloat() < tallest.toFloat() -> tallest
            else -> aspect
        }
    }
}
