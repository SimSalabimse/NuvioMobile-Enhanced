package com.nuvio.app.features.analysis

import com.nuvio.app.features.downloads.hasStoredFingerprint

internal const val ANALYSIS_MAX_ATTEMPTS = 3
private const val ANALYSIS_RETRY_DELAY_MS = 2 * 60_000L

internal enum class AnalysisAttemptKind {
    Succeeded,
    /** Analyser failure, or a sibling clip that could not be read. */
    Retryable,
    /** User cancel or [MediaAnalysisScheduler.releaseFile]. The stored count stays. */
    Cancelled,
}

internal fun classifyAnalysisAttempt(
    scopeActive: Boolean,
    analyserFailed: Boolean,
    clipUnavailable: Boolean,
): AnalysisAttemptKind {
    if (!scopeActive) return AnalysisAttemptKind.Cancelled
    if (analyserFailed || clipUnavailable) return AnalysisAttemptKind.Retryable
    return AnalysisAttemptKind.Succeeded
}

/** 1 -> 2 minutes, 2 -> 4 minutes. Nothing after the last try. */
internal fun analysisRetryDelayMs(attempt: Int): Long? = when (attempt) {
    1 -> ANALYSIS_RETRY_DELAY_MS
    2 -> ANALYSIS_RETRY_DELAY_MS * 2
    else -> null
}

/** A different download file name starts the budget over. */
internal fun resolveAnalysisAttempts(stored: MediaAnalysisResult?, fileName: String?): Int {
    if (stored == null || fileName == null || stored.fileName != fileName) return 0
    return stored.analysisAttempts
}

internal data class AttemptUpdate(
    val attempts: Int,
    val retryNotBeforeMs: Long?,
    val giveUp: Boolean,
    /** False when a cancel must leave the stored count untouched. */
    val write: Boolean,
)

internal fun updateAnalysisAttempts(prior: Int, kind: AnalysisAttemptKind, nowMs: Long): AttemptUpdate = when (kind) {
    AnalysisAttemptKind.Cancelled -> AttemptUpdate(prior, retryNotBeforeMs = null, giveUp = false, write = false)
    AnalysisAttemptKind.Succeeded -> AttemptUpdate(0, retryNotBeforeMs = null, giveUp = false, write = prior != 0)
    AnalysisAttemptKind.Retryable -> {
        val attempts = (prior + 1).coerceAtMost(ANALYSIS_MAX_ATTEMPTS)
        val delay = analysisRetryDelayMs(attempts)
        AttemptUpdate(
            attempts = attempts,
            retryNotBeforeMs = delay?.let { nowMs + it },
            giveUp = attempts >= ANALYSIS_MAX_ATTEMPTS,
            write = true,
        )
    }
}

/**
 * True when this completed file should be analysed on the next pass.
 * [MediaAnalysisBackground.appDidLaunch] reaches this through [MediaAnalysisScheduler.kick]:
 * a retryable attempt with no in-memory backoff is queued again.
 */
internal fun shouldQueueAnalysis(
    stored: MediaAnalysisResult?,
    fileName: String?,
    needsDerivedWork: Boolean,
    backingOff: Boolean,
): Boolean {
    if (backingOff) return false
    if (stored != null && stored.fileName == fileName && stored.analysisAttempts >= ANALYSIS_MAX_ATTEMPTS) return false
    if (stored == null || stored.fileName != fileName) return true
    if (stored.analysisAttempts in 1 until ANALYSIS_MAX_ATTEMPTS) return true
    return needsDerivedWork
}

/** Fingerprint is stored, intro and credits are still missing, and a clip try can still run. */
internal fun MediaAnalysisResult.needsSiblingClipRetry(): Boolean =
    analysisAttempts in 1 until ANALYSIS_MAX_ATTEMPTS &&
        hasStoredFingerprint() &&
        introEndMs == null &&
        creditsStartMs == null
