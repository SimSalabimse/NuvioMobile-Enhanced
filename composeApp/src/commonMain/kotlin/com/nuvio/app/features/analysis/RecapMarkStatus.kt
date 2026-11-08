package com.nuvio.app.features.analysis

import com.nuvio.app.features.player.sameCut

/**
 * A same-cut recap mark is Ready even when the stored end time was not copied yet.
 * Movies have no recap chip.
 */
internal fun recapAnalysisPart(
    recapEnabled: Boolean,
    isEpisode: Boolean,
    derivedCurrent: Boolean,
    recapEndMs: Long?,
    userRecap: UserSegment?,
    durationMs: Long,
    siblingsEmpty: Boolean,
): AnalysisPart {
    if (!recapEnabled) return AnalysisPart.Off
    if (!isEpisode) return AnalysisPart.NotFound
    if (!derivedCurrent) return AnalysisPart.Pending
    val markKept = userRecap != null &&
        userRecap.endMs > userRecap.startMs &&
        sameCut(userRecap.durationMs, durationMs)
    if (recapEndMs != null || markKept) return AnalysisPart.Ready
    if (siblingsEmpty) return AnalysisPart.NeedsMoreEpisodes
    return AnalysisPart.NotFound
}
