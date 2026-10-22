package com.astermediaplayer.astermediaplayer.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.ItemPage
import com.astermediaplayer.astermediaplayer.ui.audiobooks.Segmented
import com.astermediaplayer.astermediaplayer.ui.theme.*
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

private enum class MusicSection(val label: String) { Albums("Albums"), Artists("Artists"), Playlists("Playlists"), Songs("Songs") }

private enum class MusicSort(val label: String, val sortBy: List<ItemSortBy>, val order: SortOrder) {
    Name("Name", listOf(ItemSortBy.SORT_NAME), SortOrder.ASCENDING),
    DateAdded("Recently Added", listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME), SortOrder.DESCENDING),
    Artist("Artist", listOf(ItemSortBy.ALBUM_ARTIST, ItemSortBy.SORT_NAME), SortOrder.ASCENDING),
    Year("Year", listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME), SortOrder.DESCENDING),
}

@Composable
private fun app() = LocalContext.current.applicationContext as AsterApp

/** "3:15" for a song's length. */
fun BaseItemDto.trackDuration(): String? = runTimeTicks?.takeIf { it > 0 }?.let { com.astermediaplayer.astermediaplayer.data.Audiobook.clock(it / 10_000_000.0) }

fun BaseItemDto.artistText(): String? = artists?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: albumArtist

