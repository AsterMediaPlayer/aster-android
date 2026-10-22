package com.astermediaplayer.astermediaplayer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Apple apps' phone layout constants (`Lockup` in Components.swift and the title page's own numbers), in dp and sp
 * so a screen here lines up with the same screen there. Change them together.
 */
object Lockup {
    val landscapeWidth = 175.dp
    /** A landscape card's height is its width times 9/16. */
    const val LANDSCAPE_ASPECT = 16f / 9f
    val posterWidth = 110.dp
    const val POSTER_ASPECT = 2f / 3f
    val cornerRadius = 10.dp
    val screenPadding = 16.dp
    val shelfSpacing = 12.dp
    val shelfVerticalPadding = 8.dp
    /** Between a shelf's title and its row. */
    val shelfTitleSpacing = 12.dp
    /** Between a card and the text under it, and between that text's two lines. */
    val cardLabelSpacing = 10.dp
    val cardTextSpacing = 2.dp
    val personPhotoSize = 80.dp
    val personWidth = 96.dp
    val personSpacing = 12.dp
    val personLabelSpacing = 6.dp
    val pageBottomPadding = 24.dp
    val sectionSpacing = 16.dp
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

/** iOS's text styles: size and line height in points, the weight the Apple apps ask for. */
object Type {
    val callout = TextStyle(fontSize = 16.sp, lineHeight = 21.sp)
    val calloutMedium = callout.copy(fontWeight = FontWeight.Medium)
    val calloutSemibold = callout.copy(fontWeight = FontWeight.SemiBold)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val title3 = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val caption2Bold = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold)
    val segmented = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    /** A title with no clear-art logo. */
    val pageTitle = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
}

/** `.secondary` on a dark page. */
val SecondaryText = Color.White.copy(alpha = 0.6f)
/** The grey of the Apple apps' secondary buttons (`Color(white: 0.3)`). */
val ButtonGrey = Color(0xFF4D4D4D)
