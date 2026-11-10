package com.nuvio.app.features.home

import com.nuvio.app.core.ui.SCROLL_EDGE_TAIL_DP
import com.nuvio.app.core.ui.ScrollEdgeKind
import com.nuvio.app.core.ui.scrollEdgeHeightDp
import com.nuvio.app.core.ui.scrollEdgeKind
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
): HomeTopEdgeKind = when (
    scrollEdgeKind(
        safeTopDp = safeTopDp,
        suppressed = cardStyle,
        reduceTransparency = reduceTransparency,
        increaseContrast = increaseContrast,
    )
) {
    ScrollEdgeKind.None -> HomeTopEdgeKind.None
    ScrollEdgeKind.QuietFade -> HomeTopEdgeKind.QuietFade
    ScrollEdgeKind.Solid -> HomeTopEdgeKind.Solid
}

/**
 * The quiet fade covers the inset and eases past it by [SCROLL_EDGE_TAIL_DP].
 * It stops where the title starts. A solid edge is only the inset.
 */
internal fun homeTopEdgeHeightDp(safeTopDp: Float, artworkBottomDp: Float, solid: Boolean): Float =
    scrollEdgeHeightDp(
        safeTopDp = safeTopDp,
        contentTopDp = artworkBottomDp,
        solid = solid,
        tailDp = SCROLL_EDGE_TAIL_DP,
    )

/** The fade over the unsafe band. It is not the title. */
internal const val HOME_HERO_TOP_EDGE_TAG = "home-hero-top-edge"
