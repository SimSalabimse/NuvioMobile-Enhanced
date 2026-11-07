package com.nuvio.app.features.analysis

import com.nuvio.app.features.player.onDeviceSkipIntervalsFrom
import com.nuvio.app.features.player.segmentTypesSyncedByAnalysis
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.player.skip.TheIntroDb
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic fingerprints only. No media files. "Before" is the previous rule copied here:
 * one longest shared run per sibling, a recap kept only when siblings agree on a start,
 * and no preview search at all.
 */
class SyntheticSegmentSignalsTest {
    @Test
    fun syntheticScorecard() {
        val cold = coldOpenSeason()
        val coldBefore = legacyLongestIntro(cold.episode, cold.others)
        val coldAfter = SeasonSegmentDetector.detect(cold.episode, cold.others)
        assertNull(coldBefore, "SYNTHETIC intro before")
        assertEquals(250_000L, coldAfter.introStartMs, "SYNTHETIC intro after")
        assertEquals(290_000L, coldAfter.introEndMs)
        assertEquals(3, coldAfter.introConfidence?.agreeingSiblings)

        val varying = varyingIntroSeason()
        val varyingBefore = legacyLongestIntro(varying.episode, varying.others)
        val varyingAfter = SeasonSegmentDetector.detect(varying.episode, varying.others)
        assertEquals(250_000L, varyingBefore?.first, "SYNTHETIC varying intro before")
        assertEquals(250_000L, varyingAfter.introStartMs, "SYNTHETIC varying intro after")

        val late = outsideHeadSeason()
        assertNull(legacyLongestIntro(late.episode, late.others), "SYNTHETIC intro past 10 min before")
        assertNull(SeasonSegmentDetector.detect(late.episode, late.others).introStartMs, "SYNTHETIC intro past 10 min after")

        val recap = previousEndingRecap()
        assertNull(legacyAgreedRecap(recap.episode, recap.others), "SYNTHETIC recap before")
        val recapAfter = RecapDetector.detect(recap.episode, recap.others)
        assertEquals(0L, recapAfter?.first, "SYNTHETIC recap after")
        assertEquals(25_000L, recapAfter?.second)

        val preview = nextColdOpenPreview()
        assertNull(legacyPreview(), "SYNTHETIC preview before")
        val previewAfter = PreviewDetector.detect(preview.episode, preview.others)
        assertEquals(2_350_000L, previewAfter?.first, "SYNTHETIC preview after")
        assertEquals(2_370_000L, previewAfter?.second)

        val plainCredits = sharedCredits()
        val withoutBlack = plainCredits.episode.applyingSeasonDetection(listOf(plainCredits.other))
        val creditsEnd = assertNotNull(withoutBlack.creditsEndMs, "SYNTHETIC credits before black")
        val withBlack = plainCredits.episode.copy(blackRanges = listOf(SilentRange(creditsEnd - 1_000L, creditsEnd + 500L)))
            .applyingSeasonDetection(listOf(plainCredits.other))
        assertEquals(creditsEnd - 1_000L, withBlack.creditsEndMs, "SYNTHETIC credits after black")
    }

    @Test
    fun aRecapAndAPreviewAreCandidatesFromTheSignal() {
        val recap = previousEndingRecap()
        val recapTrace = SegmentTrace()
        val recapRange = assertNotNull(RecapDetector.detect(recap.episode, recap.others, recapTrace))
        val recapDecision = recapTrace.decisions.single { it.type == "recap" }
        assertTrue(recapDecision.candidates >= 1)
        assertEquals("previous-ending", recapDecision.signal)
        assertTrue(recapDecision.accepted)
        assertEquals(recapRange.first, recapDecision.startMs)

        val preview = nextColdOpenPreview()
        val previewTrace = SegmentTrace()
        val previewRange = assertNotNull(PreviewDetector.detect(preview.episode, preview.others, previewTrace))
        val previewDecision = previewTrace.decisions.single { it.type == "preview" }
        assertTrue(previewDecision.candidates >= 1)
        assertEquals("next-cold-open", previewDecision.signal)
        assertTrue(previewDecision.accepted)
        assertEquals(previewRange.first, previewDecision.startMs)
    }

