package com.astermediaplayer.astermediaplayer.ui.disc

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.astermediaplayer.astermediaplayer.data.disc.DiscBackground
import com.astermediaplayer.astermediaplayer.data.disc.DiscLayer
import com.astermediaplayer.astermediaplayer.data.disc.DiscMenuEntry
import com.astermediaplayer.astermediaplayer.data.disc.DiscMenuRules
import com.astermediaplayer.astermediaplayer.data.disc.DiscPosition
import com.astermediaplayer.astermediaplayer.data.disc.DiscRect
import com.astermediaplayer.astermediaplayer.data.disc.DiscTheme
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** What a screen is drawn from, so a leaving screen keeps its own picture while the arriving one eases in. */
private data class Shown(val generation: Int, val screen: DiscResolvedScreen)

/** The menu full screen, driven by a remote or a touch; playing something is [onPlay]'s, closing is [onClose]'s. */
@Composable
fun DiscMenuScreen(controller: DiscMenuController, onPlay: (DiscPlay) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    var canvasOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    LaunchedEffect(controller) { controller.plays.collect { onPlay(it) } }
    LaunchedEffect(controller) { controller.closes.collect { onClose() } }
    LaunchedEffect(Unit) { repeat(15) { if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect else delay(60) } }
    BackHandler { controller.back() }

    // The menu is quiet while the player, or another app, is in front.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) controller.shown() else if (event == Lifecycle.Event.ON_STOP) controller.hidden()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.hidden() }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        // The largest 16:9 rectangle that fits, centred.
        var w = maxWidth
        var h = w * 9 / 16
        if (h > maxHeight) { h = maxHeight; w = h * 16 / 9 }
        Box(
            Modifier.align(Alignment.Center).size(w, h).clipToBounds()
                .onGloballyPositioned { canvasOrigin = it.positionInRoot() }
                .focusRequester(focus).focusable()
                .onPreviewKeyEvent { e -> handleKey(controller, e) }
                // Touch: the entry under the finger is the one chosen.
                .pointerInput(controller) { detectTapGestures { p -> controller.tap(p.x + canvasOrigin.x.toDouble(), p.y + canvasOrigin.y.toDouble()) } },
        ) {
            val screen = controller.screen
            val style = DiscStyle(w, h, screen.theme, LocalDensityScale.current)
            DiscBackgroundView(screen.background, controller, style, crossfade = screen.transitionStyle != "none" && screen.transitionSeconds > 0)
            DiscLayersView(screen.layout.layers.orEmpty(), style, controller.pictures)
            val animate = controller.animateChange && screen.transitionStyle != "none" && screen.transitionSeconds > 0
            val duration = (screen.transitionSeconds * 1000).roundToInt()
            val forward = controller.direction >= 0
            AnimatedContent(
                targetState = Shown(controller.generation, screen),
                transitionSpec = {
                    if (!animate) EnterTransition.None togetherWith ExitTransition.None
                    else transitionFor(screen.transitionStyle, forward, duration)
                },
                label = "discScreen",
                modifier = Modifier.fillMaxSize(),
            ) { shown ->
                val wipe = animate && shown.screen.transitionStyle == "wipe"
                Box(if (wipe) Modifier.fillMaxSize().then(wipeIn(forward, duration)) else Modifier.fillMaxSize()) {
                    DiscScreenView(shown.screen, controller, style, shown.generation)
                }
            }
        }
        if (!LocalIsTv.current) {
            // Below the status bar, which the page draws under.
            IconButton(onClick = { controller.back() }, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
                Icon(if (controller.canGoBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close, if (controller.canGoBack) "Back" else "Close", tint = Color.White)
            }
        }
    }

    controller.alertMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { controller.alertMessage = null },
            title = { Text("Disc Menu") }, text = { Text(message) },
            confirmButton = { TextButton({ controller.alertMessage = null }) { Text("OK") } },
        )
    }
}

