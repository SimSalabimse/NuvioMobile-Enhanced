package com.nuvio.app.features.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

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

/**
 * Dynamic Type scale for home, details, and Downloads.
 * iOS reads the same content-size category as the season sheet.
 * Android reads the configuration scale, which the app theme otherwise pins to 1.
 */
@Composable
internal expect fun changedSurfaceFontScale(): Float

internal fun changedSurfaceDensity(density: Float, fontScale: Float): Density =
    Density(density = density, fontScale = fontScale)

@Composable
internal fun ChangedSurfaceTextScale(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides changedSurfaceDensity(base.density, changedSurfaceFontScale()),
    ) {
        content()
    }
}