    @Test
    fun shortRecapAndPreviewAreRejectedAndANormalOneIsAccepted() {
        val tinyRecap = recapOfLength(frames = 30, introAtMs = 25_000L)
        val tinyTrace = SegmentTrace()
        assertNull(RecapDetector.detect(tinyRecap.episode, tinyRecap.others, tinyTrace))
        assertFalse(tinyTrace.decisions.single().accepted)

        val shortRecap = recapOfLength(frames = 40, introAtMs = null)
        val shortTrace = SegmentTrace()
        assertNull(RecapDetector.detect(shortRecap.episode, shortRecap.others, shortTrace))
        assertEquals("rejected-short", shortTrace.decisions.single().why)
        assertEquals(1, shortTrace.decisions.single().candidates)

        val keptRecap = previousEndingRecap()
        assertEquals(0L to 25_000L, RecapDetector.detect(keptRecap.episode, keptRecap.others))

        val tinyPreview = previewOfLength(frames = 30)
        val tinyPreviewTrace = SegmentTrace()
        assertNull(PreviewDetector.detect(tinyPreview.episode, tinyPreview.others, tinyPreviewTrace))
        assertFalse(tinyPreviewTrace.decisions.single().accepted)

        val shortPreview = previewCollapsedByBlack()
        val shortPreviewTrace = SegmentTrace()
        assertNull(PreviewDetector.detect(shortPreview.episode, shortPreview.others, shortPreviewTrace))
        assertEquals("rejected-not-beside-credits", shortPreviewTrace.decisions.single().why)
        assertTrue(shortPreviewTrace.decisions.single().candidates >= 1)

        val keptPreview = nextColdOpenPreview()
        assertEquals(2_350_000L to 2_370_000L, PreviewDetector.detect(keptPreview.episode, keptPreview.others))
    }

    @Test
    fun aStoredRecapAndPreviewAreOfferedAsSkipTimesAndSubmitSuggestions() {
        val recap = previousEndingRecap()
        val preview = nextColdOpenPreview()
        val recapRange = assertNotNull(RecapDetector.detect(recap.episode, recap.others))
        val previewRange = assertNotNull(PreviewDetector.detect(preview.episode, preview.others))
        val stored = MediaAnalysisResult(
            key = recap.episode.key,
            analyzedAtMs = 1L,
            durationMs = DURATION_MS,
            fileName = "episode.mkv",
            recapStartMs = recapRange.first,
            recapEndMs = recapRange.second,
            previewStartMs = previewRange.first,
            previewEndMs = previewRange.second,
        )
        assertEquals(setOf("recap", "preview"), segmentTypesSyncedByAnalysis(stored))
        val offered = onDeviceSkipIntervalsFrom(stored, recapDetection = true, introDetection = true)
        assertEquals(recapRange.first / 1000.0, offered.first { it.type == "recap" }.startTime)
        assertEquals(previewRange.first / 1000.0, offered.first { it.type == "preview" }.startTime)
        assertEquals("on-device", offered.first { it.type == "recap" }.provider)
        assertEquals("on-device", offered.first { it.type == "preview" }.provider)
    }

    @Test
    fun theSeasonPassFillsRecapAndPreviewFromStoredFingerprints() {
        val recap = previousEndingRecap()
        val filledRecap = seasonFingerprintPass(listOf(recap.episode) + recap.others)
            .first { it.key == recap.episode.key }
        assertEquals(0L, filledRecap.recapStartMs)
        assertEquals(25_000L, filledRecap.recapEndMs)

        val preview = nextColdOpenPreview()
        val filledPreview = seasonFingerprintPass(listOf(preview.episode) + preview.others)
            .first { it.key == preview.episode.key }
        assertEquals(2_350_000L, filledPreview.previewStartMs)
        assertEquals(2_370_000L, filledPreview.previewEndMs)
    }

    @Test
    fun aTheIntroDbTimeDoesNotInventRecapPreviewOrIntro() {
        val episode = fingerprinted("prior__s1e2", noise(4, HEAD_FRAMES), noise(5, TAIL_FRAMES))
            .copy(introStartMs = 30_000L, introEndMs = 70_000L)
        val previous = fingerprinted("prior__s1e1", noise(6, HEAD_FRAMES), noise(7, TAIL_FRAMES))
        val recapTrace = SegmentTrace()
        assertNull(RecapDetector.detect(episode, listOf(previous), recapTrace, prior = 0L to 20_000L))
        assertEquals("rejected-audio-miss-prior-unused", recapTrace.decisions.single().why)

        val next = fingerprinted("prior__s1e3", noise(8, HEAD_FRAMES), noise(9, TAIL_FRAMES))
            .copy(introStartMs = 40_000L, introEndMs = 80_000L)
        val previewTrace = SegmentTrace()
        assertNull(PreviewDetector.detect(episode, listOf(next), previewTrace, prior = 2_300_000L to 2_340_000L))
        assertEquals("rejected-audio-miss-prior-unused", previewTrace.decisions.single().why)

        val intro = SeasonSegmentDetector.detect(
            episode,
            listOf(previous, next),
            theIntroDbIntro = 10_000L to 50_000L,
        )
        assertNull(intro.introStartMs)
    }

