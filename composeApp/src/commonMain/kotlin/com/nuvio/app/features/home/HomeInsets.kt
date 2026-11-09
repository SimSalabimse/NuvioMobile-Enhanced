package com.nuvio.app.features.home

import kotlin.math.max

/**
 * Top padding for Home content. The tab host draws edge to edge, so this is the
 * larger of the Compose status bar and the window's top inset, applied once.
 * A portrait return whose status bar is still 0 uses the window top.
 * The same number from both sources is not added twice.
 */
internal fun homeContentTopInsetDp(statusBarDp: Float, windowTopDp: Float): Float =
    max(statusBarDp.coerceAtLeast(0f), windowTopDp.coerceAtLeast(0f))

/** iPhone 16 Pro portrait safe top, in points. Tests only. The layout reads the live inset. */
internal const val HOME_PRO_PORTRAIT_TOP_DP = 59f

/** Small-phone portrait status-bar top, in points. Tests only. */
internal const val HOME_SMALL_PORTRAIT_TOP_DP = 20f

/** The hero title and artwork. The screen background is not this tag. */
internal const val HOME_HERO_CONTENT_TAG = "home-hero-content"

/** The hero picture. It is not padded by the status inset. */
internal const val HOME_HERO_ART_TAG = "home-hero-art"

/**
 * Full-bleed artwork starts at the top of the viewport.
 * A card hero is content, so it keeps the one status inset.
 */
internal fun homeHeroTopPaddingDp(cardStyle: Boolean, contentTopDp: Float): Float =
    if (cardStyle) contentTopDp.coerceAtLeast(0f) else 0f

/**
 * Extra space above the title when the picture ends inside the unsafe band.
 * A picture that already clears the inset adds nothing, so the column is not padded twice.
 */
internal fun homeInteractiveClearanceDp(artworkBottomDp: Float, safeTopDp: Float): Float =
    (safeTopDp - artworkBottomDp).coerceAtLeast(0f)

/** How the full-bleed banner meets the unsafe top. A card and a zero inset draw nothing. */
internal enum class HomeTopEdgeKind { None, QuietFade, Solid }

internal fun homeTopEdgeKind(
    safeTopDp: Float,
    cardStyle: Boolean,
    reduceTransparency: Boolean,
    increaseContrast: Boolean,
): HomeTopEdgeKind = when {
    cardStyle || safeTopDp <= 0f -> HomeTopEdgeKind.None
    reduceTransparency || increaseContrast -> HomeTopEdgeKind.Solid
    else -> HomeTopEdgeKind.QuietFade
}

/** The fade over the unsafe band. It is not the title. */
internal const val HOME_HERO_TOP_EDGE_TAG = "home-hero-top-edge"
