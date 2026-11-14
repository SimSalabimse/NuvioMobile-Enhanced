package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.round
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIColor
import platform.UIKit.UIEdgeInsets
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

internal actual val nuvioPlatformExtraTopPadding: Dp = 0.dp
internal actual val nuvioPlatformExtraBottomPadding: Dp = 0.dp
internal actual val nuvioBottomNavigationExtraVerticalPadding: Dp = 0.dp

@Composable
internal actual fun nuvioBottomNavigationBarInsets(): WindowInsets =
	WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)

@Composable
internal actual fun platformPhysicalTopInset(): Dp = platformPhysicalSafeInsets().top

@Composable
internal actual fun platformPhysicalBottomInset(): Dp = platformPhysicalSafeInsets().bottom

/**
 * The window is often still nil during the first composition. Reading it once
 * then falls through to a status-bar inset of 0 and never updates. The probe
 * publishes again from didMoveToWindow, layout, and safe-area changes.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun platformPhysicalSafeInsets(): DisplaySafeInsets {
    val controller = LocalUIViewController.current
    val observed = rememberLatestInset(
        key = controller,
        read = { controller.windowSafeInsets() },
        subscribe = { onChange -> controller.watchWindowSafeInsets(onChange) },
    )
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val drawing = WindowInsets.safeDrawing.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
    val composeTop = maxOf(status, drawing)
    return if (composeTop > observed.top) observed.copy(top = composeTop) else observed
}

@OptIn(ExperimentalForeignApi::class)
private fun UIViewController.windowSafeInsets(): DisplaySafeInsets {
    val host = view.safeAreaInsets.toStableInsets()
    val window = view.window?.safeAreaInsets?.toStableInsets()
    return if (window == null) host else host.largest(window)
}

@OptIn(ExperimentalForeignApi::class)
private fun UIViewController.watchWindowSafeInsets(onChange: () -> Unit): () -> Unit {
    val probe = WindowInsetProbe(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
    probe.userInteractionEnabled = false
    probe.backgroundColor = UIColor.clearColor
    probe.opaque = false
    probe.onChange = onChange
    view.addSubview(probe)
    onChange()
    // The window can attach on the turn after the first composition. Read again
    // then, as well as from the probe's own layout and safe-area callbacks.
    var live = true
    dispatch_async(dispatch_get_main_queue()) {
        if (live) onChange()
    }
    return {
        live = false
        probe.onChange = null
        probe.removeFromSuperview()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class WindowInsetProbe(frame: CValue<CGRect>) : UIView(frame) {
    var onChange: (() -> Unit)? = null

    override fun didMoveToWindow() {
        super.didMoveToWindow()
        onChange?.invoke()
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        onChange?.invoke()
    }

    override fun safeAreaInsetsDidChange() {
        super.safeAreaInsetsDidChange()
        onChange?.invoke()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CValue<UIEdgeInsets>.toStableInsets(): DisplaySafeInsets = useContents {
    DisplaySafeInsets(
        top = top.toFloat().stablePoint(),
        bottom = bottom.toFloat().stablePoint(),
        left = left.toFloat().stablePoint(),
        right = right.toFloat().stablePoint(),
    )
}

private fun DisplaySafeInsets.largest(other: DisplaySafeInsets): DisplaySafeInsets = DisplaySafeInsets(
    top = maxOf(top, other.top),
    bottom = maxOf(bottom, other.bottom),
    left = maxOf(left, other.left),
    right = maxOf(right, other.right),
)

private fun Float.stablePoint(): Dp = (round(this * 2f) / 2f).dp

@Composable
internal actual fun ResizeForKeyboard() = Unit
