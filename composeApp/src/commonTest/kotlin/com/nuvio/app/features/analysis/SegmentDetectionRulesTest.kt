package com.nuvio.app.features.analysis

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic fingerprints only. No media files and no measured times for any titled show.
 * A late title means the shared pattern sits late inside a long head print, which is the
 * shape a long first act leaves behind. It is not a result from a real episode.
 */
class SegmentDetectionRulesTest {
    @Test
    fun aTitleAfterALongFirstActIsFoundWhenTheHeadContainsIt() {
        val theme = pattern(83, 830)
        val episodeHead = noise(1, HEAD_FRAMES).also { theme.copyInto(it, 13_200) }
        val others = listOf(6_000, 10_800, 12_600).mapIndexed { index, at ->
            val head = noise(20 + index, HEAD_FRAMES)
            val copied = if (index == 1) theme.map { it xor 0b101 }.toIntArray() else theme
            copied.copyInto(head, at)
            fingerprinted("late__s1e${index + 2}", head, noise(40 + index, TAIL_FRAMES))
        }
        val found = SeasonSegmentDetector.detect(
            fingerprinted("late__s1e1", episodeHead, noise(2, TAIL_FRAMES)),
            others,
        )
        assertEquals(1_320_000L, found.introStartMs, "SYNTHETIC title at 22 min")
        assertEquals(1_403_000L, found.introEndMs, "SYNTHETIC 83s title")
        assertEquals(3, found.introConfidence?.agreeingSiblings)
    }

    @Test
    fun aTitleMissingFromTheStoredHeadIsNotInvented() {
        val found = SeasonSegmentDetector.detect(
            fingerprinted("missing__s1e1", noise(1, 6_000), noise(2, TAIL_FRAMES)),
            listOf(
                fingerprinted("missing__s1e2", noise(3, 6_000), noise(4, TAIL_FRAMES)),
                fingerprinted("missing__s1e3", noise(5, 6_000), noise(6, TAIL_FRAMES)),
                fingerprinted("missing__s1e4", noise(7, 6_000), noise(8, TAIL_FRAMES)),
            ),
        )
        assertNull(found.introStartMs, "SYNTHETIC print that does not contain the title")
        assertNull(found.creditsStartMs)
    }

    @Test
    fun anEarlyThemeWithVariableColdOpensStaysTheIntro() {
        val theme = pattern(40, 400)
        fun head(seed: Int, at: Int): IntArray {
            val frames = noise(seed, 6_000)
            theme.copyInto(frames, at)
            return frames
        }
        val found = SeasonSegmentDetector.detect(
            fingerprinted("early__s1e1", head(1, 1_800), noise(2, TAIL_FRAMES)),
            listOf(
                fingerprinted("early__s1e2", head(3, 600), noise(4, TAIL_FRAMES)),
                fingerprinted("early__s1e3", head(5, 2_400), noise(6, TAIL_FRAMES)),
                fingerprinted("early__s1e4", head(7, 3_000), noise(8, TAIL_FRAMES)),
            ),
        )
        assertEquals(180_000L, found.introStartMs)
        assertEquals(220_000L, found.introEndMs)
    }

