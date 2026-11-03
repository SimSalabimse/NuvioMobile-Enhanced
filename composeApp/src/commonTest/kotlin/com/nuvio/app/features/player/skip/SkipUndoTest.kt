package com.nuvio.app.features.player.skip

import com.nuvio.app.features.stats.WatchStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkipUndoTest {
    @Test
    fun stateMachine() {
        val rows = listOf(
            "offer",
            "undo",
            "expire after 5s",
            "manual seek cancels",
            "episode change",
            "auto-skip undo not re-skipped",
            "party lock",
            "stats subtraction",
            "dismiss stays available",
        )
        assertEquals(existingSkipActions().map { it.name }, listOf("ManualSkip", "Dismiss", "AutoSkipToast"))
        assertTrue(skipUndoMotionSnaps(true), "reduce motion snaps")
        assertFalse(skipUndoMotionSnaps(false), "otherwise the pill animates")
        rows.forEach { row ->
            when (row) {
                "offer" -> checkOffer()
                "undo" -> checkUndo()
                "expire after 5s" -> checkExpire()
                "manual seek cancels" -> checkManualSeek()
                "episode change" -> checkEpisodeChange()
                "auto-skip undo not re-skipped" -> checkAutoSkipHold()
                "party lock" -> checkPartyLock()
                "stats subtraction" -> checkStats()
                "dismiss stays available" -> checkDismiss()
                else -> error(row)
            }
        }
    }

    private fun checkOffer() {
        val offered = SkipUndo.noteSegment(SkipUndoState.Hidden, 10_000L, 70_000L)
        val skipped = offerAt(offered, nowMs = 1_000L, fromMs = 12_000L, savedMs = 58_000L)
        assertTrue(skipped is SkipUndoState.Skipped, "offer")
        assertEquals(12_000L, skipped.fromMs, "offer from")
        assertEquals(1_000L + SKIP_UNDO_WINDOW_MS, skipped.untilMs, "offer until")
        assertTrue(SkipUndo.showsBack(skipped, nowMs = 1_000L, partyLocked = false), "offer shows Back")
    }

    private fun checkUndo() {
        val skipped = offerAt(SkipUndoState.Hidden, nowMs = 0L, fromMs = 12_000L, savedMs = 8_000L)
        val tap = SkipUndo.undo(skipped, nowMs = 4_999L)
        assertEquals(12_000L, tap.seekToMs, "undo seek")
        assertEquals(8_000L, tap.subtractMs, "undo subtract")
        assertTrue(tap.state is SkipUndoState.Undone, "undo state")
        val again = SkipUndo.undo(tap.state, nowMs = 4_999L)
        assertNull(again.seekToMs, "second undo")
        assertEquals(0L, again.subtractMs, "second undo stats")
    }

    private fun checkExpire() {
        val skipped = offerAt(SkipUndoState.Hidden, nowMs = 10_000L, fromMs = 4_000L, savedMs = 3_000L)
        assertTrue(SkipUndo.showsBack(skipped, nowMs = 14_999L, partyLocked = false), "inside window")
        assertFalse(SkipUndo.showsBack(skipped, nowMs = 15_000L, partyLocked = false), "at 5s")
        val expired = SkipUndo.tick(skipped, nowMs = 15_000L)
        assertEquals(SkipUndoState.Expired, expired, "expire")
        val late = SkipUndo.undo(skipped, nowMs = 15_000L)
        assertEquals(SkipUndoState.Expired, late.state, "late undo")
        assertNull(late.seekToMs, "late undo seek")
        val observed = SkipUndo.observe(
            state = skipped,
            nowMs = 15_000L,
            positionMs = 4_000L,
            partyLocked = false,
            manualSeek = null,
        )
        assertEquals(SkipUndoState.Expired, observed, "injected clock")
    }

    private fun checkManualSeek() {
        val skipped = offerAt(SkipUndoState.Hidden, nowMs = 0L, fromMs = 12_000L, savedMs = 1_000L, seen = null)
        val same = SkipUndo.onManualSeek(skipped, manualSeek = null, positionMs = 20_000L)
        assertEquals(skipped, same, "same seek does not cancel")
        val cancelled = SkipUndo.onManualSeek(skipped, manualSeek = 12_000L to 40_000L, positionMs = 40_000L)
        assertEquals(SkipUndoState.Hidden, cancelled, "manual seek")
        assertFalse(SkipUndo.showsBack(cancelled, nowMs = 1L, partyLocked = false), "Back hidden after seek")
    }

    private fun checkEpisodeChange() {
        val skipped = offerAt(SkipUndoState.Hidden, nowMs = 0L, fromMs = 12_000L, savedMs = 1_000L)
        assertEquals(SkipUndoState.Hidden, SkipUndo.onEpisodeChanged(), "episode")
        val undone = SkipUndo.undo(skipped, nowMs = 1L).state
        assertTrue(SkipUndo.blocksAutoSkip(undone, 12_000L))
        assertEquals(SkipUndoState.Hidden, SkipUndo.onEpisodeChanged(), "episode clears hold")
        assertFalse(SkipUndo.blocksAutoSkip(SkipUndo.onEpisodeChanged(), 12_000L))
    }

    private fun checkAutoSkipHold() {
        val skipped = offerAt(SkipUndoState.Hidden, nowMs = 0L, fromMs = 12_000L, savedMs = 50_000L)
        val undone = SkipUndo.undo(skipped, nowMs = 100L).state
        val landing = 80_000L
        assertTrue(landing > 70_000L, "landing is past the segment")
        assertTrue(SkipUndo.blocksAutoSkip(undone, landing), "hold before the seek lands")
        assertFalse(autoSkipWouldFire(undone, landing, alreadyMarked = false), "not re-skipped outside")
        val inside = SkipUndo.observe(undone, nowMs = 200L, positionMs = 12_000L, partyLocked = false, manualSeek = null)
        assertTrue(SkipUndo.blocksAutoSkip(inside, 12_000L), "hold inside the segment")
        assertFalse(autoSkipWouldFire(inside, 12_000L, alreadyMarked = false), "not re-skipped inside")
        val left = SkipUndo.observe(inside, nowMs = 8_000L, positionMs = 70_000L, partyLocked = false, manualSeek = null)
        assertEquals(SkipUndoState.Hidden, left, "playhead left")
        assertFalse(SkipUndo.blocksAutoSkip(left, 70_000L), "hold released")
    }

    private fun checkPartyLock() {
        val offered = SkipUndo.noteSegment(SkipUndoState.Hidden, 10_000L, 70_000L)
        val refused = offerAt(offered, nowMs = 0L, fromMs = 12_000L, savedMs = 1_000L, partyLocked = true)
        assertEquals(offered, refused, "party lock does not offer Back")
        assertFalse(SkipUndo.showsBack(refused, nowMs = 0L, partyLocked = true), "no Back while locked")
        val skipped = offerAt(offered, nowMs = 0L, fromMs = 12_000L, savedMs = 1_000L, partyLocked = false)
        val locked = SkipUndo.observe(
            state = skipped,
            nowMs = 500L,
            positionMs = 12_000L,
            partyLocked = true,
            manualSeek = null,
        )
        assertEquals(SkipUndoState.Hidden, locked, "lock hides an open Back")
        assertFalse(SkipUndo.showsBack(skipped, nowMs = 500L, partyLocked = true))
    }

    private fun checkStats() {
        assertEquals(28_000L, WatchStats.adjustSkippedMs(20_000L, 8_000L), "count")
        val skipped = offerAt(SkipUndoState.Hidden, nowMs = 0L, fromMs = 12_000L, savedMs = 8_000L)
        val tap = SkipUndo.undo(skipped, nowMs = 1L)
        assertEquals(12_000L, WatchStats.adjustSkippedMs(20_000L, -tap.subtractMs), "subtract the undone skip")
        assertEquals(0L, WatchStats.adjustSkippedMs(1_000L, -8_000L), "does not go below zero")
        assertNull(WatchStats.adjustSkippedMs(0L, 0L), "zero is not a skip")
        val max = 30 * 60_000L
        assertEquals(max, WatchStats.adjustSkippedMs(0L, max))
        assertNull(WatchStats.adjustSkippedMs(0L, max + 1L), "seek is not a skip")
        assertNull(WatchStats.adjustSkippedMs(5_000L, -(max + 1L)), "undo of a seek is ignored")
    }

    private fun checkDismiss() {
        val offered = SkipUndo.noteSegment(SkipUndoState.Hidden, 10_000L, 70_000L)
        val dismissed = SkipUndo.dismiss(offered)
        assertEquals(SkipUndoState.Hidden, dismissed, "dismiss")
        val skipped = offerAt(offered, nowMs = 0L, fromMs = 12_000L, savedMs = 1_000L)
        assertEquals(skipped, SkipUndo.dismiss(skipped), "dismiss does not remove Back")
        val after = offerAt(dismissed, nowMs = 0L, fromMs = 12_000L, savedMs = 1_000L)
        assertTrue(after is SkipUndoState.Skipped, "skip still offered after dismiss")
    }

    private fun offerAt(
        state: SkipUndoState,
        nowMs: Long,
        fromMs: Long,
        savedMs: Long,
        partyLocked: Boolean = false,
        seen: Pair<Long, Long>? = null,
    ): SkipUndoState = SkipUndo.offer(
        state = state,
        fromMs = fromMs,
        nowMs = nowMs,
        segmentStartMs = 10_000L,
        segmentEndMs = 70_000L,
        savedMs = savedMs,
        partyLocked = partyLocked,
        seenManualSeek = seen,
    )

    private fun autoSkipWouldFire(state: SkipUndoState, positionMs: Long, alreadyMarked: Boolean): Boolean =
        !alreadyMarked && !SkipUndo.blocksAutoSkip(state, positionMs)
}
