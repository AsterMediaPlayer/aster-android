package com.astermediaplayer.astermediaplayer.data.profiles

import com.astermediaplayer.astermediaplayer.data.Session
import org.json.JSONObject

/**
 * A household's profiles, wherever they come from (the Apple apps' `ProfileProvider.swift`): Bonfire on a Jellyfin server, or a
 * Plex Home. The picker shows them the same way; each provider knows how to list its own and how to switch to one.
 */

/** One person in the household, as the picker shows them. */
data class HouseholdProfile(
    /** The id as Jellyfin writes it either way (lowercase, no dashes), or a Plex Home user's uuid: what the session is compared to. */
    val id: String,
    /** The id as the server sent it, for its own routes. */
    val rawId: String,
    val name: String,
    /** The profile's picture, when it has one; the initial shows meanwhile. */
    val imageUrl: String?,
    val initial: String,
    /** `#RRGGBB`. */
    val colorHex: String,
    /** The one signed in right now. */
    val isCurrent: Boolean,
    /** The household's main account. */
    val isMaster: Boolean,
    /** A PIN is asked for before switching, right now (a profile can skip it on the home network). */
    val requiresPin: Boolean,
    /** A PIN is set at all. */
    val hasPin: Boolean,
    /** A member of a linked household, not this one. */
    val isOtherHousehold: Boolean,
    val masterUserId: String?,
    /** What the sign-in remembers about the household once switched to. */
    val storedHousehold: StoredHousehold,
    /** On the main account: how many profiles it may have. */
    val maxProfiles: Int? = null,
    /** On a profile of this household: every library the account has (now and later), or the ones it was given (plain ids). */
    val allLibraries: Boolean? = null,
    val enabledFolders: List<String>? = null,
    /** Its PIN isn't asked on the home network. */
    val bypassPinOnLocalNetwork: Boolean? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("rawId", rawId).put("name", name).put("initial", initial).put("color", colorHex)
        .put("current", isCurrent).put("master", isMaster).put("requiresPin", requiresPin).put("hasPin", hasPin).put("other", isOtherHousehold)
        .put("household", storedHousehold.toJson())
        .apply {
            imageUrl?.let { put("image", it) }
            masterUserId?.let { put("masterUserId", it) }
            maxProfiles?.let { put("maxProfiles", it) }
            allLibraries?.let { put("allLibraries", it) }
            enabledFolders?.let { put("enabledFolders", org.json.JSONArray(it)) }
            bypassPinOnLocalNetwork?.let { put("bypassPin", it) }
        }

    companion object {
        /** An id as Jellyfin writes it either way: lowercase, without dashes. */
        fun plainId(id: String): String = id.lowercase().replace("-", "")

        /** A Jellyfin id in the dashed form the app keeps sessions under (the SDK's UUID form); left alone if it isn't one. */
        fun dashedId(id: String): String {
            val p = plainId(id)
            if (p.length != 32 || !p.all { it in '0'..'9' || it in 'a'..'f' }) return id
            return "${p.substring(0, 8)}-${p.substring(8, 12)}-${p.substring(12, 16)}-${p.substring(16, 20)}-${p.substring(20)}"
        }

        fun fromJson(o: JSONObject) = HouseholdProfile(
            o.getString("id"), o.getString("rawId"), o.getString("name"), o.optString("image").ifEmpty { null }, o.optString("initial"),
            o.optString("color").ifEmpty { "#00A4DC" }, o.optBoolean("current"), o.optBoolean("master"), o.optBoolean("requiresPin"),
            o.optBoolean("hasPin"), o.optBoolean("other"), o.optString("masterUserId").ifEmpty { null },
            StoredHousehold.fromJson(o.getJSONObject("household")),
            maxProfiles = if (o.has("maxProfiles")) o.getInt("maxProfiles") else null,
            allLibraries = if (o.has("allLibraries")) o.getBoolean("allLibraries") else null,
            enabledFolders = o.optJSONArray("enabledFolders")?.let { a -> List(a.length()) { a.getString(it) } },
            bypassPinOnLocalNetwork = if (o.has("bypassPin")) o.getBoolean("bypassPin") else null,
        )
    }
}

/**
 * The household a sign-in is part of: a Bonfire main account and its profiles (or a Plex Home). Enough to find the household's
 * other saved sign-ins, decide whether to ask who's watching on opening, and draw the profile's tile before the server answers.
 */