    @Test
    fun aShortCleanThemeNeedsTwoSiblingsAndALongThemeStillWins() {
        val sting = pattern(5, 80)
        val only = SeasonSegmentDetector.detect(
            fingerprinted("sting__s1e1", headWith(1, sting, 200), noise(2, TAIL_FRAMES)),
            listOf(fingerprinted("sting__s1e2", headWith(3, sting, 400), noise(4, TAIL_FRAMES))),
        )
        assertNull(only.introStartMs, "SYNTHETIC 8s theme with one sibling")

        val agreed = SeasonSegmentDetector.detect(
            fingerprinted("sting3__s1e1", headWith(1, sting, 200), noise(2, TAIL_FRAMES)),
            listOf(
                fingerprinted("sting3__s1e2", headWith(3, sting, 400), noise(4, TAIL_FRAMES)),
                fingerprinted("sting3__s1e3", headWith(5, sting, 800), noise(6, TAIL_FRAMES)),
                fingerprinted("sting3__s1e4", headWith(7, sting, 100), noise(8, TAIL_FRAMES)),
            ),
        )
        assertEquals(20_000L, agreed.introStartMs, "SYNTHETIC clean 8s theme")
        assertEquals(28_000L, agreed.introEndMs)

        val noisy = sting.map { it xor 0b11111 }.toIntArray()
        val loose = SeasonSegmentDetector.detect(
            fingerprinted("loose__s1e1", headWith(1, noisy, 200), noise(2, TAIL_FRAMES)),
            listOf(
                fingerprinted("loose__s1e2", headWith(3, sting, 400), noise(4, TAIL_FRAMES)),
                fingerprinted("loose__s1e3", headWith(5, sting, 800), noise(6, TAIL_FRAMES)),
                fingerprinted("loose__s1e4", headWith(7, sting, 100), noise(8, TAIL_FRAMES)),
            ),
        )
        assertNull(loose.introStartMs, "SYNTHETIC short theme with high bit errors")

        val theme = pattern(44, 400)
        val episode = noise(1, 6_000).also {
            sting.copyInto(it, 50)
            theme.copyInto(it, 1_200)
        }
        val both = SeasonSegmentDetector.detect(
            fingerprinted("both__s1e1", episode, noise(2, TAIL_FRAMES)),
            (2..4).map { index ->
                val head = noise(10 + index, 6_000)
                sting.copyInto(head, 80 + index)
                theme.copyInto(head, 2_000 + index * 100)
                fingerprinted("both__s1e$index", head, noise(20 + index, TAIL_FRAMES))
            },
        )
        assertEquals(120_000L, both.introStartMs, "SYNTHETIC 40s theme beats an 8s sting")
        assertEquals(160_000L, both.introEndMs)
    }

    @Test
    fun aThemeLongerThanTheOldCapIsKeptAndOnePastFiveMinutesIsNot() {
        val kept = pattern(200, 2_000)
        val found = SeasonSegmentDetector.detect(
            fingerprinted("long__s1e1", headWith(1, kept, 300), noise(2, TAIL_FRAMES)),
            listOf(
                fingerprinted("long__s1e2", headWith(3, kept, 800), noise(4, TAIL_FRAMES)),
                fingerprinted("long__s1e3", headWith(5, kept, 200), noise(6, TAIL_FRAMES)),
            ),
        )
        assertEquals(30_000L, found.introStartMs, "SYNTHETIC 200s theme")
        assertEquals(230_000L, found.introEndMs)

        val tooLong = pattern(310, 3_100)
        val rejected = SeasonSegmentDetector.detect(
            fingerprinted("toolong__s1e1", headWith(1, tooLong, 100), noise(2, TAIL_FRAMES)),
            listOf(fingerprinted("toolong__s1e2", headWith(3, tooLong, 100), noise(4, TAIL_FRAMES))),
        )
        assertNull(rejected.introStartMs, "SYNTHETIC shared run past 300s")
    }

    @Test
    fun variableFadesKeepTheClusterAndIgnoreALongOutlier() {
        val episodeHead = noise(1, 6_000)
        pattern(52, 2_000).copyInto(episodeHead, 1_000)
        fun sibling(index: Int, frames: Int): MediaAnalysisResult {
            val head = noise(10 + index, 6_000)
            pattern(52, frames).copyInto(head, 400)
            return fingerprinted("fade__s1e$index", head, noise(30 + index, TAIL_FRAMES))
        }
        val found = SeasonSegmentDetector.detect(
            fingerprinted("fade__s1e1", episodeHead, noise(2, TAIL_FRAMES)),
            listOf(sibling(2, 400), sibling(3, 480), sibling(4, 520), sibling(5, 2_000)),
        )
        assertEquals(100_000L, found.introStartMs)
        assertEquals(152_000L, found.introEndMs, "SYNTHETIC fade, not the 200s outlier")
    }

