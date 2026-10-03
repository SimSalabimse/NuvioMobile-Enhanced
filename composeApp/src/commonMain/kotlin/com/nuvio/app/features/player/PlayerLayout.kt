package com.nuvio.app.features.player

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_resize_fill
import nuvio.composeapp.generated.resources.compose_player_resize_fit
import nuvio.composeapp.generated.resources.compose_player_resize_zoom
import org.jetbrains.compose.resources.StringResource

internal enum class PlayerSizeClass {
    S,
    M,
    L,
    T,
}

internal val LocalPlayerSizeClass = staticCompositionLocalOf { PlayerSizeClass.T }

/** Off keeps the developer overlay. On selects the experimental overlay. */
internal enum class PlayerOverlayPath {
    Developer,
    Experimental,
}

internal fun playerOverlayPath(useExperimentalPlayerOverlay: Boolean): PlayerOverlayPath =
    if (useExperimentalPlayerOverlay) PlayerOverlayPath.Experimental else PlayerOverlayPath.Developer

internal val LocalExperimentalPlayerOverlay = staticCompositionLocalOf { false }

/**
 * Developer chrome does not use the shorter-edge classes. [PlayerSizeClass.T]
 * keeps the pre-experiment panels, headers, and transport on every width.
 * Numeric metrics still follow the width steps.
 */
internal fun playerSizeClassForOverlay(width: Dp, height: Dp, experimental: Boolean): PlayerSizeClass =
    if (experimental) playerSizeClass(width, height) else PlayerSizeClass.T

internal val PlayerTabletSidePanelWidth = 520.dp
internal val PlayerPhoneCenterControlGap = 16.dp
internal val PlayerPhoneSeekTarget = 44.dp

internal fun playerSizeClass(width: Dp, height: Dp): PlayerSizeClass {
    val shorter = if (width < height) width else height
    return when {
        shorter >= 500.dp || width >= 1024.dp -> PlayerSizeClass.T
        shorter >= 430.dp -> PlayerSizeClass.L
        shorter >= 380.dp -> PlayerSizeClass.M
        else -> PlayerSizeClass.S
    }
}

internal fun playerTrailingPanelWidth(screenWidth: Dp, leadingInset: Dp): Dp {
    val preferred = (screenWidth * 0.58f).coerceIn(300.dp, 400.dp)
    val maxLeavingVideo = (screenWidth - leadingInset - 48.dp).coerceAtLeast(0.dp)
    return preferred.coerceAtMost(maxLeavingVideo)
}

internal fun playerLiveCategoryMenuWidth(panelWidth: Dp): Dp = panelWidth

internal fun playerCenteredCardWidth(safeWidth: Dp): Dp =
    (safeWidth * 0.9f).coerceAtMost(400.dp).coerceAtLeast(0.dp)

internal fun playerCenteredCardMaxHeight(safeHeight: Dp): Dp =
    (safeHeight - 32.dp).coerceAtLeast(0.dp)

internal val PlayerTransportTopButtonSize = 48.dp
internal const val PlayerPanelHeaderGapDp = 12

internal fun transportTopRowWidth(includeSubmitIntro: Boolean): Dp =
    PlayerTransportTopButtonSize * (if (includeSubmitIntro) 3 else 2)

/**
 * Phone panel headers keep the title's full one-line width. Pills move to the
 * next line when they and a 12dp gap would not leave that width.
 */
internal fun phonePanelHeaderStacksActions(
    titleWidthPx: Int,
    actionsWidthPx: Int,
    maxWidthPx: Int,
    gapPx: Int,
): Boolean = actionsWidthPx > 0 && titleWidthPx + gapPx + actionsWidthPx > maxWidthPx

