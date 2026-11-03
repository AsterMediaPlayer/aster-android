package com.astermediaplayer.astermediaplayer.data.diagnostics

/**
 * What a debug report leaves out: anything that signs in (tokens, keys, passwords), where the server is (addresses, host
 * names, IPs), who's using it (the user's name, email addresses), and the library's file paths, which name the user's titles.
 * Every line of a report passes through [text] before it leaves the device.
 */
object Redact {
    private val rules: List<Pair<Regex, String>> = listOf(
        // Tokens and keys in addresses and headers: api_key=…, ApiKey=…, X-Emby-Token: …, X-Plex-Token=…, Token="…".
        Regex("""(?i)\b(api_?key|access_?token|x-emby-token|x-mediabrowser-token|x-plex-token|token|password|pw)(["']?\s*[:=]\s*["']?)[^\s"'&,;}]+""") to "$1$2<hidden>",
        // A MediaBrowser authorization header's token (and its device id, which can be traced to the device).
        Regex("""(?i)\b(Token|DeviceId)="[^"]*"""") to "$1=\"<hidden>\"",
        // A bearer or basic authorization.
        Regex("""(?i)\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{8,}""") to "$1 <hidden>",
        // An address: scheme and host (with any user and port), the path kept, since it says what was asked for.
        Regex("""(?i)\b(https?|wss?|rtsp)://[^\s/"'<>]+""") to "$1://<server>",
        // Plex's own host names carry the server's IP and id.
        Regex("""(?i)\b[0-9a-z-]+\.(?:[0-9a-f]+\.)?plex\.direct\b""") to "<server>",
        // IPv4 and IPv6 addresses outside an address; a version number ("Fire OS 8.1.0.3", "v1.2.3.4") isn't one.
        Regex("""(?i)(?<!\b(?:os|version|build|v)\s?)(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""") to "<ip>",
        Regex("""(?i)\b(?:[0-9a-f]{1,4}:){4,7}[0-9a-f]{1,4}\b""") to "<ip>",
        // Email addresses.
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "<email>",
        // A media file's place in the library, which names the title: kept to its kind of file.
        Regex("""(?i)(?:[A-Z]:\\|/(?:mnt|media|data|srv|share|volume\d*|storage|home|Users|nas|library|movies|tv)/)[^\n"'<>]*?\.(mkv|mp4|m4v|avi|ts|m2ts|iso|mov|webm|mp3|flac|m4a|m4b|aac|ogg|opus|srt|ass|ssa|vtt|sup)\b""") to "<path>.$1",
    )

    /** A server's name that says nothing of whose it is (Emby and Jellyfin call themselves "Server"): left as it is. */
    private val generic = setOf("server", "emby", "jellyfin", "plex", "media", "home", "localhost")

    /**
     * [value] with everything above taken out; [names] (the user's name and account id, the server's own name) go too, as whole
     * words and never inside a placeholder already put in. An id goes with and without its dashes.
     */
    fun text(value: String, names: Collection<String> = emptyList()): String {
        var out = value
        for ((pattern, replacement) in rules) out = pattern.replace(out, replacement)
        val words = names.flatMap { listOf(it, it.replace("-", "")) }.distinct()
            .filter { it.length >= 3 && it.lowercase() !in generic }.sortedByDescending { it.length }
        for (name in words) {
            out = Regex("(?i)(?<![A-Za-z0-9<])" + Regex.escape(name) + "(?![A-Za-z0-9>])").replace(out, "<name>")
        }
        return out
    }
}
