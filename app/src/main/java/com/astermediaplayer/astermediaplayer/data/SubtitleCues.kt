package com.astermediaplayer.astermediaplayer.data

import org.json.JSONObject

/**
 * One subtitle line, in milliseconds from the start of the title.
 *
 * The player never waits on these: Jellyfin extracts a text track from the file on the first request for it, which can
 * take minutes, so the chosen track is fetched in the background as the server's JSON cue list (what its web player
 * reads) and the cue under the playhead is drawn by the app. Same as the Apple apps' `SubtitleCue`.
 */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String) {
    companion object {
        /** The cues showing at [positionMs], top to bottom, or null for none. [cues] must be sorted by start. */
        fun textAt(cues: List<SubtitleCue>, positionMs: Long): String? {
            if (cues.isEmpty()) return null
            // First cue starting after the position; everything before it may be showing.
            var low = 0
            var high = cues.size
            while (low < high) {
                val mid = (low + high) / 2
                if (cues[mid].startMs <= positionMs) low = mid + 1 else high = mid
            }
            // Cues overlap rarely and briefly, so a short look back finds them all.
            val showing = ArrayDeque<String>()
            for (i in low - 1 downTo maxOf(0, low - 8)) if (cues[i].endMs > positionMs) showing.addFirst(cues[i].text)
            return if (showing.isEmpty()) null else showing.joinToString("\n")
        }

        /** Decodes Jellyfin's `Stream.js` response: `{"TrackEvents":[...]}` with positions in ticks and text that may carry HTML or ASS markup. */
        fun parse(json: String): List<SubtitleCue> {
            val events = JSONObject(json).optJSONArray("TrackEvents") ?: return emptyList()
            val cues = ArrayList<SubtitleCue>(events.length())
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                val text = plainText(event.optString("Text", ""))
                if (text.isEmpty()) continue
                cues += SubtitleCue(event.getLong("StartPositionTicks") / TICKS_PER_MS, event.getLong("EndPositionTicks") / TICKS_PER_MS, text)
            }
            return cues.sortedBy { it.startMs }
        }

        private const val TICKS_PER_MS = 10_000L

        private val lineBreak = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        private val htmlTag = Regex("<[^>]+>")
        // Both braces escaped: Android's regex engine (ICU) rejects a bare "}", which the desktop JVM the unit tests run on allows.
        private val assBlock = Regex("\\{[^}]*\\}")
        private val entities = listOf("&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&#39;" to "'", "&nbsp;" to " ", "&amp;" to "&")

        /** Cue text as it should read on screen: line breaks kept, markup (`<i>`, `{\an8}`, `<br>`) removed, entities decoded. */
        fun plainText(raw: String): String {
            var text = withoutDrawings(raw)
                .replace("\r\n", "\n")
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace(lineBreak, "\n")
                .replace(htmlTag, "")
                .replace(assBlock, "")
            // "&amp;" last, so "&amp;lt;" reads as the text "&lt;" and not as "<".
            for ((entity, character) in entities) text = text.replace(entity, character)
            return text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        }

        private val drawingTag = Regex("\\\\p(\\d+)(?![a-z])")

        /**
         * ASS text with its vector drawings left out. `{\p1}` (any `\p` above 0) makes the text that follows shape
         * commands, such as the boxes behind signs ("m 0 0 l 100 0 100 50"), until `{\p0}`; shown as text they read as
         * coordinates. `\pos` and `\pbo` are other tags.
         */
        fun withoutDrawings(raw: String): String {
            if (!raw.contains("\\p")) return raw
            val result = StringBuilder()
            var drawing = false
            var rest = raw
            while (true) {
                val open = rest.indexOf('{')
                if (open < 0) break
                if (!drawing) result.append(rest, 0, open)
                val close = rest.indexOf('}', open)
                if (close < 0) {
                    rest = rest.substring(open)
                    break
                }
                val block = rest.substring(open, close + 1)
                // The last \p in a block decides, as in a renderer.
                drawingTag.findAll(block).lastOrNull()?.let { drawing = it.groupValues[1].toInt() > 0 }
                result.append(block)
                rest = rest.substring(close + 1)
            }
            if (!drawing) result.append(rest)
            return result.toString()
        }
    }
}
