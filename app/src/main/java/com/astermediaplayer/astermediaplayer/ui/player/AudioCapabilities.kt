package com.astermediaplayer.astermediaplayer.ui.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/** What this device and the screen or receiver on its HDMI can do with surround audio, written to the log for the audit of what can direct play. */
object AudioCapabilities {
    fun log(context: Context) {
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        val encodings = listOf(
            "AC-3" to AudioFormat.ENCODING_AC3, "E-AC-3" to AudioFormat.ENCODING_E_AC3, "E-AC-3 JOC (Atmos)" to AudioFormat.ENCODING_E_AC3_JOC,
            "TrueHD" to AudioFormat.ENCODING_DOLBY_TRUEHD, "DTS" to AudioFormat.ENCODING_DTS, "DTS-HD" to AudioFormat.ENCODING_DTS_HD,
            "DTS:X (UHD)" to AudioFormat.ENCODING_DTS_UHD, "Dolby MAT" to AudioFormat.ENCODING_DOLBY_MAT,
        )
        val lines = mutableListOf<String>()
        for ((name, encoding) in encodings) {
            val support = if (Build.VERSION.SDK_INT >= 33) runCatching {
                val format = AudioFormat.Builder().setEncoding(encoding).setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build()
                when (AudioManager.getDirectPlaybackSupport(format, attributes)) {
                    AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED -> "no"
                    AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED -> "PASSTHROUGH"
                    AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED -> "offload"
                    else -> "yes"
                }
            }.getOrDefault("?") else "n/a"
            lines += "$name=$support"
        }
        val decoders = listOf("audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/true-hd", "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.uhd;profile=p2").map { mime ->
            val names = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }.map { it.name }
            "$mime=${names.ifEmpty { listOf("none") }.joinToString("/")}"
        }
        Log.i("Aster", "Audio passthrough over HDMI: ${lines.joinToString(", ")}")
        Log.i("Aster", "Audio decoders: ${decoders.joinToString("; ")}")
        Log.i("Aster", "Surround mode: ${runCatching { android.provider.Settings.Global.getInt(context.contentResolver, "encoded_surround_output") }.getOrNull()} (0 auto, 1 never, 2 always, 3 manual)")
    }
}
