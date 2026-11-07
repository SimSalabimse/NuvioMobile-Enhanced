package com.nuvio.app.features.analysis

import com.nuvio.app.core.serialization.LenientDefaultsJson
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString

class SeasonFingerprintPassTest {
    @Test
    fun savingEpisodeTwoFillsEpisodeOneIntroAndCredits() {
        val season = Season(3)
        val first = season.episode("show__s1e1", introAt = 900, creditsAt = 4300).copy(analyzedAtMs = 1, fileName = "e1.mkv")
        val second = season.episode("show__s1e2", introAt = 30, creditsAt = 4000).copy(analyzedAtMs = 2, fileName = "e2.mkv")

        val filled = backfillSeasonOnFingerprintSave(saved = second, stored = listOf(first))
            .first { it.key == first.key }

        assertEquals(90_000L, filled.introStartMs)
        assertEquals(130_000L, filled.introEndMs)
        assertEquals(first.tailStartMs + 430_000L, filled.creditsStartMs)
        assertEquals(2_400_000L, filled.creditsEndMs)
    }

    @Test
    fun recapIsFilledFromStoredFingerprintsWhenTheOtherEpisodeAlreadyHasAnIntro() {
        val random = Random(3)
        fun noise(size: Int) = IntArray(size) { random.nextInt() }
        val recap = IntArray(150) { 0x13570000 + it * 17 }
        val intro = IntArray(400) { random.nextInt() }
        val firstHead = recap + noise(750) + intro + noise(4_700)
        val secondHead = noise(30) + intro + noise(70) + recap + noise(5_350)
        val tail = noise(4_800)
        val first = fingerprinted("show__s1e1", firstHead, tail).copy(
            analyzedAtMs = 1,
            fileName = null,
            introStartMs = 90_000L,
            introEndMs = 130_000L,
            creditsStartMs = 2_000_000L,
            creditsEndMs = 2_400_000L,
        )
        val second = fingerprinted("show__s1e2", secondHead, tail).copy(
            analyzedAtMs = 2,
            fileName = "e2.mkv",
            introStartMs = 3_000L,
            introEndMs = 43_000L,
            creditsStartMs = 2_000_000L,
            creditsEndMs = 2_400_000L,
        )

        val filled = backfillSeasonOnFingerprintSave(saved = second, stored = listOf(first))
            .first { it.key == first.key }

        assertNull(first.fileName)
        assertEquals(0L, filled.recapStartMs)
        assertEquals(15_000L, filled.recapEndMs)
        assertEquals(90_000L, filled.introStartMs)
    }

    @Test
    fun aFifthEpisodeStillUpdatesAnOlderSiblingOutsideTheNewestFour() {
        val season = Season(9)
        val stored = (1..5).map { episode ->
            season.episode("show__s1e$episode", introAt = 900, creditsAt = 4300).copy(
                analyzedAtMs = episode.toLong(),
                fileName = "e$episode.mkv",
                introStartMs = if (episode == 1) null else 90_000L,
                introEndMs = if (episode == 1) null else 130_000L,
                creditsStartMs = if (episode == 1) null else 2_000_000L,
                creditsEndMs = if (episode == 1) null else 2_400_000L,
            )
        }
        val saved = stored.last()
        val filled = backfillSeasonOnFingerprintSave(saved = saved, stored = stored.dropLast(1))
            .first { it.key == "show__s1e1" }

        assertEquals(90_000L, filled.introStartMs)
        assertEquals(130_000L, filled.introEndMs)
        assertNotNull(filled.creditsStartMs)
    }

    @Test
    fun existingTimesStayAndUserMarksWinOnSave() {
        val season = Season(3)
        val marked = UserSegment(startMs = 1_000L, endMs = 2_000L, durationMs = 2_400_000L)
        val first = season.episode("marks__s1e1", introAt = 900, creditsAt = 4300).copy(
            analyzedAtMs = 1,
            introStartMs = 10_000L,
            introEndMs = 50_000L,
            userSegments = mapOf("outro" to marked),
        )
        val second = season.episode("marks__s1e2", introAt = 30, creditsAt = 4000).copy(analyzedAtMs = 2)

        val filled = backfillSeasonOnFingerprintSave(saved = second, stored = listOf(first))
            .first { it.key == first.key }

        assertEquals(10_000L, filled.introStartMs)
        assertEquals(50_000L, filled.introEndMs)
        assertEquals(marked, filled.userSegments["outro"])
        MediaAnalysisStore.save(filled)
        val stored = assertNotNull(MediaAnalysisStore.load(first.key))
        assertEquals(10_000L, stored.introStartMs)
        assertEquals(50_000L, stored.introEndMs)
        assertEquals(1_000L, stored.creditsStartMs)
        assertEquals(2_000L, stored.creditsEndMs)
    }

