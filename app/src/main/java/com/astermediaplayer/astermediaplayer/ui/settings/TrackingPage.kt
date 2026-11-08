package com.astermediaplayer.astermediaplayer.ui.settings

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.TrackedAccount
import com.astermediaplayer.astermediaplayer.data.TrackedAccounts
import com.astermediaplayer.astermediaplayer.data.TrackedLink
import com.astermediaplayer.astermediaplayer.data.TrackedLinkResult
import com.astermediaplayer.astermediaplayer.data.TrackedService
import com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/*
 * Settings ▸ Tracking, on a server with the Aster Discovery and Tracking plugin: each service linked once, for every app,
 * and what's watched or listened to sent by the server. Link, Unlink, and a switch for sending; and the plugin's log of
 * the last sends, which is how people see it work. The counterpart of the Apple apps' PluginTrackingView.swift.
 */

@Composable
internal fun TrackingPage(repo: AsterRepository) {
    val scope = rememberCoroutineScope()
    val tv = LocalIsTv.current
    var accounts by remember { mutableStateOf<TrackedAccounts?>(null) }
    var offered by remember { mutableStateOf<List<String>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var linking by remember { mutableStateOf<TrackedService?>(null) }

    suspend fun load() {
        offered = repo.trackingFeatures()?.services.orEmpty().map { it.lowercase() }
        try {
            accounts = repo.trackedAccounts()
            problem = null
        } catch (e: Exception) {
            problem = "Your server's linked accounts couldn't be read (${e.message})."
        }
        loaded = true
    }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            try { block(); load() } catch (e: Exception) { problem = e.message ?: "That didn't work. Try again." }
        }
    }
    LaunchedEffect(Unit) { load() }
    // Linked somewhere else meanwhile (the dashboard, another app): asked again on coming back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (loaded) scope.launch { load() } }

    if (!loaded) { CircularProgressIndicator(); return }

    val shown = TrackedService.entries.filter { it.id in offered && accounts?.account(it)?.available != false }
    val groups = TrackedService.groupOrder.mapNotNull { group -> shown.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { group to it } }
    if (groups.isEmpty() && problem == null) SNote("This server's plugin has no tracking services set up yet. Its admin can add them in the plugin's settings.")
    groups.forEach { (group, services) ->
        SGroup(group)
        services.forEach { service ->
            val account = accounts?.account(service)
            if (account?.linked == true) {
                SRow(service.title, subtitle = linkedLine(account), onClick = if (tv) ({}) else null)
                SSwitch("Send what I ${service.verb}", account.scrobble ?: true) { on -> act { repo.setTrackingScrobble(service, on) } }
                SRow("Unlink ${service.title}", destructive = true) { act { repo.unlinkTracking(service) } }
            } else if (tv && service.flow == TrackedService.Flow.Paste) {
                // Nobody pastes a token with a remote: those link elsewhere.
                SRow(service.title, "Not linked")
                SNote("Link it on your server's dashboard (Plugins ▸ Aster Discovery and Tracking), or in Aster on your phone.")
            } else {
                SRow(service.title, "Link", chevron = true) { linking = service }
            }
        }
    }
    if (groups.isNotEmpty()) SNote("Linked on your server, so every Aster app on this server account has them, this one included, and the server sends what you watch or listen to, from any app.")

    accounts?.log?.takeIf { it.isNotEmpty() }?.let { log ->
        SGroup("Recent Sends")
        log.take(8).forEach { entry ->
            val line = listOfNotNull(entry.service?.let { id -> TrackedService.entries.firstOrNull { it.id.equals(id, true) }?.title ?: id }, entry.result, entry.at?.let(::shortDate)).joinToString(" · ")
            SRow(entry.title ?: "Something", subtitle = line, onClick = if (tv) ({}) else null)
        }
        accounts?.waiting?.takeIf { it > 0 }?.let { SNote("$it waiting to be tried again.") }
    }
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

    linking?.let { service ->
        TrackingLinkDialog(repo, service, onLinked = { scope.launch { load() } }, onDismiss = { linking = null })
    }
}

/** "Linked as Name", and when AniList's link needs doing again. */
private fun linkedLine(account: TrackedAccount): String {
    var line = account.name?.let { "Linked as $it" } ?: "Linked"
    val expires = account.expires?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (expires != null) {
        val day = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(expires)
        line += if (expires.isBefore(Instant.now().plusSeconds(30L * 24 * 3600))) " · link again by $day" else " · until $day"
    }
    return line
}

