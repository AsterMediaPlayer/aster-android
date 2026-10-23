package com.astermediaplayer.astermediaplayer.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.TrickplayInfoDto
import java.util.UUID
import kotlin.math.abs

// The picture shown above the timeline while scrubbing: Jellyfin's trickplay images when the server has made them
// (sheets of small frames, one every few seconds, for the whole title), else just the time. The Kotlin counterpart of
// the Apple apps' `ScrubPreview.swift`.

/** One resolution of a title's trickplay images, as Jellyfin lists them. */
data class TrickplayInfo(
    /** Each frame's size in pixels. */
    val width: Int,
    val height: Int,
    /** Frames across and down one sheet. */
    val tileWidth: Int,
    val tileHeight: Int,
    val thumbnailCount: Int,
    /** Milliseconds between frames. */
    val intervalMs: Int,
) {
    /** Where the frame for a time is: which sheet, and its cell there. */
    data class Cell(val sheet: Int, val column: Int, val row: Int)

    /** The cell's rectangle in its sheet, in pixels. */
    data class Area(val x: Int, val y: Int, val width: Int, val height: Int)

    fun cell(seconds: Double): Cell? {
        if (intervalMs <= 0 || tileWidth <= 0 || tileHeight <= 0 || thumbnailCount <= 0 || !seconds.isFinite()) return null
        val frame = (seconds * 1000).toLong().div(intervalMs).toInt().coerceIn(0, thumbnailCount - 1)
        val perSheet = tileWidth * tileHeight
        val inSheet = frame % perSheet
        return Cell(frame / perSheet, inSheet % tileWidth, inSheet / tileWidth)
    }

    fun area(cell: Cell) = Area(cell.column * width, cell.row * height, width, height)

    companion object {
        /** The resolution closest to [near] among a source's, for a preview that size. */
        fun best(resolutions: Map<String, TrickplayInfo>, near: Int): TrickplayInfo? = resolutions.values.minByOrNull { abs(it.width - near) }

        fun from(dto: TrickplayInfoDto) = TrickplayInfo(dto.width, dto.height, dto.tileWidth, dto.tileHeight, dto.thumbnailCount, dto.interval)
    }
}

/**
 * Fetches and cuts preview frames for one title, keeping the sheets it has fetched so dragging along the timeline doesn't
 * fetch them again. A sheet is kept as the picture the server sent, and only the frame wanted is decoded from it.
 */
class ScrubPreviewSource private constructor(
    private val repo: AsterRepository,
    private val itemId: UUID,
    private val mediaSourceId: String,
    private val info: TrickplayInfo,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sheets = LinkedHashMap<Int, BitmapRegionDecoder>()
    private val loading = HashMap<Int, Deferred<BitmapRegionDecoder?>>()
    private val lock = Mutex()

    suspend fun frame(seconds: Double): Bitmap? {
        val cell = info.cell(seconds) ?: return null
        val decoder = sheet(cell.sheet) ?: return null
        val area = info.area(cell)
        return lock.withLock {
            runCatching {
                decoder.decodeRegion(Rect(area.x, area.y, area.x + area.width, area.y + area.height), BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
            }.getOrNull()
        }
    }

    private suspend fun sheet(index: Int): BitmapRegionDecoder? {
        val pending = lock.withLock {
            sheets[index]?.let { return it }
            loading.getOrPut(index) { scope.async { fetchSheet(index) } }
        }
        val decoder = pending.await()
        lock.withLock {
            loading.remove(index)
            if (decoder != null) {
                sheets[index] = decoder
                // A few sheets cover a long stretch of timeline; the oldest are let go.
                while (sheets.size > MAX_SHEETS) sheets.keys.first().let { sheets.remove(it)?.recycle() }
            }
        }
        return decoder
    }

    private suspend fun fetchSheet(index: Int): BitmapRegionDecoder? {
        val bytes = repo.trickplaySheet(itemId, info.width, index, mediaSourceId) ?: return null
        return runCatching { BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) }.getOrNull()
    }

    /** Lets the sheets go when the player does. */
    fun release() {
        scope.cancel()
        runCatching { sheets.values.forEach { it.recycle() }; sheets.clear() }
    }

    companion object {
        const val PREVIEW_WIDTH = 320
        private const val MAX_SHEETS = 4

        /** Null when the item has no trickplay images for this file. */
        fun create(repo: AsterRepository, item: BaseItemDto, mediaSourceId: String?): ScrubPreviewSource? {
            val trickplay: Map<String, Map<String, TrickplayInfoDto>?> = item.trickplay?.takeIf { it.isNotEmpty() } ?: return null
            val sourceId = mediaSourceId?.takeIf { id -> trickplay.keys.any { it.equals(id, ignoreCase = true) } } ?: trickplay.keys.sorted().first()
            val key = trickplay.keys.first { it.equals(sourceId, ignoreCase = true) }
            val info = TrickplayInfo.best(trickplay.getValue(key).orEmpty().mapValues { TrickplayInfo.from(it.value) }, PREVIEW_WIDTH) ?: return null
            return ScrubPreviewSource(repo, item.id, key, info)
        }
    }
}