/** The full-title second line is experimental. Off keeps the single header row. */
internal fun phonePanelHeaderStacksForOverlay(
    titleWidthPx: Int,
    actionsWidthPx: Int,
    maxWidthPx: Int,
    gapPx: Int,
    experimental: Boolean,
): Boolean = experimental && phonePanelHeaderStacksActions(
    titleWidthPx = titleWidthPx,
    actionsWidthPx = actionsWidthPx,
    maxWidthPx = maxWidthPx,
    gapPx = gapPx,
)

internal data class PictureMargins(
    val leading: Dp,
    val trailing: Dp,
    val top: Dp,
    val bottom: Dp,
) {
    companion object {
        val None = PictureMargins(0.dp, 0.dp, 0.dp, 0.dp)
    }
}

internal enum class TransportEdge {
    OnPicture,
    TrailingBar,
    TopBar,
}

/**
 * Empty regions around a fitted picture. Fill, zoom, and an unknown size do
 * not invent a bar.
 */
internal fun pictureMargins(
    frameWidth: Dp,
    frameHeight: Dp,
    videoWidth: Int,
    videoHeight: Int,
    resizeMode: PlayerResizeMode,
): PictureMargins {
    if (
        resizeMode != PlayerResizeMode.Fit ||
        videoWidth <= 0 ||
        videoHeight <= 0 ||
        frameWidth <= 0.dp ||
        frameHeight <= 0.dp
    ) {
        return PictureMargins.None
    }
    val frameAspect = frameWidth.value / frameHeight.value
    val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
    val epsilon = 0.001f
    return when {
        frameAspect > videoAspect + epsilon -> {
            val pictureWidth = frameHeight * videoAspect
            val bar = (frameWidth - pictureWidth) / 2
            PictureMargins(leading = bar, trailing = bar, top = 0.dp, bottom = 0.dp)
        }
        videoAspect > frameAspect + epsilon -> {
            val pictureHeight = frameWidth / videoAspect
            val bar = (frameHeight - pictureHeight) / 2
            PictureMargins(leading = 0.dp, trailing = 0.dp, top = bar, bottom = bar)
        }
        else -> PictureMargins.None
    }
}

/**
 * Flag, lock, and close move into a black bar only when the whole row fits
 * there inside the inset already applied around the frame. A full-frame
 * picture keeps them on the picture.
 */
internal fun flagLockCloseEdge(
    margins: PictureMargins,
    rowWidth: Dp,
    rowHeight: Dp,
    topInset: Dp,
    trailingInset: Dp,
): TransportEdge {
    val trailingRoom = margins.trailing - trailingInset
    if (margins.trailing > 0.dp && rowWidth > 0.dp && trailingRoom >= rowWidth) {
        return TransportEdge.TrailingBar
    }
    val topRoom = margins.top - topInset
    if (margins.top > 0.dp && rowHeight > 0.dp && topRoom >= rowHeight) {
        return TransportEdge.TopBar
    }
    return TransportEdge.OnPicture
}

/** Flag, lock, and close stay on the picture unless the experimental overlay is on. */
internal fun flagLockCloseEdgeForOverlay(
    margins: PictureMargins,
    rowWidth: Dp,
    rowHeight: Dp,
    topInset: Dp,
    trailingInset: Dp,
    experimental: Boolean,
): TransportEdge = if (experimental) {
    flagLockCloseEdge(margins, rowWidth, rowHeight, topInset, trailingInset)
} else {
    TransportEdge.OnPicture
}

/** Timeline and the bottom action row stay on the picture when the only empty region is a side bar. */
internal fun bottomRowInBottomBar(
    margins: PictureMargins,
    rowHeight: Dp,
    bottomInset: Dp,
): Boolean = margins.bottom > 0.dp && rowHeight > 0.dp && margins.bottom - bottomInset >= rowHeight

internal fun bottomRowInBottomBarForOverlay(
    margins: PictureMargins,
    rowHeight: Dp,
    bottomInset: Dp,
    experimental: Boolean,
): Boolean = experimental && bottomRowInBottomBar(margins, rowHeight, bottomInset)

