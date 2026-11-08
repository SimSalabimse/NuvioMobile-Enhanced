package com.nuvio.app.features.analysis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AnalysisWaitSnapshot(
    val reason: String,
    val elapsedMs: Long,
)

/**
 * One active wait. Entering a new reason releases the previous one with its elapsed time.
 * The lines are the evidence for a gap: charger, playback, retry-wait, download, or app-background.
 */
internal class AnalysisWaitTracker {
    private var reason: String? = null
    private var enteredAtMs: Long = 0L

    fun enter(reason: String, nowMs: Long): List<String> {
        if (this.reason == reason) return emptyList()
        val released = release(nowMs)
        this.reason = reason
        enteredAtMs = nowMs
        return listOfNotNull(released, "entered reason=$reason")
    }

    fun release(nowMs: Long): String? {
        val active = reason ?: return null
        val elapsed = (nowMs - enteredAtMs).coerceAtLeast(0L)
        reason = null
        return "released reason=$active elapsedMs=$elapsed"
    }

    fun snapshot(nowMs: Long): AnalysisWaitSnapshot? {
        val active = reason ?: return null
        return AnalysisWaitSnapshot(active, (nowMs - enteredAtMs).coerceAtLeast(0L))
    }
}

internal object AnalysisWaitStatus {
    private val _snapshot = MutableStateFlow<AnalysisWaitSnapshot?>(null)
    val snapshot: StateFlow<AnalysisWaitSnapshot?> = _snapshot.asStateFlow()

    fun show(reason: String, elapsedMs: Long) {
        _snapshot.value = AnalysisWaitSnapshot(reason, elapsedMs.coerceAtLeast(0L))
    }

    fun clear() {
        _snapshot.value = null
    }
}

internal fun analysisWaitClock(elapsedMs: Long): String {
    val seconds = (elapsedMs / 1_000L).coerceAtLeast(0L)
    return "${seconds}s"
}
