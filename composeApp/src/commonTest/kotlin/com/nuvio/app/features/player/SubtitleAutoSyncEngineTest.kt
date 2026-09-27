package com.nuvio.app.features.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleAutoSyncEngineTest {

    @Test
    fun alignedDialogueWithMusicStaysConfidentAtZero() {
        val cues = dialogueCues(dialogueSpans)
        val audio = speechDuring(dialogueSpans, attackDelay = true)
        val result = sync(audio, cues, positionMs = 20_000L)
        assertEquals(0, offsetOf(result))
        assertFalse(result.movesSubtitleDelay())
        assertTrue(result is SubtitleAutoSyncResult.Success, "expected Synced path, got $result")
        val confidence = confidenceOf(result)
        assertTrue(confidence >= 0.3, "margin $confidence")
    }

    @Test
    fun dialogueShiftedByTwoSecondsFollowsTheSpeech() {
        val shifted = dialogueSpans.map { (start, end) -> start + 2_000L to end + 2_000L }
        val cues = dialogueCues(dialogueSpans)
        val audio = speechDuring(shifted, attackDelay = true)
        val result = sync(audio, cues, positionMs = 22_000L)
        val offset = offsetOf(result)
        assertTrue(abs(offset - 2_000) <= 400, "offset $offset")
        assertTrue(result.movesSubtitleDelay())
        assertTrue(offset != -7_200)
    }

    @Test
    fun loudHitSevenSecondsAwayDoesNotMoveDelay() {
        val cues = dialogueCues(dialogueSpans)
        val audio = speechDuring(dialogueSpans, attackDelay = true).toMutableList()
        val hitAt = dialogueSpans.first().first + 7_200L
        for (index in audio.indices) {
            val sample = audio[index]
            if (sample.timestampMs in hitAt until hitAt + 400L) {
                audio[index] = sample.copy(energy = sample.energy + 0.8)
            }
        }
        val result = sync(audio, cues, positionMs = 20_000L)
        assertEquals(0, offsetOf(result))
        assertFalse(result.movesSubtitleDelay())
    }

    @Test
    fun ambiguousEnvelopeDoesNotMoveDelay() {
        val cues = dialogueCues(dialogueSpans)
        val audio = flatEnergy()
        val result = sync(audio, cues, positionMs = 20_000L)
        assertEquals(0, offsetOf(result))
        assertFalse(result.movesSubtitleDelay())
        val confidence = confidenceOf(result)
        assertTrue(confidence < 0.3, "margin $confidence must stay below the sync cutoff")
    }

    @Test
    fun openingLinesInsideTheCaptureSyncAtZero() {
        val spans = listOf(18_000L to 19_800L, 20_000L to 22_200L)
        val cues = listOf(
            SubtitleSyncCue(18_000L, 19_800L, "Hva har skjedd her?"),
            SubtitleSyncCue(20_000L, 22_200L, "Du sov mens Lisbon"),
        )
        val audio = speechDuring(spans, attackDelay = true).filter { it.timestampMs in 0L..22_000L }
        val result = sync(audio, cues, positionMs = 20_000L)
        assertEquals(0, offsetOf(result))
        assertTrue(result is SubtitleAutoSyncResult.Success, "expected Synced path, got $result")
        val confidence = confidenceOf(result)
        assertTrue(confidence >= 0.3, "margin $confidence")
        assertFalse(result.toString().contains("Insufficient dialogue activity detected"))
        assertFalse(result.toString().contains("Low confidence sync"))
    }

    @Test
    fun audioAfterTheLinesReportsEnergyInsteadOfInsufficientDialogue() {
        val cues = listOf(
            SubtitleSyncCue(18_000L, 19_800L, "Hva har skjedd her?"),
            SubtitleSyncCue(20_000L, 21_600L, "Du sov mens Lisbon"),
        )
        val audio = speechDuring(emptyList(), attackDelay = false).filter { it.timestampMs in 23_000L..43_000L }
        val result = sync(audio, cues, positionMs = 22_000L)
        val message = (result as SubtitleAutoSyncResult.Error).message
        assertFalse(message.contains("Insufficient dialogue activity detected"))
        assertTrue(message.contains("N="), message)
        assertTrue(message.contains("peak="), message)
        val peak = message.substringAfter("peak=").substringBefore(",")
        assertTrue(peak != "0.000", message)
    }

    @Test
    fun lowConfidenceDoesNotWriteSubtitleDelay() {
        // 9700ms is the search step that this formatter prints as +9.6s.
        val result = SubtitleAutoSyncResult.LowConfidence(offsetMs = 9_700, confidence = 0.087)
        assertFalse(result.movesSubtitleDelay())
        val line = autoSyncLowConfidenceMessage(
            offsetMs = 9_700,
            energyStats = "N=256, peak=0.121",
            confidence = 0.087,
            cuesOnScreen = true,
        )
        assertTrue(line.startsWith("Low confidence sync. Offset: +9.6s"), line)
    }

    @Test
    fun playheadSeventeenMinutesFollowsSpeechFiveSecondsEarly() {
        val playhead = 17 * 60 * 1000L
        val lengths = listOf(1400L, 2800L, 1100L, 3200L, 1700L, 2500L, 1300L, 2900L, 1600L, 2100L, 1900L, 3000L)
        val gaps = listOf(500L, 1100L, 350L, 1800L, 700L, 1400L, 450L, 2200L, 900L, 650L, 1600L)
        val cues = ArrayList<SubtitleSyncCue>()
        var cursor = playhead - 18_000L
        lengths.forEachIndexed { index, length ->
            cues.add(SubtitleSyncCue(cursor, cursor + length, "line"))
            cursor += length + gaps[index % gaps.size]
        }
        val speech = cues.map { it.startTimeMs - 5_000L to it.endTimeMs - 5_000L }
        val audio = speechDuring(
            spans = speech,
            attackDelay = true,
            shape = "bursty",
            t0 = playhead - 24_000L,
            t1 = playhead + 8_000L,
        )
        val result = sync(audio, cues, positionMs = playhead)
        val offset = offsetOf(result)
        assertTrue(kotlin.math.abs(offset + 5_000) <= 1_000, "offset $offset")
        assertTrue(offset != 9_600, "edge lag $offset")
        assertTrue(result is SubtitleAutoSyncResult.Success, "expected Synced path, got $result")
        val confidence = confidenceOf(result)
        assertTrue(confidence >= 0.3, "margin $confidence")
        val line = "Synced! Offset: ${formatOffsetMessage(offset)} " +
            "(${formatEnergyStats(audio)}, margin: ${formatMargin(confidence)})"
        assertTrue(line.startsWith("Synced! Offset:"), line)
        assertFalse(line.contains("Low confidence sync"), line)
        assertFalse(line.contains("+9.6s"), line)
        assertTrue(result.movesSubtitleDelay())
    }

    @Test
    fun lowConfidenceWithCuesOnScreenDoesNotAskForMoreDialogue() {
        val message = autoSyncLowConfidenceMessage(
            offsetMs = 0,
            energyStats = "N=303, peak=0.120",
            confidence = -0.233,
            cuesOnScreen = true,
        )
        assertTrue(message.startsWith("Low confidence sync. Offset: +0.0s (N=303, peak=0.120, margin:"))
        assertFalse(message.contains("Try a scene with more dialogue"))
    }

    @Test
    fun lowConfidenceWithoutCuesAsksForDialogue() {
        val message = autoSyncLowConfidenceMessage(
            offsetMs = 0,
            energyStats = "N=1, peak=0.010",
            confidence = 0.01,
            cuesOnScreen = false,
        )
        assertTrue(message.endsWith("Try a scene with more dialogue."))
    }

    private fun sync(
        audio: List<AudioEnergySample>,
        cues: List<SubtitleSyncCue>,
        positionMs: Long = 20_000L,
    ): SubtitleAutoSyncResult {
        return SubtitleAutoSyncEngine.computeOptimalOffset(
            audioSamples = audio,
            subtitleCues = cues,
            currentPositionMs = positionMs,
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

    private fun dialogueCues(spans: List<Pair<Long, Long>>): List<SubtitleSyncCue> {
        return spans.map { (start, end) ->
            SubtitleSyncCue(
                startTimeMs = start,
                endTimeMs = end,
                text = "Hva har skjedd her?",
            )
        }
    }

    /**
     * Speech is quiet at the cue start and loud through the rest of the line,
     * with a small music bed underneath. That is the shape that made an onset
     * spike prefer a lag inside the line.
     */
    private fun speechDuring(
        spans: List<Pair<Long, Long>>,
        attackDelay: Boolean,
        shape: String = "cosine",
        t0: Long = 0L,
        t1: Long = 40_000L,
    ): List<AudioEnergySample> {
        val samples = ArrayList<AudioEnergySample>()
        var time = t0
        while (time <= t1) {
            var energy = 0.03 + 0.008 * (0.5 + 0.5 * sin(time / 700.0))
            val span = spans.firstOrNull { (start, end) -> time >= start && time < end }
            if (span != null) {
                val length = (span.second - span.first).coerceAtLeast(1L)
                val progress = (time - span.first).toDouble() / length.toDouble()
                val formed = when (shape) {
                    "bursty" -> {
                        val body = 0.55 + 0.45 * sin(progress * PI * 6.0)
                        if (attackDelay && progress < 0.12) 0.08 else body
                    }
                    else -> if (!attackDelay || progress > 0.2) {
                        sin(PI * progress).let { wave -> wave * wave }
                    } else {
                        0.05
                    }
                }
                energy += 0.11 * formed
            }
            samples.add(AudioEnergySample(timestampMs = time, energy = energy))
            time += 100L
        }
        return samples
    }

    private fun flatEnergy(): List<AudioEnergySample> {
        val samples = ArrayList<AudioEnergySample>()
        var time = 0L
        while (time <= 40_000L) {
            samples.add(AudioEnergySample(timestampMs = time, energy = 0.4))
            time += 100L
        }
        return samples
    }

    private companion object {
        val dialogueSpans = listOf(
            4_000L to 5_800L,
            8_200L to 10_600L,
            13_000L to 14_400L,
            18_000L to 19_800L,
            20_000L to 22_800L,
            27_500L to 29_400L,
            33_000L to 35_200L,
        )
    }
}