    @Test
    fun aCachedTheIntroDbPriorCanRescueARealIntroCandidate() {
        val theme = pattern(4242, 400)
        val episodeHead = noise(1, HEAD_FRAMES).also { theme.copyInto(it, 2_500) }
        pattern(7, 800).copyInto(episodeHead, 200)
        val episode = fingerprinted("rescue__s1e1", episodeHead, noise(3, TAIL_FRAMES))
        val others = (2..4).map { index ->
            val head = noise(10 + index, HEAD_FRAMES)
            // The long one-off clip stays clear of the theme, so it cannot overwrite it.
            pattern(100 + index, 800).copyInto(head, 1_200)
            if (index == 2) theme.copyInto(head, 300)
            fingerprinted("rescue__s1e$index", head, noise(20 + index, TAIL_FRAMES))
        }
        // The theme is shared with only one sibling, so the cluster vote has nothing.
        assertNull(SeasonSegmentDetector.detect(episode, others).introStartMs)
        val rescued = SeasonSegmentDetector.detect(
            episode,
            others,
            theIntroDbIntro = 250_000L to 290_000L,
        )
        assertEquals(250_000L, rescued.introStartMs)
        assertEquals(290_000L, rescued.introEndMs)
        assertEquals(true, rescued.introConfidence?.theIntroDbAgrees)
    }

    @Test
    fun decisionLinesNameTheSignalAndCarryNoAddressOrShowKey() {
        val recap = previousEndingRecap()
        val trace = SegmentTrace()
        RecapDetector.detect(recap.episode, recap.others, trace)
        val line = trace.decisions.single().line("tt8550800")
        assertTrue(line.contains("type=recap"))
        assertTrue(line.contains("candidates="))
        assertTrue(line.contains("signal=previous-ending"))
        assertTrue(line.contains("accepted=1"))
        assertTrue(line.contains("thresholds="))
        assertFalse(line.contains("tt8550800"))
        assertFalse(line.contains("http"))
        assertFalse(line.contains("://"))
        val leaked = SegmentDecision(
            type = "preview",
            candidates = 1,
            signal = "next-cold-open",
            accepted = false,
            startMs = null,
            endMs = null,
            confidence = "none",
            why = "see https://example.com/tt8550800",
            thresholds = "minMs=5000",
        ).line("download-1")
        assertFalse(leaked.contains("http"))
        assertFalse(leaked.contains("://"))
        assertFalse(leaked.contains("tt8550800"))
        assertFalse(leaked.contains("example"))
    }

    @Test
    fun sceneChangesNeedAStableRunThenAJump() {
        val frames = IntArray(12) { 0x11111111 }
        frames[11] = 0xEEEEEEEE.toInt()
        assertEquals(listOf(1_100L), sceneChanges(frames, stepMs = 100L, offsetMs = 0L))
        val mid = IntArray(40) { index -> if (index % 2 == 0) 0 else 0x0FFF }
        assertEquals(emptyList(), sceneChanges(mid, stepMs = 100L, offsetMs = 0L))
    }

    @Test
    fun blackFramesBeatSilenceWhenBothAreNearTheEdge() {
        val episode = MediaAnalysisResult(
            key = "edges__s1e1",
            analyzedAtMs = 0L,
            durationMs = DURATION_MS,
            blackRanges = listOf(SilentRange(98_500L, 100_000L)),
            silentRanges = listOf(SilentRange(97_000L, 99_000L)),
        )
        assertEquals(90_000L to 98_500L, episode.snapSegmentEdges(90_000L, 100_000L))
    }

