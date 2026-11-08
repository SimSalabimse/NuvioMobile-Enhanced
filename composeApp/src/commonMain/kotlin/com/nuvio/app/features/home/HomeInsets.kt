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
