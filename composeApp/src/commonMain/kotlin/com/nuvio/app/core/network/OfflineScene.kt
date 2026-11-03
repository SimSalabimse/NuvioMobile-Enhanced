package com.nuvio.app.core.network

import kotlin.math.PI
import kotlin.math.sin

/**
 * Leading back button. Matches the details overlay: 12dp from the edge, 8dp under
 * the status bar, 44dp hit target.
 */
const val OfflineBannerBackButtonStartDp = 12f
const val OfflineBannerBackButtonTopGapDp = 8f
const val OfflineBannerBackButtonSizeDp = 44f
const val OfflineBannerControlGapDp = 12f

/** Trailing overlay (hero mute). Kept clear of the label the same way. */
const val OfflineBannerTrailingControlEndDp = 18f
const val OfflineBannerTrailingControlSizeDp = 44f

data class OfflineDpRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    fun overlaps(other: OfflineDpRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

data class OfflinePhaseSample(
    val offline: Boolean,
    val progress: Float,
)

fun offlinePhaseSample(phase: OfflineBannerPhase): OfflinePhaseSample = when (phase) {
    OfflineBannerPhase.Online -> OfflinePhaseSample(offline = false, progress = 0f)
    OfflineBannerPhase.Offline -> OfflinePhaseSample(offline = true, progress = 1f)
    OfflineBannerPhase.GoingOffline -> OfflinePhaseSample(offline = true, progress = 0.45f)
    OfflineBannerPhase.ComingOnline,
    OfflineBannerPhase.Reconnecting,
    -> OfflinePhaseSample(offline = false, progress = 0.55f)
}

/**
 * Layout plus the animation sample for one offline bit.
 *
 * This is the fixture entry point. Pass [offline] true or false. [progress] is the
 * eased clock from [offlineTransitionMotion]: 0 is fully online, 1 is fully offline.
 * [OfflineBannerHost] calls this with `offline = isOfflineBannerCondition(condition)`
 * and the same clock, so a fixture that calls [offlineScene] takes the real path.
 */
data class OfflineScene(
    val offline: Boolean,
    val progress: Float,
    val phase: OfflineBannerPhase,
    val screen: OfflineBannerScreen,
    val statusInsetDp: Float,
    val slimRowDp: Float,
    val bannerHeightDp: Float,
    val bannerDrawn: Boolean,
    val bannerAlpha: Float,
    val bannerSlideDp: Float,
    val contentTopOffsetDp: Float,
    val contentAlpha: Float,
    val contentSlideDp: Float,
    val label: OfflineDpRect,
    val backButton: OfflineDpRect,
    val trailingControl: OfflineDpRect,
    val motion: OfflineTransitionMotion,
)

fun offlineScene(
    offline: Boolean,
    progress: Float,
    screen: OfflineBannerScreen,
    statusInsetDp: Float,
    reduceMotion: Boolean = false,
    fontScale: Float = 1f,
    viewportWidthDp: Float = 390f,
): OfflineScene {
    val motion = offlineTransitionMotion(reduceMotion)
    val raw = progress.coerceIn(0f, 1f)
    val visual = if (motion.snap) {
        if (offline) 1f else 0f
    } else {
        raw
    }
    val inset = statusInsetDp.coerceAtLeast(0f)
    val slim = offlineBannerStripHeightDp(fontScale)
    val slots = offlineBannerControlSlots(screen)
    val width = viewportWidthDp.coerceAtLeast(0f)
    val labelLeft = slots.leadingDp
    val labelRight = (width - slots.trailingDp).coerceAtLeast(labelLeft)
    val label = OfflineDpRect(
        left = labelLeft,
        top = inset,
        right = labelRight,
        bottom = inset + slim,
    )
    val backButton = OfflineDpRect(
        left = OfflineBannerBackButtonStartDp,
        top = inset + OfflineBannerBackButtonTopGapDp,
        right = OfflineBannerBackButtonStartDp + OfflineBannerBackButtonSizeDp,
        bottom = inset + OfflineBannerBackButtonTopGapDp + OfflineBannerBackButtonSizeDp,
    )
    val trailingLeft = (width - OfflineBannerTrailingControlEndDp - OfflineBannerTrailingControlSizeDp)
        .coerceAtLeast(0f)
    val trailingControl = OfflineDpRect(
        left = trailingLeft,
        top = inset + OfflineBannerBackButtonTopGapDp,
        right = trailingLeft + OfflineBannerTrailingControlSizeDp,
        bottom = inset + OfflineBannerBackButtonTopGapDp + OfflineBannerTrailingControlSizeDp,
    )
    val crest = if (visual <= 0f || visual >= 1f || motion.snap) {
        0f
    } else {
        sin(visual * PI.toFloat()).coerceAtLeast(0f)
    }
    val phase = when {
        offline && visual >= 1f -> OfflineBannerPhase.Offline
        !offline && visual <= 0f -> OfflineBannerPhase.Online
        offline -> OfflineBannerPhase.GoingOffline
        else -> OfflineBannerPhase.ComingOnline
    }
    return OfflineScene(
        offline = offline,
        progress = visual,
        phase = phase,
        screen = screen,
        statusInsetDp = inset,
        slimRowDp = slim,
        bannerHeightDp = inset + slim,
        bannerDrawn = offline || visual > 0f,
        bannerAlpha = visual,
        bannerSlideDp = (visual - 1f) * slim,
        contentTopOffsetDp = 0f,
        contentAlpha = 1f - motion.contentFade * crest,
        contentSlideDp = motion.contentSlideDp * crest,
        label = label,
        backButton = backButton,
        trailingControl = trailingControl,
        motion = motion,
    )
}

data class OfflineBannerControlSlots(
    val leadingDp: Float,
    val trailingDp: Float,
)

/** Both screens keep the label beside the back button and the trailing overlay. */
fun offlineBannerControlSlots(screen: OfflineBannerScreen): OfflineBannerControlSlots {
    val leading = OfflineBannerBackButtonStartDp + OfflineBannerBackButtonSizeDp + OfflineBannerControlGapDp
    val trailing = OfflineBannerTrailingControlEndDp + OfflineBannerTrailingControlSizeDp + OfflineBannerControlGapDp
    return when (screen) {
        OfflineBannerScreen.Home,
        OfflineBannerScreen.Details,
        -> OfflineBannerControlSlots(leadingDp = leading, trailingDp = trailing)
    }
}
