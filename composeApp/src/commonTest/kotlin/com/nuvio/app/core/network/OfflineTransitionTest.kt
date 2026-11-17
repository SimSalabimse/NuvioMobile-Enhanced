package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineTransitionTest {
    @Test
    fun offlineToOnlineKeepsItemsHidesBannerAndFlushesOnce() {
        val transition = offlineToOnlineTransition(hasExistingItems = true)
        assertFalse(transition.bannerVisible)
        assertFalse(transition.bannerReservesLayout)
        assertEquals(ListRefreshStrategy.KeepExisting, transition.refresh)
        assertTrue(transition.flushOutbox)
        assertFalse(transition.announceReconnect)
        assertEquals(
            ListRefreshStrategy.ReplaceWithPlaceholder,
            offlineToOnlineTransition(hasExistingItems = false).refresh,
        )
        assertTrue(keepExistingItemsDuringRefresh(existingCount = 4, keepVisible = true))
        assertFalse(keepExistingItemsDuringRefresh(existingCount = 0, keepVisible = true))
        assertFalse(keepExistingItemsDuringRefresh(existingCount = 4, keepVisible = false))

        var edge = ConnectivityEdgeState()
        var flushes = 0
        fun step(next: NetworkCondition) {
            val result = reduceConnectivityEdge(edge, next)
            edge = result.state
            if (result.flushOutbox) flushes += 1
        }
        step(NetworkCondition.NoInternet)
        step(NetworkCondition.Online)
        assertEquals(1, flushes)
        step(NetworkCondition.Online)
        step(NetworkCondition.Checking)
        step(NetworkCondition.Online)
        assertEquals(1, flushes)
    }

    @Test
    fun flappingConnectionFlushesOncePerRealEdge() {
        var edge = ConnectivityEdgeState()
        var flushes = 0
        listOf(
            NetworkCondition.Online,
            NetworkCondition.NoInternet,
            NetworkCondition.Checking,
            NetworkCondition.Online,
            NetworkCondition.Online,
            NetworkCondition.ServersUnreachable,
            NetworkCondition.NoInternet,
            NetworkCondition.Unknown,
            NetworkCondition.Online,
        ).forEach { next ->
            val result = reduceConnectivityEdge(edge, next)
            edge = result.state
            if (result.flushOutbox) flushes += 1
        }
        assertEquals(2, flushes)
        assertFalse(edge.sawOffline)
    }
}