package com.nuvio.app.core.network

import androidx.compose.runtime.Composable
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.details_check_connection
import nuvio.composeapp.generated.resources.details_servers_unreachable
import nuvio.composeapp.generated.resources.network_cannot_reach_servers
import nuvio.composeapp.generated.resources.network_connection_issue
import nuvio.composeapp.generated.resources.network_no_internet_connection
import nuvio.composeapp.generated.resources.network_please_check_connection
import org.jetbrains.compose.resources.stringResource

enum class NetworkCondition {
    Unknown,
    Checking,
    Online,
    NoInternet,
    ServersUnreachable,
}

data class NetworkStatusUiState(
    val condition: NetworkCondition = NetworkCondition.Unknown,
) {
    val isOnline: Boolean
        get() = condition == NetworkCondition.Online

    val isOfflineLike: Boolean
        get() = condition == NetworkCondition.NoInternet || condition == NetworkCondition.ServersUnreachable
}

@Composable
fun NetworkCondition.titleForEmptyState(): String =
    when (this) {
        NetworkCondition.ServersUnreachable -> stringResource(Res.string.network_cannot_reach_servers)
        NetworkCondition.NoInternet -> stringResource(Res.string.network_no_internet_connection)
        else -> stringResource(Res.string.network_connection_issue)
    }

@Composable
fun NetworkCondition.messageForEmptyState(): String =
    when (this) {
        NetworkCondition.ServersUnreachable -> stringResource(Res.string.details_servers_unreachable)
        NetworkCondition.NoInternet -> stringResource(Res.string.details_check_connection)
        else -> stringResource(Res.string.network_please_check_connection)
    }

object NetworkStatusRepository {
    private const val REQUEST_TIMEOUT_MS = 4_500L
    private const val FOREGROUND_REFRESH_DELAY_MS = 6_000L
    private const val FOREGROUND_FAILURE_CONFIRM_DELAY_MS = 2_000L
    private const val PUBLIC_PROBE_PRIMARY = "https://www.gstatic.com/generate_204"
    private const val PUBLIC_PROBE_FALLBACK = "https://cloudflare.com/cdn-cgi/trace"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(NetworkStatusUiState())
    val uiState: StateFlow<NetworkStatusUiState> = _uiState.asStateFlow()

    private val watchLock = SynchronizedObject()
    private val probeLock = SynchronizedObject()
    private val messages = Channel<WatchMessage>(Channel.UNLIMITED)
    private var watchState = ConnectionWatchState()
    private var watcherStarted = false
    private var debounceJob: Job? = null

    private var started = false
    private var foregroundRefreshJob: Job? = null
    private var probeLoopRunning = false
    private var probeLoopToken = 0
    private var probeStartedGeneration = Long.MIN_VALUE
    private var pendingForce = false
    private var pendingWatchRestart = false
    private var pendingConfirm = false
    private var queuedWatchGeneration: Long? = null

    fun ensureStarted() {
        if (!started) {
            started = true
            requestRefresh(force = true)
            return
        }
        ensureWatcher()
    }

    fun requestForegroundRefresh() {
        ensureStarted()
        foregroundRefreshJob?.cancel()
        foregroundRefreshJob = scope.launch {
            delay(FOREGROUND_REFRESH_DELAY_MS)
            requestRefresh(force = true, confirmFailures = true)
        }
    }

    fun requestRefresh(force: Boolean = false, confirmFailures: Boolean = false) {
        if (!started) started = true
        ensureWatcher()
        enqueueProbe(force = force, confirmFailures = confirmFailures, watchGeneration = null)
    }

    private fun ensureWatcher() {
        val start = synchronized(watchLock) {
            if (watcherStarted) {
                false
            } else {
                watcherStarted = true
                true
            }
        }
        if (!start) return
        scope.launch {
            for (message in messages) {
                when (message) {
                    WatchMessage.ProbeBegan -> {
                        if (_uiState.value.condition == NetworkCondition.Unknown) {
                            _uiState.value = NetworkStatusUiState(NetworkCondition.Checking)
                        }
                    }

                    is WatchMessage.Input -> applyInput(message.input, probeCondition = null)
                    is WatchMessage.ProbeSettled -> onProbeSettled(message)
                }
            }
        }
        ConnectionPathMonitor.start { satisfied ->
            val input = if (satisfied) {
                ConnectionWatchInput.PathSatisfied
            } else {
                ConnectionWatchInput.PathUnsatisfied
            }
            messages.trySend(WatchMessage.Input(input))
        }
        scope.launch(Dispatchers.Main) {
            AppForegroundMonitor.events().collect { visibility ->
                val input = if (visibility == AppVisibility.Foreground) {
                    ConnectionWatchInput.AppForeground
                } else {
                    ConnectionWatchInput.AppBackground
                }
                messages.trySend(WatchMessage.Input(input))
            }
        }
    }