    @Test
    fun plainIntroAndCreditsStayPrefilled() {
        val plain = plainIntroAndCredits()
        val none = SeasonSegmentDetector.detect(plain.episode, plain.others)
        val quiet = encodeEnergyLevels(IntArray(24_000) { 40 })
        val partial = IntArray(24_000) { 40 }
        // Half of the intro window is loud. That is under the dialog fraction, so the times stay.
        for (index in 900 until 1_100) partial[index] = 255
        val withQuiet = SeasonSegmentDetector.detect(plain.episode.copy(energy = quiet), plain.others)
        val withPartial = SeasonSegmentDetector.detect(
            plain.episode.copy(energy = encodeEnergyLevels(partial)),
            plain.others,
        )
        assertEquals(90_000L, none.introStartMs)
        assertEquals(130_000L, none.introEndMs)
        assertEquals(plain.episode.tailStartMs + 430_000L, none.creditsStartMs)
        assertEquals(DURATION_MS, none.creditsEndMs)
        assertEquals(none, withQuiet)
        assertEquals(none, withPartial)
        val stored = plain.episode.copy(
            introStartMs = none.introStartMs,
            introEndMs = none.introEndMs,
            creditsStartMs = none.creditsStartMs,
            creditsEndMs = none.creditsEndMs,
        )
        assertEquals(setOf("intro", "outro"), segmentTypesSyncedByAnalysis(stored))
    }

    @Test
    fun sharedOpeningClipStaysIntroWhenTheChapterSaysCredits() {
        val opening = sharedOpeningCredits()
        val trace = SegmentTrace()
        val found = SeasonSegmentDetector.detect(opening.episode, listOf(opening.other), trace = trace)
        assertEquals(20_000L, found.introStartMs)
        assertEquals(60_000L, found.introEndMs)
        assertNull(found.creditsStartMs)
        assertEquals("rejected-opening-credits", trace.decisions.single { it.type == "credits" }.why)
        val chaptered = opening.episode.copy(
            introStartMs = found.introStartMs,
            introEndMs = found.introEndMs,
        ).applyingChapterSegments(
            chapters = listOf(FileChapter("Credits", 20_000L, 60_000L)),
            durationMs = DURATION_MS,
            protectedKinds = emptySet(),
            introDetection = true,
            recapDetection = true,
        )
        assertNull(chaptered.creditsStartMs)
        assertEquals(20_000L, chaptered.introStartMs)
        assertEquals(60_000L, chaptered.introEndMs)
        assertTrue("intro" in chaptered.chapterTypes)
        assertFalse("outro" in chaptered.chapterTypes)
    }

    @Test
    fun highSpeechAcrossTheCandidateIsNotPrefilled() {
        val plain = plainIntroAndCredits()
        val introLevels = IntArray(24_000) { 40 }
        for (index in 900 until 1_300) introLevels[index] = DIALOG_OVERLAY_LEVEL
        val introTrace = SegmentTrace()
        val introFound = SeasonSegmentDetector.detect(
            plain.episode.copy(energy = encodeEnergyLevels(introLevels)),
            plain.others,
            trace = introTrace,
        )
        assertNull(introFound.introStartMs)
        assertEquals(plain.episode.tailStartMs + 430_000L, introFound.creditsStartMs)
        val introDecision = introTrace.decisions.single { it.type == "intro" }
        assertFalse(introDecision.accepted)
        assertEquals("rejected-dialog-overlay", introDecision.why)
        assertEquals("low", introDecision.confidence)
        assertNull(introDecision.startMs)
        val introStored = plain.episode.copy(
            creditsStartMs = introFound.creditsStartMs,
            creditsEndMs = introFound.creditsEndMs,
        )
        assertFalse("intro" in segmentTypesSyncedByAnalysis(introStored))
        assertTrue("outro" in segmentTypesSyncedByAnalysis(introStored))

        val creditsStart = plain.episode.tailStartMs + 430_000L
        val creditsLevels = IntArray(24_000) { 40 }
        val from = (creditsStart / 100L).toInt()
        for (index in from until 24_000) creditsLevels[index] = DIALOG_OVERLAY_LEVEL
        val creditsTrace = SegmentTrace()
        val creditsFound = SeasonSegmentDetector.detect(
            plain.episode.copy(energy = encodeEnergyLevels(creditsLevels)),
            plain.others,
            trace = creditsTrace,
        )
        assertEquals(90_000L, creditsFound.introStartMs)
        assertNull(creditsFound.creditsStartMs)
        val creditsDecision = creditsTrace.decisions.single { it.type == "credits" }
        assertFalse(creditsDecision.accepted)
        assertEquals("rejected-dialog-overlay", creditsDecision.why)
        assertEquals("low", creditsDecision.confidence)

        val chapter = MediaAnalysisResult(
            key = "chapter-loud",
            analyzedAtMs = 0L,
            durationMs = DURATION_MS,
            energyStepMs = 100,
            energy = encodeEnergyLevels(IntArray(1_300) { DIALOG_OVERLAY_LEVEL }),
        )
        val loudChapter = chapter.applyingChapterSegments(
            chapters = listOf(FileChapter("Intro", 90_000L, 130_000L)),
            durationMs = DURATION_MS,
            protectedKinds = emptySet(),
            introDetection = true,
            recapDetection = true,
        )
        assertNull(loudChapter.introStartMs)
        assertTrue(loudChapter.chapterTypes.isEmpty())
        val quietChapter = chapter.copy(energy = encodeEnergyLevels(IntArray(1_300) { 40 })).applyingChapterSegments(
            chapters = listOf(FileChapter("Intro", 90_000L, 130_000L)),
            durationMs = DURATION_MS,
            protectedKinds = emptySet(),
            introDetection = true,
            recapDetection = true,
        )
        assertEquals(90_000L, quietChapter.introStartMs)
        assertEquals(130_000L, quietChapter.introEndMs)
    }

