package com.nuvio.app.features.analysis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A delay that [kick] ends at once. Used so kick(0) does not wait out a poll sleep. */
internal class AnalysisDelayWake {
    private val generation = MutableStateFlow(0)

    fun kick() {
        generation.value = generation.value + 1
    }

    suspend fun sleep(ms: Long) {
        if (ms <= 0L) return
        val start = generation.value
        withTimeoutOrNull(ms) {
            generation.first { it != start }
        }
    }
}
