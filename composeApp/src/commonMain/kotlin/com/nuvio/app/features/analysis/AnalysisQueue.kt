package com.nuvio.app.features.analysis

/** A matching pass that has not returned. Past this, the row shows an error and the queue moves on. */
internal const val ANALYSIS_MATCH_LIMIT_MS = 60_000L

/** A reference-clip read that has not returned. */
internal const val ANALYSIS_CLIP_LIMIT_MS = 90_000L

/** Native decode or previews whose reported progress has not changed. */
internal const val ANALYSIS_PROGRESS_STALL_MS = 120_000L

internal const val ANALYSIS_STOPPED_REASON = "Analysis stopped"
internal const val ANALYSIS_MATCH_STALL_REASON = "Matching did not finish"
internal const val ANALYSIS_CLIP_STALL_REASON = "The reference clip did not finish"
internal const val ANALYSIS_PROGRESS_STALL_REASON = "Analysis stopped making progress"

/**
 * The next file after [finishedId] leaves the running slot.
 * [finishedId] null means the current file did not finish and is already in [failedIds] or [backingOffIds].
 */
internal fun nextQueuedAnalysisId(
    orderedIds: List<String>,
    finishedId: String?,
    failedIds: Set<String>,
    backingOffIds: Set<String>,
): String? {
    val index = finishedId?.let(orderedIds::indexOf) ?: -1
    val start = if (index < 0) 0 else index + 1
    return orderedIds.drop(start).firstOrNull { id ->
        id !in failedIds && id !in backingOffIds
    }
}

/** True when a stage has shown the same progress for [limitMs]. */
internal fun analysisStageIsStale(lastChangeMs: Long, nowMs: Long, limitMs: Long): Boolean =
    limitMs > 0L && nowMs - lastChangeMs >= limitMs

/** Unknown progress is NaN, and NaN is not equal to itself. That must not count as movement. */
internal fun analysisProgressMoved(previous: Double, progress: Double): Boolean =
    !progress.isNaN() && progress != previous