    @Test
    fun anIntroAtZeroIsDroppedWhenTheSeasonStartsLater() {
        val flash = pattern(91, 800)
        val episode = fingerprinted("zero__s1e1", headWith(1, flash, 0), noise(2, TAIL_FRAMES))
        val others = (2..4).map { index ->
            fingerprinted("zero__s1e$index", headWith(10 + index, flash, 0), noise(20 + index, TAIL_FRAMES))
                .copy(introStartMs = 90_000L, introEndMs = 130_000L)
        }
        val found = SeasonSegmentDetector.detect(episode, others)
        assertNull(found.introStartMs, "SYNTHETIC cold open at 0")
    }

    @Test
    fun loudSharedAudioIsNotAnIntroAndQuietSharedAudioIs() {
        val theme = pattern(70, 400)
        val episode = fingerprinted("loud__s1e1", headWith(1, theme, 900), noise(2, TAIL_FRAMES))
        val other = fingerprinted("loud__s1e2", headWith(3, theme, 200), noise(4, TAIL_FRAMES))
        val levels = IntArray(6_000) { 40 }
        for (index in 900 until 1_300) levels[index] = DIALOG_OVERLAY_LEVEL
        val loud = SeasonSegmentDetector.detect(episode.copy(energy = encodeEnergyLevels(levels), energyStepMs = 100), listOf(other))
        assertNull(loud.introStartMs, "SYNTHETIC dialog-level energy across the match")
        val quiet = SeasonSegmentDetector.detect(
            episode.copy(energy = encodeEnergyLevels(IntArray(6_000) { 40 }), energyStepMs = 100),
            listOf(other),
        )
        assertEquals(90_000L, quiet.introStartMs)
        assertEquals(130_000L, quiet.introEndMs)
    }

    @Test
    fun aRecapAfterALeadingBumperStartsAtTheLaterClip() {
        val bumper = pattern(8, 40)
        val recap = pattern(21, 300)
        val head = noise(1, 6_000)
        bumper.copyInto(head, 0)
        recap.copyInto(head, 450)
        val previousHead = noise(4, 6_000)
        bumper.copyInto(previousHead, 0)
        val previousTail = noise(2, TAIL_FRAMES)
        recap.copyInto(previousTail, 200)
        val episode = fingerprinted("bumperlate__s1e2", head, noise(3, TAIL_FRAMES)).copy(
            introStartMs = 90_000L,
            introEndMs = 130_000L,
        )
        val previous = fingerprinted("bumperlate__s1e1", previousHead, previousTail)
        val found = assertNotNull(
            RecapDetector.detect(episode, listOf(previous)),
            "SYNTHETIC bumper then a recap after 20s",
        )
        assertTrue(found.first >= 40_000L, "recap start ${found.first}")
        assertTrue(found.first < 90_000L)
        val span = found.second - found.first
        assertTrue(span in 5_000L..1_200_000L, "span $span")
    }

    @Test
    fun aRecapAfterTheColdOpenStartsAtTheClip() {
        val clip = pattern(21, 150)
        val head = noise(1, 6_000)
        clip.copyInto(head, 210)
        val tail = noise(2, TAIL_FRAMES)
        clip.copyInto(tail, 100)
        val episode = fingerprinted("afteropen__s1e2", head, noise(3, TAIL_FRAMES)).copy(
            introStartMs = 90_000L,
            introEndMs = 130_000L,
        )
        val previous = fingerprinted("afteropen__s1e1", noise(4, 6_000), tail)
        val found = RecapDetector.detect(episode, listOf(previous))
        assertEquals(21_000L, found?.first)
        assertTrue((found?.second ?: 0L) > 21_000L)
    }

