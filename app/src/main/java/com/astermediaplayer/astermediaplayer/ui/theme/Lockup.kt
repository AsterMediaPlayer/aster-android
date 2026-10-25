package com.astermediaplayer.astermediaplayer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * The Apple apps' phone layout constants (`Lockup` in Components.swift and the title page's own numbers), in dp and sp
 * so a screen here lines up with the same screen there. Change them together.
 */
object Lockup {
    var landscapeWidth = 175.dp; private set
    /** A landscape card's height is its width times 9/16. */
    const val LANDSCAPE_ASPECT = 16f / 9f
    var posterWidth = 110.dp; private set
    const val POSTER_ASPECT = 2f / 3f
    var cornerRadius = 10.dp; private set
    var screenPadding = 16.dp; private set
    var shelfSpacing = 12.dp; private set
    var shelfVerticalPadding = 8.dp; private set
    /** Between a shelf's title and its row. */
    var shelfTitleSpacing = 12.dp; private set
    /** Between a card and the text under it, and between that text's two lines. */
    var cardLabelSpacing = 10.dp; private set
    var cardTextSpacing = 2.dp; private set
    var personPhotoSize = 80.dp; private set
    var personWidth = 96.dp; private set
    var personSpacing = 12.dp; private set
    var personLabelSpacing = 6.dp; private set
    var pageBottomPadding = 24.dp; private set
    var sectionSpacing = 16.dp; private set
    /** The dp one Apple TV point comes to on this screen (1 off a TV); see [useTv]. */
    var tvUnit = 1f; private set

    /**
     * The Apple TV's numbers (the tvOS branch of `Lockup`, in points on its 1920x1080 screen) for a TV. [unit] is the dp one
     * of those points comes to on this screen, so a card is as many pixels wide here as there. Set once, before anything draws.
     */
    fun useTv(unit: Float) {
        tvUnit = unit
        landscapeWidth = (400 * unit).dp
        posterWidth = (240 * unit).dp
        cornerRadius = (14 * unit).dp
        screenPadding = (80 * unit).dp
        shelfSpacing = (36 * unit).dp
        shelfVerticalPadding = (30 * unit).dp
        shelfTitleSpacing = (10 * unit).dp
        cardLabelSpacing = (14 * unit).dp
        cardTextSpacing = (6 * unit).dp
        personPhotoSize = (200 * unit).dp
        personWidth = (220 * unit).dp
        personSpacing = (28 * unit).dp
        personLabelSpacing = (10 * unit).dp
        pageBottomPadding = (80 * unit).dp
        sectionSpacing = (30 * unit).dp
    }
}

/** The title page's own measurements. */
object DetailMetrics {
    /** The header is at least this tall, so the first screen is just the art and the title block. */
    val headerMinHeight = 780.dp
    val headerSpacing = 20.dp
    val headerBottomPadding = 20.dp
    /** Between the header, the tab bar and each section. */
    val sectionSpacing = 36.dp
    val pageBottomPadding = 80.dp
    val logoMaxWidth = 280.dp
    val logoMaxHeight = 100.dp
    val seriesLogoMaxWidth = 220.dp
    val seriesLogoMaxHeight = 70.dp
    val backButtonSize = 44.dp
    val buttonHeight = 34.dp
    val buttonHorizontalPadding = 22.dp
    val iconButtonWidth = 46.dp
    val actionSpacing = 20.dp
    val themeButtonSpacing = 12.dp
    val actionRowSpacing = 10.dp
    val segmentedHeight = 32.dp
    val segmentedMaxWidth = 560.dp
    val seasonPillHeight = 33.5.dp
    val seasonPillHorizontalPadding = 18.5.dp
    val seasonSpacing = 16.dp
    val seasonVerticalPadding = 16.dp
    val seasonShelfSpacing = 10.dp
}

/**
 * iOS's text styles: size and line height in points, and the weight the Apple apps use. Inter sets a little narrower
 * than San Francisco, so the letter-spacing is what makes a string here as wide as the same string there: it was
 * tuned against widths measured on an iPhone ("Episodes" at 20sp is 82.6 wide, "Beastars (2021)" at 16sp 118.8,
 * "Watch OP" at 17sp 76.9, the tab control's "Episodes" 55.5).
 */
object Type {
    var callout = TextStyle(fontFamily = SfText, lineBreak = LineBreak.Simple, fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.04).sp); private set
    var calloutMedium = callout.copy(fontWeight = FontWeight.Medium); private set
    var calloutSemibold = callout.copy(fontWeight = FontWeight.SemiBold); private set
    var headline = TextStyle(fontFamily = SfText, lineBreak = LineBreak.Simple, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.19).sp); private set
    var title3 = TextStyle(fontFamily = SfDisplay, lineBreak = LineBreak.Simple, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp); private set
    var caption = TextStyle(fontFamily = SfText, lineBreak = LineBreak.Simple, fontSize = 12.sp, lineHeight = 16.sp); private set
    var caption2Bold = TextStyle(fontFamily = SfText, lineBreak = LineBreak.Simple, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.1.sp); private set
    var segmented = TextStyle(fontFamily = SfText, lineBreak = LineBreak.Simple, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.26).sp); private set
    /** A title with no clear-art logo. */
    var pageTitle = TextStyle(fontFamily = SfDisplay, lineBreak = LineBreak.Simple, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.0.sp); private set

    /** The same styles at the Apple TV's sizes (tvOS's text styles, in points), for a TV. [unit] is the sp one point comes to; see [Lockup.useTv]. */
    fun useTv(unit: Float) {
        fun TextStyle.tv(size: Int, line: Int) = copy(fontSize = (size * unit).sp, lineHeight = (line * unit).sp, letterSpacing = if (letterSpacing.isSpecified) letterSpacing * (size / fontSize.value) else letterSpacing)
        callout = callout.tv(31, 40)
        calloutMedium = calloutMedium.tv(31, 40)
        calloutSemibold = calloutSemibold.tv(31, 40)
        headline = headline.tv(34, 44)
        title3 = title3.tv(46, 56)
        caption = caption.tv(26, 32)
        caption2Bold = caption2Bold.tv(22, 28)
        segmented = segmented.tv(28, 34)
        pageTitle = pageTitle.tv(64, 76)
    }
}

/** `.secondary` on a dark page. */
val SecondaryText = Color.White.copy(alpha = 0.6f)
/** The grey of the Apple apps' secondary buttons (`Color(white: 0.3)`). */
val ButtonGrey = Color(0xFF4D4D4D)
