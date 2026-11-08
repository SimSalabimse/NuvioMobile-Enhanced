package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.navigation.LocalHostRespectsTopSafeArea
import kotlin.math.max

/** UIKit's standard navigation bar. Used only when that bar overlays an immersive hero. */
internal const val NATIVE_NAVIGATION_BAR_DP = 44f

/** Accessibility sizes past the system Larger Text setting. Normal Larger Text stays under this. */
internal const val EXTREME_FONT_SCALE = 1.5f

internal fun extremeAccessibilityText(fontScale: Float): Boolean = fontScale >= EXTREME_FONT_SCALE

/**
 * Routes whose artwork is allowed to run under the status bar.
 * Every other pushed screen is placed below the top safe area by the host.
 */
internal fun hostRespectsTopSafeArea(routeKind: String?): Boolean = when (routeKind) {
    null, "detail", "stream", "player" -> false
    else -> true
}

/** Extra top padding Compose may add. Zero when the host already applied the top safe area. */
internal fun detailChromeTopInsetDp(
    physicalDp: Float,
    statusBarDp: Float,
    hostRespectsTop: Boolean,
): Float {
    if (hostRespectsTop) return 0f
    return max(physicalDp, statusBarDp).coerceAtLeast(0f)
}

/**
 * Title under the artwork. A tall picture already clears the island, so the title keeps a small gap.
 * A short picture does not, and the title moves down by the remaining inset.
 */
internal fun headingTopPaddingDp(artworkHeightDp: Float, topInsetDp: Float, minimumDp: Float = 8f): Float {
    val inset = topInsetDp.coerceAtLeast(0f)
    val art = artworkHeightDp.coerceAtLeast(0f)
    return (inset - art).coerceAtLeast(minimumDp)
}

/** Text on an immersive hero starts below the status bar, and below the navigation bar when it overlays. */
internal fun immersiveTextTopInsetDp(
    chromeDp: Float,
    navigationBarDp: Float,
    nativeBarOverlaysHero: Boolean,
): Float {
    val chrome = chromeDp.coerceAtLeast(0f)
    val bar = if (nativeBarOverlaysHero) navigationBarDp.coerceAtLeast(0f) else 0f
    return chrome + bar
}

@Composable
internal fun detailChromeTopInset(): Dp {
    val physical = platformPhysicalTopInset().value
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value
    return detailChromeTopInsetDp(
        physicalDp = physical,
        statusBarDp = status,
        hostRespectsTop = LocalHostRespectsTopSafeArea.current,
    ).dp
}

/** Status-bar padding for a pushed screen that is not an immersive hero. */
@Composable
internal fun Modifier.respectHostTopInset(): Modifier =
    if (LocalHostRespectsTopSafeArea.current) {
        this
    } else {
        windowInsetsPadding(WindowInsets.statusBars)
    }
