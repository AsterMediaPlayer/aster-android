package com.astermediaplayer.astermediaplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
    /** Downloads (Settings > Downloads), as on the Apple apps. */
    val downloadsEnabled: Boolean = true,
    /** Whether the first-download setup has been through. */
    val downloadsSetUp: Boolean = false,
    /** The most downloads may take, in gigabytes; 0 for no limit. */
    val downloadLimitGb: Int = 0,
    val downloadQuality: com.astermediaplayer.astermediaplayer.data.downloads.DownloadQuality = com.astermediaplayer.astermediaplayer.data.downloads.DownloadQuality.High,
    /** HEVC makes smaller files but takes a server longer to prepare without hardware encoding. */
    val downloadHevc: Boolean = false,
    val downloadSubtitles: Boolean = true,
    val downloadCellular: Boolean = false,
    /** Titles with a disc menu start at it when Play is pressed, like putting in the disc. */
    val preferDiscMenu: Boolean = false,
    /** Menu music and button sounds. */
    val discMenuSounds: Boolean = true,
    /** Minutes of an audiobook saved ahead of the listener on a phone, so a spot with no signal doesn't stop it; 0 for off. */
    val listenAheadMinutes: Int = 30,
    /** The song card over an opening or ending, and at the start and end of a music video. */
    val themeCards: Boolean = true,
    /** Name an opening or ending's song from AnimeThemes when the server can't; off, Aster doesn't contact it. Kept on the device. */
    val songLookups: Boolean = true,
    /** When Aster asks who's watching as it opens, for a household (Settings › Account › Ask on Opening). Kept on the device. */
    val profileGate: com.astermediaplayer.astermediaplayer.data.profiles.ProfileGateMode = com.astermediaplayer.astermediaplayer.data.profiles.ProfileGateMode.Server,
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
    private val downloadsEnabledKey = booleanPreferencesKey("downloads_enabled")
    private val downloadsSetUpKey = booleanPreferencesKey("downloads_set_up")
    private val downloadLimitKey = intPreferencesKey("download_limit_gb")
    private val downloadQualityKey = stringPreferencesKey("download_quality")
    private val downloadHevcKey = booleanPreferencesKey("download_hevc")
    private val downloadSubtitlesKey = booleanPreferencesKey("download_subtitles")
    private val downloadCellularKey = booleanPreferencesKey("download_cellular")
    private val preferDiscMenuKey = booleanPreferencesKey("prefer_disc_menu")
    private val discMenuSoundsKey = booleanPreferencesKey("disc_menu_sounds")
    private val listenAheadKey = intPreferencesKey("listen_ahead_minutes")
    private val themeCardsKey = booleanPreferencesKey("theme_cards")
    private val songLookupsKey = booleanPreferencesKey("song_lookups")
    private val welcomeSeenKey = stringSetPreferencesKey("welcome_seen")
    private val profileGateKey = stringPreferencesKey("profile_gate")
    /** Each household's "ask on startup" as last read (see [askOnStartup]), as a JSON object by household key. */
    private val askOnStartupKey = stringPreferencesKey("profile_ask_on_startup")

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
            downloadsEnabled = it[downloadsEnabledKey] ?: true,
            downloadsSetUp = it[downloadsSetUpKey] ?: false,
            downloadLimitGb = it[downloadLimitKey] ?: 0,
            downloadQuality = com.astermediaplayer.astermediaplayer.data.downloads.DownloadQuality.entries.firstOrNull { q -> q.name == it[downloadQualityKey] }
                ?: com.astermediaplayer.astermediaplayer.data.downloads.DownloadQuality.High,
            downloadHevc = it[downloadHevcKey] ?: false,
            downloadSubtitles = it[downloadSubtitlesKey] ?: true,
            downloadCellular = it[downloadCellularKey] ?: false,
            preferDiscMenu = it[preferDiscMenuKey] ?: false,
            discMenuSounds = it[discMenuSoundsKey] ?: true,
            listenAheadMinutes = it[listenAheadKey] ?: 30,
            themeCards = it[themeCardsKey] ?: true,
            songLookups = it[songLookupsKey] ?: true,
            profileGate = com.astermediaplayer.astermediaplayer.data.profiles.ProfileGateMode.of(it[profileGateKey]),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    /**
     * The settings that follow the user from device to device, by their name on the server (the Apple apps' names, so the
     * two kinds of app share them): matters of taste. Quality, downloads and the like stay on the device.
     */
    private val synced: List<Pair<String, androidx.datastore.preferences.core.Preferences.Key<Boolean>>> = listOf(
        "autoplayNext" to autoPlayNextKey,
        "autoSkipIntro" to autoSkipKey,
        "styledSubtitles" to styledSubtitlesKey,
        "preferDiscMenu" to preferDiscMenuKey,
        "discMenuSounds" to discMenuSoundsKey,
        "themeMusic" to themeMusicKey,
        "themeVideos" to themeVideosKey,
        "openingSound" to openingSoundKey,
        "themeCards" to themeCardsKey,
    )

    /** The synced settings that have been set on this device, as the server stores them ("true" or "false"); one never set isn't here. */
    val syncedValues: Flow<Map<String, String>> = context.settingsDataStore.data.map { prefs ->
        buildMap { for ((name, key) in synced) prefs[key]?.let { put(name, it.toString()) } }
    }

    /** Takes the server's values for the synced settings, leaving the rest alone. */
    suspend fun applySynced(values: Map<String, String>) {
        context.settingsDataStore.edit { prefs ->
            for ((name, key) in synced) values[name]?.toBooleanStrictOrNull()?.let { prefs[key] = it }
        }
    }

    /** The saved servers ([SavedServer.id]) whose welcome screen has been seen. */
    val welcomeSeen: Flow<Set<String>> = context.settingsDataStore.data.map { it[welcomeSeenKey] ?: emptySet() }
    suspend fun markWelcomeSeen(serverId: String) = context.settingsDataStore.edit { it[welcomeSeenKey] = (it[welcomeSeenKey] ?: emptySet()) + serverId }
    suspend fun showWelcomeAgain(serverId: String) = context.settingsDataStore.edit { it[welcomeSeenKey] = (it[welcomeSeenKey] ?: emptySet()) - serverId }

    suspend fun setProfileGate(mode: com.astermediaplayer.astermediaplayer.data.profiles.ProfileGateMode) = context.settingsDataStore.edit { it[profileGateKey] = mode.raw }

    /** A household's "ask on startup" as the server last said, so the decision on a cold launch needs no network. */
    suspend fun askOnStartup(householdKey: String): Boolean? =
        context.settingsDataStore.data.first()[askOnStartupKey]?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            ?.takeIf { it.has(householdKey) }?.optBoolean(householdKey)

    suspend fun setAskOnStartup(householdKey: String, ask: Boolean) = context.settingsDataStore.edit {
        val all = it[askOnStartupKey]?.let { text -> runCatching { org.json.JSONObject(text) }.getOrNull() } ?: org.json.JSONObject()
        it[askOnStartupKey] = all.put(householdKey, ask).toString()
    }

    suspend fun setQualityIndex(index: Int) = context.settingsDataStore.edit { it[qualityKey] = index }
    suspend fun setAutoSkipSegments(on: Boolean) = context.settingsDataStore.edit { it[autoSkipKey] = on }
    suspend fun setAutoPlayNext(on: Boolean) = context.settingsDataStore.edit { it[autoPlayNextKey] = on }
    suspend fun setStyledSubtitles(on: Boolean) = context.settingsDataStore.edit { it[styledSubtitlesKey] = on }
    suspend fun setThemeCut(cut: ThemeCut) = context.settingsDataStore.edit { it[themeCutKey] = cut.ordinal }
    suspend fun setThemeMusic(on: Boolean) = context.settingsDataStore.edit { it[themeMusicKey] = on }
    suspend fun setThemeVideos(on: Boolean) = context.settingsDataStore.edit { it[themeVideosKey] = on }
    suspend fun setSongLookups(on: Boolean) = context.settingsDataStore.edit { it[songLookupsKey] = on }
    suspend fun setThemeCards(on: Boolean) = context.settingsDataStore.edit { it[themeCardsKey] = on }
    suspend fun setOpeningSound(on: Boolean) = context.settingsDataStore.edit { it[openingSoundKey] = on }
    suspend fun setDownloadsEnabled(on: Boolean) = context.settingsDataStore.edit { it[downloadsEnabledKey] = on }
    suspend fun setDownloadsSetUp(on: Boolean) = context.settingsDataStore.edit { it[downloadsSetUpKey] = on }
    suspend fun setDownloadLimitGb(gb: Int) = context.settingsDataStore.edit { it[downloadLimitKey] = gb }
    suspend fun setDownloadQuality(q: com.astermediaplayer.astermediaplayer.data.downloads.DownloadQuality) = context.settingsDataStore.edit { it[downloadQualityKey] = q.name }
    suspend fun setDownloadHevc(on: Boolean) = context.settingsDataStore.edit { it[downloadHevcKey] = on }
    suspend fun setDownloadSubtitles(on: Boolean) = context.settingsDataStore.edit { it[downloadSubtitlesKey] = on }
    suspend fun setDownloadCellular(on: Boolean) = context.settingsDataStore.edit { it[downloadCellularKey] = on }
    suspend fun setPreferDiscMenu(on: Boolean) = context.settingsDataStore.edit { it[preferDiscMenuKey] = on }
    suspend fun setDiscMenuSounds(on: Boolean) = context.settingsDataStore.edit { it[discMenuSoundsKey] = on }
    suspend fun setListenAheadMinutes(minutes: Int) = context.settingsDataStore.edit { it[listenAheadKey] = minutes }
}
