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
    NotChecked,
    Other,
}

internal fun recapChipSentence(part: AnalysisPart): RecapChipSentence = when (part) {
    AnalysisPart.Ready -> RecapChipSentence.Ready
    AnalysisPart.NotFound -> RecapChipSentence.NoRecapFound
    AnalysisPart.NotChecked -> RecapChipSentence.NotChecked
    else -> RecapChipSentence.Other
}

internal fun recapOutcomeToStore(decisions: List<SegmentDecision>): String? =
    decisions.lastOrNull { it.type == "recap" }?.why

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
    if (!derivedCurrent) return AnalysisPart.Pending
    val mark = userRecap
    val markRange = mark != null && mark.endMs > mark.startMs
    val markKept = mark != null && markRange && sameCut(mark.durationMs, durationMs)
    if (recapEndMs != null || markKept) return AnalysisPart.Ready
    if (mark != null && markRange && !sameCut(mark.durationMs, durationMs)) {
        return AnalysisPart.NotChecked
    }
    if (siblingsEmpty) return AnalysisPart.NeedsMoreEpisodes
    return if (outcome in RECAP_CHECKED_NONE) AnalysisPart.NotFound else AnalysisPart.NotChecked
}