    private fun applyInput(input: ConnectionWatchInput, probeCondition: NetworkCondition?) {
        val previous = synchronized(watchLock) { watchState }
        val decision = reduceConnectionWatch(previous, input)
        synchronized(watchLock) { watchState = decision.state }
        val publication = connectionWatchPublication(previous, input, decision, probeCondition)
        if (publication != null) {
            _uiState.value = NetworkStatusUiState(publication)
        }
        if (decision.cancelDebounce) cancelDebounce()
        if (decision.stopWatching) ConnectionPathMonitor.stop()
        if (decision.startWatching) {
            ConnectionPathMonitor.start { satisfied ->
                val next = if (satisfied) {
                    ConnectionWatchInput.PathSatisfied
                } else {
                    ConnectionWatchInput.PathUnsatisfied
                }
                messages.trySend(WatchMessage.Input(next))
            }
        }
        if (decision.armDebounce) armDebounce()
        if (decision.probeRequested) {
            enqueueProbe(
                force = false,
                confirmFailures = false,
                watchGeneration = decision.state.probeGeneration,
            )
        }
    }

    private fun onProbeSettled(message: WatchMessage.ProbeSettled) {
        val currentGeneration = synchronized(watchLock) { watchState.probeGeneration }
        if (message.startedGeneration != currentGeneration) return
        _uiState.value = NetworkStatusUiState(message.condition)
        val watchGeneration = message.watchGeneration ?: return
        if (watchGeneration != currentGeneration) return
        val input = if (message.condition == NetworkCondition.Online) {
            ConnectionWatchInput.ProbeOk
        } else {
            ConnectionWatchInput.ProbeFailed
        }
        applyInput(input, message.condition)
    }

