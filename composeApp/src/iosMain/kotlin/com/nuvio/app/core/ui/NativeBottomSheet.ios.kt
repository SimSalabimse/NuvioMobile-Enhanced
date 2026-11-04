package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.compose.ui.platform.LocalDensity
import kotlin.coroutines.resume
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIBlurEffect
import platform.UIKit.UIBlurEffectStyle
import platform.UIKit.UIColor
import platform.UIKit.UIDevice
import platform.UIKit.UIModalPresentationPageSheet
import platform.UIKit.UIPresentationController
import platform.UIKit.UISheetPresentationController
import platform.UIKit.UISheetPresentationControllerDetent
import platform.UIKit.UISheetPresentationControllerDetentIdentifierMedium
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.UIVisualEffectView
import platform.UIKit.presentationController
import platform.darwin.NSObject

internal actual val usesNativeNuvioBottomSheet: Boolean = true

private var activeNativeBottomSheet: UIViewController? = null
private var activeNativeBottomSheetDelegate: NuvioNativeBottomSheetDelegate? = null

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalForeignApi::class)
@Composable
internal actual fun NuvioNativeModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    containerColor: Color,
    contentColor: Color,
    showDragHandle: Boolean,
    fullHeight: Boolean,
    liquidGlass: Boolean,
    fitSheet: FitSheetHeightHost?,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val parentViewController = LocalUIViewController.current
    val latestContent = rememberUpdatedState(content)
    val latestOnDismissRequest = rememberUpdatedState(onDismissRequest)
    val latestContainerColor = rememberUpdatedState(containerColor)
    val latestContentColor = rememberUpdatedState(contentColor)
    val latestModifier = rememberUpdatedState(modifier)
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    val themeTokens = LocalNuvioThemeTokens.current
    val typeScale = LocalNuvioTypeScale.current
    val appTheme = LocalAppTheme.current
    val themePalette = LocalThemePalette.current
    val density = LocalDensity.current
    val rippleConfiguration = LocalRippleConfiguration.current

    val latestColorScheme = rememberUpdatedState(colorScheme)
    val latestTypography = rememberUpdatedState(typography)
    val latestShapes = rememberUpdatedState(shapes)
    val latestThemeTokens = rememberUpdatedState(themeTokens)
    val latestTypeScale = rememberUpdatedState(typeScale)
    val latestAppTheme = rememberUpdatedState(appTheme)
    val latestThemePalette = rememberUpdatedState(themePalette)
    val latestDensity = rememberUpdatedState(density)
    val latestRippleConfiguration = rememberUpdatedState(rippleConfiguration)
    val latestLiquidGlass = rememberUpdatedState(liquidGlass)
    val latestFitSheet = rememberUpdatedState(fitSheet)

    DisposableEffect(parentViewController, liquidGlass, fullHeight, fitSheet != null) {
        val contentController = ComposeUIViewController(
            configure = {
                if (liquidGlass) opaque = false
            },
        ) {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = latestDensity.value.density,
                    fontScale = latestDensity.value.fontScale,
                ),
                LocalNuvioThemeTokens provides latestThemeTokens.value,
                LocalNuvioTypeScale provides latestTypeScale.value,
                LocalAppTheme provides latestAppTheme.value,
                LocalThemePalette provides latestThemePalette.value,
                LocalRippleConfiguration provides latestRippleConfiguration.value,
            ) {
                MaterialTheme(
                    colorScheme = latestColorScheme.value,
                    typography = latestTypography.value,
                    shapes = latestShapes.value,
                ) {
                    CompositionLocalProvider(
                        LocalContentColor provides latestContentColor.value,
                    ) {
                        val glass = latestLiquidGlass.value
                        val host = latestFitSheet.value
                        var sheetPx by remember { mutableIntStateOf(0) }
                        var contentPx by remember { mutableIntStateOf(0) }
                        val topPad = if (latestLiquidGlass.value) NuvioTokens.Space.s6 else NuvioTokens.Space.s20
                        val scale = LocalDensity.current.density.coerceAtLeast(1f)
                        val topPx = with(LocalDensity.current) { topPad.toPx() }
                        SideEffect {
                            val active = host ?: return@SideEffect
                            if (sheetPx > 0 && contentPx > 0) {
                                active.onMeasured(
                                    sheetPoints = sheetPx / scale,
                                    contentPoints = (contentPx + topPx) / scale,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (host != null) {
                                        Modifier.onSizeChanged { sheetPx = it.height }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            if (glass && !iosAtLeast(26, 1)) {
                                LegacySheetBlur(Modifier.fillMaxSize())
                            }
                            Column(
                                modifier = latestModifier.value
                                    .fillMaxSize()
                                    .background(if (glass) FlagSheetFrostTint else latestContainerColor.value)
                                    .padding(top = topPad),
                            ) {
                                if (host != null) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onSizeChanged { contentPx = it.height },
                                    ) {
                                        latestContent.value(this)
                                    }
                                } else {
                                    latestContent.value(this)
                                }
                            }
                        }
                    }
                }
            }
        }
        val sheetController = contentController
        sheetController.modalPresentationStyle = UIModalPresentationPageSheet
        if (liquidGlass) {
            sheetController.view.backgroundColor = UIColor.clearColor
            sheetController.view.opaque = false
        } else {
            sheetController.view.backgroundColor = latestContainerColor.value.toUIColor()
        }
        val containerBounds = parentViewController.view.bounds
        val containerWidth = containerBounds.useContents { size.width }
        val containerHeight = containerBounds.useContents { size.height }
        if (liquidGlass) {
            val cardWidth = flagSheetCardWidth(containerWidth)
            val cardHeight = flagSheetDetentHeight(containerHeight)
            if (cardWidth > 0.0 && cardHeight > 0.0) {
                sheetController.setPreferredContentSize(CGSizeMake(cardWidth, cardHeight))
            }
        }
        (sheetController.presentationController() as? UISheetPresentationController)?.apply {
            if (liquidGlass) {
                val detent = UISheetPresentationControllerDetent.customDetentWithIdentifier(
                    identifier = FLAG_SHEET_DETENT_ID,
                    resolver = { context ->
                        flagSheetDetentHeight(context?.maximumDetentValue ?: 0.0)
                    },
                )
                if (iosAtLeast(26, 1)) {
                    detent.backgroundEffect = UIBlurEffect.effectWithStyle(
                        UIBlurEffectStyle.UIBlurEffectStyleSystemUltraThinMaterialDark,
                    )
                }
                detents = listOf(detent)
                selectedDetentIdentifier = FLAG_SHEET_DETENT_ID
                // Compact height (landscape iPhone) otherwise promotes the sheet to a full-screen cover.
                prefersEdgeAttachedInCompactHeight = true
                prefersPageSizing = false
                widthFollowsPreferredContentSizeWhenEdgeAttached = true
                preferredCornerRadius = 28.0
            } else if (fullHeight) {
                detents = listOf(UISheetPresentationControllerDetent.largeDetent())
            } else if (fitSheet != null) {
                val sheet = this
                val host = fitSheet
                val detent = UISheetPresentationControllerDetent.customDetentWithIdentifier(
                    identifier = FIT_SHEET_DETENT_ID,
                    resolver = { context ->
                        fitSheetDetentPoints(
                            requestedPoints = host.requestedPoints,
                            maximumPoints = context?.maximumDetentValue ?: 0.0,
                        )
                    },
                )
                detents = listOf(detent)
                selectedDetentIdentifier = FIT_SHEET_DETENT_ID
                host.invalidate = { sheet.invalidateDetents() }
            } else {
                detents = listOf(
                    UISheetPresentationControllerDetent.mediumDetent(),
                    UISheetPresentationControllerDetent.largeDetent(),
                )
                selectedDetentIdentifier = UISheetPresentationControllerDetentIdentifierMedium
            }
            prefersGrabberVisible = showDragHandle
            prefersScrollingExpandsWhenScrolledToEdge = false
        }

        val dismissalDelegate = NuvioNativeBottomSheetDelegate {
            if (activeNativeBottomSheet === sheetController) {
                activeNativeBottomSheet = null
                activeNativeBottomSheetDelegate = null
            }
            latestOnDismissRequest.value()
        }
        sheetController.presentationController()?.delegate = dismissalDelegate

        activeNativeBottomSheet?.dismissViewControllerAnimated(
            flag = false,
            completion = null,
        )
        activeNativeBottomSheet = sheetController
        activeNativeBottomSheetDelegate = dismissalDelegate
        parentViewController.presentViewController(
            viewControllerToPresent = sheetController,
            animated = true,
            completion = if (liquidGlass) {
                {
                    sheetController.view.backgroundColor = UIColor.clearColor
                    sheetController.view.opaque = false
                }
            } else {
                null
            },
        )

        onDispose {
            fitSheet?.invalidate = null
            if (activeNativeBottomSheet === sheetController) {
                activeNativeBottomSheet = null
                activeNativeBottomSheetDelegate = null
            }
            val animate = !sheetDismissSkipsSystemAnimation
            sheetDismissSkipsSystemAnimation = false
            if (sheetController.presentingViewController != null) {
                sheetController.dismissViewControllerAnimated(
                    flag = animate,
                    completion = null,
                )
            }
        }
    }
}