    @Test
    fun introAtZeroWhileSiblingsStartLaterIsNotPrefilled() {
        val onlyCold = coldOpenAtZero(siblingIntroMs = 90_000L)
        val trace = SegmentTrace()
        val found = SeasonSegmentDetector.detect(onlyCold.episode, onlyCold.others, trace = trace)
        assertNull(found.introStartMs)
        assertNull(found.introEndMs)
        val decision = trace.decisions.single { it.type == "intro" }
        assertFalse(decision.accepted)
        assertEquals("rejected-cold-open", decision.why)
        assertEquals("low", decision.confidence)
        assertFalse("intro" in segmentTypesSyncedByAnalysis(onlyCold.episode))

        val withTheme = coldOpenThenTheme()
        val themed = SeasonSegmentDetector.detect(withTheme.episode, withTheme.others)
        assertEquals(250_000L, themed.introStartMs)
        assertEquals(290_000L, themed.introEndMs)

        val atStart = coldOpenAtZero(siblingIntroMs = 0L)
        val kept = SeasonSegmentDetector.detect(atStart.episode, atStart.others)
        assertEquals(0L, kept.introStartMs)
        assertEquals(80_000L, kept.introEndMs)
    }

    @Test
    fun speechEnergyFiresOnlyWhenMostOfTheRangeIsLoud() {
        val span = 100
        val levels = IntArray(span)
        val justUnder = span * DIALOG_OVERLAY_MIN_PERCENT / 100 - 1
        for (index in 0 until justUnder) levels[index] = DIALOG_OVERLAY_LEVEL
        assertFalse(speechEnergyHigh(encodeEnergyLevels(levels), 100, 0L, span * 100L))
        levels[justUnder] = DIALOG_OVERLAY_LEVEL
        assertTrue(speechEnergyHigh(encodeEnergyLevels(levels), 100, 0L, span * 100L))
        assertFalse(speechEnergyHigh(null, 100, 0L, 20_000L))
        assertFalse(coldOpenLikely(0L, emptyList()))
        assertTrue(isOpeningCreditsRange(0L, 60_000L, DURATION_MS))
        assertFalse(isOpeningCreditsRange(2_310_000L, DURATION_MS, DURATION_MS))
        val short = 15 * 60_000L
        assertFalse(isOpeningCreditsRange(6 * 60_000L, short, short))
    }

    @Test
    fun cachedPriorsReadAPlaybackHitAndDoNotAskTheNetwork() {
        val durationMs = DURATION_MS
        val key = "tt0000001:1:2:$durationMs"
        SkipIntroRepository.skipIntervalCache.store(
            key,
            listOf(
                SkipInterval(10.0, 40.0, "intro", TheIntroDb.PROVIDER),
                SkipInterval(0.0, 15.0, "recap", TheIntroDb.PROVIDER),
                SkipInterval(2200.0, 2300.0, "preview", TheIntroDb.PROVIDER),
            ),
        )
        val priors = cachedSegmentPriors("tt0000001", season = 1, episode = 2, durationMs = durationMs)
        assertEquals(10_000L to 40_000L, priors.intro)
        assertEquals(0L to 15_000L, priors.recap)
        assertEquals(2_200_000L to 2_300_000L, priors.preview)
        assertEquals(SegmentPriors(), cachedSegmentPriors("no-imdb-here", 1, 2, durationMs))
    }

