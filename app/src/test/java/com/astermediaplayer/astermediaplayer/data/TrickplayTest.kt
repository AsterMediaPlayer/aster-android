package com.astermediaplayer.astermediaplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The same cases as the Apple apps' `TrickplayTests`: which sheet, which cell, and the cell's rectangle, as Jellyfin lays them out (left to right, then down, one sheet after another). */
class TrickplayTest {
    /** Like the server's: 320×180 frames, 10×10 to a sheet, one every 10 s. */
    private val info = TrickplayInfo(width = 320, height = 180, tileWidth = 10, tileHeight = 10, thumbnailCount = 143, intervalMs = 10_000)

    private fun cell(sheet: Int, column: Int, row: Int) = TrickplayInfo.Cell(sheet, column, row)

    @Test fun theStartIsTheFirstCell() {
        assertEquals(cell(0, 0, 0), info.cell(0.0))
        assertEquals(cell(0, 0, 0), info.cell(9.9))
    }

    @Test fun framesRunAcrossThenDown() {
        assertEquals(cell(0, 1, 0), info.cell(10.0))
        assertEquals(cell(0, 9, 0), info.cell(95.0))
        assertEquals(cell(0, 0, 1), info.cell(100.0))
    }

    @Test fun theHundredthFrameStartsTheNextSheet() {
        assertEquals(cell(0, 9, 9), info.cell(999.0))
        assertEquals(cell(1, 0, 0), info.cell(1000.0))
    }

    @Test fun pastTheEndIsTheLastFrame() {
        // Frame 142 of 143: sheet 1, cell 42.
        assertEquals(cell(1, 2, 4), info.cell(99_999.0))
        assertEquals(cell(0, 0, 0), info.cell(-5.0))
    }

    @Test fun cellRectangles() {
        assertEquals(TrickplayInfo.Area(960, 360, 320, 180), info.area(cell(0, 3, 2)))
    }

    @Test fun theClosestResolutionIsChosen() {
        val small = TrickplayInfo(160, 90, 10, 10, 10, 10_000)
        val large = TrickplayInfo(640, 360, 10, 10, 10, 10_000)
        assertEquals(info, TrickplayInfo.best(mapOf("160" to small, "320" to info, "640" to large), 320))
        assertEquals(large, TrickplayInfo.best(mapOf("160" to small, "640" to large), 600))
        assertNull(TrickplayInfo.best(emptyMap(), 320))
    }

    @Test fun missingSpacingGivesNoCell() {
        assertNull(info.copy(intervalMs = 0).cell(10.0))
    }
}
