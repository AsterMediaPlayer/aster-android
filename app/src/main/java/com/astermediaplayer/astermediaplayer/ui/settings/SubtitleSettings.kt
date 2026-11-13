package com.astermediaplayer.astermediaplayer.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.astermediaplayer.astermediaplayer.data.AppSettings
import com.astermediaplayer.astermediaplayer.data.SettingsStore
import kotlinx.coroutines.launch

/** ISO 639-2 codes as Jellyfin stores them, with an "Any" entry: the Apple apps' list. */
private val Languages = listOf(
    "" to "Any", "eng" to "English", "spa" to "Spanish", "fre" to "French", "ger" to "German",
    "ita" to "Italian", "por" to "Portuguese", "jpn" to "Japanese", "kor" to "Korean", "chi" to "Chinese",
    "rus" to "Russian", "ara" to "Arabic", "hin" to "Hindi", "dut" to "Dutch", "swe" to "Swedish",
    "nor" to "Norwegian", "dan" to "Danish", "fin" to "Finnish", "pol" to "Polish", "tur" to "Turkish",
    "heb" to "Hebrew", "tha" to "Thai", "vie" to "Vietnamese", "gre" to "Greek", "cze" to "Czech", "hun" to "Hungarian",
)

/** Jellyfin's subtitle modes, as the server names them and as the Apple apps title them. */
private val SubtitleModes = listOf("Default" to "Default", "Always" to "Always on", "OnlyForced" to "Only forced", "None" to "Off", "Smart" to "Smart")

@Composable
internal fun SubtitlesPage(settings: AppSettings, store: SettingsStore, scope: kotlinx.coroutines.CoroutineScope, repo: com.astermediaplayer.astermediaplayer.data.AsterRepository) {
    // The languages and subtitle mode are the user's own settings on the server, which picks the tracks a title starts with,
    // so the web app and every device make the same choices. Plex has none.
    var config by remember { mutableStateOf<org.json.JSONObject?>(null) }
    LaunchedEffect(repo.currentServerId) { config = runCatching { repo.userConfiguration() }.getOrNull() }
    config?.let { current ->
        fun value(key: String) = if (current.isNull(key)) "" else current.optString(key)
        // Shown at once; put back if the server refuses it.
        fun change(key: String, value: String) {
            val changed = org.json.JSONObject(current.toString()).put(key, value)
            config = changed
            scope.launch { runCatching { repo.updateUserConfiguration(changed) }.onFailure { config = current } }
        }
        // The list, plus the current value when the server has a code that isn't in it, so the choice never shows nothing.
        fun languages(code: String) = if (code.isEmpty() || Languages.any { it.first == code }) Languages else Languages + (code to code.uppercase())
        SGroup("Audio")
        val audio = value("AudioLanguagePreference")
        val audioChoices = languages(audio)
        SChoice("Preferred audio language", audioChoices.map { it.second }, audioChoices.indexOfFirst { it.first == audio }) { change("AudioLanguagePreference", audioChoices[it].first) }
        SGroup("Subtitles")
        val subtitles = value("SubtitleLanguagePreference")
        val subtitleChoices = languages(subtitles)
        SChoice("Preferred subtitle language", subtitleChoices.map { it.second }, subtitleChoices.indexOfFirst { it.first == subtitles }) { change("SubtitleLanguagePreference", subtitleChoices[it].first) }
        val mode = value("SubtitleMode")
        SChoice("Subtitles", SubtitleModes.map { it.second }, SubtitleModes.indexOfFirst { it.first == mode }.coerceAtLeast(0)) { change("SubtitleMode", SubtitleModes[it].first) }
        SNote("Synced with your ${repo.serverKind.displayName} profile, so the web app and this device make the same choices. Choosing subtitles while a title plays overrides them for that title.")
    }
    SGroup("Styled Subtitles")
    SSwitch("Draw ASS and SSA subtitles as styled", settings.styledSubtitles) { scope.launch { store.setStyledSubtitles(it) } }
    SNote("Draws anime subtitles as the release intended: signs where they belong on screen, their drawings, and the fonts inside the file. Turn off for plain text at the bottom, in your caption style.")
}
