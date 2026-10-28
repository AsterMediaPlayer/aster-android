package com.astermediaplayer.astermediaplayer.ui.player

import androidx.media3.common.MimeTypes
import java.util.Locale

/** One entry of the player's subtitle menu: a track the player can draw, one the server burns into the picture, or Off. */
data class SubtitleChoice(
    val label: String,
    val selected: Boolean,
    /** The text track in the player's current tracks (group, track), when it can draw it itself. */
    val group: Int? = null,
    val track: Int = 0,
    /** The server's index of a picture-based track it burns into the video, when that's the only way. */
    val burnIn: Int? = null,
    /** The server's index of a text track the app draws itself from the server's cue list. */
    val serverTrack: Int? = null,
    val isOff: Boolean = false,
)

/** How subtitle tracks are named in the menu: "English · PGS", "Japanese · ASS (Forced)", "English · PGS · Burned in". */
object SubtitleLabels {
    /** A short name for the kind of track, from the player's MIME type or the server's codec name; null for one we've no name for. */
    fun format(mimeType: String?, codec: String?): String? {
        when (mimeType) {
            MimeTypes.APPLICATION_PGS -> return "PGS"
            MimeTypes.APPLICATION_DVBSUBS -> return "DVB"
            MimeTypes.APPLICATION_VOBSUB -> return "VobSub"
            MimeTypes.TEXT_SSA -> return "ASS"
            MimeTypes.APPLICATION_SUBRIP -> return "SRT"
            MimeTypes.TEXT_VTT -> return "VTT"
        }
        return when (codec?.lowercase()) {
            "pgssub", "pgs", "hdmv_pgs_subtitle" -> "PGS"
            "dvdsub", "dvd_subtitle", "vobsub" -> "VobSub"
            "dvbsub", "dvb_subtitle" -> "DVB"
            "ass" -> "ASS"
            "ssa" -> "SSA"
            "subrip", "srt" -> "SRT"
            "vtt", "webvtt" -> "VTT"
            else -> null
        }
    }

    /** Three-letter codes (and the older "bibliographic" ones some files use) to the two-letter code the platform knows. */
    private val twoLetter: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (code in Locale.getISOLanguages()) map[Locale(code).isO3Language] = code
        listOf(
            "fre" to "fr", "ger" to "de", "chi" to "zh", "dut" to "nl", "cze" to "cs", "gre" to "el", "ice" to "is", "mac" to "mk",
            "mao" to "mi", "may" to "ms", "per" to "fa", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy", "alb" to "sq",
            "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka",
        ).forEach { map.putIfAbsent(it.first, it.second) }
        map
    }

    /** "eng" or "en" as the language's own name in the viewer's language; the code itself when unknown. */
    fun language(code: String?): String? {
        if (code.isNullOrBlank() || code == "und") return null
        val lower = code.lowercase()
        val locale = Locale.forLanguageTag(twoLetter[lower] ?: lower)
        val name = locale.getDisplayLanguage(Locale.getDefault())
        return name.takeIf { it.isNotBlank() && it != locale.language } ?: code
    }

    /**
     * [title] is what the file's maker called the track (often already "English"), so it stands in for the language when
     * there is one; the language is kept when the title doesn't carry it.
     */
    fun label(language: String?, title: String?, format: String?, forced: Boolean = false, burnedIn: Boolean = false, number: Int? = null): String {
        val name = language(language)
        val head = when {
            !title.isNullOrBlank() && (name == null || title.contains(name, ignoreCase = true)) -> title
            !title.isNullOrBlank() -> "$name · $title"
            name != null -> name
            else -> number?.let { "Track $it" } ?: "Subtitles"
        }
        return buildString {
            append(head)
            if (format != null && !head.contains(format, ignoreCase = true)) append(" · ").append(format)
            if (forced && !head.contains("forced", ignoreCase = true)) append(" (Forced)")
            if (burnedIn) append(" · Burned in")
        }
    }
}

/** One entry of the player's audio menu: a track in the file the player switches itself, or one the server streams. */
data class AudioChoice(
    val label: String,
    val selected: Boolean,
    /** The audio track in the player's current tracks, in a direct play. */
    val group: Int? = null,
    /** The server's index of the track, which a converted stream is asked for again with. */
    val serverIndex: Int? = null,
)

/** How audio tracks are named in the menu, as the Apple apps do: "English · EAC3 5.1", "Commentary · English · AAC Stereo". */
object AudioLabels {
    fun codec(mimeType: String?): String? = when (mimeType) {
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "AC3"
        MimeTypes.AUDIO_E_AC3 -> "EAC3"
        MimeTypes.AUDIO_E_AC3_JOC -> "Atmos"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_RAW -> "PCM"
        else -> mimeType?.substringAfter('/')?.uppercase()
    }

    fun label(language: String?, title: String?, codec: String?, channels: Int?, number: Int): String {
        val name = SubtitleLabels.language(language)
        val parts = mutableListOf(
            when {
                !title.isNullOrBlank() -> title
                name != null -> name
                else -> "Track $number"
            },
        )
        if (!title.isNullOrBlank() && name != null && !title.contains(name, ignoreCase = true)) parts += name
        val layout = when (channels) { 6 -> " 5.1"; 8 -> " 7.1"; 2 -> " Stereo"; else -> "" }
        if (codec != null) parts += codec + layout
        return parts.joinToString(" · ")
    }
}