    private data class Group(val episode: MediaAnalysisResult, val others: List<MediaAnalysisResult>)

    private data class PairEpisodes(val episode: MediaAnalysisResult, val other: MediaAnalysisResult)

    private fun coldOpenSeason(): Group {
        val theme = pattern(11, 400)
        val flashAB = pattern(21, 800)
        val flashAC = pattern(22, 800)
        val flashAD = pattern(23, 800)
        val episodeHead = noise(1, HEAD_FRAMES)
        flashAB.copyInto(episodeHead, 200)
        flashAC.copyInto(episodeHead, 1_100)
        theme.copyInto(episodeHead, 2_500)
        flashAD.copyInto(episodeHead, 3_200)
        fun sibling(index: Int, flash: IntArray, flashAt: Int, themeAt: Int): MediaAnalysisResult {
            val head = noise(100 + index, HEAD_FRAMES)
            flash.copyInto(head, flashAt)
            theme.copyInto(head, themeAt)
            return fingerprinted("cold__s1e$index", head, noise(200 + index, TAIL_FRAMES))
        }
        return Group(
            episode = fingerprinted("cold__s1e1", episodeHead, noise(201, TAIL_FRAMES)),
            others = listOf(
                sibling(2, flashAB, flashAt = 50, themeAt = 3_000),
                sibling(3, flashAC, flashAt = 80, themeAt = 1_800),
                sibling(4, flashAD, flashAt = 120, themeAt = 3_600),
            ),
        )
    }

    private fun varyingIntroSeason(): Group {
        val theme = pattern(31, 400)
        fun head(seed: Int, themeAt: Int): IntArray {
            val frames = noise(seed, HEAD_FRAMES)
            theme.copyInto(frames, themeAt)
            return frames
        }
        return Group(
            episode = fingerprinted("vary__s1e1", head(1, 2_500), noise(11, TAIL_FRAMES)),
            others = listOf(
                fingerprinted("vary__s1e2", head(2, 3_000), noise(12, TAIL_FRAMES)),
                fingerprinted("vary__s1e3", head(3, 1_800), noise(13, TAIL_FRAMES)),
                fingerprinted("vary__s1e4", head(4, 3_600), noise(14, TAIL_FRAMES)),
            ),
        )
    }

    /** The shared theme would start at 610 s, which is outside the stored 10 minute head. */
    private fun outsideHeadSeason(): Group {
        return Group(
            episode = fingerprinted("late__s1e1", noise(1, HEAD_FRAMES), noise(2, TAIL_FRAMES)),
            others = listOf(
                fingerprinted("late__s1e2", noise(3, HEAD_FRAMES), noise(4, TAIL_FRAMES)),
                fingerprinted("late__s1e3", noise(5, HEAD_FRAMES), noise(6, TAIL_FRAMES)),
                fingerprinted("late__s1e4", noise(7, HEAD_FRAMES), noise(8, TAIL_FRAMES)),
            ),
        )
    }

    private fun previousEndingRecap(): Group = recapOfLength(frames = 200, introAtMs = 25_000L, key = "recap")

    private fun recapOfLength(frames: Int, introAtMs: Long?, key: String = "shortrecap$frames"): Group {
        val clip = pattern(41 + frames, frames)
        val previousTail = noise(70 + frames, TAIL_FRAMES).also { clip.copyInto(it, 0) }
        val head = noise(80 + frames, HEAD_FRAMES).also { clip.copyInto(it, 0) }
        val episode = fingerprinted("${key}__s1e2", head, noise(81 + frames, TAIL_FRAMES)).copy(
            introStartMs = introAtMs,
            introEndMs = introAtMs?.plus(40_000L),
            creditsStartMs = 2_000_000L,
            creditsEndMs = DURATION_MS,
        )
        val previous = fingerprinted("${key}__s1e1", noise(60 + frames, HEAD_FRAMES), previousTail).copy(
            creditsStartMs = 2_200_000L,
            creditsEndMs = DURATION_MS,
        )
        val others = listOf(previous) + listOf(3, 4).map { index ->
            fingerprinted("${key}__s1e$index", noise(90 + frames + index, HEAD_FRAMES), noise(100 + frames + index, TAIL_FRAMES))
                .copy(creditsStartMs = 2_200_000L, creditsEndMs = DURATION_MS)
        }
        return Group(episode, others)
    }

