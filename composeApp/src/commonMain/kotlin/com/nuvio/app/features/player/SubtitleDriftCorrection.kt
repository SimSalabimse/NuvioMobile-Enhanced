package com.nuvio.app.features.player

/**
 * What the player bridge sets. The orchestrator calls this. A test records the calls.
 * It does not compute the fit.
 */
internal interface SubtitleSyncTransport {
    fun applyFit(speed: Double, fitDelayMs: Long)
    fun applyUserDelay(delayMs: Long)
    fun resetFit()
}

/** One measured match from the existing audio/text aligner. */
internal data class SubtitleMatchObservation(
    val videoMs: Long,
    val offsetMs: Long,
    val confident: Boolean,
)

internal fun interface SubtitleObservationSource {
    fun collect(): List<SubtitleMatchObservation>
}

internal enum class SubtitleDriftOutcome {
    Applied,
    LowConfidence,
    Cancelled,
}

/**
 * Playback sync entry. Observations come from [SubtitleObservationSource].
 * A confident line across the timeline sets mpv speed and delay. User delay is added once.
 * A seek does not change the fit. A track or stream change clears it. A stale ticket applies nothing.
 */
internal class SubtitleDriftCorrection(
    private val transport: SubtitleSyncTransport,
) {
    var generation: Long = 0
        private set
    private var userDelayMs: Long = 0
    private var fit: MpvSubtitleProperties = MpvSubtitleProperties(1.0, 0L, confident = false)
    private var provider: String? = null
    private var release: String? = null
    private var anchorCount: Int = 0

    fun begin(): Long {
        generation += 1
        return generation
    }

    fun cancel() {
        generation += 1
    }

    fun isCurrent(ticket: Long): Boolean = ticket == generation

    /** A seek keeps the fit and does not add the user delay again. */
    fun noteSeek() = Unit

    fun noteManualDelay(delayMs: Long) {
        userDelayMs = delayMs
        transport.applyUserDelay(delayMs)
        publishExport()
    }

    fun noteTrackOrStreamChanged() {
        generation += 1
        fit = MpvSubtitleProperties(1.0, 0L, confident = false)
        anchorCount = 0
        transport.resetFit()
        publishExport()
    }

    fun correct(
        source: SubtitleObservationSource,
        ticket: Long,
        provider: String?,
        release: String?,
        cancelled: () -> Boolean = { false },
    ): SubtitleDriftOutcome {
        if (cancelled() || ticket != generation) return SubtitleDriftOutcome.Cancelled
        val observations = source.collect()
        if (cancelled() || ticket != generation) return SubtitleDriftOutcome.Cancelled
        this.provider = provider
        this.release = release
        val anchors = observations.mapNotNull { it.toAnchor() }
        anchorCount = anchors.size
        val fitted = fitObservations(anchors)
        if (!fitted.confident) {
            publishExport(fitted)
            return SubtitleDriftOutcome.LowConfidence
        }
        if (cancelled() || ticket != generation) return SubtitleDriftOutcome.Cancelled
        fit = fitted
        transport.applyFit(fitted.subSpeed, fitted.subDelayMs)
        publishExport(fitted)
        return SubtitleDriftOutcome.Applied
    }

    /** Commands mpv receives: fitted speed, and fitted delay plus the user delay once. */
    fun appliedCommands(): Map<String, Double> = mpvPropertyValues(withUserDelay(fit, userDelayMs))

    private fun publishExport(shown: MpvSubtitleProperties = fit) {
        SubtitleSyncExportLog.record(provider, release, shown, userDelayMs, anchorCount)
    }
}

internal fun SubtitleMatchObservation.toAnchor(): SubtitleAnchor? {
    if (!confident) return null
    val subtitleMs = videoMs - offsetMs
    if (subtitleMs < 0L) return null
    return SubtitleAnchor(videoMs, subtitleMs)
}

internal fun fitObservations(anchors: List<SubtitleAnchor>): MpvSubtitleProperties {
    if (anchors.size == 1) {
        val only = anchors.single()
        return MpvSubtitleProperties(
            subSpeed = 1.0,
            subDelayMs = only.videoMs - only.subtitleMs,
            confident = true,
        )
    }
    return fitSubtitleAnchors(anchors)
}

internal fun matchObservation(
    videoMs: Long,
    samples: List<AudioEnergySample>,
    cues: List<SubtitleSyncCue>,
): SubtitleMatchObservation? = when (
    val result = SubtitleAutoSyncEngine.computeOptimalOffset(samples, cues, videoMs)
) {
    is SubtitleAutoSyncResult.Success -> SubtitleMatchObservation(videoMs, result.offsetMs.toLong(), confident = true)
    is SubtitleAutoSyncResult.LowConfidence -> SubtitleMatchObservation(videoMs, result.offsetMs.toLong(), confident = false)
    else -> null
}
