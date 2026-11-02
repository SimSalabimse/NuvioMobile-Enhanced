package com.nuvio.app.core.network

internal const val ConnectionWatchDebounceMs = 750L

internal const val ConnectionWatchProbeTimeoutMs = 1_500L

internal enum class ConnectionWatchStatus {
    Unknown,
    Online,
    Offline,
}

internal enum class ConnectionWatchInput {
    PathSatisfied,
    PathUnsatisfied,
    ProbeOk,
    ProbeFailed,
    AppForeground,
    AppBackground,
    DebounceElapsed,
}

internal data class ConnectionWatchState(
    val status: ConnectionWatchStatus = ConnectionWatchStatus.Unknown,
    val foreground: Boolean = true,
    val debounceArmed: Boolean = false,
    val probeInFlight: Boolean = false,
    val probeGeneration: Long = 0,
)

internal data class ConnectionWatchDecision(
    val state: ConnectionWatchState,
    val probeRequested: Boolean = false,
    val armDebounce: Boolean = false,
    val cancelDebounce: Boolean = false,
    val startWatching: Boolean = false,
    val stopWatching: Boolean = false,
)

internal fun reduceConnectionWatch(
    state: ConnectionWatchState,
    input: ConnectionWatchInput,
): ConnectionWatchDecision {
    return when (input) {
        ConnectionWatchInput.PathUnsatisfied -> reducePathUnsatisfied(state)
        ConnectionWatchInput.PathSatisfied -> reducePathSatisfied(state)
        ConnectionWatchInput.DebounceElapsed -> reduceDebounceElapsed(state)
        ConnectionWatchInput.ProbeOk -> reduceProbeFinished(state, online = true)
        ConnectionWatchInput.ProbeFailed -> reduceProbeFinished(state, online = false)
        ConnectionWatchInput.AppBackground -> reduceAppBackground(state)
        ConnectionWatchInput.AppForeground -> reduceAppForeground(state)
    }
}

internal fun connectionWatchPublication(
    previous: ConnectionWatchState,
    input: ConnectionWatchInput,
    decision: ConnectionWatchDecision,
    probeCondition: NetworkCondition? = null,
): NetworkCondition? {
    return when (input) {
        ConnectionWatchInput.PathUnsatisfied -> {
            val pathDropped = previous.status != ConnectionWatchStatus.Offline ||
                previous.probeInFlight ||
                previous.debounceArmed
            if (pathDropped) NetworkCondition.NoInternet else null
        }

        ConnectionWatchInput.ProbeOk -> {
            if (previous.probeInFlight && decision.state.status == ConnectionWatchStatus.Online) {
                NetworkCondition.Online
            } else {
                null
            }
        }

        ConnectionWatchInput.ProbeFailed -> {
            if (previous.probeInFlight && decision.state.status == ConnectionWatchStatus.Offline) {
                if (probeCondition == NetworkCondition.ServersUnreachable) {
                    NetworkCondition.ServersUnreachable
                } else {
                    NetworkCondition.NoInternet
                }
            } else {
                null
            }
        }

        ConnectionWatchInput.AppForeground,
        ConnectionWatchInput.AppBackground,
        ConnectionWatchInput.DebounceElapsed,
        ConnectionWatchInput.PathSatisfied,
        -> null
    }
}

private fun reducePathUnsatisfied(state: ConnectionWatchState): ConnectionWatchDecision {
    val idleOffline = state.status == ConnectionWatchStatus.Offline &&
        !state.probeInFlight &&
        !state.debounceArmed
    if (idleOffline) return ConnectionWatchDecision(state)
    return ConnectionWatchDecision(
        state = state.copy(
            status = ConnectionWatchStatus.Offline,
            debounceArmed = false,
            probeInFlight = false,
            probeGeneration = state.probeGeneration + 1,
        ),
        cancelDebounce = state.debounceArmed,
    )
}

private fun reducePathSatisfied(state: ConnectionWatchState): ConnectionWatchDecision {
    val waitForForeground = !state.foreground
    val alreadyConfirming = state.probeInFlight || state.debounceArmed
    val alreadyOnline = state.status == ConnectionWatchStatus.Online
    if (waitForForeground || alreadyConfirming || alreadyOnline) {
        return ConnectionWatchDecision(state)
    }
    return ConnectionWatchDecision(
        state = state.copy(debounceArmed = true),
        armDebounce = true,
    )
}

private fun reduceDebounceElapsed(state: ConnectionWatchState): ConnectionWatchDecision {
    if (!state.debounceArmed) return ConnectionWatchDecision(state)
    if (!state.foreground || state.probeInFlight) {
        return ConnectionWatchDecision(state.copy(debounceArmed = false))
    }
    return ConnectionWatchDecision(
        state = state.copy(debounceArmed = false, probeInFlight = true),
        probeRequested = true,
    )
}

private fun reduceProbeFinished(
    state: ConnectionWatchState,
    online: Boolean,
): ConnectionWatchDecision {
    if (!state.probeInFlight) return ConnectionWatchDecision(state)
    return ConnectionWatchDecision(
        state = state.copy(
            status = if (online) ConnectionWatchStatus.Online else ConnectionWatchStatus.Offline,
            probeInFlight = false,
        ),
    )
}

private fun reduceAppBackground(state: ConnectionWatchState): ConnectionWatchDecision {
    if (!state.foreground) return ConnectionWatchDecision(state)
    return ConnectionWatchDecision(
        state = state.copy(foreground = false, debounceArmed = false),
        cancelDebounce = state.debounceArmed,
        stopWatching = true,
    )
}

private fun reduceAppForeground(state: ConnectionWatchState): ConnectionWatchDecision {
    if (state.foreground) return ConnectionWatchDecision(state)
    val resumed = state.copy(foreground = true)
    if (resumed.probeInFlight || resumed.debounceArmed) {
        return ConnectionWatchDecision(
            state = resumed,
            startWatching = true,
        )
    }
    return ConnectionWatchDecision(
        state = resumed.copy(debounceArmed = true),
        armDebounce = true,
        startWatching = true,
    )
}
