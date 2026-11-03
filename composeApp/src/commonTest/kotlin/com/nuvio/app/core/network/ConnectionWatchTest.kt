package com.nuvio.app.core.network

import com.nuvio.app.core.startup.ConnectivityRegainDebounceMs
import com.nuvio.app.core.startup.LaunchRefreshState
import com.nuvio.app.core.startup.connectivityRegained
import com.nuvio.app.core.startup.finishLaunchRefresh
import com.nuvio.app.core.startup.noteFailedLaunchAttempt
import com.nuvio.app.core.startup.reduceConnectivityRegained
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionWatchTest {
    @Test
    fun stateTable() {
        val online = ConnectionWatchState(status = ConnectionWatchStatus.Online)
        val offline = ConnectionWatchState(status = ConnectionWatchStatus.Offline)
        val unknown = ConnectionWatchState(status = ConnectionWatchStatus.Unknown)
        val cases = listOf(
            WatchCase(
                name = "drop goes offline at once",
                start = online,
                steps = listOf(ConnectionWatchInput.PathUnsatisfied),
                status = ConnectionWatchStatus.Offline,
                probes = 0,
            ),
            WatchCase(
                name = "return runs one probe and goes online only if it succeeds",
                start = offline,
                steps = listOf(
                    ConnectionWatchInput.PathSatisfied,
                    ConnectionWatchInput.DebounceElapsed,
                    ConnectionWatchInput.ProbeOk,
                ),
                status = ConnectionWatchStatus.Online,
                probes = 1,
            ),
            WatchCase(
                name = "return stays offline until the probe succeeds",
                start = offline,
                steps = listOf(
                    ConnectionWatchInput.PathSatisfied,
                    ConnectionWatchInput.DebounceElapsed,
                ),
                status = ConnectionWatchStatus.Offline,
                probes = 1,
            ),
            WatchCase(
                name = "captive portal stays offline",
                start = offline,
                steps = listOf(
                    ConnectionWatchInput.PathSatisfied,
                    ConnectionWatchInput.DebounceElapsed,
                    ConnectionWatchInput.ProbeFailed,
                ),
                status = ConnectionWatchStatus.Offline,
                probes = 1,
            ),
            WatchCase(
                name = "captive portal from unknown stays offline",
                start = unknown,
                steps = listOf(
                    ConnectionWatchInput.PathSatisfied,
                    ConnectionWatchInput.DebounceElapsed,
                    ConnectionWatchInput.ProbeFailed,
                ),
                status = ConnectionWatchStatus.Offline,
                probes = 1,
            ),
            WatchCase(
                name = "servers unreachable probe stays offline",
                start = offline,
                steps = listOf(
                    ConnectionWatchInput.PathSatisfied,
                    ConnectionWatchInput.DebounceElapsed,
                    ConnectionWatchInput.ProbeFailed,
                ),
                status = ConnectionWatchStatus.Offline,
                probes = 1,
            ),
            WatchCase(
                name = "a drop and return probes again, repeats while a probe is up do not",
                start = offline,
                steps = listOf(
                    ConnectionWatchInput.PathSatisfied,
                    ConnectionWatchInput.PathSatisfied,
                    ConnectionWatchInput.PathUnsatisfied,
                    ConnectionWatchInput.PathSatisfied,
                    ConnectionWatchInput.PathSatisfied,
                    ConnectionWatchInput.DebounceElapsed,
                ),
                status = ConnectionWatchStatus.Offline,
                probes = 2,
            ),
            WatchCase(
                name = "satisfied events during a probe do not request another",
                start = offline,
                steps = listOf(
                    ConnectionWatchInput.PathSatisfied,
                    ConnectionWatchInput.DebounceElapsed,
                    ConnectionWatchInput.PathSatisfied,
                    ConnectionWatchInput.PathSatisfied,
                    ConnectionWatchInput.ProbeOk,
                ),
                status = ConnectionWatchStatus.Online,
                probes = 1,
            ),
            WatchCase(
                name = "foreground rechecks",
                start = offline.copy(foreground = false),
                steps = listOf(
                    ConnectionWatchInput.AppForeground,
                    ConnectionWatchInput.DebounceElapsed,
                ),
                status = ConnectionWatchStatus.Offline,
                probes = 1,
            ),
            WatchCase(
                name = "foreground recheck can come back online",
                start = ConnectionWatchState(
                    status = ConnectionWatchStatus.Online,
                    foreground = false,
                ),
                steps = listOf(
                    ConnectionWatchInput.AppForeground,
                    ConnectionWatchInput.PathSatisfied,
                    ConnectionWatchInput.DebounceElapsed,
                    ConnectionWatchInput.ProbeOk,
                ),
                status = ConnectionWatchStatus.Online,
                probes = 1,
            ),
            WatchCase(
                name = "foreground recheck stays offline when the probe fails",
                start = offline.copy(foreground = false),
                steps = listOf(
                    ConnectionWatchInput.AppForeground,
                    ConnectionWatchInput.DebounceElapsed,
                    ConnectionWatchInput.ProbeFailed,
                ),
                status = ConnectionWatchStatus.Offline,
                probes = 1,
            ),
            WatchCase(
                name = "background after an immediate reconnect probe does not request another",
                start = offline,
                steps = listOf(
                    ConnectionWatchInput.PathSatisfied,
                    ConnectionWatchInput.AppBackground,
                    ConnectionWatchInput.DebounceElapsed,
                ),
                status = ConnectionWatchStatus.Offline,
                probes = 1,
            ),
            WatchCase(
                name = "an already online path hint does not probe",
                start = online,
                steps = listOf(
                    ConnectionWatchInput.PathSatisfied,
                    ConnectionWatchInput.PathSatisfied,
                    ConnectionWatchInput.DebounceElapsed,
                ),
                status = ConnectionWatchStatus.Online,
                probes = 0,
            ),
            WatchCase(
                name = "stale probe success after a drop stays offline",
                start = offline,
                steps = listOf(
                    ConnectionWatchInput.PathSatisfied,
                    ConnectionWatchInput.DebounceElapsed,
                    ConnectionWatchInput.PathUnsatisfied,
                    ConnectionWatchInput.ProbeOk,
                ),
                status = ConnectionWatchStatus.Offline,
                probes = 1,
            ),
        )

        cases.forEach { case ->
            val played = play(case.start, case.steps)
            assertEquals(case.status, played.state.status, case.name)
            assertEquals(case.probes, played.probes, case.name)
        }
    }

    @Test
    fun dropPublishesOfflineImmediatelyAndAFailedProbeStaysOffline() {
        val offline = ConnectionWatchState(status = ConnectionWatchStatus.Offline)
        val drop = reduceConnectionWatch(
            ConnectionWatchState(status = ConnectionWatchStatus.Online),
            ConnectionWatchInput.PathUnsatisfied,
        )
        assertEquals(ConnectionWatchStatus.Offline, drop.state.status)
        assertEquals(
            NetworkCondition.NoInternet,
            connectionWatchPublication(
                previous = ConnectionWatchState(status = ConnectionWatchStatus.Online),
                input = ConnectionWatchInput.PathUnsatisfied,
                decision = drop,
            ),
        )
        assertFalse(drop.probeRequested)

        val probing = play(
            offline,
            listOf(ConnectionWatchInput.PathSatisfied, ConnectionWatchInput.DebounceElapsed),
        )
        val failed = reduceConnectionWatch(probing.state, ConnectionWatchInput.ProbeFailed)
        assertEquals(ConnectionWatchStatus.Offline, failed.state.status)
        assertEquals(
            NetworkCondition.NoInternet,
            connectionWatchPublication(probing.state, ConnectionWatchInput.ProbeFailed, failed),
        )
        assertEquals(
            NetworkCondition.ServersUnreachable,
            connectionWatchPublication(
                probing.state,
                ConnectionWatchInput.ProbeFailed,
                failed,
                probeCondition = NetworkCondition.ServersUnreachable,
            ),
        )
    }

    @Test
    fun foregroundArmsOneDebouncedProbeAndPathFlapsDoNotAddAnother() {
        var state = ConnectionWatchState(
            status = ConnectionWatchStatus.Offline,
            foreground = false,
        )
        val background = reduceConnectionWatch(state, ConnectionWatchInput.AppBackground)
        assertFalse(background.stopWatching)

        val toBackground = reduceConnectionWatch(
            state.copy(foreground = true, debounceArmed = true),
            ConnectionWatchInput.AppBackground,
        )
        assertTrue(toBackground.stopWatching)
        assertTrue(toBackground.cancelDebounce)

        val resumed = reduceConnectionWatch(state, ConnectionWatchInput.AppForeground)
        assertTrue(resumed.startWatching)
        assertTrue(resumed.probeRequested)
        assertFalse(resumed.armDebounce)
        state = resumed.state

        val flap = reduceConnectionWatch(state, ConnectionWatchInput.PathSatisfied)
        assertFalse(flap.armDebounce)
        assertFalse(flap.probeRequested)
        state = flap.state

        val elapsed = reduceConnectionWatch(state, ConnectionWatchInput.DebounceElapsed)
        assertFalse(elapsed.probeRequested)

        val online = reduceConnectionWatch(
            ConnectionWatchState(status = ConnectionWatchStatus.Online, foreground = false),
            ConnectionWatchInput.AppForeground,
        )
        assertTrue(online.armDebounce)
        assertFalse(online.probeRequested)
        assertEquals(ConnectionWatchDebounceMs, reconnectConfirmDelayMs(ConnectionWatchStatus.Online))
        assertEquals(0L, reconnectConfirmDelayMs(ConnectionWatchStatus.Offline))
        assertTrue(reconnectConfirmDelayMs(ConnectionWatchStatus.Offline) <= 300L)
        assertTrue(reconnectConfirmDelayMs(ConnectionWatchStatus.Unknown) <= 300L)
    }

    @Test
    fun everyStateCanReachOnlineAndOffline() {
        val statuses = ConnectionWatchStatus.entries
        val flags = listOf(false, true)
        statuses.forEach { status ->
            flags.forEach { foreground ->
                flags.forEach { debounceArmed ->
                    flags.forEach { probeInFlight ->
                        val start = ConnectionWatchState(
                            status = status,
                            foreground = foreground,
                            debounceArmed = debounceArmed,
                            probeInFlight = probeInFlight,
                        )
                        assertTrue(reaches(start, ConnectionWatchStatus.Online), "$start -> online")
                        assertTrue(reaches(start, ConnectionWatchStatus.Offline), "$start -> offline")
                    }
                }
            }
        }
    }

    @Test
    fun debounceAndProbeBudgetStayShort() {
        assertEquals(750L, ConnectionWatchDebounceMs)
        assertEquals(1_500L, ConnectionWatchProbeTimeoutMs)
        assertTrue(ConnectionWatchProbeTimeoutMs < 4_500L)
    }

    @Test
    fun onlineEdgeFiresTheDeferredRetryOnce() {
        var watch = ConnectionWatchState(status = ConnectionWatchStatus.Offline)
        var launch = noteFailedLaunchAttempt(LaunchRefreshState())
        var published = NetworkCondition.NoInternet
        var starts = 0
        var atMs = 5_000L

        fun drive(input: ConnectionWatchInput, probe: NetworkCondition? = null) {
            val previous = watch
            val decision = reduceConnectionWatch(watch, input)
            watch = decision.state
            val next = connectionWatchPublication(previous, input, decision, probe) ?: return
            if (next == published) return
            val prior = published
            published = next
            when (next) {
                NetworkCondition.NoInternet,
                NetworkCondition.ServersUnreachable,
                -> launch = noteFailedLaunchAttempt(launch)

                NetworkCondition.Online -> {
                    if (connectivityRegained(prior, next) || prior != NetworkCondition.Online) {
                        val reduced = reduceConnectivityRegained(launch, atMs)
                        launch = reduced.first
                        if (reduced.second) starts += 1
                    }
                }

                NetworkCondition.Unknown,
                NetworkCondition.Checking,
                -> Unit
            }
        }

        drive(ConnectionWatchInput.PathSatisfied)
        drive(ConnectionWatchInput.DebounceElapsed)
        assertEquals(0, starts)
        drive(ConnectionWatchInput.ProbeOk, NetworkCondition.Online)
        assertEquals(NetworkCondition.Online, published)
        assertEquals(1, starts)
        assertEquals(1, launch.startCount)
        assertTrue(launch.inFlight)

        val duplicate = reduceConnectivityRegained(
            launch,
            atMs + ConnectivityRegainDebounceMs + 50,
        )
        assertFalse(duplicate.second)
        assertEquals(1, duplicate.first.startCount)

        atMs += ConnectivityRegainDebounceMs + 50
        drive(ConnectionWatchInput.PathUnsatisfied)
        drive(ConnectionWatchInput.PathSatisfied)
        drive(ConnectionWatchInput.DebounceElapsed)
        drive(ConnectionWatchInput.ProbeOk, NetworkCondition.Online)
        assertEquals(1, starts)
        assertEquals(1, launch.startCount)
        assertTrue(launch.inFlight)

        launch = finishLaunchRefresh(launch, succeeded = true)
        val afterSuccess = reduceConnectivityRegained(launch, atMs + 20_000)
        assertFalse(afterSuccess.second)
        assertEquals(1, afterSuccess.first.startCount)
        assertTrue(afterSuccess.first.catalogsLoaded)
    }
}

