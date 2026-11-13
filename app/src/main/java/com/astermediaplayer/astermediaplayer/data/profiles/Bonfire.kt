package com.astermediaplayer.astermediaplayer.data.profiles

import com.astermediaplayer.astermediaplayer.data.Session
import com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog
import com.astermediaplayer.astermediaplayer.data.emby.ServerKind
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
) {
    companion object {
        fun parse(o: JSONObject): BonfireProfileRecord? = BonfireProfileRecord(
            o.str("profileUserId") ?: return null, o.str("profileName") ?: "Profile",
            o.str("avatarInitial"), o.str("avatarColor"), o.bool("requiresPin"), o.bool("hasPin"), o.bool("isMaster"), o.bool("isBonfire"),
            o.str("profileImage"), o.str("masterUserId"),
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

    // The PIN goes in the request body, never in a log.
    private fun body(id: String, pin: String?) = JSONObject().put("profileId", id).apply { pin?.let { put("pin", it) } }.toString()
}

/** A Jellyfin server's household, through the Bonfire plugin. */
class BonfireProfiles(send: BonfireSend, private val serverUrl: String, private val serverName: String?) : ProfileProvider {
    val client = BonfireClient(send, serverUrl)
    override val kind = ProfileProviderKind.Bonfire
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
                storedHousehold = StoredHousehold(masterId ?: plain, isMaster, initial, color, record.profileImage),
            )
        }
    }
}
