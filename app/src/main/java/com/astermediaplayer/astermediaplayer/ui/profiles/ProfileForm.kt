package com.astermediaplayer.astermediaplayer.ui.profiles

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog
import com.astermediaplayer.astermediaplayer.data.profiles.HouseholdProfile
import com.astermediaplayer.astermediaplayer.data.profiles.HouseholdProfiles
import com.astermediaplayer.astermediaplayer.data.profiles.ProfileCreationOptions
import com.astermediaplayer.astermediaplayer.data.profiles.ProfileDraft
import com.astermediaplayer.astermediaplayer.ui.components.userMessage
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Adding someone to the household, or changing them (the Apple apps' `ProfileCreateView`): a name, a picture (or a letter on a
 * colour), a PIN if they want one, how far up the ratings they may watch, and which libraries they see. The main account's own PIN
 * is asked when it has one. A profile being changed can be removed from here too. Done on the server; the picker then shows the
 * household as it is ([onDone]).
 */
@Composable
fun ProfileForm(households: HouseholdProfiles, editing: HouseholdProfile?, onDone: () -> Unit) {
    val tv = LocalIsTv.current
    val u = Lockup.tvUnit
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(ProfileDraft()) }
    var confirmPin by remember { mutableStateOf("") }
    var options by remember { mutableStateOf<ProfileCreationOptions?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Counts the tries that failed, so each message shows afresh and the PIN boxes shake.
    var attempt by remember { mutableIntStateOf(0) }
    val isEditing = editing != null
    // The main account changing itself: its colour, picture and PIN, not its name or what it may watch.
    val editsMaster = editing?.isMaster == true
    val nameField = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        coroutineScope {
            // The profile as it is, and the choices, side by side.
            val filled = editing?.let { async { households.draft(it) } }
            val choices = async { households.creationOptions() }
            filled?.let { draft = it.await() }
            options = choices.await()
        }
        delay(300)
        if (!editsMaster) runCatching { nameField.requestFocus() }
    }

    fun fail(message: String) { attempt++; error = message }

    fun save() {
        if (saving) return
        draft.validationMessage(confirmPin)?.let { fail(it); return }
        if (options?.asksMasterPin == true && draft.masterPin.isEmpty()) { fail("Enter the main account's PIN."); return }
        saving = true; error = null
        scope.launch {
            try {
                if (isEditing) households.update(draft) else households.create(draft)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.note("profiles", "${if (isEditing) "Changing" else "Making"} a profile failed: ${e.userMessage()}")
                saving = false; fail(e.userMessage())
            }
        }
    }

    fun delete() {
        val profile = editing ?: return
        if (saving) return
        if (options?.asksMasterPin == true && draft.masterPin.isEmpty()) { fail("Enter the main account's PIN to remove a profile."); return }
        saving = true; error = null
        scope.launch {
            try {
                households.delete(profile, draft.masterPin)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.note("profiles", "Removing a profile failed: ${e.userMessage()}")
                saving = false; fail(e.userMessage())
            }
        }
    }

    BackHandler { if (!saving) onDone() }

    // The picture as the preview shows it: the one picked, or (changing) the one it has.
    val preview = draft.avatarId?.let { id -> options?.avatars?.firstOrNull { it.id == id }?.url } ?: editing?.imageUrl
    val ring = if (tv) Modifier.tvFocus((24f * u).dp, lift = 1.04f) else Modifier
    val width = if (tv) (900f * u).dp else 520.dp

    Box(
        Modifier.fillMaxSize().background(Color(0xFF0E0B0A)).pointerInput(Unit) { detectTapGestures { } }.safeDrawingPadding().imePadding().focusGroup(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = width).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = if (tv) (60f * u).dp else 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (tv) (36f * u).dp else 24.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (isEditing) "Edit Profile" else "Add a Profile", color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (tv) (56f * u).sp else 28.sp)
                ProfileAvatar(draft.initial, draft.colorHex, preview, if (tv) (180f * u).dp else 96.dp)
                val remaining = options?.remaining
                if (!isEditing && remaining != null) Text(if (remaining == 1) "Room for one more profile." else "Room for $remaining more profiles.", color = Color.White.copy(alpha = 0.6f))
            }

            Section("Name") {
                OutlinedTextField(
                    value = draft.name, onValueChange = { draft = draft.copy(name = it) }, label = { Text("Name") }, singleLine = true, enabled = !editsMaster,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().focusRequester(nameField).then(if (tv) Modifier.tvFocus((8f * u).dp, lift = 1.02f) else Modifier),
                )
                if (editsMaster) Note("The main account keeps its own name.")
            }

            options?.let { o ->
                Section("Picture") {
                    if (o.avatars.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // The letter on the colour as it is without a picture, or (changing) the picture it has.
                        ProfileAvatar(draft.initial, draft.colorHex, editing?.imageUrl, choiceSize(), Choice(draft.avatarId == null, CircleShape) { draft = draft.copy(avatarId = null) })
                        o.avatars.forEach { avatar ->
                            ProfileAvatar("", draft.colorHex, avatar.url, choiceSize(), Choice(draft.avatarId == avatar.id, CircleShape) { draft = draft.copy(avatarId = avatar.id) })
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProfileDraft.COLORS.forEach { hex ->
                            Box(Choice(draft.colorHex == hex, CircleShape) { draft = draft.copy(colorHex = hex) }.size(if (tv) (56f * u).dp else 36.dp).background(parseColor(hex), CircleShape))
                        }
                    }
                }
            }

            Section("PIN") {
                if (editing?.hasPin == true) {
                    SwitchRow("Remove the PIN", draft.removePin) { draft = draft.copy(removePin = it) }
                    if (!draft.removePin) PinCodeField("New PIN (leave empty to keep it)", draft.pin, { draft = draft.copy(pin = it) }, attempt)
                } else PinCodeField("PIN (optional)", draft.pin, { draft = draft.copy(pin = it) }, attempt)
                if (draft.pin.isNotEmpty() && !draft.removePin) PinCodeField("Confirm PIN", confirmPin, { confirmPin = it }, attempt)
                if (!draft.removePin && (draft.pin.isNotEmpty() || editing?.hasPin == true))
                    SwitchRow("Skip the PIN on the home network", draft.bypassPinOnLocalNetwork) { draft = draft.copy(bypassPinOnLocalNetwork = it) }
                Note(
                    when {
                        draft.removePin -> "Anyone on the household's devices will be able to open this profile."
                        editing?.hasPin == true -> "This profile has a PIN. Type a new one to change it, 4 to 8 digits."
                        draft.pin.isEmpty() -> "4 to 8 digits. With a PIN, switching to this profile asks for it."
                        else -> "Switching to this profile asks for the PIN; without it, anyone on the household's devices can open it."
                    },
                )
            }

            val o = options
            if (!editsMaster && o != null && o.ratings.isNotEmpty()) Section("Viewing Limit") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip("No Limit", draft.ratingScore == null) { draft = draft.copy(ratingScore = null) }
                    o.ratings.forEach { r -> Chip(r.label, draft.ratingScore == r.score) { draft = draft.copy(ratingScore = r.score) } }
                }
                Note("Up to this rating${o.ratingCountry?.let { " ($it)" }.orEmpty()}, and never past what the main account may watch.")
            }

            if (!editsMaster && o != null && o.libraries.isNotEmpty()) Section("Libraries") {
                SwitchRow("All Libraries", draft.libraryIds == null) { all -> draft = draft.copy(libraryIds = if (all) null else o.libraries.map { it.id }.toSet()) }
                draft.libraryIds?.let { ids ->
                    o.libraries.forEach { library ->
                        SwitchRow(library.name, library.id in ids) { on -> draft = draft.copy(libraryIds = if (on) ids + library.id else ids - library.id) }
                    }
                }
                Note(if (draft.libraryIds == null) "Everything the main account can see, including libraries added later." else "Only the libraries switched on.")
            }

            if (options?.asksMasterPin == true) Section("Your PIN") {
                PinCodeField("Your own PIN", draft.masterPin, { draft = draft.copy(masterPin = it) }, attempt)
                Note("The main account's PIN, to confirm it's you changing the household.")
            }

            if (options == null) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = ::save, enabled = !saving && draft.trimmedName.isNotEmpty() && options != null, modifier = ring) {
                    if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(if (isEditing) "Save" else "Add Profile")
                }
                OutlinedButton(onClick = onDone, enabled = !saving, modifier = ring) { Text("Cancel") }
            }
            if (editing != null && !editing.isMaster) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { confirmingDelete = true }, enabled = !saving, modifier = ring) { Text("Remove ${editing.name}", color = MaterialTheme.colorScheme.error) }
                Note("Their watch history and settings go with them.")
            }
        }
    }

    if (confirmingDelete) AlertDialog(
        onDismissRequest = { confirmingDelete = false },
        title = { Text("Remove ${editing?.name ?: "this profile"}?") },
        text = { Text("Their account on the server goes, with what they've watched and their settings. This can't be undone.") },
        confirmButton = { TextButton({ confirmingDelete = false; delete() }) { Text("Remove Profile", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmingDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun choiceSize(): Dp = if (LocalIsTv.current) (120f * Lockup.tvUnit).dp else 64.dp

/** A form section: its heading, then what it holds. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val tv = LocalIsTv.current
    Column(Modifier.fillMaxWidth().focusGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.SemiBold, fontSize = if (tv) (28f * Lockup.tvUnit).sp else 16.sp)
        content()
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.55f), fontSize = if (LocalIsTv.current) (22f * Lockup.tvUnit).sp else 13.sp)
}

/** A picked picture or colour: ringed when chosen, lifted with the remote's focus. */
@Composable
private fun Choice(selected: Boolean, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit): Modifier {
    val tv = LocalIsTv.current
    val u = Lockup.tvUnit
    return Modifier.then(if (tv) Modifier.tvFocus((60f * u).dp, lift = 1.12f, ring = false) else Modifier)
        .border(if (selected) 4.dp else 1.5.dp, Color.White.copy(alpha = if (selected) 0.95f else 0.15f), shape)
        .padding(4.dp).clip(shape)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

/** A rating to pick: a capsule, ticked when chosen; white with black text when focused on a TV. */
@Composable
private fun Chip(title: String, selected: Boolean, onClick: () -> Unit) {
    val tv = LocalIsTv.current
    val u = Lockup.tvUnit
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.then(if (tv) Modifier.tvFocus((30f * u).dp, lift = 1.06f, ring = false) else Modifier).onFocusChanged { focused = it.isFocused }
            .clip(shape).background(if (focused) Color.White else if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = if (tv) (24f * u).dp else 14.dp, vertical = if (tv) (12f * u).dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val tint = if (focused) Color.Black else if (selected) MaterialTheme.colorScheme.onPrimary else Color.White
        if (selected) Icon(Icons.Default.Check, null, tint = tint, modifier = Modifier.size(if (tv) (24f * u).dp else 16.dp))
        Text(title, color = tint, maxLines = 1, fontSize = if (tv) (24f * u).sp else 14.sp)
    }
}

/** A switch with its title, the whole row pressable (the remote's OK flips it). */
@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tv = LocalIsTv.current
    val u = Lockup.tvUnit
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().then(if (tv) Modifier.tvFocus((14f * u).dp, lift = 1.02f, ring = false) else Modifier).onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp)).background(if (focused) Color.White else Color.White.copy(alpha = 0.07f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(!checked) }
            .padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = if (focused) Color.Black else Color.White, fontSize = if (tv) (26f * u).sp else 16.sp)
        Switch(checked = checked, onCheckedChange = null)
    }
}
