package com.astermediaplayer.astermediaplayer.data.disc

import kotlin.math.abs
import kotlin.math.ceil

/** A button's frame on screen, in any one space. */
data class DiscRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
    val midX get() = (left + right) / 2
    val midY get() = (top + bottom) / 2
}

/**
 * The screen-independent rules of the JellyDisc format (the plugin's CLIENT_GUIDE §4): paging, grid cells, settings
 * inheritance, directional focus, chapter time text and what to accept from untrusted data. Pure, like the Apple apps'
 * `DiscMenuRules`.
 */
object DiscMenuRules {
    /** Shift of the element, as a fraction of its own size, for each anchor name. */
    fun anchorShift(anchor: String?): Pair<Double, Double> = when (anchor ?: "top-left") {
        "top" -> -0.5 to 0.0
        "top-right" -> -1.0 to 0.0
        "left" -> 0.0 to -0.5
        "center" -> -0.5 to -0.5
        "right" -> -1.0 to -0.5
        "bottom-left" -> 0.0 to -1.0
        "bottom" -> -0.5 to -1.0
        "bottom-right" -> -1.0 to -1.0
        else -> 0.0 to 0.0
    }

    data class Page(val items: List<DiscMenuEntry>, val backs: List<DiscMenuEntry>, val prev: Boolean, val more: Boolean)

    /** Splits a screen's entries over the pages of a `slots`-cell grid. The first `back` and the first `home` entry are pinned to every page. */
    fun paginate(entries: List<DiscMenuEntry>, slots: Int, maxPerPage: Int = 0): List<Page> {
        val maxPer = if (maxPerPage > 0) maxPerPage else Int.MAX_VALUE
        val firstBack = entries.indexOfFirst { it.action == "back" }
        val firstHome = entries.indexOfFirst { it.action == "home" }
        val backs = mutableListOf<DiscMenuEntry>()
        val content = mutableListOf<DiscMenuEntry>()
        entries.forEachIndexed { index, entry -> if (index == firstBack || index == firstHome) backs += entry else content += entry }
        val pages = mutableListOf<Page>()
        var taken = 0
        while (true) {
            val prev = pages.isNotEmpty()
            val room = slots - (backs.size + if (prev) 1 else 0)
            val left = content.size - taken
            if (left <= minOf(room, maxPer)) {
                pages += Page(content.subList(taken, content.size).toList(), backs, prev, more = false)
                return pages
            }
            val cap = minOf(maxOf(1, room - 1), maxPer)
            pages += Page(content.subList(taken, taken + cap).toList(), backs, prev, more = true)
            taken += cap
        }
    }

    /** The on-screen position of grid cell `slot` (row-major from 0) of a flow. */
    fun cellPosition(flow: DiscFlow, slot: Int, tall: Boolean): DiscPosition {
        val region = flow.region
        val (sx, sy) = anchorShift(region.anchor)
        val width = region.w ?: 0.0
        val height = region.h ?: 0.0
        val left = region.x + sx * width
        val top = region.y + sy * height
        val columns = maxOf(1, flow.columns)
        val rows = maxOf(1, flow.rows)
        val cellW = width / columns
        val cellH = height / rows
        return DiscPosition(
            x = left + (slot % columns + 0.5) * cellW,
            y = top + (slot / columns + 0.5) * cellH,
            w = cellW * 0.94,
            h = if (tall) cellH * 0.94 else null,
            anchor = "center",
        )
    }

    /** Document layout, then the screen's, key by key. A missing key is skipped. */
    fun effectiveLayout(document: DiscLayout?, screen: DiscLayout?) = DiscLayout(
        titlePosition = screen?.titlePosition ?: document?.titlePosition,
        hideTitle = screen?.hideTitle ?: document?.hideTitle,
        buttonStyle = screen?.buttonStyle ?: document?.buttonStyle,
        flow = screen?.flow ?: document?.flow,
        transition = screen?.transition ?: document?.transition,
        layers = screen?.layers ?: document?.layers,
    )

    /** Theme and background are whole objects: the screen's, else the document's. */
    fun effectiveTheme(document: DiscTheme?, screen: DiscTheme?): DiscTheme = screen ?: document ?: DiscTheme()
    fun effectiveBackground(document: DiscBackground?, screen: DiscBackground?): DiscBackground? = screen ?: document

    /** Audio merges per part: Music and Sounds each fall back separately. */
    fun effectiveAudio(document: DiscAudio?, screen: DiscAudio?): DiscAudio? =
        if (document == null && screen == null) null else DiscAudio(screen?.music ?: document?.music, screen?.sounds ?: document?.sounds)

    data class PageLayout(val entries: List<DiscMenuEntry>, val pageCount: Int, val page: Int, val positioned: Boolean)

