package com.astermediaplayer.astermediaplayer.ui.disc

import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.disc.DiscAction
import com.astermediaplayer.astermediaplayer.data.disc.DiscAudio
import com.astermediaplayer.astermediaplayer.data.disc.DiscBackground
import com.astermediaplayer.astermediaplayer.data.disc.DiscLayout
import com.astermediaplayer.astermediaplayer.data.disc.DiscMenuDocument
import com.astermediaplayer.astermediaplayer.data.disc.DiscMenuEntry
import com.astermediaplayer.astermediaplayer.data.disc.DiscMenuRules
import com.astermediaplayer.astermediaplayer.data.disc.DiscMenuScreenSpec
import com.astermediaplayer.astermediaplayer.data.disc.DiscRect
import com.astermediaplayer.astermediaplayer.data.disc.DiscTheme
import com.astermediaplayer.astermediaplayer.data.disc.parseUuid
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/** Which screen is on the stack: one from the document, or a generated scene-selection screen. */
sealed interface DiscScreenRef {
    data class Menu(val key: String) : DiscScreenRef
    data class Chapters(val key: String) : DiscScreenRef
}

/** A screen with its inherited settings resolved and its page laid out. */
data class DiscResolvedScreen(
    val ref: DiscScreenRef,
    val title: String,
    val theme: DiscTheme,
    val background: DiscBackground?,
    val layout: DiscLayout,
    val audio: DiscAudio?,
    val entries: List<DiscMenuEntry>,
    val positioned: Boolean,
    val page: Int,
    val pageCount: Int,
) {
    val transitionStyle: String get() = layout.transition?.style ?: "none"
    val transitionSeconds: Double get() = (layout.transition?.durationMs ?: 300).coerceIn(0, 2000) / 1000.0
}

/** Something for the player to play: an item from a point, or a line of items one after another. */
data class DiscPlay(val itemId: UUID, val startTicks: Long = 0, val followedBy: List<UUID>? = null)

/**
 * Navigation state of an open disc menu (the plugin's CLIENT_GUIDE §4.3–4.7): the screen stack, page and focus memory,
 * actions, scene selection, the trailer layer and the hand-off to playback. The Kotlin counterpart of the Apple apps'
 * `DiscMenuController`; it outlives the player that opens over it, so coming back finds the menu where it was left.
 */