    @Test
    fun aRecapMontageJoinsClipsAndDoesNotCrossTheColdOpen() {
        val clips = listOf(pattern(1, 30), pattern(2, 30), pattern(3, 30))
        val head = noise(1, 6_000)
        clips[0].copyInto(head, 0)
        clips[1].copyInto(head, 70)
        clips[2].copyInto(head, 140)
        pattern(9, 100).copyInto(head, 400)
        val previousTail = noise(2, TAIL_FRAMES)
        clips[0].copyInto(previousTail, 100)
        clips[1].copyInto(previousTail, 400)
        clips[2].copyInto(previousTail, 800)
        val episode = fingerprinted("montage__s1e2", head, noise(3, TAIL_FRAMES)).copy(introStartMs = 90_000L, introEndMs = 130_000L)
        val previous = fingerprinted("montage__s1e1", noise(4, 6_000), previousTail)
        val others = listOf(previous) + (3..4).map { index ->
            fingerprinted("montage__s1e$index", noise(10 + index, 6_000), noise(20 + index, TAIL_FRAMES))
        }
        val trace = SegmentTrace()
        assertEquals(0L to 17_000L, RecapDetector.detect(episode, others, trace), "SYNTHETIC recap montage")
        assertEquals("previous-ending", trace.decisions.single().signal)
        assertEquals("accepted-previous-ending", trace.decisions.single().why)
    }

    @Test
    fun aSingleShortRecapClipAndALoudSingleClipAreRejected() {
        val clip = pattern(4, 40)
        val head = noise(1, 6_000).also { clip.copyInto(it, 0) }
        val tail = noise(2, TAIL_FRAMES).also { clip.copyInto(it, 0) }
        val episode = fingerprinted("shortrecap__s1e2", head, noise(3, TAIL_FRAMES))
        val previous = fingerprinted("shortrecap__s1e1", noise(4, 6_000), tail)
        assertNull(RecapDetector.detect(episode, listOf(previous)), "SYNTHETIC 4s recap")

        val loudClip = pattern(15, 150)
        val loudHead = noise(5, 6_000).also { loudClip.copyInto(it, 0) }
        val loudTail = noise(6, TAIL_FRAMES).also { loudClip.copyInto(it, 0) }
        val levels = IntArray(2_000) { DIALOG_OVERLAY_LEVEL }
        val loudEpisode = fingerprinted("loudrecap__s1e2", loudHead, noise(7, TAIL_FRAMES)).copy(
            energy = encodeEnergyLevels(levels),
            energyStepMs = 100,
            introStartMs = 40_000L,
            introEndMs = 80_000L,
        )
        val loudPrevious = fingerprinted("loudrecap__s1e1", noise(8, 6_000), loudTail)
        val trace = SegmentTrace()
        assertNull(RecapDetector.detect(loudEpisode, listOf(loudPrevious), trace), "SYNTHETIC loud single recap clip")
        assertEquals("rejected-dialog-overlay", trace.decisions.single().why)
    }

    @Test
    fun aSpokenOpeningSharedByTwoSiblingsIsKept() {
        val narration = pattern(15, 150)
        val episode = fingerprinted(
            "spoken__s1e3",
            noise(5, 6_000).also { narration.copyInto(it, 0) },
            noise(7, TAIL_FRAMES),
        ).copy(
            energy = encodeEnergyLevels(IntArray(2_000) { DIALOG_OVERLAY_LEVEL }),
            energyStepMs = 100,
        )
        val previous = fingerprinted(
            "spoken__s1e2",
            noise(8, 6_000).also { narration.copyInto(it, 0) },
            noise(9, TAIL_FRAMES),
        )
        val earlier = fingerprinted(
            "spoken__s1e1",
            noise(10, 6_000).also { narration.copyInto(it, 0) },
            noise(11, TAIL_FRAMES),
        )
        val trace = SegmentTrace()
        assertEquals(0L to 15_000L, RecapDetector.detect(episode, listOf(previous, earlier), trace))
        assertTrue(trace.decisions.single().why.startsWith("accepted-"))
    }

