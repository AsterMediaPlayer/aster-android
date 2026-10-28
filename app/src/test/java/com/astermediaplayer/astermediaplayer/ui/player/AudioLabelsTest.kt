package com.astermediaplayer.astermediaplayer.ui.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AudioLabelsTest {
    init { Locale.setDefault(Locale.US) }

    @Test fun namesTheCodec() {
        assertEquals("EAC3", AudioLabels.codec(MimeTypes.AUDIO_E_AC3))
        assertEquals("Atmos", AudioLabels.codec(MimeTypes.AUDIO_E_AC3_JOC))
        assertEquals("TrueHD", AudioLabels.codec(MimeTypes.AUDIO_TRUEHD))
        assertEquals("X-UNKNOWN", AudioLabels.codec("audio/x-unknown"))
    }

    @Test fun languageThenFormat() {
        assertEquals("English · EAC3 5.1", AudioLabels.label("eng", null, "EAC3", 6, 1))
        assertEquals("Japanese · AAC Stereo", AudioLabels.label("jpn", null, "AAC", 2, 2))
    }

    @Test fun theTitleStandsInForTheLanguageWhenItCarriesIt() {
        assertEquals("English Commentary · AAC Stereo", AudioLabels.label("eng", "English Commentary", "AAC", 2, 1))
        assertEquals("Commentary · English · AAC Stereo", AudioLabels.label("eng", "Commentary", "AAC", 2, 1))
    }

    @Test fun anUnnamedTrackIsNumbered() {
        assertEquals("Track 3 · DTS 7.1", AudioLabels.label(null, null, "DTS", 8, 3))
        assertEquals("Track 1", AudioLabels.label("und", "", null, null, 1))
    }
}
