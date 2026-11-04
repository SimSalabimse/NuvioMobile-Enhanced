package com.nuvio.app.core.network

/**
 * Shared clock for the banner and the page. Both directions use it.
 * FastOutSlowIn is cubic-bezier(0.4, 0, 0.2, 1). Under 300ms.
 */
const val OfflineTransitionDurationMs = 250

/** No extra wait before the transition. A dead network stays offline until the probe succeeds. */
const val OfflineBannerHoldMs = 0L
const val OfflineBannerShowMs = OfflineTransitionDurationMs
const val OfflineBannerHideFadeMs = OfflineTransitionDurationMs

const val OfflineTransitionBezierX1 = 0.4f
const val OfflineTransitionBezierY1 = 0f
const val OfflineTransitionBezierX2 = 0.2f
const val OfflineTransitionBezierY2 = 1f

/** How far the page travels at the middle of the transition. Resting offset is 0. */
const val OfflineContentSlideDp = 16f

/** How much the page fades at the middle of the transition. Resting alpha is 1. */
const val OfflineContentFade = 0.28f

enum class OfflineTransitionEasing {
    FastOutSlowIn,
}

enum class OfflineTransitionDirection {
    GoingOffline,
    ComingOnline,
}

enum class OfflineMotionElement {
    BannerFade,
    BannerSlide,
    ContentFade,
    ContentSlide,
}

data class OfflineTransitionMotion(
    val durationMs: Int,
    val easing: OfflineTransitionEasing,
    val elements: Set<OfflineMotionElement>,
    val directions: Set<OfflineTransitionDirection>,
    val snap: Boolean,
    val contentSlideDp: Float,
    val contentFade: Float,
)

/**
 * One spec for the banner and the content, going offline and coming back online.
 * Reduce Motion snaps. [ConnectionWatchDebounceMs] is not part of this clock.
 */
fun offlineTransitionMotion(reduceMotion: Boolean): OfflineTransitionMotion {
    val elements = setOf(
        OfflineMotionElement.BannerFade,
        OfflineMotionElement.BannerSlide,
        OfflineMotionElement.ContentFade,
        OfflineMotionElement.ContentSlide,
    )
    val directions = setOf(
        OfflineTransitionDirection.GoingOffline,
        OfflineTransitionDirection.ComingOnline,
    )
    return if (reduceMotion) {
        OfflineTransitionMotion(
            durationMs = 0,
            easing = OfflineTransitionEasing.FastOutSlowIn,
            elements = elements,
            directions = directions,
            snap = true,
            contentSlideDp = OfflineContentSlideDp,
            contentFade = OfflineContentFade,
        )
    } else {
        OfflineTransitionMotion(
            durationMs = OfflineTransitionDurationMs,
            easing = OfflineTransitionEasing.FastOutSlowIn,
            elements = elements,
            directions = directions,
            snap = false,
            contentSlideDp = OfflineContentSlideDp,
            contentFade = OfflineContentFade,
        )
    }
}

/** Eased clock in 0..1. Endpoints stay 0 and 1. */
fun offlineTransitionEased(linear: Float, easing: OfflineTransitionEasing = OfflineTransitionEasing.FastOutSlowIn): Float {
    val t = linear.coerceIn(0f, 1f)
    return when (easing) {
        OfflineTransitionEasing.FastOutSlowIn -> cubicBezier(
            t,
            OfflineTransitionBezierX1,
            OfflineTransitionBezierY1,
            OfflineTransitionBezierX2,
            OfflineTransitionBezierY2,
        )
    }
}

private fun cubicBezier(x: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
    if (x <= 0f) return 0f
    if (x >= 1f) return 1f
    var u = x
    repeat(8) {
        val slope = bezierSlope(u, x1, x2)
        if (kotlin.math.abs(slope) < 1e-4f) return@repeat
        val current = bezierComponent(u, x1, x2) - x
        u = (u - current / slope).coerceIn(0f, 1f)
    }
    return bezierComponent(u, y1, y2)
}

private fun bezierComponent(t: Float, p1: Float, p2: Float): Float {
    val c = 3f * p1
    val b = 3f * (p2 - p1) - c
    val a = 1f - c - b
    return ((a * t + b) * t + c) * t
}

private fun bezierSlope(t: Float, p1: Float, p2: Float): Float {
    val c = 3f * p1
    val b = 3f * (p2 - p1) - c
    val a = 1f - c - b
    return (3f * a * t + 2f * b) * t + c
}

/** Appear has no hold. [OfflineBannerHoldMs] stays 0 so the hide fade is not postponed. */
const val OfflineBannerAppearDelayMs = 0L

