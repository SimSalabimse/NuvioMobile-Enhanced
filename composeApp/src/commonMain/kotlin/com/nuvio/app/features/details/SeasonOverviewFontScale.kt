package com.nuvio.app.features.details

import androidx.compose.runtime.Composable

/**
 * Body-text scale for an iOS content-size category, relative to Large (1).
 * AX5 (`AccessibilityXXXL`) is the largest. Unknown names stay at 1.
 */
internal fun fontScaleForIosContentCategory(category: String?): Float {
    val name = category?.trim().orEmpty()
    if (name.isEmpty()) return 1f
    return when {
        name.endsWith("AccessibilityXXXL") || name.endsWith("AccessibilityExtraExtraExtraLarge") -> 3.12f
        name.endsWith("AccessibilityXXL") || name.endsWith("AccessibilityExtraExtraLarge") -> 2.76f
        name.endsWith("AccessibilityXL") || name.endsWith("AccessibilityExtraLarge") -> 2.35f
        name.endsWith("AccessibilityL") || name.endsWith("AccessibilityLarge") -> 1.94f
        name.endsWith("AccessibilityM") || name.endsWith("AccessibilityMedium") -> 1.65f
        name.endsWith("XXXL") || name.endsWith("ExtraExtraExtraLarge") -> 1.35f
        name.endsWith("XXL") || name.endsWith("ExtraExtraLarge") -> 1.24f
        name.endsWith("XL") || name.endsWith("ExtraLarge") -> 1.12f
        name.endsWith("XS") || name.endsWith("ExtraSmall") -> 0.82f
        name.endsWith("CategoryS") || name.endsWith("SizeCategorySmall") -> 0.88f
        name.endsWith("CategoryM") || name.endsWith("SizeCategoryMedium") -> 0.94f
        name.endsWith("CategoryL") || name.endsWith("SizeCategoryLarge") -> 1f
        else -> 1f
    }
}

/** Dynamic Type scale for the season overview sheet. Large is 1. */
@Composable
internal expect fun seasonOverviewFontScale(): Float
