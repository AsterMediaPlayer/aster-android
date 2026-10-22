package com.astermediaplayer.astermediaplayer.data

import org.jellyfin.sdk.model.api.DeviceProfile
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
    fun build(maxBitrate: Int) = DeviceProfile(
        name = "Aster Android",
        maxStreamingBitrate = maxBitrate,
        maxStaticBitrate = maxBitrate,
        musicStreamingTranscodingBitrate = 320_000,
        directPlayProfiles = listOf(
            DirectPlayProfile(
                container = "mp4,m4v,mkv,webm",
                type = DlnaProfileType.VIDEO,
                videoCodec = "h264,hevc,vp9,av1",
                audioCodec = "aac,mp3,opus,flac,vorbis,ac3,eac3",
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
                videoCodec = "h264",
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
}
