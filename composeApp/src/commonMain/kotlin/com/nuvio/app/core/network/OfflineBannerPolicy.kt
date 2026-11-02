package com.nuvio.app.core.network

const val OfflineBannerHoldMs = 1_000L
const val OfflineBannerShowMs = 220
const val OfflineBannerHideFadeMs = 140

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

fun isOfflineBannerCondition(condition: NetworkCondition): Boolean =
    condition == NetworkCondition.NoInternet || condition == NetworkCondition.ServersUnreachable

fun offlineBannerMotionEnabled(
    surface: OfflineBannerSurface,
    reduceMotion: Boolean,
): Boolean = surface == OfflineBannerSurface.Main && !reduceMotion

fun offlineBannerView(
    condition: NetworkCondition,
    offlineHeldMs: Long,
    surface: OfflineBannerSurface,
    reduceMotion: Boolean,
): OfflineBannerView {
    val visible = surface == OfflineBannerSurface.Main &&
        isOfflineBannerCondition(condition) &&
        offlineHeldMs >= OfflineBannerHoldMs
    return OfflineBannerView(
        visible = visible,
        animated = visible && offlineBannerMotionEnabled(surface, reduceMotion),
    )
}

fun offlineHeldMs(marks: List<OfflineBannerMark>, atMs: Long): Long {
    var since: Long? = null
    for (mark in marks.sortedBy { it.atMs }) {
        if (mark.atMs > atMs) break
        since = if (isOfflineBannerCondition(mark.condition)) since ?: mark.atMs else null
    }
    val start = since ?: return 0L
    return (atMs - start).coerceAtLeast(0L)
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
): OfflineBannerView = offlineBannerView(
    condition = offlineBannerConditionAt(marks, atMs),
    offlineHeldMs = offlineHeldMs(marks, atMs),
    surface = surface,
    reduceMotion = reduceMotion,
)

fun onOfflineBannerTap(recheck: () -> Unit) {
    recheck()
}
