package com.astermediaplayer.astermediaplayer.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.ui.BackButton
import com.astermediaplayer.astermediaplayer.ui.components.PosterCard
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID

/** A person from a title's cast: their photo, name and biography, then the movies and shows they're in, the newest first. The Apple apps' `PersonView`. */
@Composable
fun PersonScreen(repo: AsterRepository, personId: UUID, onItem: (BaseItemDto) -> Unit, onBack: () -> Unit) {
    val person by produceState<BaseItemDto?>(null, personId) { value = runCatching { repo.item(personId) }.getOrNull() }
    val titles by produceState<List<BaseItemDto>?>(null, personId) { value = runCatching { repo.filmography(personId) }.getOrDefault(emptyList()) }
    val photoSize = if (LocalIsTv.current) (260f * Lockup.tvUnit).dp else 110.dp

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val room = maxWidth - Lockup.screenPadding * 2
            val perRow = maxOf(1, ((room + Lockup.shelfSpacing) / (Lockup.posterWidth + Lockup.shelfSpacing)).toInt())
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 56.dp + Lockup.screenPadding / 2).padding(horizontal = Lockup.screenPadding),
                        verticalArrangement = Arrangement.spacedBy(Lockup.sectionSpacing),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Lockup.sectionSpacing), verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(photoSize).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                AsyncImage(
                                    model = person?.let { repo.imageUrl(it, ImageType.PRIMARY, 500) }, contentDescription = null,
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(person?.name.orEmpty(), style = Type.pageTitle.copy(fontWeight = FontWeight.Bold))
                                person?.overview?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = Type.callout, color = SecondaryText, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 1100.dp))
                                }
                            }
                        }
                        Text("Filmography", style = Type.title3.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(4.dp))
                    }
                }
                when (val list = titles) {
                    null -> item { Box(Modifier.fillMaxWidth().padding(Lockup.pageBottomPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    else -> items(list.chunked(perRow)) { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = Lockup.screenPadding).padding(bottom = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing),
                        ) { row.forEach { PosterCard(it, { onItem(it) }) } }
                    }
                }
                item { Spacer(Modifier.height(TabBarClearance)) }
            }
        }
        BackButton(onBack)
    }
}