internal fun PlayerLayoutMetrics.usesPhoneChrome(): Boolean = sizeClass != PlayerSizeClass.T

internal fun PlayerLayoutMetrics.transportSeekGap(): Dp =
    if (usesPhoneChrome()) PlayerPhoneCenterControlGap else centerGap

internal fun PlayerLayoutMetrics.showsTransportSeekButtons(): Boolean = sizeClass != PlayerSizeClass.S

@Composable
internal fun playerPanelInnerPadding(): Dp =
    if (LocalPlayerSizeClass.current == PlayerSizeClass.T) 24.dp else 0.dp

internal data class PlayerLayoutMetrics(
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val titleSize: TextUnit,
    val episodeInfoSize: TextUnit,
    val metadataSize: TextUnit,
    val centerGap: Dp,
    val centerLift: Dp,
    val sliderBottomOffset: Dp,
    val sliderTouchHeight: Dp,
    val sliderScaleY: Float,
    val timeSize: TextUnit,
    val headerIconSize: Dp,
    val sideButtonPadding: Dp,
    val sideIconSize: Dp,
    val playButtonPadding: Dp,
    val playIconSize: Dp,
    val sizeClass: PlayerSizeClass = PlayerSizeClass.S,
) {
    companion object {
        fun fromWidth(width: Dp): PlayerLayoutMetrics = fromSize(width, width)

        /**
         * Off uses the width steps from the developer overlay and reports
         * [PlayerSizeClass.T] so shorter-edge phone chrome stays off.
         * On uses the shorter-edge classes.
         */
        fun forOverlay(width: Dp, height: Dp, experimental: Boolean): PlayerLayoutMetrics {
            if (!experimental) {
                return metricsForTabletWidth(width).copy(sizeClass = PlayerSizeClass.T)
            }
            return fromSize(width, height)
        }

        fun fromSize(width: Dp, height: Dp): PlayerLayoutMetrics {
            val sizeClass = playerSizeClass(width, height)
            val metrics = if (sizeClass == PlayerSizeClass.T) metricsForTabletWidth(width) else phoneMetrics()
            return metrics.copy(sizeClass = sizeClass)
        }

        private fun phoneMetrics(): PlayerLayoutMetrics = PlayerLayoutMetrics(
            horizontalPadding = 20.dp,
            verticalPadding = 16.dp,
            titleSize = 18.dp.value.sp,
            episodeInfoSize = 14.dp.value.sp,
            metadataSize = 12.dp.value.sp,
            centerGap = 56.dp,
            centerLift = 10.dp,
            sliderBottomOffset = 16.dp,
            sliderTouchHeight = 22.dp,
            sliderScaleY = 0.82f,
            timeSize = 12.dp.value.sp,
            headerIconSize = 20.dp,
            sideButtonPadding = 10.dp,
            sideIconSize = 26.dp,
            playButtonPadding = 13.dp,
            playIconSize = 34.dp,
        )

        private fun metricsForTabletWidth(width: Dp): PlayerLayoutMetrics =
            when {
                width >= 1440.dp -> PlayerLayoutMetrics(
                    horizontalPadding = 28.dp,
                    verticalPadding = 24.dp,
                    titleSize = 28.dp.value.sp,
                    episodeInfoSize = 16.dp.value.sp,
                    metadataSize = 14.dp.value.sp,
                    centerGap = 112.dp,
                    centerLift = 24.dp,
                    sliderBottomOffset = 28.dp,
                    sliderTouchHeight = 28.dp,
                    sliderScaleY = 0.72f,
                    timeSize = 14.dp.value.sp,
                    headerIconSize = 24.dp,
                    sideButtonPadding = 14.dp,
                    sideIconSize = 34.dp,
                    playButtonPadding = 18.dp,
                    playIconSize = 44.dp,
                )
                width >= 1024.dp -> PlayerLayoutMetrics(
                    horizontalPadding = 24.dp,
                    verticalPadding = 20.dp,
                    titleSize = 24.dp.value.sp,
                    episodeInfoSize = 15.dp.value.sp,
                    metadataSize = 13.dp.value.sp,
                    centerGap = 88.dp,
                    centerLift = 18.dp,
                    sliderBottomOffset = 24.dp,
                    sliderTouchHeight = 26.dp,
                    sliderScaleY = 0.74f,
                    timeSize = 13.dp.value.sp,
                    headerIconSize = 22.dp,
                    sideButtonPadding = 13.dp,
                    sideIconSize = 32.dp,
                    playButtonPadding = 16.dp,
                    playIconSize = 42.dp,
                )
                width >= 768.dp -> PlayerLayoutMetrics(
                    horizontalPadding = 20.dp,
                    verticalPadding = 16.dp,
                    titleSize = 22.dp.value.sp,
                    episodeInfoSize = 14.dp.value.sp,
                    metadataSize = 12.dp.value.sp,
                    centerGap = 72.dp,
                    centerLift = 14.dp,
                    sliderBottomOffset = 20.dp,
                    sliderTouchHeight = 24.dp,
                    sliderScaleY = 0.78f,
                    timeSize = 12.dp.value.sp,
                    headerIconSize = 20.dp,
                    sideButtonPadding = 12.dp,
                    sideIconSize = 30.dp,
                    playButtonPadding = 15.dp,
                    playIconSize = 38.dp,
                )
                else -> phoneMetrics()
            }
    }
}

