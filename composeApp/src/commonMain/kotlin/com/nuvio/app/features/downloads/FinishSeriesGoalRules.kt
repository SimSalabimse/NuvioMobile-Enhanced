package com.nuvio.app.features.downloads

import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration
import com.nuvio.app.features.watchprogress.isWatchProgressComplete

/** What to do with a Finish-a-series pin when an episode is near its end, or nothing is left to queue. */
internal enum class FinishSeriesGoalAction {
    /** Not the last episode: move the anchor forward and keep the goal. */
    AdvanceAnchor,

    /** Last episode is still in progress: leave the pin where it is. */
    KeepPinned,

    /** The series is actually finished: unpin and tell the user. */
    RemoveAndToast,
}

internal data class FinishSeriesGoalDecision(
    val action: FinishSeriesGoalAction,
    /** Why the pin came off. Logged; never a url, token, or debrid link. */
    val removalReason: String? = null,
    /** Chip text while the last episode is still playing. */
    val statusText: String? = null,
)

internal const val FINISHING_LAST_EPISODE_STATUS = "Finishing last episode"

/**
 * True when a resume row still has playback left. Position is checked against the app's completion
 * rule (90 %). A watched-history flag (Trakt / Simkl / MDBList at 80 %) does not count as finished
 * while the saved position is still short of that rule.
 */
internal fun resumeStillInProgress(
    positionMs: Long,
    durationMs: Long,
    markedCompleted: Boolean,
): Boolean {
    if (positionMs <= 0L) return false
    if (durationMs > 0L && !isShortPlaceholderDuration(durationMs)) {
        return !isWatchProgressComplete(positionMs, durationMs, isEnded = false)
    }
    return !markedCompleted
}

/**
 * Goal decision for one check.
 *
 * [playingEpisodeIndex] is set when the player just hit the near-end trigger. The last regular
 * episode stays pinned until [playbackReachedEnd] or [playbackCompleted]. Any earlier episode
 * advances the anchor, same as before.
 *
 * [playingEpisodeIndex] is null for a catalog check (launch, pin, settings). When [nextEpisodeIndex]
 * is past the end, an unfinished resume on the last episode keeps the pin.
 */
internal fun decideFinishSeriesGoal(
    regularEpisodeCount: Int,
    playingEpisodeIndex: Int?,
    nextEpisodeIndex: Int,
    lastEpisodePositionMs: Long,
    lastEpisodeDurationMs: Long,
    lastEpisodeMarkedCompleted: Boolean,
    playbackReachedEnd: Boolean,
    playbackCompleted: Boolean,
): FinishSeriesGoalDecision {
    if (regularEpisodeCount <= 0) {
        return remove("no regular episodes left")
    }
    val finalIndex = regularEpisodeCount - 1
    if (playingEpisodeIndex != null && playingEpisodeIndex != finalIndex) {
        return FinishSeriesGoalDecision(FinishSeriesGoalAction.AdvanceAnchor)
    }
    if (playingEpisodeIndex == finalIndex) {
        if (playbackReachedEnd || playbackCompleted) {
            val why = when {
                playbackReachedEnd && playbackCompleted ->
                    "playback reached the end and the episode is completed"
                playbackReachedEnd -> "playback reached the end"
                else -> "episode completed by the playback rule"
            }
            return remove("last episode finished: $why")
        }
        return keep()
    }
    if (nextEpisodeIndex >= regularEpisodeCount) {
        val inProgress = resumeStillInProgress(
            positionMs = lastEpisodePositionMs,
            durationMs = lastEpisodeDurationMs,
            markedCompleted = lastEpisodeMarkedCompleted,
        )
        if (inProgress) return keep()
        val why = if (lastEpisodePositionMs > 0L) {
            "nothing left to download and the last episode resume is completed"
        } else {
            "nothing left to download and the last episode has no in-progress resume"
        }
        return remove(why)
    }
    return FinishSeriesGoalDecision(FinishSeriesGoalAction.AdvanceAnchor)
}

private fun keep(): FinishSeriesGoalDecision = FinishSeriesGoalDecision(
    action = FinishSeriesGoalAction.KeepPinned,
    statusText = FINISHING_LAST_EPISODE_STATUS,
)

private fun remove(reason: String): FinishSeriesGoalDecision = FinishSeriesGoalDecision(
    action = FinishSeriesGoalAction.RemoveAndToast,
    removalReason = reason,
)
