package com.astermediaplayer.astermediaplayer.data

import java.net.URI

/**
 * Whether an address sends everything in the clear over the internet: plain http:// to a host that isn't on the home
 * network. Aster allows it (many self-hosted servers are reached that way), and warns before a password is sent. The
 * Apple apps' `ServerAddress`, with the same rules.
 */
object ServerAddress {
    fun isUnencryptedRemote(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() != "http") return false
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        return !isLocal(host)
    }

    /** A name or address only the home network (or a private overlay such as Tailscale) reaches. */
    fun isLocal(name: String): Boolean {
        val host = name.trim('[', ']')
        if (host == "localhost" || ('.' !in host && ':' !in host)) return true
        if (listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain").any { host.endsWith(it) }) return true
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size == 4 && host.split('.').size == 4 && parts.all { it in 0..255 }) {
            val (a, b) = parts
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
        }
        if (':' in host) return host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")
        return false
    }
}