    @Test
    fun aLoudClipFromOnlyThePreviousEpisodeStaysDialogWhenOtherSiblingsDoNotShareIt() {
        val narration = pattern(16, 150)
        val episode = fingerprinted(
            "lone__s1e4",
            noise(5, 6_000).also { narration.copyInto(it, 0) },
            noise(7, TAIL_FRAMES),
        ).copy(
            energy = encodeEnergyLevels(IntArray(2_000) { DIALOG_OVERLAY_LEVEL }),
            energyStepMs = 100,
            introStartMs = 40_000L,
            introEndMs = 80_000L,
        )
        val previous = fingerprinted(
            "lone__s1e3",
            noise(8, 6_000),
            noise(9, TAIL_FRAMES).also { narration.copyInto(it, 0) },
        )
        val others = listOf(previous) + (1..2).map { index ->
            fingerprinted("lone__s1e$index", noise(20 + index, 6_000), noise(30 + index, TAIL_FRAMES))
        }
        val trace = SegmentTrace()
        assertNull(RecapDetector.detect(episode, others, trace))
        assertEquals("rejected-dialog-overlay", trace.decisions.single().why)
    }

    @Test
    fun aPreviewMontageSitsAgainstTheCreditsAndAMiddleHitDoesNot() {
        val clips = listOf(pattern(11, 30), pattern(12, 30), pattern(13, 30))
        val tail = noise(1, TAIL_FRAMES)
        val creditsAt = 4_500
        clips[0].copyInto(tail, creditsAt - 170)
        clips[1].copyInto(tail, creditsAt - 100)
        clips[2].copyInto(tail, creditsAt - 30)
        val creditsStart = TAIL_START_MS + creditsAt * STEP_MS
        val episode = fingerprinted("preview__s1e1", noise(2, 6_000), tail).copy(
            creditsStartMs = creditsStart,
            creditsEndMs = DURATION_MS,
        )
        val nextHead = noise(3, 6_000)
        clips[0].copyInto(nextHead, 0)
        clips[1].copyInto(nextHead, 50)
        clips[2].copyInto(nextHead, 100)
        val next = fingerprinted("preview__s1e2", nextHead, noise(4, TAIL_FRAMES)).copy(
            introStartMs = 20_000L,
            introEndMs = 60_000L,
        )
        val found = PreviewDetector.detect(episode, listOf(next))
        assertEquals(TAIL_START_MS + (creditsAt - 170) * STEP_MS, found?.first, "SYNTHETIC preview montage")
        assertEquals(creditsStart, found?.second)

        val middle = noise(5, TAIL_FRAMES).also { pattern(14, 80).copyInto(it, 1_000) }
        val middleEpisode = episode.copy(tailFingerprint = encodeFingerprint(middle))
        val middleNext = fingerprinted(
            "preview__s1e2",
            noise(6, 6_000).also { pattern(14, 80).copyInto(it, 0) },
            noise(7, TAIL_FRAMES),
        ).copy(introStartMs = 20_000L, introEndMs = 60_000L)
        assertNull(PreviewDetector.detect(middleEpisode, listOf(middleNext)), "SYNTHETIC preview away from the credits")
    }

    @Test
    fun aCreditsThemeIsNotCalledAPreviewWhenTheCreditsTimeIsUnknown() {
        val theme = pattern(21, 400)
        val tail = noise(1, TAIL_FRAMES).also { theme.copyInto(it, TAIL_FRAMES - theme.size) }
        val episode = fingerprinted("theme__s1e1", noise(2, 6_000), tail)
        val next = fingerprinted(
            "theme__s1e2",
            noise(3, 6_000).also { theme.copyInto(it, 0) },
            noise(4, TAIL_FRAMES),
        )
        assertNull(PreviewDetector.detect(episode, listOf(next)), "SYNTHETIC ending theme without a credits time")
    }

