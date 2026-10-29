package com.astermediaplayer.astermediaplayer.ui.audiobooks

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.itemsIndexed
import com.astermediaplayer.astermediaplayer.ui.components.cardClickable
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.layout.BoxScope
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import com.astermediaplayer.astermediaplayer.ui.theme.LocalTvChip
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.Audiobook
import com.astermediaplayer.astermediaplayer.data.AudiobookShelf
import com.astermediaplayer.astermediaplayer.ui.components.ErrorBox
import com.astermediaplayer.astermediaplayer.ui.home.TabBarClearance
import com.astermediaplayer.astermediaplayer.ui.theme.ButtonGrey
import com.astermediaplayer.astermediaplayer.ui.theme.DetailMetrics
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import java.util.UUID

private enum class AudiobookSection(val label: String) { Books("Books"), Authors("Authors"), Series("Series") }

@Composable
private fun app() = LocalContext.current.applicationContext as AsterApp

/** A book's cover, square. */
@Composable
fun AudiobookCover(book: Audiobook, size: Dp, modifier: Modifier = Modifier, width: Int = 500) {
    AsyncImage(
        model = app().downloads.cover(book, width), contentDescription = book.title,
        contentScale = ContentScale.Crop, modifier = modifier.size(size).background(Color(0xFF1C1C1E)),
    )
}

/** The Apple apps' progress bar: a white capsule on a faint track. */
@Composable
private fun Progress(fraction: Double, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0.03f, 1f)).height(height).clip(CircleShape).background(Color.White))
    }
}

// MARK: Audiobooks tab

/** What's being listened to, what's new, and every book by title, author or series. */
@Composable
fun AudiobooksScreen(
    onBook: (Audiobook) -> Unit,
    onShelf: (AudiobookShelf) -> Unit,
    onListen: (Audiobook) -> Unit,
    modifier: Modifier = Modifier,
    /** Room above the title for a back button, when the page was opened from a list. */
    topInset: Dp = 0.dp,
) {
    val library = app().audiobookLibrary
    val state by library.state.collectAsStateWithLifecycle()
    val playing by app().audiobooks.state.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf(AudiobookSection.Books) }
    // Again each time the tab shows, so listening done elsewhere (or just now) moves Continue Listening along.
    LaunchedEffect(playing.book?.id) { library.load() }

    val featured = state.featured
    val hasHero = state.loaded && featured.isNotEmpty()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = TabBarClearance),
        verticalArrangement = Arrangement.spacedBy(if (LocalIsTv.current) Lockup.sectionSpacing else 20.dp),
    ) {
        // Down from the hero's button lands on the first book below it, not the section picker (as on the Apple TV).
        val firstBook = remember { FocusRequester() }
        if (hasHero) AudiobookHero(featured, onListen, downTarget = if (state.continueListening.isNotEmpty() || state.recentlyAdded.isNotEmpty()) firstBook else null)
        if (state.loaded || state.books.isNotEmpty()) {
            if (LocalIsTv.current) {
                // The Apple TV's header: the title with the section picker beside it, 740 wide.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Lockup.screenPadding).then(if (hasHero) Modifier else Modifier.padding(top = (40f * Lockup.tvUnit).dp)),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Audiobooks", style = Type.pageTitle.copy(fontSize = (76f * Lockup.tvUnit).sp, lineHeight = (90f * Lockup.tvUnit).sp))
                    Segmented(AudiobookSection.entries.map { it.label }, section.ordinal) { section = AudiobookSection.entries[it] }
                }
            } else Column(
                Modifier.padding(horizontal = Lockup.screenPadding).then(if (hasHero) Modifier else Modifier.statusBarsPadding().padding(top = 8.dp + topInset)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Audiobooks", style = Type.pageTitle.copy(fontSize = 34.sp, lineHeight = 41.sp))
                Segmented(AudiobookSection.entries.map { it.label }, section.ordinal) { section = AudiobookSection.entries[it] }
            }
        }
        when {
            state.books.isEmpty() && state.error != null -> ErrorBox(state.error!!, null, Modifier.height(320.dp))
            state.books.isEmpty() && state.loaded -> Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { Text("No audiobooks yet", color = SecondaryText) }
            state.books.isEmpty() -> Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> when (section) {
                AudiobookSection.Books -> {
                    val firstIsContinue = state.continueListening.isNotEmpty()
                    BookShelf("Continue Listening", state.continueListening, onBook, if (firstIsContinue) firstBook else null)
                    BookShelf("Recently Added", state.recentlyAdded, onBook, if (!firstIsContinue) firstBook else null)
                    Text("All Books", style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding).padding(top = 20.dp))
                    BookGrid(state.books, onBook)
                }
                AudiobookSection.Authors -> {
                    Text("Authors", style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding).padding(top = 20.dp))
                    ShelfGrid(state.authors, onShelf)
                }
                AudiobookSection.Series -> {
                    Text("Series", style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding).padding(top = 20.dp))
                    ShelfGrid(state.series, onShelf)
                }
            }
        }
    }
}

