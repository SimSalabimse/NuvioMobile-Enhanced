package com.nuvio.app.features.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Matches subtitle cue timing to speech activity.
 * The offset is the ±10s peak of the FFT cross-correlation, the same lag
 * ffsubsync's FFTAligner reports. A flat bed has no speech bins and stays at 0.
 * A mask that stays active across most of the window is a bed and is not written.
 */
object SubtitleAutoSyncEngine {

    private const val SAMPLE_WINDOW_MS = 100L
    private const val MAX_ANALYSIS_DURATION_MS = 60_000L
    private const val MAX_OFFSET_SEARCH_MS = 10_000L
    /** Quiet-bed percentile removed before a bin is marked speech. */
    private const val SPEECH_FLOOR_PERCENTILE = 0.35
    /** ffsubsync DEFAULT_SPLIT_LENGTH_PENALTY. Speech just outside a cue edge counts against that lag. */
    private const val EDGE_PENALTY = 0.25
    /** ffsubsync caps the edge guard at 2s so a long cue does not reach the next line. */
    private const val EDGE_GUARD_MS = 2_000L
    /** A peak under this margin is not written to Subtitle Delay. */
    const val MIN_CONFIDENCE = 0.3
    /** Cache audio shorter than the pre-playhead lookback does not win the capture. */
    const val LOOKBACK_MS = 20_000L
    /**
     * auditok min_length on the non-transcribing aligner. A shorter run is a click.
     * Bins are 100ms, so this is two bins.
     */
    private const val MIN_SPEECH_RUN_MS = 200L
    /**
     * Silence at or under this stays inside the run. auditok max_continuous_silence
     * is 250ms; two 100ms bins is the longest gap that still fits under that.
     */
    private const val MAX_SILENCE_BRIDGE_MS = 200L
    /** After the activity rule, a mask this full has no dialogue pauses. It is a bed. */
    private const val BED_ACTIVE_FRACTION = 0.80

    /**
     * @param audioSamples Audio amplitude samples with timestamps
     * @param subtitleCues Subtitle cues with timing information
     * @param currentPositionMs Playback position the analysis window is centered on
     */
    fun computeOptimalOffset(
        audioSamples: List<AudioEnergySample>,
        subtitleCues: List<SubtitleSyncCue>,
        currentPositionMs: Long,
    ): SubtitleAutoSyncResult? {
        if (audioSamples.isEmpty() || subtitleCues.isEmpty()) {
            return SubtitleAutoSyncResult.Error("Insufficient data for auto-sync")
        }

        val windowStart = max(0L, currentPositionMs - MAX_ANALYSIS_DURATION_MS / 2)
        val windowEnd = currentPositionMs + MAX_ANALYSIS_DURATION_MS / 2
        val windowedAudio = audioSamples.filter { it.timestampMs in windowStart..windowEnd }
        val windowedCues = subtitleCues.filter { it.startTimeMs in windowStart..windowEnd }

        if (windowedAudio.isEmpty() || windowedCues.isEmpty()) {
            return SubtitleAutoSyncResult.Error("No subtitle or audio data in analysis window")
        }
        if (!cuesOverlapAudio(windowedCues, windowedAudio)) {
            return SubtitleAutoSyncResult.Error(energyMatchSpanMessage(windowedAudio))
        }

        val envelope = buildLiftedEnvelope(windowedAudio, SAMPLE_WINDOW_MS)
        if (envelope.size < 2) {
            return SubtitleAutoSyncResult.Error(energyMatchSpanMessage(windowedAudio))
        }

        val decision = alignSpeech(envelope, windowedCues)
        if (decision.confidence < MIN_CONFIDENCE) {
            return SubtitleAutoSyncResult.LowConfidence(decision.offsetMs, decision.confidence)
        }
        return SubtitleAutoSyncResult.Success(decision.offsetMs, decision.confidence)
    }

