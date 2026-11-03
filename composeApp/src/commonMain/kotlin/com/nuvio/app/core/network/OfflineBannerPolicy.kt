package com.nuvio.app.core.network

/**
 * No extra wait before the hide fade. A dead network stays offline until the probe
 * succeeds, so the fade can start on the online frame. [OfflineBannerHideFadeMs] is that fade.
 */
const val OfflineBannerHoldMs = 0L
const val OfflineBannerShowMs = 220
const val OfflineBannerHideFadeMs = 250

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
    val hiding = !visible && showingBefore && onlineHeldMs >= OfflineBannerHoldMs
    return OfflineBannerView(
        visible = visible,
        animated = hiding && offlineBannerMotionEnabled(surface, reduceMotion),
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
