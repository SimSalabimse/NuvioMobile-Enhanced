package com.nuvio.app.features.analysis

import com.nuvio.app.features.player.sameCut

/**
 * Outcomes that mean the recap search ran and accepted nothing.
 * Anything else, including a missing token, was not a completed "none".
 */
internal val RECAP_CHECKED_NONE: Set<String> = setOf(
    "rejected-no-match",
    "rejected-short",
    "rejected-agreement",
    "rejected-dialog-overlay",
    "rejected-no-previous",
    "rejected-audio-miss-prior-unused",
)

/** The sentence on the recap chip. [NoRecapFound] is only a completed search. */
internal enum class RecapChipSentence {
    Ready,
    NoRecapFound,
    YouMarkedNone,
    NotChecked,
    Other,
}

/** The chip sentence. A user "none" is [YouMarkedNone], never the detector's miss. */
internal fun recapChipSentence(part: AnalysisPart): RecapChipSentence = when (part) {
    AnalysisPart.Ready -> RecapChipSentence.Ready
    AnalysisPart.NotFound -> RecapChipSentence.NoRecapFound
    AnalysisPart.UserMarkedNone -> RecapChipSentence.YouMarkedNone
    AnalysisPart.NotChecked -> RecapChipSentence.NotChecked
    else -> RecapChipSentence.Other
}

internal fun recapOutcomeToStore(decisions: List<SegmentDecision>): String? =
    decisions.lastOrNull { it.type == "recap" }?.why

/**
 * Intro and credits tokens that mean the search ran and accepted nothing.
 * A missing token, a cancel, or a failure is not in this set.
 */
internal val INTRO_CREDITS_CHECKED_NONE: Set<String> = setOf(
    "rejected-no-cluster",
    "rejected-cold-open",
    "rejected-dialog-overlay",
    "rejected-opening-credits",
)

/**
 * Token written with the analysis.
 * A finished pass keeps the recorded token, or the previous one when this pass recorded none.
 * A cancelled or failed pass that recorded nothing drops a previous completed miss,
 * so a stopped pass cannot keep claiming "none found".
 */
internal fun segmentOutcomeToPersist(
    previous: String?,
    recorded: String?,
    finished: Boolean,
    checkedNone: Set<String>,
): String? {
    if (recorded != null) return recorded
    if (!finished && previous in checkedNone) return null
    return previous
}

internal fun recapOutcomeToPersist(
    previous: String?,
    decisions: List<SegmentDecision>,
    finished: Boolean,
): String? = segmentOutcomeToPersist(
    previous = previous,
    recorded = recapOutcomeToStore(decisions),
    finished = finished,
    checkedNone = RECAP_CHECKED_NONE,
)

internal fun introCreditsOutcomeToPersist(
    type: String,
    previous: String?,
    decisions: List<SegmentDecision>,
    finished: Boolean,
): String? = segmentOutcomeToPersist(
    previous = previous,
    recorded = decisions.lastOrNull { it.type == type }?.why,
    finished = finished,
    checkedNone = INTRO_CREDITS_CHECKED_NONE,
)

/**
 * A same-cut recap mark is Ready even when the stored end time was not copied yet.
 * A mark for another cut is not "no recap found".
 * Movies have no recap chip.
 * [outcome] is the stored token from the last pass. Null means that pass did not record one.
 */
internal fun recapAnalysisPart(
    recapEnabled: Boolean,
    isEpisode: Boolean,
    derivedCurrent: Boolean,
    recapEndMs: Long?,
    userRecap: UserSegment?,
    durationMs: Long,
    siblingsEmpty: Boolean,
    outcome: String? = null,
): AnalysisPart {
    if (!recapEnabled) return AnalysisPart.Off
    if (!isEpisode) return AnalysisPart.NotFound
    val mark = userRecap
    if (mark != null && mark.absent && sameCut(mark.durationMs, durationMs)) {
        return AnalysisPart.UserMarkedNone
    }
    if (!derivedCurrent) return AnalysisPart.Pending
    val markRange = mark != null && mark.endMs > mark.startMs
    val markKept = mark != null && markRange && sameCut(mark.durationMs, durationMs)
    if (recapEndMs != null || markKept) return AnalysisPart.Ready
    if (mark != null && markRange && !sameCut(mark.durationMs, durationMs)) {
        return AnalysisPart.NotChecked
    }
    if (siblingsEmpty) return AnalysisPart.NeedsMoreEpisodes
    return if (outcome in RECAP_CHECKED_NONE) AnalysisPart.NotFound else AnalysisPart.NotChecked
}
