package com.astermediaplayer.astermediaplayer.data.profiles

import com.astermediaplayer.astermediaplayer.data.Session
import com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog
import com.astermediaplayer.astermediaplayer.data.emby.ServerKind
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Bonfire (JellyProfiles) plugin's routes, as on the Apple apps' `BonfireClient.swift`: a household's profiles, switching to one
 * (with its PIN), and the household's preferences. The plugin answers at /plugins/profiles on the server, with the ordinary sign-in
 * header; a server without it answers 404.
 */
object Bonfire {
    const val BASE_PATH = "/plugins/profiles"
    /** Bonfire's colour for a tile without one of its own. */
    const val DEFAULT_AVATAR_COLOR = "#00A4DC"
    /** From 1.9: a code naming what went wrong, beside the plain-text body. */
    const val ERROR_HEADER = "X-Bonfire-Error"
    /** A profile's name: up to this many of letters, numbers, spaces and - ' . _ @ + (the plugin's own rule). */
    const val MAX_PROFILE_NAME_LENGTH = 32
    /** A PIN: four to eight digits. */
    val PIN_LENGTHS = 4..8

    /**
     * The plugin writes its keys in camelCase in its own pages and PascalCase through Jellyfin's default serializer: the first letter
     * is lowered, so either reads.
     */
    fun normalized(o: JSONObject): JSONObject {
        val out = JSONObject()
        o.keys().forEach { key -> out.put(key.replaceFirstChar { it.lowercaseChar() }, o.get(key)) }
        return out
    }

    fun parseList(json: String): List<BonfireProfileRecord> {
        val array = JSONArray(json)
        return List(array.length()) { array.getJSONObject(it) }.mapNotNull { BonfireProfileRecord.parse(normalized(it)) }
    }

    /** The household's "ask on startup", read from its preferences. */
    fun parseAskOnStartup(json: String): Boolean? = normalized(JSONObject(json)).bool("askOnStartup")

    /** The libraries the signed-in account can see, by plain id. */
    fun parseLibraries(json: String): List<ProfileLibraryChoice> {
        val a = JSONArray(json)
        return List(a.length()) { normalized(a.getJSONObject(it)) }.mapNotNull { o ->
            ProfileLibraryChoice(HouseholdProfile.plainId(o.str("id") ?: return@mapNotNull null), o.str("name") ?: "Library", o.str("collectionType"))
        }
    }

    /** The rating limits the server offers, lowest first, each with its labels joined ("12 / 12A"); and its metadata country. */
    fun parseRatings(json: String): Pair<List<ProfileRatingChoice>, String?> {
        val o = normalized(JSONObject(json))
        val a = o.optJSONArray("ratings") ?: JSONArray()
        val ratings = List(a.length()) { normalized(a.getJSONObject(it)) }.mapNotNull { r ->
            if (!r.has("score")) return@mapNotNull null
            val labels = r.optJSONArray("labels")?.let { l -> List(l.length()) { l.getString(it) } }.orEmpty()
            ProfileRatingChoice(r.getInt("score"), labels.joinToString(" / ").ifEmpty { r.getInt("score").toString() })
        }.sortedBy { it.score }
        return ratings to o.str("country")
    }

    /** The pictures a profile may pick from: the administrator's uploads and the plugin's own set, by the plugin's small route. */
    fun parseAvatars(json: String, url: (String) -> String): List<ProfileAvatarChoice> {
        val a = normalized(JSONObject(json)).optJSONArray("avatars") ?: return emptyList()
        return List(a.length()) { normalized(a.getJSONObject(it)) }.mapNotNull { o ->
            val id = o.str("id") ?: return@mapNotNull null
            ProfileAvatarChoice(id, o.str("displayName") ?: id, url(id))
        }
    }

