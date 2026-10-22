package com.astermediaplayer.astermediaplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

@Composable
private fun repo() = (LocalContext.current.applicationContext as AsterApp).repository

/** Fraction watched, 0..1, or null when there's no resume point. */
fun BaseItemDto.progress(): Float? {
    val position = userData?.playbackPositionTicks ?: return null
    val total = runTimeTicks ?: return null
    if (position <= 0 || total <= 0) return null
    return (position.toFloat() / total).coerceIn(0f, 1f)
}

/** Title shown under a card; episodes lead with their series. */
fun BaseItemDto.cardTitle(): String = if (type == BaseItemKind.EPISODE) seriesName ?: name.orEmpty() else name.orEmpty()

fun BaseItemDto.cardSubtitle(): String? = when (type) {
    BaseItemKind.EPISODE -> listOfNotNull(
        parentIndexNumber?.let { "S$it" }?.plus(indexNumber?.let { "E$it" } ?: ""),
        name,
    ).joinToString(" · ").ifEmpty { null }
    else -> productionYear?.toString()
}

@Composable
fun PosterCard(item: BaseItemDto, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val url = repo().imageUrl(item, ImageType.PRIMARY, 400)
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))
                .then(Modifier),
        ) {
            AsyncImage(
                model = url, contentDescription = item.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            item.progress()?.let {
                LinearProgressIndicator(
                    progress = { it },
                    color = Color.White, trackColor = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                )
            }
            if (item.type == BaseItemKind.EPISODE && item.userData?.played == false && item.progress() == null) {
                Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
        Text(
            item.cardTitle(), style = MaterialTheme.typography.titleMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
        item.cardSubtitle()?.let {
            Text(
                it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 16:9 card for Continue Watching and Next Up, using the episode still or the backdrop. */
@Composable
fun LandscapeCard(item: BaseItemDto, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val repo = repo()
    val url = if (item.type == BaseItemKind.EPISODE && item.imageTags?.containsKey(ImageType.PRIMARY) == true) {
        repo.imageUrl(item, ImageType.PRIMARY, 600)
    } else {
        repo.imageUrl(item, ImageType.BACKDROP, 600) ?: repo.imageUrl(item, ImageType.PRIMARY, 600)
    }
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            AsyncImage(
                model = url, contentDescription = item.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            item.progress()?.let {
                LinearProgressIndicator(
                    progress = { it },
                    color = Color.White, trackColor = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                )
            }
            if (item.type == BaseItemKind.EPISODE && item.userData?.played == false && item.progress() == null) {
                Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
        Text(
            item.cardTitle(), style = MaterialTheme.typography.titleMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
        item.cardSubtitle()?.let {
            Text(
                it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun Shelf(
    title: String,
    items: List<BaseItemDto>,
    landscape: Boolean = false,
    onItem: (BaseItemDto) -> Unit,
) {
    if (items.isEmpty()) return
    Column(Modifier.padding(top = 20.dp)) {
        Text(
            title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { item ->
                val width = if (landscape) (LocalConfiguration.current.screenWidthDp * 0.43f).coerceIn(170f, 300f).dp else 130.dp
                if (landscape) LandscapeCard(item, { onItem(item) }, Modifier.width(width))
                else PosterCard(item, { onItem(item) }, Modifier.width(width))
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onRetry != null) Button(onClick = onRetry) { Text("Try again") }
        }
    }
}
