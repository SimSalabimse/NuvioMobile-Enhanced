package com.nuvio.app.core.network

internal data class ConnectivityEdgeState(
    val sawOffline: Boolean = false,
)

internal data class ConnectivityEdgeResult(
    val state: ConnectivityEdgeState,
    val flushOutbox: Boolean,
)

/**
 * One outbox flush for each real offline-to-online edge.
 * Checking or Unknown between the drop and the return does not add a flush.
 * A second Online signal while already online does not flush again.
 */
internal fun reduceConnectivityEdge(
    state: ConnectivityEdgeState,
    next: NetworkCondition,
): ConnectivityEdgeResult {
    val offline = next == NetworkCondition.NoInternet || next == NetworkCondition.ServersUnreachable
    if (offline) {
        return ConnectivityEdgeResult(state.copy(sawOffline = true), flushOutbox = false)
    }
    if (next == NetworkCondition.Online && state.sawOffline) {
        return ConnectivityEdgeResult(state.copy(sawOffline = false), flushOutbox = true)
    }
    return ConnectivityEdgeResult(state, flushOutbox = false)
}

internal enum class ListRefreshStrategy {
    KeepExisting,
    ReplaceWithPlaceholder,
}

internal data class OfflineOnlineTransition(
    val bannerVisible: Boolean,
    val bannerReservesLayout: Boolean,
    val refresh: ListRefreshStrategy,
    val flushOutbox: Boolean,
    val announceReconnect: Boolean,
)

/** What the screen should do on the frame the connection is back. */
internal fun offlineToOnlineTransition(hasExistingItems: Boolean): OfflineOnlineTransition =
    OfflineOnlineTransition(
        bannerVisible = false,
        bannerReservesLayout = false,
        refresh = if (hasExistingItems) {
            ListRefreshStrategy.KeepExisting
        } else {
            ListRefreshStrategy.ReplaceWithPlaceholder
        },
        flushOutbox = true,
        announceReconnect = false,
    )

/** True when a refresh must leave the current rows on screen. */
internal fun keepExistingItemsDuringRefresh(existingCount: Int, keepVisible: Boolean): Boolean =
    keepVisible && existingCount > 0