/** A song's, album's or artist's picture: its own, else its album's. */
@Composable
fun MusicArt(item: BaseItemDto, size: Dp, modifier: Modifier = Modifier, width: Int = 500, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Lockup.cornerRadius)) {
    val repo = app().repository
    val url = repo.imageUrl(item, ImageType.PRIMARY, width) ?: item.albumId?.let { id -> item.albumPrimaryImageTag?.let { repo.imageUrlFor(id, it, width) } }
    Box(modifier.size(size).clip(shape).background(Color(0xFF1C1C1E)), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(model = url, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(if (item.type == BaseItemKind.PLAYLIST) Icons.Default.QueueMusic else Icons.Default.MusicNote, null, tint = SecondaryText)
    }
}

/** A list fetched a page at a time. */
private class Paged(private val fetch: suspend (Int) -> ItemPage) {
    var items by mutableStateOf<List<BaseItemDto>>(emptyList())
    var total by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val hasMore get() = items.size < total

    suspend fun reset() { items = emptyList(); total = 0; loaded = false; error = null; more() }

    suspend fun more() {
        if (loading || (loaded && !hasMore)) return
        loading = true
        try {
            val page = fetch(items.size)
            items = items + page.items.filter { n -> items.none { it.id == n.id } }
            total = if (page.items.isEmpty()) items.size else page.total
        } catch (e: Exception) { if (items.isEmpty()) error = e.message ?: "Couldn't load" }
        loaded = true
        loading = false
    }
}

// MARK: Music tab

@Composable
fun MusicScreen(onAlbum: (BaseItemDto) -> Unit, onArtist: (BaseItemDto) -> Unit, modifier: Modifier = Modifier, topInset: Dp = 0.dp) {
    val app = app()
    val repo = app.repository
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf(MusicSection.Albums) }
    var sort by remember { mutableStateOf(MusicSort.Name) }
    var recentAlbums by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var recentlyPlayed by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    LaunchedEffect(Unit) {
        recentAlbums = runCatching { repo.latest(null, 20, listOf(BaseItemKind.MUSIC_ALBUM)) }.getOrDefault(emptyList())
        recentlyPlayed = runCatching { repo.musicPage(BaseItemKind.AUDIO, 0, 20, listOf(ItemSortBy.DATE_PLAYED), SortOrder.DESCENDING, played = true).items }.getOrDefault(emptyList())
        favorites = runCatching { repo.musicPage(BaseItemKind.MUSIC_ALBUM, 0, 20, favorites = true).items }.getOrDefault(emptyList())
    }
    val albums = remember(sort) { Paged { repo.musicPage(BaseItemKind.MUSIC_ALBUM, it, 100, sort.sortBy, sort.order) } }
    val artists = remember { Paged { repo.albumArtists(it) } }
    val playlists = remember { Paged { repo.musicPage(BaseItemKind.PLAYLIST, it, 100) } }
    val songs = remember { Paged { repo.musicPage(BaseItemKind.AUDIO, it, 100) } }
    val current = when (section) { MusicSection.Albums -> albums; MusicSection.Artists -> artists; MusicSection.Playlists -> playlists; MusicSection.Songs -> songs }
    LaunchedEffect(current) { if (!current.loaded) current.reset() }

    LazyColumn(modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(top = 8.dp + topInset, bottom = com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Column(Modifier.padding(horizontal = Lockup.screenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Music", style = Type.pageTitle.copy(fontSize = 34.sp, lineHeight = 41.sp))
                Segmented(MusicSection.entries.map { it.label }, section.ordinal) { section = MusicSection.entries[it] }
            }
        }
        if (section == MusicSection.Albums) {
            if (recentAlbums.isNotEmpty()) item { SquareShelf("Recently Added", recentAlbums) { onAlbum(it) } }
            if (recentlyPlayed.isNotEmpty()) item { SquareShelf("Recently Played", recentlyPlayed) { song -> app.music.play(recentlyPlayed, recentlyPlayed.indexOf(song)) } }
            if (favorites.isNotEmpty()) item { SquareShelf("Favorite Albums", favorites) { onAlbum(it) } }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = Lockup.screenPadding).padding(top = 20.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("All Albums", style = Type.title3)
                    SortMenu(sort) { sort = it }
                }
            }
        } else item { Text(section.label, style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding).padding(top = 20.dp)) }

        when {
            current.items.isEmpty() && current.error != null -> item { Message(current.error!!) }
            current.items.isEmpty() && current.loaded -> item { Message("No ${section.label.lowercase()} yet") }
            section == MusicSection.Songs -> items(current.items.size, key = { current.items[it].id }) { i ->
                val song = current.items[i]
                if (i >= current.items.size - 10) LaunchedEffect(current.items.size) { scope.launch { current.more() } }
                TrackRow(song, null, showArtist = true, showArt = true, Modifier.padding(horizontal = Lockup.screenPadding)) { app.music.play(current.items, i) }
            }
            else -> {
                val perRow = 3
                val chunks = current.items.chunked(3)
                items(chunks.size) { i ->
                    if (i >= chunks.size - 3) LaunchedEffect(current.items.size) { scope.launch { current.more() } }
                    SquareRow(chunks[i], artist = section == MusicSection.Artists, onClick = { if (section == MusicSection.Artists) onArtist(it) else onAlbum(it) })
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) = Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { Text(text, color = SecondaryText) }

@Composable
private fun SortMenu(sort: MusicSort, onSort: (MusicSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clip(CircleShape).clickable { open = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(sort.label, style = Type.callout, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Default.UnfoldMore, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MusicSort.entries.forEach { o -> DropdownMenuItem(text = { Text(if (o == sort) "✓ ${o.label}" else o.label) }, onClick = { open = false; onSort(o) }) }
        }
    }
}

@Composable
private fun SquareShelf(title: String, items: List<BaseItemDto>, onClick: (BaseItemDto) -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Lockup.shelfTitleSpacing)) {
        Text(title, style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding))
        LazyRow(contentPadding = PaddingValues(horizontal = Lockup.screenPadding, vertical = Lockup.shelfVerticalPadding), horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing)) {
            items(items, key = { it.id }) { SquareLockup(it) { onClick(it) } }
        }
    }
}

@Composable
private fun SquareRow(row: List<BaseItemDto>, artist: Boolean, onClick: (BaseItemDto) -> Unit) {
    Row(Modifier.padding(horizontal = Lockup.screenPadding), horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing)) {
        row.forEach { if (artist) ArtistLockup(it) { onClick(it) } else SquareLockup(it) { onClick(it) } }
    }
}

