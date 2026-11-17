package com.nuvio.app.features.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import com.nuvio.app.nativebridge.NuvioSeasonOverviewApply
import com.nuvio.app.nativebridge.NuvioSeasonOverviewBind
import com.nuvio.app.nativebridge.NuvioSeasonOverviewRootView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import platform.UIKit.UIView

internal actual val seasonOverviewNativeList: Boolean = true

/**
 * The system sheet already on screen hosts this view.
 * Kotlin pushes a read-only payload. Swift calls the same actions back.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
internal actual fun SeasonOverviewNativeListHost(
    payload: String,
    onOpen: (String) -> Unit,
    onWatchPrivately: (String) -> Unit,
    onInclude: (String) -> Unit,
    onSelecting: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    SideEffect {
        SeasonOverviewNativeActions.onOpen = onOpen
        SeasonOverviewNativeActions.onWatch = onWatchPrivately
        SeasonOverviewNativeActions.onInclude = onInclude
        SeasonOverviewNativeActions.onSelecting = onSelecting
        SeasonOverviewNativeActions.onDownload = onDownload
        SeasonOverviewNativeActions.onCancel = onCancel
        NuvioSeasonOverviewBind(
            seasonOverviewOnOpen,
            seasonOverviewOnWatch,
            seasonOverviewOnInclude,
            seasonOverviewOnSelecting,
            seasonOverviewOnDownload,
            seasonOverviewOnCancel,
        )
        NuvioSeasonOverviewApply(payload)
    }
    DisposableEffect(Unit) {
        onDispose { SeasonOverviewNativeActions.clear() }
    }
    UIKitView(
        factory = { NuvioSeasonOverviewRootView() ?: UIView() },
        modifier = modifier,
        background = Color.Transparent,
        interactive = true,
        accessibilityEnabled = true,
    )
}

internal object SeasonOverviewNativeActions {
    var onOpen: (String) -> Unit = {}
    var onWatch: (String) -> Unit = {}
    var onInclude: (String) -> Unit = {}
    var onSelecting: (Boolean) -> Unit = {}
    var onDownload: () -> Unit = {}
    var onCancel: () -> Unit = {}

    fun clear() {
        onOpen = {}
        onWatch = {}
        onInclude = {}
        onSelecting = {}
        onDownload = {}
        onCancel = {}
    }
}

@OptIn(ExperimentalForeignApi::class)
private val seasonOverviewOnOpen = staticCFunction<CPointer<ByteVar>?, Unit> { ptr ->
    SeasonOverviewNativeActions.onOpen(ptr?.toKString().orEmpty())
}

@OptIn(ExperimentalForeignApi::class)
private val seasonOverviewOnWatch = staticCFunction<CPointer<ByteVar>?, Unit> { ptr ->
    SeasonOverviewNativeActions.onWatch(ptr?.toKString().orEmpty())
}

@OptIn(ExperimentalForeignApi::class)
private val seasonOverviewOnInclude = staticCFunction<CPointer<ByteVar>?, Unit> { ptr ->
    SeasonOverviewNativeActions.onInclude(ptr?.toKString().orEmpty())
}

@OptIn(ExperimentalForeignApi::class)
private val seasonOverviewOnSelecting = staticCFunction<Int, Unit> { flag ->
    SeasonOverviewNativeActions.onSelecting(flag != 0)
}

@OptIn(ExperimentalForeignApi::class)
private val seasonOverviewOnDownload = staticCFunction<Unit> {
    SeasonOverviewNativeActions.onDownload()
}

@OptIn(ExperimentalForeignApi::class)
private val seasonOverviewOnCancel = staticCFunction<Unit> {
    SeasonOverviewNativeActions.onCancel()
}
