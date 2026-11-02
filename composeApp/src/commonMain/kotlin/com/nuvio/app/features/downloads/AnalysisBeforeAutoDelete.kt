package com.nuvio.app.features.downloads

import com.nuvio.app.features.analysis.MediaAnalysisResult

/** Automatic deletion waits this long for a fingerprint, then deletes anyway. */
internal const val ANALYSIS_HOLD_CAP_MS = 30L * 60L * 1000L

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
