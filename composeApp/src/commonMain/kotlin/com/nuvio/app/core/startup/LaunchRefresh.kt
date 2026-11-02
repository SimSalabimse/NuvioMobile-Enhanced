package com.nuvio.app.core.startup

import com.nuvio.app.core.network.NetworkCondition

internal const val DeferredLaunchNetworkTimeoutMs = 4_000L
internal const val ConnectivityRegainDebounceMs = 750L

internal data class LaunchRefreshState(
    val attemptFailed: Boolean = false,
    val inFlight: Boolean = false,
    val catalogsLoaded: Boolean = false,
    val startCount: Int = 0,
    val lastStartAtMs: Long = Long.MIN_VALUE,
)

internal fun noteFailedLaunchAttempt(state: LaunchRefreshState): LaunchRefreshState =
    state.copy(attemptFailed = true, catalogsLoaded = false)

internal fun reduceConnectivityRegained(
    state: LaunchRefreshState,
    atMs: Long,
): Pair<LaunchRefreshState, Boolean> {
    if (!state.attemptFailed || state.catalogsLoaded || state.inFlight) return state to false
    if (
        state.lastStartAtMs != Long.MIN_VALUE &&
        atMs - state.lastStartAtMs < ConnectivityRegainDebounceMs
    ) {
        return state to false
    }
    return state.copy(
        inFlight = true,
        startCount = state.startCount + 1,
        lastStartAtMs = atMs,
    ) to true
}

internal fun finishLaunchRefresh(
    state: LaunchRefreshState,
    succeeded: Boolean,
): LaunchRefreshState {
    if (!state.inFlight) return state
    return state.copy(
        inFlight = false,
        attemptFailed = !succeeded,
        catalogsLoaded = succeeded,
    )
}

internal fun connectivityRegained(previous: NetworkCondition, next: NetworkCondition): Boolean =
    next == NetworkCondition.Online &&
        (previous == NetworkCondition.NoInternet || previous == NetworkCondition.ServersUnreachable)
