package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForegroundRefreshTest {
    @Test
    fun foregroundWhileOfflineProbesImmediatelyAndOnlineKeepsTheLongDelay() {
        assertEquals(0L, foregroundRefreshDelayMs(NetworkCondition.NoInternet))
        assertEquals(0L, foregroundRefreshDelayMs(NetworkCondition.ServersUnreachable))
        assertTrue(ForegroundOfflineProbeDelayMs <= 300L)
        assertEquals(6_000L, foregroundRefreshDelayMs(NetworkCondition.Online))
        assertEquals(6_000L, foregroundRefreshDelayMs(NetworkCondition.Checking))
        assertEquals(6_000L, foregroundRefreshDelayMs(NetworkCondition.Unknown))
        assertEquals(ForegroundOnlineRefreshDelayMs, foregroundRefreshDelayMs(NetworkCondition.Online))
    }

    @Test
    fun offlineReconnectUsesTheFullProbeBudgetAndABlipOnlineDoesNot() {
        assertEquals(NetworkStatusProbeTimeoutMs, watchProbeTimeoutMs(watchOnly = true, offlineLike = true))
        assertEquals(ConnectionWatchProbeTimeoutMs, watchProbeTimeoutMs(watchOnly = true, offlineLike = false))
        assertEquals(NetworkStatusProbeTimeoutMs, watchProbeTimeoutMs(watchOnly = false, offlineLike = false))
        assertEquals(4_500L, NetworkStatusProbeTimeoutMs)
        assertTrue(ConnectionWatchProbeTimeoutMs < NetworkStatusProbeTimeoutMs)
        assertEquals(2_000L, ForegroundFailureConfirmDelayMs)
        assertTrue(
            shouldConfirmForegroundFailure(
                confirmFailures = true,
                previous = NetworkCondition.Online,
                next = NetworkCondition.NoInternet,
            ),
        )
        assertFalse(
            shouldConfirmForegroundFailure(
                confirmFailures = true,
                previous = NetworkCondition.NoInternet,
                next = NetworkCondition.NoInternet,
            ),
        )
        assertFalse(
            shouldConfirmForegroundFailure(
                confirmFailures = false,
                previous = NetworkCondition.Online,
                next = NetworkCondition.ServersUnreachable,
            ),
        )
    }
}
