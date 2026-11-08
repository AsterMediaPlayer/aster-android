package com.astermediaplayer.astermediaplayer.ui.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Publishes what's playing as media sessions: the notification with its play, skip back and skip ahead buttons, the
 * lock screen's controls, headphone buttons, Bluetooth, and Android Auto, which also browses the music and audiobooks
 * through [CarLibrary]. It's the foreground service that keeps audio playing with the screen off.
 *
 * Music and audiobooks share one session, which follows whichever has something loaded (only one plays at a time); a
 * video gets a session of its own while its player is open.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {
    private val app get() = application as AsterApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var audio: MediaLibrarySession? = null
    private var video: MediaSession? = null
    /** The audio session's player before there's a sign-in to make the music player with. */
    private var idle: ExoPlayer? = null
    private val buttons by lazy { CarButtons(app, scope) }
    private val lastPlayed by lazy { LastPlayed(this) }
    private val library by lazy { CarLibrary(app, scope, buttons, lastPlayed) }

    override fun onCreate() {
        super.onCreate()
        sync()
        // A book or a queue starting or ending moves the audio session to that player.
        scope.launch {
            combine(app.music.state, app.audiobooks.state) { m, b -> (m.current != null) to (b.book != null) }
                .distinctUntilChanged().collect { sync() }
        }
        // What's playing is remembered, for a car to pick up where listening left off.
        scope.launch {
            app.music.state.collect { m -> if (m.current != null) lastPlayed.noteSongs(m.queue.map { it.id }, m.index, (m.position * 1000).toLong()) }
        }
        scope.launch {
            app.audiobooks.state.map { it.book?.id }.distinctUntilChanged().collect { id -> id?.let(lastPlayed::noteBook) }
        }
        // Now Playing's buttons follow the song (its heart), shuffle, repeat, and a book's speed and chapters.
        scope.launch {
            combine(app.music.state, app.audiobooks.state, buttons.favoritesChanged) { m, b, f ->
                listOf(m.current?.id, m.shuffled, m.repeat, b.book?.id, b.rate, f)
            }.distinctUntilChanged().collect { audio?.setMediaButtonPreferences(buttons.current()) }
        }
    }

    private val open by lazy {
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The player the audio session shows: the book's when one is loaded, else the music player. */
    private fun audioPlayer(): Player {
        app.audiobooks.sessionPlayer?.takeIf { app.audiobooks.state.value.book != null }?.let { return it }
        app.music.sessionPlayer()?.let { return it }
        return idle ?: ExoPlayer.Builder(this).build().also { idle = it }
    }

    /** The player as the session shows it: covers a car can open, and a car connecting picks up what played last. */
    private fun shown(player: Player, isBook: Boolean) = CarArtPlayer(this, player, isBook) { library.resume(play = false) }

    private fun sync() {
        val player = audioPlayer()
        val isBook = player === app.audiobooks.sessionPlayer
        val session = audio
        if (session == null) {
            audio = MediaLibrarySession.Builder(this, shown(player, isBook), library).setId("audio").setSessionActivity(open).build()
                // Started rather than only bound to, so the session is registered here: that is what posts its
                // notification and puts the service in the foreground once it plays.
                .also { addSession(it) }
        } else if ((session.player as? CarArtPlayer)?.wrappedPlayer !== player) {
            session.player = shown(player, isBook)
        }
        if (player !== idle && idle != null) { idle?.release(); idle = null }

        val videoPlayer = app.videoPlayer
        if (video?.player !== videoPlayer) {
            video?.let { removeSession(it); it.release() }
            video = videoPlayer?.let { MediaSession.Builder(this, it).setId("video").setSessionActivity(open).build().also { s -> addSession(s) } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sync()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = audio

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away leaves something playing going; otherwise the service ends.
        val sessions = listOfNotNull(audio, video)
        if (sessions.none { it.player.playWhenReady && it.player.mediaItemCount > 0 }) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        listOfNotNull(audio, video).forEach { it.release() }
        audio = null
        video = null
        idle?.release()
        idle = null
        super.onDestroy()
    }
}
