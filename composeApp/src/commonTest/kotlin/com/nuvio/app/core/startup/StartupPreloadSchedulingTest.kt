package com.nuvio.app.core.startup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * The iOS and Android preload share this scheduler. Virtual time checks the shape:
 * the tracking lane stays ordered, and independent steps overlap only when parallel is on.
 */
class StartupPreloadSchedulingTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun parallelStartupOverlapsIndependentStepsAndSerialDoesNot() = runTest {
        val pause = 50.milliseconds
        val tracking = List(3) { suspend { delay(pause) } }
        val independent = List(6) { suspend { delay(pause) } }

        suspend fun measure(parallel: Boolean): Long {
            val started = currentTime
            runStartupPreloadSteps(
                parallel = parallel,
                tracking = tracking,
                independent = independent,
            )
            return currentTime - started
        }

        val serialMs = measure(parallel = false)
        val parallelMs = measure(parallel = true)

        // 9 steps in order: 3 tracking + 6 independent.
        assertEquals(450L, serialMs)
        // Tracking stays one lane (3 x 50). The independent steps overlap that lane.
        assertEquals(150L, parallelMs)
    }
}