/** The system segmented control, as on the title pages. */
@Composable
fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    if (LocalIsTv.current) { TvSegmented(labels, selected, onSelect); return }
    Row(
        Modifier.widthIn(max = DetailMetrics.segmentedMaxWidth).fillMaxWidth().height(DetailMetrics.segmentedHeight)
            .clip(CircleShape).background(Color.White.copy(alpha = 0.19f)).padding(2.dp),
    ) {
        labels.forEachIndexed { index, label ->
            Box(
                Modifier.weight(1f).fillMaxSize().clip(CircleShape)
                    .background(if (index == selected) Color.White.copy(alpha = 0.21f) else Color.Transparent).clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = Type.segmented, color = Color.White, maxLines = 1) }
        }
    }
}

@Composable
private fun BookShelf(title: String, books: List<Audiobook>, onBook: (Audiobook) -> Unit, firstFocus: FocusRequester? = null) {
    if (books.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Lockup.shelfTitleSpacing)) {
        Text(title, style = Type.title3, modifier = Modifier.padding(horizontal = Lockup.screenPadding))
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Lockup.screenPadding, vertical = Lockup.shelfVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(Lockup.shelfSpacing),
        ) { itemsIndexed(books, key = { _, b -> b.id }) { i, b -> BookLockup(b, modifier = if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier, onClick = { onBook(b) }) } }
    }
}

/** A grid of covers: as many columns as fit. */
@Composable
fun BookGrid(books: List<Audiobook>, onBook: (Audiobook) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val perRow = maxOf(1, ((maxWidth - Lockup.screenPadding * 2 + Lockup.gridSpacing) / (Lockup.posterWidth + Lockup.gridSpacing)).toInt())
        Column(Modifier.padding(horizontal = Lockup.screenPadding).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(Lockup.gridRowSpacing)) {
            books.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Lockup.gridSpacing)) { row.forEach { BookLockup(it, onClick = { onBook(it) }) } }
            }
        }
    }
}

