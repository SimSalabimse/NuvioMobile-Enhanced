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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.PosterZoomAnchor
import com.nuvio.app.core.ui.systemReduceMotionEnabled
import com.nuvio.app.features.details.SeasonPosterDismissDragDp
import com.nuvio.app.features.details.SeasonPosterOverlayAccessibilityId
import com.nuvio.app.features.details.fitSeasonPoster
import com.nuvio.app.features.details.seasonPosterDismisses
import com.nuvio.app.features.details.seasonPosterPreviewMotion
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
)

internal val LocalSeasonPosterPreview =
    staticCompositionLocalOf<((SeasonPosterPreviewRequest) -> Unit)?> { null }

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
    val motion = seasonPosterPreviewMotion(systemReduceMotionEnabled())
    val posterDescription = seasonPosterContentDescription(request.season)
    val closeLabel = stringResource(Res.string.action_close)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val currentOnDismissed by rememberUpdatedState(onDismissed)
    val platformContext = LocalPlatformContext.current
    val imageRequest = remember(platformContext, request.imageUrl) {
        ImageRequest.Builder(platformContext)
            .data(request.imageUrl)
            .crossfade(false)
            .build()
    }
    val open = remember(request.settled) { Animatable(if (request.settled) 1f else 0f) }
    val closing = remember { mutableStateOf(false) }
    var dragPx by remember { mutableFloatStateOf(0f) }
    var swipeDismiss by remember { mutableStateOf(false) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var imageWidth by remember(request.imageUrl) { mutableFloatStateOf(0f) }
    var imageHeight by remember(request.imageUrl) { mutableFloatStateOf(0f) }

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
            if (open.value > 0f) {
                open.animateTo(
                    0f,
                    tween(durationMillis = motion.durationMillis, easing = NuvioTokens.Motion.standard),
                )
            }
            currentOnDismissed()
        }
    }

    PlatformBackHandler(enabled = true) { requestClose(fromSwipe = false) }

    val dismissThreshold = with(density) { SeasonPosterDismissDragDp.dp.toPx() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(SeasonPosterOverlayAccessibilityId)
            .onGloballyPositioned { coordinates ->
                rootOrigin = coordinates.positionInRoot()
                rootSize = coordinates.size
            }
            .pointerInput(dismissThreshold) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var drag = 0f
                    var moved = false
                    var swiping = false
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
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
            },
    ) {
        val progress = open.value.coerceIn(0f, 1f)
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
            imageWidthPx = imageWidth,
            imageHeightPx = imageHeight,
        )
        if (fit.widthPx > 1f && fit.heightPx > 1f) {
            val endLeft = insetLeft + (containerWidth - fit.widthPx) / 2f
            val endTop = insetTop + (containerHeight - fit.heightPx) / 2f
            val anchor = request.anchor?.boundsInRoot?.takeIf { it.width > 1f && it.height > 1f }
            val zoom = motion.zoomsFromAnchor && anchor != null && !swipeDismiss
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(
                        width = with(density) { fit.widthPx.toDp() },
                        height = with(density) { fit.heightPx.toDp() },
                    )
                    .graphicsLayer {
                        val fraction = progress
                        if (zoom && anchor != null) {
                            val scaleXFraction = anchor.width / fit.widthPx
                            scaleX = lerp(scaleXFraction, 1f, fraction)
                            scaleY = lerp(anchor.height / fit.heightPx, 1f, fraction)
                            transformOrigin = TransformOrigin(0f, 0f)
                            translationX = lerp(anchor.left - rootOrigin.x, endLeft, fraction)
                            translationY = lerp(anchor.top - rootOrigin.y, endTop, fraction) + dragPx
                            alpha = 1f
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
                        }
                    },
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = posterDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    onSuccess = { state ->
                        val size = state.painter.intrinsicSize
                        if (size.width.isFinite() && size.width > 1f) imageWidth = size.width
                        if (size.height.isFinite() && size.height > 1f) imageHeight = size.height
                    },
                )
            }
        }
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction
