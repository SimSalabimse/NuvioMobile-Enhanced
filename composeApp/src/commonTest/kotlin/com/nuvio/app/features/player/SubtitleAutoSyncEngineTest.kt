package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleAutoSyncEngineTest {

    @Test
    fun alignedBurstsAndCuesStayAtZero() {
        val cues = dialogueCues(burstTimesMs)
        val audio = energyBursts(burstTimesMs)
        val result = sync(audio, cues)
        assertEquals(0, offsetOf(result))
        assertFalse(result.movesSubtitleDelay())
    }

    @Test
    fun burstsShiftedByTwoSecondsReturnTwoSeconds() {
        val cues = dialogueCues(burstTimesMs)
        val audio = energyBursts(burstTimesMs.map { it + 2_000L })
        val result = sync(audio, cues)
        assertEquals(2_000, offsetOf(result))
        assertTrue(result.movesSubtitleDelay())
    }

    @Test
    fun ambiguousEnvelopeDoesNotMoveDelay() {
        val cues = dialogueCues(burstTimesMs)
        val audio = flatEnergy()
        val result = sync(audio, cues)
        assertEquals(0, offsetOf(result))
        assertFalse(result.movesSubtitleDelay())
        val confidence = confidenceOf(result)
        assertTrue(confidence < 1.0, "margin $confidence must stay below 1")
    }

    private fun sync(
        audio: List<AudioEnergySample>,
        cues: List<SubtitleSyncCue>,
    ): SubtitleAutoSyncResult {
        return SubtitleAutoSyncEngine.computeOptimalOffset(
            audioSamples = audio,
            subtitleCues = cues,
            currentPositionMs = 12_000L,
        ) ?: error("auto sync returned null")
    }

    private fun offsetOf(result: SubtitleAutoSyncResult): Int = when (result) {
        is SubtitleAutoSyncResult.Success -> result.offsetMs
        is SubtitleAutoSyncResult.LowConfidence -> result.offsetMs
        is SubtitleAutoSyncResult.Error -> error(result.message)
    }

    private fun confidenceOf(result: SubtitleAutoSyncResult): Double = when (result) {
        is SubtitleAutoSyncResult.Success -> result.confidence
        is SubtitleAutoSyncResult.LowConfidence -> result.confidence
        is SubtitleAutoSyncResult.Error -> error(result.message)
    }

    private fun dialogueCues(timesMs: List<Long>): List<SubtitleSyncCue> {
        return timesMs.map { start ->
            SubtitleSyncCue(
                startTimeMs = start,
                endTimeMs = start + 800L,
                text = "Hello there dialogue",
            )
        }
    }

    private fun energyBursts(centersMs: List<Long>): List<AudioEnergySample> {
        val samples = ArrayList<AudioEnergySample>()
        var time = 0L
        while (time <= 24_000L) {
            // One energy bin, the same 100ms window the cue onset lands in.
            val hot = centersMs.any { center -> time >= center && time < center + 100L }
            samples.add(AudioEnergySample(timestampMs = time, energy = if (hot) 1.0 else 0.0))
            time += 50L
        }
        return samples
    }

    private fun flatEnergy(): List<AudioEnergySample> {
        val samples = ArrayList<AudioEnergySample>()
        var time = 0L
        while (time <= 24_000L) {
            samples.add(AudioEnergySample(timestampMs = time, energy = 0.4))
            time += 50L
        }
        return samples
    }

    private companion object {
        val burstTimesMs = listOf(4_000L, 8_000L, 12_000L, 16_000L)
    }
}
