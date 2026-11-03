package com.nuvio.app.features.analysis

import com.nuvio.app.features.downloads.hasStoredFingerprint

/** How many other stored fingerprints one episode is matched against. The episodes updated are not capped. */
internal const val SEASON_PASS_REFERENCE_CAP = 16

/**
 * After a fingerprint is saved, fill intro, credits, and recap on every stored episode of that
 * season that still needs them. Uses stored fingerprints only: nothing is opened or downloaded,
 * so an episode whose file is already deleted is included.
 * A stored intro or credits time is replaced only when the new score is strictly higher and the
 * user has not marked that segment. User marks are applied by [MediaAnalysisStore.save].
 */
internal fun backfillSeasonOnFingerprintSave(
    saved: MediaAnalysisResult,
    stored: List<MediaAnalysisResult>,
    detectRecap: Boolean = true,
    referenceCap: Int = SEASON_PASS_REFERENCE_CAP,
): List<MediaAnalysisResult> {
    if (!saved.hasStoredFingerprint()) return emptyList()
    val episodes = stored.filter { it.key != saved.key } + saved
    return seasonFingerprintPass(episodes, detectRecap = detectRecap, referenceCap = referenceCap)
}

internal fun seasonFingerprintPass(
    episodes: List<MediaAnalysisResult>,
    detectRecap: Boolean = true,
    referenceCap: Int = SEASON_PASS_REFERENCE_CAP,
): List<MediaAnalysisResult> {
    val working = episodes.associateBy { it.key }.toMutableMap()
    val ordered = episodes.distinctBy { it.key }
    for (episode in ordered) {
        val current = working[episode.key] ?: episode
        if (!current.needsSegmentPass()) continue
        val updated = current.applyingSeasonDetection(referencesFor(current, working.values, referenceCap))
        working[current.key] = updated
    }
    if (detectRecap) {
        for (episode in ordered) {
            val current = working[episode.key] ?: episode
            if (!current.needsRecapFromStoredFingerprints()) continue
            val recap = RecapDetector.detect(current, referencesFor(current, working.values, referenceCap)) ?: continue
            working[current.key] = current.copy(
                recapStartMs = current.recapStartMs ?: recap.first,
                recapEndMs = current.recapEndMs ?: recap.second,
            )
        }
    }
    return working.values.mapNotNull { updated ->
        val before = episodes.firstOrNull { it.key == updated.key } ?: return@mapNotNull null
        updated.takeIf { it.segmentsDiffer(before) }
    }
}

private fun MediaAnalysisResult.needsSegmentPass(): Boolean {
    if (!hasStoredFingerprint()) return false
    if (introEndMs == null || creditsStartMs == null) return true
    if (rejectedSegments.isNotEmpty()) return true
    return introConfidence != null || creditsConfidence != null
}

private fun MediaAnalysisResult.needsRecapFromStoredFingerprints(): Boolean =
    hasStoredFingerprint() && introStartMs != null && (recapStartMs == null || recapEndMs == null)

private fun referencesFor(
    episode: MediaAnalysisResult,
    pool: Collection<MediaAnalysisResult>,
    cap: Int,
): List<MediaAnalysisResult> {
    val others = pool.filter { it.key != episode.key && it.hasStoredFingerprint() }
    if (others.size <= cap) return others
    return others.sortedByDescending { it.analyzedAtMs }.take(cap)
}

private fun MediaAnalysisResult.segmentsDiffer(before: MediaAnalysisResult): Boolean =
    introStartMs != before.introStartMs ||
        introEndMs != before.introEndMs ||
        creditsStartMs != before.creditsStartMs ||
        creditsEndMs != before.creditsEndMs ||
        recapStartMs != before.recapStartMs ||
        recapEndMs != before.recapEndMs ||
        introConfidence != before.introConfidence ||
        creditsConfidence != before.creditsConfidence ||
        rejectedSegments != before.rejectedSegments ||
        silentRanges != before.silentRanges ||
        derivedVersion != before.derivedVersion
