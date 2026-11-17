package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.ArtworkBackdrop
import com.nuvio.app.core.ui.DETAIL_SOFT_EDGE_PEAK
import com.nuvio.app.core.ui.LocalVerticalBarPadding
import com.nuvio.app.core.ui.SoftTopEdge
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.SCROLL_EDGE_TAIL_DP
import com.nuvio.app.core.ui.ScrollEdgeKind
import com.nuvio.app.core.ui.atLeastIosHitTarget
import com.nuvio.app.core.ui.detailChromeTopInset
import com.nuvio.app.core.ui.scrollEdgeHeightDp
import com.nuvio.app.core.ui.scrollEdgeKind
import com.nuvio.app.core.ui.systemIncreaseContrastEnabled
import com.nuvio.app.core.ui.systemReduceMotionEnabled
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.player.playerReduceTransparencyEnabled
import com.nuvio.app.navigation.LocalNativeNavigationBarHidden
import com.nuvio.app.navigation.LocalUseNativeNavigation
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

internal const val DETAIL_HEADER_TAG = "detail-header"
internal const val DETAIL_HEADER_EDGE_TAG = "detail-header-edge"
internal const val DETAIL_HEADER_BACK_TAG = "detail-header-back"
internal const val DETAIL_HEADER_ACTION_TAG = "detail-header-action"
internal const val DETAIL_HEADER_TITLE_TAG = "detail-header-title"
internal const val DETAIL_HEADER_COVER_TAG = "detail-header-cover"
internal const val DETAIL_HEADER_ROW_TAG = "detail-header-row"
internal const val DETAIL_PLAY_TAG = "detail-play"

internal val DetailHeaderRowHeight = 56.dp

/**
 * Approach before the title meets the row. The cover is already solid on that line,
 * so the fade never leaves the title or the Play pill showing through the buttons.
 */
internal val DetailHeaderCoverFade = 16.dp

internal val DetailHeaderCoverAlphaKey = SemanticsPropertyKey<Float>("DetailHeaderCoverAlpha")
internal var SemanticsPropertyReceiver.detailHeaderCoverAlpha by DetailHeaderCoverAlphaKey

/**
 * Draw-phase alpha for the title that joins the top row.
 * Callers read this only inside [graphicsLayer], so the scroll across the
 * threshold does not insert a child or remeasure the page.
 */
internal fun detailHeaderTitleAlpha(progress: Float): Float = progress.coerceIn(0f, 1f)

/** Clamps a resolved cover progress for the draw phase. */
internal fun detailHeaderCoverAlpha(progress: Float): Float = progress.coerceIn(0f, 1f)

/**
 * How solid the pinned details header is.
 *
 * [scrollOffsetPx] is the page scroll. [protectedTopPx] is the unscrolled top of the
 * hero title or the Play pill, whichever is higher. [headerBottomPx] is the safe-area
 * top plus the back/+ row. Rest stays clear so the hero is full-bleed. The cover is
 * solid once that content meets the bottom of the row. [fadePx] finishes on that line.
 * Reduce Motion snaps there instead of fading.
 */
internal fun detailHeaderCoverProgress(
    scrollOffsetPx: Float,
    protectedTopPx: Float,
    headerBottomPx: Float,
    fadePx: Float,
    reduceMotion: Boolean,
): Float {
    if (scrollOffsetPx <= 0f) return 0f
    if (protectedTopPx.isNaN() || headerBottomPx <= 0f) return 0f
    val coverAt = (protectedTopPx - headerBottomPx).coerceAtLeast(0f)
    if (reduceMotion || fadePx <= 0f) {
        return if (scrollOffsetPx >= coverAt) 1f else 0f
    }
    val fadeStart = (coverAt - fadePx).coerceAtLeast(0f)
    if (scrollOffsetPx <= fadeStart) return 0f
    if (scrollOffsetPx >= coverAt) return 1f
    val span = coverAt - fadeStart
    if (span <= 0f) return 1f
    return ((scrollOffsetPx - fadeStart) / span).coerceIn(0f, 1f)
}

