package com.astermediaplayer.astermediaplayer.data.emby

import java.util.regex.Pattern

/** Which kind of server this is: Jellyfin, or Emby, whose routes and ids differ a little. */
enum class ServerKind(val raw: String, val displayName: String) {
    Jellyfin("jellyfin", "Jellyfin"), Emby("emby", "Emby");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Jellyfin

        /** As the Apple apps tell them apart: Jellyfin names itself in its public info, an Emby server doesn't. */
        fun fromProductName(productName: String?) = if (productName?.contains("Jellyfin", ignoreCase = true) == true) Jellyfin else Emby
    }
}

/**
 * Emby's item ids are small whole numbers ("12345"), where the Jellyfin SDK's models want a UUID. Each number is carried as a
 * UUID that holds it in its last twelve hex digits and zeros elsewhere (no real UUID looks like that), so the SDK decodes an
 * Emby response unchanged and the number comes back out of any id the app sends.
 */
object EmbyIds {
    private const val PREFIX = "00000000-0000-0000-0000-"
    private val encoded: Pattern = Pattern.compile("00000000-0000-0000-0000-([0-9a-fA-F]{12})")

    /** "12345" as a UUID string; null for anything that isn't a plain number small enough to carry. */
    fun encode(number: String): String? {
        if (number.isEmpty() || number.length > 12 || (number.length > 1 && number[0] == '0')) return null
        val value = number.toLongOrNull() ?: return null
        if (value < 0 || value > 0xFFFFFFFFFFFFL) return null
        return PREFIX + value.toString(16).padStart(12, '0')
    }

    /** A UUID made by [encode] back to its number; anything else comes back as it was. */
    fun decode(text: String): String {
        if (text.length != 36) return text
        val match = encoded.matcher(text)
        return if (match.matches()) match.group(1).toLong(16).toString() else text
    }

    /** Keys that end in "Id" but hold something other than an item: those stay as the server wrote them. */
    private val notItems = setOf("ServerId", "MediaSourceId", "PlaySessionId", "LiveStreamId", "DeviceId", "ClientId", "UserId", "SessionId", "OpenToken", "TagId")

    // A number is written either way: "Id":"479" on an item, "Id":479 on its genres and studios.
    private val idField: Pattern = Pattern.compile("\"([A-Za-z]*Id)\"\\s*:\\s*\"?(\\d{1,12})\"?(?=[,}\\]\\s])")
    private val idList: Pattern = Pattern.compile("\"([A-Za-z]*Ids)\"\\s*:\\s*\\[([^\\]]*)]")
    private val digits: Pattern = Pattern.compile("\"(\\d{1,12})\"")

    /** An Emby JSON response with every item id as the UUID the SDK expects. */
    fun forSdk(json: String): String {
        if (json.length < 8) return json
        var text = json
        text = replace(text, idField) { m ->
            val key = m.group(1)
            val id = if (key in notItems) null else encode(m.group(2))
            if (id == null) m.group() else "\"$key\":\"$id\""
        }
        text = replace(text, idList) { m ->
            val inner = replace(m.group(2), digits) { d -> encode(d.group(1))?.let { "\"$it\"" } ?: d.group() }
            "\"${m.group(1)}\":[$inner]"
        }
        return text
    }

    /** The same walk the other way, over a request body: any string holding an encoded id becomes its number. */
    fun forServer(json: String): String = replace(json, Pattern.compile("\"(00000000-0000-0000-0000-[0-9a-fA-F]{12})\"")) { m -> "\"${decode(m.group(1))}\"" }

    private inline fun replace(text: String, pattern: Pattern, with: (java.util.regex.Matcher) -> String): String {
        val matcher = pattern.matcher(text)
        if (!matcher.find()) return text
        val out = StringBuilder(text.length + 64)
        var last = 0
        do {
            out.append(text, last, matcher.start()).append(with(matcher))
            last = matcher.end()
        } while (matcher.find())
        return out.append(text, last, text.length).toString()
    }
}
