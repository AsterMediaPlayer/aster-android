package com.astermediaplayer.astermediaplayer.data.profiles

import com.astermediaplayer.astermediaplayer.data.Session
import com.astermediaplayer.astermediaplayer.data.emby.ServerKind
import com.astermediaplayer.astermediaplayer.data.plex.PlexHomeUser
import com.astermediaplayer.astermediaplayer.data.plex.PlexHttpException
import com.astermediaplayer.astermediaplayer.data.plex.PlexLink
import com.astermediaplayer.astermediaplayer.data.plex.PlexMapper
import com.astermediaplayer.astermediaplayer.data.plex.PlexResource

/** The plex.tv calls a Home needs, as the repository makes them. */
class PlexHomeAccess(
    val homeUsers: suspend (token: String) -> List<PlexHomeUser>,
    /** The profile's own token once plex.tv accepts the switch (and its PIN); throws [PlexHttpException] when not. */
    val switchUser: suspend (uuid: String, pin: String?, homeToken: String) -> String?,
    val servers: suspend (token: String) -> List<PlexResource>,
    val accountName: suspend (token: String) -> Pair<Long, String>,
)

/**
 * A Plex Home's people, as a household the picker can switch between (the Apple apps' `PlexHomeProfiles.swift`). Switching asks
 * plex.tv, with the person's PIN where they have one, for their own token, then the server's token for them.
 */
class PlexHomeProfiles private constructor(
    private val access: PlexHomeAccess, private val link: PlexLink, private val homeToken: String,
    private val serverUrl: String, private val serverName: String?,
) : ProfileProvider {
    override val kind = ProfileProviderKind.PlexHome

    override suspend fun askOnStartup(): Boolean? = null

    override suspend fun profiles(current: Session): List<HouseholdProfile> {
        val users = access.homeUsers(homeToken)
        if (users.isEmpty()) throw ProfileSwitchException.Unavailable()
        return users.map { profile(it, link, current.plex?.homeUserUuid) }
    }

    override suspend fun verifyPin(pin: String, profile: HouseholdProfile) { switchUser(profile, pin) }

    override suspend fun switchSession(profile: HouseholdProfile, pin: String?): Session {
        val userToken = switchUser(profile, pin) ?: homeToken
        // The server's token for this person (a shared server's is its own), and the addresses plex.tv lists for it now.
        val server = runCatching { access.servers(userToken).firstOrNull { it.clientIdentifier == link.machineId } }.getOrNull()
        val (accountId, accountName) = access.accountName(userToken)
        val fresh = PlexLink(userToken, link.machineId, server?.connections ?: link.connections, profile.rawId, profile.hasPin, homeToken)
        // Kept under the same id a sign-in through the sign-in screen gives this person, so it lands on the same saved entry.
        return Session(serverUrl, server?.accessToken ?: userToken, PlexMapper.id(accountId.toString()), accountName, ServerKind.Plex, serverName, fresh)
    }

    /** plex.tv lets a profile switch to itself with no PIN at all, so an empty one is never sent for a profile that has one. */
    private suspend fun switchUser(profile: HouseholdProfile, pin: String?): String? {
        val p = pin?.ifEmpty { null }
        if (profile.hasPin && p == null) throw ProfileSwitchException.PinNeeded()
        return try {
            access.switchUser(profile.rawId, p, homeToken)
        } catch (e: PlexHttpException) {
            throw when (e.code) {
                401, 403 -> ProfileSwitchException.WrongPin()
                429 -> ProfileSwitchException.RateLimited()
                else -> ProfileSwitchException.Failed("plex.tv answered ${e.code}.")
            }
        }
    }

    companion object {
        /** Plex's gold, for a person without a picture. */
        const val PLEX_COLOR = "#E5A00D"

        /** Null for a sign-in from before the Home's token was kept, or not as a Home profile: it can't check a PIN, so it can't switch. */
        fun of(access: PlexHomeAccess, session: Session): PlexHomeProfiles? {
            val link = session.plex ?: return null
            val homeToken = link.homeToken ?: return null
            if (link.homeUserUuid == null) return null
            return PlexHomeProfiles(access, link, homeToken, session.serverUrl, session.serverName)
        }

        fun profile(user: PlexHomeUser, link: PlexLink, currentUuid: String?): HouseholdProfile {
            val initial = user.title.take(1).uppercase()
            return HouseholdProfile(
                id = user.uuid, rawId = user.uuid, name = user.title, imageUrl = user.thumb, initial = initial, colorHex = PLEX_COLOR,
                isCurrent = user.uuid == currentUuid, isMaster = user.admin, requiresPin = user.hasPin, hasPin = user.hasPin,
                isOtherHousehold = false, masterUserId = null,
                storedHousehold = StoredHousehold(link.machineId, user.admin, initial, PLEX_COLOR, user.thumb),
            )
        }
    }
}