/** Square artwork with a title underneath: albums, playlists and songs. */
@Composable
fun SquareLockup(item: BaseItemDto, size: Dp = Lockup.posterWidth, onClick: () -> Unit) {
    val subtitle = when (item.type) {
        BaseItemKind.MUSIC_ALBUM -> item.artistText() ?: item.productionYear?.toString()
        BaseItemKind.AUDIO -> item.artistText() ?: item.album
        BaseItemKind.PLAYLIST -> item.childCount?.let { "$it songs" }
        else -> null
    }
    Column(Modifier.width(size).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(Lockup.cardLabelSpacing)) {
        MusicArt(item, size)
        Column(verticalArrangement = Arrangement.spacedBy(Lockup.cardTextSpacing)) {
            Text(item.name.orEmpty(), style = Type.calloutMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = Type.caption, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Round artwork for an artist. */
@Composable
fun ArtistLockup(artist: BaseItemDto, size: Dp = 100.dp, onClick: () -> Unit) {
    Column(Modifier.width(size + 10.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MusicArt(artist, size, shape = CircleShape)
        Text(artist.name.orEmpty(), style = Type.calloutMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One song in a list: number or art, title, artist and length. */
@Composable
fun TrackRow(track: BaseItemDto, number: Int?, showArtist: Boolean, showArt: Boolean = false, modifier: Modifier = Modifier, isCurrent: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = if (isCurrent) 0.12f else 0.06f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showArt) MusicArt(track, 44.dp, width = 200, shape = RoundedCornerShape(8.dp))
        else Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) {
            if (isCurrent) Icon(Icons.Default.VolumeUp, null, tint = SecondaryText, modifier = Modifier.size(18.dp))
            else number?.let { Text("$it", style = Type.calloutMedium, color = SecondaryText) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(track.name.orEmpty(), style = Type.calloutMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showArtist) track.artistText()?.let { Text(it, style = Type.caption, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (track.userData?.isFavorite == true) Icon(Icons.Default.Favorite, null, tint = SecondaryText, modifier = Modifier.size(14.dp))
        track.trackDuration()?.let { Text(it, style = Type.callout, color = SecondaryText) }
    }
}

// MARK: Album, playlist and artist pages

@Composable
private fun PageButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, prominent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.height(DetailMetrics.buttonHeight).alpha(if (enabled) 1f else 0.4f).clip(CircleShape).background(if (prominent) Color.White else ButtonGrey)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = DetailMetrics.buttonHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val c = if (prominent) Color.Black else Color.White
        icon?.let { Icon(it, null, tint = c, modifier = Modifier.size(17.dp)) }
        Text(label, style = Type.headline, color = c, maxLines = 1)
    }
}

@Composable
private fun IconPill(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(DetailMetrics.iconButtonWidth, DetailMetrics.buttonHeight).clip(CircleShape).background(ButtonGrey).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** An album or playlist: artwork, details, Play and Shuffle, and the songs. */
@Composable
fun AlbumPage(itemId: UUID, modifier: Modifier = Modifier) {
    val app = app()
    val repo = app.repository
    val music by app.music.state.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<BaseItemDto?>(null) }
    var tracks by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(itemId) {
        val full = runCatching { repo.item(itemId) }.getOrNull()
        detail = full
        tracks = runCatching { if (full?.type == BaseItemKind.PLAYLIST) repo.playlistItems(itemId) else repo.albumTracks(itemId) }.getOrDefault(emptyList())
        loading = false
    }
    val item = detail ?: return
    val isPlaylist = item.type == BaseItemKind.PLAYLIST
    val total = tracks.sumOf { it.runTimeTicks ?: 0L } / 10_000_000.0
    val info = listOfNotNull(item.productionYear?.toString(), item.genres?.firstOrNull(), "${tracks.size} song${if (tracks.size == 1) "" else "s"}", com.astermediaplayer.astermediaplayer.data.Audiobook.lengthText(total)).joinToString(" · ")
    val discs = tracks.mapNotNull { it.parentIndexNumber }.toSet().sorted().takeIf { it.size > 1 }
    var favorite by remember(item.id) { mutableStateOf(item.userData?.isFavorite == true) }
    val scope = rememberCoroutineScope()

    Box(modifier.fillMaxSize()) {
        MusicBackdrop(item)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 64.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
            Column(Modifier.padding(horizontal = Lockup.screenPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MusicArt(item, 180.dp, Modifier.shadow(30.dp, RoundedCornerShape(16.dp)), 800, RoundedCornerShape(16.dp))
                Text(item.name.orEmpty(), style = Type.pageTitle.copy(fontSize = 26.sp, lineHeight = 31.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.artistText()?.let { Text(it, style = Type.title3, color = SecondaryText) }
                Text(info, style = Type.callout, color = SecondaryText)
                item.overview?.takeIf { it.isNotEmpty() }?.let { Text(it, style = Type.callout, color = SecondaryText, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(DetailMetrics.actionSpacing)) {
                        PageButton("Play", Icons.Default.PlayArrow, prominent = true, enabled = tracks.isNotEmpty()) { app.music.play(tracks, 0) }
                        PageButton("Shuffle", Icons.Default.Shuffle, enabled = tracks.isNotEmpty()) { app.music.play(tracks, tracks.indices.random(), shuffled = true) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(DetailMetrics.actionSpacing)) {
                        PageButton("Instant Mix", Icons.Default.AutoFixHigh, enabled = tracks.isNotEmpty()) {
                            scope.launch { app.music.play(runCatching { repo.instantMix(itemId) }.getOrDefault(tracks)) }
                        }
                        IconPill(if (favorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, "Favorite") {
                            favorite = !favorite
                            scope.launch { runCatching { repo.setFavorite(itemId, favorite) } }
                        }
                    }
                }
            }
            when {
                tracks.isEmpty() && !loading -> Message(if (isPlaylist) "This playlist is empty" else "No songs found")
                discs == null -> TrackList(tracks, tracks, item, isPlaylist, music.current?.id, null) { app.music.play(tracks, it) }
                else -> discs.forEach { d -> TrackList(tracks.filter { it.parentIndexNumber == d }, tracks, item, isPlaylist, music.current?.id, "Disc $d") { app.music.play(tracks, it) } }
            }
        }
    }
}

@Composable
private fun TrackList(songs: List<BaseItemDto>, all: List<BaseItemDto>, album: BaseItemDto, isPlaylist: Boolean, currentId: UUID?, title: String?, onPlay: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = Lockup.screenPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.let { Text(it, style = Type.title3, modifier = Modifier.padding(bottom = 8.dp)) }
        songs.forEach { song ->
            val position = all.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
            TrackRow(song, if (isPlaylist) position + 1 else song.indexNumber, showArtist = isPlaylist || song.artistText() != album.artistText(), isCurrent = currentId == song.id) { onPlay(position) }
        }
    }
}

@Composable
private fun MusicBackdrop(item: BaseItemDto) {
    val repo = app().repository
    AsyncImage(model = repo.imageUrl(item, ImageType.PRIMARY, 400), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(80.dp))
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)))
}

/** An artist: a round photo, Play, Shuffle and Instant Mix, and their albums. */
@Composable
fun ArtistPage(artistId: UUID, onAlbum: (BaseItemDto) -> Unit, modifier: Modifier = Modifier) {
    val app = app()
    val repo = app.repository
    var detail by remember { mutableStateOf<BaseItemDto?>(null) }
    var albums by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(artistId) {
        detail = runCatching { repo.item(artistId) }.getOrNull()
        albums = runCatching { repo.artistAlbums(artistId) }.getOrDefault(emptyList())
        loading = false
    }
    val item = detail ?: return
    var favorite by remember(item.id) { mutableStateOf(item.userData?.isFavorite == true) }
    fun playAll(shuffled: Boolean) = scope.launch {
        val songs = runCatching { repo.artistTracks(artistId) }.getOrDefault(emptyList())
        app.music.play(songs, if (shuffled) songs.indices.randomOrNull() ?: 0 else 0, shuffled)
    }
    Box(modifier.fillMaxSize()) {
        MusicBackdrop(item)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 64.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
            Column(Modifier.padding(horizontal = Lockup.screenPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MusicArt(item, 140.dp, Modifier.shadow(30.dp, CircleShape), 600, CircleShape)
                Text(item.name.orEmpty(), style = Type.pageTitle.copy(fontSize = 26.sp, lineHeight = 31.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.genres?.takeIf { it.isNotEmpty() }?.let { Text(it.take(3).joinToString(" · "), style = Type.callout, color = SecondaryText) }
                item.overview?.takeIf { it.isNotEmpty() }?.let { Text(it, style = Type.callout, color = SecondaryText, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(DetailMetrics.actionSpacing)) {
                        PageButton("Play", Icons.Default.PlayArrow, prominent = true) { playAll(false) }
                        PageButton("Shuffle", Icons.Default.Shuffle) { playAll(true) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(DetailMetrics.actionSpacing)) {
                        PageButton("Instant Mix", Icons.Default.AutoFixHigh) { scope.launch { app.music.play(runCatching { repo.instantMix(artistId) }.getOrDefault(emptyList())) } }
                        IconPill(if (favorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, "Favorite") { favorite = !favorite; scope.launch { runCatching { repo.setFavorite(artistId, favorite) } } }
                    }
                }
            }
            if (albums.isEmpty() && !loading) Message("No albums found") else SquareShelf("Albums", albums, onAlbum)
        }
    }
}
