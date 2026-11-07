package com.nuvio.app.features.analysis

import com.nuvio.app.features.downloads.DownloadStatus

/** A thrown analysis is recorded with this reason and the queue continues. */
internal const val ANALYSIS_STOPPED_REASON = "Analysis stopped"

/** Only a finished file is analysed. A paused or partial download is left out of the queue. */
internal fun analysisAcceptsDownload(status: DownloadStatus): Boolean =
    status == DownloadStatus.Completed

/** Whole minutes since the current step started. The percent is not derived from this. */
internal fun analysisElapsedMinutes(sinceEpochMs: Long, nowEpochMs: Long): Int {
    if (sinceEpochMs <= 0L) return 0
    return ((nowEpochMs - sinceEpochMs).coerceAtLeast(0L) / 60_000L).toInt()
}

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

/** Unknown progress is NaN, and NaN is not equal to itself. That must not count as movement. */
internal fun analysisProgressMoved(previous: Double, progress: Double): Boolean =
    !progress.isNaN() && progress != previous
