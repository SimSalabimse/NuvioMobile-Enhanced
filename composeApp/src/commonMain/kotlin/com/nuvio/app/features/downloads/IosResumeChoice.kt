package com.nuvio.app.features.downloads

/**
 * How iOS continues one download when the app opens, or when a resume blob fails.
 * Start-at-0 is legal only when `.part` is empty and the background session has no live task.
 */
internal enum class IosResumeAction {
    Reattach,
    RangeFromPrefix,
    StayPaused,
    StartAtZero,
}

/**
 * @param userPaused the person paused this download. Opening the app must not resume it.
 * @param systemTaskStillRunning a task for this file is still in `com.nuvio.app.downloads`.
 * @param partLength bytes already in the `.part` file. Not the NSURLSession temp file.
 * @param resumeBlobExists a resume blob is on disk. A missing blob uses [partLength].
 * @param resumeBlobFailed the blob was rejected. A failed blob also uses [partLength].
 */
internal data class IosResumeSignals(
    val userPaused: Boolean,
    val systemTaskStillRunning: Boolean,
    val partLength: Long,
    val resumeBlobExists: Boolean,
    val resumeBlobFailed: Boolean = false,
)

internal fun chooseIosResume(signals: IosResumeSignals): IosResumeAction {
    if (signals.userPaused) return IosResumeAction.StayPaused
    if (signals.systemTaskStillRunning) return IosResumeAction.Reattach
    // Missing and failed blobs both continue from the prefix. Neither may use byte 0
    // while that prefix still has data, and a usable blob does not either: the `.part`
    // file is the copy that survives the session temp file being dropped.
    if (signals.partLength > 0L) return IosResumeAction.RangeFromPrefix
    return IosResumeAction.StartAtZero
}

/** Bytes to keep after a cold open. A prefix still on disk is never shown as zero. */
internal fun resumedByteCount(storedBytes: Long, partLength: Long): Long =
    maxOf(storedBytes.coerceAtLeast(0L), partLength.coerceAtLeast(0L))

/** What to do with `.part` when the ranged request's response arrives. */
internal enum class PartialPrefixDisposition {
    /** A ranged 200 must not wipe the bytes already in `.part`. */
    Keep,
    Append,
    Replace,
}

internal fun partialPrefixDisposition(
    statusCode: Int,
    requestedRange: Long,
    prefixLength: Long,
): PartialPrefixDisposition {
    if (statusCode == 200 && requestedRange > 0L && prefixLength > 0L) {
        return PartialPrefixDisposition.Keep
    }
    if (statusCode == 206 && requestedRange > 0L) {
        return PartialPrefixDisposition.Append
    }
    return PartialPrefixDisposition.Replace
}
