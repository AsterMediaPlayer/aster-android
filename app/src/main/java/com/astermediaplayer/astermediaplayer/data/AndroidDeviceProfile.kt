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
    /**
     * Built from what the device reports ([DeviceCapabilities]): the video codecs it decodes, up to the size and bit depth it
     * manages, and the surround audio it can decode or pass through. [copyHevc]: a transcode may keep an HEVC picture (the
     * server copies it, converting only the audio) where the device decodes it; off for a retry after a failed play and for
     * burned-in subtitles, which re-encode.
     */
    fun build(maxBitrate: Int, copyHevc: Boolean = true) = DeviceProfile(
        name = "Aster Android",
        maxStreamingBitrate = maxBitrate,
        maxStaticBitrate = maxBitrate,
        musicStreamingTranscodingBitrate = 320_000,
        directPlayProfiles = listOf(
            DirectPlayProfile(
                container = "mp4,m4v,mkv,webm",
                type = DlnaProfileType.VIDEO,
                videoCodec = DeviceCapabilities.video.joinToString(",") { it.codec },
                audioCodec = (listOf("aac", "mp3", "opus", "flac", "vorbis") + DeviceCapabilities.surroundAudio()).joinToString(","),
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
                videoCodec = if (copyHevc && DeviceCapabilities.video("hevc") != null) "hevc,h264" else "h264",
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
            // ASS and SSA stay in the file in a direct play, where libass draws them whole (styles, positions, fonts, vector drawings);
            // the server's separate copy is plain timed text. Where they can't stay (an HLS transcode) the separate copy is the fallback.
            SubtitleProfile("ass", SubtitleDeliveryMethod.EMBED),
            SubtitleProfile("ssa", SubtitleDeliveryMethod.EMBED),
            SubtitleProfile("ass", SubtitleDeliveryMethod.EXTERNAL),
            SubtitleProfile("ssa", SubtitleDeliveryMethod.EXTERNAL),
            SubtitleProfile("pgs", SubtitleDeliveryMethod.EMBED),
            SubtitleProfile("pgssub", SubtitleDeliveryMethod.EMBED),
            SubtitleProfile("dvdsub", SubtitleDeliveryMethod.EMBED),
            SubtitleProfile("dvbsub", SubtitleDeliveryMethod.EMBED),
        ),
        containerProfiles = emptyList(),
        // The size and bit depth each decoder manages; a file beyond them is converted rather than failing to play.
        codecProfiles = DeviceCapabilities.video.map { decoder ->
            CodecProfile(
                CodecType.VIDEO,
                listOf(
                    ProfileCondition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.WIDTH, decoder.maxWidth.toString(), true),
                    // Not required: a file whose bit depth isn't known still plays.
                    ProfileCondition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.VIDEO_BIT_DEPTH, if (decoder.tenBit) "10" else "8", false),
                ),
                emptyList(), decoder.codec, null, null,
            )
        },
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

    /**
     * What a Chromecast can play from the Default Media Receiver: H.264 in MP4 with AAC, AC3 or MP3 straight from the server,
     * and anything else as an H.264/AAC HLS transcode. Text subtitles go over as separate tracks, which the player asks the
     * server for as WebVTT (it converts any text format to the one named in the URL); picture formats can't, so they are
     * left to the server to burn in.
     */
    fun cast(maxBitrate: Int): DeviceProfile = build(maxBitrate).copy(
        name = "Aster Chromecast",
        directPlayProfiles = listOf(
            DirectPlayProfile(container = "mp4,m4v", type = DlnaProfileType.VIDEO, videoCodec = "h264", audioCodec = "aac,mp3,ac3,eac3"),
            DirectPlayProfile(container = "mp3,aac,flac,m4a,m4b", type = DlnaProfileType.AUDIO),
        ),
        transcodingProfiles = listOf(
            TranscodingProfile(
                container = "ts", type = DlnaProfileType.VIDEO, videoCodec = "h264", audioCodec = "aac",
                protocol = MediaStreamProtocol.HLS, context = EncodingContext.STREAMING, maxAudioChannels = "2", minSegments = 1, breakOnNonKeyFrames = true, conditions = emptyList(),
            ),
        ),
        subtitleProfiles = listOf(
            SubtitleProfile("vtt", SubtitleDeliveryMethod.EXTERNAL),
            SubtitleProfile("webvtt", SubtitleDeliveryMethod.EXTERNAL),
            SubtitleProfile("srt", SubtitleDeliveryMethod.EXTERNAL),
            SubtitleProfile("subrip", SubtitleDeliveryMethod.EXTERNAL),
            SubtitleProfile("ass", SubtitleDeliveryMethod.EXTERNAL),
            SubtitleProfile("ssa", SubtitleDeliveryMethod.EXTERNAL),
        ),
        codecProfiles = listOf(
            CodecProfile(
                CodecType.VIDEO,
                listOf(
                    ProfileCondition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.VIDEO_LEVEL, "42", true),
                    ProfileCondition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.WIDTH, "1920", true),
                    ProfileCondition(ProfileConditionType.NOT_EQUALS, ProfileConditionValue.IS_ANAMORPHIC, "true", true),
                ),
                emptyList(), "h264", null, null,
            ),
        ),
    )
}
