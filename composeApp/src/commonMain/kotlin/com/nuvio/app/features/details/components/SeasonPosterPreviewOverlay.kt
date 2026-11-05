package com.nuvio.app.features.details.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import com.nuvio.app.core.automation.AutomationTestMode
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.PosterZoomAnchor
import com.nuvio.app.core.ui.systemReduceMotionEnabled
import com.nuvio.app.features.details.SeasonPosterDismissDragDp
import com.nuvio.app.features.details.SeasonPosterOverlayAccessibilityId
import com.nuvio.app.features.details.fitSeasonPoster
import com.nuvio.app.features.details.seasonPosterCrossfadeMillis
import com.nuvio.app.features.details.seasonPosterDecodeSizePx
import com.nuvio.app.features.details.seasonPosterDiagnosticsEnabled
import com.nuvio.app.features.details.seasonPosterDismisses
import com.nuvio.app.features.details.seasonPosterFallbackUrl
import com.nuvio.app.features.details.seasonPosterCloseClipsToRow
import com.nuvio.app.features.details.seasonPosterCloseFrame
import com.nuvio.app.features.details.seasonPosterOverlayBlocksTouches
import com.nuvio.app.features.details.seasonPosterFullImageCacheKey
import com.nuvio.app.features.details.seasonPosterPreviewLogLine
import com.nuvio.app.features.details.seasonPosterPreviewMotion
import com.nuvio.app.features.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.details_season_poster
import nuvio.composeapp.generated.resources.details_specials_poster
import org.jetbrains.compose.resources.stringResource

internal data class SeasonPosterPreviewRequest(
    val season: Int,
    val imageUrl: String,
    val anchor: PosterZoomAnchor?,
    val settled: Boolean = false,
    val listImageUrl: String? = null,
)

internal val LocalSeasonPosterPreview =
    staticCompositionLocalOf<((SeasonPosterPreviewRequest) -> Unit)?> { null }

/** Live thumbnail rects, keyed by season number, plus the row that holds them. */
internal class SeasonPosterPlacement {
    val bounds: SnapshotStateMap<Int, Rect> = mutableStateMapOf()
    var rowViewport by mutableStateOf<Rect?>(null)
}

private val EmptySeasonPosterPlacement = SeasonPosterPlacement()

internal val LocalSeasonPosterPlacement =
    staticCompositionLocalOf { EmptySeasonPosterPlacement }

@Composable
internal fun seasonPosterContentDescription(season: Int): String =
    if (season <= 0) {
        stringResource(Res.string.details_specials_poster)
    } else {
        stringResource(Res.string.details_season_poster, season)
    }

