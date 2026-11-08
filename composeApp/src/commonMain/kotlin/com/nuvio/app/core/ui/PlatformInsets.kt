package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.isIos

internal expect val nuvioPlatformExtraTopPadding: Dp
internal expect val nuvioPlatformExtraBottomPadding: Dp
internal expect val nuvioBottomNavigationExtraVerticalPadding: Dp
@Composable
internal expect fun nuvioBottomNavigationBarInsets(): WindowInsets

/** Physical display-safe top inset, excluding any enclosing native toolbar. */
@Composable
internal expect fun platformPhysicalTopInset(): Dp

/** Physical display-safe bottom inset, including the home indicator. */
@Composable
internal expect fun platformPhysicalBottomInset(): Dp

/** The window's safe area. The tab host draws edge to edge, so this is not the Compose inset. */
internal data class DisplaySafeInsets(
    val top: Dp = 0.dp,
    val bottom: Dp = 0.dp,
    val left: Dp = 0.dp,
    val right: Dp = 0.dp,
)

@Composable
internal expect fun platformPhysicalSafeInsets(): DisplaySafeInsets

@Composable
internal expect fun ResizeForKeyboard()

internal val LocalNuvioBottomNavigationOverlayPadding = staticCompositionLocalOf { 0.dp }

internal val LocalBottomInsetsConsumed = staticCompositionLocalOf { false }

/** CompositionLocal providing the shared [NuvioNavBarScrollState] so child screens can attach the nestedScrollConnection. */
val LocalNuvioNavBarScrollState = staticCompositionLocalOf<NuvioNavBarScrollState?> { null }

@Composable
internal fun nuvioBottomSafeInset(): Dp {
	val reported = nuvioBottomNavigationBarInsets()
		.asPaddingValues()
		.calculateBottomPadding()
	return maxOf(reported, platformPhysicalBottomInset())
		.coerceAtLeast(nuvioPlatformExtraBottomPadding)
}

@Composable
internal fun nuvioSafeBottomPadding(extra: Dp = 0.dp): Dp {
	if (LocalBottomInsetsConsumed.current) return extra
	val tabBar = LocalNuvioBottomNavigationOverlayPadding.current
	return tabBarContentInsetDp(tabBar.value, nuvioBottomSafeInset().value).dp + extra
}

/** Apple's 44pt minimum control. Android keeps the size the caller already chose. */
internal fun Dp.atLeastIosHitTarget(): Dp = if (isIos) coerceAtLeast(44.dp) else this