data class StoredHousehold(
    /** The main account's id, as Jellyfin writes it: lowercase, no dashes (a Plex Home: the server's machine id). */
    val masterUserId: String,
    /** Whether this sign-in is the main account itself. */
    val isMaster: Boolean,
    /** The tile without a picture: a letter on a colour. */
    val avatarInitial: String? = null,
    val avatarColor: String? = null,
    /** Bonfire's own picture value (an avatar's id, a URL, or a stored file's name); null without one. */
    val profileImage: String? = null,
    /** Whether the profile has a PIN, which locking asks for; read again whenever the household is. */
    val hasPin: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("masterUserId", masterUserId).put("isMaster", isMaster).apply {
        if (hasPin) put("hasPin", true)
        avatarInitial?.let { put("avatarInitial", it) }
        avatarColor?.let { put("avatarColor", it) }
        profileImage?.let { put("profileImage", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = StoredHousehold(
            o.getString("masterUserId"), o.optBoolean("isMaster"),
            o.optString("avatarInitial").ifEmpty { null }, o.optString("avatarColor").ifEmpty { null }, o.optString("profileImage").ifEmpty { null },
            hasPin = o.optBoolean("hasPin"),
        )
    }
}

/** The server and the household's main account, which every profile of the household shares; null without a household. */
val Session.householdKey: String? get() = household?.let { "$serverUrl|${it.masterUserId}" }

enum class ProfileProviderKind(val serviceName: String) { Bonfire("Bonfire"), PlexHome("Plex Home") }

/** Lists a household's profiles and switches to one. */
interface ProfileProvider {
    val kind: ProfileProviderKind

    /** Whether the household asks who's watching when the app opens; null where the service has no such setting. */
    suspend fun askOnStartup(): Boolean?

    /** Every profile that can be switched to, the one signed in included. Throws [ProfileSwitchException.Unavailable] when the service isn't there. */
    suspend fun profiles(current: Session): List<HouseholdProfile>

    suspend fun verifyPin(pin: String, profile: HouseholdProfile)

    /** A fresh sign-in as [profile], ready to switch to. */
    suspend fun switchSession(profile: HouseholdProfile, pin: String?): Session

    /** Makes, changes and removes profiles, where the service can (Bonfire); null where it can't (a Plex Home). */
    val creation: ProfileCreation? get() = null
}

// MARK: Making and changing a profile

/** A picture a profile may have. */
data class ProfileAvatarChoice(val id: String, val name: String, val url: String)

/** A library a profile may be given; its plain id. */
data class ProfileLibraryChoice(val id: String, val name: String, val collectionType: String?)

/** A rating limit: everything up to this age. */
data class ProfileRatingChoice(val score: Int, val label: String)

/** What the form offers: the pictures, libraries and ratings the server has, and what the household's rules add. */
data class ProfileCreationOptions(
    val avatars: List<ProfileAvatarChoice> = emptyList(),
    val libraries: List<ProfileLibraryChoice> = emptyList(),
    val ratings: List<ProfileRatingChoice> = emptyList(),
    val ratingCountry: String? = null,
    /** The main account's own PIN is asked for before making or changing a profile (the account has one; the server insists on it). */
    val asksMasterPin: Boolean = false,
    /** How many more profiles the account may make; null when not known. */
    val remaining: Int? = null,
)

/** A profile as it's filled in: a new one, or (with [profileId]) one being changed. */
data class ProfileDraft(
    /** The profile being changed, as the server knows it; null for a new one. */
    val profileId: String? = null,
    val name: String = "",
    /** A new PIN; empty keeps the one there is (or sets none). */
    val pin: String = "",
    /** Changing: take the PIN away. */
    val removePin: Boolean = false,
    /** One of the offered pictures; null for the initial on the colour (a new profile) or the picture it has (changing). */
    val avatarId: String? = null,
    val colorHex: String = Bonfire.DEFAULT_AVATAR_COLOR,
    /** Everything up to this rating; null for no limit beyond the account's. */
    val ratingScore: Int? = null,
    /** The libraries it may see (plain ids); null for all the account has, now and later. */
    val libraryIds: Set<String>? = null,
    val masterPin: String = "",
    /** A PIN that isn't asked on the home network. */
    val bypassPinOnLocalNetwork: Boolean = false,
) {
    val isEditing: Boolean get() = profileId != null
    val trimmedName: String get() = name.trim()
    val initial: String get() = trimmedName.take(1).uppercase()

    /** What the server would turn down, said before it's asked (its rules: the name's length and letters, the PIN's digits); null when fine. */
    fun validationMessage(confirmPin: String): String? {
        val name = trimmedName
        if (name.isEmpty()) return "Enter a name for the profile."
        if (name.length > Bonfire.MAX_PROFILE_NAME_LENGTH) return "A profile name can be at most ${Bonfire.MAX_PROFILE_NAME_LENGTH} characters."
        if (name.any { !(it.isLetterOrDigit() || it in "-'._@+ ") }) return "A profile name can use letters, numbers, spaces and - ' . _ @ +."
        if (pin.isNotEmpty() && !removePin) {
            if (pin.length !in Bonfire.PIN_LENGTHS || !pin.all { it in '0'..'9' }) return "A PIN is 4 to 8 digits."
            if (confirmPin != pin) return "The PINs don't match."
        }
        return null
    }

    companion object {
        /** The colours offered for the initial's tile. */
        val COLORS = listOf("#00A4DC", "#1F77B4", "#30A46C", "#FFB224", "#F76B15", "#E5484D", "#E93D82", "#8E4EC6")
    }
}

/** Makes, changes and removes profiles in the household. */
interface ProfileCreation {
    /** The pictures, libraries and ratings to offer. Each list is empty when the server can't say. */
    suspend fun creationChoices(): ProfileCreationOptions
    /** Makes the profile: its plain id. */
    suspend fun createProfile(draft: ProfileDraft): String
    /** A profile as it is now, filled in to change. */
    suspend fun draft(editing: HouseholdProfile): ProfileDraft
    suspend fun updateProfile(draft: ProfileDraft)
    suspend fun deleteProfile(profile: HouseholdProfile, masterPin: String?)
}

sealed class ProfileSwitchException(message: String) : Exception(message) {
    class WrongPin : ProfileSwitchException("That PIN isn't right.")
    class RateLimited : ProfileSwitchException("Too many tries. Wait a few minutes, then try again.")
    class PinNeeded : ProfileSwitchException("This profile needs its PIN.")
    /** The service isn't on this server. */
    class Unavailable : ProfileSwitchException("This server has no profiles.")
    class Failed(message: String) : ProfileSwitchException(message)
}

/**
 * How to get to a profile: the current one, a sign-in already saved, a switch the server can do at once, or one that needs the PIN
 * first. A saved sign-in never stands in for a PIN.
 */
sealed interface ProfileSwitchPlan {
    data object AlreadyCurrent : ProfileSwitchPlan
    data class ReuseStored(val session: Session) : ProfileSwitchPlan
    data object SwitchNow : ProfileSwitchPlan
    data object NeedsPin : ProfileSwitchPlan

    companion object {
        fun make(profile: HouseholdProfile, stored: Session?): ProfileSwitchPlan = when {
            profile.isCurrent -> AlreadyCurrent
            profile.requiresPin -> NeedsPin
            stored != null -> ReuseStored(stored)
            else -> SwitchNow
        }
    }
}

/** When the app asks who's watching as it opens. */
enum class ProfileGateMode(val raw: String, val title: String) {
    /** Every time the app opens. */
    Always("always", "Every Time"),
    /** As the household's setting on the server says (Bonfire's "ask on startup"); never for a service without one. */
    Server("server", "As the Server Is Set"),
    Never("never", "Never");

    companion object { fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Server }
}

object ProfileGate {
    /**
     * Whether to ask who's watching on a cold launch, from what's known without asking the server: the saved sign-in, the setting
     * here, and the household's preference as last read.
     */
    fun shouldAsk(stored: Session, mode: ProfileGateMode, cachedAskOnStartup: Boolean?): Boolean {
        val inHousehold = stored.household != null || (stored.plex?.homeUserUuid != null && stored.plex.homeToken != null)
        return when (mode) {
            ProfileGateMode.Never -> false
            ProfileGateMode.Always -> inHousehold
            ProfileGateMode.Server -> stored.household != null && cachedAskOnStartup == true
        }
    }
}

/** Why the picker is up: the app opened, someone asked to switch, or someone at the lock screen is choosing another profile than the locked one. */
enum class ProfilePickerReason { Launch, Switcher, Locked }