@OptIn(UnstableApi::class)
class DiscMenuController(
    private val app: AsterApp,
    private val repo: AsterRepository,
    document: DiscMenuDocument,
    val itemId: UUID,
    soundsEnabled: Boolean,
) : ViewModel() {
    val pictures = DiscPictures(repo, itemId)
    val audio = DiscMenuAudio(app, pictures).also { it.enabled = soundsEnabled }
    var trailer by mutableStateOf<DiscTrailerPlayer?>(null)
        private set

    var document: DiscMenuDocument = document
        private set
    var screen by mutableStateOf(resolve(DiscScreenRef.Menu(document.root), 0))
        private set
    var focusIndex by mutableIntStateOf(0)
        private set
    /** Bumped on every screen or page change; entry frames are measured per generation. */
    var generation by mutableIntStateOf(0)
        private set
    /** +1 going forward (a submenu, More), -1 going back. */
    var direction = 1
        private set
    /** False when the screen is replaced without animation. */
    var animateChange = false
        private set
    var alertMessage by mutableStateOf<String?>(null)

    private val _plays = MutableSharedFlow<DiscPlay>(extraBufferCapacity = 1)
    val plays: SharedFlow<DiscPlay> = _plays.asSharedFlow()
    private val _closes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closes: SharedFlow<Unit> = _closes.asSharedFlow()

    private val stack = mutableListOf<DiscScreenRef>(DiscScreenRef.Menu(document.root))
    private val pageMemory = mutableMapOf<DiscScreenRef, Int>()
    private val focusMemory = mutableMapOf<DiscScreenRef, Int>()
    private val generated = mutableMapOf<DiscScreenRef, DiscMenuScreenSpec>()
    private val frames = mutableMapOf<Int, MutableMap<Int, DiscRect>>()
    private var opened = false
    private var suspended = false

    // --- Lifecycle ---

    /** The menu is on screen: the first time it opens, and afterwards when the player closes over it. */
    fun shown() {
        if (!opened) {
            opened = true
            focusIndex = 0
            animateChange = true
            generation++
            syncPresentation()
        } else if (suspended) {
            suspended = false
            viewModelScope.launch { refetch() }
            audio.resume()
            trailer?.play()
        }
    }

    /** Pauses music and the trailer while the player, or another app, is up. */
    fun hidden() {
        if (!opened || suspended) return
        suspended = true
        audio.suspend()
        trailer?.pause()
    }

    fun close() {
        audio.stop()
        trailer?.stop()
        trailer = null
        _closes.tryEmit(Unit)
    }

    override fun onCleared() {
        audio.stop()
        audio.release()
        trailer?.stop()
    }

    /** Playback ended: the document is fetched again and the same screen shown again. */
    private suspend fun refetch() {
        repo.discMenu(itemId)?.let { document = it }
        stack.retainAll { ref ->
            when (ref) {
                is DiscScreenRef.Menu -> document.menus.containsKey(ref.key)
                is DiscScreenRef.Chapters -> generated.containsKey(ref)
            }
        }
        if (stack.isEmpty()) stack += DiscScreenRef.Menu(document.root)
        animateChange = false
        rebuild(keepFocus = true)
    }

    // --- Geometry from the view ---

    fun reportFrame(frame: DiscRect, index: Int, generation: Int) {
        frames.getOrPut(generation) { mutableMapOf() }[index] = frame
    }

    private fun currentFrames(): List<DiscRect>? {
        val measured = frames[generation] ?: return null
        if (screen.entries.isEmpty() || measured.size < screen.entries.size) return null
        return screen.entries.indices.map { measured[it] ?: DiscRect(0.0, 0.0, 0.0, 0.0) }
    }

    // --- Input ---

    /** Moves focus one step: -1/1 on each axis, left/up being negative. */
    fun move(dx: Int, dy: Int) {
        if (screen.entries.isEmpty()) return
        val rects = currentFrames()
        val target = if (rects != null) DiscMenuRules.moveFocus(rects, focusIndex, dx, dy) else {
            // Frames not measured yet: step through the drawing order.
            val step = if (dx + dy > 0) 1 else -1
            (focusIndex + step + screen.entries.size) % screen.entries.size
        }
        if (target == focusIndex) return
        focusIndex = target
        focusMemory[screen.ref] = target
        audio.play(DiscMenuAudio.Sound.Move)
    }

    fun select() {
        screen.entries.getOrNull(focusIndex)?.let(::activate)
    }

    /** A touch at a point in the frames' space: highlights and activates the entry under it, if any. */
    fun tap(x: Double, y: Double) {
        val rects = currentFrames() ?: return
        val hit = rects.indexOfLast { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }
        if (hit < 0) return
        focusIndex = hit
        focusMemory[screen.ref] = hit
        activate(screen.entries[hit])
    }

    val canGoBack: Boolean get() = screen.page > 0 || stack.size > 1

    /** Back: a page back, then a screen back, then close. */
    fun back() {
        audio.play(DiscMenuAudio.Sound.Back)
        when {
            screen.page > 0 -> changePage(-1)
            stack.size > 1 -> pop()
            else -> close()
        }
    }

    fun playPause() {
        val entry = screen.entries.getOrNull(focusIndex) ?: return
        when (entry.kind) {
            DiscAction.PlayFeature, DiscAction.PlayExtra, DiscAction.PlaySequence, DiscAction.PlayChapter -> activate(entry)
            else -> Unit
        }
    }

    // --- Actions ---

    fun activate(entry: DiscMenuEntry) {
        when (entry.kind) {
            DiscAction.Submenu -> {
                val key = entry.menu ?: return
                if (!document.menus.containsKey(key)) return
                audio.play(DiscMenuAudio.Sound.Select); push(DiscScreenRef.Menu(key))
            }
            DiscAction.Back -> { audio.play(DiscMenuAudio.Sound.Back); if (stack.size > 1) pop() else close() }
            DiscAction.Home -> { audio.play(DiscMenuAudio.Sound.Back); home() }
            DiscAction.PagePrev -> { audio.play(DiscMenuAudio.Sound.Back); changePage(-1) }
            DiscAction.PageNext -> { audio.play(DiscMenuAudio.Sound.Select); changePage(1) }
            DiscAction.Chapters -> { audio.play(DiscMenuAudio.Sound.Select); openChapters(entry) }
            DiscAction.PlayFeature -> {
                audio.play(DiscMenuAudio.Sound.Select)
                val chapter = entry.startChapter
                val chapters = document.chapters
                if (chapter != null) {
                    // A chapter belongs to one title; otherwise the menu's own item may be a season or show, whose feature is its episodes.
                    val start = if (chapters != null && chapter >= 1 && chapter <= chapters.size) chapters[chapter - 1].startTicks else 0L
                    _plays.tryEmit(DiscPlay(itemId, start))
                } else playFeature(entry.label)
            }
            DiscAction.PlayExtra -> {
                audio.play(DiscMenuAudio.Sound.Select)
                val extra = entry.itemId?.let(::parseUuid)
                if (extra == null) alertMessage = "\"${entry.label}\" isn't available in this library." else _plays.tryEmit(DiscPlay(extra))
            }
            DiscAction.PlaySequence -> {
                audio.play(DiscMenuAudio.Sound.Select)
                val ids = entry.itemIds.orEmpty().mapNotNull(::parseUuid)
                if (ids.isEmpty()) alertMessage = "None of \"${entry.label}\" is available in this library."
                else _plays.tryEmit(DiscPlay(ids.first(), followedBy = ids.drop(1)))
            }
            DiscAction.PlayChapter -> { audio.play(DiscMenuAudio.Sound.Select); _plays.tryEmit(DiscPlay(itemId, entry.startTicks ?: 0)) }
            DiscAction.Unknown -> Unit
        }
    }

    /** The menu's feature: a movie (or episode) plays itself; a season or a show (a menu bound to one) plays its episodes in order. */
    private fun playFeature(label: String) {
        viewModelScope.launch {
            val item = runCatching { repo.item(itemId) }.getOrNull()
            val episodes = when (item?.type) {
                BaseItemKind.SEASON -> item.seriesId?.let { runCatching { repo.episodes(it, item.id) }.getOrNull() }.orEmpty()
                BaseItemKind.SERIES -> runCatching { repo.episodes(item.id, null) }.getOrNull().orEmpty()
                else -> { _plays.tryEmit(DiscPlay(itemId)); return@launch }
            }
            val ids = episodes.filter { it.type == BaseItemKind.EPISODE }.map { it.id }
            if (ids.isEmpty()) alertMessage = "\"$label\" has no episodes to play in this library."
            else _plays.tryEmit(DiscPlay(ids.first(), followedBy = ids.drop(1)))
        }
    }

    private fun push(ref: DiscScreenRef) {
        pageMemory[ref] = 0
        focusMemory.remove(ref)
        stack += ref
        direction = 1
        animateChange = true
        rebuild(keepFocus = false)
    }

    private fun pop() {
        if (stack.size <= 1) return
        stack.removeAt(stack.lastIndex)
        direction = -1
        animateChange = true
        rebuild(keepFocus = true)
    }

    private fun home() {
        stack.clear(); stack += DiscScreenRef.Menu(document.root)
        pageMemory.clear()
        direction = -1
        animateChange = true
        rebuild(keepFocus = true)
    }

    private fun changePage(delta: Int) {
        val next = screen.page + delta
        if (next < 0 || next >= screen.pageCount) return
        pageMemory[screen.ref] = next
        focusMemory[screen.ref] = 0
        direction = if (delta > 0) 1 else -1
        animateChange = true
        rebuild(keepFocus = false)
    }

    // --- Scene selection ---

    private fun openChapters(entry: DiscMenuEntry) {
        val chapters = document.chapters.orEmpty()
        if (chapters.isEmpty()) { alertMessage = "This title has no chapter markers."; return }
        val ref = DiscScreenRef.Chapters("${entry.menu.orEmpty()}|${entry.label}")
        val styling = entry.menu?.let { document.menus[it] }
        val perPage = (entry.perPage ?: 6).coerceIn(1, 24)

        val entries = chapters.mapIndexed { position, chapter ->
            DiscMenuEntry(
                action = DiscAction.PlayChapter.raw,
                label = chapter.name?.takeIf { it.isNotEmpty() } ?: "Chapter ${position + 1}",
                sub = DiscMenuRules.formatTicks(chapter.startTicks),
                thumbUrl = if (chapter.hasImage == true) repo.chapterImageUrl(itemId, chapter.index, chapter.imageStamp) else null,
                startTicks = chapter.startTicks,
            )
        }.toMutableList()
        if (styling != null) entries += styling.entries else entries += DiscMenuEntry(DiscAction.Back.raw, "Back")

        var layout = styling?.layout ?: DiscLayout()
        if (DiscMenuRules.effectiveLayout(document.layout, styling?.layout).flow == null) layout = layout.copy(flow = DiscMenuRules.defaultChapterFlow(perPage))

        generated[ref] = DiscMenuScreenSpec(
            title = styling?.title ?: entry.label, entries = entries, background = styling?.background, theme = styling?.theme,
            layout = layout, audio = styling?.audio, maxPerPage = perPage,
        )
        push(ref)
    }

    // --- Resolution ---

    private fun resolve(ref: DiscScreenRef, page: Int): DiscResolvedScreen {
        val spec = when (ref) {
            is DiscScreenRef.Menu -> document.menus[ref.key]
            is DiscScreenRef.Chapters -> generated[ref]
        } ?: DiscMenuScreenSpec("", emptyList())
        val laid = DiscMenuRules.entriesForPage(document, spec, page)
        return DiscResolvedScreen(
            ref, spec.title,
            DiscMenuRules.effectiveTheme(document.theme, spec.theme),
            DiscMenuRules.effectiveBackground(document.background, spec.background),
            DiscMenuRules.effectiveLayout(document.layout, spec.layout),
            DiscMenuRules.effectiveAudio(document.audio, spec.audio),
            laid.entries, laid.positioned, laid.page, laid.pageCount,
        )
    }

    private fun rebuild(keepFocus: Boolean) {
        val ref = stack.lastOrNull() ?: return
        val resolved = resolve(ref, pageMemory[ref] ?: 0)
        val remembered = if (keepFocus) focusMemory[ref] ?: 0 else 0
        focusIndex = if (resolved.entries.isEmpty()) 0 else minOf(remembered, resolved.entries.size - 1)
        screen = resolved
        generation++
        frames.keys.retainAll { it >= generation - 1 }
        syncPresentation()
    }

    /** Music, button sounds and the trailer layer follow the current screen. */
    private fun syncPresentation() {
        audio.apply(screen.audio, document)
        syncTrailer()
    }

    private fun syncTrailer() {
        val background = screen.background
        if (background?.source != "trailer") { trailer?.stop(); trailer = null; return }
        val trailers = document.trailers.orEmpty()
        val index = maxOf(0, background.trailerIndex ?: 0)
        val chosen = trailers.getOrNull(index)
        val trailerId = chosen?.takeIf { it.kind == "local" }?.itemId?.let(::parseUuid)
        if (trailerId == null) { trailer?.stop(); trailer = null; return }
        val muted = background.muted ?: true
        val existing = trailer
        if (existing != null && existing.trailerId == trailerId && existing.muted == muted) return
        existing?.stop()
        val player = DiscTrailerPlayer(app, trailerId, repo.videoStreamUrl(trailerId), muted)
        trailer = player
        if (!suspended) player.play()
    }
}