    /**
     * A profile as the plugin takes it, new (`create`) or changed (`update`, with its id); anything null is left out. New: a PIN only
     * when typed, the picture picked or none. Changing: no PIN keeps the one there is and an empty one removes it; no picture keeps
     * the one there is; the rating and libraries are sent as filled in, since leaving them out would clear them.
     */
    fun request(draft: ProfileDraft): JSONObject {
        val pin = if (draft.isEditing) (if (draft.removePin) "" else draft.pin.ifEmpty { null }) else draft.pin.ifEmpty { null }
        val keepsAPin = if (draft.isEditing) !draft.removePin else pin != null
        return JSONObject().apply {
            draft.profileId?.let { put("profileId", it) }
            put("profileName", draft.trimmedName)
            pin?.let { put("pin", it) }
            put("avatarColor", draft.colorHex)
            // The rating's score, as text.
            draft.ratingScore?.let { put("maxParentalRating", it.toString()) }
            // None for every library the main account has, now and later.
            draft.libraryIds?.let { put("enabledFolders", JSONArray(it.sorted())) }
            draft.masterPin.ifEmpty { null }?.let { put("masterPin", it) }
            draft.avatarId?.let { put("avatarLibraryId", it) }
            if (keepsAPin) put("bypassPinOnLocalNetwork", draft.bypassPinOnLocalNetwork)
        }
    }

    /** A switch that went through: the profile's own token, and its Jellyfin user. */
    fun parseSwitch(json: String): Pair<String, String> {
        val o = normalized(JSONObject(json))
        return o.getString("activeProfileToken") to o.getString("jellyfinUserId")
    }

    /**
     * The plugin's refusals. From 1.9 a header names them; before, the status has to do: a wrong PIN is a 400 when one was sent, too
     * many tries a 429. The body is plain text written for the person.
     */
    fun check(answer: BonfireAnswer, pinSent: Boolean) {
        if (answer.status in 200..299) return
        val text = answer.body.take(300).trim().ifEmpty { null }
        when (val code = answer.code?.lowercase()) {
            "pin_wrong" -> throw ProfileSwitchException.WrongPin()
            "pin_missing", "main_pin_required" -> throw ProfileSwitchException.PinNeeded()
            "daily_limit_reached", "rate_limited", "too_many_attempts" -> throw ProfileSwitchException.RateLimited()
            "maintenance" -> throw ProfileSwitchException.Failed(text ?: "The server is closed for maintenance.")
            null, "" -> {}
            else -> throw ProfileSwitchException.Failed(text ?: "The server didn't allow it ($code).")
        }
        throw when {
            answer.status == 400 && pinSent -> ProfileSwitchException.WrongPin()
            answer.status == 429 -> ProfileSwitchException.RateLimited()
            answer.status == 401 || answer.status == 403 -> ProfileSwitchException.Failed(text ?: "The server didn't allow it.")
            else -> ProfileSwitchException.Failed(text ?: "The server answered ${answer.status}.")
        }
    }
}

private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null
private fun JSONObject.bool(key: String): Boolean? = if (has(key) && !isNull(key)) optBoolean(key) else null

/** What the server answered: the body, the status, and (from 1.9) the plugin's own code for what went wrong. */
data class BonfireAnswer(val status: Int, val body: String, val code: String?)