    /** The buttons to draw on `page` of a screen, in drawing order, with their final positions (null = the default column). */
    fun entriesForPage(document: DiscMenuDocument, screen: DiscMenuScreenSpec, requested: Int): PageLayout {
        val layout = effectiveLayout(document.layout, screen.layout)
        val flow = layout.flow
        if (flow == null) {
            val positioned = screen.entries.isNotEmpty() && screen.entries.all { it.position != null }
            return PageLayout(screen.entries, 1, 0, positioned)
        }
        val slots = maxOf(1, flow.columns) * maxOf(1, flow.rows)
        val pages = paginate(screen.entries, slots, screen.maxPerPage ?: 0)
        val page = requested.coerceIn(0, pages.size - 1)
        val current = pages[page]
        val nav = current.backs.toMutableList()
        if (current.prev) nav += DiscMenuEntry(DiscAction.PagePrev.raw, flow.previousLabel ?: "Previous")
        if (current.more) nav += DiscMenuEntry(DiscAction.PageNext.raw, flow.moreLabel ?: "More")
        val placed = mutableListOf<DiscMenuEntry>()
        current.items.forEachIndexed { index, entry -> placed += entry.copy(position = cellPosition(flow, index, entry.isTall)) }
        nav.forEachIndexed { index, entry -> placed += entry.copy(position = cellPosition(flow, slots - nav.size + index, !entry.image.isNullOrEmpty())) }
        return PageLayout(placed, pages.size, page, true)
    }

    /** The grid for a scene-selection screen when neither the styling screen nor the document declares a `Flow`. */
    fun defaultChapterFlow(perPage: Int): DiscFlow {
        val slots = perPage + 3
        val columns = when {
            perPage <= 3 -> maxOf(1, perPage)
            perPage <= 8 -> 3
            else -> 4
        }
        val rows = ceil(slots.toDouble() / columns).toInt()
        return DiscFlow(DiscPosition(50.0, 52.0, 86.0, 68.0, "center"), columns, rows)
    }

    /**
     * Index of the button that takes focus when (dx, dy) is pressed on `current`, or `current` when nothing moves.
     * Rectangles are the buttons' on-screen frames in drawing order.
     */
    fun moveFocus(rects: List<DiscRect>, current: Int, dx: Int, dy: Int): Int {
        if (current !in rects.indices) return current
        val from = rects[current]
        var best: Int? = null
        var bestKey: Pair<Int, Double>? = null
        var wrap: Int? = null
        var wrapKey: Pair<Double, Double>? = null
        for ((index, rect) in rects.withIndex()) {
            if (index == current) continue
            val across = acrossDistance(from, rect, dx)
            val ahead = distanceAlong(from, rect, dx, dy)
            if (ahead != null) {
                val key = if (overlapsAcross(from, rect, dx)) 0 to ahead else 1 to (ahead + 2 * across)
                val b = bestKey
                if (b == null || key.first < b.first || (key.first == b.first && key.second < b.second)) { best = index; bestKey = key }
                continue
            }
            val behind = distanceAlong(from, rect, -dx, -dy) ?: continue
            val far = -behind
            val w = wrapKey
            if (w == null || far < w.first - 20 || (far < w.first + 20 && across < w.second)) { wrap = index; wrapKey = far to across }
        }
        return best ?: wrap ?: current
    }

    private fun distanceAlong(from: DiscRect, rect: DiscRect, dx: Int, dy: Int): Double? {
        val tolerance = 0.25 * (if (dx != 0) from.width else from.height)
        val gap = when {
            dx == 1 -> rect.left - from.right
            dx == -1 -> from.left - rect.right
            dy == 1 -> rect.top - from.bottom
            else -> from.top - rect.bottom
        }
        if (gap < -tolerance) return null
        return (rect.midX - from.midX) * dx + (rect.midY - from.midY) * dy
    }

    private fun overlapsAcross(from: DiscRect, rect: DiscRect, dx: Int) =
        if (dx != 0) from.top < rect.bottom && rect.top < from.bottom else from.left < rect.right && rect.left < from.right

    private fun acrossDistance(from: DiscRect, rect: DiscRect, dx: Int) =
        abs(if (dx != 0) rect.midY - from.midY else rect.midX - from.midX)

    /** Chapter start time: h:mm:ss from one hour, otherwise m:ss (minutes not padded). */
    fun formatTicks(ticks: Long): String {
        val total = (maxOf(0L, ticks) / 10_000_000).toInt()
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    // What to accept from untrusted data (§4.8).
    private const val SEGMENT = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}"
    private const val HTTPS = "https://[^\\s\"'()<>\\\\]+"
    private const val ASSET = "asset:$SEGMENT(?:/$SEGMENT){0,3}"
    private val safeImage = Regex("^(?:$HTTPS|data:image/(?:png|jpeg|webp);base64,[A-Za-z0-9+/=]+|$ASSET)$")
    private val safeAudio = Regex("^(?:$HTTPS|asset:$SEGMENT(?:/$SEGMENT){0,3}\\.(?:mp3|ogg|opus|m4a|wav))$")
    private val hexColor = Regex("^#[0-9a-fA-F]{6}$")
    private val tmdbPath = Regex("^/[A-Za-z0-9_-]+\\.(?:jpg|png)$")
    private val fanartId = Regex("^[0-9]{1,12}$")
    private val itemId = Regex("^[0-9a-fA-F-]{32,36}$")

    fun isSafeImage(v: String?) = v != null && safeImage.matches(v)
    fun isSafeAudio(v: String?) = v != null && safeAudio.matches(v)
    fun isHexColor(v: String?) = v != null && hexColor.matches(v)
    fun isTmdbPath(v: String?) = v != null && tmdbPath.matches(v)
    fun isFanartId(v: String?) = v != null && fanartId.matches(v)
    fun isItemId(v: String?) = v != null && itemId.matches(v)

    /** Clamps a number into a range, treating a missing or non-finite value as the default. */
    fun clamp(value: Double?, range: ClosedFloatingPointRange<Double>, default: Double): Double =
        if (value == null || !value.isFinite()) default else value.coerceIn(range)
}