/** A book's cover, how far through it is, its title and author. */
@Composable
fun BookLockup(book: Audiobook, size: Dp = Lockup.posterWidth, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val subtitle = if (book.seriesIndex != null && book.series != null) "${book.series} ${book.seriesIndex}" else book.author
    Column(modifier.width(size).tvFocus(Lockup.cornerRadius, lift = 1.08f, ring = false, pictureAspect = 1f).cardClickable(onClick), verticalArrangement = Arrangement.spacedBy(Lockup.cardLabelSpacing)) {
        Box(Modifier.clip(RoundedCornerShape(Lockup.cornerRadius))) {
            AudiobookCover(book, size)
            if (book.progress > 0 && !book.isFinished) Progress(book.progress, Modifier.align(Alignment.BottomCenter).padding(10.dp), 5.dp)
            if (book.isFinished) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(Lockup.cardTextSpacing)) {
            Text(book.title, style = Type.calloutMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = Type.caption, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Authors or series: the first book's cover, the name and how many books. */
@Composable
private fun ShelfGrid(shelves: List<AudiobookShelf>, onShelf: (AudiobookShelf) -> Unit) {
    if (shelves.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { Text("Nothing here yet", color = SecondaryText) }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val perRow = maxOf(1, ((maxWidth - Lockup.screenPadding * 2 + Lockup.gridSpacing) / (Lockup.posterWidth + Lockup.gridSpacing)).toInt())
        Column(Modifier.padding(horizontal = Lockup.screenPadding).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(Lockup.gridRowSpacing)) {
            shelves.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Lockup.gridSpacing)) { row.forEach { ShelfLockup(it) { onShelf(it) } } }
            }
        }
    }
}

@Composable
private fun ShelfLockup(shelf: AudiobookShelf, size: Dp = Lockup.posterWidth, onClick: () -> Unit) {
    Column(Modifier.width(size).tvFocus(Lockup.cornerRadius, lift = 1.08f, ring = false, pictureAspect = 1f).cardClickable(onClick), verticalArrangement = Arrangement.spacedBy(Lockup.cardLabelSpacing)) {
        Box(Modifier.size(size)) {
            // A second cover peeking out behind, for a stack of books.
            if (shelf.books.size > 1) AudiobookCover(shelf.books[1], size * 0.9f, Modifier.offset(size * 0.05f, -size * 0.05f).alpha(0.5f).clip(RoundedCornerShape(Lockup.cornerRadius)), 400)
            AudiobookCover(
                shelf.books[0], size * 0.9f,
                Modifier.offset(-size * 0.03f, size * 0.03f).shadow(8.dp, RoundedCornerShape(Lockup.cornerRadius)).clip(RoundedCornerShape(Lockup.cornerRadius)),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Lockup.cardTextSpacing)) {
            Text(shelf.name, style = Type.calloutMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${shelf.books.size} book${if (shelf.books.size == 1) "" else "s"}", style = Type.caption, color = SecondaryText)
        }
    }
}

// MARK: Hero

/** A book as a page of the Audiobooks hero: its cover, blurred, fills the banner behind the cover itself. */
@Composable
private fun AudiobookHero(books: List<Audiobook>, onListen: (Audiobook) -> Unit, downTarget: FocusRequester? = null) {
    val pager = rememberPagerState { books.size }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val tv = LocalIsTv.current
    val u = Lockup.tvUnit
    val scope = rememberCoroutineScope()
    // A remote pages the hero with Left and Right, and the page it lands on takes focus on its button (as on Home).
    var refocusPage by remember { mutableStateOf(-1) }
    val chip = LocalTvChip.current
    androidx.compose.runtime.DisposableEffect(chip) { onDispose { chip?.value = true } }
    Box(
        Modifier.fillMaxWidth().height(windowHeight).then(
            if (!tv) Modifier else Modifier.onFocusChanged { chip?.value = it.hasFocus }.onKeyEvent { event ->
                val step = when (event.key) { Key.DirectionRight -> 1; Key.DirectionLeft -> -1; else -> 0 }
                val target = pager.currentPage + step
                if (step == 0 || target !in books.indices) false
                else { if (event.type == KeyEventType.KeyDown) { refocusPage = target; scope.launch { pager.animateScrollToPage(target) } }; true }
            },
        ),
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            HeroPage(books[page], onListen, autofocus = page == 0, focusNow = page == refocusPage && page == pager.settledPage, onFocused = { refocusPage = -1 }, downTarget = downTarget)
        }
        if (books.size > 1) {
            Row(
                if (tv) Modifier.align(Alignment.BottomEnd).padding(end = (86f * u).dp, bottom = (46f * u).dp)
                else Modifier.align(Alignment.BottomEnd).padding(end = Lockup.screenPadding, bottom = 84.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(if (tv) (11f * u).dp else 10.dp),
            ) { repeat(books.size) { i -> Box(Modifier.size(if (tv) (9f * u).dp else 10.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == pager.currentPage) 0.95f else 0.35f))) } }
        }
    }
}