    /**
     * A cache dump shorter than the 20s lookback does not win. The URL decode
     * that starts 20s before the playhead runs instead.
     */
    fun planPcmCapture(
        hasCache: Boolean,
        cacheOriginMs: Long,
        cacheDurationMs: Long,
        playheadMs: Long,
    ): PcmCapturePlan {
        val urlDecodeStartMs = max(0L, playheadMs - LOOKBACK_MS)
        val coversLines = hasCache && cacheOriginMs + 1_000L < playheadMs
        val longEnough = hasCache && cacheDumpWins(cacheDurationMs)
        return PcmCapturePlan(
            cacheFirst = coversLines && longEnough,
            includeCache = longEnough,
            urlDecodeStartMs = urlDecodeStartMs,
        )
    }

    /** True when a decoded cache span is long enough to be the chosen capture. */
    fun cacheDumpWins(sampleSpanMs: Long): Boolean = sampleSpanMs >= LOOKBACK_MS

    private fun cuesOverlapAudio(
        cues: List<SubtitleSyncCue>,
        audio: List<AudioEnergySample>,
    ): Boolean {
        val audioStart = audio.minOf { it.timestampMs }
        val audioEnd = audio.maxOf { it.timestampMs }
        return cues.any { cue ->
            val cueEnd = if (cue.endTimeMs > cue.startTimeMs) cue.endTimeMs else cue.startTimeMs + SAMPLE_WINDOW_MS
            cue.startTimeMs <= audioEnd && cueEnd >= audioStart
        }
    }

    private fun buildLiftedEnvelope(
        samples: List<AudioEnergySample>,
        windowMs: Long,
    ): List<EnergyPoint> {
        val raw = buildRawEnergyEnvelope(samples, windowMs)
        if (raw.isEmpty()) return raw
        val floor = percentile(raw.map { it.value }, SPEECH_FLOOR_PERCENTILE)
        return raw.map { it.copy(value = max(0.0, it.value - floor)) }
    }

    private fun buildRawEnergyEnvelope(
        samples: List<AudioEnergySample>,
        windowMs: Long,
    ): List<EnergyPoint> {
        if (samples.isEmpty()) return emptyList()

        val startTime = samples.first().timestampMs
        val endTime = samples.last().timestampMs
        val points = mutableListOf<EnergyPoint>()
        var windowStart = startTime
        while (windowStart <= endTime) {
            val windowEnd = windowStart + windowMs
            var sum = 0.0
            var count = 0
            for (sample in samples) {
                if (sample.timestampMs >= windowStart && sample.timestampMs < windowEnd) {
                    sum += sample.energy
                    count += 1
                }
            }
            if (count > 0) {
                points.add(EnergyPoint(windowStart + windowMs / 2, sum / count))
            }
            windowStart += windowMs
        }
        return points
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val ordered = values.sorted()
        val index = (fraction * (ordered.size - 1)).toInt().coerceIn(0, ordered.lastIndex)
        return ordered[index]
    }

    /**
     * Binary speech activity against cue boxes on the capture grid.
     * A bin counts only inside a run that lasts at least [MIN_SPEECH_RUN_MS],
     * and a click is dropped. A mask that stays active across most of the
     * window is a bed, so its peak is not a subtitle lag.
     * Cue boxes use file timestamps. The current Subtitle Delay is not an input.
     */
    private fun alignSpeech(
        envelope: List<EnergyPoint>,
        cues: List<SubtitleSyncCue>,
    ): OffsetDecision {
        val raised = DoubleArray(envelope.size) { index ->
            if (envelope[index].value > 0.0) 1.0 else 0.0
        }
        val reference = speechActivity(raised)
        if (reference.none { it > 0.0 } || maskFillsWindow(reference)) {
            return OffsetDecision(0, 0.0)
        }
        val subtitle = DoubleArray(envelope.size)
        val cueSpans = ArrayList<Pair<Int, Int>>(cues.size)
        val halfWindow = SAMPLE_WINDOW_MS / 2
        for (cue in cues) {
            val start = cue.startTimeMs
            val end = if (cue.endTimeMs > cue.startTimeMs) cue.endTimeMs else cue.startTimeMs + SAMPLE_WINDOW_MS
            var first = -1
            var last = -1
            for (index in envelope.indices) {
                val binStart = envelope[index].timestampMs - halfWindow
                val binEnd = envelope[index].timestampMs + halfWindow
                if (start < binEnd && end > binStart) {
                    subtitle[index] = 1.0
                    if (first < 0) first = index
                    last = index + 1
                }
            }
            if (first >= 0) cueSpans.add(first to last)
        }
        val maxBins = (MAX_OFFSET_SEARCH_MS / SAMPLE_WINDOW_MS).toInt()
        val aligned = correlate(reference, subtitle, maxBins, cueSpans)
        val confidence = aligned.correlation / subtitle.size.toDouble()
        return OffsetDecision(aligned.offsetBins * SAMPLE_WINDOW_MS.toInt(), confidence)
    }