private data class WatchCase(
    val name: String,
    val start: ConnectionWatchState,
    val steps: List<ConnectionWatchInput>,
    val status: ConnectionWatchStatus,
    val probes: Int,
)

private data class PlayedWatch(
    val state: ConnectionWatchState,
    val probes: Int,
)

private fun play(start: ConnectionWatchState, steps: List<ConnectionWatchInput>): PlayedWatch {
    var state = start
    var probes = 0
    steps.forEach { step ->
        val decision = reduceConnectionWatch(state, step)
        if (decision.probeRequested) probes += 1
        state = decision.state
    }
    return PlayedWatch(state, probes)
}

private data class WatchFlags(
    val status: ConnectionWatchStatus,
    val foreground: Boolean,
    val debounceArmed: Boolean,
    val probeInFlight: Boolean,
)

private fun ConnectionWatchState.flags(): WatchFlags = WatchFlags(
    status = status,
    foreground = foreground,
    debounceArmed = debounceArmed,
    probeInFlight = probeInFlight,
)

private fun reaches(start: ConnectionWatchState, target: ConnectionWatchStatus): Boolean {
    if (start.status == target) return true
    val pending = ArrayDeque<ConnectionWatchState>()
    val seen = HashSet<WatchFlags>()
    pending.add(start)
    seen.add(start.flags())
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        ConnectionWatchInput.entries.forEach { input ->
            val next = reduceConnectionWatch(current, input).state
            if (next.status == target) return true
            if (seen.add(next.flags())) pending.add(next)
        }
    }
    return false
}
