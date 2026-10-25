package com.astermediaplayer.astermediaplayer.ui.detail

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.OpEdTheme
import com.astermediaplayer.astermediaplayer.data.ThemeCut
import com.astermediaplayer.astermediaplayer.data.ThemeClip
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** What a theme's card shows that the plugin doesn't say: its length, and a frame from its video. */
class ThemePreview(val seconds: Int?, val frame: Bitmap?)

/** Reads each theme's length (and grabs a frame, when it has video) from the file itself, once per theme while the app runs, a few at a time. */
object ThemePreviews {
    private val done = mutableMapOf<String, ThemePreview>()
    private val turns = Semaphore(3)

    suspend fun preview(theme: OpEdTheme, repo: AsterRepository): ThemePreview? {
        done[theme.id]?.let { return it }
        val path = theme.playablePath ?: return null
        val url = repo.opEdMediaUrl(path)
        val hasVideo = theme.videoMp4Url != null
        val result = turns.withPermit { withContext(Dispatchers.IO) { read(url, hasVideo) } }
        done[theme.id] = result
        return result
    }

    /** A frame about a third of the way in, past any fade from black. */
    private fun read(url: String, hasVideo: Boolean): ThemePreview {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url, HashMap())
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val frame = if (hasVideo && ms != null && ms > 0) {
                val at = minOf((ms * 0.35).toLong(), 45_000L) * 1000
                retriever.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 800, 450)
            } else null
            ThemePreview(ms?.let { ((it + 500) / 1000).toInt() }, frame)
        } catch (e: Exception) {
            ThemePreview(null, null)
        } finally {
            runCatching { retriever.release() }
        }
    }
}

private fun time(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

@Composable
private fun Pill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(
        Modifier.padding(10.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = Color.White, modifier = Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1)
    }
}

/**
 * An opening or ending from the OP/ED plugin: a frame from its own video (the show's picture until then, or for a song
 * alone), "OP 1" and its length over it, and the song, artist and episodes below.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun ThemeCard(
    theme: OpEdTheme, repo: AsterRepository, seasonName: String?, fallbackArtwork: String?, isPlaying: Boolean,
    /** Plays one version (TV size, full, music video); null when it has only the TV size. */
    onPlayCut: ((ThemeCut) -> Unit)? = null,
    /** For a theme the plugin published as a music video: adds it to a playlist. */
    onAddToPlaylist: (() -> Unit)? = null,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onClick: () -> Unit,
) {
    val deck = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.astermediaplayer.astermediaplayer.AsterApp).themeDeck
    var menu by remember { mutableStateOf(false) }
    // The versions this theme has: the TV size, and a full version or a music video where the plugin has them.
    val cuts = ThemeCut.entries.filter { it == ThemeCut.TvSize || theme.cut(it) != null }
    val hasMenu = (onPlayCut != null && cuts.size > 1) || onAddToPlaylist != null
    val hidden = theme.spoiler || theme.nsfw
    val preview by produceState<ThemePreview?>(null, theme.id) { value = ThemePreviews.preview(theme, repo) }
    val badge = (if (theme.isOpening) "OP" else "ED") + " ${theme.sequence}" + if (theme.version > 1) " v${theme.version}" else ""
    val detail = listOfNotNull(seasonName, theme.episodes?.let { "Episodes $it" }).joinToString(" · ").ifEmpty { null }
    Column(Modifier.width(Lockup.landscapeWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier).tvFocus(14.dp)
                // Holding Select here opens this card's menu instead of going to the Now Playing panel.
                .onFocusChanged { if (hasMenu) deck.cardHoldFocused = it.isFocused }
                .clip(RoundedCornerShape(14.dp)).background(Color.Black)
                .combinedClickable(enabled = !theme.isPreparing, onClick = onClick, onLongClick = if (hasMenu) ({ menu = true }) else null),
        ) {
            val frame = preview?.frame
            val blurModifier = if (hidden) Modifier.blur(24.dp) else Modifier
            if (frame != null) Image(frame.asImageBitmap(), null, Modifier.fillMaxSize().then(blurModifier), contentScale = ContentScale.Crop)
            else if (fallbackArtwork != null) AsyncImage(fallbackArtwork, null, Modifier.fillMaxSize().then(blurModifier), contentScale = ContentScale.Crop)
            if (hidden || theme.isPreparing) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Pill(if (theme.isPreparing) "Preparing…" else if (theme.nsfw) "NSFW" else "Spoiler", if (theme.isPreparing) Icons.Default.HourglassEmpty else Icons.Default.VisibilityOff)
            }
            Row(Modifier.align(Alignment.TopStart)) {
                Pill(badge)
                if (theme.cut(com.astermediaplayer.astermediaplayer.data.ThemeCut.Full) != null || theme.hasFullVersion) Pill("Full")
                if (theme.cut(com.astermediaplayer.astermediaplayer.data.ThemeCut.MusicVideo) != null || theme.hasMusicVideo) Pill("MV")
                if (isPlaying) Pill("Playing", Icons.Default.VolumeUp)
            }
            Row(Modifier.align(Alignment.BottomEnd)) {
                if (theme.videoMp4Url == null && theme.playablePath != null) Pill("Audio only", Icons.Default.MusicNote)
                preview?.seconds?.let { Pill(time(it)) }
            }
        }
        MenuHost(expanded = menu, onDismiss = { menu = false }, below = true) {
            if (onPlayCut != null && cuts.size > 1) cuts.reversed().forEachIndexed { i, cut ->
                MenuItem("Play ${cut.settingTitle}", Icons.Default.MusicNote, first = i == 0, onClick = { menu = false; onPlayCut(cut) })
            }
            onAddToPlaylist?.let { add -> MenuItem("Add to Playlist", Icons.AutoMirrored.Filled.PlaylistAdd, first = onPlayCut == null || cuts.size <= 1, onClick = { menu = false; add() }) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(theme.songTitle ?: badge, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (theme.artists.isNotEmpty()) Text(theme.artists.joinToString(", "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** An opening or ending found in an episode's chapters: the episode's picture, "OP 1" and its length over it, and where it's from below. */
@Composable
fun ThemeClipCard(clip: ThemeClip, artwork: String?, isPlaying: Boolean, focusRequester: androidx.compose.ui.focus.FocusRequester? = null, onClick: () -> Unit) {
    val badge = (if (clip.opening) "OP" else "ED") + " ${clip.number}"
    val season = clip.episode.parentIndexNumber
    val number = clip.episode.indexNumber
    val from = if (season != null && number != null) "From S$season, E$number" else clip.episode.name
    Column(Modifier.width(Lockup.landscapeWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier).tvFocus(14.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black).clickable(onClick = onClick)) {
            if (artwork != null) AsyncImage(artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Row(Modifier.align(Alignment.TopStart)) { Pill(badge); if (isPlaying) Pill("Playing", Icons.Default.VolumeUp) }
            Box(Modifier.align(Alignment.BottomEnd)) { Pill(time(clip.seconds)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(clip.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            from?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** A pill that always says what it does (the Apple TV's bordered buttons): white when focused or the main one, grey otherwise. */
@Composable
fun LabeledPill(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, prominent: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val lit = focused || prominent
    val tint = if (lit) Color.Black else Color.White
    Row(
        Modifier.onFocusChanged { focused = it.isFocused }.tvFocus(40.dp, lift = 1.05f, ring = false).clip(CircleShape)
            .background(if (lit) Color.White else Color(0xFF3A3A3C)).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.width(22.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = tint, maxLines = 1)
    }
}