    private fun nextColdOpenPreview(): Group = previewOfLength(frames = 200, key = "preview")

    private fun previewOfLength(frames: Int, key: String = "shortpreview$frames"): Group {
        val clip = pattern(51 + frames, frames)
        val tail = noise(120 + frames, TAIL_FRAMES)
        val creditsAt = TAIL_FRAMES - 300
        val clipAt = creditsAt - frames
        clip.copyInto(tail, clipAt)
        val creditsStart = TAIL_START_MS + creditsAt * STEP_MS
        val episode = fingerprinted("${key}__s1e1", noise(110 + frames, HEAD_FRAMES), tail).copy(
            introStartMs = 90_000L,
            introEndMs = 130_000L,
            creditsStartMs = creditsStart,
            creditsEndMs = DURATION_MS,
        )
        val nextHead = noise(130 + frames, HEAD_FRAMES).also { clip.copyInto(it, 0) }
        val next = fingerprinted("${key}__s1e2", nextHead, noise(140 + frames, TAIL_FRAMES)).copy(
            introStartMs = frames * STEP_MS,
            introEndMs = frames * STEP_MS + 40_000L,
        )
        return Group(episode, listOf(next))
    }

    private fun previewCollapsedByBlack(): Group {
        val built = previewOfLength(frames = 60, key = "collapsed")
        val start = TAIL_START_MS + 1_000 * STEP_MS
        val moved = built.episode.copy(
            tailFingerprint = encodeFingerprint(noise(121, TAIL_FRAMES).also { tail ->
                pattern(111, 60).copyInto(tail, 1_000)
            }),
            blackRanges = listOf(
                SilentRange(start, start + 2_000L),
                SilentRange(start + 4_000L, start + 6_000L),
            ),
            creditsStartMs = null,
            creditsEndMs = null,
        )
        val nextHead = noise(131, HEAD_FRAMES).also { pattern(111, 60).copyInto(it, 0) }
        val next = built.others.single().copy(headFingerprint = encodeFingerprint(nextHead), introStartMs = 6_000L, introEndMs = 46_000L)
        return Group(moved, listOf(next))
    }

    private fun plainIntroAndCredits(): Group {
        val theme = pattern(71, 400)
        val credits = pattern(72, 500)
        fun head(seed: Int): IntArray {
            val frames = noise(seed, HEAD_FRAMES)
            theme.copyInto(frames, 900)
            return frames
        }
        fun tail(seed: Int): IntArray {
            val frames = noise(seed, TAIL_FRAMES)
            credits.copyInto(frames, 4_300)
            return frames
        }
        return Group(
            episode = fingerprinted("plain__s1e1", head(1), tail(2)),
            others = listOf(fingerprinted("plain__s1e2", head(3), tail(4))),
        )
    }

    /** Shared opening clip, with the tail window covering the start the way a short file does. */
    private fun sharedOpeningCredits(): PairEpisodes {
        val clip = pattern(81, 400)
        fun head(seed: Int): IntArray {
            val frames = noise(seed, HEAD_FRAMES)
            clip.copyInto(frames, 200)
            return frames
        }
        fun tail(seed: Int): IntArray {
            val frames = noise(seed + 50, TAIL_FRAMES)
            clip.copyInto(frames, 200)
            return frames
        }
        return PairEpisodes(
            episode = fingerprinted("open__s1e1", head(1), tail(2)).copy(tailStartMs = 0L),
            other = fingerprinted("open__s1e2", head(3), tail(4)).copy(tailStartMs = 0L),
        )
    }

    private fun coldOpenAtZero(siblingIntroMs: Long): Group {
        val flash = pattern(91, 800)
        fun head(seed: Int): IntArray {
            val frames = noise(seed, HEAD_FRAMES)
            flash.copyInto(frames, 0)
            return frames
        }
        return Group(
            episode = fingerprinted("zero__s1e1", head(1), noise(2, TAIL_FRAMES)),
            others = (2..4).map { index ->
                fingerprinted("zero__s1e$index", head(10 + index), noise(20 + index, TAIL_FRAMES))
                    .copy(introStartMs = siblingIntroMs, introEndMs = siblingIntroMs + 40_000L)
            },
        )
    }