@Composable
private fun HeroPage(book: Audiobook, onListen: (Audiobook) -> Unit, autofocus: Boolean = false, focusNow: Boolean = false, onFocused: () -> Unit = {}, downTarget: FocusRequester? = null) {
    val tv = LocalIsTv.current
    val focus = remember { FocusRequester() }
    if (tv && autofocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    if (tv) LaunchedEffect(focusNow) { if (focusNow) { runCatching { focus.requestFocus() }; onFocused() } }
    val stacked = LocalConfiguration.current.screenWidthDp < LocalConfiguration.current.screenHeightDp
    val timeText = if (book.progress > 0 && !book.isFinished) Audiobook.lengthText(book.duration * (1 - book.progress))?.let { "$it left" } else book.lengthText
    // The page itself is no stop for a remote; its button is.
    Box(Modifier.fillMaxSize().focusProperties { canFocus = !tv }.clickable { onListen(book) }) {
        // The cover, blurred and darkened, as the backdrop.
        AsyncImage(
            model = app().downloads.cover(book, 600), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(60.dp),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.675f to Color.Black.copy(alpha = 0.2f), 1f to Color.Black.copy(alpha = 0.9f))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.5f), 0.5f to Color.Transparent)))
        if (tv) { TvHeroBody(book, timeText, focus, downTarget, onListen); return@Box }
        val body: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                book.series?.let {
                    Text(
                        listOfNotNull(it, book.seriesIndex?.let { i -> "Book $i" }).joinToString(" · ").uppercase(),
                        style = Type.headline.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp), color = Color.White.copy(alpha = 0.75f),
                    )
                }
                Text(book.title, style = Type.pageTitle.copy(fontSize = 32.sp, lineHeight = 38.sp), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                book.author?.let { Text(it, style = Type.title3, color = Color.White.copy(alpha = 0.85f)) }
                if (book.progress > 0 && !book.isFinished) Progress(book.progress, Modifier.widthIn(max = 360.dp))
                book.overview?.let { Text(it, style = Type.callout, color = SecondaryText, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 560.dp)) }
                Row(
                    Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.9f)).clickable { onListen(book) }.padding(horizontal = 28.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(17.dp))
                    Text(if (book.resumePoint != null) "Resume" else "Listen", style = Type.headline, color = Color.Black)
                    timeText?.let { Text("·  $it", style = Type.headline.copy(fontWeight = FontWeight.Normal), color = Color.Black.copy(alpha = 0.5f)) }
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = Lockup.screenPadding).padding(bottom = 125.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // A phone held upright has no room for the cover beside the text, so the cover goes above it, smaller.
            AudiobookCover(book, if (stacked) 150.dp else 200.dp, Modifier.shadow(30.dp, RoundedCornerShape(Lockup.cornerRadius)).clip(RoundedCornerShape(Lockup.cornerRadius)), 900)
            body()
        }
    }
}

// MARK: An author's or series' books

@Composable
fun AudiobookShelfPage(shelfId: String, onBook: (Audiobook) -> Unit, modifier: Modifier = Modifier) {
    val state by app().audiobookLibrary.state.collectAsStateWithLifecycle()
    val shelf = state.shelf(shelfId) ?: return
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 56.dp, bottom = Lockup.pageBottomPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(shelf.name, style = Type.pageTitle.copy(fontSize = 34.sp, lineHeight = 41.sp), modifier = Modifier.padding(horizontal = Lockup.screenPadding))
        Text(
            if (shelf.kind == AudiobookShelf.Kind.Series) "Series · ${shelf.books.size} books" else "${shelf.books.size} book${if (shelf.books.size == 1) "" else "s"}",
            style = Type.callout, color = SecondaryText, modifier = Modifier.padding(horizontal = Lockup.screenPadding),
        )
        BookGrid(shelf.books, onBook)
    }
}

// MARK: A book's page