private fun handleKey(controller: DiscMenuController, e: androidx.compose.ui.input.key.KeyEvent): Boolean {
    val handled = when (e.key) {
        Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.MediaPlay, Key.Spacebar -> true
        else -> false
    }
    if (!handled) return false
    if (e.type == KeyEventType.KeyDown) when (e.key) {
        Key.DirectionLeft -> controller.move(-1, 0)
        Key.DirectionRight -> controller.move(1, 0)
        Key.DirectionUp -> controller.move(0, -1)
        Key.DirectionDown -> controller.move(0, 1)
        Key.MediaPlayPause, Key.MediaPlay -> controller.playPause()
        else -> controller.select()
    }
    return true
}

/** A wipe (§5.4): the arriving screen revealed from the leading side going forward, the trailing side going back. */
@Composable
private fun androidx.compose.animation.AnimatedVisibilityScope.wipeIn(forward: Boolean, ms: Int): Modifier {
    val progress by transition.animateFloat({ tween(ms, easing = LinearOutSlowInEasing) }, label = "wipe") {
        if (it == androidx.compose.animation.EnterExitState.PreEnter) 0f else 1f
    }
    return Modifier.drawWithContent {
        val shown = size.width * progress
        if (forward) clipRect(right = shown) { this@drawWithContent.drawContent() }
        else clipRect(left = size.width - shown) { this@drawWithContent.drawContent() }
    }
}

/** Screen transitions (§5.4): the leaving screen eases in while the arriving one eases out, together. */
private fun transitionFor(style: String, forward: Boolean, ms: Int): androidx.compose.animation.ContentTransform {
    val s = if (forward) 1 else -1
    val inSpec = tween<Float>(ms, easing = LinearOutSlowInEasing)
    val outSpec = tween<Float>(ms, easing = FastOutLinearInEasing)
    fun <T> inT() = tween<T>(ms, easing = LinearOutSlowInEasing)
    fun <T> outT() = tween<T>(ms, easing = FastOutLinearInEasing)
    return when (style) {
        "fade" -> fadeIn(inSpec) togetherWith fadeOut(outSpec)
        "slide" -> (fadeIn(inSpec) + slideInHorizontally(inT()) { (it * 0.08f * s).toInt() }) togetherWith (fadeOut(outSpec) + slideOutHorizontally(outT()) { (-it * 0.08f * s).toInt() })
        "rise" -> (fadeIn(inSpec) + slideInVertically(inT()) { (it * 0.06f * s).toInt() }) togetherWith (fadeOut(outSpec) + slideOutVertically(outT()) { (-it * 0.06f * s).toInt() })
        "zoom" -> (fadeIn(inSpec) + scaleIn(inSpec, if (forward) 0.92f else 1.08f)) togetherWith (fadeOut(outSpec) + scaleOut(outSpec, if (forward) 1.08f else 0.92f))
        // The arriving screen is revealed by [wipeIn], drawn on top of the leaving one as it fades.
        "wipe" -> EnterTransition.None togetherWith fadeOut(outSpec)
        else -> EnterTransition.None togetherWith ExitTransition.None
    }
}

// --- Style: a menu's Theme as fonts, colours and sizes for the canvas (§5.2) ---

private val LocalDensityScale = androidx.compose.runtime.compositionLocalOf { 1f }

private class DiscStyle(val width: Dp, val height: Dp, val theme: DiscTheme, @Suppress("unused") val scale: Float) {
    val accent = parseColor(theme.accent) ?: Color(0xFF3DDC84)
    val textColor = parseColor(theme.textColor) ?: Color.White
    /** The base unit: the overlay's font size in the web reference. */
    val baseEm = height * 0.024f
    val buttonFont: Dp
    val titleFont: Dp

    init {
        val size = theme.fontSize
        if (size != null && size.isFinite() && size > 0) {
            val clamped = size.coerceIn(1.0, 10.0).toFloat()
            buttonFont = height * clamped / 100
            titleFont = height * clamped * 1.6f / 100
        } else {
            buttonFont = baseEm * 1.25f
            titleFont = baseEm * 2
        }
    }

    fun px(percent: Double): Dp = width * (percent / 100).toFloat()
    fun py(percent: Double): Dp = height * (percent / 100).toFloat()
    val vh: Dp get() = height / 100

