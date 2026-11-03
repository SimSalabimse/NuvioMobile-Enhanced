package com.nuvio.app.core.ui

/**
 * Scrollable content keeps this much clear at the bottom: the floating tab bar
 * plus the bottom safe-area inset.
 */
fun tabBarContentInsetDp(tabBarHeightDp: Float, safeAreaBottomDp: Float): Float =
    tabBarHeightDp.coerceAtLeast(0f) + safeAreaBottomDp.coerceAtLeast(0f)

/**
 * Distance from the physical bottom to the top of the tab bar, plus room so the
 * last row's labels are not under the bar.
 *
 * Native glass: the pill is 62pt and keeps 20pt above the physical bottom.
 * Compose pill: the track grows with its labels and sits 8pt above the safe area.
 */
fun floatingTabContentBottomInsetDp(
    nativeTabs: Boolean,
    safeAreaBottomDp: Float,
    labelFraction: Float = 1f,
): Float {
    val safe = safeAreaBottomDp.coerceAtLeast(0f)
    val fraction = labelFraction.coerceIn(0f, 1f)
    val labelClearance = 16f
    return if (nativeTabs) {
        val fromPhysicalBottom = 62f + 20f + labelClearance
        maxOf(fromPhysicalBottom, tabBarContentInsetDp(tabBarHeightDp = 62f, safeAreaBottomDp = safe))
    } else {
        val track = 48f + 16f * fraction
        tabBarContentInsetDp(tabBarHeightDp = track + 8f + labelClearance, safeAreaBottomDp = safe)
    }
}
