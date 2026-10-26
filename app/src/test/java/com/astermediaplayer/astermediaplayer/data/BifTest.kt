package com.astermediaplayer.astermediaplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class BifTest {
    private val magic = byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun le(value: Long) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    /** A BIF with the given pictures, each at [intervalMs] apart in units of [multiplier] ms. */
    private fun bif(pictures: List<ByteArray>, multiplier: Long = 1000, step: Long = 10, version: Long = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(magic); out.write(le(version)); out.write(le(pictures.size.toLong())); out.write(le(multiplier)); out.write(ByteArray(44))
        var offset = 64L + (pictures.size + 1) * 8
        pictures.forEachIndexed { i, p -> out.write(le(i * step)); out.write(le(offset)); offset += p.size }
        out.write(le(0xFFFFFFFFL)); out.write(le(offset))
        pictures.forEach { out.write(it) }
        return out.toByteArray()
    }

    @Test fun readsPicturesByTime() {
        val file = BifFile.parse(bif(listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5), byteArrayOf(6, 7, 8, 9)), multiplier = 1000, step = 10))!!
        assertEquals(3, file.count)
        // The first picture runs from the start up to the second one's time, ten seconds in.
        val (start0, length0) = file.picture(0.0)!!
        assertEquals(64 + 4 * 8, start0); assertEquals(3, length0)
        assertEquals(file.picture(0.0), file.picture(9.9))
        val (start1, length1) = file.picture(10.0)!!
        assertEquals(64 + 4 * 8 + 3, start1); assertEquals(2, length1)
        // The last picture runs to the end of the file, and covers whatever is after its time.
        val (_, lengthLast) = file.picture(25.0)!!
        assertEquals(4, lengthLast)
        assertEquals(file.picture(20.0), file.picture(3_600.0))
    }

    @Test fun timesAreInTheFilesOwnUnits() {
        // Emby writes a multiplier of 1000 and a frame every ten; a file counting in 1-second units with one picture per frame number reads the same way.
        val file = BifFile.parse(bif(listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)), multiplier = 10_000, step = 1))!!
        assertEquals(file.picture(10.0), file.picture(19.9))
        assertEquals(1, file.picture(10.0)!!.second)
        assertNotNull(file.picture(20.0))
    }

    @Test fun aZeroMultiplierMeansASecond() {
        val file = BifFile.parse(bif(listOf(byteArrayOf(1), byteArrayOf(2)), multiplier = 0, step = 5))!!
        assertEquals(file.picture(5.0)!!.first, file.picture(9.0)!!.first)
        assertEquals(file.picture(0.0)!!.first + 1, file.picture(5.0)!!.first)
    }

    @Test fun beforeTheStartIsTheFirstPicture() {
        val file = BifFile.parse(bif(listOf(byteArrayOf(1), byteArrayOf(2))))!!
        assertEquals(file.picture(0.0), file.picture(-5.0))
    }

    @Test fun aFileWithNoPicturesIsNothing() {
        // What Emby sends for a title it has made no previews for: the header and a closing index entry, 72 bytes.
        val empty = bif(emptyList())
        assertEquals(72, empty.size)
        assertNull(BifFile.parse(empty))
    }

    @Test fun somethingElseIsNothing() {
        assertNull(BifFile.parse(ByteArray(0)))
        assertNull(BifFile.parse("<html>Not found</html>".toByteArray()))
        val good = bif(listOf(byteArrayOf(1, 2, 3), byteArrayOf(4)))
        // A wrong magic, and a file cut off inside its index.
        assertNull(BifFile.parse(good.copyOf().also { it[1] = 0x00 }))
        assertNull(BifFile.parse(good.copyOf(70)))
    }

    @Test fun aSillyCountIsRefusedNotAllocated() {
        val huge = bif(listOf(byteArrayOf(1))).also { le(4_000_000_000L).copyInto(it, 12) }
        assertNull(BifFile.parse(huge))
    }
}
