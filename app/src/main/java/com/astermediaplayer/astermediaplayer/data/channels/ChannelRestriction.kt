package com.astermediaplayer.astermediaplayer.data.channels

import org.jellyfin.sdk.model.api.ParentalRating
import org.jellyfin.sdk.model.api.UserPolicy

/**
 * The parental controls on the user's profile: the highest rating's score (and sub-score) allowed, tags blocked or
 * required, kinds of unrated item blocked, and whether every library is open.
 */
data class ContentPolicy(
    val maxParentalRating: Int? = null,
    val maxParentalSubRating: Int? = null,
    val blockedTags: List<String> = emptyList(),
    val allowedTags: List<String> = emptyList(),
    val blocksUnrated: Boolean = false,
    val enableAllFolders: Boolean = true,
) {
    /** Whether anything other than the rating cap holds titles back. */
    val restrictsBeyondRating: Boolean
        get() = blockedTags.isNotEmpty() || allowedTags.isNotEmpty() || blocksUnrated || !enableAllFolders

    /** Whether the server holds anything back from this user. */
    val restrictsContent: Boolean get() = maxParentalRating != null || restrictsBeyondRating

    companion object {
        fun from(policy: UserPolicy) = ContentPolicy(
            maxParentalRating = policy.maxParentalRating, maxParentalSubRating = policy.maxParentalSubRating,
            blockedTags = policy.blockedTags.orEmpty(), allowedTags = policy.allowedTags.orEmpty(),
            blocksUnrated = !policy.blockUnratedItems.isNullOrEmpty(), enableAllFolders = policy.enableAllFolders,
        )
    }
}

/** A rating the server knows ("PG-13") and how it ranks: a user's cap is compared with [score], then [subScore]. */
data class RatingRank(val name: String, val score: Int?, val subScore: Int? = null) {
    companion object {
        fun from(rating: ParentalRating) = RatingRank(rating.name.orEmpty(), rating.ratingScore?.score ?: rating.value, rating.ratingScore?.subScore)
    }
}

object ChannelRestriction {
    /**
     * Whether a channel has anything on it the user's parental controls hold back, so it's left out rather than shown
     * with gaps. The server leaves those titles out of what it sends this user, so:
     * - A playlist lists more titles than came back (or none came back).
     * - Genres, networks and collections can't be counted that way, so a restricted user gets such a channel only when
     *   its own `rating=` tags keep it within their rating cap and nothing else is restricted.
     *
     * A user without restrictions gets every channel, whatever the counts. As on the Apple apps.
     */
    fun isHeldBack(listed: Int?, fetched: Int?, tags: ChannelTags, policy: ContentPolicy, ratings: List<RatingRank>): Boolean {
        if (!policy.restrictsContent) return false
        if (listed != null && listed > 0 && (fetched ?: 0) < listed) return true
        if (!tags.hasSources) return false
        val cap = policy.maxParentalRating
        if (policy.restrictsBeyondRating || cap == null || tags.ratings.isEmpty()) return true
        return tags.ratings.any { name ->
            val rating = ratings.firstOrNull { it.name.equals(name, ignoreCase = true) }
            val score = rating?.score ?: return@any true
            score > cap || (score == cap && (rating.subScore ?: 0) > (policy.maxParentalSubRating ?: Int.MAX_VALUE))
        }
    }
}
