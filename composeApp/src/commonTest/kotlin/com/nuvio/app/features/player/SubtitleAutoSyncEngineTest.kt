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
    fun speechMaskEqualToCueMaskShiftedMinusFiveSecondsWritesDelay() {
        val cues = equalWidthCues()
        val speech = cues.map { it.startTimeMs - 5_000L to it.endTimeMs - 5_000L }
        val result = sync(maskEnergy(speech), cues, positionMs = 20_000L)
        val offset = offsetOf(result)
        assertTrue(abs(offset + 5_000) <= 100, "offset $offset")
        assertTrue(result is SubtitleAutoSyncResult.Success, "expected Synced path, got $result")
        assertTrue(result.movesSubtitleDelay())
    }

    @Test
    fun speechMaskEqualToCueMaskWithNoShiftDoesNotWriteDelay() {
        val cues = equalWidthCues()
        val speech = cues.map { it.startTimeMs to it.endTimeMs }
        val result = sync(maskEnergy(speech), cues, positionMs = 20_000L)
        assertEquals(0, offsetOf(result))
        assertFalse(result.movesSubtitleDelay())
    }

    @Test
    fun speechPulsesAtTheStartOfLongerCuesShiftedMinusFiveSeconds() {
        val playhead = 17 * 60 * 1000L
        val cues = seventeenMinuteCues(playhead)
        val pulses = cues.map { cue ->
            val pulse = maxOf(400L, cue.endTimeMs - cue.startTimeMs - 1_500L)
            cue.startTimeMs - 5_000L to cue.startTimeMs - 5_000L + pulse
        }
        val audio = maskEnergy(pulses, t0 = playhead - 24_000L, t1 = playhead + 8_000L)
        val result = sync(audio, cues, positionMs = playhead)
        val offset = offsetOf(result)
        assertTrue(abs(offset + 5_000) <= 1_000, "offset $offset")
        assertTrue(offset != -3_500, "offset $offset")
        assertTrue(offset != -3_300, "offset $offset")
        assertTrue(offset != -3_700, "offset $offset")
        assertTrue(offset != 0, "offset $offset")
        val confidence = confidenceOf(result)
        assertTrue(confidence < 0.3, "margin $confidence")
        assertTrue(result is SubtitleAutoSyncResult.LowConfidence, "got $result")
        assertFalse(result.movesSubtitleDelay())
    }

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
        assertTrue(result is SubtitleAutoSyncResult.LowConfidence, "expected low confidence, got $result")
        assertTrue(abs(offsetOf(result)) <= 1_000, "offset ${offsetOf(result)}")
        assertTrue(confidenceOf(result) < 0.3, "margin ${confidenceOf(result)}")
        assertFalse(result.movesSubtitleDelay())
        assertFalse(result.toString().contains("Insufficient dialogue activity detected"))
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
        assertFalse(message.contains("Need at least"), message)
        assertFalse(message.contains("dialogue for reliable sync"), message)
        assertFalse(message.contains("more dialogue"), message)
        assertTrue(message.contains("Energy match span"), message)
        assertTrue(message.contains("N="), message)
        assertTrue(message.contains("peak="), message)
        val peak = message.substringAfter("peak=").substringBefore(",")
        assertTrue(peak != "0.000", message)
    }

    @Test
    fun shortOpeningCaptureSyncsWithoutTheFifteenSecondError() {
        val spans = listOf(18_000L to 19_800L, 20_000L to 22_200L)
        val cues = listOf(
            SubtitleSyncCue(18_000L, 19_800L, "Hva har skjedd her?"),
            SubtitleSyncCue(20_000L, 22_200L, "Du sov mens Lisbon"),
        )
        val audio = speechDuring(spans, attackDelay = true).filter { it.timestampMs in 16_000L..24_000L }
        val spanMs = audio.maxOf { it.timestampMs } - audio.minOf { it.timestampMs }
        assertTrue(spanMs < 15_000L, "fixture span ${spanMs}ms")
        val result = sync(audio, cues, positionMs = 20_000L)
        assertTrue(result is SubtitleAutoSyncResult.Success, "expected Synced path, got $result")
        assertTrue(abs(offsetOf(result)) <= 300, "offset ${offsetOf(result)}")
        assertFalse(result.movesSubtitleDelay())
        assertFalse(result.toString().contains("Need at least"))
        assertFalse(result.toString().contains("Low confidence"))
        val line = "Synced! Offset: ${formatOffsetMessage(offsetOf(result))} " +
            "(${formatEnergyStats(audio)}, ${formatEnergyMatchSpan(audio)}, margin: ${formatMargin(confidenceOf(result))})"
        assertTrue(line.startsWith("Synced! Offset: +0."), line)
        assertTrue(line.contains("Energy match span"), line)
        assertTrue(line.contains("N="), line)
        assertTrue(line.contains("peak="), line)
    }

    @Test
    fun shortCapturePrintsEnergySpanInsteadOfAskingForDialogue() {
        val cues = listOf(
            SubtitleSyncCue(18_000L, 19_800L, "Hva har skjedd her?"),
            SubtitleSyncCue(20_000L, 21_600L, "Du sov mens Lisbon"),
        )
        val audio = speechDuring(emptyList(), attackDelay = false).filter { it.timestampMs in 23_000L..32_000L }
        val spanMs = audio.maxOf { it.timestampMs } - audio.minOf { it.timestampMs }
        assertTrue(spanMs < 15_000L, "fixture span ${spanMs}ms")
        val message = (sync(audio, cues, positionMs = 22_000L) as SubtitleAutoSyncResult.Error).message
        assertTrue(message.contains("Energy match span"), message)
        assertTrue(message.contains("N="), message)
        assertTrue(message.contains("peak="), message)
        assertFalse(message.contains("Need at least"), message)
        assertFalse(message.contains("dialogue for reliable sync"), message)
        assertFalse(message.contains("more dialogue"), message)
        assertFalse(message.contains("Insufficient dialogue"), message)
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
    fun flatSpeechAtSeventeenMinutesClearsThePeriodicCopy() {
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
            attackDelay = false,
            shape = "flat",
            t0 = playhead - 24_000L,
            t1 = playhead + 8_000L,
        )
        val result = sync(audio, cues, positionMs = playhead)
        val offset = offsetOf(result)
        assertTrue(abs(offset + 5_000) <= 1_000, "offset $offset")
        assertTrue(offset != -3_700, "stamp-biased lag $offset")
        assertTrue(result is SubtitleAutoSyncResult.Success, "expected Synced path, got $result")
        val confidence = confidenceOf(result)
        assertTrue(confidence >= 0.3, "margin $confidence")
        val line = "Synced! Offset: ${formatOffsetMessage(offset)} " +
            "(${formatEnergyStats(audio)}, margin: ${formatMargin(confidence)})"
        assertTrue(line.startsWith("Synced! Offset:"), line)
        assertFalse(line.contains("Low confidence sync"), line)
        assertFalse(line.contains("-3.7s"), line)
        assertTrue(result.movesSubtitleDelay())
    }

    @Test
    fun weakDialogueAtSeventeenMinutesStillSyncsNearMinusFive() {
        val playhead = 17 * 60 * 1000L
        val cues = seventeenMinuteCues(playhead)
        val speech = cues.map { it.startTimeMs - 5_000L to it.endTimeMs - 5_000L }
        val audio = weakSpeech(speech, playhead - 24_000L, playhead + 8_000L, stampMs = 0L)
        val result = sync(audio, cues, positionMs = playhead)
        val offset = offsetOf(result)
        assertTrue(abs(offset + 5_000) <= 1_000, "offset $offset")
        assertTrue(offset != -3_300, "device lag $offset")
        assertTrue(offset != -3_700, "stamp-biased lag $offset")
        assertTrue(result is SubtitleAutoSyncResult.Success, "expected Synced path, got $result")
        val confidence = confidenceOf(result)
        assertTrue(confidence >= 0.3, "margin $confidence")
        val line = "Synced! Offset: ${formatOffsetMessage(offset)} " +
            "(${formatEnergyStats(audio)}, margin: ${formatMargin(confidence)})"
        assertTrue(line.startsWith("Synced! Offset:"), line)
        assertFalse(line.contains("Low confidence sync"), line)
        assertFalse(line.contains("-3.3s"), line)
        assertTrue(result.movesSubtitleDelay())
    }

    @Test
    fun uniformTimestampShiftIsWritten() {
        // Speech is 5s early, then every sample is stamped +1.7s. That audio
        // clock is the lag. There is no rival gate refusing it.
        val cues = equalWidthCues()
        val speech = cues.map { it.startTimeMs - 5_000L to it.endTimeMs - 5_000L }
        val audio = maskEnergy(speech).map { it.copy(timestampMs = it.timestampMs + 1_700L) }
        val result = sync(audio, cues, positionMs = 20_000L)
        val offset = offsetOf(result)
        assertTrue(abs(offset + 3_300) <= 100, "offset $offset")
        assertTrue(result is SubtitleAutoSyncResult.Success, "expected Synced path, got $result")
        assertTrue(result.movesSubtitleDelay())
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
    fun lagThatBeatsOffsetZeroIsNotLowConfidenceAtZero() {
        // 25.1s around the 17-minute playhead. Speech is 5s early. The periodic
        // copy near the search edge scores almost as high, so today's fallback
        // prints Low confidence at +0.0s with a negative margin.
        val playhead = 17 * 60 * 1000L
        val cues = seventeenMinuteCues(playhead)
        val speech = cues.map { it.startTimeMs - 5_000L to it.endTimeMs - 5_000L }
        val audio = speechDuring(
            spans = speech,
            attackDelay = true,
            shape = "bursty",
            t0 = playhead - 12_000L,
            t1 = playhead + 13_100L,
        )
        val spanMs = audio.maxOf { it.timestampMs } - audio.minOf { it.timestampMs }
        assertTrue(spanMs in 25_100L until 25_200L, "fixture span ${spanMs}ms")
        assertEquals(252, audio.size)
        val result = sync(audio, cues, positionMs = playhead)
        val offset = offsetOf(result)
        val confidence = confidenceOf(result)
        val line = "Synced! Offset: ${formatOffsetMessage(offset)} " +
            "(${formatEnergyStats(audio)}, ${formatEnergyMatchSpan(audio)}, margin: ${formatMargin(confidence)})"
        assertTrue(result is SubtitleAutoSyncResult.Success, "expected Synced path, got $result")
        assertTrue(abs(offset + 5_000) <= 1_000, "offset $offset")
        assertTrue(confidence >= 0.3, "margin $confidence")
        assertTrue(line.startsWith("Synced! Offset:"), line)
        assertTrue(line.contains("Energy match span 25.1s"), line)
        assertFalse(line.contains("Low confidence sync"), line)
        assertFalse(line.contains("margin: -"), line)
        assertFalse(line.contains("+0.0s"), line)
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
    fun ninePointNineSecondCaptureDoesNotSucceedAtMinusEightPointFive() {
        // N=100 over 9.9s. Speech sits 8.5s early, and the FFT peak overlaps
        // that capture by about 1.4s. Margin stays under 0.3, so Subtitle Delay
        // stays where the user left it.
        val playhead = 17 * 60 * 1000L
        val cues = seventeenMinuteCues(playhead)
        val speech = cues.map { it.startTimeMs - 8_500L to it.endTimeMs - 8_500L }
        val origin = playhead - 27_900L
        val audio = speechDuring(
            spans = speech,
            attackDelay = true,
            shape = "bursty",
            t0 = origin,
            t1 = origin + 9_900L,
        )
        assertEquals(100, audio.size)
        val spanMs = audio.maxOf { it.timestampMs } - audio.minOf { it.timestampMs }
        assertEquals(9_900L, spanMs)
        assertTrue(formatEnergyMatchSpan(audio).endsWith("9.9s"), formatEnergyMatchSpan(audio))
        val result = sync(audio, cues, positionMs = playhead)
        val offset = offsetOf(result)
        val confidence = confidenceOf(result)
        assertTrue(result is SubtitleAutoSyncResult.LowConfidence, "got $result offset $offset margin $confidence")
        assertEquals(-8_500, offset)
        assertTrue(confidence < 0.3, "margin $confidence")
        assertFalse(result.movesSubtitleDelay())
        val line = autoSyncLowConfidenceMessage(
            offsetMs = offset,
            energyStats = formatEnergyStats(audio),
            confidence = confidence,
            cuesOnScreen = true,
        )
        assertTrue(line.startsWith("Low confidence sync."), line)
        assertFalse(line.startsWith("Synced!"), line)
    }

    @Test
    fun speechBurstsShiftedMinusFiveOnTwentySecondCaptureWritesDelay() {
        val playhead = 17 * 60 * 1000L
        val captureStart = playhead - 20_000L
        val captureEnd = playhead
        val bursts = twentySecondBursts(captureStart)
        val cues = bursts.map { (start, end) ->
            SubtitleSyncCue(start + 5_000L, end + 5_000L, "line")
        }
        val audio = maskEnergy(bursts, t0 = captureStart, t1 = captureEnd)
        val spanMs = audio.maxOf { it.timestampMs } - audio.minOf { it.timestampMs }
        assertTrue(spanMs in 19_000L..21_000L, "span ${spanMs}ms")
        val result = sync(audio, cues, positionMs = playhead)
        val offset = offsetOf(result)
        val confidence = confidenceOf(result)
        assertTrue(result is SubtitleAutoSyncResult.Success, "got $result")
        assertTrue(abs(offset + 5_000) <= 1_000, "offset $offset")
        assertTrue(confidence >= 0.300, "margin $confidence")
        assertTrue(result.movesSubtitleDelay())
        val line = "Synced! Offset: ${formatOffsetMessage(offset)} " +
            "(${formatEnergyStats(audio)}, ${formatEnergyMatchSpan(audio)}, margin: ${formatMargin(confidence)})"
        assertTrue(line.startsWith("Synced! Offset:"), line)
        assertTrue(line.contains("margin: 0."), line)
    }

    @Test
    fun alignedBurstsOnTwentySecondCaptureStayAtZero() {
        val playhead = 17 * 60 * 1000L
        val captureStart = playhead - 20_000L
        val captureEnd = playhead
        val bursts = twentySecondBursts(captureStart).map { (start, end) ->
            start + 5_000L to end + 5_000L
        }
        val cues = bursts.map { (start, end) -> SubtitleSyncCue(start, end, "line") }
        val audio = maskEnergy(bursts, t0 = captureStart, t1 = captureEnd)
        val result = sync(audio, cues, positionMs = playhead)
        assertEquals(0, offsetOf(result))
        assertFalse(result.movesSubtitleDelay())
    }

    @Test
    fun burstsOnABedAboveThePercentileDoNotSucceedAtMinusOnePointNine() {
        val playhead = 17 * 60 * 1000L
        val captureStart = playhead - 20_000L
        val captureEnd = playhead
        val bursts = twentySecondBursts(captureStart)
        val cues = bursts.map { (start, end) ->
            SubtitleSyncCue(start + 5_000L, end + 5_000L, "line")
        }
        val audio = rippleBed(bursts, captureStart, captureEnd)
        val result = sync(audio, cues, positionMs = playhead)
        val wroteMinusOnePointNine = result is SubtitleAutoSyncResult.Success && offsetOf(result) == -1_900
        assertFalse(wroteMinusOnePointNine, "got $result")
        assertTrue(result is SubtitleAutoSyncResult.LowConfidence, "a filled bed got $result")
        assertFalse(result.movesSubtitleDelay())
    }

    @Test
    fun cacheDumpShorterThanLookbackFallsThroughToUrl() {
        val playhead = 17 * 60 * 1000L
        val plan = SubtitleAutoSyncEngine.planPcmCapture(
            hasCache = true,
            cacheOriginMs = playhead - 9_900L,
            cacheDurationMs = 9_900L,
            playheadMs = playhead,
        )
        assertFalse(plan.cacheFirst)
        assertFalse(plan.includeCache)
        assertEquals(playhead - 20_000L, plan.urlDecodeStartMs)
        assertFalse(SubtitleAutoSyncEngine.cacheDumpWins(9_900L))
    }

    @Test
    fun cacheDumpAtLeastTwentySecondsCanLead() {
        val playhead = 17 * 60 * 1000L
        val plan = SubtitleAutoSyncEngine.planPcmCapture(
            hasCache = true,
            cacheOriginMs = playhead - 20_000L,
            cacheDurationMs = 25_100L,
            playheadMs = playhead,
        )
        assertTrue(plan.cacheFirst)
        assertTrue(plan.includeCache)
        assertEquals(playhead - 20_000L, plan.urlDecodeStartMs)
        assertTrue(SubtitleAutoSyncEngine.cacheDumpWins(20_000L))
        assertTrue(SubtitleAutoSyncEngine.cacheDumpWins(25_100L))
    }

    @Test
    fun cacheThatStartsAtThePlayheadStaysBehindTheUrl() {
        val playhead = 17 * 60 * 1000L
        val plan = SubtitleAutoSyncEngine.planPcmCapture(
            hasCache = true,
            cacheOriginMs = playhead,
            cacheDurationMs = 30_000L,
            playheadMs = playhead,
        )
        assertFalse(plan.cacheFirst)
        assertTrue(plan.includeCache)
        assertEquals(playhead - 20_000L, plan.urlDecodeStartMs)
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

    private fun seventeenMinuteCues(playhead: Long): List<SubtitleSyncCue> {
        val lengths = listOf(1400L, 2800L, 1100L, 3200L, 1700L, 2500L, 1300L, 2900L, 1600L, 2100L, 1900L, 3000L)
        val gaps = listOf(500L, 1100L, 350L, 1800L, 700L, 1400L, 450L, 2200L, 900L, 650L, 1600L)
        val cues = ArrayList<SubtitleSyncCue>()
        var cursor = playhead - 18_000L
        lengths.forEachIndexed { index, length ->
            cues.add(SubtitleSyncCue(cursor, cursor + length, "line"))
            cursor += length + gaps[index % gaps.size]
        }
        return cues
    }

    /**
     * Speech follows the cues, with dropped syllables and a laugh track.
     * That flattens the lead over a bump next to offset 0 without moving the lag.
     */
    private fun weakSpeech(
        spans: List<Pair<Long, Long>>,
        t0: Long,
        t1: Long,
        stampMs: Long,
    ): List<AudioEnergySample> {
        val samples = ArrayList<AudioEnergySample>()
        var time = t0
        while (time <= t1) {
            var energy = 0.04 + 0.015 * sin(time / 500.0)
            val span = spans.firstOrNull { (start, end) -> time >= start && time < end }
            if (span != null) {
                val length = (span.second - span.first).coerceAtLeast(1L)
                val progress = (time - span.first).toDouble() / length.toDouble()
                val syllable = sin(progress * PI * 5.0)
                val gate = ((time / 100L) % 5L).toInt()
                if (syllable > 0.2 && gate != 0) {
                    energy += 0.08 * syllable
                }
            }
            val laugh = ((time / 100L) % 40L).toInt()
            if (laugh == 7 || laugh == 8 || laugh == 9 || laugh == 10) {
                energy += 0.10
            }
            samples.add(AudioEnergySample(timestampMs = time + stampMs, energy = energy.coerceAtLeast(0.0)))
            time += 100L
        }
        return samples
    }

    /** High during [spans], quiet elsewhere, so the lifted mask matches the spans. */
    private fun maskEnergy(
        spans: List<Pair<Long, Long>>,
        t0: Long = 0L,
        t1: Long = 40_000L,
    ): List<AudioEnergySample> {
        val samples = ArrayList<AudioEnergySample>()
        var time = t0
        while (time <= t1) {
            val speaking = spans.any { (start, end) -> time >= start && time < end }
            samples.add(AudioEnergySample(timestampMs = time, energy = if (speaking) 0.5 else 0.02))
            time += 100L
        }
        return samples
    }

    /**
     * Four speech bursts inside a 20s capture, with quiet gaps longer than a click.
     * The cues for the shifted case are these spans plus 5s.
     */
    private fun twentySecondBursts(captureStart: Long): List<Pair<Long, Long>> {
        return listOf(
            captureStart + 2_000L to captureStart + 3_800L,
            captureStart + 5_500L to captureStart + 7_800L,
            captureStart + 10_000L to captureStart + 12_400L,
            captureStart + 14_500L to captureStart + 16_800L,
        )
    }

    /**
     * The same bursts sit on a ripple that stays above the 35th percentile.
     * The troughs are one bin, so the activity rule joins them into one bed.
     */
    private fun rippleBed(
        bursts: List<Pair<Long, Long>>,
        t0: Long,
        t1: Long,
    ): List<AudioEnergySample> {
        val samples = ArrayList<AudioEnergySample>()
        var time = t0
        var index = 0
        while (time <= t1) {
            var energy = 0.40 + 0.002 * sin(time / 50.0) + 0.0001 * (index % 7)
            if (bursts.any { (start, end) -> time >= start && time < end }) {
                energy += 0.30
            }
            samples.add(AudioEnergySample(timestampMs = time, energy = energy))
            time += 100L
            index += 1
        }
        return samples
    }

    private fun equalWidthCues(): List<SubtitleSyncCue> {
        return listOf(
            8_000L to 10_000L,
            12_000L to 14_500L,
            17_000L to 19_000L,
            22_000L to 25_000L,
        ).map { (start, end) -> SubtitleSyncCue(start, end, "line") }
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
                    "flat" -> 1.0
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
