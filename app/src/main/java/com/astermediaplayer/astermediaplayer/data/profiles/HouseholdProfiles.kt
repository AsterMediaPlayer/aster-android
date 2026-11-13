package com.astermediaplayer.astermediaplayer.data.profiles

import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.SettingsStore
import com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * The household of the server in use (the Apple apps' `HouseholdProfiles.swift`): whether it has profiles to switch between
 * (Bonfire on Jellyfin, a Plex Home), who they are, and whether it asks who's watching on opening. Asked once the main screen is
 * reached and again after a while, like the tracking plugin; cleared on switching server. Also whether the picker is showing.
 */
class HouseholdProfiles(private val repo: AsterRepository, private val settings: SettingsStore) {
    data class State(
        /** Null while there's no service (or not yet known). */
        val provider: ProfileProvider? = null,
        val profiles: List<HouseholdProfile> = emptyList(),
        /** The household's "ask on startup", as the server last said. */
        val askOnStartup: Boolean? = null,
        /** Whether the server has been asked yet, for this sign-in. */
        val checked: Boolean = false,
        val lastError: String? = null,
    ) {
        /** Something to open the picker for: someone else to switch to, or room to add someone. */
        val isAvailable: Boolean get() = provider != null && (profiles.size > 1 || canCreateProfiles)
        val current: HouseholdProfile? get() = profiles.firstOrNull { it.isCurrent }
        /** This household's main account. */
        val master: HouseholdProfile? get() = profiles.firstOrNull { it.isMaster && !it.isOtherHousehold }

        /** The main account, signed in, on a service that makes profiles. */
        val canCreateProfiles: Boolean get() = provider?.creation != null && current?.let { it.isMaster && !it.isOtherHousehold } == true

        /** Whether [profile] can be changed from here: this household's own, by its main account. */
        fun canEdit(profile: HouseholdProfile): Boolean = canCreateProfiles && !profile.isOtherHousehold
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The "Who's watching?" screen showing, and why; null when it isn't. */
    private val _picker = MutableStateFlow<ProfilePickerReason?>(null)
    val picker: StateFlow<ProfilePickerReason?> = _picker.asStateFlow()

    private var checkedAt = 0L
    /** The server and user the list is for. */
    private var owner: String? = null

    /** Asks who's watching, over the main screen. */
    fun presentPicker() { if (_state.value.isAvailable) _picker.value = ProfilePickerReason.Switcher }

    fun dismissPicker() { _picker.value = null }

    /**
     * Who's watching, before anything loads, on opening signed in: decided from what's kept here (the sign-in, the setting, the
     * household's preference as last read), so opening never waits on the server.
     */
    suspend fun decideAtLaunch() {
        val s = repo.session ?: return
        val cached = s.householdKey?.let { settings.askOnStartup(it) }
        if (ProfileGate.shouldAsk(s, settings.current().profileGate, cached)) _picker.value = ProfilePickerReason.Launch
    }

    /** Asks the service what the household is. A server without one, or Emby, clears it. */
    suspend fun refresh(force: Boolean = false) {
        val s = repo.session
        if (s == null) { clear(); _state.value = State(checked = true); return }
        val owner = "${s.serverUrl}|${s.userId}"
        if (owner != this.owner) { clear(); this.owner = owner }
        if (!force && _state.value.checked && System.currentTimeMillis() - checkedAt < 30 * 60_000L) return
        val provider = _state.value.provider ?: repo.profileProvider()
        if (provider == null) { _state.value = State(checked = true); return }
        try {
            val list = provider.profiles(s)
            // Switched meanwhile: that answer isn't for this sign-in.
            if (this.owner != owner) return
            _state.value = _state.value.copy(provider = provider, profiles = list, lastError = null)
            repo.keepSnapshot(CACHE_NAME, JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString())
            DebugLog.note("profiles", "${provider.kind.serviceName} present: ${list.size} profiles, ${list.count { it.requiresPin }} with a PIN")
            val current = list.firstOrNull { it.isCurrent }
            if (current != null) {
                repo.noteHousehold(current.storedHousehold)
                val ask = runCatching { provider.askOnStartup() }.getOrNull()
                if (ask != null && this.owner == owner) {
                    _state.value = _state.value.copy(askOnStartup = ask)
                    settings.setAskOnStartup("${s.serverUrl}|${current.storedHousehold.masterUserId}", ask)
                    DebugLog.note("profiles", "Ask on startup: $ask")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProfileSwitchException.Unavailable) {
            if (this.owner != owner) return
            _state.value = State()
            DebugLog.note("profiles", "No profiles on this server")
        } catch (e: Exception) {
            if (this.owner != owner) return
            // Out of reach for a moment: what was known stays.
            _state.value = _state.value.copy(lastError = e.userMessage())
            DebugLog.note("profiles", "Profiles couldn't be read: ${e.userMessage()}")
        }
        _state.value = _state.value.copy(checked = true)
        checkedAt = System.currentTimeMillis()
    }

    /** The list as last read for this sign-in, at once, while the server is asked again. Nothing if it's known already. */
    suspend fun restoreCached() {
        if (_state.value.profiles.isNotEmpty()) return
        val s = repo.session ?: return
        val provider = repo.profileProvider() ?: return
        val text = repo.snapshot(CACHE_NAME) ?: return
        val kept = runCatching { JSONArray(text).let { a -> List(a.length()) { HouseholdProfile.fromJson(a.getJSONObject(it)) } } }.getOrNull() ?: return
        // Not if the fresh answer came first, or the sign-in changed.
        if (_state.value.profiles.isNotEmpty() || repo.session?.userId != s.userId) return
        if (owner == null) owner = "${s.serverUrl}|${s.userId}"
        _state.value = _state.value.copy(provider = provider, profiles = kept)
    }

    // MARK: Making and changing a profile

    private fun creation(): ProfileCreation = _state.value.provider?.creation ?: throw ProfileSwitchException.Unavailable()

    /** What the form offers, with the household's own rules: the main account's PIN is asked when it has one, and how many more it may make. */
    suspend fun creationOptions(): ProfileCreationOptions {
        val creation = _state.value.provider?.creation ?: return ProfileCreationOptions()
        val options = creation.creationChoices()
        val now = _state.value
        val master = now.master
        val remaining = master?.maxProfiles?.let { limit -> (limit - now.profiles.count { !it.isMaster && !it.isOtherHousehold }).coerceAtLeast(0) }
        return options.copy(asksMasterPin = master?.hasPin ?: false, remaining = remaining)
    }

    /** Makes the profile and reads the household again, so it's there to pick: its plain id. */
    suspend fun create(draft: ProfileDraft): String = creation().createProfile(draft).also { refresh(force = true) }

    /** A profile as it is now, filled in to change. */
    suspend fun draft(editing: HouseholdProfile): ProfileDraft = _state.value.provider?.creation?.draft(editing) ?: ProfileDraft()

    suspend fun update(draft: ProfileDraft) { creation().updateProfile(draft); refresh(force = true) }

    /** Removes the profile on the server, and its saved sign-in here (the server ended it), then reads the household again. */
    suspend fun delete(profile: HouseholdProfile, masterPin: String?) {
        creation().deleteProfile(profile, masterPin)
        repo.reusableSession(profile)?.let { repo.removeServer(com.astermediaplayer.astermediaplayer.data.SavedServer.id(it.serverUrl, it.userId)) }
        refresh(force = true)
    }

    /** Signed out, or on another server: nothing known, and no picker. */
    fun clear() {
        _state.value = State()
        checkedAt = 0L
        owner = null
    }

    companion object { private const val CACHE_NAME = "household-profiles" }
}
