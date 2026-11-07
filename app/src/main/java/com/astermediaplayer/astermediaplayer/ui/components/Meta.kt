package com.astermediaplayer.astermediaplayer.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType

// What the title page and the hero banners both show under a title: the metadata line, its badge and the rating.

/** Years, what's unwatched, rating, runtime and genres, then the 4K or HD badge and the star rating. */
@Composable
fun MetaLine(item: BaseItemDto, showsUnwatched: Boolean = false, modifier: Modifier = Modifier, style: TextStyle = Type.callout) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // Inter sets digits and the separators wider than San Francisco, so this line is tracked a little tighter to
        // wrap where the Apple apps' does.
        Text(metaLine(item, showsUnwatched), style = style.copy(letterSpacing = (-0.2).sp), color = SecondaryText, modifier = Modifier.weight(1f, fill = false))
        videoBadge(item)?.let { Badge(it) }
        item.communityRating?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.Star, null, tint = SecondaryText, modifier = Modifier.size(12.dp))
                Text("%.1f".format(it), style = style, color = SecondaryText)
            }
        }
    }
}


/** "4K" or "HD", outlined like the Apple apps' badge. */
@Composable
fun Badge(text: String) {
    Text(
        text, style = Type.caption2Bold, color = Color.White,
        modifier = Modifier.border(2.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}


fun runtimeText(item: BaseItemDto): String? {
    // A placeholder for an episode that hasn't aired has no length: "0 min" would be a lie.
    val minutes = item.runTimeTicks?.let { it / 600_000_000L }?.takeIf { it > 0 } ?: return null
    // The Apple TV writes "1 hr 41 min"; the phone apps, "1h 41m".
    if (com.astermediaplayer.astermediaplayer.ui.theme.Lockup.onTv) return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

/** The meta line the Apple TV app shows under a title: years, what's unwatched, rating, runtime and two genres. */
fun metaLine(item: BaseItemDto, showsUnwatched: Boolean = false): String = listOfNotNull(
    yearText(item),
    item.userData?.unplayedItemCount?.takeIf { showsUnwatched && it > 0 && item.type == BaseItemKind.SERIES }?.let { "$it unwatched" },
    item.officialRating,
    if (item.type == BaseItemKind.SERIES) null else runtimeText(item),
    item.genres?.take(2)?.joinToString(", ")?.ifEmpty { null },
).joinToString("  ·  ")

/** A running show is "2019–", an ended one "2019–2021". */
fun yearText(item: BaseItemDto): String? {
    val year = item.productionYear ?: return null
    if (item.type != BaseItemKind.SERIES) return year.toString()
    if (item.status == "Continuing") return "$year–"
    val end = item.endDate?.year
    return if (end != null && end != year) "$year–$end" else year.toString()
}

/** "1 hr 45 min left" for something partly watched. */
fun remainingText(item: BaseItemDto): String? {
    val total = item.runTimeTicks ?: return null
    val position = item.userData?.playbackPositionTicks ?: return null
    if (total <= 0 || position <= 0) return null
    val left = ((total - position) / 600_000_000L).coerceAtLeast(0)
    return if (left >= 60) "${left / 60} hr ${left % 60} min left" else "$left min left"
}

/** "4K" or "HD" from the video stream's width. */
fun videoBadge(item: BaseItemDto): String? {
    // A list's items carry the video's width; a title page's, its streams in its media sources.
    val streams = item.mediaStreams?.takeIf { it.isNotEmpty() } ?: item.mediaSources?.firstOrNull()?.mediaStreams
    val width = item.width?.takeIf { it > 0 } ?: streams?.firstOrNull { it.type == MediaStreamType.VIDEO }?.width ?: return null
    return when {
        width >= 3800 -> "4K"
        width >= 1280 -> "HD"
        else -> null
    }
}
