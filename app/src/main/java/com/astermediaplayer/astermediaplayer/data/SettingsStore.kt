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
)

private val Context.settingsDataStore by preferencesDataStore("aster_settings")

class SettingsStore(private val context: Context) {
    private val qualityKey = intPreferencesKey("quality")
    private val autoSkipKey = booleanPreferencesKey("auto_skip")
    private val autoPlayNextKey = booleanPreferencesKey("auto_play_next")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map {
        AppSettings(
            qualityIndex = it[qualityKey] ?: 0,
            autoSkipSegments = it[autoSkipKey] ?: false,
            autoPlayNext = it[autoPlayNextKey] ?: true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setQualityIndex(index: Int) = context.settingsDataStore.edit { it[qualityKey] = index }
    suspend fun setAutoSkipSegments(on: Boolean) = context.settingsDataStore.edit { it[autoSkipKey] = on }
    suspend fun setAutoPlayNext(on: Boolean) = context.settingsDataStore.edit { it[autoPlayNextKey] = on }
}
