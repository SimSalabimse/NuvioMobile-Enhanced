package com.nuvio.app.features.player.skip

/** How long the Back pill stays up after a skip. */
internal const val SKIP_UNDO_WINDOW_MS = 5_000L

/**
 * Undo for one skip.
 * Hidden: nothing pending.
 * Offered: a segment can be skipped; Back is not showing.
 * Skipped: the jump happened and Back is up until [SkipUndoState.Skipped.untilMs].
 * Undone: Back was used. Auto-skip must not take the same segment again until the playhead leaves it.
 * Expired: the five seconds passed with no tap.
 */
internal sealed interface SkipUndoState {
    data object Hidden : SkipUndoState

    data class Offered(
        val segmentStartMs: Long,
        val segmentEndMs: Long,
    ) : SkipUndoState

    data class Skipped(
        val fromMs: Long,
        val untilMs: Long,
        val segmentStartMs: Long,
        val segmentEndMs: Long,
        val savedMs: Long,
        val seenManualSeek: Pair<Long, Long>?,
    ) : SkipUndoState

    data class Undone(
        val fromMs: Long,
        val segmentStartMs: Long,
        val segmentEndMs: Long,
        val seenManualSeek: Pair<Long, Long>?,
        val armed: Boolean,
    ) : SkipUndoState

    data object Expired : SkipUndoState
}

internal data class SkipUndoTap(
    val state: SkipUndoState,
    val seekToMs: Long?,
    val subtractMs: Long,
)

/** Actions that already existed. Undo does not replace any of them. */
internal enum class ExistingSkipAction {
    ManualSkip,
    Dismiss,
    AutoSkipToast,
}

internal fun existingSkipActions(): List<ExistingSkipAction> = ExistingSkipAction.entries

/** Reduce Motion snaps the Back pill. Anything else uses the skip pill's fade and scale. */
internal fun skipUndoMotionSnaps(reduceMotion: Boolean): Boolean = reduceMotion

internal object SkipUndo {
    fun noteSegment(state: SkipUndoState, segmentStartMs: Long, segmentEndMs: Long): SkipUndoState =
        when (state) {
            is SkipUndoState.Hidden, is SkipUndoState.Expired, is SkipUndoState.Offered ->
                SkipUndoState.Offered(segmentStartMs, segmentEndMs)
            else -> state
        }

    /** Dismiss hides the skip pill. It does not take away a Back that is already up. */
    fun dismiss(state: SkipUndoState): SkipUndoState =
        if (state is SkipUndoState.Offered) SkipUndoState.Hidden else state

    fun offer(
        state: SkipUndoState,
        fromMs: Long,
        nowMs: Long,
        segmentStartMs: Long,
        segmentEndMs: Long,
        savedMs: Long,
        partyLocked: Boolean,
        seenManualSeek: Pair<Long, Long>?,
        windowMs: Long = SKIP_UNDO_WINDOW_MS,
    ): SkipUndoState {
        if (partyLocked) return if (state is SkipUndoState.Skipped) SkipUndoState.Hidden else state
        return SkipUndoState.Skipped(
            fromMs = fromMs,
            untilMs = nowMs + windowMs,
            segmentStartMs = segmentStartMs,
            segmentEndMs = segmentEndMs,
            savedMs = savedMs,
            seenManualSeek = seenManualSeek,
        )
    }

    fun undo(state: SkipUndoState, nowMs: Long): SkipUndoTap {
        val skipped = state as? SkipUndoState.Skipped ?: return SkipUndoTap(state, null, 0L)
        if (nowMs >= skipped.untilMs) return SkipUndoTap(SkipUndoState.Expired, null, 0L)
        return SkipUndoTap(
            state = SkipUndoState.Undone(
                fromMs = skipped.fromMs,
                segmentStartMs = skipped.segmentStartMs,
                segmentEndMs = skipped.segmentEndMs,
                seenManualSeek = skipped.seenManualSeek,
                armed = false,
            ),
            seekToMs = skipped.fromMs,
            subtractMs = skipped.savedMs,
        )
    }

    fun tick(state: SkipUndoState, nowMs: Long): SkipUndoState =
        if (state is SkipUndoState.Skipped && nowMs >= state.untilMs) SkipUndoState.Expired else state

    fun onEpisodeChanged(): SkipUndoState = SkipUndoState.Hidden

    fun onManualSeek(
        state: SkipUndoState,
        manualSeek: Pair<Long, Long>?,
        positionMs: Long,
    ): SkipUndoState = when (state) {
        is SkipUndoState.Skipped ->
            if (manualSeek != state.seenManualSeek) SkipUndoState.Hidden else state
        is SkipUndoState.Undone -> when {
            manualSeek == state.seenManualSeek -> state
            contains(state, positionMs) -> state.copy(seenManualSeek = manualSeek)
            else -> SkipUndoState.Hidden
        }
        else -> state
    }

    fun observe(
        state: SkipUndoState,
        nowMs: Long,
        positionMs: Long,
        partyLocked: Boolean,
        manualSeek: Pair<Long, Long>?,
    ): SkipUndoState {
        val gated = if (partyLocked && state is SkipUndoState.Skipped) SkipUndoState.Hidden else state
        val afterSeek = onManualSeek(gated, manualSeek, positionMs)
        val afterTime = tick(afterSeek, nowMs)
        val undone = afterTime as? SkipUndoState.Undone ?: return afterTime
        if (!undone.armed && contains(undone, positionMs)) return undone.copy(armed = true)
        if (undone.armed && !contains(undone, positionMs)) return SkipUndoState.Hidden
        return undone
    }

    /**
     * True while an undo is responsible for this segment, including the moment before the
     * seek back has landed. Auto-skip must not jump the same segment again in that window.
     */
    fun blocksAutoSkip(state: SkipUndoState, positionMs: Long): Boolean {
        val undone = state as? SkipUndoState.Undone ?: return false
        if (!undone.armed) return true
        return contains(undone, positionMs)
    }

    fun showsBack(state: SkipUndoState, nowMs: Long, partyLocked: Boolean): Boolean =
        !partyLocked && state is SkipUndoState.Skipped && nowMs < state.untilMs

    private fun contains(state: SkipUndoState.Undone, positionMs: Long): Boolean =
        positionMs >= state.segmentStartMs && positionMs < state.segmentEndMs
}