    @Test
    fun blackFramesCanCollapseAPreviewThatSitsOnTheCredits() {
        val clip = pattern(31, 60)
        val creditsAt = 4_500
        val tail = noise(1, TAIL_FRAMES).also { clip.copyInto(it, creditsAt - 60) }
        val start = TAIL_START_MS + (creditsAt - 60) * STEP_MS
        val episode = fingerprinted("collapse__s1e1", noise(2, 6_000), tail).copy(
            creditsStartMs = TAIL_START_MS + creditsAt * STEP_MS,
            creditsEndMs = DURATION_MS,
            blackRanges = listOf(
                SilentRange(start, start + 2_000L),
                SilentRange(start + 4_000L, start + 6_000L),
            ),
        )
        val next = fingerprinted(
            "collapse__s1e2",
            noise(3, 6_000).also { clip.copyInto(it, 0) },
            noise(4, TAIL_FRAMES),
        ).copy(introStartMs = 8_000L, introEndMs = 40_000L)
        val trace = SegmentTrace()
        assertNull(PreviewDetector.detect(episode, listOf(next), trace))
        assertEquals("rejected-short", trace.decisions.single().why)
    }

    @Test
    fun aTheIntroDbTimeDoesNotInventARecapOrPreview() {
        val episode = fingerprinted("prior__s1e2", noise(1, 6_000), noise(2, TAIL_FRAMES)).copy(introStartMs = 30_000L)
        val previous = fingerprinted("prior__s1e1", noise(3, 6_000), noise(4, TAIL_FRAMES))
        val next = fingerprinted("prior__s1e3", noise(5, 6_000), noise(6, TAIL_FRAMES)).copy(introStartMs = 40_000L)
        val recapTrace = SegmentTrace()
        assertNull(RecapDetector.detect(episode, listOf(previous), recapTrace, prior = 0L to 20_000L))
        assertEquals("rejected-audio-miss-prior-unused", recapTrace.decisions.single().why)
        val previewTrace = SegmentTrace()
        assertNull(PreviewDetector.detect(episode, listOf(next), previewTrace, prior = 2_300_000L to 2_340_000L))
        assertEquals("rejected-audio-miss-prior-unused", previewTrace.decisions.single().why)
    }

    @Test
    fun theSeasonPassFillsAMontageRecapAndPreview() {
        val clip = pattern(41, 30)
        val second = pattern(42, 30)
        val head = noise(1, 6_000).also {
            clip.copyInto(it, 0)
            second.copyInto(it, 70)
        }
        val previousTail = noise(2, TAIL_FRAMES).also {
            clip.copyInto(it, 10)
            second.copyInto(it, 80)
        }
        val episode = fingerprinted("pass__s1e2", head, noise(3, TAIL_FRAMES)).copy(
            introStartMs = 90_000L,
            introEndMs = 130_000L,
        )
        val previous = fingerprinted("pass__s1e1", noise(4, 6_000), previousTail)
        val filledRecap = seasonFingerprintPass(listOf(episode, previous)).first { it.key == episode.key }
        assertEquals(0L, filledRecap.recapStartMs)
        assertEquals(10_000L, filledRecap.recapEndMs)

        val previewClips = listOf(pattern(51, 30), pattern(52, 30))
        val creditsAt = 4_500
        val tail = noise(5, TAIL_FRAMES).also {
            previewClips[0].copyInto(it, creditsAt - 100)
            previewClips[1].copyInto(it, creditsAt - 30)
        }
        val creditsStart = TAIL_START_MS + creditsAt * STEP_MS
        val current = fingerprinted("passpreview__s1e1", noise(6, 6_000), tail).copy(
            creditsStartMs = creditsStart,
            creditsEndMs = DURATION_MS,
        )
        val nextHead = noise(7, 6_000).also {
            previewClips[0].copyInto(it, 0)
            previewClips[1].copyInto(it, 50)
        }
        val next = fingerprinted("passpreview__s1e2", nextHead, noise(8, TAIL_FRAMES)).copy(
            introStartMs = 12_000L,
            introEndMs = 40_000L,
        )
        val filledPreview = seasonFingerprintPass(listOf(current, next)).first { it.key == current.key }
        assertEquals(TAIL_START_MS + (creditsAt - 100) * STEP_MS, filledPreview.previewStartMs)
        assertEquals(creditsStart, filledPreview.previewEndMs)
    }

