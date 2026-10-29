package com.astermediaplayer.astermediaplayer.ui.detail

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.data.OpeningEnding
import com.astermediaplayer.astermediaplayer.ui.player.ThemeDeck
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText

/** Where Back from the Now Playing panel sends focus: the page, which remembers what had it. */
val LocalPageFocus = androidx.compose.runtime.compositionLocalOf<FocusRequester?> { null }

/** The Music tab's Now Playing panel: the picture, what's playing and its progress, the controls, and what it is. Nothing while nothing plays. */
@OptIn(UnstableApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ThemeNowPlayingPanel(deck: ThemeDeck, artwork: String?, modifier: Modifier = Modifier, showDetails: Boolean = true, wide: Boolean = false, onFullScreen: (() -> Unit)? = null, playFocus: FocusRequester? = null, onOpenShow: (() -> Unit)? = null) {
    val entry = deck.current ?: return
    val picture: @Composable (Modifier) -> Unit = { m ->
        Box(m.aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            val player = deck.player
            if (deck.hasVideo && player != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            this.player = player
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            } else if (artwork != null) {
                AsyncImage(artwork, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Crop)
                Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(14.dp))
            }
            if (deck.loading) CircularProgressIndicator()
        }
    }
    val info: @Composable () -> Unit = {
        val (title, subtitle) = titles(entry)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Now Playing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val fraction = if (deck.durationMs > 0) (deck.elapsedMs.toFloat() / deck.durationMs).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(time(deck.elapsedMs), style = MaterialTheme.typography.labelSmall, color = SecondaryText)
                Text(if (deck.durationMs > 0) time(deck.durationMs) else "–:––", style = MaterialTheme.typography.labelSmall, color = SecondaryText)
            }
        }
        // Moving in from outside lands on Play/Pause, not on whichever button is nearest.
        Row(
            Modifier.focusGroup().then(if (playFocus != null) Modifier.focusRestorer(playFocus) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            IconActionButton(Icons.Default.SkipPrevious, "Previous") { deck.previous() }
            IconActionButton(if (deck.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (deck.isPlaying) "Pause" else "Play", if (playFocus != null) Modifier.focusRequester(playFocus) else Modifier) { deck.togglePlaying() }
            IconActionButton(Icons.Default.SkipNext, "Next") { deck.next() }
            IconActionButton(Icons.Default.Stop, "Stop") { deck.stop() }
            // The same one, bigger.
            onFullScreen?.let { IconActionButton(Icons.Default.Fullscreen, "Full Screen", onClick = it) }
            onOpenShow?.let { IconActionButton(Icons.Default.ChevronRight, "Go to the Show", onClick = it) }
        }
        if (showDetails) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((label, value) in details(entry, deck.seasons)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = SecondaryText, modifier = Modifier.padding(end = 4.dp))
                    Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    val page = LocalPageFocus.current
    // Back in the panel goes back to what had focus on the page, rather than leaving the page.
    val shape = Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xF21C1C1E)).onPreviewKeyEvent { e ->
        if (e.key == Key.Back && e.type == KeyEventType.KeyDown && (deck.returnFocus != null || page != null)) {
            // The card that started it, if it is still on the page; otherwise the page's last-focused item.
            if (runCatching { deck.returnFocus?.requestFocus() }.getOrNull() == null) runCatching { page?.requestFocus() }
            true
        } else false
    }
    // Wide (a TV, across the page): the picture beside what it is and the controls; narrow: stacked.
    if (wide) Row(modifier.then(shape).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        picture(Modifier.width(300.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { info() }
    } else Column(modifier.then(shape).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        picture(Modifier.fillMaxWidth())
        info()
    }
}

/** The big line and the one under it: the song and artist, or for a chapter clip, which opening it is and where it's from. */
private fun titles(entry: OpeningEnding): Pair<String, String?> = when (entry) {
    is OpeningEnding.Theme -> {
        val t = entry.theme
        ((t.songTitle ?: t.code) + (t.cut.label?.let { " ($it)" } ?: "")) to t.artists.joinToString(", ").ifEmpty { null }
    }
    is OpeningEnding.Chapter -> entry.clip.songTitle to (entry.clip.artists ?: episodeCode(entry)?.let { "From $it" })
}

private fun episodeCode(entry: OpeningEnding.Chapter): String? {
    val e = entry.clip.episode
    val season = e.parentIndexNumber
    val number = e.indexNumber
    return if (season != null && number != null) "S$season, E$number" else null
}

/** What it is, in rows, from what's known. */
private fun details(entry: OpeningEnding, seasons: List<org.jellyfin.sdk.model.api.BaseItemDto>): List<Pair<String, String>> = when (entry) {
    is OpeningEnding.Theme -> {
        val t = entry.theme
        listOfNotNull(
            "Type" to ((if (t.isOpening) "Opening" else "Ending") + " ${t.sequence}" + if (t.version > 1) ", version ${t.version}" else ""),
            // OP 1 comes round again each season, so the season is named when the show has more than one.
            if (seasons.count { (it.indexNumber ?: 0) > 0 } > 1) t.seasonId?.let { id -> seasons.firstOrNull { it.id.toString().replace("-", "") == id.replace("-", "") }?.name }?.let { "Season" to it } else null,
            if (t.artists.isEmpty()) null else (if (t.artists.size == 1) "Artist" else "Artists") to t.artists.joinToString(", "),
            "Plays" to if (t.videoMp4Url == null) "Song only" else "Music video",
        )
    }
    is OpeningEnding.Chapter -> {
        val c = entry.clip
        listOfNotNull(
            "Type" to ((if (c.opening) "Opening" else "Ending") + if (c.number > 1) " ${c.number}" else ""),
            "Season" to "Season ${c.season}",
            "From" to listOfNotNull(episodeCode(entry), c.episode.name).joinToString(" · "),
            "Plays" to "From the episode",
        )
    }
}

private fun time(ms: Long): String {
    val whole = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(whole / 60, whole % 60)
}