    @Test
    fun aSecondPassReplacesALowConfidenceMiss() {
        val season = Season(31)
        val missed = season.episode("sticky__s1e1", introAt = 900, creditsAt = 4300).copy(
            introStartMs = 200_000L,
            introEndMs = 280_000L,
            introConfidence = SegmentConfidence(agreeingSiblings = 1, avgBitErrors = 1.0),
            creditsStartMs = 2_000_000L,
            creditsEndMs = 2_400_000L,
            creditsConfidence = SegmentConfidence(agreeingSiblings = 4, avgBitErrors = 0.0),
        )
        val filled = seasonFingerprintPass(listOf(missed) + agreeingSiblings(season, "sticky"))
            .first { it.key == missed.key }
        assertEquals(90_000L, filled.introStartMs)
        assertEquals(130_000L, filled.introEndMs)
        assertEquals(3, filled.introConfidence?.agreeingSiblings)
        assertEquals(2_000_000L, filled.creditsStartMs)
    }

    @Test
    fun anEqualOrWeakerScoreDoesNotReplaceTheStoredIntro() {
        val season = Season(32)
        val stored = season.episode("weaker__s1e1", introAt = 900, creditsAt = 4300).copy(
            introStartMs = 200_000L,
            introEndMs = 280_000L,
            introConfidence = SegmentConfidence(agreeingSiblings = 4, avgBitErrors = 0.0),
            creditsStartMs = 2_000_000L,
            creditsEndMs = 2_400_000L,
            creditsConfidence = SegmentConfidence(agreeingSiblings = 4, avgBitErrors = 0.0),
        )
        val updated = seasonFingerprintPass(listOf(stored) + agreeingSiblings(season, "weaker"))
            .firstOrNull { it.key == stored.key }
            ?: stored
        assertEquals(200_000L, updated.introStartMs)
        assertEquals(280_000L, updated.introEndMs)
    }

    @Test
    fun aUserMarkIsNeverReplaced() {
        val season = Season(33)
        val marked = UserSegment(startMs = 1_000L, endMs = 20_000L, durationMs = 2_400_000L)
        val stored = season.episode("marked__s1e1", introAt = 900, creditsAt = 4300).copy(
            introStartMs = 1_000L,
            introEndMs = 20_000L,
            introConfidence = SegmentConfidence(agreeingSiblings = 1, avgBitErrors = 3.0),
            creditsStartMs = 2_000_000L,
            creditsEndMs = 2_400_000L,
            creditsConfidence = SegmentConfidence(agreeingSiblings = 4, avgBitErrors = 0.0),
            userSegments = mapOf("intro" to marked),
        )
        val updated = seasonFingerprintPass(listOf(stored) + agreeingSiblings(season, "marked"))
            .firstOrNull { it.key == stored.key }
            ?: stored
        assertEquals(1_000L, updated.introStartMs)
        assertEquals(20_000L, updated.introEndMs)
        assertEquals(marked, updated.userSegments["intro"])
    }

    @Test
    fun aWrongTapWithoutASavedRangeRejectsNothing() {
        assertNull(rejectedRangeForCorrection("intro", 90_000L, 130_000L, 90_000L, 130_000L))
        assertEquals(
            RejectedSegment("intro", 90_000L, 130_000L),
            rejectedRangeForCorrection("intro", 90_000L, 130_000L, 5_000L, 25_000L),
        )
    }

    @Test
    fun aSavedCorrectionKeepsTheOldRangeFromComingBack() = runTest {
        val show = "oldrange"
        val season = Season(77)
        val key = MediaAnalysisStore.keyFor(show, 1, 1)
        val episode = season.episode(key, introAt = 900, creditsAt = 4300).copy(
            introStartMs = 90_000L,
            introEndMs = 130_000L,
            creditsStartMs = 2_000_000L,
            creditsEndMs = 2_400_000L,
            introConfidence = SegmentConfidence(agreeingSiblings = 1, avgBitErrors = 0.0),
        )
        MediaAnalysisStore.save(episode)
        SkipLearning.recordMark(show, 1, 1, "intro", 5_000L, 25_000L, durationMs = 2_400_000L)
        val marked = assertNotNull(MediaAnalysisStore.load(key))
        assertEquals(5_000L, marked.introStartMs)
        assertEquals(25_000L, marked.introEndMs)
        assertTrue(marked.rejectedSegments.contains(RejectedSegment("intro", 90_000L, 130_000L)))

        val sibling = season.episode(MediaAnalysisStore.keyFor(show, 1, 2), introAt = 30, creditsAt = 4000)
        val kept = seasonFingerprintPass(listOf(marked, sibling)).firstOrNull { it.key == key } ?: marked
        assertEquals(5_000L, kept.introStartMs)
        assertEquals(25_000L, kept.introEndMs)

        val unmarked = marked.copy(
            userSegments = emptyMap(),
            introStartMs = 90_000L,
            introEndMs = 130_000L,
            introConfidence = SegmentConfidence(agreeingSiblings = 1, avgBitErrors = 0.0),
        )
        val cleared = seasonFingerprintPass(listOf(unmarked, sibling)).first { it.key == key }
        assertNull(cleared.introStartMs)
        assertNull(cleared.introEndMs)
    }