    @Test
    fun absentSegmentsStayAbsent() {
        val episode = fingerprinted("none__s1e2", noise(1, 6_000), noise(2, TAIL_FRAMES))
        val others = listOf(
            fingerprinted("none__s1e1", noise(3, 6_000), noise(4, TAIL_FRAMES)),
            fingerprinted("none__s1e3", noise(5, 6_000), noise(6, TAIL_FRAMES)),
        )
        val segments = SeasonSegmentDetector.detect(episode, others)
        assertNull(segments.introStartMs)
        assertNull(segments.creditsStartMs)
        assertNull(RecapDetector.detect(episode, others))
        assertNull(PreviewDetector.detect(episode, others))
    }

    @Test
    fun aShortCaptureIsRecapturedOnlyWhenTheAnalyserExists() {
        val short = storedWindow(headFrames = 6_000, tailFrames = 4_800, durationMs = 2_400_000L)
        val full = storedWindow(headFrames = 15_000, tailFrames = 9_000, durationMs = 2_400_000L)
        val episode = storedWindow(headFrames = 4_800, tailFrames = 4_800, durationMs = 480_000L)
        assertTrue(short.capturedWindowIsShort())
        assertFalse(full.capturedWindowIsShort())
        assertFalse(episode.capturedWindowIsShort())
        assertFalse(shouldRecapture(short, analyzerSupported = false))
        assertTrue(shouldRecapture(short, analyzerSupported = true))
        assertFalse(shouldRecapture(full, analyzerSupported = true))
        assertFalse(shouldRecapture(null, analyzerSupported = true))
    }

    private fun storedWindow(headFrames: Int, tailFrames: Int, durationMs: Long) = MediaAnalysisResult(
        key = "window__s1e1",
        analyzedAtMs = 0L,
        durationMs = durationMs,
        fingerprintStepMs = 100,
        headFingerprint = encodeFingerprint(IntArray(headFrames)),
        tailFingerprint = encodeFingerprint(IntArray(tailFrames)),
        tailStartMs = (durationMs - tailFrames * 100L).coerceAtLeast(0L),
    )

    private fun headWith(seed: Int, clip: IntArray, at: Int): IntArray {
        val frames = noise(seed, 6_000)
        clip.copyInto(frames, at)
        return frames
    }

    private fun fingerprinted(key: String, head: IntArray, tail: IntArray) = MediaAnalysisResult(
        key = key,
        analyzedAtMs = 0L,
        durationMs = DURATION_MS,
        fingerprintStepMs = STEP_MS.toInt(),
        headFingerprint = encodeFingerprint(head),
        tailFingerprint = encodeFingerprint(tail),
        tailStartMs = TAIL_START_MS,
    )

    private fun noise(seed: Int, size: Int): IntArray {
        val random = Random(seed)
        return IntArray(size) { random.nextInt() }
    }

    private fun pattern(seed: Int, size: Int) = IntArray(size) { index ->
        (seed * 1_000_003) xor (index * -0x61c88647)
    }

    private companion object {
        const val STEP_MS = 100L
        const val DURATION_MS = 2_400_000L
        const val HEAD_FRAMES = 15_000
        const val TAIL_FRAMES = 4_800
        const val TAIL_START_MS = DURATION_MS - TAIL_FRAMES * STEP_MS
    }
}