private fun shortDate(text: String): String? = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.parse(text))
}.getOrNull()

/**
 * Linking one service: a code to enter on its site (with a QR code), an approval to give there, or a code to paste back,
 * while the server waits on the service and the app asks every few seconds whether it's done.
 */
@Composable
private fun TrackingLinkDialog(repo: AsterRepository, service: TrackedService, onLinked: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf<TrackedLink?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var expired by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var pasted by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    fun finished() {
        DebugLog.note("tracking", "Linked ${service.id}")
        onLinked()
        onDismiss()
    }

    /** One check for the code and approve flows; true once it's over (linked or ran out). */
    suspend fun check(): Boolean = when (val result = runCatching { repo.completeTrackingLink(service) }.getOrDefault(TrackedLinkResult.Waiting)) {
        TrackedLinkResult.Linked -> { finished(); true }
        TrackedLinkResult.Expired -> { expired = true; true }
        is TrackedLinkResult.Failed -> { problem = result.message; false }
        TrackedLinkResult.Waiting -> false
    }

    LaunchedEffect(attempt) {
        expired = false
        problem = null
        link = null
        try {
            val started = repo.startTrackingLink(service)
            link = started
            if (started.paste) return@LaunchedEffect
            val interval = maxOf(started.interval ?: 5, 3) * 1000L
            val deadline = System.currentTimeMillis() + (started.expiresIn ?: 600) * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(interval)
                if (check()) return@LaunchedEffect
            }
            expired = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = e.message ?: "${service.title} couldn't be reached."
        }
    }
    // A page in the background stops polling: asked again on coming back from the browser.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val current = link
        if (current != null && !current.paste && !expired) scope.launch { check() }
    }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { problem = "Open $url on a phone or computer." }
    }

    fun submit() {
        working = true
        scope.launch {
            when (val result = runCatching { repo.completeTrackingLink(service, pasted.trim()) }.getOrElse { TrackedLinkResult.Failed(it.message ?: "That didn't work.") }) {
                TrackedLinkResult.Linked -> finished()
                TrackedLinkResult.Waiting -> problem = "Not linked yet. Try again."
                TrackedLinkResult.Expired -> expired = true
                is TrackedLinkResult.Failed -> problem = result.message
            }
            working = false
        }
    }

    val current = link
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link ${service.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    current == null && problem == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    current == null -> {}
                    current.paste -> {
                        Text(
                            if (service == TrackedService.ListenBrainz) "Open ListenBrainz, copy your user token, and paste it here."
                            else "Open ${service.title}, sign in and approve Aster, then paste the code it shows here.",
                        )
                        TextButton({ open(current.url) }) { Text("Open ${service.title}") }
                        OutlinedTextField(pasted, { pasted = it }, label = { Text("Paste the code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    current.code != null -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            QrCode(current.completeUrl ?: current.url)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("On a phone or computer, go to", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Address(current.url) { open(current.url) }
                                Text("and enter", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SelectionContainer { Text(current.code, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
                            }
                        }
                        if (!expired) Waiting(service)
                    }
                    else -> {
                        // Last.fm: approve Aster on its page.
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            QrCode(current.url)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Open ${service.title} and approve Aster.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Address(current.url) { open(current.url) }
                                Text("${service.title} may show an error page afterwards. Ignore it and come back here.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (!expired) Waiting(service)
                    }
                }
                if (expired) Text("That code ran out.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                expired -> TextButton({ attempt++ }) { Text("Try Again") }
                current?.paste == true -> TextButton(::submit, enabled = pasted.isNotBlank() && !working) { Text("Link ${service.title}") }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Address(url: String, onOpen: () -> Unit) {
    val tv = LocalIsTv.current
    val shown = url.removePrefix("https://")
    // A TV has no browser to send it to: the address is there to type on a phone.
    if (tv) Text(shown, style = MaterialTheme.typography.titleMedium)
    else TextButton(onOpen) { Text(shown, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun Waiting(service: TrackedService) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("Waiting for ${service.title}…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The address as a QR code, black on white, for a phone's camera. */
@Composable
private fun QrCode(text: String) {
    val bitmap = remember(text) { qrBitmap(text) } ?: return
    Image(bitmap.asImageBitmap(), null, Modifier.size(if (LocalIsTv.current) 200.dp else 140.dp), filterQuality = FilterQuality.None)
}

private fun qrBitmap(text: String): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
        bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    bitmap
}.getOrNull()
