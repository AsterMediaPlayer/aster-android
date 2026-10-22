package com.astermediaplayer.astermediaplayer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.astermediaplayer.astermediaplayer.R

/**
 * Inter, the open font closest to the Apple apps' San Francisco (SF isn't licensed outside Apple's platforms). It's
 * one variable file; like SF it has a text cut for body sizes and a display cut, here the `opsz` axis, for 20sp and up.
 */
private fun inter(opticalSize: Float) = FontFamily(
    listOf(400, 500, 600, 700).map { weight ->
        Font(
            R.font.inter, FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.opticalSizing(opticalSize.sp)),
        )
    },
)

/** For text under 20sp, as SF Pro Text. */
val SfText = inter(14f)
/** For titles of 20sp and up, as SF Pro Display. */
val SfDisplay = inter(32f)

/** Material's text styles in the same font, so everything not set by [Type] matches too. */
fun asterTypography(): Typography {
    val base = Typography()
    fun TextStyle.sf() = copy(fontFamily = if ((fontSize.value) >= 20f) SfDisplay else SfText)
    return Typography(
        displayLarge = base.displayLarge.sf(), displayMedium = base.displayMedium.sf(), displaySmall = base.displaySmall.sf(),
        headlineLarge = base.headlineLarge.sf(), headlineMedium = base.headlineMedium.sf(), headlineSmall = base.headlineSmall.sf(),
        titleLarge = base.titleLarge.sf(), titleMedium = base.titleMedium.sf(), titleSmall = base.titleSmall.sf(),
        bodyLarge = base.bodyLarge.sf(), bodyMedium = base.bodyMedium.sf(), bodySmall = base.bodySmall.sf(),
        labelLarge = base.labelLarge.sf(), labelMedium = base.labelMedium.sf(), labelSmall = base.labelSmall.sf(),
    )
}
