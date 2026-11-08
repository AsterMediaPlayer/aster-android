package com.astermediaplayer.astermediaplayer.ui.player

import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.AudiobookRates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The buttons beside play and pause on the car's Now Playing (and the notification), as the Apple apps' CarPlay has
 * them. A song: favorite, shuffle and repeat. A book: back 15 and ahead 30 seconds where skip back and ahead go, then
 * speed (each press the next speed) and the chapter before and after.
 */
@UnstableApi
class CarButtons(private val app: AsterApp, private val scope: CoroutineScope) {
    /** Songs favorited or unfavorited here, ahead of the server saying so. */
    private val favorites = mutableMapOf<UUID, Boolean>()
    /** Changes whenever [favorites] does, so the buttons are drawn again. */
    val favoritesChanged = MutableStateFlow(0)

    val commands: SessionCommands = SessionCommands.Builder().apply { ALL.forEach { add(SessionCommand(it, Bundle.EMPTY)) } }.build()

    /** The buttons for what's playing now. */
    fun current(): List<CommandButton> {
        if (app.audiobooks.state.value.book != null) return bookButtons()
        if (app.music.state.value.current != null) return musicButtons()
        return emptyList()
    }

    private fun musicButtons(): List<CommandButton> {
        val state = app.music.state.value
        val song = state.current ?: return emptyList()
        val favorite = favorites[song.id] ?: (song.userData?.isFavorite == true)
        return listOf(
            button(FAVORITE, if (favorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED, if (favorite) "Unfavorite" else "Favorite"),
            button(SHUFFLE, if (state.shuffled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF, if (state.shuffled) "Shuffle Off" else "Shuffle"),
            when (state.repeat) {
                RepeatKind.Off -> button(REPEAT, CommandButton.ICON_REPEAT_OFF, "Repeat")
                RepeatKind.All -> button(REPEAT, CommandButton.ICON_REPEAT_ALL, "Repeat One")
                RepeatKind.One -> button(REPEAT, CommandButton.ICON_REPEAT_ONE, "Repeat Off")
            },
        )
    }

    private fun bookButtons(): List<CommandButton> {
        val state = app.audiobooks.state.value
        val book = state.book ?: return emptyList()
        val buttons = mutableListOf(
            button(BACK, CommandButton.ICON_SKIP_BACK_15, "Back 15 Seconds", CommandButton.SLOT_BACK),
            button(AHEAD, CommandButton.ICON_SKIP_FORWARD_30, "Ahead 30 Seconds", CommandButton.SLOT_FORWARD),
            button(SPEED, speedIcon(state.rate), "Speed ${AudiobookRates.label(state.rate)}"),
        )
        if (book.chapters.size > 1) {
            buttons += button(PREVIOUS_CHAPTER, CommandButton.ICON_PREVIOUS, "Previous Chapter")
            buttons += button(NEXT_CHAPTER, CommandButton.ICON_NEXT, "Next Chapter")
        }
        return buttons
    }

    /** Does what a button asks. False when it isn't one of these. */
    fun handle(action: String): Boolean {
        when (action) {
            FAVORITE -> toggleFavorite()
            SHUFFLE -> app.music.toggleShuffle()
            REPEAT -> app.music.cycleRepeat()
            BACK -> app.audiobooks.skip(-AudiobookPlayer.SKIP_BACK_SECONDS.toInt())
            AHEAD -> app.audiobooks.skip(AudiobookPlayer.SKIP_FORWARD_SECONDS.toInt())
            SPEED -> {
                val choices = AudiobookRates.choices
                val now = app.audiobooks.state.value.rate
                app.audiobooks.setRate(choices.firstOrNull { it > now + 0.01f } ?: choices.first())
            }
            PREVIOUS_CHAPTER -> app.audiobooks.previousChapter()
            NEXT_CHAPTER -> app.audiobooks.nextChapter()
            else -> return false
        }
        return true
    }

    /** The heart changes at once, and back if the server says no. */
    private fun toggleFavorite() {
        val song = app.music.state.value.current ?: return
        val id = song.id
        val now = !(favorites[id] ?: (song.userData?.isFavorite == true))
        set(id, now)
        scope.launch {
            runCatching { app.repository.setFavorite(id, now) }.onFailure {
                android.util.Log.w("Aster", "[Car] Couldn't favorite $id", it)
                set(id, !now)
            }
        }
    }

    private fun set(id: UUID, favorite: Boolean) {
        favorites[id] = favorite
        favoritesChanged.value++
    }

    private fun button(action: String, icon: Int, name: String, slot: Int = CommandButton.SLOT_OVERFLOW) =
        CommandButton.Builder(icon).setSessionCommand(SessionCommand(action, Bundle.EMPTY)).setDisplayName(name).setSlots(slot).build()

    /** Media3's speed pictures cover a few speeds; the rest get the plain one. */
    private fun speedIcon(rate: Float): Int = when (rate) {
        0.5f -> CommandButton.ICON_PLAYBACK_SPEED_0_5
        1f -> CommandButton.ICON_PLAYBACK_SPEED_1_0
        1.5f -> CommandButton.ICON_PLAYBACK_SPEED_1_5
        2f -> CommandButton.ICON_PLAYBACK_SPEED_2_0
        else -> CommandButton.ICON_PLAYBACK_SPEED
    }

    companion object {
        private const val FAVORITE = "aster.favorite"
        private const val SHUFFLE = "aster.shuffle"
        private const val REPEAT = "aster.repeat"
        private const val BACK = "aster.back15"
        private const val AHEAD = "aster.ahead30"
        private const val SPEED = "aster.speed"
        private const val PREVIOUS_CHAPTER = "aster.previousChapter"
        private const val NEXT_CHAPTER = "aster.nextChapter"
        private val ALL = listOf(FAVORITE, SHUFFLE, REPEAT, BACK, AHEAD, SPEED, PREVIOUS_CHAPTER, NEXT_CHAPTER)
    }
}
