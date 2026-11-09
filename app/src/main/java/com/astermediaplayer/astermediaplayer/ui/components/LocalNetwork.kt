package com.astermediaplayer.astermediaplayer.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Android 17's local network permission. An app targeting 37 can't reach the home network without it: not a server at
 * 192.168.1.240, not a Plex server's local address, not Aster for Plex beside it, not a Chromecast. Without it a connection
 * there doesn't fail outright, it times out. It's in the Nearby devices group, so someone who's allowed that isn't asked again.
 * Before 17, INTERNET covers it and there's nothing to ask.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Whether the home network is shut to the app: Android 17 or later and the permission not given. */
    fun blocked(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED

    /**
     * Whether an address is plainly on the home network: a private, link-local or unique-local IP, a `.local` name, or a bare
     * name like `nas`. A name that only resolves to one (`jellyfin.lan` through the router's DNS) can't be told from here.
     */
    fun isLocalAddress(input: String): Boolean {
        val text = input.trim()
        val host = runCatching { java.net.URI(if ("://" in text) text else "http://$text").host }.getOrNull()
            ?.trim('[', ']')?.lowercase()
            ?: return false
        if (host.endsWith(".local") || (host.isNotEmpty() && '.' !in host && ':' !in host && host != "localhost")) return true
        val v4 = host.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false }
        if (v4 != null) {
            val (a, b) = v4
            return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        if (':' in host) return host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")
        return false
    }

    /** What to say when reaching a home-network address failed because the permission's been refused. */
    const val REFUSED =
        "Aster isn't allowed on your local network, so it can't reach this server. " +
            "Allow it under Settings › Apps › Aster › Permissions › Nearby devices, then try again."
}

/**
 * Something to run once the home network's open: asks for the permission first where Android 17 wants it, then runs
 * [then] whatever the answer, as a server elsewhere still works without it.
 */
@Composable
fun rememberLocalNetworkAccess(): (then: () -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending[0]?.invoke()
        pending[0] = null
    }
    val current = rememberUpdatedState(context)
    return remember {
        { then ->
            if (LocalNetwork.blocked(current.value)) {
                pending[0] = then
                runCatching { launcher.launch(LocalNetwork.PERMISSION) }.onFailure { pending[0] = null; then() }
            } else {
                then()
            }
        }
    }
}