/** Unscrolled top of the first control that must not cross the header row. */
internal fun detailHeaderProtectedTopPx(
    headingRootTopPx: Float,
    playRootTopPx: Float,
    listOriginY: Float,
    scrollOffsetPx: Float,
): Float {
    val root = when {
        headingRootTopPx.isNaN() && playRootTopPx.isNaN() -> return Float.NaN
        headingRootTopPx.isNaN() -> playRootTopPx
        playRootTopPx.isNaN() -> headingRootTopPx
        else -> minOf(headingRootTopPx, playRootTopPx)
    }
    return root - listOriginY + scrollOffsetPx
}

internal enum class DetailHeaderOcclusionTarget { Heading, Play }

/**
 * Positions of the hero title and the Play pill, in the scrolling page.
 * The header reads these; the two controls report them.
 */
internal class DetailHeaderOcclusion(
    private val scrollOffsetPx: () -> Float,
    private val heroScrolledOff: () -> Boolean,
) {
    var listOriginY by mutableFloatStateOf(0f)
    var headingRootTopPx by mutableFloatStateOf(Float.NaN)
        private set
    var playRootTopPx by mutableFloatStateOf(Float.NaN)
        private set

    fun reportRootTop(target: DetailHeaderOcclusionTarget, rootTopPx: Float) {
        when (target) {
            DetailHeaderOcclusionTarget.Heading -> headingRootTopPx = rootTopPx
            DetailHeaderOcclusionTarget.Play -> playRootTopPx = rootTopPx
        }
    }

    fun scrolledOff(): Boolean = heroScrolledOff()

    fun scrollOffset(): Float = scrollOffsetPx()

    fun protectedTopPx(): Float = detailHeaderProtectedTopPx(
        headingRootTopPx = headingRootTopPx,
        playRootTopPx = playRootTopPx,
        listOriginY = listOriginY,
        scrollOffsetPx = scrollOffsetPx(),
    )
}

internal val LocalDetailHeaderOcclusion = compositionLocalOf<DetailHeaderOcclusion?> { null }

@Composable
internal fun Modifier.reportDetailHeaderListOrigin(): Modifier {
    val occlusion = LocalDetailHeaderOcclusion.current ?: return this
    return this.onGloballyPositioned { coordinates ->
        occlusion.listOriginY = coordinates.positionInRoot().y
    }
}

@Composable
internal fun Modifier.reportDetailHeaderOcclusion(target: DetailHeaderOcclusionTarget): Modifier {
    val occlusion = LocalDetailHeaderOcclusion.current ?: return this
    return this.onGloballyPositioned { coordinates ->
        occlusion.reportRootTop(target, coordinates.positionInRoot().y)
    }
}

/**
 * Cover progress for the details header. Clear at rest. Solid once the hero title
 * or the Play pill reaches the bottom of the status-bar-plus-row bar.
 */
@Composable
internal fun rememberDetailHeaderCoverProgress(
    reduceMotion: Boolean = systemReduceMotionEnabled(),
): State<Float> {
    val occlusion = LocalDetailHeaderOcclusion.current
    val density = LocalDensity.current
    val headerBottomPx = with(density) { detailFloatingHeaderHeight().toPx() }
    val fadePx = with(density) { DetailHeaderCoverFade.toPx() }
    return remember(occlusion, reduceMotion, headerBottomPx, fadePx) {
        derivedStateOf {
            val source = occlusion ?: return@derivedStateOf 0f
            if (source.scrolledOff()) return@derivedStateOf 1f
            detailHeaderCoverProgress(
                scrollOffsetPx = source.scrollOffset(),
                protectedTopPx = source.protectedTopPx(),
                headerBottomPx = headerBottomPx,
                fadePx = fadePx,
                reduceMotion = reduceMotion,
            )
        }
    }
}

@Composable
internal fun detailFloatingHeaderHeight(): Dp {
    return detailChromeTopInset() + DetailHeaderRowHeight
}

