package com.nuvio.app.features.player

import com.nuvio.app.features.analysis.MediaAnalysisResult
import com.nuvio.app.features.analysis.encodeEnergyLevels
import com.nuvio.app.features.analysis.energyLevelForLinear
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Quantized speech energy and real cue text, through [energySubtitleObservations] and [SubtitleDriftCorrection.correct].
 * The ±10 second matcher is unchanged. A frame-rate candidate only brings a growing lag into that window.
 */
class SubtitleScaleObservationTest {
    @Test
    fun frameRateScaleIsMeasuredInsteadOfStoppingOnTheSearchWall() {
        val duration = 2_400_000L
        val scale = 25.0 / 23.976
        val fit = fitQuantized(duration, scale, extraFileLagMs = 0L)
        assertTrue(fit.subSpeed < 0.99, "speed ${fit.subSpeed}")
        val endFile = (duration * scale).toLong()
        val shown = shownSubtitleMs(endFile, fit)
        assertTrue(abs(shown - duration) < 3_000.0, "end error ${shown - duration}")
        assertTrue(abs(fit.subDelayMs) < 20_000L, "delay ${fit.subDelayMs}")
    }

    @Test
    fun anEightSecondDriftStaysOnTheIdentityScale() {
        val duration = 2_400_000L
        val scale = (duration + 8_000L).toDouble() / duration.toDouble()
        val fit = fitQuantized(duration, scale, extraFileLagMs = 0L)
        assertTrue(fit.confident)
        assertTrue(fit.subSpeed < 1.0)
        val shown = shownSubtitleMs((duration * scale).toLong(), fit)
        assertTrue(abs(shown - duration) < 1_500.0, "end error ${shown - duration}")
    }

    @Test
    fun aConstantTwoSecondLagDoesNotInventAFrameRate() {
        val duration = 2_400_000L
        val fit = fitQuantized(duration, scale = 1.0, extraFileLagMs = 2_000L)
        assertTrue(abs(fit.subSpeed - 1.0) < 0.001, "speed ${fit.subSpeed}")
        assertTrue(abs(fit.subDelayMs + 2_000L) <= 200L, "delay ${fit.subDelayMs}")
    }

    @Test
    fun unmatchedAudioDoesNotApply() {
        val duration = 2_400_000L
        val analysis = quantizedAnalysis(duration, speechAt(duration, 1.0, 0L).map { it.first - 30_000L to it.second - 30_000L })
        val cues = cuesAt(duration, 1.0, 0L)
        val transport = ScaleRecordingTransport()
        val correction = SubtitleDriftCorrection(transport)
        val ticket = correction.begin()
        val outcome = correction.correct(
            source = SubtitleObservationSource { energySubtitleObservations(analysis, cues) },
            ticket = ticket,
            provider = "OpenSubtitles",
            release = "Episode.srt",
        )
        assertEquals(SubtitleDriftOutcome.LowConfidence, outcome)
        assertTrue(transport.fits.isEmpty())
    }

    private fun fitQuantized(duration: Long, scale: Double, extraFileLagMs: Long): MpvSubtitleProperties {
        val speech = speechAt(duration, scale, extraFileLagMs)
        val analysis = quantizedAnalysis(duration, speech)
        val cues = cuesAt(duration, scale, extraFileLagMs)
        val transport = ScaleRecordingTransport()
        val correction = SubtitleDriftCorrection(transport)
        val ticket = correction.begin()
        val outcome = correction.correct(
            source = SubtitleObservationSource { energySubtitleObservations(analysis, cues) },
            ticket = ticket,
            provider = "OpenSubtitles",
            release = "Episode.srt",
        )
        assertEquals(SubtitleDriftOutcome.Applied, outcome, "observations ${energySubtitleObservations(analysis, cues)}")
        val speed = transport.commands.last().getValue("sub-speed")
        val delayMs = (transport.commands.last().getValue("sub-delay") * 1_000.0).toLong()
        return MpvSubtitleProperties(speed, delayMs, confident = true)
    }

    private fun speechAt(duration: Long, scale: Double, extraFileLagMs: Long): List<Pair<Long, Long>> =
        centers(duration).flatMap { center -> maskAround(center) }

    private fun cuesAt(duration: Long, scale: Double, extraFileLagMs: Long): List<SubtitleSyncCue> =
        centers(duration).flatMap { center ->
            maskAround(center).map { (start, end) ->
                SubtitleSyncCue(
                    startTimeMs = (start * scale).toLong() + extraFileLagMs,
                    endTimeMs = (end * scale).toLong() + extraFileLagMs,
                    text = "line",
                )
            }
        }

    private fun centers(duration: Long): List<Long> =
        listOf(0.2, 0.4, 0.6, 0.8).map { (duration * it).toLong() }

    /** The cue mask the matcher already accepts, placed on the picture timeline. */
    private fun maskAround(center: Long): List<Pair<Long, Long>> {
        val shift = center - 20_000L
        return listOf(
            8_000L to 10_000L,
            12_000L to 14_500L,
            17_000L to 19_000L,
            22_000L to 25_000L,
        ).map { (start, end) -> start + shift to end + shift }
    }

    private fun quantizedAnalysis(durationMs: Long, speech: List<Pair<Long, Long>>): MediaAnalysisResult {
        val step = 100
        val count = (durationMs / step).toInt()
        val levels = IntArray(count) { index ->
            val time = index.toLong() * step
            val speaking = speech.any { (start, end) -> time >= start && time < end }
            energyLevelForLinear(if (speaking) 0.5 else 0.02)
        }
        return MediaAnalysisResult(
            key = "show__s1e1",
            analyzedAtMs = 1L,
            durationMs = durationMs,
            energyStepMs = step,
            energy = encodeEnergyLevels(levels),
        )
    }
}

private class ScaleRecordingTransport : SubtitleSyncTransport {
    val fits = mutableListOf<Pair<Double, Long>>()
    val commands = mutableListOf<Map<String, Double>>()
    private var speed = 1.0
    private var fitDelay = 0L

    override fun applyFit(speed: Double, fitDelayMs: Long) {
        fits += speed to fitDelayMs
        this.speed = speed
        fitDelay = fitDelayMs
        publish()
    }

    override fun applyUserDelay(delayMs: Long) = Unit

    override fun resetFit() {
        speed = 1.0
        fitDelay = 0L
        publish()
    }

    private fun publish() {
        commands += mpvPropertyValues(MpvSubtitleProperties(speed, fitDelay, confident = true))
    }
}
