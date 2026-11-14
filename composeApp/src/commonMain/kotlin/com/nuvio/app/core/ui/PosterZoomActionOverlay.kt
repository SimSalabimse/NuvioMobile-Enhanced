package com.nuvio.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import coil3.compose.AsyncImage
import com.kyant.backdrop.Backdrop
import com.nuvio.app.core.ui.glass.liquidGlassBlur
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Geometry of the shelf card that was long-pressed, captured in root coordinates
 * right before the long-press callback fires. Used as the start frame of the
 * shared-element zoom in [NuvioPosterZoomActionOverlay].
 */
data class PosterZoomAnchor(
    val boundsInRoot: Rect,
    val imageUrl: String?,
    val cornerRadius: Dp,
) {
    internal var source: PosterLiftSource? = null
}

/**
 * Hand-off slot between the pressed card and the overlay host. The card stashes
 * its anchor synchronously inside the long-press callback; the host consumes it
 * in the same call stack, so the value can never go stale.
 */
object PosterZoomAnchorHolder {
    private var pending: PosterZoomAnchor? = null

    fun stash(anchor: PosterZoomAnchor) {
        pending = anchor
    }

    fun consume(): PosterZoomAnchor? = pending.also { pending = null }
}

enum class PosterZoomOverlayExitAnimation {
    COLLAPSE,
    DISINTEGRATE,
}

class PosterZoomOverlayAction(
    val icon: ImageVector,
    val label: String,
    val isDestructive: Boolean = false,
    val exitAnimation: PosterZoomOverlayExitAnimation = if (isDestructive) {
        PosterZoomOverlayExitAnimation.DISINTEGRATE
    } else {
        PosterZoomOverlayExitAnimation.COLLAPSE
    },
    val enabled: Boolean = true,
    val onSelected: () -> Unit,
)

private enum class PosterZoomPhase {
    Open,
    Closing,
    Disintegrating,
}

private const val PosterZoomVisibilityThreshold = 0.0005f
internal const val POSTER_ZOOM_MENU_TAG = "poster-zoom-menu"

/** 0 while the menu is still at its opening scale, 1 when it is fully shown. */
internal val PosterZoomMenuProgressKey = SemanticsPropertyKey<Float>("PosterZoomMenuProgress")
internal var SemanticsPropertyReceiver.posterZoomMenuProgress by PosterZoomMenuProgressKey

/** The lifted episode card. Hidden when the menu needs the space. */
internal const val POSTER_ZOOM_PREVIEW_TAG = "poster-zoom-preview"

internal data class PosterZoomPreviewFit(
    val posterHeightDp: Float,
    val previewVisible: Boolean,
    val compactRows: Boolean,
)

/**
 * The preview and every menu row share the space between the window's top inset and the tab bar.
 * Heights are the measured title, gaps, and menu, not a guess. The preview shrinks, then hides.
 * Rows tighten only when the comfortable rows themselves do not fit.
 */
internal fun posterZoomPreviewFit(
    availableDp: Float,
    normalContentDp: Float,
    compactContentDp: Float,
    preferredPosterDp: Float,
    minimumPreviewDp: Float = 96f,
): PosterZoomPreviewFit {
    val available = availableDp.coerceAtLeast(0f)
    val normal = normalContentDp.coerceAtLeast(0f)
    val compact = compactContentDp.coerceAtLeast(0f)
    val preferred = preferredPosterDp.coerceAtLeast(0f)
    val minimum = minimumPreviewDp.coerceAtLeast(0f)
    if (preferred <= 0f) {
        return PosterZoomPreviewFit(
            posterHeightDp = 0f,
            previewVisible = false,
            compactRows = normal > available,
        )
    }
    val normalRoom = available - normal
    if (normalRoom >= preferred) {
        return PosterZoomPreviewFit(preferred, previewVisible = true, compactRows = false)
    }
    if (normalRoom >= minimum) {
        return PosterZoomPreviewFit(normalRoom, previewVisible = true, compactRows = false)
    }
    if (normal <= available) {
        return PosterZoomPreviewFit(0f, previewVisible = false, compactRows = false)
    }
    val compactRoom = available - compact
    if (compactRoom >= minimum) {
        return PosterZoomPreviewFit(
            posterHeightDp = minOf(preferred, compactRoom),
            previewVisible = true,
            compactRows = true,
        )
    }
    return PosterZoomPreviewFit(0f, previewVisible = false, compactRows = true)
}
private val PosterZoomExpandSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 340f,
    visibilityThreshold = PosterZoomVisibilityThreshold,
)
private val PosterZoomCollapseSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 460f,
    visibilityThreshold = PosterZoomVisibilityThreshold,
)
private val PosterZoomMenuSpring = spring<Float>(
    dampingRatio = 0.8f,
    stiffness = 420f,
    visibilityThreshold = Spring.DefaultDisplacementThreshold,
)

