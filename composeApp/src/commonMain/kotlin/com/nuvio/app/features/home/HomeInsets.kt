package com.nuvio.app.features.home

import com.nuvio.app.core.ui.windowSafeTopInsetDp

/**
 * Top padding for Home content. The tab host draws edge to edge, so this is the
 * larger of the Compose status bar and the window's top inset, applied once.
 * A portrait return whose status bar is still 0 uses the window top.
 * The same number from both sources is not added twice.
 */
internal fun homeContentTopInsetDp(
    statusBarDp: Float,
    windowTopDp: Float,
    safeDrawingDp: Float = 0f,
): Float = windowSafeTopInsetDp(statusBarDp, windowTopDp, safeDrawingDp)

/** Space under the status inset before Home foreground. A zero inset adds none. */
internal const val HOME_FOREGROUND_GAP_DP = 12f

/** iPhone 16 Pro portrait safe top, in points. Tests only. The layout reads the live inset. */
internal const val HOME_PRO_PORTRAIT_TOP_DP = 59f

/** Small-phone portrait status-bar top, in points. Tests only. */
internal const val HOME_SMALL_PORTRAIT_TOP_DP = 20f

/** The hero title and artwork. The screen background is not this tag. */
internal const val HOME_HERO_CONTENT_TAG = "home-hero-content"

/** The hero picture. The screen keeps it below the status inset. */
internal const val HOME_HERO_ART_TAG = "home-hero-art"

/** Logo, or the title when the logo is missing. */
internal const val HOME_HERO_LOGO_TAG = "home-hero-logo"

/** Year, rating, and the other badges under the title. */
internal const val HOME_HERO_BADGES_TAG = "home-hero-badges"

/** Carousel page dots. */
internal const val HOME_HERO_DOTS_TAG = "home-hero-dots"

/**
 * The home list already starts below the window's top inset.
 * The hero does not add that inset again, for a card or for the banner.
 */
internal fun homeHeroTopPaddingDp(cardStyle: Boolean, contentTopDp: Float): Float {
    if (contentTopDp < 0f || cardStyle) return 0f
    return 0f
}
