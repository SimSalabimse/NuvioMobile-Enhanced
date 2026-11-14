package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.isIos
import kotlin.math.max

internal expect val nuvioPlatformExtraTopPadding: Dp
internal expect val nuvioPlatformExtraBottomPadding: Dp
internal expect val nuvioBottomNavigationExtraVerticalPadding: Dp
@Composable
internal expect fun nuvioBottomNavigationBarInsets(): WindowInsets

/** Physical display-safe top inset, excluding any enclosing native toolbar. */
@Composable
internal expect fun platformPhysicalTopInset(): Dp

/**
 * Test seam for the window's top safe area. Production leaves this null and reads the window.
 * The value is the inset itself, not a stand-in for a status-bar constant inside layout.
 */
internal val LocalWindowSafeTopInset = staticCompositionLocalOf<Dp?> { null }

/** Status bar, window top, and safe drawing, once. The largest of the three. */
internal fun windowSafeTopInsetDp(
    statusBarDp: Float,
    windowTopDp: Float,
    safeDrawingDp: Float = 0f,
): Float = max(
    max(statusBarDp.coerceAtLeast(0f), windowTopDp.coerceAtLeast(0f)),
    safeDrawingDp.coerceAtLeast(0f),
)

@Composable
internal fun windowSafeTopInset(): Dp {
    LocalWindowSafeTopInset.current?.let { return it.coerceAtLeast(0.dp) }
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value
    val windowTop = platformPhysicalTopInset().value
    val safeDrawing = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
        .asPaddingValues()
        .calculateTopPadding()
        .value
    return windowSafeTopInsetDp(status, windowTop, safeDrawing).dp
}

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

/**
 * The first composition can run before the view is in a window, so the first
 * reading is often 0. [subscribe] must call back when a later reading exists.
 * A one-shot read never sees the window attach.
 */
@Composable
internal fun rememberLatestInset(
    key: Any?,
    read: () -> DisplaySafeInsets,
    subscribe: (onChange: () -> Unit) -> () -> Unit,
): DisplaySafeInsets {
    var insets by remember(key) { mutableStateOf(read()) }
    DisposableEffect(key) {
        val unsubscribe = subscribe {
            val next = read()
            if (next != insets) insets = next
        }
        val next = read()
        if (next != insets) insets = next
        onDispose { unsubscribe() }
    }
    return insets
}

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