/**
 * Apple-style long-press preview: the pressed poster lifts off the shelf and
 * springs to the centre of the screen, the app content behind is blurred and
 * dimmed, and an action menu cascades in underneath. Destructive actions burn
 * the centred poster away with [DisintegratingContainer] instead of zooming back.
 */
@Composable
fun NuvioPosterZoomActionOverlay(
    imageUrl: String?,
    title: String,
    subtitle: String?,
    isWatched: Boolean = false,
    blurred: Boolean = false,
    depthSurface: NuvioCardDepthSurface = NuvioCardDepthSurface.Posters,
    anchor: PosterZoomAnchor?,
    actions: List<PosterZoomOverlayAction>,
    backdrop: Backdrop,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    bottomClearance: Dp? = null,
) {
    val tokens = MaterialTheme.nuvio
    val previewShape = RoundedCornerShape(PosterZoomFinalCornerRadius)
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val source = remember(anchor) { anchor?.source }
    val currentOnDismissed by rememberUpdatedState(onDismissed)

    // A context menu shows a snapshot of the moment it was invoked; don't let
    // repository updates mid-animation relabel or reorder the rows.
    val frozenActions = remember { actions }

    val zoom = remember { Animatable(0f) }
    val scrim = remember { Animatable(0f) }
    val menu = remember { Animatable(0f) }
    val shadowFade = remember { Animatable(1f) }
    var phase by remember { mutableStateOf(PosterZoomPhase.Open) }
    var destructiveAction by remember { mutableStateOf<PosterZoomOverlayAction?>(null) }
    var returnBounds by remember { mutableStateOf<Rect?>(null) }
    var lifted by remember { mutableStateOf(false) }
    var showFallback by remember { mutableStateOf(source == null) }
    val reduceMotion = systemReduceMotionEnabled()

    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var slotBounds by remember { mutableStateOf<Rect?>(null) }

    DisposableEffect(source) {
        onDispose { source?.land() }
    }

    fun close(afterClose: (() -> Unit)? = null) {
        if (phase != PosterZoomPhase.Open) return
        returnBounds = source?.bounds
        phase = PosterZoomPhase.Closing
        scope.launch {
            coroutineScope {
                launch { menu.animateTo(0f, posterZoomSpec(reduceMotion, tween(durationMillis = 140))) }
                launch {
                    delay(if (reduceMotion) 0 else 80)
                    scrim.animateTo(0f, posterZoomSpec(reduceMotion, tween(durationMillis = 260, easing = NuvioTokens.Motion.standard)))
                }
                launch { zoom.animateTo(0f, posterZoomSpec(reduceMotion, PosterZoomCollapseSpring)) }
            }
            currentOnDismissed()
            afterClose?.invoke()
        }
    }

    fun select(action: PosterZoomOverlayAction) {
        if (phase != PosterZoomPhase.Open) return
        if (action.exitAnimation == PosterZoomOverlayExitAnimation.DISINTEGRATE) {
            phase = PosterZoomPhase.Disintegrating
            destructiveAction = action
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch {
                launch { menu.animateTo(0f, tween(durationMillis = 160)) }
                launch { shadowFade.animateTo(0f, tween(durationMillis = 350)) }
            }
        } else {
            close(afterClose = action.onSelected)
        }
    }

    PlatformBackHandler(enabled = true) {
        close()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates -> rootOrigin = coordinates.positionInRoot() }
            .pointerInput(Unit) {
                detectTapGestures { close() }
            },
    ) {
        val anchorAspect = anchor
            ?.boundsInRoot
            ?.takeIf { it.height > 0f }
            ?.let { it.width / it.height }
            ?: 0.675f
        val aspect = anchorAspect.coerceIn(0.35f, 2.4f)
        val maxPosterWidth = if (aspect >= 1f) maxWidth * 0.8f else maxWidth * 0.6f
        val safeTop = windowSafeTopInset()
        val safeBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val tabClearance = bottomClearance ?: maxOf(
            floatingTabContentBottomInsetDp(nativeTabs = true, safeAreaBottomDp = safeBottom.value).dp,
            floatingTabContentBottomInsetDp(nativeTabs = false, safeAreaBottomDp = safeBottom.value).dp,
        )
        val preferredPoster = min(maxPosterWidth / aspect, maxHeight * 0.44f)
        val menuWidth = min(280.dp, maxWidth - NuvioTokens.Space.s48)
        // The sheet reports whether a preview slot was placed. Reading that during
        // composition is what starts the open animation; a side effect that only
        // peeks at the slot never recomposes, and the menu stays at 0.62 scale.
        var sheetReady by remember { mutableStateOf(false) }
        var previewShowing by remember { mutableStateOf(false) }
        val menuArmed = sheetReady && (!previewShowing || slotBounds != null)
        LaunchedEffect(menuArmed, phase) {
            if (!menuArmed || phase != PosterZoomPhase.Open) return@LaunchedEffect
            lifted = source?.lift() == true
            showFallback = !lifted
            launch { scrim.animateTo(1f, posterZoomSpec(reduceMotion, tween(durationMillis = 260, easing = NuvioTokens.Motion.standard))) }
            launch { zoom.animateTo(1f, posterZoomSpec(reduceMotion, PosterZoomExpandSpring)) }
            launch {
                delay(if (reduceMotion) 0 else 60)
                menu.animateTo(1f, posterZoomSpec(reduceMotion, PosterZoomMenuSpring))
            }
        }
        val density = LocalDensity.current
        val posterWidth = with(density) { slotBounds?.width?.toDp() ?: 0.dp }
        val posterHeight = with(density) { slotBounds?.height?.toDp() ?: 0.dp }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .liquidGlassBlur(
                    backdrop = backdrop,
                    radius = 36.dp,
                    alphaProvider = { scrim.value.coerceIn(0f, 1f) },
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Color.Black.copy(alpha = 0.45f * scrim.value.coerceIn(0f, 1f)),
                ),
        )

        PosterZoomSheet(
            title = title,
            subtitle = subtitle,
            actions = frozenActions,
            aspect = aspect,
            menuWidth = menuWidth,
            sheetWidth = max(menuWidth, maxPosterWidth),
            preferredPoster = preferredPoster,
            safeTop = safeTop,
            tabClearance = tabClearance,
            availableHeight = (maxHeight - safeTop - tabClearance).coerceAtLeast(0.dp),
            menuProgress = { menu.value },
            rowsEnabled = phase == PosterZoomPhase.Open,
            onSelect = ::select,
            onPreviewShowing = { showing ->
                sheetReady = true
                previewShowing = showing
            },
            onSlotBounds = { bounds -> slotBounds = bounds },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // The travelling poster itself, drawn above the slot column.
        Box(
            modifier = Modifier
                .align(AbsoluteAlignment.TopLeft)
                .size(width = posterWidth, height = posterHeight)
                .graphicsLayer {
                    val slot = slotBounds
                    if (slot == null || slot.width <= 0f) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    val progress = zoom.value
                    val clamped = progress.coerceIn(0f, 1f)
                    val start = returnBounds ?: posterStartBounds(anchor = anchor, slot = slot)
                    val scale = lerp(start.width / slot.width, 1f, progress)
                    scaleX = scale
                    scaleY = lerp(start.height / slot.height, 1f, progress)
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = lerp(start.left, slot.left, progress) - rootOrigin.x
                    translationY = lerp(start.top, slot.top, progress) - rootOrigin.y
                    alpha = when {
                        lifted -> 1f
                        !showFallback -> 0f
                        anchor == null -> clamped
                        else -> (clamped / 0.08f).coerceIn(0f, 1f)
                    }
                    shape = RoundedCornerShape(
                        if (lifted && anchor != null) {
                            anchor.cornerRadius.toPx() * slot.width / anchor.boundsInRoot.width
                        } else {
                            posterCornerRadiusPx(anchor, clamped, scale)
                        },
                    )
                    clip = false
                    shadowElevation = NuvioTokens.Space.s24.toPx() * clamped * shadowFade.value
                },
        ) {
            DisintegratingContainer(
                disintegrating = phase == PosterZoomPhase.Disintegrating,
                onDisintegrationStarted = {
                    destructiveAction?.onSelected?.invoke()
                    destructiveAction = null
                },
                onDisintegrated = {
                    scope.launch {
                        scrim.animateTo(0f, tween(durationMillis = 300, easing = NuvioTokens.Motion.standard))
                        currentOnDismissed()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!showFallback && source != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind { drawLiftedPoster(source) },
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val clamped = zoom.value.coerceIn(0f, 1f)
                                val slot = slotBounds
                                val scale = if (slot != null && slot.width > 0f) {
                                    posterScale(anchor = anchor, slot = slot, progress = zoom.value)
                                } else {
                                    1f
                                }
                                shape = RoundedCornerShape(posterCornerRadiusPx(anchor, clamped, scale))
                                clip = true
                            }
                            .background(tokens.colors.surfaceCard)
                            .nuvioCardDepth(
                                shape = previewShape,
                                surface = depthSurface,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (imageUrl != null) {
                            AsyncImage(
                                model = imageUrl,
                                contentDescription = title,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (blurred) Modifier.blur(NuvioTokens.Space.s18) else Modifier),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Text(
                                text = title,
                                modifier = Modifier.padding(NuvioTokens.Space.s14),
                                style = MaterialTheme.typography.titleMedium,
                                color = tokens.colors.textMuted,
                                textAlign = TextAlign.Center,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        NuvioPosterWatchedOverlay(
                            isWatched = isWatched,
                            modifier = Modifier.graphicsLayer {
                                val slot = slotBounds
                                if (slot != null && slot.width > 0f) {
                                    val scale = posterScale(
                                        anchor = anchor,
                                        slot = slot,
                                        progress = zoom.value,
                                    ).coerceAtLeast(0.001f)
                                    scaleX = 1f / scale
                                    scaleY = 1f / scale
                                    transformOrigin = TransformOrigin(1f, 0f)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterZoomSheet(
    title: String,
    subtitle: String?,
    actions: List<PosterZoomOverlayAction>,
    aspect: Float,
    menuWidth: Dp,
    sheetWidth: Dp,
    preferredPoster: Dp,
    safeTop: Dp,
    tabClearance: Dp,
    availableHeight: Dp,
    menuProgress: () -> Float,
    rowsEnabled: Boolean,
    onSelect: (PosterZoomOverlayAction) -> Unit,
    onPreviewShowing: (Boolean) -> Unit,
    onSlotBounds: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubcomposeLayout(
        modifier = modifier
            .padding(top = safeTop, bottom = tabClearance)
            .width(sheetWidth)
            .heightIn(max = availableHeight),
    ) { constraints ->
        val menuWidthPx = menuWidth.roundToPx().coerceAtMost(constraints.maxWidth)
        val gapTop = NuvioTokens.Space.s18.roundToPx()
        val gapBottom = NuvioTokens.Space.s14.roundToPx()
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = Constraints.Infinity)
        val menuLoose = Constraints(maxWidth = menuWidthPx, maxHeight = Constraints.Infinity)

        val titlePlaceable = subcompose("title") {
            PosterZoomTitleBlock(title = title, subtitle = subtitle, menuProgress = menuProgress)
        }.first().measure(loose)

        fun menuProbe(compact: Boolean, slot: String) = subcompose(slot) {
            Box(Modifier.clearAndSetSemantics {}) {
                PosterZoomMenuStack(
                    actions = actions,
                    compact = compact,
                    tagged = false,
                    scroll = false,
                    menuProgress = menuProgress,
                    rowsEnabled = false,
                    onSelect = { _ -> },
                )
            }
        }.first().measure(menuLoose)

        val normalProbe = menuProbe(compact = false, slot = "probe-normal")
        val compactProbe = menuProbe(compact = true, slot = "probe-compact")
        val fixedNormal = gapTop + titlePlaceable.height + gapBottom + normalProbe.height
        val fixedCompact = gapTop + titlePlaceable.height + gapBottom + compactProbe.height
        val availablePx = if (constraints.maxHeight == Constraints.Infinity) {
            availableHeight.roundToPx()
        } else {
            constraints.maxHeight
        }
        val fit = posterZoomPreviewFit(
            availableDp = availablePx.toDp().value,
            normalContentDp = fixedNormal.toDp().value,
            compactContentDp = fixedCompact.toDp().value,
            preferredPosterDp = preferredPoster.value,
        )
        val requestedPreview = if (fit.previewVisible) fit.posterHeightDp.dp.roundToPx() else 0
        val previewBudget = (availablePx - if (fit.compactRows) fixedCompact else fixedNormal).coerceAtLeast(0)
        val previewH = requestedPreview.coerceAtMost(previewBudget)
        val previewW = if (previewH > 0) {
            (previewH * aspect).roundToInt().coerceIn(1, constraints.maxWidth)
        } else {
            0
        }
        onPreviewShowing(previewH > 0)
        if (previewH == 0) onSlotBounds(null)
        val previewPlaceable = if (previewH > 0 && previewW > 0) {
            subcompose("preview") {
                Box(
                    modifier = Modifier
                        .size(width = previewW.toDp(), height = previewH.toDp())
                        .testTag(POSTER_ZOOM_PREVIEW_TAG)
                        .onGloballyPositioned { coordinates -> onSlotBounds(coordinates.boundsInRoot()) },
                )
            }.first().measure(Constraints.fixed(previewW, previewH))
        } else {
            null
        }
        val menuHeight = if (fit.compactRows) compactProbe.height else normalProbe.height
        val menuBudget = (availablePx - previewH - gapTop - titlePlaceable.height - gapBottom)
            .coerceAtLeast(0)
        val scroll = menuHeight > menuBudget
        val menuPlaceable = subcompose("menu") {
            PosterZoomMenuStack(
                actions = actions,
                compact = fit.compactRows,
                tagged = true,
                scroll = scroll,
                menuProgress = menuProgress,
                rowsEnabled = rowsEnabled,
                onSelect = onSelect,
            )
        }.first().measure(
            Constraints(
                maxWidth = menuWidthPx,
                maxHeight = if (scroll) menuBudget else Constraints.Infinity,
            ),
        )

        val contentHeight = previewH + gapTop + titlePlaceable.height + gapBottom + menuPlaceable.height
        val height = contentHeight.coerceAtMost(availablePx)
        val width = constraints.maxWidth
        layout(width, height) {
            var y = 0
            previewPlaceable?.let { preview ->
                preview.place((width - preview.width) / 2, y)
                y += previewH
            }
            y += gapTop
            titlePlaceable.place((width - titlePlaceable.width) / 2, y)
            y += titlePlaceable.height + gapBottom
            menuPlaceable.place((width - menuPlaceable.width) / 2, y)
        }
    }
}

@Composable
private fun PosterZoomTitleBlock(
    title: String,
    subtitle: String?,
    menuProgress: () -> Float,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val progress = menuProgress().coerceIn(0f, 1f)
                alpha = progress
                translationY = (1f - progress) * NuvioTokens.Space.s10.toPx()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s12),
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s4))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = NuvioTokens.Space.s12),
            )
        }
    }
}

@Composable
private fun PosterZoomMenuStack(
    actions: List<PosterZoomOverlayAction>,
    compact: Boolean,
    tagged: Boolean,
    scroll: Boolean,
    menuProgress: () -> Float,
    rowsEnabled: Boolean,
    onSelect: (PosterZoomOverlayAction) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val menuScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (tagged) {
                    Modifier
                        .testTag(POSTER_ZOOM_MENU_TAG)
                        .semantics { posterZoomMenuProgress = menuProgress() }
                } else {
                    Modifier
                },
            )
            .then(if (scroll) Modifier.verticalScroll(menuScroll) else Modifier)
            .graphicsLayer {
                val progress = menuProgress()
                val clamped = progress.coerceIn(0f, 1f)
                alpha = clamped
                val scale = 0.62f + 0.38f * progress
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
            .graphicsLayer {
                shape = RoundedCornerShape(NuvioTokens.Space.s20.toPx())
                clip = true
            }
            .background(tokens.colors.surfaceElevated),
    ) {
        actions.forEachIndexed { index, action ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(NuvioTokens.Space.hairline)
                        .background(tokens.colors.textPrimary.copy(alpha = 0.08f)),
                )
            }
            PosterZoomMenuRow(
                action = action,
                enabled = rowsEnabled && action.enabled,
                compact = compact,
                onSelected = { onSelect(action) },
                modifier = Modifier.graphicsLayer {
                    val stagger = index * 0.07f
                    val progress = ((menuProgress().coerceIn(0f, 1f) - stagger) / (1f - stagger))
                        .coerceIn(0f, 1f)
                    alpha = progress
                    translationY = (1f - progress) * NuvioTokens.Space.s8.toPx()
                },
            )
        }
    }
}