@Composable
fun AudiobookPage(bookId: UUID, modifier: Modifier = Modifier) {
    val app = app()
    val library = app.audiobookLibrary
    val state by library.state.collectAsStateWithLifecycle()
    val playing by app.audiobooks.state.collectAsStateWithLifecycle()
    // A downloaded book opens with no connection, from the copy saved with it.
    val listed = remember(bookId, state.loaded) { state.book(bookId) ?: app.downloads.savedBook(bookId) } ?: return
    // The parts' listening state, fresh from the server, again once the player lets go of the book.
    val isLoaded = playing.book?.id == bookId
    var book by remember(bookId) { mutableStateOf(listed) }
    // With no connection, the place listening got to is the one kept on the device with the download.
    LaunchedEffect(bookId, isLoaded) {
        val fresh = library.refresh(book)
        book = if (fresh === book) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.downloads.savedBook(bookId) } ?: book else fresh
    }
    val player = app.audiobooks

    val elapsed = if (isLoaded) playing.bookElapsed else book.resumePoint?.let { book.offsetOfPart(it.first) + it.second } ?: if (book.isFinished) book.duration else 0.0
    val infoLine = listOfNotNull(
        if (book.seriesIndex != null && book.series != null) "Book ${book.seriesIndex} of ${book.series}" else book.series,
        book.year?.toString(), book.lengthText,
    ).joinToString(" · ")
    val progressText = when {
        book.isFinished && !isLoaded -> "Finished"
        elapsed > 0 && book.duration > 0 -> (Audiobook.lengthText((book.duration - elapsed).coerceAtLeast(0.0)) ?: "") + " left"
        else -> null
    }

    Box(modifier.fillMaxSize()) {
        AsyncImage(model = app.downloads.cover(book, 400), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(80.dp))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 64.dp, bottom = Lockup.pageBottomPadding), verticalArrangement = Arrangement.spacedBy(30.dp)) {
            Column(Modifier.padding(horizontal = Lockup.screenPadding), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AudiobookCover(book, 240.dp, Modifier.shadow(30.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)), 800)
                Text(book.title, style = Type.pageTitle, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                book.author?.let { Text(it, style = Type.title3, color = SecondaryText) }
                Text(infoLine, style = Type.callout, color = SecondaryText, textAlign = TextAlign.Center)
                progressText?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Progress(if (book.duration > 0) elapsed / book.duration else 0.0, Modifier.width(200.dp), 5.dp)
                        Text(it, style = Type.caption, color = SecondaryText)
                    }
                }
                book.overview?.takeIf { it.isNotEmpty() }?.let { Text(it, style = Type.callout, color = SecondaryText, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(DetailMetrics.actionSpacing)) {
                    PillButton(
                        if (isLoaded) (if (playing.isPlaying) "Pause" else "Resume") else if (book.resumePoint != null) "Resume" else "Play",
                        prominent = true,
                    ) {
                        if (isLoaded) { player.togglePlayPause(); player.setPresented(true) } else player.play(book)
                    }
                    // Downloads are for a phone or tablet, as on the Apple apps; a TV has none.
                    if (!LocalIsTv.current) DownloadPill(book)
                    if (book.resumePoint != null || isLoaded) PillButton("From the Beginning") { player.play(book, book.chapters.firstOrNull() ?: com.astermediaplayer.astermediaplayer.data.AudiobookChapter(0, 0, 0.0, "")) }
                }
            }
            if (book.chapters.isNotEmpty()) {
                Column(Modifier.padding(horizontal = Lockup.screenPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Chapters", style = Type.title3, modifier = Modifier.padding(bottom = 8.dp))
                    book.chapters.forEach { chapter ->
                        ChapterRow(chapter.title, Audiobook.clock(book.offsetOfPart(chapter.part) + chapter.startSeconds), isLoaded && playing.chapter?.id == chapter.id) {
                            player.play(book, chapter)
                        }
                    }
                }
            }
        }
    }
}

/** Download, its progress, or Remove Download, for a book. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun DownloadPill(book: Audiobook) {
    val app = app()
    val records by app.downloads.records.collectAsStateWithLifecycle()
    val saved = records.firstOrNull { it.id == book.id.toString() }
    val flow = com.astermediaplayer.astermediaplayer.ui.downloads.rememberDownloadFlow()
    var confirm by remember { mutableStateOf(false) }
    val label = when (saved?.state) {
        null -> "Download"
        com.astermediaplayer.astermediaplayer.data.downloads.DownloadState.Downloading -> "${(saved.progress * 100).toInt()}%"
        com.astermediaplayer.astermediaplayer.data.downloads.DownloadState.Failed -> "Resume"
        com.astermediaplayer.astermediaplayer.data.downloads.DownloadState.Finished -> "Downloaded"
    }
    PillButton(label) {
        when (saved?.state) {
            null -> flow.downloadBook(book)
            com.astermediaplayer.astermediaplayer.data.downloads.DownloadState.Failed -> flow.retry(saved.id)
            else -> confirm = true
        }
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (saved?.state == com.astermediaplayer.astermediaplayer.data.downloads.DownloadState.Downloading) "Cancel the download?" else "Remove the download?") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { app.downloads.remove(book.id.toString()); confirm = false }) { Text("Remove") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Keep") } },
        )
    }
    flow.Dialogs()
}

@Composable
private fun PillButton(label: String, prominent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(DetailMetrics.buttonHeight).clip(CircleShape).background(if (prominent) Color.White else ButtonGrey).clickable(onClick = onClick)
            .padding(horizontal = DetailMetrics.buttonHorizontalPadding),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Type.headline, color = if (prominent) Color.Black else Color.White, maxLines = 1) }
}

@Composable
fun ChapterRow(title: String, startText: String, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = if (isCurrent) 0.12f else 0.06f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(24.dp)) { if (isCurrent) Icon(Icons.Default.VolumeUp, null, tint = SecondaryText, modifier = Modifier.size(18.dp)) }
        Text(title, style = if (isCurrent) Type.calloutSemibold else Type.calloutMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(startText, style = Type.callout, color = SecondaryText)
    }
}

/**
 * An audiobook hero page on a TV, to the Apple TV's `AudiobookHeroCard` (its measurements, in points): a 420 cover beside the text,
 * 48 apart, the foot 86 above the bottom and 72 in from the left.
 */
