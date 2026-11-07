package com.nuvio.app.features.details

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp

/**
 * Layout for home and details once Dynamic Type is in the accessibility range.
 * The font scale is applied in full. These helpers only choose a smaller base
 * style, a stacked control row, or extra scroll room. They do not divide the scale.
 */
internal const val ACCESSIBILITY_FONT_SCALE = 1.5f

/** Average advance of a bold Latin glyph, as a fraction of the font size. */
internal const val HERO_TITLE_CHAR_EM = 0.62f

/** Smallest title base. At AX5 this is still larger than body text at the default size. */
internal const val HERO_TITLE_MIN_BASE_SP = 13f

/** Apple's 44pt minimum. Used for the stacked Smart downloads choices. */
internal const val ACCESSIBILITY_CONTROL_MIN_DP = 44f

internal fun accessibilityTextSize(fontScale: Float): Boolean = fontScale >= ACCESSIBILITY_FONT_SCALE

internal fun longestWordLength(title: String): Int {
    val words = title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return words.maxOfOrNull { it.length } ?: 1
}

/**
 * Base font size in sp. Below the accessibility range the caller keeps its display style.
 * Inside that range the largest base at or under [defaultBaseSp] is chosen so the longest
 * word still fits [contentWidthDp] after [fontScale] is applied. [fontScale] is never reduced.
 */
internal fun heroTitleBaseSp(
    fontScale: Float,
    contentWidthDp: Float,
    defaultBaseSp: Float,
    longestWordChars: Int,
): Float {
    if (!accessibilityTextSize(fontScale) || contentWidthDp <= 0f) return defaultBaseSp
    val chars = longestWordChars.coerceAtLeast(1)
    val renderedRoom = contentWidthDp / (HERO_TITLE_CHAR_EM * chars)
    val candidates = floatArrayOf(38f, 32f, 28f, 26f, 22f, 18f, 16f, 14f, HERO_TITLE_MIN_BASE_SP)
    for (base in candidates) {
        if (base <= defaultBaseSp + 0.01f && base * fontScale <= renderedRoom) return base
    }
    return HERO_TITLE_MIN_BASE_SP
}

/** True when the label and the count choices should stack instead of sharing one row. */
internal fun smartDownloadPickerStacks(fontScale: Float): Boolean = accessibilityTextSize(fontScale)

/**
 * Display style at normal sizes. At an accessibility size, a smaller base so the longest
 * word fits [contentWidthDp]. An unspecified font size is left alone.
 */
internal fun accessibleHeroTitleStyle(
    base: TextStyle,
    contentWidthDp: Float,
    title: String,
    fontScale: Float,
): TextStyle {
    val defaultSp = base.fontSize.value
    if (!accessibilityTextSize(fontScale) || defaultSp.isNaN()) return base
    val chosen = heroTitleBaseSp(fontScale, contentWidthDp, defaultSp, longestWordLength(title))
    if (chosen >= defaultSp - 0.01f) return base
    return base.copy(
        fontSize = chosen.sp,
        lineHeight = (chosen * 1.2f).sp,
        letterSpacing = 0.sp,
        lineBreak = LineBreak.Simple,
        hyphens = Hyphens.None,
    )
}
