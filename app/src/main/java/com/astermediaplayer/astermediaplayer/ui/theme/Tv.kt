package com.astermediaplayer.astermediaplayer.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp

/**
 * Whether the TV's "‹ Home" chip is showing. A page with a banner sets it from the banner's focus: up over the banner, gone once
 * focus goes down into the shelves. Null off a TV.
 */
val LocalTvChip = compositionLocalOf<MutableState<Boolean>?> { null }

/** True on an Android TV device, where a remote's D-pad is the only input. Provided once by MainActivity. */
val LocalIsTv = compositionLocalOf { false }

/** How far past the edge a scroll to a focused item goes, in px. */
private const val EDGE_MARGIN = 48f

/**
 * Scrolls to a focused item only as far as it takes to show it. The default on a TV pivots it a third of the way down the page,
 * which pushes the banner's own button, focused as the page opens, half the banner off the top.
 */
@OptIn(ExperimentalFoundationApi::class)
val MinimalBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0f && trailing <= containerSize -> 0f
            // A whole screen's worth (the banner): flush with the top, no margin.
            size >= containerSize * 0.95f -> offset
            offset < 0f && trailing > containerSize -> 0f
            // Scrolling to it leaves a little room, so its focus ring isn't against the screen's edge.
            kotlin.math.abs(offset) < kotlin.math.abs(trailing - containerSize) -> offset - EDGE_MARGIN
            else -> trailing - containerSize + EDGE_MARGIN
        }
    }
}

/**
 * A TV that also has a touchscreen, as the Echo Show 15 is: the TV's layout, which the remote steers, with what touch needs on
 * top (a way back and to the menu, the player's controls on a tap).
 */
val LocalTouchTv = androidx.compose.runtime.staticCompositionLocalOf { false }

fun hasTouchscreen(context: Context): Boolean = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)

/** An Amazon device (Fire TV, Echo Show). */
fun isAmazonDevice(manufacturer: String = android.os.Build.MANUFACTURER): Boolean = manufacturer.equals("Amazon", ignoreCase = true)

/**
 * Whether to lay touch controls over the TV layout. Not on Amazon's devices: Fire OS doesn't pass touch to Appstore apps on the
 * Echo Show ("Touch functionality is currently not supported for Appstore Apps on Echo Show devices", Amazon Developer
 * Community, 22 July 2026). It shows its own Onscreen Remote instead, which sends the remote's keys, and buttons nobody can
 * tap would fail review (Appstore test 2.12).
 */
fun usesTouchLayer(tv: Boolean, touchscreen: Boolean, amazon: Boolean): Boolean = tv && touchscreen && !amazon

fun usesTouchLayer(context: Context): Boolean = usesTouchLayer(isTelevision(context), hasTouchscreen(context), isAmazonDevice())

fun isTelevision(context: Context): Boolean =
    (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

/**
 * What a D-pad user sees as "here": a white ring just outside the element, so it never crops a label, and a small lift. Goes
 * before the `clickable`, whose focus it reads; it draws nothing until something is focused, so touch screens are untouched.
 *
 * A modifier node that only draws (the lift is a canvas transform, not a layer), so a screen full of cards costs nothing
 * until focus moves, which a TV's slow CPU notices.
 */
fun Modifier.tvFocus(cornerRadius: Dp, lift: Float = 1.05f, ring: Boolean = true, pictureAspect: Float = 0f): Modifier =
    this then TvFocusElement(cornerRadius, lift, ring, pictureAspect)

private data class TvFocusElement(val cornerRadius: Dp, val lift: Float, val ring: Boolean, val pictureAspect: Float) : ModifierNodeElement<TvFocusNode>() {
    override fun create() = TvFocusNode(cornerRadius, lift, ring, pictureAspect)
    override fun update(node: TvFocusNode) { node.cornerRadius = cornerRadius; node.lift = lift; node.ring = ring; node.pictureAspect = pictureAspect }
    override fun InspectorInfo.inspectableProperties() { name = "tvFocus" }
}

private class TvFocusNode(var cornerRadius: Dp, var lift: Float, var ring: Boolean, var pictureAspect: Float) : Modifier.Node(), FocusEventModifierNode, DrawModifierNode {
    private val scale = Animatable(1f)
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        val now = focusState.isFocused
        if (now == focused) return
        focused = now
        invalidateDraw()
        coroutineScope.launch { scale.animateTo(if (now) lift else 1f, tween(120)) }
    }

    override fun ContentDrawScope.draw() {
        val s = scale.value
        if (!focused && s == 1f) { drawContent(); return }
        val content = this
        withTransform({ scale(s, s, center) }) {
            if (!ring && focused) {
                // The Apple TV's card focus: it lifts and throws a soft shadow, with no outline. A few widening, fading layers.
                // It follows the picture (width over [pictureAspect] tall), not the label under it.
                val pictureHeight = if (pictureAspect > 0f) size.width / pictureAspect else size.height
                for (i in 1..6) {
                    val grow = i * 5.dp.toPx()
                    drawRoundRect(
                        Color.Black.copy(alpha = 0.10f), Offset(-grow, -grow + 14.dp.toPx()), Size(size.width + 2 * grow, pictureHeight + 2 * grow),
                        CornerRadius(cornerRadius.toPx() + grow),
                    )
                }
            }
            content.drawContent()
            if (focused && ring) {
                val gap = 4.dp.toPx()
                drawRoundRect(
                    Color.White, Offset(-gap, -gap), Size(size.width + 2 * gap, size.height + 2 * gap),
                    CornerRadius(cornerRadius.toPx() + gap), Stroke(3.dp.toPx()),
                )
            }
        }
    }
}
