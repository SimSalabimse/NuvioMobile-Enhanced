package com.nuvio.app.features.player

/** A local file may sit without a time-pos while a large download is opened. */
internal const val LocalFileOpenTimeoutMs = 60_000L

internal const val LocalFileStallThresholdMs = 5_000L

internal enum class LocalFileOpenPhase {
    NotYetOpened,
    OpenedNoTimePos,
    TimePosValid,
}

internal enum class LocalFileStallDecision {
    Wait,
    Recover,
}

/**
 * Stall recovery stays quiet until a valid time-pos has been seen for this open,
 * or until [LocalFileOpenTimeoutMs], whichever comes first.
 */
internal fun localFileStallDecision(
    phase: LocalFileOpenPhase,
    elapsedSinceOpenMs: Long,
    elapsedAtSamePositionMs: Long,
    openTimeoutMs: Long = LocalFileOpenTimeoutMs,
    stallThresholdMs: Long = LocalFileStallThresholdMs,
): LocalFileStallDecision = when (phase) {
    LocalFileOpenPhase.NotYetOpened -> LocalFileStallDecision.Wait
    LocalFileOpenPhase.OpenedNoTimePos ->
        if (elapsedSinceOpenMs >= openTimeoutMs) {
            LocalFileStallDecision.Recover
        } else {
            LocalFileStallDecision.Wait
        }
    LocalFileOpenPhase.TimePosValid ->
        if (elapsedAtSamePositionMs >= stallThresholdMs) {
            LocalFileStallDecision.Recover
        } else {
            LocalFileStallDecision.Wait
        }
}