@Composable
internal fun SeasonPosterPreviewOverlay(
    request: SeasonPosterPreviewRequest,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = systemReduceMotionEnabled()
    val motion = seasonPosterPreviewMotion(reduceMotion)
    val crossfadeMillis = seasonPosterCrossfadeMillis(reduceMotion)
    val posterDescription = seasonPosterContentDescription(request.season)
    val closeLabel = stringResource(Res.string.action_close)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val currentOnDismissed by rememberUpdatedState(onDismissed)
    val platformContext = LocalPlatformContext.current
    val listImageUrl = request.listImageUrl
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != request.imageUrl }
    var requestedUrl by remember(request.imageUrl) { mutableStateOf(request.imageUrl) }
    val hasListUnderlay = listImageUrl != null
    val fullAlpha = remember(requestedUrl, hasListUnderlay) { Animatable(if (hasListUnderlay) 0f else 1f) }
    var fullReady by remember(requestedUrl) { mutableStateOf(false) }
    val openedAt = remember(request.season, request.imageUrl) { kotlin.time.TimeSource.Monotonic.markNow() }
    var listShownMs by remember(request.season, request.imageUrl) { mutableStateOf(-1L) }
    var loggedLarge by remember(request.season, request.imageUrl) { mutableStateOf(false) }
    val listRequest = remember(platformContext, listImageUrl) {
        listImageUrl?.let { url ->
            ImageRequest.Builder(platformContext)
                .data(url)
                .memoryCacheKey(url)
                // Smaller than the rail decode so Coil's memory cache can return that
                // bitmap (INEXACT allows a larger cached image). EXACT would miss it
                // and decode a new thumbnail. READ_ONLY keeps the tiny size out of the cache.
                .size(32, 48)
                .precision(Precision.INEXACT)
                .memoryCachePolicy(CachePolicy.READ_ONLY)
                .diskCachePolicy(CachePolicy.READ_ONLY)
                .crossfade(false)
                .build()
        }
    }
    val open = remember(request.settled) { Animatable(if (request.settled) 1f else 0f) }
    val closing = remember { mutableStateOf(false) }
    var dragPx by remember { mutableFloatStateOf(0f) }
    var swipeDismiss by remember { mutableStateOf(false) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(fullReady, requestedUrl, crossfadeMillis, hasListUnderlay) {
        if (!fullReady || !hasListUnderlay) return@LaunchedEffect
        if (crossfadeMillis <= 0) {
            fullAlpha.snapTo(1f)
        } else if (fullAlpha.value < 1f) {
            fullAlpha.animateTo(
                1f,
                tween(durationMillis = crossfadeMillis, easing = NuvioTokens.Motion.standard),
            )
        }
    }

    LaunchedEffect(request.settled) {
        if (request.settled) return@LaunchedEffect
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (open.value < 1f) {
            open.animateTo(
                1f,
                tween(durationMillis = motion.durationMillis, easing = NuvioTokens.Motion.standard),
            )
        }
    }

    fun requestClose(fromSwipe: Boolean) {
        if (closing.value) return
        closing.value = true
        swipeDismiss = fromSwipe
        scope.launch {
            try {
                if (open.value > 0f) {
                    open.animateTo(
                        0f,
                        tween(durationMillis = motion.durationMillis, easing = NuvioTokens.Motion.standard),
                    )
                }
            } finally {
                currentOnDismissed()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { currentOnDismissed() }
    }

    PlatformBackHandler(enabled = true) { requestClose(fromSwipe = false) }

    val progress = open.value.coerceIn(0f, 1f)
    val blocksTouches = seasonPosterOverlayBlocksTouches(progress, closing.value)
    val dismissThreshold = with(density) { SeasonPosterDismissDragDp.dp.toPx() }
    Box(
        modifier = modifier
            .zIndex(1f)
            .fillMaxSize()
            .graphicsLayer { clip = false }
            .testTag(SeasonPosterOverlayAccessibilityId)
            .onGloballyPositioned { coordinates ->
                rootOrigin = coordinates.positionInRoot()
                rootSize = coordinates.size
            }
            .then(
                if (blocksTouches) {
                    Modifier.pointerInput(dismissThreshold) {
                        try {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var drag = 0f
                                var moved = false
                                var swiping = false
                                val slop = viewConfiguration.touchSlop
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null) {
                                        requestClose(fromSwipe = false)
                                        break
                                    }
                                    val travelY = change.position.y - down.position.y
                                    val travelX = change.position.x - down.position.x
                                    if (!change.pressed) {
                                        when {
                                            swiping -> {
                                                if (seasonPosterDismisses(drag, dismissThreshold)) {
                                                    requestClose(fromSwipe = true)
                                                } else {
                                                    dragPx = 0f
                                                }
                                            }
                                            !moved -> requestClose(fromSwipe = false)
                                        }
                                        break
                                    }
                                    if (!moved && (kotlin.math.abs(travelY) > slop || kotlin.math.abs(travelX) > slop)) {
                                        moved = true
                                        swiping = travelY > slop && travelY > kotlin.math.abs(travelX)
                                    }
                                    if (swiping) {
                                        drag = travelY.coerceAtLeast(0f)
                                        dragPx = drag
                                        change.consume()
                                    }
                                }
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            requestClose(fromSwipe = false)
                            throw cancelled
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f * progress))
                .semantics {
                    role = Role.Button
                    contentDescription = closeLabel
                    onClick {
                        requestClose(fromSwipe = false)
                        true
                    }
                },
        )
        val insetLeft = WindowInsets.safeDrawing.getLeft(density, layoutDirection).toFloat()
        val insetTop = WindowInsets.safeDrawing.getTop(density).toFloat()
        val insetRight = WindowInsets.safeDrawing.getRight(density, layoutDirection).toFloat()
        val insetBottom = WindowInsets.safeDrawing.getBottom(density).toFloat()
        val containerWidth = (rootSize.width - insetLeft - insetRight).coerceAtLeast(0f)
        val containerHeight = (rootSize.height - insetTop - insetBottom).coerceAtLeast(0f)
        val fit = fitSeasonPoster(
            containerWidthPx = containerWidth,
            containerHeightPx = containerHeight,
            imageWidthPx = 0f,
            imageHeightPx = 0f,
        )
        val decode = seasonPosterDecodeSizePx(fit.widthPx.toInt(), fit.heightPx.toInt())
        val imageRequest = remember(platformContext, requestedUrl, decode.widthPx, decode.heightPx) {
            if (decode.widthPx < 1f || decode.heightPx < 1f) {
                null
            } else {
                val widthPx = decode.widthPx.toInt()
                val heightPx = decode.heightPx.toInt()
                val cacheKey = seasonPosterFullImageCacheKey(requestedUrl, widthPx, heightPx)
                ImageRequest.Builder(platformContext)
                    .data(requestedUrl)
                    .size(widthPx, heightPx)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .precision(Precision.EXACT)
                    .crossfade(false)
                    .build()
            }
        }
        if (fit.widthPx > 1f && fit.heightPx > 1f) {
            val endLeft = insetLeft + (containerWidth - fit.widthPx) / 2f
            val endTop = insetTop + (containerHeight - fit.heightPx) / 2f
            val placement = LocalSeasonPosterPlacement.current
            val closingNow = closing.value
            val closeFrame = seasonPosterCloseFrame(
                progress = progress,
                liveBounds = placement.bounds[request.season],
                rowViewport = placement.rowViewport,
                expandedLeft = rootOrigin.x + endLeft,
                expandedTop = rootOrigin.y + endTop,
                expandedWidth = fit.widthPx,
                expandedHeight = fit.heightPx,
                reduceMotion = !motion.zoomsFromAnchor,
                swiping = swipeDismiss,
            )
            val zoom = closeFrame.zooms
            val clipToRow = seasonPosterCloseClipsToRow(closingNow, zoom)
            val rowViewport = placement.rowViewport
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (clipToRow && rowViewport != null) {
                            Modifier.drawWithContent {
                                clipRect(
                                    left = rowViewport.left - rootOrigin.x,
                                    top = rowViewport.top - rootOrigin.y,
                                    right = rowViewport.right - rootOrigin.x,
                                    bottom = rowViewport.bottom - rootOrigin.y,
                                ) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(
                        width = with(density) { fit.widthPx.toDp() },
                        height = with(density) { fit.heightPx.toDp() },
                    )
                    .graphicsLayer {
                        val fraction = progress
                        if (zoom) {
                            scaleX = closeFrame.width / fit.widthPx
                            scaleY = closeFrame.height / fit.heightPx
                            transformOrigin = TransformOrigin(0f, 0f)
                            translationX = closeFrame.left - rootOrigin.x
                            translationY = closeFrame.top - rootOrigin.y + dragPx
                            alpha = 1f
                            clip = false
                        } else {
                            translationX = endLeft
                            translationY = endTop + dragPx
                            val scale = if (motion.zoomsFromAnchor && !request.settled && !swipeDismiss) {
                                lerp(0.92f, 1f, fraction)
                            } else {
                                1f
                            }
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin.Center
                            alpha = fraction
                            clip = false
                        }
                    },
            ) {
                if (listRequest != null) {
                    AsyncImage(
                        model = listRequest,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.High,
                        onSuccess = {
                            if (listShownMs < 0L) {
                                listShownMs = openedAt.elapsedNow().inWholeMilliseconds
                            }
                        },
                        onError = { state ->
                            logSeasonPosterOpen(
                                url = listImageUrl,
                                bitmapWidthPx = 0,
                                bitmapHeightPx = 0,
                                listShownMs = listShownMs,
                                largeShownMs = -1L,
                                errorClass = state.result.throwable::class.simpleName,
                            )
                        },
                    )
                }
                if (imageRequest != null) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = posterDescription,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(fullAlpha.value),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.High,
                        onSuccess = { state ->
                            val size = state.painter.intrinsicSize
                            val bitmapWidth = if (size.width.isFinite()) size.width.toInt() else 0
                            val bitmapHeight = if (size.height.isFinite()) size.height.toInt() else 0
                            fullReady = true
                            if (!loggedLarge) {
                                loggedLarge = true
                                logSeasonPosterOpen(
                                    url = requestedUrl,
                                    bitmapWidthPx = bitmapWidth,
                                    bitmapHeightPx = bitmapHeight,
                                    listShownMs = listShownMs,
                                    largeShownMs = openedAt.elapsedNow().inWholeMilliseconds,
                                )
                            }
                        },
                        onError = { state ->
                            logSeasonPosterOpen(
                                url = requestedUrl,
                                bitmapWidthPx = 0,
                                bitmapHeightPx = 0,
                                listShownMs = listShownMs,
                                largeShownMs = openedAt.elapsedNow().inWholeMilliseconds,
                                errorClass = state.result.throwable::class.simpleName,
                            )
                            val next = seasonPosterFallbackUrl(requestedUrl)
                            if (next != null && next != requestedUrl) requestedUrl = next
                        },
                    )
                }
            }
            }
        }
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

private fun logSeasonPosterOpen(
    url: String?,
    bitmapWidthPx: Int,
    bitmapHeightPx: Int,
    listShownMs: Long,
    largeShownMs: Long,
    errorClass: String? = null,
) {
    if (!seasonPosterDiagnosticsEnabled(
            automationActive = AutomationTestMode.isActive,
            diagnosticsEnabled = DiagnosticsLogger.isEnabled(),
        )
    ) {
        return
    }
    val line = seasonPosterPreviewLogLine(
        url = url,
        bitmapWidthPx = bitmapWidthPx,
        bitmapHeightPx = bitmapHeightPx,
        listShownMs = listShownMs,
        largeShownMs = largeShownMs,
        errorClass = errorClass,
    )
    InAppLogger.info("SeasonPoster", line)
    DiagnosticsLogger.logPerformance(line)
}
