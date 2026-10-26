package com.astermediaplayer.astermediaplayer.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/**
 * What this device says it can play, asked of its own codec list and audio output rather than assumed: the profile sent to the
 * server (see [AndroidDeviceProfile]) is built from it, so the server converts only what this device can't decode. A phone with
 * no hardware AV1 decoder is told so, and so is an emulator, whose software decoders fail on what a real device plays.
 */
object DeviceCapabilities {
    /** A video codec the device decodes, named as Jellyfin names it ("h264", "hevc", "vp9", "av1"). */
    data class Video(val codec: String, val hardware: Boolean, val maxWidth: Int, val tenBit: Boolean)

    private val videoTypes = listOf(
        Triple("h264", "video/avc", null),
        Triple("hevc", "video/hevc", MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
        Triple("vp9", "video/x-vnd.on2.vp9", MediaCodecInfo.CodecProfileLevel.VP9Profile2),
        Triple("av1", "video/av01", MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10),
    )

    /** The video codecs it decodes well: in hardware, or H.264 in software, which every device manages and every file can fall back to. */
    val video: List<Video> by lazy {
        runCatching {
            val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
            videoTypes.mapNotNull { (codec, mime, tenBitProfile) ->
                val candidates = decoders.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                val best = candidates.firstOrNull { isHardware(it) } ?: candidates.firstOrNull()
                when {
                    best == null -> if (codec == "h264") Video("h264", hardware = false, maxWidth = 1920, tenBit = false) else null
                    !isHardware(best) && codec != "h264" -> null
                    else -> {
                        val capabilities = best.getCapabilitiesForType(mime)
                        val size = capabilities.videoCapabilities
                        val width = listOf(7680 to 4320, 3840 to 2160, 1920 to 1080).firstOrNull { (w, h) -> size?.isSizeSupported(w, h) == true }?.first ?: 1280
                        Video(codec, isHardware(best), width, tenBitProfile != null && capabilities.profileLevels.any { it.profile == tenBitProfile })
                    }
                }
            }
        }.getOrElse { listOf(Video("h264", hardware = false, maxWidth = 1920, tenBit = false)) }
    }

    fun video(codec: String): Video? = video.firstOrNull { it.codec == codec }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly
        else !info.name.startsWith("c2.android.") && !info.name.startsWith("OMX.google.")

    // Audio the framework can't always decode: a decoder on the device, or a screen or receiver that takes it as it is.
    private val audioTypes = listOf(
        "ac3" to (listOf("audio/ac3") to listOf(AudioFormat.ENCODING_AC3)),
        "eac3" to (listOf("audio/eac3", "audio/eac3-joc") to listOf(AudioFormat.ENCODING_E_AC3, AudioFormat.ENCODING_E_AC3_JOC)),
        "dts" to (listOf("audio/vnd.dts", "audio/vnd.dts.hd") to listOf(AudioFormat.ENCODING_DTS, AudioFormat.ENCODING_DTS_HD)),
        "truehd" to (listOf("audio/true-hd") to listOf(AudioFormat.ENCODING_DOLBY_TRUEHD, AudioFormat.ENCODING_DOLBY_MAT)),
    )

    private val audioDecoders: Set<String> by lazy {
        runCatching {
            val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }.flatMap { it.supportedTypes.toList() }.map { it.lowercase() }
            audioTypes.filter { (_, v) -> v.first.any { it in all } }.map { it.first }.toSet()
        }.getOrDefault(emptySet())
    }

    /**
     * The surround audio ("ac3", "eac3", "dts", "truehd") it can play: decoded here, or passed through to what's on its HDMI. The
     * passthrough half is asked each time, since the screen or receiver can change.
     */
    fun surroundAudio(): List<String> {
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        return audioTypes.filter { (codec, v) ->
            codec in audioDecoders || (Build.VERSION.SDK_INT >= 33 && v.second.any { encoding ->
                runCatching {
                    val format = AudioFormat.Builder().setEncoding(encoding).setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build()
                    AudioManager.getDirectPlaybackSupport(format, attributes) != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
                }.getOrDefault(false)
            })
        }.map { it.first }
    }

    /** What was found, for the log. */
    fun log() {
        Log.i("Aster", "Device decodes video: " + video.joinToString { "${it.codec}(${if (it.hardware) "hardware" else "software"}, up to ${it.maxWidth} wide${if (it.tenBit) ", 10-bit" else ""})" } + " | surround audio: ${surroundAudio().joinToString().ifEmpty { "none" }}")
    }
}
