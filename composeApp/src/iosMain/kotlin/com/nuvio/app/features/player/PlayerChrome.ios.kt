package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityIsReduceTransparencyEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification
import platform.UIKit.UIAccessibilityReduceTransparencyStatusDidChangeNotification
import platform.UIKit.UIBlurEffect
import platform.UIKit.UIBlurEffectStyle
import platform.UIKit.UIColor
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIView
import platform.UIKit.UIVisualEffectView

@Composable
internal actual fun playerReduceTransparencyEnabled(): Boolean = rememberIosAccessibilityFlag(
    read = { UIAccessibilityIsReduceTransparencyEnabled() },
    notification = UIAccessibilityReduceTransparencyStatusDidChangeNotification,
)

@Composable
internal actual fun playerReduceMotionEnabled(): Boolean = rememberIosAccessibilityFlag(
    read = { UIAccessibilityIsReduceMotionEnabled() },
    notification = UIAccessibilityReduceMotionStatusDidChangeNotification,
)

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun IosSystemMaterial(modifier: Modifier) {
    UIKitView(
        factory = { playerMaterialView() },
        modifier = modifier,
        background = Color.Transparent,
        onResize = { view, rect ->
            view.setFrame(rect)
            rect.useContents {
                layoutPlayerMaterial(view, CGRectMake(0.0, 0.0, size.width, size.height))
            }
        },
        interactive = false,
        accessibilityEnabled = false,
    )
}

@Composable
private fun rememberIosAccessibilityFlag(
    read: () -> Boolean,
    notification: String?,
): Boolean {
    var enabled by remember { mutableStateOf(read()) }
    DisposableEffect(notification) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = notification,
            `object` = null,
            queue = null,
        ) { _ ->
            enabled = read()
        }
        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(observer)
        }
    }
    return enabled
}

@OptIn(ExperimentalForeignApi::class)
private fun playerMaterialView(): UIView {
    val container = UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
    container.backgroundColor = UIColor.clearColor
    container.opaque = false
    container.userInteractionEnabled = false
    container.clipsToBounds = true
    val blur = UIVisualEffectView(
        effect = UIBlurEffect.effectWithStyle(UIBlurEffectStyle.UIBlurEffectStyleSystemUltraThinMaterialDark),
    )
    blur.overrideUserInterfaceStyle = UIUserInterfaceStyle.UIUserInterfaceStyleDark
    blur.backgroundColor = UIColor.clearColor
    blur.opaque = false
    blur.userInteractionEnabled = false
    blur.clipsToBounds = true
    container.addSubview(blur)
    return container
}

@OptIn(ExperimentalForeignApi::class)
private fun layoutPlayerMaterial(container: UIView, bounds: CValue<CGRect>) {
    (container.subviews.firstOrNull() as? UIVisualEffectView)?.setFrame(bounds)
}
