package com.astermediaplayer.astermediaplayer.ui.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.Session
import com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog
import com.astermediaplayer.astermediaplayer.data.profiles.HouseholdProfile
import com.astermediaplayer.astermediaplayer.data.profiles.HouseholdProfiles
import com.astermediaplayer.astermediaplayer.data.profiles.ProfilePickerReason
import com.astermediaplayer.astermediaplayer.data.profiles.ProfileSwitchException
import com.astermediaplayer.astermediaplayer.data.profiles.ProfileSwitchPlan
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Who's watching?" (the Apple apps' `ProfilePickerView`): the household's people as tiles. Picking one switches at once, or asks
 * for their PIN first; the main screen then starts afresh as theirs ([onSwitch]). Shown as the app opens (when the household asks
 * for it) and whenever someone asks to switch. Nothing behind it takes a touch or the remote while it's up.
 */
@Composable
fun ProfilePicker(reason: ProfilePickerReason, households: HouseholdProfiles, repo: AsterRepository, onSwitch: suspend (Session) -> Unit) {
    val tv = LocalIsTv.current
    val u = Lockup.tvUnit
    fun sized(phone: Dp, television: Float) = if (tv) (television * u).dp else phone
    val household by households.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Its own copy: the shared list is cleared as the switch goes through, and the tiles should stay meanwhile.
    var profiles by remember { mutableStateOf(household.profiles) }
    var loading by remember { mutableStateOf(profiles.isEmpty()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var pinFor by remember { mutableStateOf<HouseholdProfile?>(null) }
    var switching by remember { mutableStateOf<HouseholdProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Counts the tries that failed, so each message shows afresh and the PIN clears.
    var attempt by remember { mutableIntStateOf(0) }

    // The tiles as last seen, at once; then the server's fresh list (a PIN can stop being asked on the home network, say).
    LaunchedEffect(Unit) {
        households.restoreCached()
        if (households.state.value.profiles.isNotEmpty()) profiles = households.state.value.profiles
        loading = profiles.isEmpty()
        households.refresh(force = true)
        loading = false
        val now = households.state.value
        if (now.provider != null) { profiles = now.profiles; loadError = null }
        else if (profiles.isEmpty()) loadError = now.lastError ?: ProfileSwitchException.Unavailable().message
        // Opened to ask who's watching, and there's no one else to pick: straight in.
        if (reason == ProfilePickerReason.Launch && now.checked && (now.provider == null || profiles.size <= 1)) households.dismissPicker()
    }

    fun perform(profile: HouseholdProfile, pin: String?) {
        val provider = households.state.value.provider
        if (provider == null) { attempt++; error = ProfileSwitchException.Unavailable().message; return }
        switching = profile; error = null
        scope.launch {
            try {
                onSwitch(provider.switchSession(profile, pin))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.note("profiles", "Switch to a profile failed: ${e.userMessage()}")
                switching = null; attempt++; error = e.userMessage()
                // A wrong PIN (or too many tries): the PIN again. Anything else: back to the tiles.
                pinFor = if (e is ProfileSwitchException.WrongPin || e is ProfileSwitchException.RateLimited || e is ProfileSwitchException.PinNeeded) profile else null
            }
        }
    }

    fun pick(profile: HouseholdProfile) {
        if (switching != null) return
        error = null
        when (val plan = ProfileSwitchPlan.make(profile, repo.reusableSession(profile))) {
            ProfileSwitchPlan.AlreadyCurrent -> households.dismissPicker()
            ProfileSwitchPlan.NeedsPin -> pinFor = profile
            is ProfileSwitchPlan.ReuseStored -> {
                switching = profile
                // A beat, so the tile is seen to be chosen before the screen changes.
                scope.launch { delay(250); onSwitch(plan.session) }
            }
            ProfileSwitchPlan.SwitchNow -> perform(profile, null)
        }
    }

    // Back leaves the PIN first, then the picker; as the app opens, it carries on as the one signed in.
    BackHandler {
        if (switching != null) return@BackHandler
        if (pinFor != null) { pinFor = null; error = null } else households.dismissPicker()
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF0E0B0A)).pointerInput(Unit) { detectTapGestures { } }.safeDrawingPadding().imePadding().focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = sized(24.dp, 60f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(sized(24.dp, 48f)),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(sized(6.dp, 10f))) {
                Text("Who's watching?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (tv) (64f * u).sp else 30.sp, textAlign = TextAlign.Center)
                repo.session?.serverName?.let { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = if (tv) (30f * u).sp else 16.sp) }
            }

            if (profiles.isEmpty()) {
                if (loading) CircularProgressIndicator()
                else loadError?.let { Text(it, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp)) }
            } else {
                val firstTile = remember { FocusRequester() }
                // On a TV the remote starts on the one watching now, else the first.
                LaunchedEffect(tv, pinFor == null) { if (tv && pinFor == null) repeat(20) { if (runCatching { firstTile.requestFocus() }.isSuccess) return@LaunchedEffect; delay(60) } }
                val focusTarget = profiles.firstOrNull { it.isCurrent } ?: profiles.first()
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = sized(16.dp, 80f), vertical = sized(8.dp, 20f)),
                    horizontalArrangement = Arrangement.spacedBy(sized(12.dp, 40f)),
                ) {
                    profiles.forEach { profile ->
                        ProfileTile(
                            profile, switching = switching?.id == profile.id, dimmed = switching != null && switching?.id != profile.id,
                            enabled = switching == null && pinFor == null,
                            modifier = if (profile.id == focusTarget.id) Modifier.focusRequester(firstTile) else Modifier,
                        ) { pick(profile) }
                    }
                }
            }

            AnimatedContent(pinFor, label = "pin") { person ->
                if (person != null) PinEntry(person, error, attempt, checking = switching != null, onSubmit = { perform(person, it) }, onCancel = { pinFor = null; error = null })
                else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp)) }
                    val ring = if (tv) Modifier.tvFocus((24f * u).dp, lift = 1.04f) else Modifier
                    when (reason) {
                        ProfilePickerReason.Launch -> repo.session?.userName?.let { name ->
                            OutlinedButton(onClick = households::dismissPicker, enabled = switching == null, modifier = ring) { Text("Continue as $name") }
                        }
                        ProfilePickerReason.Switcher -> OutlinedButton(onClick = households::dismissPicker, enabled = switching == null, modifier = ring) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

/** A profile's tile: its picture (or initial on its colour) in a circle, its name, and a lock when it asks for a PIN. */
@Composable
private fun ProfileTile(profile: HouseholdProfile, switching: Boolean, dimmed: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tv = LocalIsTv.current
    val u = Lockup.tvUnit
    val size = if (tv) (200f * u).dp else 96.dp
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.width(size + if (tv) (24f * u).dp else 12.dp).alpha(if (dimmed) 0.4f else 1f)
            .then(if (tv) Modifier.tvFocus(size / 2, lift = 1.08f, ring = false) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (tv) (16f * u).dp else 8.dp),
    ) {
        Box(
            Modifier.size(size).clip(CircleShape).background(parseColor(profile.colorHex))
                .border(if (focused || profile.isCurrent) (if (tv) (5f * u).dp else 3.dp) else 0.dp, if (focused) Color.White else Color.White.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(profile.initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (tv) (84f * u).sp else 40.sp)
            if (profile.imageUrl != null) AsyncImage(model = profile.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (switching) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                profile.name, color = if (focused) Color.White else Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = if (tv) (28f * u).sp else 15.sp, fontWeight = if (profile.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (profile.requiresPin) Icon(Icons.Default.Lock, "PIN required", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(if (tv) (24f * u).dp else 14.dp))
        }
    }
}

/** A locked profile's PIN, under the tiles: digits, checked by the server (or plex.tv); a wrong one clears it to type again. */
@Composable
private fun PinEntry(profile: HouseholdProfile, error: String?, attempt: Int, checking: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    val tv = LocalIsTv.current
    val u = Lockup.tvUnit
    var pin by remember(profile.id) { mutableStateOf("") }
    LaunchedEffect(attempt) { pin = "" }
    val field = remember { FocusRequester() }
    // To the field once it's on screen.
    LaunchedEffect(profile.id, attempt) { delay(300); runCatching { field.requestFocus() } }
    fun submit() { if (pin.isNotEmpty() && !checking) onSubmit(pin) }
    val ring = if (tv) Modifier.tvFocus((24f * u).dp, lift = 1.04f) else Modifier
    Column(
        Modifier.widthIn(max = if (tv) (720f * u).dp else 360.dp).fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Enter ${profile.name}'s PIN", color = Color.White.copy(alpha = 0.7f), fontSize = if (tv) (30f * u).sp else 17.sp)
        OutlinedTextField(
            value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(8) }, label = { Text("PIN") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(field), visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { submit() }),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = ::submit, enabled = !checking && pin.isNotEmpty(), modifier = ring) {
                if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Continue")
            }
            OutlinedButton(onClick = onCancel, enabled = !checking, modifier = ring) { Text("Cancel") }
        }
    }
}

/** Who's watching, as a small round picture: pressing it asks who's watching (Home's corner on a phone). */
@Composable
fun ProfileAvatarButton(profile: HouseholdProfile, modifier: Modifier = Modifier, size: Dp = 36.dp, onClick: (() -> Unit)?) {
    Box(
        modifier.size(size).clip(CircleShape).background(parseColor(profile.colorHex)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(profile.initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp)
        if (profile.imageUrl != null) AsyncImage(model = profile.imageUrl, contentDescription = "Switch profile: ${profile.name}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** `#RRGGBB` (or `#AARRGGBB`), Bonfire's blue when it isn't one. */
internal fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")) }.getOrDefault(Color(0xFF00A4DC))
