package com.astermediaplayer.astermediaplayer.ui.profiles

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astermediaplayer.astermediaplayer.data.profiles.Bonfire
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.tvFocus
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A PIN typed the way a streaming service asks for one (the Apple apps' `PinCodeEntry.swift`): a box per digit filling with dots,
 * growing from four boxes to eight as more are typed (a Bonfire PIN is four to eight digits, and which isn't known until it's
 * checked). On a TV the digits come from a keypad under the boxes, the remote moving between them; on a phone or tablet from the
 * number keyboard. A wrong PIN shakes the boxes.
 *
 * [PinCodeEntry] is the full pad, for opening a profile. [PinCodeField] is the boxes alone, for a form that sets a PIN (on a TV,
 * there, the system's number keyboard, as a keypad under each would crowd the form).
 */
object PinCode {
    val MIN_LENGTH = Bonfire.PIN_LENGTHS.first
    val MAX_LENGTH = Bonfire.PIN_LENGTHS.last

    /** Only ASCII digits, and no more than a PIN can hold. */
    fun digits(text: String): String = text.filter { it in '0'..'9' }.take(MAX_LENGTH)

    /** Enough digits to be a PIN. */
    fun isComplete(pin: String): Boolean = pin.length in Bonfire.PIN_LENGTHS

    /** How many boxes show: one past what's typed, four at least and eight at most. */
    fun boxCount(pin: String): Int = (pin.length + 1).coerceIn(MIN_LENGTH, MAX_LENGTH)
}

/** The boxes, the next one lit while keys are being taken; [attempt] going up shakes them. */
@Composable
fun PinDigitBoxes(pin: String, attempt: Int, active: Boolean, compact: Boolean = false) {
    val tv = LocalIsTv.current
    val u = Lockup.tvUnit
    val width = when { compact -> 36.dp; tv -> (64f * u).dp; else -> 44.dp }
    val height = when { compact -> 46.dp; tv -> (80f * u).dp; else -> 56.dp }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(attempt) { if (attempt > 0) { shake.snapTo(0f); shake.animateTo(1f, tween(450)) } }
    val shift = (12f * sin(shake.value * PI * 6)).toFloat()
    Row(Modifier.offset { IntOffset((shift * density).roundToInt(), 0) }, horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else if (tv) (16f * u).dp else 12.dp)) {
        repeat(PinCode.boxCount(pin)) { i ->
            val filled = i < pin.length
            val next = active && i == pin.length
            Box(
                Modifier.size(width, height).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = if (next) 0.14f else 0.08f))
                    .border(if (next) 3.dp else 1.5.dp, Color.White.copy(alpha = if (next) 0.9f else if (filled) 0.5f else 0.25f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { if (filled) Box(Modifier.size(width * 0.28f).clip(CircleShape).background(Color.White)) }
        }
    }
}

/** The boxes and, on a TV, the keypad. Return, or the keypad's tick, with enough digits typed calls [onSubmit]. */
@Composable
fun PinCodeEntry(pin: String, onPin: (String) -> Unit, attempt: Int, enabled: Boolean = true, onSubmit: () -> Unit) {
    val tv = LocalIsTv.current
    val u = Lockup.tvUnit
    fun submitIfReady() { if (enabled && PinCode.isComplete(pin)) onSubmit() }
    if (!tv) {
        HiddenPinField(pin, onPin, attempt, compact = false, enabled = enabled, onDone = ::submitIfReady)
        return
    }
    val middle = remember { FocusRequester() }
    var onKeys by remember { mutableStateOf(false) }
    // Set as it appears, focus is ignored: a moment later; and back to the keys after a wrong one.
    LaunchedEffect(attempt) { delay(300); runCatching { middle.requestFocus() } }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((28f * u).dp)) {
        PinDigitBoxes(pin, attempt, active = onKeys)
        Column(verticalArrangement = Arrangement.spacedBy((12f * u).dp)) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy((12f * u).dp)) {
                    row.forEach { d -> PadKey(d, null, enabled, if (d == "5") Modifier.focusRequester(middle) else Modifier, { onKeys = it }) { if (pin.length < PinCode.MAX_LENGTH) onPin(pin + d) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy((12f * u).dp)) {
                PadKey("Delete", Icons.AutoMirrored.Filled.Backspace, enabled && pin.isNotEmpty(), Modifier, { onKeys = it }) { onPin(pin.dropLast(1)) }
                PadKey("0", null, enabled, Modifier, { onKeys = it }) { if (pin.length < PinCode.MAX_LENGTH) onPin(pin + "0") }
                PadKey("Done", Icons.Default.Check, enabled && PinCode.isComplete(pin), Modifier, { onKeys = it }) { submitIfReady() }
            }
        }
    }
}

/** One key of the TV's keypad: a digit, or an icon (Delete, Done). Dimmed and unpressable when [enabled] is off, but still a stop. */
@Composable
private fun PadKey(label: String, icon: ImageVector?, enabled: Boolean, modifier: Modifier, onFocus: (Boolean) -> Unit, onPress: () -> Unit) {
    val u = Lockup.tvUnit
    var focused by remember { mutableStateOf(false) }
    val tint = if (focused) Color.Black else Color.White
    Box(
        modifier.size((128f * u).dp, (62f * u).dp).tvFocus((14f * u).dp, lift = 1.06f, ring = false)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .clip(RoundedCornerShape((14f * u).dp)).background(if (focused) Color.White else Color.White.copy(alpha = 0.1f))
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (enabled) onPress() },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, label, tint = tint, modifier = Modifier.size((30f * u).dp))
        else Text(label, color = tint, fontSize = (34f * u).sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * The boxes alone, for a form setting a PIN. On a phone or tablet the digits are typed into them; on a TV the system's number
 * keyboard opens from an ordinary field.
 */
@Composable
fun PinCodeField(title: String, pin: String, onPin: (String) -> Unit, attempt: Int) {
    if (LocalIsTv.current) {
        val u = Lockup.tvUnit
        OutlinedTextField(
            value = pin, onValueChange = { onPin(PinCode.digits(it)) }, label = { Text(title) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.tvFocus((8f * u).dp, lift = 1.02f),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        HiddenPinField(pin, onPin, attempt, compact = true, enabled = true, onDone = {})
    }
}

/** The boxes over a text field nobody sees, which the number keyboard types into; a tap on the boxes brings the keyboard. */
@Composable
private fun HiddenPinField(pin: String, onPin: (String) -> Unit, attempt: Int, compact: Boolean, enabled: Boolean, onDone: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    if (!compact) LaunchedEffect(attempt) { delay(300); runCatching { field.requestFocus() } }
    BasicTextField(
        value = pin, onValueChange = { onPin(PinCode.digits(it)) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        cursorBrush = SolidColor(Color.Transparent),
        modifier = Modifier.focusRequester(field).onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box {
                // The field itself takes no room and shows nothing; the boxes show what's typed.
                Box(Modifier.size(1.dp).alpha(0f)) { inner() }
                PinDigitBoxes(pin, attempt, active = focused, compact = compact)
            }
        },
    )
}

/** The profile's picture (or its initial on its colour) in a circle. */
@Composable
fun ProfileAvatar(initial: String, colorHex: String, imageUrl: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(parseColor(colorHex)), contentAlignment = Alignment.Center) {
        Text(initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
        if (imageUrl != null) coil3.compose.AsyncImage(
            model = imageUrl, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
    }
}
