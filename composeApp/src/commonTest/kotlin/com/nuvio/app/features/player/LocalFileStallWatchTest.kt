package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalFileStallWatchTest {
    @Test
    fun notYetOpened_doesNotRecover() {
        assertEquals(
            LocalFileStallDecision.Wait,
            localFileStallDecision(
                phase = LocalFileOpenPhase.NotYetOpened,
                elapsedSinceOpenMs = LocalFileOpenTimeoutMs,
                elapsedAtSamePositionMs = LocalFileStallThresholdMs,
            ),
        )
    }

    @Test
    fun openedNoTimePos_doesNotRecover() {
        assertEquals(
            LocalFileStallDecision.Wait,
            localFileStallDecision(
                phase = LocalFileOpenPhase.OpenedNoTimePos,
                elapsedSinceOpenMs = LocalFileStallThresholdMs,
                elapsedAtSamePositionMs = LocalFileStallThresholdMs,
            ),
        )
    }

    @Test
    fun timePosValidThenStalled_recovers() {
        assertEquals(
            LocalFileStallDecision.Recover,
            localFileStallDecision(
                phase = LocalFileOpenPhase.TimePosValid,
                elapsedSinceOpenMs = LocalFileStallThresholdMs,
                elapsedAtSamePositionMs = LocalFileStallThresholdMs,
            ),
        )
    }

    @Test
    fun stalledBeforeValid_waitsUntilTheOpenTimeout() {
        assertEquals(
            LocalFileStallDecision.Wait,
            localFileStallDecision(
                phase = LocalFileOpenPhase.OpenedNoTimePos,
                elapsedSinceOpenMs = LocalFileOpenTimeoutMs - 1L,
                elapsedAtSamePositionMs = LocalFileStallThresholdMs,
            ),
        )
        assertEquals(
            LocalFileStallDecision.Recover,
            localFileStallDecision(
                phase = LocalFileOpenPhase.OpenedNoTimePos,
                elapsedSinceOpenMs = LocalFileOpenTimeoutMs,
                elapsedAtSamePositionMs = LocalFileStallThresholdMs,
            ),
        )
    }
}