    private fun coldOpenThenTheme(): Group {
        val flash = pattern(93, 800)
        val theme = pattern(94, 400)
        fun head(seed: Int): IntArray {
            val frames = noise(seed, HEAD_FRAMES)
            flash.copyInto(frames, 0)
            theme.copyInto(frames, 2_500)
            return frames
        }
        return Group(
            episode = fingerprinted("both__s1e1", head(1), noise(2, TAIL_FRAMES)),
            others = (2..4).map { index ->
                fingerprinted("both__s1e$index", head(10 + index), noise(20 + index, TAIL_FRAMES))
                    .copy(introStartMs = 250_000L, introEndMs = 290_000L)
            },
        )
    }

    private fun sharedCredits(): PairEpisodes {
        val credits = pattern(61, 500)
        fun tail(seed: Int): IntArray {
            val frames = noise(seed, TAIL_FRAMES)
            credits.copyInto(frames, 2_000)
            return frames
        }
        return PairEpisodes(
            episode = fingerprinted("credits__s1e1", noise(1, HEAD_FRAMES), tail(2)),
            other = fingerprinted("credits__s1e2", noise(3, HEAD_FRAMES), tail(4)),
        )
    }

    /**
     * Previous intro rule. One longest run per sibling. With three or more siblings the starts
     * have to agree within two seconds, otherwise nothing is kept.
     */
    private fun legacyLongestIntro(episode: MediaAnalysisResult, others: List<MediaAnalysisResult>): Pair<Long, Long>? {
        val head = decodeFingerprint(episode.headFingerprint)
        val step = episode.fingerprintStepMs
        val matches = mutableListOf<Pair<Long, Long>>()
        var comparable = 0
        for (other in others) {
            if (other.fingerprintStepMs != step) continue
            comparable++
            val segment = FingerprintMatcher.longestSharedSegment(
                head,
                decodeFingerprint(other.headFingerprint),
                minFrames = 150,
                maxFrames = 1_500,
            ) ?: continue
            matches += (segment.startA * step.toLong()) to (segment.endA * step.toLong())
        }
        if (matches.isEmpty()) return null
        if (comparable < 3) return matches.maxBy { it.second - it.first }
        val sorted = matches.sortedBy { it.first }
        var best: List<Pair<Long, Long>>? = null
        for (index in sorted.indices) {
            val group = sorted.drop(index).takeWhile { it.first - sorted[index].first <= SEGMENT_AGREE_MS }
            if (group.size < 2) continue
            if (best == null || group.size > best.size) best = group
        }
        return best?.maxBy { it.second - it.first }
    }

    /**
     * Previous recap rule. A shared opening clip is kept only when two siblings agree on its
     * start, once three or more siblings exist. A clip that matches only the previous episode
     * is dropped.
     */
    private fun legacyAgreedRecap(episode: MediaAnalysisResult, others: List<MediaAnalysisResult>): Pair<Long, Long>? {
        val introStart = episode.introStartMs ?: return null
        if (introStart < 10_000L) return null
        val step = episode.fingerprintStepMs.coerceAtLeast(1).toLong()
        val head = decodeFingerprint(episode.headFingerprint)
        val windowFrames = minOf(head.size, (minOf(introStart, 260_000L) / step).toInt())
        if (windowFrames < 40) return null
        val window = head.copyOfRange(0, windowFrames)
        val hits = mutableListOf<Int>()
        var comparable = 0
        for (other in others) {
            if (other.fingerprintStepMs.toLong() != step) continue
            comparable++
            val sequences = listOf(decodeFingerprint(other.headFingerprint), decodeFingerprint(other.tailFingerprint))
            for (sequence in sequences) {
                val segment = FingerprintMatcher.longestSharedSegment(window, sequence, minFrames = 40) ?: continue
                hits += segment.startA
            }
        }
        if (hits.isEmpty()) return null
        if (comparable >= 3) {
            val agreed = hits.filter { hit -> hits.count { abs(it - hit) <= 20 } >= 2 }
            if (agreed.isEmpty()) return null
        }
        return 0L to 1L
    }

    /** Previous preview rule: fingerprint matching did not produce one. */
    private fun legacyPreview(): Pair<Long, Long>? = null

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
        const val HEAD_FRAMES = 6_000
        const val TAIL_FRAMES = 4_800
        const val TAIL_START_MS = DURATION_MS - TAIL_FRAMES * STEP_MS
    }
}