@Composable
private fun PosterZoomMenuRow(
    action: PosterZoomOverlayAction,
    enabled: Boolean,
    compact: Boolean,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val contentColor = if (action.isDestructive) tokens.colors.danger else tokens.colors.textPrimary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (action.enabled) 1f else 0.38f)
            .clickable(enabled = enabled, onClick = onSelected)
            .padding(
                horizontal = NuvioTokens.Space.s18,
                vertical = if (compact) NuvioTokens.Space.s8 else NuvioTokens.Space.s16,
            ),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = action.label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(NuvioTokens.Icon.md),
        )
    }
}

internal fun <T> posterZoomSpec(reduceMotion: Boolean, spec: AnimationSpec<T>): AnimationSpec<T> =
    if (reduceMotion) snap() else spec

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

private fun posterStartBounds(anchor: PosterZoomAnchor?, slot: Rect): Rect =
    anchor?.boundsInRoot ?: slot.scaleAboutCenter(0.85f)

private fun posterScale(anchor: PosterZoomAnchor?, slot: Rect, progress: Float): Float =
    lerp(posterStartBounds(anchor = anchor, slot = slot).width / slot.width, 1f, progress)

private fun Rect.scaleAboutCenter(factor: Float): Rect {
    val newWidth = width * factor
    val newHeight = height * factor
    return Rect(
        offset = Offset(center.x - newWidth / 2f, center.y - newHeight / 2f),
        size = androidx.compose.ui.geometry.Size(newWidth, newHeight),
    )
}

private fun androidx.compose.ui.unit.Density.posterCornerRadiusPx(
    anchor: PosterZoomAnchor?,
    clampedProgress: Float,
    scale: Float,
): Float {
    val finalRadius = PosterZoomFinalCornerRadius.toPx()
    val startRadius = anchor?.cornerRadius?.toPx() ?: finalRadius
    val apparent = lerp(startRadius, finalRadius, clampedProgress)
    return if (scale > 0f) apparent / scale else apparent
}

private val PosterZoomFinalCornerRadius = NuvioTokens.Space.s18
