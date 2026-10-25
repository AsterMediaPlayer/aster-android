package com.astermediaplayer.astermediaplayer.data

import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.TranscodingProfile

/**
 * What ExoPlayer can play without server help. Anything outside this goes through an H.264/AAC HLS transcode,
 * the Android counterpart of `DeviceProfile.appleTV` in the iOS app.
 */
object AndroidDeviceProfile {
    /** The emulator only has software AV1 decoding, and its frames come out green and doubled, so the server converts AV1 for it. */
    private val isEmulator: Boolean
        get() = android.os.Build.HARDWARE.let { it.contains("ranchu") || it.contains("goldfish") } ||
            android.os.Build.FINGERPRINT.contains("generic")

    fun build(maxBitrate: Int) = DeviceProfile(
        name = "Aster Android",
        maxStreamingBitrate = maxBitrate,
        maxStaticBitrate = maxBitrate,
        musicStreamingTranscodingBitrate = 320_000,
        directPlayProfiles = listOf(
            DirectPlayProfile(
                container = "mp4,m4v,mkv,webm",
                type = DlnaProfileType.VIDEO,
                videoCodec = if (isEmulator) "h264,hevc,vp9" else "h264,hevc,vp9,av1",
                audioCodec = "aac,mp3,opus,flac,vorbis,ac3,eac3,dts,truehd",
            ),
            DirectPlayProfile(
                container = "mp3,aac,flac,ogg,opus,m4a,m4b",
                type = DlnaProfileType.AUDIO,
            ),
        ),
        transcodingProfiles = listOf(
            TranscodingProfile(
                container = "ts",
                type = DlnaProfileType.VIDEO,
                // HEVC is allowed so a title whose audio needs converting keeps its picture: the server copies the video rather than re-encoding it.
                videoCodec = "hevc,h264",
                audioCodec = "aac",
                protocol = MediaStreamProtocol.HLS,
                context = EncodingContext.STREAMING,
                maxAudioChannels = "6",
                minSegments = 1,
                breakOnNonKeyFrames = true,
                conditions = emptyList(),
            ),
            TranscodingProfile(
                container = "mp3",
                type = DlnaProfileType.AUDIO,
                videoCodec = "",
                audioCodec = "mp3",
                protocol = MediaStreamProtocol.HTTP,
                context = EncodingContext.STREAMING,
                conditions = emptyList(),
            ),
        ),
        // Text formats ExoPlayer renders itself are delivered externally so they work in direct play and HLS alike;
        // picture formats (PGS/VobSub) can only be played when embedded in the source container.
        subtitleProfiles = listOf(
            SubtitleProfile("srt", SubtitleDeliveryMethod.EXTERNAL),
            SubtitleProfile("subrip", SubtitleDeliveryMethod.EXTERNAL),
            SubtitleProfile("vtt", SubtitleDeliveryMethod.EXTERNAL),
            SubtitleProfile("webvtt", SubtitleDeliveryMethod.EXTERNAL),
            SubtitleProfile("ass", SubtitleDeliveryMethod.EXTERNAL),
            SubtitleProfile("ssa", SubtitleDeliveryMethod.EXTERNAL),
            SubtitleProfile("pgs", SubtitleDeliveryMethod.EMBED),
            SubtitleProfile("pgssub", SubtitleDeliveryMethod.EMBED),
            SubtitleProfile("dvdsub", SubtitleDeliveryMethod.EMBED),
            SubtitleProfile("dvbsub", SubtitleDeliveryMethod.EMBED),
        ),
        containerProfiles = emptyList(),
        codecProfiles = emptyList(),
    )

    /**
     * A file for offline viewing: always an HLS stream (so it downloads in segments and resumes), with stereo AAC, at most
     * [maxWidth] pixels wide. The video is copied when it already fits. The counterpart of `DeviceProfile.download`, but
     * in MPEG-TS segments: the server's fragmented MP4 starts its audio at a negative time, which ExoPlayer won't read.
     */
    fun download(maxBitrate: Int, maxWidth: Int?, allowHevc: Boolean): DeviceProfile {
        val size = maxWidth?.let {
            listOf(ProfileCondition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.WIDTH, it.toString(), true))
        }.orEmpty()
        val notAnamorphic = ProfileCondition(ProfileConditionType.NOT_EQUALS, ProfileConditionValue.IS_ANAMORPHIC, "true", true)
        return build(maxBitrate).copy(
            name = "Aster Download",
            directPlayProfiles = emptyList(),
            transcodingProfiles = listOf(
                TranscodingProfile(
                    container = "ts", type = DlnaProfileType.VIDEO, videoCodec = if (allowHevc) "hevc,h264" else "h264", audioCodec = "aac",
                    protocol = MediaStreamProtocol.HLS, context = EncodingContext.STREAMING, maxAudioChannels = "2", minSegments = 1, breakOnNonKeyFrames = true, conditions = emptyList(),
                ),
            ),
            codecProfiles = listOf(
                CodecProfile(
                    CodecType.VIDEO,
                    listOf(ProfileCondition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.VIDEO_LEVEL, "52", true), notAnamorphic) + size,
                    emptyList(), "h264", null, null,
                ),
            ) + if (allowHevc) listOf(CodecProfile(CodecType.VIDEO, listOf(notAnamorphic) + size, emptyList(), "hevc", null, null)) else emptyList(),
        )
    }
}