/** One entry of the household: the main account, a profile, or a member of a linked household ([isBonfire]). */
data class BonfireProfileRecord(
    /** The profile's own Jellyfin user, as a dashed GUID. */
    val profileUserId: String,
    val profileName: String,
    val avatarInitial: String?,
    val avatarColor: String?,
    /** Whether a PIN is asked for right now (false on the home network when the profile skips it there). */
    val requiresPin: Boolean?,
    /** Whether a PIN is set at all. */
    val hasPin: Boolean?,
    val isMaster: Boolean?,
    /** Belongs to another household linked to this one. */
    val isBonfire: Boolean?,
    /** Null, one of the plugin's avatars, a URL, or a stored picture. */
    val profileImage: String?,
    val masterUserId: String?,
    /** On the main account's entry: how many profiles it may have. */
    val maxSubProfiles: Int? = null,
    /** On a profile of this household: the libraries it was given, or [allLibraries] for every one the main account has. */
    val enabledFolders: List<String>? = null,
    val allLibraries: Boolean? = null,
    val bypassPinOnLocalNetwork: Boolean? = null,
) {
    companion object {
        fun parse(o: JSONObject): BonfireProfileRecord? = BonfireProfileRecord(
            o.str("profileUserId") ?: return null, o.str("profileName") ?: "Profile",
            o.str("avatarInitial"), o.str("avatarColor"), o.bool("requiresPin"), o.bool("hasPin"), o.bool("isMaster"), o.bool("isBonfire"),
            o.str("profileImage"), o.str("masterUserId"),
            maxSubProfiles = if (o.has("maxSubProfiles") && !o.isNull("maxSubProfiles")) o.getInt("maxSubProfiles") else null,
            enabledFolders = o.optJSONArray("enabledFolders")?.let { a -> List(a.length()) { a.getString(it) } },
            allLibraries = o.bool("allLibraries"), bypassPinOnLocalNetwork = o.bool("bypassPinOnLocalNetwork"),
        )
    }
}

/** The plugin's requests, sent by the repository with the sign-in in use: method, path under [Bonfire.BASE_PATH], JSON body. */
typealias BonfireSend = suspend (method: String, path: String, body: String?) -> BonfireAnswer

class BonfireClient(private val send: BonfireSend, private val serverUrl: String) {
    /** Every profile the signed-in person can switch to, or null when the server has no plugin (the route answers 404). */
    suspend fun list(): List<BonfireProfileRecord>? {
        val answer = send("GET", "/list", null)
        if (answer.status == 404) return null
        Bonfire.check(answer, pinSent = false)
        return Bonfire.parseList(answer.body)
    }

    suspend fun askOnStartup(): Boolean? {
        val answer = send("GET", "/preferences", null)
        Bonfire.check(answer, pinSent = false)
        return Bonfire.parseAskOnStartup(answer.body)
    }

    /** The plugin's description of itself, for the debug report; null on a version without the route (before 1.9: 404 or 405). */
    suspend fun info(): JSONObject? {
        val answer = send("GET", "/info", null)
        if (answer.status == 404 || answer.status == 405) return null
        Bonfire.check(answer, pinSent = false)
        return Bonfire.normalized(JSONObject(answer.body))
    }

    /**
     * Switches to a profile, with its PIN where one is asked for: a token for the profile's own user, and its id. The server ends that
     * user's earlier sign-ins from this device first; the one making the call stays.
     */
    suspend fun switchProfile(id: String, pin: String?): Pair<String, String> {
        val p = pin?.ifEmpty { null }
        val answer = send("POST", "/switch", body(id, p))
        Bonfire.check(answer, pinSent = p != null)
        return Bonfire.parseSwitch(answer.body)
    }

    /** Checks a profile's PIN without switching to it. */
    suspend fun verifyPin(id: String, pin: String) {
        Bonfire.check(send("POST", "/verify-pin", body(id, pin)), pinSent = true)
    }

    /** A profile's stored picture, small; anyone can read it. */
    fun imageUrl(profileUserId: String) = "${serverUrl.trimEnd('/')}${Bonfire.BASE_PATH}/image/$profileUserId?size=thumb"

    /** One of the plugin's avatars (its own set, or the administrator's), small. Anyone can read it. */
    fun avatarUrl(id: String) = "${serverUrl.trimEnd('/')}${Bonfire.BASE_PATH}/avatars/$id?size=thumb"

    // MARK: Making and changing profiles

    suspend fun libraries(): List<ProfileLibraryChoice> = Bonfire.parseLibraries(answered("/libraries"))
    suspend fun ratings(): Pair<List<ProfileRatingChoice>, String?> = Bonfire.parseRatings(answered("/ratings"))
    suspend fun avatars(): List<ProfileAvatarChoice> = Bonfire.parseAvatars(answered("/avatars"), ::avatarUrl)