    val family: FontFamily get() = when (theme.font) { "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; else -> FontFamily.Default }
    val weight: FontWeight get() = if (theme.bold == true) FontWeight.Bold else FontWeight.Normal
    val align: TextAlign get() = when (theme.align) { "center" -> TextAlign.Center; "right" -> TextAlign.End; else -> TextAlign.Start }
    val horizontal: Alignment.Horizontal get() = when (theme.align) { "center" -> Alignment.CenterHorizontally; "right" -> Alignment.End; else -> Alignment.Start }
    val frameAlignment: Alignment get() = when (theme.align) { "center" -> Alignment.Center; "right" -> Alignment.CenterEnd; else -> Alignment.CenterStart }
    fun case(text: String) = if (theme.uppercase == true) text.uppercase() else text
}

private fun parseColor(hex: String?): Color? =
    if (DiscMenuRules.isHexColor(hex)) Color(0xFF000000L or hex!!.substring(1).toLong(16)) else null

private val TextShadow = Shadow(Color.Black.copy(alpha = 0.8f), androidx.compose.ui.geometry.Offset(0f, 3f), 6f)

@Composable
private fun DiscText(text: String, size: Dp, style: DiscStyle, modifier: Modifier = Modifier, color: Color? = null) {
    val spacing = style.theme.letterSpacing?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
    Text(
        style.case(text), modifier,
        style = TextStyle(
            fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { size.toSp() }, fontFamily = style.family, fontWeight = style.weight,
            color = color ?: style.textColor, textAlign = style.align, letterSpacing = spacing.em, shadow = TextShadow,
        ),
    )
}

// --- Absolute placement: each child at its position, a percentage of the canvas, shifted by its anchor ---

private data class Placement(val position: DiscPosition?) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@Placement
}

private fun Modifier.place(position: DiscPosition?) = this.then(Placement(position))

@Composable
private fun DiscCanvas(style: DiscStyle, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.size(style.width, style.height)) { measurables, constraints ->
        val cw = style.width.roundToPx()
        val ch = style.height.roundToPx()
        val placed = measurables.map { m ->
            val pos = (m.parentData as? Placement)?.position
            val fixedW = pos?.w?.let { (cw * it / 100).roundToInt() }
            val fixedH = pos?.h?.let { (ch * it / 100).roundToInt() }
            val p = m.measure(Constraints(fixedW ?: 0, fixedW ?: cw, fixedH ?: 0, fixedH ?: ch))
            Triple(p, pos, Unit)
        }
        layout(cw, ch) {
            for ((p, pos, _) in placed) {
                if (pos == null) { p.place(0, 0); continue }
                val (sx, sy) = DiscMenuRules.anchorShift(pos.anchor)
                p.place(IntOffset((cw * pos.x / 100 + sx * p.width).roundToInt(), (ch * pos.y / 100 + sy * p.height).roundToInt()))
            }
        }
    }
}

// --- Backgrounds ---

@OptIn(UnstableApi::class)
@Composable
private fun DiscBackgroundView(background: DiscBackground?, controller: DiscMenuController, style: DiscStyle, crossfade: Boolean) {
    val base = Color(0xFF101010)
    Box(Modifier.fillMaxSize().background(base)) {
        controller.trailer?.let { trailer ->
            val alpha by animateFloatAsState(if (trailer.ready) 1f else 0f, tween(800), label = "trailer")
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        player = trailer.player
                    }
                },
                update = { it.player = trailer.player },
                modifier = Modifier.fillMaxSize().alpha(alpha),
            )
        }
        Crossfade(background, animationSpec = tween(if (crossfade) 300 else 0), label = "discBackground") { b ->
            when (b?.source) {
                "color" -> Box(Modifier.fillMaxSize().background(parseColor(b.color) ?: base))
                "trailer" -> Box(Modifier.fillMaxSize()) {
                    val trailer = controller.trailer
                    if (trailer == null || !trailer.ready) controller.pictures.picture(b.poster)?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = DiscMenuRules.clamp(b.dim, 0.0..1.0, 0.25).toFloat())))
                }
                "jellyfin", "tmdb", "fanart", "image" -> Box(Modifier.fillMaxSize()) {
                    controller.pictures.background(b)?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = DiscMenuRules.clamp(b.dim, 0.0..1.0, 0.4).toFloat())))
                }
                else -> Unit
            }
        }
    }
}

