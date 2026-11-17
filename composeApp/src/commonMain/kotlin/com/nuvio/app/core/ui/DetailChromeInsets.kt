package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
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
 * Detail, stream, and player artwork run under the status area. The detail header
 * clears the live inset itself. Every other pushed screen is placed below the top
 * safe area by the host. Applying that inset again would leave a blank band.
 */
internal fun hostRespectsTopSafeArea(routeKind: String?): Boolean = when (routeKind) {
    null, "stream", "player", "detail" -> false
    else -> true
}

/** How far a quiet fade continues past the protected inset. A solid edge does not use it. */
internal const val SCROLL_EDGE_TAIL_DP = 28f

internal enum class ScrollEdgeKind { None, QuietFade, Solid }

/** One protective edge. A card, or a zero inset, draws nothing. Contrast makes it solid. */
internal fun scrollEdgeKind(
    safeTopDp: Float,
    suppressed: Boolean,
    reduceTransparency: Boolean,
    increaseContrast: Boolean,
): ScrollEdgeKind = when {
    suppressed || safeTopDp <= 0f -> ScrollEdgeKind.None
    reduceTransparency || increaseContrast -> ScrollEdgeKind.Solid
    else -> ScrollEdgeKind.QuietFade
}

/**
 * Quiet fade height. It covers [safeTopDp] and eases past it, and it stops at the next
 * title so the fade does not bury that title. A solid edge is only the inset.
 */
internal fun scrollEdgeHeightDp(
    safeTopDp: Float,
    contentTopDp: Float,
    solid: Boolean,
    tailDp: Float = SCROLL_EDGE_TAIL_DP,
): Float {
    val safe = safeTopDp.coerceAtLeast(0f)
    if (safe <= 0f) return 0f
    if (solid) return safe
    val limit = max(contentTopDp.coerceAtLeast(0f), safe)
    return (safe + tailDp).coerceAtMost(limit)
}

/** Test seam. Production leaves this unset and reads the window inset. */
internal val LocalDetailChromeTopOverride = staticCompositionLocalOf<Dp?> { null }

/**
 * Top of the hero image inside the Compose view. Detail starts at the top of that view,
 * under the status area. Other full-bleed routes are not placed by this helper.
 */
internal fun detailHeroImageTopDp(routeKind: String?): Float =
    if (routeKind == "detail" || hostRespectsTopSafeArea(routeKind)) 0f else Float.NaN

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
    LocalDetailChromeTopOverride.current?.let { return it }
    val physical = platformPhysicalTopInset().value
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value
    val safeDrawing = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
        .asPaddingValues()
        .calculateTopPadding()
        .value
    return detailChromeTopInsetDp(
        physicalDp = max(physical, safeDrawing),
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
