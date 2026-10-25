package com.astermediaplayer.astermediaplayer.ui.player

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext

/**
 * Chromecast for a phone: the Cast framework finds the devices and runs the session, and a [CastPlayer] is the player
 * that stands in for the local one while a session is open. Null where Google Play services (or a touch screen to
 * pick a device on) isn't there, so a TV box never shows a Cast button.
 */
class CastSupport private constructor(private val castContext: CastContext, val player: CastPlayer) {
    /** The session opened or closed: the view model moves playback to or from the Chromecast. */
    var onSessionChanged: ((Boolean) -> Unit)? = null

    val isConnected: Boolean get() = player.isCastSessionAvailable

    /** The name of the Chromecast being played on, as its owner named it. */
    val deviceName: String? get() = castContext.sessionManager.currentCastSession?.castDevice?.friendlyName

    init {
        player.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() { onSessionChanged?.invoke(true) }
            override fun onCastSessionUnavailable() { onSessionChanged?.invoke(false) }
        })
    }

    fun release() {
        player.setSessionAvailabilityListener(null)
        player.release()
    }

    companion object {
        fun create(context: Context): CastSupport? = runCatching {
            val castContext = CastContext.getSharedInstance(context.applicationContext)
            CastSupport(castContext, CastPlayer(castContext))
        }.onFailure { android.util.Log.i("Aster", "Chromecast isn't available: ${it.message}") }.getOrNull()
    }
}

/** The Cast button: it lists the Chromecasts on the network, and shows which one is playing. */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx ->
            // The button draws with AppCompat styles, which the app's own theme doesn't provide.
            MediaRouteButton(ContextThemeWrapper(ctx, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)).also {
                runCatching { CastButtonFactory.setUpMediaRouteButton(context.applicationContext, it) }
            }
        },
        modifier = modifier,
    )
}
