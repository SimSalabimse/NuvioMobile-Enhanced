package com.nuvio.app.features.downloads

import com.nuvio.app.features.analysis.MediaAnalysisResult

/** Automatic deletion waits this long for a fingerprint, then deletes anyway. */
internal const val ANALYSIS_HOLD_CAP_MS = 30L * 60L * 1000L

/** How often a hold rechecks while the clock is frozen or still counting. */
internal const val ANALYSIS_HOLD_RECHECK_MS = 60_000L

internal data class HoldClock(
    val eligibleMs: Long = 0L,
    val accountedUntilEpochMs: Long? = null,
    val frozen: Boolean = false,
)

/** Charger-only analysis that cannot run because the device is unplugged does not spend the cap. */
internal fun analysisHoldClockFrozen(onlyWhileCharging: Boolean, deviceCharging: Boolean): Boolean =
    onlyWhileCharging && !deviceCharging

/**
 * Adds time since the last check only when that stretch was not frozen.
 * The first check, before a hold exists, records the clock and adds nothing.
 */
internal fun accountHoldClock(
    clock: HoldClock,
    nowMs: Long,
    frozenNow: Boolean,
    heldSinceEpochMs: Long?,
): HoldClock {
    if (heldSinceEpochMs == null) {
        return HoldClock(eligibleMs = 0L, accountedUntilEpochMs = nowMs, frozen = frozenNow)
    }
    val until = clock.accountedUntilEpochMs ?: heldSinceEpochMs
    val delta = (nowMs - until).coerceAtLeast(0L)
    val eligible = clock.eligibleMs + if (clock.frozen) 0L else delta
    return HoldClock(eligibleMs = eligible, accountedUntilEpochMs = nowMs, frozen = frozenNow)
}

internal fun MediaAnalysisResult?.hasStoredFingerprint(): Boolean =
    this != null && (!headFingerprint.isNullOrEmpty() || !tailFingerprint.isNullOrEmpty())

/**
 * True when an automatic delete must keep the file so intro matching can store a fingerprint.
 * Android (and any build without an analyser) is never held: nothing would ever be saved.
 */
internal fun needsAnalysisBeforeAutoDelete(
    item: DownloadItem,
    storedAnalysis: MediaAnalysisResult?,
    introDetectionEnabled: Boolean,
    analyserSupported: Boolean,
): Boolean {
    if (!analyserSupported || !introDetectionEnabled) return false
    if (item.status != DownloadStatus.Completed) return false
    if (!item.isEpisode) return false
    if (storedAnalysis.hasStoredFingerprint()) return false
    return true
}

/** Manual deletes, a spent attempt budget, and a hold past [holdCapMs] all delete now. */
internal fun shouldHoldAutomaticDeletion(
    item: DownloadItem,
    storedAnalysis: MediaAnalysisResult?,
    introDetectionEnabled: Boolean,
    analyserSupported: Boolean,
    automatic: Boolean,
    heldForMs: Long,
    holdCapMs: Long = ANALYSIS_HOLD_CAP_MS,
    attemptsExhausted: Boolean = false,
): Boolean {
    if (!automatic || attemptsExhausted) return false
    if (heldForMs >= holdCapMs) return false
    return needsAnalysisBeforeAutoDelete(item, storedAnalysis, introDetectionEnabled, analyserSupported)
}
