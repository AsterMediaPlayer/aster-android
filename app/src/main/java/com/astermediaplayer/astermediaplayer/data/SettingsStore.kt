package com.astermediaplayer.astermediaplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    /** Index into the player's quality list; 0 is Auto. */
    val qualityIndex: Int = 0,
    val autoSkipSegments: Boolean = false,
    val autoPlayNext: Boolean = true,
    /** ASS/SSA drawn by libass; off falls back to plain text. */
    val styledSubtitles: Boolean = true,
    /** Which version of an opening or ending plays, when there's a choice. */
    val themeCut: ThemeCut = ThemeCut.TvSize,
    /** Title pages: a title's theme song plays quietly, and its theme video loops muted behind the page. */
    val themeMusic: Boolean = false,
    val themeVideos: Boolean = false,
    /** Anime with no theme video loops its opening behind the series page; with sound it plays quietly, like theme music. */
    val openingSound: Boolean = false,
)

private val Context.settingsDataStore by preferencesDataStore("aster_settings")

class SettingsStore(private val context: Context) {
    private val qualityKey = intPreferencesKey("quality")
    private val autoSkipKey = booleanPreferencesKey("auto_skip")
    private val autoPlayNextKey = booleanPreferencesKey("auto_play_next")
    private val styledSubtitlesKey = booleanPreferencesKey("styled_subtitles")
    private val themeCutKey = intPreferencesKey("theme_cut")
    private val themeMusicKey = booleanPreferencesKey("theme_music")
    private val themeVideosKey = booleanPreferencesKey("theme_videos")
    private val openingSoundKey = booleanPreferencesKey("opening_sound")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map {
        AppSettings(
            qualityIndex = it[qualityKey] ?: 0,
            autoSkipSegments = it[autoSkipKey] ?: false,
            autoPlayNext = it[autoPlayNextKey] ?: true,
            styledSubtitles = it[styledSubtitlesKey] ?: true,
            themeCut = ThemeCut.entries.getOrElse(it[themeCutKey] ?: 0) { ThemeCut.TvSize },
            themeMusic = it[themeMusicKey] ?: false,
            themeVideos = it[themeVideosKey] ?: false,
            openingSound = it[openingSoundKey] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setQualityIndex(index: Int) = context.settingsDataStore.edit { it[qualityKey] = index }
    suspend fun setAutoSkipSegments(on: Boolean) = context.settingsDataStore.edit { it[autoSkipKey] = on }
    suspend fun setAutoPlayNext(on: Boolean) = context.settingsDataStore.edit { it[autoPlayNextKey] = on }
    suspend fun setStyledSubtitles(on: Boolean) = context.settingsDataStore.edit { it[styledSubtitlesKey] = on }
    suspend fun setThemeCut(cut: ThemeCut) = context.settingsDataStore.edit { it[themeCutKey] = cut.ordinal }
    suspend fun setThemeMusic(on: Boolean) = context.settingsDataStore.edit { it[themeMusicKey] = on }
    suspend fun setThemeVideos(on: Boolean) = context.settingsDataStore.edit { it[themeVideosKey] = on }
    suspend fun setOpeningSound(on: Boolean) = context.settingsDataStore.edit { it[openingSoundKey] = on }
}