@Composable
fun DetailFloatingHeader(
    meta: MetaDetails,
    isSaved: Boolean,
    progress: State<Float>,
    backgroundColor: Color? = null,
    background: (@Composable () -> Unit)? = null,
    onBack: () -> Unit,
    onToggleSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showComposeBack = !LocalUseNativeNavigation.current || LocalNativeNavigationBarHidden.current
    val headerTopPadding = detailChromeTopInset()
    val edgeColor = backgroundColor ?: MaterialTheme.colorScheme.background
    val solidEdge = scrollEdgeKind(
        safeTopDp = headerTopPadding.value,
        suppressed = false,
        reduceTransparency = playerReduceTransparencyEnabled(),
        increaseContrast = systemIncreaseContrastEnabled(),
    ) == ScrollEdgeKind.Solid
    val rowBottom = headerTopPadding + DetailHeaderRowHeight
    val coverAlpha = detailHeaderCoverAlpha(progress.value)
    // At rest this wash is only the status inset, the same idea as Home. The full bar is the cover.
    val edgeHeight = scrollEdgeHeightDp(
        safeTopDp = headerTopPadding.value,
        contentTopDp = headerTopPadding.value + SCROLL_EDGE_TAIL_DP,
        solid = solidEdge,
    ).dp
    var logoLoadError by remember(meta.id, meta.logo) {
        mutableStateOf(false)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .semantics { detailHeaderCoverAlpha = coverAlpha }
            .testTag(DETAIL_HEADER_TAG),
    ) {
        val logoWidth = (maxWidth * 0.6f).coerceAtMost(240.dp)
        if (coverAlpha <= 0f) {
            SoftTopEdge(
                height = edgeHeight,
                peakAlpha = DETAIL_SOFT_EDGE_PEAK,
                solid = solidEdge,
                tag = DETAIL_HEADER_EDGE_TAG,
                color = edgeColor,
            )
        }
        if (coverAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowBottom)
                    .graphicsLayer { alpha = coverAlpha }
                    .background(edgeColor)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { change -> change.consume() }
                            }
                        }
                    }
                    .testTag(DETAIL_HEADER_COVER_TAG),
            )
        }
        if (background != null) {
            Box(modifier = Modifier.matchParentSize()) { background() }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(LocalVerticalBarPadding.current)
                .padding(top = headerTopPadding, start = 16.dp, end = 16.dp)
                .height(DetailHeaderRowHeight)
                .testTag(DETAIL_HEADER_ROW_TAG),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sideSlot = 44.dp.atLeastIosHitTarget()
            if (showComposeBack) {
                NuvioBackButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(sideSlot)
                        .testTag(DETAIL_HEADER_BACK_TAG),
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    buttonSize = sideSlot,
                    iconSize = 24.dp,
                )
            } else {
                Box(modifier = Modifier.size(sideSlot))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .graphicsLayer { alpha = detailHeaderTitleAlpha(progress.value) },
                contentAlignment = Alignment.Center,
            ) {
                if (!meta.logo.isNullOrBlank() && !logoLoadError) {
                    AsyncImage(
                        model = meta.logo,
                        contentDescription = stringResource(Res.string.detail_logo_content_description, meta.name),
                        modifier = Modifier
                            .width(logoWidth)
                            .widthIn(max = 240.dp)
                            .height(42.dp)
                            .testTag(DETAIL_HEADER_TITLE_TAG),
                        onError = { logoLoadError = true },
                    )
                } else {
                    Text(
                        text = meta.name,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(DETAIL_HEADER_TITLE_TAG),
                    )
                }
            }

            DetailFloatingHeaderAction(
                isSaved = isSaved,
                onClick = onToggleSaved,
            )
        }
    }
}

@Composable
internal fun CinematicHeaderBackdrop(
    image: ImageBitmap?,
    screenHeight: Dp,
    alignment: Alignment = Alignment.Center,
    scrim: Brush? = null,
) {
    val modifier = Modifier
        .wrapContentHeight(align = Alignment.Top, unbounded = true)
        .height(screenHeight)
    if (scrim != null) {
        ArtworkBackdrop(image = image, alignment = alignment, scrim = scrim, modifier = modifier)
    } else {
        ArtworkBackdrop(image = image, alignment = alignment, modifier = modifier)
    }
}

@Composable
private fun DetailFloatingHeaderAction(
    isSaved: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp.atLeastIosHitTarget())
            .testTag(DETAIL_HEADER_ACTION_TAG)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val contentDescription = if (isSaved) {
            stringResource(Res.string.hero_remove_from_library)
        } else {
            stringResource(Res.string.hero_add_to_library)
        }
        if (isSaved) {
            Icon(
                painter = painterResource(Res.drawable.sidebar_library),
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onBackground,
            )
        } else {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