@Composable
private fun BoxScope.TvHeroBody(book: Audiobook, timeText: String?, focus: FocusRequester, downTarget: FocusRequester?, onListen: (Audiobook) -> Unit) {
    val u = Lockup.tvUnit
    fun pt(points: Float) = (points * u).dp
    fun sp(points: Float) = (points * u).sp
    Row(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = pt(72f), bottom = pt(86f)),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(pt(48f)),
    ) {
        AudiobookCover(book, pt(420f), Modifier.shadow(30.dp, RoundedCornerShape(Lockup.cornerRadius)).clip(RoundedCornerShape(Lockup.cornerRadius)), 900)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(pt(16f))) {
            book.series?.let {
                Text(
                    listOfNotNull(it, book.seriesIndex?.let { i -> "Book $i" }).joinToString(" · ").uppercase(),
                    style = Type.headline.copy(fontWeight = FontWeight.Bold, fontSize = sp(34f), lineHeight = sp(42f), letterSpacing = 1.5.sp), color = Color.White.copy(alpha = 0.75f), maxLines = 1,
                )
            }
            Text(book.title, style = Type.pageTitle.copy(fontSize = sp(60f), lineHeight = sp(70f)), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            book.author?.let { Text(it, style = Type.title3.copy(fontWeight = FontWeight.SemiBold), color = Color.White.copy(alpha = 0.85f), maxLines = 1) }
            if (book.progress > 0 && !book.isFinished) Progress(book.progress, Modifier.widthIn(max = pt(360f)), height = pt(6f))
            book.overview?.let { Text(it, style = Type.callout, color = SecondaryText, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = pt(900f))) }
            Row(
                Modifier.focusRequester(focus).then(if (downTarget != null) Modifier.focusProperties { down = downTarget } else Modifier).tvFocus(pt(36f)).height(pt(72f)).clip(CircleShape).background(Color.White).clickable { onListen(book) }.padding(horizontal = pt(32f)),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(pt(16f)),
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(pt(42f)))
                Text(if (book.resumePoint != null) "Resume" else "Listen", style = Type.headline.copy(fontSize = sp(36f), lineHeight = sp(44f)), color = Color.Black, maxLines = 1)
                timeText?.let { Text("·  $it", style = Type.headline.copy(fontSize = sp(36f), lineHeight = sp(44f), fontWeight = FontWeight.Normal), color = Color.Black.copy(alpha = 0.5f), maxLines = 1) }
            }
        }
    }
}

/** The section picker on a TV, beside the page title: a 740-point capsule of segments, the picked one lighter and the focused one white. */
@Composable
private fun TvSegmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val u = Lockup.tvUnit
    Row(
        Modifier.width((740f * u).dp).height((68f * u).dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).padding((4f * u).dp),
    ) {
        labels.forEachIndexed { index, label ->
            var focused by remember { mutableStateOf(false) }
            Box(
                Modifier.weight(1f).fillMaxSize().onFocusChanged { focused = it.isFocused }.clip(CircleShape)
                    .background(if (focused) Color.White else if (index == selected) Color.White.copy(alpha = 0.3f) else Color.Transparent)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = Type.calloutMedium, color = if (focused) Color.Black else Color.White, maxLines = 1) }
        }
    }
}