    /**
     * The non-transcribing aligner's activity rule. Short gaps stay in the run.
     * A bin outside a long-enough run is cleared, which drops a click.
     */
    private fun speechActivity(raw: DoubleArray): DoubleArray {
        val mask = raw.copyOf()
        val bridgeBins = (MAX_SILENCE_BRIDGE_MS / SAMPLE_WINDOW_MS).toInt()
        val minRunBins = (MIN_SPEECH_RUN_MS / SAMPLE_WINDOW_MS).toInt()
        var index = 0
        while (index < mask.size) {
            if (mask[index] == 0.0) {
                var end = index
                while (end < mask.size && mask[end] == 0.0) end += 1
                if (end - index <= bridgeBins && index > 0 && end < mask.size) {
                    for (fill in index until end) mask[fill] = 1.0
                }
                index = end
            } else {
                index += 1
            }
        }
        index = 0
        while (index < mask.size) {
            if (mask[index] > 0.0) {
                var end = index
                while (end < mask.size && mask[end] > 0.0) end += 1
                if (end - index < minRunBins) {
                    for (clear in index until end) mask[clear] = 0.0
                }
                index = end
            } else {
                index += 1
            }
        }
        return mask
    }

    private fun maskFillsWindow(mask: DoubleArray): Boolean {
        if (mask.isEmpty()) return false
        var active = 0
        for (value in mask) {
            if (value > 0.0) active += 1
        }
        return active.toDouble() / mask.size.toDouble() >= BED_ACTIVE_FRACTION
    }