    @Test
    fun creditsBedSnapsToTheSilenceBeforeThePostCreditsScene() {
        val bed = noise(99, 500)
        val episode = creditsEpisode("bedsnap__s1e1", bed, backgroundSeed = 1).copy(
            energy = creditsEnergy(),
            fileName = "e1.mkv",
        )
        val remembered = episode.rememberingSilentRanges()
        MediaAnalysisStore.save(remembered)
        MediaAnalysisStore.forgetFileData(remembered.key)
        val kept = assertNotNull(MediaAnalysisStore.load(remembered.key))
        assertNull(kept.energy)
        assertTrue(kept.silentRanges.contains(SilentRange(2_219_500L, 2_220_400L)))
        assertTrue(kept.silentRanges.contains(SilentRange(2_279_500L, 2_280_300L)))

        val sibling = creditsEpisode("bedsnap__s1e2", bed, backgroundSeed = 2)
        val snapped = seasonFingerprintPass(listOf(kept, sibling)).first { it.key == kept.key }
        assertEquals(2_170_000L, snapped.creditsStartMs)
        assertEquals(2_219_500L, snapped.creditsEndMs)
    }

    @Test
    fun creditsEdgeStaysWhenNoSilenceIsNear() {
        val bed = noise(98, 500)
        val episode = creditsEpisode("bedfar__s1e1", bed, backgroundSeed = 3).copy(
            silentRanges = listOf(SilentRange(2_300_000L, 2_301_000L)),
        )
        val sibling = creditsEpisode("bedfar__s1e2", bed, backgroundSeed = 4)
        val stayed = seasonFingerprintPass(listOf(episode, sibling)).first { it.key == episode.key }
        assertEquals(2_170_000L, stayed.creditsStartMs)
        assertEquals(2_220_000L, stayed.creditsEndMs)
    }

    @Test
    fun storedSilentRangesStaySmallAfterTheFileIsGone() {
        val levels = IntArray(24_000) { 200 }
        for (i in 100 until 102) levels[i] = 0
        for (i in 5_000 until 5_020) levels[i] = 40
        for (i in 22_195 until 22_204) levels[i] = 10
        val energy = encodeEnergyLevels(levels)
        assertTrue(energy.length > 20_000)
        val episode = MediaAnalysisResult(
            key = "silencesize__s1e1",
            analyzedAtMs = 0L,
            durationMs = 2_400_000L,
            fileName = "ep.mkv",
            energy = energy,
        ).rememberingSilentRanges()
        val stored = LenientDefaultsJson.encodeToString(ListSerializer(SilentRange.serializer()), episode.silentRanges)
        assertEquals(2, episode.silentRanges.size)
        assertTrue(stored.length < 500)
        assertTrue(stored.length * 20 < energy.length)
        MediaAnalysisStore.save(episode)
        MediaAnalysisStore.forgetFileData(episode.key)
        val kept = assertNotNull(MediaAnalysisStore.load(episode.key))
        assertNull(kept.energy)
        assertNull(kept.fileName)
        assertEquals(episode.silentRanges, kept.silentRanges)
    }

    private fun creditsEpisode(key: String, bed: IntArray, backgroundSeed: Int): MediaAnalysisResult {
        val tail = noise(backgroundSeed, 4_800)
        bed.copyInto(tail, destinationOffset = 2_500)
        return MediaAnalysisResult(
            key = key,
            analyzedAtMs = 0L,
            durationMs = 2_400_000L,
            fingerprintStepMs = 100,
            headFingerprint = encodeFingerprint(noise(backgroundSeed + 50, 6_000)),
            tailFingerprint = encodeFingerprint(tail),
            tailStartMs = 2_400_000L - 4_800L * 100,
        )
    }

    private fun creditsEnergy(): String {
        val levels = IntArray(24_000) { 200 }
        for (i in 22_195 until 22_204) levels[i] = 0
        for (i in 22_795 until 22_803) levels[i] = 0
        return encodeEnergyLevels(levels)
    }

    private fun noise(seed: Int, size: Int): IntArray {
        val random = Random(seed)
        return IntArray(size) { random.nextInt() }
    }

