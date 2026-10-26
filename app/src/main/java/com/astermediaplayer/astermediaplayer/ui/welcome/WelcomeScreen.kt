package com.astermediaplayer.astermediaplayer.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.height as heightOf
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MusicVideo
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astermediaplayer.astermediaplayer.data.AsterPlugin
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.PluginState
import com.astermediaplayer.astermediaplayer.data.ServerPluginsModel
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.SecondaryText
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import kotlinx.coroutines.launch

/**
 * Shown once for each Jellyfin server signed in to: what Aster does, and what its companion plugins add. An administrator
 * can switch the plugins on here and restart the server; anyone else is told to ask theirs. The plugin rows are also
 * Settings > Server Plugins. The counterpart of the Apple apps' `WelcomeView`.
 */
@Composable
fun WelcomeScreen(repo: AsterRepository, serverName: String?, onDone: () -> Unit) {
    val isTv = LocalIsTv.current
    val model = remember { ServerPluginsModel(repo) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.load() }
    val admin = model.isAdministrator
    val restarting = model.restart == ServerPluginsModel.Restart.Restarting
    // On a TV, sizes are the Apple TV's, in the dp one of its points comes to here.
    val u = Lockup.tvUnit
    val spacing = if (isTv) (40 * u).dp else 18.dp
    val first = remember { FocusRequester() }
    // The remote starts on the first card, at the top, and walks the page card by card.
    // (After the page has laid out, or the page-level focus restore that follows takes it instead.)
    LaunchedEffect(isTv) { if (isTv) { kotlinx.coroutines.delay(200); runCatching { first.requestFocus() } } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
                .padding(horizontal = if (isTv) Lockup.screenPadding else 24.dp, vertical = if (isTv) (60 * u).dp else 32.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv) (60 * u).dp else 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Aster", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Text("Welcome to Aster", style = Type.pageTitle, color = Color.White, textAlign = TextAlign.Center)
                Text(
                    "Here's everything that's waiting for you on ${serverName ?: "your server"}.",
                    style = Type.headline.copy(fontWeight = FontWeight.Normal), color = SecondaryText, textAlign = TextAlign.Center,
                )
            }

            Section("Ready the moment you sign in") {
                Cards(spacing, minWidth = if (isTv) (480 * u).dp else 280.dp) {
                    card { InfoCard(Icons.Default.PlayCircle, null, "Plays it all, as it was meant to be",
                        "HDR, Dolby Vision, and surround sound like TrueHD and DTS go straight from your files to your screen when your device can play them. Even fancy anime subtitles look exactly as their makers styled them.", it.focusRequester(first)) }
                    card { InfoCard(Icons.Default.Tv, null, "Your own TV channels",
                        "Turn any playlist into a channel that's always on, then flip through them like cable, with a guide that shows what's on now and next.", it) }
                    card { InfoCard(Icons.Default.LibraryMusic, null, "Music and audiobooks",
                        "Albums keep playing while you browse, and every audiobook picks up right where you left off, chapter and all.", it) }
                }
            }

            Section(
                "Unlock even more",
                if (admin == false) "Free add-ons for your Jellyfin server that light up new parts of Aster."
                else "Free add-ons for your Jellyfin server that light up new parts of Aster. Switch one on and Aster installs it for you.",
            ) {
                if (admin == null) {
                    Box(Modifier.fillMaxWidth().heightOf(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    Cards(spacing, minWidth = if (isTv) (480 * u).dp else 280.dp) {
                        for (plugin in AsterPlugin.entries) card { PluginCard(plugin, model, admin, restarting, it) { on -> scope.launch { model.set(plugin, on) } } }
                    }
                }
                RestartControls(model)
                model.errorMessage?.let { Text(it, style = Type.callout, color = Color(0xFFFF453A)) }
                Cards(spacing, minWidth = if (isTv) (720 * u).dp else 420.dp) {
                    card { InfoCard(Icons.Default.AutoAwesome, "Aster Aurora · for the web", "Love the look? Take it to your browser",
                        "Aurora brings as much of Aster as it can to Jellyfin on the web: big hero banners, trailers playing behind titles, and more. Once Aster's repository is on your server, you'll find it in the plugin catalogue in Jellyfin's dashboard.", it) }
                    if (admin == false) card {
                        InfoCard(Icons.Default.VpnKey, null, "Want these? Ask whoever runs your server",
                            "They can switch them on from Aster in a few seconds, or add this repository under Plugins > Repositories in Jellyfin's dashboard:", it) {
                            Text(AsterPlugin.REPOSITORY_URL, style = Type.caption.copy(fontFamily = FontFamily.Monospace), color = SecondaryText, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            // Not while the server restarts: the app needs it back.
            Button(
                onClick = onDone, enabled = !restarting,
                modifier = Modifier.widthIn(min = if (isTv) (420 * u).dp else 260.dp).heightOf(if (isTv) (90 * u).dp else 52.dp).tvFocus(26.dp),
            ) { Text("Start Watching", style = Type.headline) }
        }
    }
}

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (LocalIsTv.current) (24 * Lockup.tvUnit).dp else 14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = Type.title3, color = Color.White)
            subtitle?.let { Text(it, style = Type.callout, color = SecondaryText) }
        }
        content()
    }
}

/** What [Cards] lays out: each entry draws one card in the modifier it's given. */
private class CardList { val cards = mutableListOf<@Composable (Modifier) -> Unit>(); fun card(content: @Composable (Modifier) -> Unit) { cards += content } }

/** Cards side by side across the whole width, as many as fit, all the same height in a row; one above the other on a phone. */
@Composable
private fun Cards(spacing: Dp, minWidth: Dp, build: CardList.() -> Unit) {
    val list = CardList().apply(build).cards
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = maxOf(1, ((maxWidth + spacing) / (minWidth + spacing)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            list.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    row.forEach { card -> card(Modifier.weight(1f).fillMaxHeight()) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A panel: an icon (and an accessory at its top right: a switch), a title and a few lines, and anything more below. */
@Composable
private fun Card(
    icon: ImageVector, eyebrow: String?, title: String, text: String, modifier: Modifier,
    onClick: (() -> Unit)? = null, accessory: @Composable () -> Unit = {}, detail: @Composable () -> Unit = {},
) {
    val isTv = LocalIsTv.current
    val u = Lockup.tvUnit
    val shape = RoundedCornerShape(if (isTv) (28 * u).dp else 20.dp)
    // On a TV every card takes focus, so the remote can walk the whole page; the card draws its own ring.
    val interact = when {
        onClick != null -> Modifier.tvFocus(if (isTv) (28 * u).dp else 28.dp).clip(shape).clickable(onClick = onClick)
        isTv -> Modifier.tvFocus(if (isTv) (28 * u).dp else 28.dp).focusable()
        else -> Modifier
    }
    Column(
        modifier.then(interact).clip(shape).background(Color.White.copy(alpha = 0.07f)).padding(if (isTv) (36 * u).dp else 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.height(if (isTv) (52 * u).dp else 30.dp).widthIn(min = if (isTv) (52 * u).dp else 30.dp))
            accessory()
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            eyebrow?.let { Text(it.uppercase(), style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = SecondaryText) }
            Text(title, style = Type.headline, color = Color.White)
            Text(text, style = Type.callout, color = SecondaryText)
            detail()
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, eyebrow: String?, title: String, text: String, modifier: Modifier, detail: @Composable () -> Unit = {}) =
    Card(icon, eyebrow, title, text, modifier, detail = detail)

/** A companion plugin as a card: an administrator switches it on and off (on a TV the whole card is the switch); anyone else sees whether it's on the server. */
@Composable
private fun PluginCard(plugin: AsterPlugin, model: ServerPluginsModel, admin: Boolean, restarting: Boolean, modifier: Modifier, set: (Boolean) -> Unit) {
    val isTv = LocalIsTv.current
    val state = model.state(plugin)
    val canSwitch = admin && state.canSwitch && !restarting
    val icon = when (plugin) { AsterPlugin.Channels -> Icons.Default.LiveTv; AsterPlugin.Disc -> Icons.Default.Album; AsterPlugin.OpEd -> Icons.Default.MusicVideo }
    val status: @Composable () -> Unit = {
        val caption = state.caption("Jellyfin")
        if (caption.isNotEmpty()) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state == PluginState.Installing) CircularProgressIndicator(Modifier.height(14.dp).widthIn(max = 14.dp), strokeWidth = 2.dp)
            Text(caption, style = Type.caption.copy(fontWeight = FontWeight.Medium), color = if (state == PluginState.Active || state == PluginState.Present(true)) Color(0xFF30D158) else SecondaryText)
        }
    }
    when {
        admin && isTv -> Card(
            icon, plugin.packageName, plugin.headline, plugin.summary, modifier,
            // Never inert: a press does nothing while it can't switch, but the remote still lands on the card.
            onClick = { if (canSwitch) set(!state.isOn) },
            accessory = { if (state.canSwitch) OnOffBadge(state.isOn) }, detail = status,
        )
        admin -> Card(icon, plugin.packageName, plugin.headline, plugin.summary, modifier, accessory = { Switch(state.isOn, { set(it) }, enabled = canSwitch) }, detail = status)
        else -> Card(icon, plugin.packageName, plugin.headline, plugin.summary, modifier, detail = status)
    }
}

/** A TV's stand-in for a switch on a card that is itself the button. */
@Composable
private fun OnOffBadge(on: Boolean) {
    Text(
        if (on) "On" else "Off", style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = Color.White,
        modifier = Modifier.clip(CircleShape).background(if (on) Color(0xD930D158) else Color.White.copy(alpha = 0.15f)).padding(horizontal = 18.dp, vertical = 6.dp),
    )
}

/** The restart a plugin change waits on, for an administrator. */
@Composable
private fun RestartControls(model: ServerPluginsModel) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    when (val restart = model.restart) {
        ServerPluginsModel.Restart.Restarting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.height(24.dp).widthIn(max = 24.dp), strokeWidth = 2.dp)
            Text("Restarting your server. This usually takes about a minute…", style = Type.callout, color = SecondaryText)
        }
        is ServerPluginsModel.Restart.Failed -> Text(restart.message, style = Type.callout, color = Color(0xFFFF453A))
        ServerPluginsModel.Restart.Idle -> if (model.isAdministrator == true && model.needsRestart) {
            if (model.canRestart) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Almost there. Your server needs a quick restart to finish.", style = Type.callout, color = SecondaryText)
                OutlinedButton({ confirming = true }, Modifier.tvFocus(24.dp)) { Text("Restart Server") }
            } else Text("Almost there. Restart Jellyfin to finish (it can't restart itself where it's running).", style = Type.callout, color = SecondaryText)
        }
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Restart your server now?") }, text = { Text("Anyone watching will be paused for about a minute.") },
        confirmButton = { TextButton({ confirming = false; scope.launch { model.restartServer() } }) { Text("Restart") } },
        dismissButton = { TextButton({ confirming = false }) { Text("Cancel") } },
    )
}