    /**
     * ffsubsync FFTAligner.fit: bipolar map, zero-pad to the next power of two,
     * FFT the padded subtitle series, FFT the flipped padded reference, multiply,
     * inverse FFT. Lags outside ±[maxOffsetBins] are negative infinity.
     * Offset = len(convolve) - 1 - argmax - len(subtitleSeries).
     * The edge term is ffsubsync's length penalty: speech in the guard just
     * outside each cue counts against that lag, so a short pulse inside a
     * longer cue peaks on the cue start.
     */
    private fun correlate(
        reference: DoubleArray,
        subtitle: DoubleArray,
        maxOffsetBins: Int,
        cueSpans: List<Pair<Int, Int>>,
    ): CorrelationPeak {
        val bipolarRef = DoubleArray(reference.size) { 2.0 * reference[it] - 1.0 }
        val bipolarSub = DoubleArray(subtitle.size) { 2.0 * subtitle[it] - 1.0 }
        val total = nextPow2(bipolarRef.size + bipolarSub.size)
        val extra = total - bipolarSub.size - bipolarRef.size
        val subPad = DoubleArray(total)
        val subOrigin = extra + bipolarRef.size
        for (index in bipolarSub.indices) subPad[subOrigin + index] = bipolarSub[index]
        val refTime = DoubleArray(total)
        for (index in bipolarRef.indices) refTime[index] = bipolarRef[index]
        val refPad = DoubleArray(total) { index -> refTime[total - 1 - index] }

        val subReal = subPad.copyOf()
        val subImag = DoubleArray(total)
        transform(subReal, subImag, inverse = false)
        val refReal = refPad.copyOf()
        val refImag = DoubleArray(total)
        transform(refReal, refImag, inverse = false)
        val prodReal = DoubleArray(total)
        val prodImag = DoubleArray(total)
        for (index in 0 until total) {
            prodReal[index] = subReal[index] * refReal[index] - subImag[index] * refImag[index]
            prodImag[index] = subReal[index] * refImag[index] + subImag[index] * refReal[index]
        }
        transform(prodReal, prodImag, inverse = true)

        var bestIndex = 0
        var bestScore = Double.NEGATIVE_INFINITY
        var bestRaw = 0.0
        val subtitleLength = bipolarSub.size
        for (index in prodReal.indices) {
            val offset = total - 1 - index - subtitleLength
            if (abs(offset) > maxOffsetBins) continue
            val raw = prodReal[index]
            val score = raw - edgePenalty(reference, cueSpans, offset)
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
                bestRaw = raw
            }
        }
        return CorrelationPeak(
            offsetBins = total - 1 - bestIndex - subtitleLength,
            correlation = bestRaw,
        )
    }

    private fun edgePenalty(
        reference: DoubleArray,
        cueSpans: List<Pair<Int, Int>>,
        offsetBins: Int,
    ): Double {
        val guardCap = (EDGE_GUARD_MS / SAMPLE_WINDOW_MS).toInt()
        var penalty = 0.0
        for ((start, end) in cueSpans) {
            val guard = minOf((end - start).coerceAtLeast(1), guardCap)
            val shiftedStart = start + offsetBins
            val shiftedEnd = end + offsetBins
            var outside = 0.0
            var index = shiftedStart - guard
            while (index < shiftedStart) {
                if (index in reference.indices && reference[index] > 0.0) outside += 1.0
                index += 1
            }
            index = shiftedEnd
            val guardEnd = shiftedEnd + guard
            while (index < guardEnd) {
                if (index in reference.indices && reference[index] > 0.0) outside += 1.0
                index += 1
            }
            penalty += EDGE_PENALTY * outside
        }
        return penalty
    }

    private fun nextPow2(value: Int): Int {
        var size = 1
        while (size < value) size = size shl 1
        return size
    }

    /** In-place radix-2 FFT. [inverse] divides by n, matching numpy.fft.ifft. */
    private fun transform(real: DoubleArray, imag: DoubleArray, inverse: Boolean) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val swapReal = real[i]
                real[i] = real[j]
                real[j] = swapReal
                val swapImag = imag[i]
                imag[i] = imag[j]
                imag[j] = swapImag
            }
        }
        var length = 2
        while (length <= n) {
            val angle = (if (inverse) 2.0 else -2.0) * PI / length
            val wlenReal = cos(angle)
            val wlenImag = sin(angle)
            var base = 0
            while (base < n) {
                var wReal = 1.0
                var wImag = 0.0
                val half = length / 2
                for (k in 0 until half) {
                    val evenReal = real[base + k]
                    val evenImag = imag[base + k]
                    val oddReal = real[base + k + half]
                    val oddImag = imag[base + k + half]
                    val twiddledReal = oddReal * wReal - oddImag * wImag
                    val twiddledImag = oddReal * wImag + oddImag * wReal
                    real[base + k] = evenReal + twiddledReal
                    imag[base + k] = evenImag + twiddledImag
                    real[base + k + half] = evenReal - twiddledReal
                    imag[base + k + half] = evenImag - twiddledImag
                    val nextReal = wReal * wlenReal - wImag * wlenImag
                    wImag = wReal * wlenImag + wImag * wlenReal
                    wReal = nextReal
                }
                base += length
            }
            length = length shl 1
        }
        if (inverse) {
            for (index in 0 until n) {
                real[index] /= n.toDouble()
                imag[index] /= n.toDouble()
            }
        }
    }

    private data class EnergyPoint(
        val timestampMs: Long,
        val value: Double,
    )

    private data class OffsetDecision(
        val offsetMs: Int,
        val confidence: Double,
    )

    private data class CorrelationPeak(
        val offsetBins: Int,
        val correlation: Double,
    )
}