enum class OfflineBannerSurface {
    Main,
    Player,
    LaunchGate,
}

data class OfflineBannerView(
    val visible: Boolean,
    val animated: Boolean,
)

data class OfflineBannerMark(
    val atMs: Long,
    val condition: NetworkCondition,
)

data class OfflineBannerLatch(
    val visible: Boolean = false,
    val hideArmedAtMs: Long? = null,
)

fun isOfflineBannerCondition(condition: NetworkCondition): Boolean =
    condition == NetworkCondition.NoInternet || condition == NetworkCondition.ServersUnreachable

fun offlineBannerMotionEnabled(
    surface: OfflineBannerSurface,
    reduceMotion: Boolean,
): Boolean = surface == OfflineBannerSurface.Main && !reduceMotion

/**
 * Offline shows at once. Leaving that state hides on this sample when
 * [onlineHeldMs] has reached [OfflineBannerHoldMs] (0, so the fade starts immediately).
 * [animated] is that hide fade. The probe, not this timer, rejects a dead network.
 */
fun offlineBannerView(
    condition: NetworkCondition,
    onlineHeldMs: Long,
    showingBefore: Boolean,
    surface: OfflineBannerSurface,
    reduceMotion: Boolean,
): OfflineBannerView {
    if (surface != OfflineBannerSurface.Main) {
        return OfflineBannerView(visible = false, animated = false)
    }
    val visible = if (isOfflineBannerCondition(condition)) {
        true
    } else {
        showingBefore && onlineHeldMs < OfflineBannerHoldMs
    }
    val appearing = visible && !showingBefore
    val hiding = !visible && showingBefore && onlineHeldMs >= OfflineBannerHoldMs
    return OfflineBannerView(
        visible = visible,
        animated = (appearing || hiding) && offlineBannerMotionEnabled(surface, reduceMotion),
    )
}

fun reduceOfflineBannerLatch(
    latch: OfflineBannerLatch,
    offline: Boolean,
    surface: OfflineBannerSurface,
    nowMs: Long,
): OfflineBannerLatch {
    if (surface != OfflineBannerSurface.Main) return OfflineBannerLatch()
    if (offline) return OfflineBannerLatch(visible = true)
    if (!latch.visible) return latch.copy(hideArmedAtMs = null)
    val armedAt = latch.hideArmedAtMs ?: nowMs
    val held = (nowMs - armedAt).coerceAtLeast(0L)
    return if (held >= OfflineBannerHoldMs) {
        OfflineBannerLatch(visible = false, hideArmedAtMs = armedAt)
    } else {
        latch.copy(visible = true, hideArmedAtMs = armedAt)
    }
}

fun offlineBannerConditionAt(marks: List<OfflineBannerMark>, atMs: Long): NetworkCondition {
    var condition = NetworkCondition.Unknown
    for (mark in marks.sortedBy { it.atMs }) {
        if (mark.atMs > atMs) break
        condition = mark.condition
    }
    return condition
}

fun offlineBannerViewAt(
    marks: List<OfflineBannerMark>,
    atMs: Long,
    surface: OfflineBannerSurface,
    reduceMotion: Boolean,
): OfflineBannerView {
    var latch = OfflineBannerLatch()
    var lastAt = Long.MIN_VALUE
    for (mark in marks.sortedBy { it.atMs }) {
        if (mark.atMs > atMs) break
        latch = reduceOfflineBannerLatch(
            latch = latch,
            offline = isOfflineBannerCondition(mark.condition),
            surface = surface,
            nowMs = mark.atMs,
        )
        lastAt = mark.atMs
    }
    if (lastAt != Long.MIN_VALUE && atMs > lastAt) {
        latch = reduceOfflineBannerLatch(
            latch = latch,
            offline = isOfflineBannerCondition(offlineBannerConditionAt(marks, atMs)),
            surface = surface,
            nowMs = atMs,
        )
    }
    val condition = offlineBannerConditionAt(marks, atMs)
    val onlineHeld = latch.hideArmedAtMs?.let { (atMs - it).coerceAtLeast(0L) } ?: 0L
    val showingBefore = latch.visible || (latch.hideArmedAtMs != null && onlineHeld >= OfflineBannerHoldMs)
    return offlineBannerView(
        condition = condition,
        onlineHeldMs = onlineHeld,
        showingBefore = showingBefore || isOfflineBannerCondition(condition),
        surface = surface,
        reduceMotion = reduceMotion,
    )
}

fun onOfflineBannerTap(recheck: () -> Unit) {
    recheck()
}
