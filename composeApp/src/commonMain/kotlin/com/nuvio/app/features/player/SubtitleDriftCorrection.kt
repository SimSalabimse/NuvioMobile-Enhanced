package com.nuvio.app.features.player

/**
 * What the player bridge sets. The orchestrator calls this. A test records the calls.
 * It does not compute the fit.
 */
internal interface SubtitleSyncTransport {
    /** False on a player that can move a subtitle by a constant delay only. */
    val supportsSubtitleRate: Boolean get() = true
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
    RateUnsupported,
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
    private var manualEpoch: Long = 0
    private var epochAtTicket: Long = 0
    private var fit: MpvSubtitleProperties = MpvSubtitleProperties(1.0, 0L, confident = false)
    private var provider: String? = null
    private var release: String? = null
    private var anchorCount: Int = 0

    fun begin(): Long {
        generation += 1
        epochAtTicket = manualEpoch
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
        manualEpoch += 1
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
        automatic: Boolean = false,
        cancelled: () -> Boolean = { false },
    ): SubtitleDriftOutcome {
        if (blocked(ticket, automatic, cancelled)) return SubtitleDriftOutcome.Cancelled
        val observations = source.collect()
        if (blocked(ticket, automatic, cancelled)) return SubtitleDriftOutcome.Cancelled
        this.provider = provider
        this.release = release
        val anchors = observations.mapNotNull { it.toAnchor() }
        anchorCount = anchors.size
        val fitted = fitObservations(anchors)
        if (!fitted.confident) {
            publishExport(fitted)
            return SubtitleDriftOutcome.LowConfidence
        }
        if (blocked(ticket, automatic, cancelled)) return SubtitleDriftOutcome.Cancelled
        val rateSupported = transport.supportsSubtitleRate
        if (!rateSupported && kotlin.math.abs(fitted.subSpeed - 1.0) > 0.0005) {
            publishExport(MpvSubtitleProperties(1.0, 0L, confident = false), rateSupported = false)
            return SubtitleDriftOutcome.RateUnsupported
        }
        fit = fitted
        transport.applyFit(fitted.subSpeed, fitted.subDelayMs)
        publishExport(fitted, rateSupported = rateSupported)
        return SubtitleDriftOutcome.Applied
    }

    private fun blocked(ticket: Long, automatic: Boolean, cancelled: () -> Boolean): Boolean {
        if (cancelled() || ticket != generation) return true
        return automatic && manualEpoch != epochAtTicket
    }

    /** Commands mpv receives: fitted speed, and fitted delay plus the user delay once. */
    fun appliedCommands(): Map<String, Double> = mpvPropertyValues(withUserDelay(fit, userDelayMs))

    private fun publishExport(shown: MpvSubtitleProperties = fit, rateSupported: Boolean = true) {
        SubtitleSyncExportLog.record(provider, release, shown, userDelayMs, anchorCount, rateSupported)
    }
}

/**
 * What both playback entries do with one matcher result.
 * A confident zero is a real measurement and clears a previous rate. A search-wall offset is not.
 */
internal fun applyCapturedSync(
    correction: SubtitleDriftCorrection,
    result: SubtitleAutoSyncResult?,
    videoMs: Long,
    ticket: Long,
    provider: String?,
    release: String?,
    automatic: Boolean,
): SubtitleDriftOutcome {
    return when (result) {
        is SubtitleAutoSyncResult.Success -> {
            val usable = !offsetSitsOnSearchWall(result.offsetMs.toLong())
            correction.correct(
                source = SubtitleObservationSource {
                    listOf(SubtitleMatchObservation(videoMs, result.offsetMs.toLong(), confident = usable))
                },
                ticket = ticket,
                provider = provider,
                release = release,
                automatic = automatic,
            )
        }
        is SubtitleAutoSyncResult.LowConfidence -> correction.correct(
            source = SubtitleObservationSource {
                listOf(SubtitleMatchObservation(videoMs, result.offsetMs.toLong(), confident = false))
            },
            ticket = ticket,
            provider = provider,
            release = release,
            automatic = automatic,
        )
        else -> SubtitleDriftOutcome.Cancelled
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
    is SubtitleAutoSyncResult.Success -> {
        val offset = result.offsetMs.toLong()
        SubtitleMatchObservation(videoMs, offset, confident = !offsetSitsOnSearchWall(offset))
    }
    is SubtitleAutoSyncResult.LowConfidence -> SubtitleMatchObservation(videoMs, result.offsetMs.toLong(), confident = false)
    else -> null
}