internal const val ENERGY_MATCH_SPAN_LABEL = "Energy match span"

internal fun energyMatchSpanMessage(samples: List<AudioEnergySample>): String {
    val start = samples.minOfOrNull { it.timestampMs } ?: 0L
    val end = samples.maxOfOrNull { it.timestampMs } ?: 0L
    return "${formatEnergyMatchSpan(samples)} (${formatEnergyStats(samples)}, audio ${start}-${end}ms)"
}

internal fun formatEnergyMatchSpan(samples: List<AudioEnergySample>): String {
    if (samples.isEmpty()) return "$ENERGY_MATCH_SPAN_LABEL 0.0s"
    val durationMs = samples.maxOf { it.timestampMs } - samples.minOf { it.timestampMs }
    val tenths = durationMs / 100
    val whole = tenths / 10
    val fraction = tenths % 10
    return "$ENERGY_MATCH_SPAN_LABEL $whole.${fraction}s"
}

internal fun autoSyncLowConfidenceMessage(
    offsetMs: Int,
    energyStats: String,
    confidence: Double,
    cuesOnScreen: Boolean,
): String {
    val detail = "Low confidence sync. Offset: ${formatOffsetMessage(offsetMs)} ($energyStats, margin: ${formatMargin(confidence)})."
    return if (cuesOnScreen) detail else "$detail Try a scene with more dialogue."
}

internal fun formatMargin(value: Double): String {
    val sign = if (value < 0.0) "-" else ""
    val scaled = (kotlin.math.abs(value) * 1000.0).toInt().coerceIn(0, 999_999)
    val whole = scaled / 1000
    val fraction = (scaled % 1000).toString().padStart(3, '0')
    return "$sign$whole.$fraction"
}

internal fun formatEnergyStats(samples: List<AudioEnergySample>): String {
    var peak = 0.0
    for (sample in samples) {
        val energy = kotlin.math.abs(sample.energy)
        if (energy > peak) peak = energy
    }
    return "N=${samples.size}, peak=${formatFixed3(peak)}"
}

private fun formatFixed3(value: Double): String {
    val scaled = (kotlin.math.abs(value) * 1000.0).toInt().coerceIn(0, 999_999)
    val whole = scaled / 1000
    val fraction = (scaled % 1000).toString().padStart(3, '0')
    return "$whole.$fraction"
}

internal fun formatOffsetMessage(offsetMs: Int): String {
    val sign = if (offsetMs >= 0) "+" else ""
    val seconds = offsetMs / 1000.0
    val formatted = buildString {
        append(sign)
        append(seconds.toInt())
        append('.')
        val fraction = ((kotlin.math.abs(seconds) % 1.0) * 10).toInt()
        append(fraction)
    }
    return "${formatted}s"
}

/** Which decode runs, and whether a cache dump is allowed to win. */
data class PcmCapturePlan(
    val cacheFirst: Boolean,
    val includeCache: Boolean,
    val urlDecodeStartMs: Long,
)

/** Subtitle Delay moves only for a confident lag outside the zero band. */
fun SubtitleAutoSyncResult.movesSubtitleDelay(): Boolean = when (this) {
    is SubtitleAutoSyncResult.Success -> offsetMs != 0
    is SubtitleAutoSyncResult.LowConfidence -> false
    is SubtitleAutoSyncResult.Error -> false
}

/**
 * Audio sample with energy/amplitude measurement.
 */
data class AudioEnergySample(
    val timestampMs: Long,
    val energy: Double,
)

/**
 * Result of auto-sync computation.
 */
sealed class SubtitleAutoSyncResult {
    data class Success(val offsetMs: Int, val confidence: Double) : SubtitleAutoSyncResult()
    data class LowConfidence(val offsetMs: Int, val confidence: Double) : SubtitleAutoSyncResult()
    data class Error(val message: String) : SubtitleAutoSyncResult()
}