// --- Layers: panels and pictures behind the buttons ---

@Composable
private fun DiscLayersView(layers: List<DiscLayer>, style: DiscStyle, pictures: DiscPictures) {
    if (layers.isEmpty()) return
    DiscCanvas(style) {
        for (layer in layers) {
            val position = layer.position ?: continue
            val alpha = DiscMenuRules.clamp(layer.opacity, 0.0..1.0, 1.0).toFloat()
            when (layer.type) {
                "panel" -> {
                    val radius = style.vh * DiscMenuRules.clamp(layer.radius, 0.0..100.0, 0.0).toFloat()
                    val border = style.vh * DiscMenuRules.clamp(layer.borderWidth, 0.0..100.0, 0.0).toFloat()
                    var m = Modifier.alpha(alpha).background(parseColor(layer.fill) ?: Color.Transparent, RoundedCornerShape(radius))
                    if (border > 0.dp) m = m.border(border, parseColor(layer.borderColor) ?: Color.Transparent, RoundedCornerShape(radius))
                    Box(m.place(position))
                }
                "image" -> pictures.picture(layer.image)?.let { url ->
                    AsyncImage(
                        url, null, Modifier.alpha(alpha).place(position).clipToBounds(),
                        contentScale = when (layer.fit) { "cover" -> ContentScale.Crop; "fill" -> ContentScale.FillBounds; else -> ContentScale.Fit },
                    )
                }
            }
        }
    }
}

// --- A screen: title and buttons ---

@Composable
private fun DiscScreenView(screen: DiscResolvedScreen, controller: DiscMenuController, style: DiscStyle, generation: Int) {
    val showTitle = screen.layout.hideTitle != true
    val titlePosition = screen.layout.titlePosition
    val focused = if (generation == controller.generation) controller.focusIndex else -1

    @Composable
    fun Title(modifier: Modifier = Modifier) = DiscText(screen.title, style.titleFont, style, modifier.then(Modifier.fillMaxWidth()))

    @Composable
    fun Button(entry: DiscMenuEntry, index: Int, modifier: Modifier) {
        DiscButton(
            entry, focused == index, style, screen.layout.buttonStyle, controller.pictures,
            modifier.onGloballyPositioned { c ->
                val b = c.boundsInRoot()
                controller.reportFrame(DiscRect(b.left.toDouble(), b.top.toDouble(), b.right.toDouble(), b.bottom.toDouble()), index, generation)
            },
        )
    }

    if (screen.positioned) {
        DiscCanvas(style) {
            if (showTitle) Title(Modifier.place(titlePosition ?: DiscPosition(0.0, 0.0, 100.0)))
            screen.entries.forEachIndexed { index, entry -> Button(entry, index, Modifier.place(entry.position)) }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.align(when (style.theme.align) { "center" -> Alignment.Center; "right" -> Alignment.CenterEnd; else -> Alignment.CenterStart }).padding(style.baseEm * 4),
                horizontalAlignment = style.horizontal,
            ) {
                if (showTitle && titlePosition == null) { Title(Modifier.padding(bottom = style.titleFont * 0.75f)) }
                Column(verticalArrangement = Arrangement.spacedBy(style.baseEm * 0.5f), horizontalAlignment = style.horizontal) {
                    screen.entries.forEachIndexed { index, entry -> Button(entry, index, Modifier) }
                }
            }
            if (showTitle && titlePosition != null) DiscCanvas(style) { Title(Modifier.place(titlePosition)) }
        }
    }
}

// --- Buttons ---

@Composable
private fun DiscButton(entry: DiscMenuEntry, focused: Boolean, style: DiscStyle, defaultStyle: String?, pictures: DiscPictures, modifier: Modifier) {
    val styleName = (entry.style ?: defaultStyle ?: "frame").takeIf { it in setOf("text", "frame", "glow", "arrow") } ?: "frame"
    val sized = entry.position?.w != null || entry.position?.h != null
    val image = pictures.picture(entry.image)
    when {
        entry.thumbUrl != null -> ChapterButton(entry, entry.thumbUrl, focused, style, modifier)
        image != null -> ArtworkButton(image, pictures.picture(entry.imageFocus), focused, sized, style, modifier)
        else -> TextButton(entry, styleName, focused, sized, style, modifier)
    }
}