    private fun agreeingSiblings(season: Season, show: String) = (2..4).map { episode ->
        season.episode("${show}__s1e$episode", introAt = 30, creditsAt = 4000).copy(analyzedAtMs = episode.toLong())
    }

    private fun fingerprinted(key: String, head: IntArray, tail: IntArray) = MediaAnalysisResult(
        key = key,
        analyzedAtMs = 0L,
        durationMs = 2_400_000L,
        fingerprintStepMs = 100,
        headFingerprint = encodeFingerprint(head),
        tailFingerprint = encodeFingerprint(tail),
        tailStartMs = 2_400_000L - 4_800L * 100,
    )

    /** Episodes of one season sharing an intro and credits at the given 100 ms frames. */
    private class Season(seed: Int) {
        private val random = Random(seed)
        private val intro = IntArray(400) { random.nextInt() }
        private val credits = IntArray(500) { random.nextInt() }
        private fun noise(size: Int) = IntArray(size) { random.nextInt() }

        fun episode(key: String, introAt: Int, creditsAt: Int): MediaAnalysisResult {
            val head = noise(introAt) + intro + noise(6_000 - introAt - intro.size)
            val tail = noise(creditsAt) + credits + noise(4_800 - creditsAt - credits.size)
            return MediaAnalysisResult(
                key = key,
                analyzedAtMs = 0L,
                durationMs = 2_400_000L,
                fingerprintStepMs = 100,
                headFingerprint = encodeFingerprint(head),
                tailFingerprint = encodeFingerprint(tail),
                tailStartMs = 2_400_000L - 4_800L * 100,
            )
        }
    }
}

class AnalysisAttemptsTest {
    @Test
    fun nullClipIsAttemptOneAndALaterKickRunsAgain() {
        val now = 1_000_000L
        val kind = classifyAnalysisAttempt(scopeActive = true, analyserFailed = false, clipUnavailable = true)
        assertEquals(AnalysisAttemptKind.Retryable, kind)
        val update = updateAnalysisAttempts(prior = 0, kind = kind, nowMs = now)
        assertEquals(1, update.attempts)
        assertTrue(update.write)
        assertEquals(now + 2 * 60_000L, update.retryNotBeforeMs)

        val stored = stored(attempts = update.attempts)
        assertTrue(stored.needsSiblingClipRetry())
        // In this session the delay holds it; once that passes (or the app launches) it is queued.
        assertEquals(false, shouldQueueAnalysis(stored, stored.fileName, needsDerivedWork = false, backingOff = true))
        assertEquals(true, shouldQueueAnalysis(stored, stored.fileName, needsDerivedWork = false, backingOff = false))
    }

    @Test
    fun analyserFailureIsRetryableAndCancelIsNot() {
        assertEquals(
            AnalysisAttemptKind.Retryable,
            classifyAnalysisAttempt(scopeActive = true, analyserFailed = true, clipUnavailable = false),
        )
        val cancelled = classifyAnalysisAttempt(scopeActive = false, analyserFailed = true, clipUnavailable = true)
        assertEquals(AnalysisAttemptKind.Cancelled, cancelled)
        val update = updateAnalysisAttempts(prior = 2, kind = cancelled, nowMs = 0L)
        assertEquals(2, update.attempts)
        assertEquals(false, update.write)
    }

    @Test
    fun newFileNameResetsTheAttemptCount() {
        val stored = stored(attempts = 2, fileName = "old.mkv")
        assertEquals(0, resolveAnalysisAttempts(stored, "new.mkv"))
        assertEquals(true, shouldQueueAnalysis(stored, "new.mkv", needsDerivedWork = false, backingOff = false))
        val retry = updateAnalysisAttempts(prior = 0, kind = AnalysisAttemptKind.Retryable, nowMs = 0L)
        assertEquals(1, retry.attempts)
    }

    @Test
    fun thirdAttemptStaysIdle() {
        val update = updateAnalysisAttempts(prior = 2, kind = AnalysisAttemptKind.Retryable, nowMs = 0L)
        assertEquals(3, update.attempts)
        assertEquals(true, update.giveUp)
        assertNull(update.retryNotBeforeMs)
        assertNull(analysisRetryDelayMs(3))
        assertEquals(4 * 60_000L, analysisRetryDelayMs(2))
        val stored = stored(attempts = 3)
        assertEquals(false, shouldQueueAnalysis(stored, stored.fileName, needsDerivedWork = true, backingOff = false))
        assertEquals(false, stored.needsSiblingClipRetry())
    }

    private fun stored(attempts: Int, fileName: String = "ep.mkv") = MediaAnalysisResult(
        key = "show__s1e1",
        analyzedAtMs = 1L,
        durationMs = 1L,
        fileName = fileName,
        headFingerprint = "abc",
        analysisAttempts = attempts,
    )
}
