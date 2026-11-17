package com.nuvio.app.core.startup

import com.nuvio.app.core.network.NetworkCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchRefreshTest {
    @Test
    fun `start offline, then go online, addons load`() {
        var state = noteFailedLaunchAttempt(LaunchRefreshState())
        assertFalse(state.catalogsLoaded)
        assertTrue(state.attemptFailed)

        val regained = reduceConnectivityRegained(state, atMs = 5_000)
        assertTrue(regained.second)
        state = regained.first
        assertEquals(1, state.startCount)
        assertTrue(state.inFlight)

        state = finishLaunchRefresh(state, succeeded = true)
        assertTrue(state.catalogsLoaded)
        assertEquals(1, state.startCount)
        assertFalse(state.inFlight)

        val again = reduceConnectivityRegained(state, atMs = 20_000)
        assertFalse(again.second)
        assertEquals(1, again.first.startCount)
        assertTrue(again.first.catalogsLoaded)
    }

    @Test
    fun `a second regained event during an in-flight refresh does not start another`() {
        val failed = noteFailedLaunchAttempt(LaunchRefreshState())
        val first = reduceConnectivityRegained(failed, atMs = 5_000)
        assertTrue(first.second)
        val second = reduceConnectivityRegained(
            first.first,
            atMs = 5_000 + ConnectivityRegainDebounceMs + 50,
        )
        assertFalse(second.second)
        assertEquals(1, second.first.startCount)
        assertTrue(second.first.inFlight)
        assertFalse(second.first.catalogsLoaded)
    }

    @Test
    fun `a flapping connection does not start a second refresh inside the debounce`() {
        val failed = noteFailedLaunchAttempt(LaunchRefreshState())
        val first = reduceConnectivityRegained(failed, atMs = 1_000)
        val finished = finishLaunchRefresh(first.first, succeeded = false)
        assertTrue(finished.attemptFailed)
        val flapping = reduceConnectivityRegained(finished, atMs = 1_000 + 100)
        assertFalse(flapping.second)
        assertEquals(1, flapping.first.startCount)
        val later = reduceConnectivityRegained(
            finished,
            atMs = 1_000 + ConnectivityRegainDebounceMs,
        )
        assertTrue(later.second)
        assertEquals(2, later.first.startCount)
    }

    @Test
    fun `connectivity regained is the online edge after offline`() {
        assertTrue(connectivityRegained(NetworkCondition.NoInternet, NetworkCondition.Online))
        assertTrue(connectivityRegained(NetworkCondition.ServersUnreachable, NetworkCondition.Online))
        assertFalse(connectivityRegained(NetworkCondition.Unknown, NetworkCondition.Online))
        assertFalse(connectivityRegained(NetworkCondition.Checking, NetworkCondition.Online))
        assertFalse(connectivityRegained(NetworkCondition.Online, NetworkCondition.Online))
        assertFalse(connectivityRegained(NetworkCondition.NoInternet, NetworkCondition.Checking))
    }
}
