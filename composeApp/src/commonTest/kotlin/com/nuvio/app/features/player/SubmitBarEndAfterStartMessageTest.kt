package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "End must be after the start" is a display rule. Submit stays disabled
 * whenever the clocks are invalid, including a fresh 0:00:00 tab.
 */
class SubmitBarEndAfterStartMessageTest {
    @Test
    fun messageShowsOnlyAfterAnEditLeavesEndAtOrBeforeStart() {
        val duration = 3_600.0
        val untouched = emptySet<String>()
        assertUntouchedZero(untouched, "intro", duration)

        val editedIntro = untouched + markSegmentId("intro")
        assertTouchedInvalid(editedIntro, "intro", 0.0, 0.0, duration)
        assertUntouchedZero(editedIntro, "recap", duration)
        assertTouchedInvalid(editedIntro, "intro", 12.0, 12.0, duration)

        assertTouchedValid(editedIntro, "intro", 0.0, 30.0, duration)
    }

    private fun touched(edited: Set<String>, segment: String): Boolean =
        markSegmentId(segment) in edited

    private fun assertUntouchedZero(edited: Set<String>, segment: String, duration: Double) {
        val status = markRangeStatus(segment, 0.0, 0.0, duration)
        assertFalse(showEndAfterStartMessage(touched(edited, segment), status.problem))
        assertFalse(submitEnabled(status))
    }

    private fun assertTouchedInvalid(
        edited: Set<String>,
        segment: String,
        start: Double,
        end: Double,
        duration: Double,
    ) {
        val status = markRangeStatus(segment, start, end, duration)
        assertTrue(showEndAfterStartMessage(touched(edited, segment), status.problem))
        assertFalse(submitEnabled(status))
    }

    private fun assertTouchedValid(
        edited: Set<String>,
        segment: String,
        start: Double,
        end: Double,
        duration: Double,
    ) {
        val status = markRangeStatus(segment, start, end, duration)
        assertFalse(showEndAfterStartMessage(touched(edited, segment), status.problem))
        assertTrue(submitEnabled(status))
    }

    private fun submitEnabled(status: MarkRangeStatus): Boolean = markSubmitEnabled(
        sent = false,
        submitting = false,
        keyProblem = false,
        blocked = false,
        absent = false,
        absentAllowed = true,
        timeOk = status.bothSet && status.problem == null,
    )
}
