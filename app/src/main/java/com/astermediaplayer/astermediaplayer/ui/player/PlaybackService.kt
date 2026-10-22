package com.astermediaplayer.astermediaplayer.ui.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.MainActivity

/**
 * Publishes the audiobook player as a media session: the notification with its play, skip back and skip ahead
 * buttons, the lock screen's controls, headphone buttons and Bluetooth (a car's display), and the foreground service
 * that keeps a book playing with the screen off.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = (application as AsterApp).audiobooks.player ?: return
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val built = MediaSession.Builder(this, player).setSessionActivity(open).build()
        session = built
        // Started rather than bound to, so the session is registered here: that is what posts the notification and
        // puts the service in the foreground once the book plays.
        addSession(built)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away leaves a playing book going; a paused one ends the service.
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
