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

/**
 * One native decode at a time. Local work takes the lane from a reference read.
 * A second decode is refused while the lane is held, so two FFmpeg calls do not run together.
 */
internal class AnalysisLane {
    var holder: String? = null
        private set

    fun beginReference(): Boolean {
        if (holder != null) return false
        holder = "reference"
        return true
    }

    fun beginLocal(): Boolean {
        if (holder == "reference") holder = null
        if (holder != null) return false
        holder = "local"
        return true
    }

    fun finish() {
        holder = null
    }

    val referenceHeld: Boolean get() = holder == "reference"
}

internal enum class ReferenceDisposition { Preempted, Retry, GaveUp, Settled }

/** A preempted reference is not finished. An unread long window retries, then stops. It is not a success. */
internal fun referenceDisposition(
    attempt: SiblingClipAttempt,
    attemptsSoFar: Int,
    maxAttempts: Int,
): ReferenceDisposition = when (attempt) {
    SiblingClipAttempt.Preempted -> ReferenceDisposition.Preempted
    SiblingClipAttempt.Unavailable ->
        if (attemptsSoFar + 1 >= maxAttempts) ReferenceDisposition.GaveUp else ReferenceDisposition.Retry
    SiblingClipAttempt.Finished -> ReferenceDisposition.Settled
}

/** Unknown progress is NaN, and NaN is not equal to itself. That must not count as movement. */
internal fun analysisProgressMoved(previous: Double, progress: Double): Boolean =
    !progress.isNaN() && progress != previous