    private fun armDebounce() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(ConnectionWatchDebounceMs)
            messages.trySend(WatchMessage.Input(ConnectionWatchInput.DebounceElapsed))
        }
    }

    private fun cancelDebounce() {
        debounceJob?.cancel()
        debounceJob = null
    }

    private fun enqueueProbe(
        force: Boolean,
        confirmFailures: Boolean,
        watchGeneration: Long?,
    ) {
        val launchGeneration = synchronized(probeLock) {
            val generationNow = synchronized(watchLock) { watchState.probeGeneration }
            if (watchGeneration != null) {
                if (probeLoopRunning && probeStartedGeneration != watchGeneration) {
                    pendingWatchRestart = true
                }
                queuedWatchGeneration = watchGeneration
            }
            if (confirmFailures) pendingConfirm = true
            if (force && probeLoopRunning) pendingForce = true
            if (probeLoopRunning) {
                null
            } else {
                probeLoopRunning = true
                probeLoopToken += 1
                probeStartedGeneration = generationNow
                generationNow
            }
        }
        if (launchGeneration != null) {
            val loopId = synchronized(probeLock) { probeLoopToken }
            scope.launch {
                try {
                    probeLoop(launchGeneration)
                } finally {
                    synchronized(probeLock) {
                        if (probeLoopToken == loopId) probeLoopRunning = false
                    }
                }
            }
        }
    }

    private suspend fun probeLoop(firstGeneration: Long) {
        var generation = firstGeneration
        while (true) {
            val ticket = synchronized(probeLock) {
                probeStartedGeneration = generation
                val queuedWatch = queuedWatchGeneration
                val watchForThisProbe = queuedWatch?.takeIf { it == generation }
                val mismatchedWatch = queuedWatch != null && queuedWatch != generation
                if (!mismatchedWatch) queuedWatchGeneration = null
                val confirm = pendingConfirm
                val explicitFollowUp = pendingForce
                pendingConfirm = false
                pendingForce = false
                pendingWatchRestart = false
                ProbeTicket(
                    watchGeneration = watchForThisProbe,
                    confirmFailures = confirm,
                    explicitFollowUp = explicitFollowUp,
                    restartAfter = mismatchedWatch,
                )
            }
            val watchOnly = ticket.watchGeneration != null && !ticket.confirmFailures && !ticket.explicitFollowUp
            if (!watchOnly) messages.trySend(WatchMessage.ProbeBegan)
            val timeoutMs = if (watchOnly) ConnectionWatchProbeTimeoutMs else REQUEST_TIMEOUT_MS
            val condition = if (watchOnly) {
                withTimeoutOrNull(timeoutMs) { runProbe(confirmFailures = false, timeoutMs = timeoutMs) }
                    ?: NetworkCondition.NoInternet
            } else {
                runProbe(confirmFailures = ticket.confirmFailures, timeoutMs = timeoutMs)
            }
            val deliverWatch = synchronized(probeLock) {
                val lateWatch = queuedWatchGeneration
                val matched = when {
                    ticket.watchGeneration == generation -> ticket.watchGeneration
                    lateWatch == generation -> lateWatch
                    else -> null
                }
                if (matched != null && queuedWatchGeneration == matched) {
                    queuedWatchGeneration = null
                }
                matched
            }
            messages.trySend(
                WatchMessage.ProbeSettled(
                    startedGeneration = generation,
                    condition = condition,
                    watchGeneration = deliverWatch,
                ),
            )
            val nextGeneration = synchronized(probeLock) {
                val queuedWatch = queuedWatchGeneration
                val again = ticket.explicitFollowUp ||
                    ticket.restartAfter ||
                    pendingForce ||
                    pendingWatchRestart ||
                    (queuedWatch != null && queuedWatch != generation)
                if (!again) {
                    probeLoopRunning = false
                    null
                } else {
                    synchronized(watchLock) { watchState.probeGeneration }
                }
            } ?: return
            generation = nextGeneration
        }
    }

    private suspend fun runProbe(confirmFailures: Boolean, timeoutMs: Long): NetworkCondition {
        val previousCondition = _uiState.value.condition
        var nextCondition = probeCondition(timeoutMs)
        if (
            confirmFailures &&
            previousCondition == NetworkCondition.Online &&
            nextCondition.isOfflineLike()
        ) {
            delay(FOREGROUND_FAILURE_CONFIRM_DELAY_MS)
            nextCondition = probeCondition(timeoutMs)
        }
        return nextCondition
    }

    private suspend fun probeCondition(timeoutMs: Long): NetworkCondition {
        val internetReachable = probePublicInternet(timeoutMs)
        if (!internetReachable) {
            return NetworkCondition.NoInternet
        }

        val supabaseReachable = SupabaseEndpointConfig.restEndpointUrls().any { url ->
            probeReachable(
                url = url,
                headers = mapOf("apikey" to ServerConfigurationRepository.active.value.publishableKey),
                timeoutMs = timeoutMs,
            )
        }
        if (!supabaseReachable) {
            return NetworkCondition.ServersUnreachable
        }

        return NetworkCondition.Online
    }

    private suspend fun probePublicInternet(timeoutMs: Long): Boolean =
        probeReachable(PUBLIC_PROBE_PRIMARY, timeoutMs = timeoutMs) ||
            probeReachable(PUBLIC_PROBE_FALLBACK, timeoutMs = timeoutMs)

    private suspend fun probeReachable(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long,
    ): Boolean {
        val response = withTimeoutOrNull(timeoutMs) {
            runCatching {
                httpRequestRaw(
                    method = "GET",
                    url = url,
                    headers = headers,
                    body = "",
                )
            }.getOrNull()
        } ?: return false

        return response.status in 100..599
    }

    private fun NetworkCondition.isOfflineLike(): Boolean =
        this == NetworkCondition.NoInternet || this == NetworkCondition.ServersUnreachable

    private data class ProbeTicket(
        val watchGeneration: Long?,
        val confirmFailures: Boolean,
        val explicitFollowUp: Boolean,
        val restartAfter: Boolean,
    )

    private sealed interface WatchMessage {
        data object ProbeBegan : WatchMessage

        data class Input(val input: ConnectionWatchInput) : WatchMessage

        data class ProbeSettled(
            val startedGeneration: Long,
            val condition: NetworkCondition,
            val watchGeneration: Long?,
        ) : WatchMessage
    }
}