@Composable
private fun TextButton(entry: DiscMenuEntry, styleName: String, focused: Boolean, sized: Boolean, style: DiscStyle, modifier: Modifier) {
    val size = style.buttonFont
    val shape = RoundedCornerShape(size * 0.4f)
    val isFrame = styleName == "frame"
    val glow = styleName == "glow" && focused
    val fill by animateColorAsState(if (isFrame) Color.Black.copy(alpha = if (focused) 0.75f else 0.5f) else Color.Transparent, tween(120), label = "fill")
    Box(
        modifier.then(if (sized) Modifier.fillMaxWidth() else Modifier)
            .then(if (glow) Modifier.shadow(10.dp, shape, ambientColor = style.accent, spotColor = style.accent) else Modifier)
            .background(fill, shape)
            .border(2.dp, if (isFrame && focused) style.accent else Color.Transparent, shape)
            // In a grid cell the button is as wide as the cell, so less goes to padding and a long word keeps whole.
            .padding(vertical = size * 0.5f, horizontal = size * (if (sized) 0.5f else 1.25f)),
        contentAlignment = style.frameAlignment,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (styleName == "arrow") {
                Text("▶", Modifier.padding(end = size * 0.5f).alpha(if (focused) 1f else 0f), color = style.accent, fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { size.toSp() })
            }
            Column(horizontalAlignment = style.horizontal) {
                DiscText(entry.label, size, style, color = if (styleName == "text" && focused) style.accent else style.textColor)
                entry.sub?.let { DiscText(it, size * 0.65f, style, color = style.textColor.copy(alpha = 0.75f)) }
            }
        }
    }
}

/** The picture is the button; its label is only its name for accessibility. */
@Composable
private fun ArtworkButton(image: String, focusImage: String?, focused: Boolean, sized: Boolean, style: DiscStyle, modifier: Modifier) {
    val plainFocus = focused && focusImage == null
    val brighten = if (plainFocus) ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        1f, 0f, 0f, 0f, 38f, 0f, 1f, 0f, 0f, 38f, 0f, 0f, 1f, 0f, 38f, 0f, 0f, 0f, 1f, 0f,
    ))) else null
    AsyncImage(
        if (focused && focusImage != null) focusImage else image, null,
        modifier.then(if (sized) Modifier.fillMaxSize() else Modifier.widthIn(max = style.width * 0.4f).heightIn(max = style.height * 0.25f)),
        contentScale = ContentScale.Fit, colorFilter = brighten,
    )
}

/** Scene selection: a 16:9 thumbnail with "Label · time" below, the picture shrinking to leave the caption room in a short cell. */
@Composable
private fun ChapterButton(entry: DiscMenuEntry, thumb: String, focused: Boolean, style: DiscStyle, modifier: Modifier) {
    val caption = style.vh * 1.6f
    val padding = caption * 0.3f
    val shape = RoundedCornerShape(caption * 0.4f)
    BoxWithConstraints(
        modifier.fillMaxSize().background(Color.Black.copy(alpha = if (focused) 0.65f else 0.35f), shape)
            .border(2.dp, if (focused) style.accent else Color.Transparent, shape).clip(shape).padding(padding),
    ) {
        // Two lines of caption and the gap under the picture come off the height; the picture keeps 16:9 in what's left.
        val room = maxHeight - caption * 2.6f - padding
        val pictureWidth = minOf(maxWidth, room * 16 / 9).coerceAtLeast(0.dp)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            AsyncImage(thumb, null, Modifier.width(pictureWidth).aspectRatio(16f / 9f).clip(RoundedCornerShape(caption * 0.3f)), contentScale = ContentScale.Crop)
            DiscText(entry.label + (entry.sub?.let { " · $it" } ?: ""), caption, style, Modifier.fillMaxWidth())
        }
    }
}

