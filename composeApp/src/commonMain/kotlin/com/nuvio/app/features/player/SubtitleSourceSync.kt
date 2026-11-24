package com.nuvio.app.features.player

import com.nuvio.app.core.serialization.LenientDefaultsJson
import com.nuvio.app.features.analysis.MediaAnalysisFiles
import com.nuvio.app.features.analysis.MediaAnalysisResult
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * A source is the provider, language, and release name.
 * Anything that looks like an address is dropped so a remembered fit cannot store one.
 */
internal fun subtitleSourceKey(provider: String?, language: String?, release: String?): String {
    fun token(raw: String?): String {
        val trimmed = raw?.trim()?.lowercase().orEmpty()
        if (
            trimmed.isEmpty() ||
            trimmed.contains("://") ||
            trimmed.contains('/') ||
            trimmed.contains('\\') ||
            trimmed.contains('?') ||
            trimmed.contains('@') ||
            trimmed.length > 80
        ) {
            return "-"
        }
        return trimmed.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }.ifEmpty { "-" }
    }
    return "${token(provider)}|${token(language)}|${token(release)}"
}

@Serializable
internal data class RememberedSubtitleFit(
    val subSpeed: Double,
    val subDelayMs: Long,
)

/** Speed and delay last measured for one subtitle source, kept across episodes. */
internal object SubtitleSourceMemory {
    private const val FILE = "subtitle-source-fits"
    private val json = LenientDefaultsJson
    private val cache = mutableMapOf<String, RememberedSubtitleFit>()
    private val lock = SynchronizedObject()
    private var loaded = false

    fun remember(sourceKey: String, subSpeed: Double, subDelayMs: Long) {
        if (sourceKey.isBlank() || !subSpeed.isFinite() || subSpeed <= 0.5 || subSpeed >= 1.5) return
        synchronized(lock) {
            loadLocked()
            cache[sourceKey] = RememberedSubtitleFit(subSpeed, subDelayMs)
            MediaAnalysisFiles.write(FILE, json.encodeToString(cache))
        }
    }

    fun recall(sourceKey: String): RememberedSubtitleFit? {
        if (sourceKey.isBlank()) return null
        return synchronized(lock) {
            loadLocked()
            cache[sourceKey]
        }
    }

    fun clearForTests() {
        synchronized(lock) {
            cache.clear()
            loaded = false
            MediaAnalysisFiles.delete("$FILE.json")
        }
    }

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        val text = MediaAnalysisFiles.read(FILE) ?: return
        val stored = runCatching {
            json.decodeFromString<Map<String, RememberedSubtitleFit>>(text)
        }.getOrNull() ?: return
        cache.putAll(stored)
    }
}

internal fun subtitleSyncIdentity(subtitle: AddonSubtitle): Triple<String?, String?, String?> {
    val provider = subtitle.addonName?.takeIf { it.isNotBlank() } ?: "addon"
    val release = subtitle.label ?: subtitle.display
    return Triple(provider, subtitle.language, release)
}

/**
 * The subtitle whose cues should be measured.
 * A downloaded file keeps its sidecar even when no add-on row is selected.
 */
internal fun subtitleSyncCandidate(
    selected: AddonSubtitle?,
    useCustomSubtitles: Boolean,
    external: List<StreamSubtitle>,
    sourceUrl: String,
    preferredLanguage: String?,
): AddonSubtitle? {
    if (useCustomSubtitles && selected != null) return selected
    if (!sourceUrl.startsWith("file:")) return null
    val preferred = normalizeLanguageCode(preferredLanguage)
    val local = external.firstOrNull { track ->
        track.url.isLocalSubtitleUri() &&
            preferred != null &&
            normalizeLanguageCode(track.language) == preferred
    } ?: external.firstOrNull { it.url.isLocalSubtitleUri() }
    return local?.toAddonSubtitle()
}

/**
 * Measures this file when it has speech energy and cues. A remembered fit is applied only
 * when there is nothing to measure, as one absolute speed and delay.
 * A restored per-video delay does not cancel the measurement. A nudge after the ticket does.
 */
internal fun correctSubtitleDrift(
    analysis: MediaAnalysisResult?,
    cues: List<SubtitleSyncCue>,
    correction: SubtitleDriftCorrection,
    ticket: Long,
    provider: String?,
    language: String?,
    release: String?,
    cancelled: () -> Boolean = { false },
): SubtitleDriftOutcome {
    if (cancelled() || !correction.isCurrent(ticket) || correction.nudgedSince(ticket)) {
        return SubtitleDriftOutcome.Cancelled
    }
    val key = subtitleSourceKey(provider, language, release)
    val remembered = SubtitleSourceMemory.recall(key)
    val canMeasure = !analysis?.energy.isNullOrEmpty() && cues.isNotEmpty()
    if (!canMeasure) {
        // No speech to measure against. A remembered fit is the only correction, and it is one
        // absolute speed and delay, not a second scale on top of a fit already on the player.
        if (remembered != null && correction.applyKnown(remembered.subSpeed, remembered.subDelayMs, ticket)) {
            return SubtitleDriftOutcome.Applied
        }
        return SubtitleDriftOutcome.LowConfidence
    }
    val outcome = correction.correct(
        source = SubtitleObservationSource { energySubtitleObservations(analysis, cues) },
        ticket = ticket,
        provider = provider,
        release = release,
        automatic = true,
        cancelled = cancelled,
    )
    if (outcome == SubtitleDriftOutcome.Applied && correction.isCurrent(ticket)) {
        correction.rememberableFit()?.let { (speed, delay) ->
            SubtitleSourceMemory.remember(key, speed, delay)
        }
    }
    return outcome
}
