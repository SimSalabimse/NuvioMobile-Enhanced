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
 * The detail hero image and the page under it are content, so the host places them
 * below the status bar. Stream and player stay full-bleed. Every other pushed screen
 * is also placed below the top safe area by the host.
 */
internal fun hostRespectsTopSafeArea(routeKind: String?): Boolean = when (routeKind) {
    null, "stream", "player" -> false
    else -> true
}

/** Top of the detail hero image inside the Compose view. The host already applied its inset. */
internal fun detailHeroImageTopDp(routeKind: String?): Float =
    if (hostRespectsTopSafeArea(routeKind)) 0f else Float.NaN

/** The custom hero back button is absent while the native back button is on screen. */
internal fun detailHeroBackShown(nativeBackVisible: Boolean): Boolean = !nativeBackVisible

/**
 * Extra top padding for that custom button. When the host already inset the page, this is 8.
 * It does not add the status bar again.
 */
internal fun detailHeroBackTopDp(hostRespectsTop: Boolean, statusBarDp: Float): Float =
    detailChromeTopInsetDp(statusBarDp, statusBarDp, hostRespectsTop) + 8f

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