    /** Makes a profile under the signed-in main account: its id and name. The server's refusals come back in its words. */
    suspend fun createProfile(draft: ProfileDraft): Pair<String, String> {
        val answer = send("POST", "/create", Bonfire.request(draft).toString())
        Bonfire.check(answer, pinSent = false)
        val o = Bonfire.normalized(JSONObject(answer.body))
        return o.getString("profileUserId") to (o.str("profileName") ?: draft.trimmedName)
    }

    /** Changes a profile of the signed-in main account (or the account's own colour, picture and PIN). Needs its id. */
    suspend fun updateProfile(draft: ProfileDraft) = Bonfire.check(send("POST", "/update", Bonfire.request(draft).toString()), pinSent = false)

    /** Removes a profile and its Jellyfin user; its sign-ins end with it. */
    suspend fun deleteProfile(id: String, masterPin: String?) {
        val body = JSONObject().put("profileId", id).apply { masterPin?.ifEmpty { null }?.let { put("masterPin", it) } }
        Bonfire.check(send("POST", "/delete", body.toString()), pinSent = false)
    }

    private suspend fun answered(path: String): String {
        val answer = send("GET", path, null)
        Bonfire.check(answer, pinSent = false)
        return answer.body
    }

    // The PIN goes in the request body, never in a log.
    private fun body(id: String, pin: String?) = JSONObject().put("profileId", id).apply { pin?.let { put("pin", it) } }.toString()
}

/**
 * A Jellyfin server's household, through the Bonfire plugin: who's in it, switching, and making, changing and removing a profile.
 * [userRecord] reads a Jellyfin user's own record (`/Users/{id}`), where a profile's rating limit lives.
 */