@Composable
internal fun playerPanelSafeInsets(): WindowInsets {
    val safe = WindowInsets.safeContent.only(
        WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End,
    )
    val keyboard = WindowInsets.ime.only(WindowInsetsSides.Bottom)
    return safe.union(keyboard)
}

@Composable
internal fun playerHorizontalSafePadding(): Dp {
    val layoutDirection = LocalLayoutDirection.current
    val safePadding = WindowInsets.safeContent.asPaddingValues()
    val left = safePadding.calculateLeftPadding(layoutDirection)
    val right = safePadding.calculateRightPadding(layoutDirection)
    return if (left > right) left else right
}

@Composable
internal fun playerTimelineBottomInsets(metrics: PlayerLayoutMetrics): WindowInsets {
    val safeInsets = WindowInsets.safeContent.only(WindowInsetsSides.Bottom)
    val contentInsets = WindowInsets(bottom = metrics.sliderBottomOffset / 2)
    return if (isIos) safeInsets.union(contentInsets) else safeInsets.add(contentInsets)
}

internal fun PlayerResizeMode.next(): PlayerResizeMode =
    when (this) {
        PlayerResizeMode.Fit -> PlayerResizeMode.Fill
        PlayerResizeMode.Fill -> PlayerResizeMode.Zoom
        PlayerResizeMode.Zoom -> PlayerResizeMode.Fit
    }

internal val PlayerResizeMode.labelRes: StringResource
    get() = when (this) {
        PlayerResizeMode.Fit -> Res.string.compose_player_resize_fit
        PlayerResizeMode.Fill -> Res.string.compose_player_resize_fill
        PlayerResizeMode.Zoom -> Res.string.compose_player_resize_zoom
    }

internal fun formatPlaybackTime(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "${hours}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

internal fun formatPlaybackRuntime(positionMs: Long, durationMs: Long, showRemainingTime: Boolean): String =
    if (showRemainingTime) {
        val remainingMs = (durationMs.coerceAtLeast(0L) - positionMs.coerceAtLeast(0L)).coerceAtLeast(0L)
        "−${formatPlaybackTime(remainingMs)}"
    } else {
        "${formatPlaybackTime(positionMs)} / ${formatPlaybackTime(durationMs)}"
    }

internal fun formatPlaybackSpeedLabel(speed: Float): String {
    val normalized = speed.toString().trimEnd('0').trimEnd('.')
    return "${normalized}x"
}
