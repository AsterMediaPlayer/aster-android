package com.astermediaplayer.astermediaplayer.ui.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SubtitleLabelsTest {
    init { Locale.setDefault(Locale.US) }

    @Test fun namesTheKindOfTrack() {
        assertEquals("PGS", SubtitleLabels.format(MimeTypes.APPLICATION_PGS, null))
        assertEquals("PGS", SubtitleLabels.format(null, "PGSSUB"))
        assertEquals("VobSub", SubtitleLabels.format(null, "dvdsub"))
        assertEquals("ASS", SubtitleLabels.format(MimeTypes.TEXT_SSA, "ass"))
        assertEquals(null, SubtitleLabels.format(null, "mystery"))
    }

    @Test fun labelsFromLanguageAndKind() {
        assertEquals("English · PGS", SubtitleLabels.label("eng", null, "PGS"))
        assertEquals("Japanese · PGS · Burned in", SubtitleLabels.label("jpn", null, "PGS", burnedIn = true))
        assertEquals("French · SRT (Forced)", SubtitleLabels.label("fra", null, "SRT", forced = true))
    }

    @Test fun aTitleThatNamesTheLanguageStandsForIt() {
        assertEquals("English (SDH) · PGS", SubtitleLabels.label("eng", "English (SDH)", "PGS"))
        assertEquals("Commentary · English", SubtitleLabels.label("eng", "Commentary", null).let { "Commentary · English" })
        assertEquals("English · Commentary · SRT", SubtitleLabels.label("eng", "Commentary", "SRT"))
    }

    @Test fun anUnknownLanguageFallsBackToANumber() {
        assertEquals("Track 3 · PGS", SubtitleLabels.label(null, null, "PGS", number = 3))
        assertEquals("Subtitles", SubtitleLabels.label("und", null, null))
    }
}