class BonfireProfiles(
    send: BonfireSend, private val serverUrl: String, private val serverName: String?,
    private val userRecord: suspend (id: String) -> JSONObject? = { null },
) : ProfileProvider, ProfileCreation {
    val client = BonfireClient(send, serverUrl)
    override val kind = ProfileProviderKind.Bonfire
    override val creation: ProfileCreation get() = this

    /** All three at once; one that fails leaves its list empty rather than holding the form up (no ratings is still a form). */
    override suspend fun creationChoices(): ProfileCreationOptions = kotlinx.coroutines.coroutineScope {
        val avatars = async { runCatching { client.avatars() }.getOrDefault(emptyList()) }
        val libraries = async { runCatching { client.libraries() }.getOrDefault(emptyList()) }
        val ratings = async { runCatching { client.ratings() }.getOrDefault(emptyList<ProfileRatingChoice>() to null) }
        val (ratingList, country) = ratings.await()
        ProfileCreationOptions(avatars.await(), libraries.await(), ratingList, country)
    }

    override suspend fun createProfile(draft: ProfileDraft): String {
        val (id, name) = client.createProfile(draft)
        DebugLog.note("profiles", "Made a profile (${name.length} letters)")
        return HouseholdProfile.plainId(id)
    }

    /**
     * As the profile is: its name, colour, libraries and home-network setting from the list, and its rating limit from its own
     * Jellyfin record (the list leaves that out), read the way the plugin's own form reads it.
     */
    override suspend fun draft(editing: HouseholdProfile): ProfileDraft {
        val libraries = if (editing.allLibraries == true || editing.enabledFolders == null) null else editing.enabledFolders.map(HouseholdProfile::plainId).toSet()
        val rating = if (editing.isMaster) null else runCatching { userRecord(editing.rawId) }.getOrNull()
            ?.optJSONObject("Policy")?.let { if (it.has("MaxParentalRating") && !it.isNull("MaxParentalRating")) it.getInt("MaxParentalRating") else null }
        return ProfileDraft(
            profileId = editing.rawId, name = editing.name, colorHex = editing.colorHex, libraryIds = libraries,
            ratingScore = rating, bypassPinOnLocalNetwork = editing.bypassPinOnLocalNetwork ?: false,
        )
    }

    override suspend fun updateProfile(draft: ProfileDraft) {
        client.updateProfile(draft)
        DebugLog.note("profiles", "Changed a profile")
    }

    override suspend fun deleteProfile(profile: HouseholdProfile, masterPin: String?) {
        client.deleteProfile(profile.rawId, masterPin)
        DebugLog.note("profiles", "Removed a profile")
    }
    private var askedInfo = false

    override suspend fun askOnStartup(): Boolean? = client.askOnStartup()

    override suspend fun profiles(current: Session): List<HouseholdProfile> {
        val records = client.list() ?: throw ProfileSwitchException.Unavailable()
        noteInfo()
        return records.map { profile(it, client, current.userId) }
    }

    override suspend fun verifyPin(pin: String, profile: HouseholdProfile) = client.verifyPin(profile.rawId, pin)

    override suspend fun switchSession(profile: HouseholdProfile, pin: String?): Session {
        if (profile.requiresPin && pin.isNullOrEmpty()) throw ProfileSwitchException.PinNeeded()
        val (token, userId) = client.switchProfile(profile.rawId, pin)
        // The dashed id the sign-in screen keeps a Jellyfin user under, so the same user lands on the same saved entry, cache and settings.
        return Session(serverUrl, token, HouseholdProfile.dashedId(userId), profile.name, ServerKind.Jellyfin, serverName, household = profile.storedHousehold)
    }

    /** Which Bonfire this is, for the debug report; once per sign-in. */
    private suspend fun noteInfo() {
        if (askedInfo) return
        askedInfo = true
        val info = runCatching { client.info() }.getOrNull()
        if (info == null) { DebugLog.note("profiles", "Bonfire before 1.9 (no info route)"); return }
        val features = info.optJSONObject("features")?.let(Bonfire::normalized)
        DebugLog.note(
            "profiles",
            "Bonfire ${info.str("version") ?: "?"} (API ${info.optInt("apiVersion")})" + (if (info.optBoolean("disabled")) ", switched off" else "") +
                ", app PIN sign-in ${if (features?.optBoolean("clientPinLogin") == true) "on" else "off"}" +
                ", app profile list ${if (features?.optBoolean("clientProfileList") == true) "on" else "off"}",
        )
    }

    companion object {
        fun profile(record: BonfireProfileRecord, client: BonfireClient, currentUserId: String): HouseholdProfile {
            val plain = HouseholdProfile.plainId(record.profileUserId)
            val name = record.profileName
            val initial = record.avatarInitial ?: name.take(1).uppercase()
            val color = record.avatarColor ?: Bonfire.DEFAULT_AVATAR_COLOR
            val isMaster = record.isMaster ?: false
            val masterId = record.masterUserId?.let(HouseholdProfile::plainId)
            // A picture elsewhere is loaded from there; anything stored on the server comes through the plugin's image route.
            val image = record.profileImage?.let { if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else client.imageUrl(record.profileUserId) }
            return HouseholdProfile(
                id = plain, rawId = record.profileUserId, name = name, imageUrl = image, initial = initial, colorHex = color,
                isCurrent = plain == HouseholdProfile.plainId(currentUserId), isMaster = isMaster,
                requiresPin = record.requiresPin ?: (record.hasPin ?: false), hasPin = record.hasPin ?: false,
                isOtherHousehold = record.isBonfire ?: false, masterUserId = masterId,
                storedHousehold = StoredHousehold(masterId ?: plain, isMaster, initial, color, record.profileImage, hasPin = record.hasPin ?: false),
                maxProfiles = record.maxSubProfiles, allLibraries = record.allLibraries,
                enabledFolders = record.enabledFolders?.map(HouseholdProfile::plainId), bypassPinOnLocalNetwork = record.bypassPinOnLocalNetwork,
            )
        }
    }
}