internal actual fun dismissNativeNuvioBottomSheet() {
    activeNativeBottomSheet?.dismissViewControllerAnimated(
        flag = true,
        completion = null,
    )
}

private var sheetDismissSkipsSystemAnimation = false

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun dismissPresentedNuvioBottomSheet(durationMs: Int) {
    val controller = activeNativeBottomSheet ?: return
    sheetDismissSkipsSystemAnimation = true
    if (durationMs <= 0) {
        controller.dismissViewControllerAnimated(flag = false, completion = null)
        return
    }
    val view = controller.view
    val travel = view.bounds.useContents { size.height }.takeIf { it > 1.0 } ?: 800.0
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            controller.dismissViewControllerAnimated(flag = false, completion = null)
        }
        UIView.animateWithDuration(
            duration = durationMs / 1000.0,
            animations = {
                view.transform = CGAffineTransformMakeTranslation(0.0, travel)
            },
            completion = { _ ->
                controller.dismissViewControllerAnimated(flag = false, completion = null)
                if (continuation.isActive) continuation.resume(Unit)
            },
        )
    }
}

private class NuvioNativeBottomSheetDelegate(
    private val onDismissRequest: () -> Unit,
) : NSObject(), UIAdaptivePresentationControllerDelegateProtocol {
    private var didNotifyDismissal = false

    override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
        notifyDismissed()
    }

    private fun notifyDismissed() {
        if (didNotifyDismissal) return
        didNotifyDismissal = true
        onDismissRequest()
    }
}

private val FlagSheetFrostTint = Color.Black.copy(alpha = 0.10f)

private fun iosAtLeast(major: Int, minor: Int = 0): Boolean {
    val parts = UIDevice.currentDevice.systemVersion.split('.')
    val systemMajor = parts.getOrNull(0)?.toIntOrNull() ?: return false
    val systemMinor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return systemMajor > major || (systemMajor == major && systemMinor >= minor)
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun LegacySheetBlur(modifier: Modifier) {
    UIKitView(
        factory = {
            UIVisualEffectView(
                effect = UIBlurEffect.effectWithStyle(UIBlurEffectStyle.UIBlurEffectStyleSystemUltraThinMaterialDark),
            ).apply {
                userInteractionEnabled = false
                backgroundColor = UIColor.clearColor
                opaque = false
            }
        },
        modifier = modifier,
        background = Color.Transparent,
        interactive = false,
        accessibilityEnabled = false,
    )
}

private fun Color.toUIColor(): UIColor = UIColor(
    red = red.toDouble(),
    green = green.toDouble(),
    blue = blue.toDouble(),
    alpha = alpha.toDouble(),
)
