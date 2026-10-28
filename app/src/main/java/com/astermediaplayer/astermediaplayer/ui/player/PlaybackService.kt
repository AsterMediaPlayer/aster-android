package com.astermediaplayer.astermediaplayer.ui.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
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
    private val sessions = mutableMapOf<Player, MediaSession>()

    override fun onCreate() {
        super.onCreate()
        sync()
    }

    /**
     * Publishes whichever players exist: the audiobook player, the music player and the video open each get a session.
     * A video's goes when its player closes.
     */
    private fun sync() {
        val app = application as AsterApp
        val wanted = listOf("audiobook" to app.audiobooks.player, "music" to app.music.player, "video" to app.videoPlayer)
        val keep = wanted.mapNotNull { it.second }.toSet()
        sessions.keys.filter { it !in keep }.forEach { gone ->
            sessions.remove(gone)?.let { removeSession(it); it.release() }
        }
        if (sessions.isEmpty() && keep.isEmpty()) { stopSelf(); return }
        wanted.forEach { (id, player) ->
            if (player == null || sessions.containsKey(player)) return@forEach
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val built = MediaSession.Builder(this, player).setId(id).setSessionActivity(open).build()
            sessions[player] = built
            // Started rather than bound to, so each session is registered here: that is what posts its notification
            // and puts the service in the foreground once it plays.
            addSession(built)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sync()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = sessions.values.firstOrNull { it.player.isPlaying } ?: sessions.values.firstOrNull()

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away leaves something playing going; otherwise the service ends.
        if (sessions.values.none { it.player.playWhenReady && it.player.mediaItemCount > 0 }) stopSelf()
    }

    override fun onDestroy() {
        sessions.values.forEach { it.release() }
        sessions.clear()
        super.onDestroy()
    }
}
