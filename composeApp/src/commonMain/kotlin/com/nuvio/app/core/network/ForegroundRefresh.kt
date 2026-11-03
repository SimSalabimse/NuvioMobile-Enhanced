package com.nuvio.app.core.network

/** Foreground recheck while the UI is already online. Unchanged from the previous 6s wait. */
internal const val ForegroundOnlineRefreshDelayMs = 6_000L

/**
 * Foreground or path-up while the UI is offline. Must stay at or under 300ms.
 * 0 starts the probe on this turn; the probe timeout is what bounds a hung request.
 */
internal const val ForegroundOfflineProbeDelayMs = 0L

/** A failed probe while already online waits this long and tries once more, so a blip does not flip the UI offline. */
internal const val ForegroundFailureConfirmDelayMs = 2_000L

/** Full probe budget. A just-rejoined radio plus one reachability check still fits; this is not a multi-retry loop. */
internal const val NetworkStatusProbeTimeoutMs = 4_500L

internal fun foregroundRefreshDelayMs(condition: NetworkCondition): Long =
    if (condition == NetworkCondition.NoInternet || condition == NetworkCondition.ServersUnreachable) {
        ForegroundOfflineProbeDelayMs
    } else {
        ForegroundOnlineRefreshDelayMs
    }

/**
 * Short cap for a watch probe that is only confirming a path while the UI is not offline.
 * An offline UI uses [NetworkStatusProbeTimeoutMs] so a slow first answer is not reported as no internet.
 */
internal fun watchProbeTimeoutMs(watchOnly: Boolean, offlineLike: Boolean): Long =
    if (watchOnly && !offlineLike) ConnectionWatchProbeTimeoutMs else NetworkStatusProbeTimeoutMs

internal fun shouldConfirmForegroundFailure(
    confirmFailures: Boolean,
    previous: NetworkCondition,
    next: NetworkCondition,
): Boolean {
    val nextOffline = next == NetworkCondition.NoInternet || next == NetworkCondition.ServersUnreachable
    return confirmFailures && previous == NetworkCondition.Online && nextOffline
}
