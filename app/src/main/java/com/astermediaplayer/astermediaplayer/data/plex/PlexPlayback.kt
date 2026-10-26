package com.astermediaplayer.astermediaplayer.data.plex

import com.astermediaplayer.astermediaplayer.data.SubtitleCue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject

/** Playing from Plex: whether the file plays as it is, the transcoder's settings when it doesn't, and what skip markers and subtitle files say. */
object PlexPlayback {
    /** Plex's codec names as the device profile writes them. */
    private fun codecName(plex: String?): String = when (val c = (plex ?: "").lowercase()) {
        "dca", "dts-hd", "dca-ma" -> "dts"
        "matroska" -> "mkv"
        else -> c
    }

    private fun list(value: String?): List<String> = (value ?: "").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /**
     * Whether the file can be played as it is on a device that sent [profile] (the Jellyfin device profile the app builds from what the
     * device decodes): its container, picture and sound are among the profile's direct-play ones, and the picture is within the
     * size and bit depth the device manages. A file bigger than [maxBitrate] is converted down regardless.
     */
    fun canDirectPlay(profile: JsonObject?, media: JSONObject, part: JSONObject, maxBitrate: Long?): Boolean {
        if (profile == null) return false
        val streams = part.optJSONArray("Stream")?.objects().orEmpty()
        val video = streams.firstOrNull { it.optInt("streamType") == 1 }
        val audio = streams.firstOrNull { it.optInt("streamType") == 2 && (it.optBoolean("selected") || it.optBoolean("default")) } ?: streams.firstOrNull { it.optInt("streamType") == 2 }
        val container = codecName(part.str("container") ?: media.str("container"))
        val videoCodec = codecName(video?.str("codec") ?: media.str("videoCodec"))
        val audioCodec = codecName(audio?.str("codec") ?: media.str("audioCodec"))
        val bitrate = media.longOrNull("bitrate")?.times(1000)
        if (maxBitrate != null && bitrate != null && bitrate > maxBitrate) return false

        val profiles = (profile["DirectPlayProfiles"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val matches = profiles.any { p ->
            (p["Type"] as? JsonPrimitive)?.content.equals("Video", ignoreCase = true) &&
                list((p["Container"] as? JsonPrimitive)?.content).let { it.isEmpty() || container in it } &&
                list((p["VideoCodec"] as? JsonPrimitive)?.content).let { it.isEmpty() || videoCodec in it } &&
                list((p["AudioCodec"] as? JsonPrimitive)?.content).let { it.isEmpty() || audio == null || audioCodec in it }
        }
        if (!matches) return false

        // The size and bit depth each decoder manages.
        val conditions = (profile["CodecProfiles"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { (it["Type"] as? JsonPrimitive)?.content.equals("Video", ignoreCase = true) && list((it["Codec"] as? JsonPrimitive)?.content).let { c -> c.isEmpty() || videoCodec in c } }
            .flatMap { (it["Conditions"] as? JsonArray).orEmpty().mapNotNull { c -> c as? JsonObject } }
        return conditions.all { c ->
            val property = (c["Property"] as? JsonPrimitive)?.content
            val limit = (c["Value"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            val required = (c["IsRequired"] as? JsonPrimitive)?.content == "true"
            val actual = when (property) {
                "Width" -> video?.intOrNull("width") ?: media.intOrNull("width")
                "Height" -> video?.intOrNull("height") ?: media.intOrNull("height")
                "VideoBitDepth" -> video?.intOrNull("bitDepth")
                else -> return@all true
            }?.toDouble()
            when {
                limit == null -> true
                actual == null -> !required
                else -> when ((c["Condition"] as? JsonPrimitive)?.content) {
                    "LessThanEqual" -> actual <= limit
                    "LessThan" -> actual < limit
                    "GreaterThanEqual" -> actual >= limit
                    "Equals" -> actual == limit
                    else -> true
                }
            }
        }
    }

    /** A subtitle track Aster can fetch and read: a file beside the video, in a text format. */
    fun isFetchableText(stream: JSONObject): Boolean = stream.has("key") && PlexSubtitles.isText(stream.str("codec"))

    /** The tracks as a source lists them: fetchable text as external files, the rest in the file (drawn by the player) or burned in when converted. */
    fun playbackStream(stream: JSONObject, converting: Boolean): JsonObject {
        val base = PlexMapper.stream(stream)
        if (stream.optInt("streamType") != 3) return base
        val text = isFetchableText(stream)
        val delivery = when { text -> "External"; converting -> "Encode"; else -> "Embed" }
        return JsonObject(base + buildJsonObject {
            put("IsTextSubtitleStream", text || PlexSubtitles.isText(stream.str("codec")))
            put("DeliveryMethod", delivery)
            if (text) stream.str("key")?.let { put("DeliveryUrl", it) }
        })
    }

    /** The transcoder's settings, for its decision and its HLS stream. [burnIn] is a picture subtitle's index; null for none. */
    fun transcodeQuery(
        itemId: String, session: String, startTicks: Long, maxBitrate: Long, burnIn: Int?, hostIsLocal: Boolean,
        token: String, device: PlexDevice,
    ): Map<String, String?> = buildMap {
        put("path", "/library/metadata/$itemId")
        put("mediaIndex", "0")
        put("partIndex", "0")
        put("protocol", "hls")
        put("fastSeek", "1")
        put("directPlay", "0")
        put("directStream", "1")
        put("directStreamAudio", "1")
        put("videoQuality", "100")
        put("maxVideoBitrate", maxOf(1L, maxBitrate / 1000).toString())
        put("offset", (startTicks / 10_000_000.0).toString())
        put("subtitles", if (burnIn == null) "none" else "burn")
        put("location", if (hostIsLocal) "lan" else "wan")
        put("session", session)
        put("X-Plex-Session-Identifier", session)
        put("X-Plex-Client-Identifier", device.clientIdentifier)
        put("X-Plex-Product", PlexDevice.PRODUCT)
        put("X-Plex-Platform", "Android")
        put("X-Plex-Token", token)
        if (burnIn != null) { put("SubtitleMethod", "Encode"); put("SubtitleStreamIndex", burnIn.toString()) }
    }

    /** Plex Pass's intro and credits markers; failing those, chapters named as an opening or ending (anime libraries have them). */
    fun segments(item: JSONObject, itemId: String): List<JsonObject> {
        fun ticks(ms: Long) = ms * PlexMapper.TICKS_PER_MS
        fun segment(type: String, start: Long, end: Long) = buildJsonObject {
            put("Id", java.util.UUID.nameUUIDFromBytes("segment:$itemId:$type".toByteArray()).toString())
            put("ItemId", PlexMapper.id(itemId))
            put("Type", type)
            put("StartTicks", ticks(start))
            put("EndTicks", ticks(end))
        }
        val found = mutableListOf<JsonObject>()
        for (marker in item.optJSONArray("Marker")?.objects().orEmpty()) {
            val start = marker.optLong("startTimeOffset")
            val end = marker.optLong("endTimeOffset")
            if (end <= start) continue
            when (marker.optString("type")) {
                "intro" -> found += segment("Intro", start, end)
                "credits" -> found += segment("Outro", start, end)
            }
        }
        if (found.isNotEmpty()) return found

        // Chapters named for what they are, ending where the next begins.
        val chapters = item.optJSONArray("Chapter")?.objects().orEmpty().sortedBy { it.optLong("startTimeOffset") }
        val duration = item.longOrNull("duration")
        val types = mutableSetOf<String>()
        chapters.forEachIndexed { i, chapter ->
            val type = when (chapter.optString("tag").trim().lowercase()) {
                "opening", "op", "intro", "opening credits" -> "Intro"
                "ending", "ed", "credits", "end credits", "outro" -> "Outro"
                else -> return@forEachIndexed
            }
            if (!types.add(type)) return@forEachIndexed
            val start = chapter.optLong("startTimeOffset")
            val end = chapter.longOrNull("endTimeOffset") ?: chapters.getOrNull(i + 1)?.optLong("startTimeOffset") ?: duration
            if (end != null && end > start) found += segment(type, start, end)
        }
        return found
    }
}

/** Subtitle files beside a video: SRT, WebVTT, or ASS/SSA as plain text. */
object PlexSubtitles {
    fun isText(codec: String?): Boolean = (codec ?: "").lowercase() in setOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa", "mov_text", "text")

    fun cues(text: String, codec: String?): List<SubtitleCue> {
        val c = (codec ?: "").lowercase()
        return if (c == "ass" || c == "ssa" || "[Events]" in text) assCues(text) else timedBlocks(text)
    }

    /** SRT and WebVTT: blocks of a time line and text, apart by blank lines. */
    fun timedBlocks(text: String): List<SubtitleCue> {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val cues = mutableListOf<SubtitleCue>()
        for (block in normalized.split("\n\n")) {
            val lines = block.split("\n")
            val timeIndex = lines.indexOfFirst { "-->" in it }
            if (timeIndex < 0) continue
            val parts = lines[timeIndex].split("-->")
            if (parts.size != 2) continue
            val start = seconds(parts[0]) ?: continue
            val end = seconds(parts[1].trim().split(" ").first()) ?: continue
            val body = stripTags(lines.drop(timeIndex + 1).joinToString("\n")).trim()
            if (body.isNotEmpty()) cues += SubtitleCue((start * 1000).toLong(), (end * 1000).toLong(), body)
        }
        return cues.sortedBy { it.startMs }
    }

    /** ASS/SSA: each Dialogue line's times and text, styling taken out. */
    fun assCues(text: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        for (line in text.lines()) {
            if (!line.startsWith("Dialogue:")) continue
            val fields = line.removePrefix("Dialogue:").split(",", limit = 10)
            if (fields.size != 10) continue
            val start = seconds(fields[1]) ?: continue
            val end = seconds(fields[2]) ?: continue
            val body = fields[9].replace("\\N", "\n").replace("\\n", "\n").replace(Regex("\\{[^}]*\\}"), "").trim()
            if (body.isNotEmpty()) cues += SubtitleCue((start * 1000).toLong(), (end * 1000).toLong(), body)
        }
        return cues.sortedBy { it.startMs }
    }

    /** "01:02:03,456", "01:02:03.456", "02:03.456" or ASS's "1:02:03.45". */
    fun seconds(text: String): Double? {
        val parts = text.trim().replace(',', '.').split(":")
        if (parts.size !in 2..3) return null
        var total = 0.0
        for (part in parts) total = total * 60 + (part.toDoubleOrNull() ?: return null)
        return total
    }

    private fun stripTags(text: String): String = text.replace(Regex("<[^>]+>"), "").replace(Regex("\\{\\\\[^}]*\\}"), "")
}
