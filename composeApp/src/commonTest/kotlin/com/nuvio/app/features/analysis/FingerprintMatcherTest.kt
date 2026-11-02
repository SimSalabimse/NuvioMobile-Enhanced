package com.nuvio.app.features.analysis

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FingerprintMatcherTest {
    private fun noise(random: Random, size: Int) = IntArray(size) { random.nextInt() }

    @Test
    fun findsSharedIntroAtDifferentOffsets() {
        val random = Random(7)
        val intro = noise(random, 600) // 60 s at 100 ms
        val a = noise(random, 300) + intro + noise(random, 2000)
        val b = noise(random, 900) + intro.map { it xor 0b101 } + noise(random, 1500) // 2 bit errors
        val segment = assertNotNull(FingerprintMatcher.longestSharedSegment(a, b, minFrames = 150, maxFrames = 1500))
        assertEquals(300, segment.startA)
        assertEquals(900, segment.endA)
        assertEquals(900, segment.startB)
        assertEquals(2.0, segment.avgBitErrors)
    }

    @Test
    fun unrelatedAudioHasNoMatch() {
        val random = Random(11)
        assertNull(FingerprintMatcher.longestSharedSegment(noise(random, 1200), noise(random, 1200), minFrames = 150))
    }

    /** Episodes of one season sharing an intro and credits at the given 100 ms frames. */
    private class Season(seed: Int) {
        private val random = Random(seed)
        private val intro = IntArray(400) { random.nextInt() }
        private val credits = IntArray(500) { random.nextInt() }
        private fun noise(size: Int) = IntArray(size) { random.nextInt() }

        fun episode(key: String, introAt: Int, creditsAt: Int): MediaAnalysisResult {
            val head = noise(introAt) + intro + noise(6000 - introAt - intro.size)
            val tail = noise(creditsAt) + credits + noise(4800 - creditsAt - credits.size)
            return MediaAnalysisResult(
                key = key,
                analyzedAtMs = 0L,
                durationMs = 2_400_000L,
                headFingerprint = encodeFingerprint(head),
                tailFingerprint = encodeFingerprint(tail),
                tailStartMs = 2_400_000L - TAIL_FINGERPRINT_MS,
            )
        }
    }

    @Test
    fun seasonDetectorReportsIntroAndCreditsTimes() {
        val season = Season(3)
        val one = season.episode("s1e1", introAt = 900, creditsAt = 4300)
        val two = season.episode("s1e2", introAt = 30, creditsAt = 4000)
        val found = SeasonSegmentDetector.detect(one, listOf(two))
        assertEquals(90_000L, found.introStartMs)
        assertEquals(130_000L, found.introEndMs)
        assertEquals(one.tailStartMs + 430_000L, found.creditsStartMs)
        // The credits run to the end of the tail window, so they end at the file's duration.
        assertEquals(2_400_000L, found.creditsEndMs)
        assertEquals(1, found.introConfidence?.agreeingSiblings)
        assertEquals(0.0, found.introConfidence?.avgBitErrors)
        assertEquals(1, found.creditsConfidence?.agreeingSiblings)
    }

    @Test
    fun deletedEpisodeStillTeachesTheNextDownload() {
        // Download E1, analyse it, delete it; then download E2 alone.
        val season = Season(5)
        val key = "seqtest__s1e1"
        MediaAnalysisStore.save(
            season.episode(key, introAt = 900, creditsAt = 4300)
                .copy(fileName = "e1.mkv", energy = "AAAA", integratedLufs = -20.0),
        )
        MediaAnalysisStore.forgetFileData(key)
        val kept = assertNotNull(MediaAnalysisStore.load(key))
        assertNull(kept.fileName)
        assertNull(kept.energy)
        assertNotNull(kept.headFingerprint)
        assertNotNull(kept.tailFingerprint)

        val two = season.episode("seqtest__s1e2", introAt = 30, creditsAt = 4000)
        val found = SeasonSegmentDetector.detect(two, listOf(kept))
        assertEquals(3_000L, found.introStartMs)
        assertEquals(43_000L, found.introEndMs)
        assertEquals(two.tailStartMs + 400_000L, found.creditsStartMs)
    }

    @Test
    fun twoOfThreeShorterRunsBeatOneLongerRun() {
        val found = SeasonSegmentDetector.detect(
            episodeFrom("vote2__s1e1", headWithShortAndLong()),
            listOf(
                episodeFrom("vote2__s1e2", headWith(shortAt = 40)),
                episodeFrom("vote2__s1e3", headWith(shortAt = 80)),
                episodeFrom("vote2__s1e4", headWithLongOnly()),
            ),
        )
        assertEquals(10_000L, found.introStartMs)
        assertEquals(30_000L, found.introEndMs)
        assertEquals(2, found.introConfidence?.agreeingSiblings)
    }

    @Test
    fun threeAgreeingShorterRunsBeatOneLongDecoy() {
        val found = SeasonSegmentDetector.detect(
            episodeFrom("vote3__s1e1", headWithShortAndLong()),
            listOf(
                episodeFrom("vote3__s1e2", headWith(shortAt = 40)),
                episodeFrom("vote3__s1e3", headWith(shortAt = 90)),
                episodeFrom("vote3__s1e4", headWith(shortAt = 140)),
                episodeFrom("vote3__s1e5", headWithLongOnly()),
            ),
        )
        assertEquals(10_000L, found.introStartMs)
        assertEquals(30_000L, found.introEndMs)
        assertEquals(3, found.introConfidence?.agreeingSiblings)
        assertEquals(0.0, found.introConfidence?.avgBitErrors)
        assertNull(found.creditsStartMs)
    }

    @Test
    fun twoSiblingsKeepTheLongestRun() {
        val found = SeasonSegmentDetector.detect(
            episodeFrom("vote1__s1e1", headWithShortAndLong()),
            listOf(
                episodeFrom("vote1__s1e2", headWith(shortAt = 40)),
                episodeFrom("vote1__s1e3", headWithLongOnly()),
            ),
        )
        assertEquals(150_000L, found.introStartMs)
        assertEquals(230_000L, found.introEndMs)
        assertEquals(1, found.introConfidence?.agreeingSiblings)
    }

    @Test
    fun aSingleLongRunDoesNotWinWhenThreeSiblingsExist() {
        val found = SeasonSegmentDetector.detect(
            episodeFrom("vote0__s1e1", headWithShortAndLong()),
            listOf(
                episodeFrom("vote0__s1e2", headWithLongOnly()),
                episodeFrom("vote0__s1e3", noise(7, HEAD_FRAMES)),
                episodeFrom("vote0__s1e4", noise(8, HEAD_FRAMES)),
            ),
        )
        assertNull(found.introStartMs)
        assertNull(found.introConfidence)
    }

    @Test
    fun aStraySingleHitDoesNotStretchTheRecap() {
        val random = Random(19)
        fun noise(size: Int) = IntArray(size) { random.nextInt() }
        val recap = IntArray(150) { 0x51A00000 + it * 17 }
        val stray = IntArray(50) { 0x575A0000 + it * 13 }
        val intro = IntArray(400) { 0x11F00000 + it * 19 }
        val head = recap + noise(350) + stray + noise(350) + intro + noise(400)
        val episode = episodeFrom("recapvote__s1e1", head).copy(introStartMs = 90_000L, introEndMs = 130_000L)
        fun sibling(key: String, clip: IntArray, at: Int) =
            episodeFrom(key, noise(at) + clip + noise(2_000 - at - clip.size))
        val found = RecapDetector.detect(
            episode,
            listOf(
                sibling("recapvote__s1e2", recap, 400),
                sibling("recapvote__s1e3", recap, 700),
                sibling("recapvote__s1e4", recap, 200),
                sibling("recapvote__s1e5", stray, 100),
            ),
        )
        assertEquals(0L, found?.first)
        assertEquals(15_000L, found?.second)
    }

    @Test
    fun aRejectedIntroIsNotDetectedAgain() {
        val season = Season(8)
        val episode = season.episode("reject__s1e1", introAt = 900, creditsAt = 4300).copy(
            rejectedSegments = listOf(RejectedSegment("intro", 90_000L, 130_000L)),
        )
        val other = season.episode("reject__s1e2", introAt = 30, creditsAt = 4000)
        val found = SeasonSegmentDetector.detect(episode, listOf(other))
        assertNull(found.introStartMs)
        assertEquals(episode.tailStartMs + 430_000L, found.creditsStartMs)
    }

    @Test
    fun higherSiblingCountBeatsLowerBitErrors() {
        val few = SegmentConfidence(agreeingSiblings = 2, avgBitErrors = 1.0)
        val many = SegmentConfidence(agreeingSiblings = 3, avgBitErrors = 4.0)
        val cleaner = SegmentConfidence(agreeingSiblings = 3, avgBitErrors = 1.0)
        assertTrue(many.strictlyBeats(few))
        assertFalse(few.strictlyBeats(many))
        assertTrue(cleaner.strictlyBeats(many))
        assertFalse(many.strictlyBeats(cleaner))
        assertFalse(cleaner.strictlyBeats(cleaner))
    }

    @Test
    fun theIntroDbAgreementOnlyRaisesConfidence() {
        val season = Season(9)
        val one = season.episode("agree__s1e1", introAt = 900, creditsAt = 4300)
        val two = season.episode("agree__s1e2", introAt = 30, creditsAt = 4000)
        val found = SeasonSegmentDetector.detect(one, listOf(two))
        val intro = assertNotNull(found.introStartMs) to assertNotNull(found.introEndMs)
        val agreed = SeasonSegmentDetector.detect(one, listOf(two), theIntroDbIntro = intro)
        val raised = assertNotNull(agreed.introConfidence)
        assertTrue(raised.theIntroDbAgrees)
        assertTrue(raised.strictlyBeats(assertNotNull(found.introConfidence)))
        assertEquals(found.introStartMs, agreed.introStartMs)
        assertEquals(found.introEndMs, agreed.introEndMs)
        assertEquals(found.creditsStartMs, agreed.creditsStartMs)

        val disagreed = SeasonSegmentDetector.detect(
            one,
            listOf(two),
            theIntroDbIntro = 400_000L to 460_000L,
            theIntroDbCredits = 10_000L to 20_000L,
        )
        assertEquals(found.introStartMs, disagreed.introStartMs)
        assertEquals(found.introEndMs, disagreed.introEndMs)
        assertEquals(found.creditsStartMs, disagreed.creditsStartMs)
        assertEquals(found.creditsEndMs, disagreed.creditsEndMs)
        assertEquals(false, disagreed.introConfidence?.theIntroDbAgrees)
        assertEquals(false, disagreed.creditsConfidence?.theIntroDbAgrees)
        assertFalse(assertNotNull(disagreed.introConfidence).strictlyBeats(assertNotNull(found.introConfidence)))
    }

    @Test
    fun aTheIntroDbTimeDoesNotInventASegment() {
        val one = Season(12).episode("noidb__s1e1", introAt = 900, creditsAt = 4300)
        val other = Season(13).episode("noidb__s1e2", introAt = 30, creditsAt = 4000)
        val found = SeasonSegmentDetector.detect(
            one,
            listOf(other),
            theIntroDbIntro = 90_000L to 130_000L,
            theIntroDbCredits = one.tailStartMs to one.durationMs,
        )
        assertNull(found.introStartMs)
        assertNull(found.creditsStartMs)
        assertNull(found.introConfidence)
    }

    @Test
    fun creditsLongerThanThreeMinutesKeepTheLastThreeMinutes() {
        val tail = noise(21, 4_800)
        val one = episodeWithTails("cap__s1e1", noise(1, 6_000), tail)
        val two = episodeWithTails("cap__s1e2", noise(2, 6_000), tail)
        val found = SeasonSegmentDetector.detect(one, listOf(two))
        assertEquals(2_220_000L, found.creditsStartMs)
        assertEquals(2_400_000L, found.creditsEndMs)
        assertNull(found.introStartMs)
    }

    @Test
    fun creditsSharedOnlyInTheHeadAreDropped() {
        val shared = noise(4, 400)
        val one = episodeWithTails(
            "headonly__s1e1",
            shared + noise(11, 5_600),
            noise(12, 4_800),
        )
        val two = episodeWithTails(
            "headonly__s1e2",
            noise(13, 200) + shared + noise(14, 5_400),
            noise(15, 4_800),
        )
        val found = SeasonSegmentDetector.detect(one, listOf(two))
        assertEquals(0L, found.introStartMs)
        assertEquals(40_000L, found.introEndMs)
        assertNull(found.creditsStartMs)
        assertNull(found.creditsEndMs)
    }

    @Test
    fun quietRunsNeedThreeTenthsOfASecondAtOrBelowTheSilenceLevel() {
        val levels = IntArray(20) { 86 }
        levels[2] = 85
        levels[3] = 85
        levels[10] = 85
        levels[11] = 85
        levels[12] = 85
        assertEquals(
            listOf(SilentRange(1_000L, 1_300L)),
            silentRangesFromEnergy(encodeEnergyLevels(levels), stepMs = 100),
        )
    }

    @Test
    fun edgesSnapOnlyToAQuietRunWithinTwoSeconds() {
        val detected = DetectedSegments(
            introStartMs = 90_000L,
            introEndMs = 130_000L,
            creditsStartMs = 2_170_000L,
            creditsEndMs = 2_220_000L,
        )
        val episode = MediaAnalysisResult(
            key = "edges",
            analyzedAtMs = 0L,
            durationMs = 2_400_000L,
            silentRanges = listOf(
                SilentRange(88_000L, 89_000L),
                SilentRange(131_500L, 132_000L),
                SilentRange(2_218_000L, 2_222_000L),
            ),
        )
        val snapped = episode.snappingEdges(detected)
        assertEquals(89_000L, snapped.introStartMs)
        assertEquals(131_500L, snapped.introEndMs)
        assertEquals(2_218_000L, snapped.creditsEndMs)

        val tooFar = episode.copy(silentRanges = listOf(SilentRange(2_222_100L, 2_223_000L)))
        val stayed = tooFar.snappingEdges(detected)
        assertEquals(2_170_000L, stayed.creditsStartMs)
        assertEquals(2_220_000L, stayed.creditsEndMs)
    }

    @Test
    fun loudnessGainIsCapped() {
        val quiet = MediaAnalysisResult(key = "k", analyzedAtMs = 0, durationMs = 1, integratedLufs = -40.0)
        val loud = quiet.copy(integratedLufs = -2.0)
        assertEquals(6.0, quiet.loudnessGainDb())
        assertEquals(-12.0, loud.loudnessGainDb())
        assertTrue(quiet.copy(integratedLufs = null).loudnessGainDb() == null)
    }

    private fun episodeWithTails(key: String, head: IntArray, tail: IntArray) = MediaAnalysisResult(
        key = key,
        analyzedAtMs = 0L,
        durationMs = 2_400_000L,
        fingerprintStepMs = 100,
        headFingerprint = encodeFingerprint(head),
        tailFingerprint = encodeFingerprint(tail),
        tailStartMs = 2_400_000L - TAIL_FINGERPRINT_MS,
    )

    private fun episodeFrom(key: String, head: IntArray) = MediaAnalysisResult(
        key = key,
        analyzedAtMs = 0L,
        durationMs = 2_400_000L,
        fingerprintStepMs = 100,
        headFingerprint = encodeFingerprint(head),
        tailStartMs = 2_400_000L - TAIL_FINGERPRINT_MS,
    )

    private fun headWithShortAndLong(): IntArray {
        val head = noise(1, HEAD_FRAMES)
        shortPattern().copyInto(head, destinationOffset = 100)
        longPattern().copyInto(head, destinationOffset = 1_500)
        return head
    }

    private fun headWith(shortAt: Int): IntArray {
        val head = noise(100 + shortAt, HEAD_FRAMES)
        shortPattern().copyInto(head, destinationOffset = shortAt)
        return head
    }

    private fun headWithLongOnly(): IntArray {
        val head = noise(50, HEAD_FRAMES)
        longPattern().copyInto(head, destinationOffset = 400)
        return head
    }

    private fun shortPattern() = noise(200, 200)
    private fun longPattern() = noise(800, 800)
    private fun noise(seed: Int, size: Int): IntArray {
        val random = Random(seed)
        return IntArray(size) { random.nextInt() }
    }

    private companion object {
        const val HEAD_FRAMES = 3_000
    }
}
