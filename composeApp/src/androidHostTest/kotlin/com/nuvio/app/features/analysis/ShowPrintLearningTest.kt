package com.nuvio.app.features.analysis

import com.nuvio.app.features.downloads.PlaybackPriority
import com.nuvio.app.features.player.segmentTypesSyncedByAnalysis
import com.nuvio.app.features.streams.epochMs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 1: a mark stores a show print, a later episode matches it, and a deleted mark does not teach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ShowPrintLearningTest {
    @Before
    fun setUp() {
        MediaAnalysisFiles.retainForTests = true
        MediaAnalysisFiles.clearForTests()
        MediaAnalysisStore.clearCacheForTests()
    }

    @After
    fun tearDown() {
        MediaAnalysisFiles.clearForTests()
        MediaAnalysisFiles.retainForTests = false
        MediaAnalysisStore.clearCacheForTests()
    }

    @Test
    fun aMarkTeachesALaterEpisodeAndAShiftedCopy() {
        val show = "tmdb:101"
        val duration = 1_800_000L
        val audio = audio(400, seed = 11)
        save(show, 1, audio, duration)
        save(show, 2, audio, duration)
        save(show, 3, IntArray(30) { 0x13579BDF.toInt() } + audio, duration)
        val started = System.nanoTime()
        runBlocking {
            SkipLearning.recordMark(show, 1, 1, "intro", 5_000L, 15_000L, duration)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        val identical = load(show, 2)
        val shifted = load(show, 3)
        val identicalStartError = kotlin.math.abs((identical.introStartMs ?: -1L) - 5_000L)
        val identicalEndError = kotlin.math.abs((identical.introEndMs ?: -1L) - 15_000L)
        val shiftedStartError = kotlin.math.abs((shifted.introStartMs ?: -1L) - 8_000L)
        val shiftedEndError = kotlin.math.abs((shifted.introEndMs ?: -1L) - 18_000L)
        println(
            "show-print identical startErrorMs=$identicalStartError endErrorMs=$identicalEndError " +
                "shifted startErrorMs=$shiftedStartError endErrorMs=$shiftedEndError teachMs=$elapsedMs",
        )
        assertEquals(0L, identicalStartError)
        assertEquals(0L, identicalEndError)
        assertEquals(0L, shiftedStartError)
        assertEquals(0L, shiftedEndError)
        assertEquals(listOf("s1e1/intro/intro"), identical.learnedRanges.map { it.printId })
        assertEquals(DETECTED_FROM_YOUR_MARKS, analysisRowLearnedLine(identical))
        assertFalse(segmentTypesSyncedByAnalysis(identical).contains("intro"))
        assertNull(analysisRowLearnedLine(load(show, 1)))
    }

    @Test
    fun aPrintDoesNotReplaceAnalysisAUserMarkOrASubmission() {
        val show = "tmdb:106"
        val duration = 1_800_000L
        val track = audio(400, seed = 61)
        save(show, 1, track, duration)
        save(
            show,
            2,
            track,
            duration,
            introStartMs = 1_000L,
            introEndMs = 9_000L,
            introConfidence = SegmentConfidence(agreeingSiblings = 3, avgBitErrors = 1.0),
        )
        save(
            show,
            3,
            track,
            duration,
            userSegments = mapOf("intro" to UserSegment(2_000L, 22_000L, durationMs = duration)),
        )
        save(
            show,
            4,
            track,
            duration,
            userSegments = mapOf("intro" to UserSegment(3_000L, 23_000L, imported = true, durationMs = duration)),
        )
        teachFromMark(show, 1, "intro", 5_000L, 15_000L, duration)
        val analysed = load(show, 2)
        assertEquals(1_000L, analysed.introStartMs)
        assertEquals(9_000L, analysed.introEndMs)
        assertEquals(3, analysed.introConfidence?.agreeingSiblings)
        assertTrue(analysed.learnedRanges.isEmpty())
        assertNull(analysisRowLearnedLine(analysed))
        val marked = load(show, 3)
        assertEquals(2_000L, marked.userSegments["intro"]?.startMs)
        assertEquals(22_000L, marked.userSegments["intro"]?.endMs)
        assertEquals(2_000L, marked.introStartMs)
        assertEquals(22_000L, marked.introEndMs)
        assertEquals(false, marked.userSegments["intro"]?.imported)
        val submission = load(show, 4)
        assertEquals(true, submission.userSegments["intro"]?.imported)
        assertEquals(3_000L, submission.userSegments["intro"]?.startMs)
        assertEquals(23_000L, submission.userSegments["intro"]?.endMs)
        assertEquals(3_000L, submission.introStartMs)
        assertEquals(23_000L, submission.introEndMs)
        assertTrue(submission.learnedRanges.isEmpty())
    }

    @Test
    fun silenceAPartialRecapAndADifferentTrackAreNotDetections() {
        val show = "tmdb:107"
        val duration = 1_800_000L
        val silent = IntArray(400)
        save(show, 1, silent, duration)
        save(show, 2, silent, duration)
        teachFromMark(show, 1, "intro", 5_000L, 15_000L, duration)
        assertNull(load(show, 2).introStartMs)
        assertTrue(load(show, 2).learnedRanges.isEmpty())
        assertNull(analysisRowLearnedLine(load(show, 2)))

        val recap = audio(400, seed = 71)
        save(show, 3, recap, duration)
        val bumper = recap.copyOf(40) + audio(360, seed = 72)
        save(show, 4, bumper, duration)
        teachFromMark(show, 3, "recap", 0L, 16_000L, duration)
        assertNull(load(show, 4).recapStartMs)
        assertTrue(load(show, 4).learnedRanges.none { it.type == "recap" })

        val other = audio(400, seed = 73)
        save(
            show,
            5,
            other,
            duration,
            introStartMs = 4_000L,
            introEndMs = 14_000L,
            introConfidence = SegmentConfidence(agreeingSiblings = 2, avgBitErrors = 2.0),
        )
        val theme = audio(400, seed = 74)
        save(show, 6, theme, duration)
        teachFromMark(show, 6, "intro", 5_000L, 15_000L, duration)
        assertEquals(4_000L, load(show, 5).introStartMs)
        assertEquals(14_000L, load(show, 5).introEndMs)
        assertNull(analysisRowLearnedLine(load(show, 5)))
    }

    @Test
    fun aShorterEpisodeDoesNotKeepAClippedPrint() {
        val show = "tmdb:108"
        val source = audio(400, seed = 81)
        save(show, 1, source, 40_000L)
        save(show, 2, source.copyOf(120), 12_000L)
        teachFromMark(show, 1, "intro", 5_000L, 15_000L, 40_000L)
        assertNull(load(show, 2).introStartMs)
        assertTrue(load(show, 2).learnedRanges.isEmpty())
        assertNull(analysisRowLearnedLine(load(show, 2)))
    }

    @Test
    fun matchingWaitsWhileVideoIsPlaying() {
        val show = "tmdb:109"
        val duration = 1_800_000L
        val track = audio(400, seed = 91)
        save(show, 1, track, duration)
        save(show, 2, track, duration)
        PlaybackPriority.sessionStarted(isLocalFile = true)
        try {
            PlaybackPriority.noteSnapshot(
                wantsToPlay = true,
                buffering = false,
                positionMs = 1_000L,
                nowMs = epochMs(),
                pictureMoving = true,
            )
            assertTrue(PlaybackPriority.isWatching())
            runBlocking {
                SkipLearning.recordMark(show, 1, 1, "recap", 5_000L, 15_000L, duration)
            }
            assertNull(load(show, 2).recapStartMs)
            assertTrue(load(show, 2).learnedRanges.isEmpty())
            assertTrue(ShowPrints.exportWires(show, ShowPrintClock(1L, 0, "device-a")).isNotEmpty())
        } finally {
            PlaybackPriority.sessionEnded()
        }
        assertTrue(ShowPrints.teach(show, ShowPrintGate.Open) >= 1)
        assertEquals(5_000L, load(show, 2).recapStartMs)
        assertEquals(DETECTED_FROM_YOUR_MARKS, analysisRowLearnedLine(load(show, 2)))
    }

    @Test
    fun cancellationLeavesTheEpisodeUnmatched() {
        val show = "tmdb:110"
        val duration = 1_800_000L
        val track = audio(400, seed = 101)
        save(show, 1, track, duration)
        teachFromMark(show, 1, "intro", 5_000L, 15_000L, duration)
        save(show, 2, track, duration)
        assertEquals(0, ShowPrints.teach(show, ShowPrintGate.Open, shouldStop = { true }))
        assertNull(load(show, 2).introStartMs)
        assertTrue(load(show, 2).learnedRanges.isEmpty())
        assertTrue(ShowPrints.exportWires(show, ShowPrintClock(1L, 0, "device-a")).isNotEmpty())
    }

    @Test
    fun twoEpisodesKeepDistinctPrintsAndTheShowStaysBounded() {
        val show = "tmdb:111"
        val duration = 1_800_000L
        val track = audio(400, seed = 111)
        repeat(17) { index -> save(show, index + 1, track, duration) }
        save(show, 18, track, duration)
        repeat(17) { index -> teachFromMark(show, index + 1, "intro", 5_000L, 15_000L, duration) }
        val wires = ShowPrints.exportWires(show, ShowPrintClock(1L, 0, "device-a"))
        assertEquals(16, wires.size)
        assertEquals(16, wires.map { it.id }.distinct().size)
        assertTrue(wires.none { it.episode == 1 })
        assertTrue(wires.any { it.episode == 17 && it.id.contains("/s1e17/") })
        assertEquals(5_000L, load(show, 1).userSegments["intro"]?.startMs)
        assertTrue(load(show, 18).learnedRanges.isNotEmpty())
        assertTrue(SkipLearning.removeMark(show, 1, 17, "intro"))
        assertEquals(5_000L, load(show, 2).userSegments["intro"]?.startMs)
        assertTrue(load(show, 18).learnedRanges.none { it.printId == "s1e17/intro/intro" })
        assertTrue(load(show, 18).learnedRanges.isNotEmpty())
    }

    @Test
    fun reanalysisKeepsADetectorResultWhenThePrintMisses() {
        val key = MediaAnalysisStore.keyFor("tmdb:112", 1, 1)
        val previous = MediaAnalysisResult(
            key = key,
            analyzedAtMs = 1L,
            durationMs = 1_800_000L,
            introStartMs = 5_000L,
            introEndMs = 15_000L,
            learnedRanges = listOf(LearnedRange("intro", 5_000L, 15_000L, "s1e1/intro/intro")),
        )
        val carried = MediaAnalysisResult(key = key, analyzedAtMs = 2L, durationMs = previous.durationMs)
            .carryingKnownSegments(previous)
        assertEquals(5_000L, carried.introStartMs)
        val open = carried.withoutPrintOwnedTimes()
        assertNull(open.introStartMs)
        val detected = open.copy(
            introStartMs = 20_000L,
            introEndMs = 30_000L,
            introConfidence = SegmentConfidence(agreeingSiblings = 2, avgBitErrors = 1.0),
        )
        val restored = detected.restoringUnfilledPrintTimes(carried)
        assertEquals(20_000L, restored.introStartMs)
        assertEquals(30_000L, restored.introEndMs)
        assertTrue(restored.learnedRanges.isEmpty())
        assertNull(analysisRowLearnedLine(restored))
        val missed = open.restoringUnfilledPrintTimes(carried)
        assertEquals(5_000L, missed.introStartMs)
        assertEquals(DETECTED_FROM_YOUR_MARKS, analysisRowLearnedLine(missed))
    }

    @Test
    fun threePrintsOfOneTypeAreKept() {
        val show = "tmdb:102"
        val duration = 1_800_000L
        val head = IntArray(2_000)
        val marks = listOf(10_000L to 20_000L, 50_000L to 60_000L, 90_000L to 100_000L, 130_000L to 140_000L)
        marks.forEachIndexed { index, (start, _) ->
            val from = (start / 100L).toInt()
            audio(100, seed = 20 + index).copyInto(head, from)
        }
        repeat(4) { index -> save(show, index + 1, head, duration) }
        save(show, 5, head, duration)
        marks.forEachIndexed { index, (start, end) ->
            runBlocking { SkipLearning.recordMark(show, 1, index + 1, "intro", start, end, duration) }
        }
        val learned = load(show, 5).learnedRanges.filter { it.type == "intro" }
        assertEquals(3, learned.size)
        assertTrue(learned.none { it.startMs == 130_000L })
        assertEquals(3, learned.map { it.startMs }.distinct().size)
    }

    @Test
    fun deletingAMarkDoesNotTeach() {
        val show = "tmdb:103"
        val duration = 1_800_000L
        val audio = audio(400, seed = 31)
        save(show, 1, audio, duration)
        save(show, 2, audio, duration)
        runBlocking { SkipLearning.recordMark(show, 1, 1, "intro", 5_000L, 15_000L, duration) }
        assertEquals(5_000L, load(show, 2).introStartMs)
        assertTrue(SkipLearning.removeMark(show, 1, 1, "intro"))
        assertNull(load(show, 2).introStartMs)
        assertTrue(load(show, 2).learnedRanges.isEmpty())
        save(show, 3, audio, duration)
        ShowPrints.teach(show, ShowPrintGate.Open)
        assertNull(load(show, 3).introStartMs)
        assertTrue(ShowPrints.exportWires(show, ShowPrintClock(1L, 0, "device-a")).isEmpty())
    }

    @Test
    fun printsRoundTripThroughTheSyncRecords() {
        val show = "tmdb:104"
        val duration = 1_800_000L
        val audio = audio(400, seed = 41)
        save(show, 1, audio, duration)
        save(show, 2, audio, duration)
        runBlocking { SkipLearning.recordMark(show, 1, 1, "intro", 5_000L, 15_000L, duration) }
        val clock = ShowPrintClock(1_700_000_000_000L, 1, "device-a")
        val wires = ShowPrints.exportWires(show, clock)
        assertTrue(wires.isNotEmpty())
        wires.forEach { wire ->
            val text = wire.canonical()
            assertTrue(text.encodeToByteArray().size <= MAX_SYNC_PLAINTEXT_BYTES)
            val parsed = assertNotNull(parseShowPrintWire(text))
            assertEquals(text, parsed.canonical())
        }
        val parsed = wires.map { parseShowPrintWire(it.canonical())!! }
        ShowPrints.installWires(show, emptyList())
        assertNull(load(show, 2).introStartMs)
        ShowPrints.installWires(show, parsed)
        ShowPrints.teach(show, ShowPrintGate.Open)
        assertEquals(5_000L, load(show, 2).introStartMs)
        assertEquals(15_000L, load(show, 2).introEndMs)
        val tombstones = parsed.map { tombstonePrint(it.id, ShowPrintClock(1_700_000_000_001L, 0, "device-b")) }
        val merged = mergeShowPrintWires(parsed + tombstones)
        assertTrue(merged.isEmpty())
        ShowPrints.installWires(show, merged)
        assertNull(load(show, 2).introStartMs)
        assertTrue(load(show, 2).learnedRanges.isEmpty())
    }

    @Test
    fun aClosedGateKeepsThePrintAndWritesNoDetection() {
        val show = "tmdb:105"
        val duration = 1_800_000L
        val audio = audio(400, seed = 51)
        save(show, 1, audio, duration)
        runBlocking { SkipLearning.recordMark(show, 1, 1, "intro", 5_000L, 15_000L, duration) }
        save(show, 2, audio, duration)
        val blocked = ShowPrintGate(videoPlaying = true)
        assertEquals(0, ShowPrints.teach(show, blocked))
        assertNull(load(show, 2).introStartMs)
        assertTrue(ShowPrints.exportWires(show, ShowPrintClock(1L, 0, "device-a")).isNotEmpty())
        assertTrue(ShowPrints.teach(show, ShowPrintGate.Open) >= 1)
        assertEquals(5_000L, load(show, 2).introStartMs)
    }

    @Test
    fun theGateMatrixMatchesTheSchedulerAndThePlaybackPass() {
        assertFalse(showPrintGateBlocks(ShowPrintGate.Open))
        assertTrue(showPrintGateBlocks(ShowPrintGate(videoPlaying = true)))
        assertFalse(showPrintGateBlocks(ShowPrintGate(videoPlaying = true, playbackPass = true)))
        assertTrue(showPrintGateBlocks(ShowPrintGate(thermal = AnalysisThermal.Serious)))
        assertTrue(showPrintGateBlocks(ShowPrintGate(thermal = AnalysisThermal.Critical, playbackPass = true)))
        assertTrue(showPrintGateBlocks(ShowPrintGate(lowPowerMode = true)))
        assertTrue(showPrintGateBlocks(ShowPrintGate(lowPowerMode = true, playbackPass = true)))
        assertTrue(showPrintGateBlocks(ShowPrintGate(thermal = AnalysisThermal.Fair, transferActive = true)))
        assertFalse(
            showPrintGateBlocks(
                ShowPrintGate(thermal = AnalysisThermal.Fair, transferActive = true, warmOverride = true),
            ),
        )
        assertFalse(showPrintGateBlocks(ShowPrintGate(thermal = AnalysisThermal.Fair, transferActive = true, playbackPass = true)))
        assertTrue(showPrintGateBlocks(ShowPrintGate(onlyWhileCharging = true, charging = false)))
        assertFalse(showPrintGateBlocks(ShowPrintGate(onlyWhileCharging = true, charging = false, playbackPass = true)))
        assertTrue(showPrintGateBlocks(ShowPrintGate(freeBytes = ANALYSIS_FREE_SPACE_BRAKE_BYTES - 1)))
        assertTrue(showPrintGateBlocks(ShowPrintGate(freeBytes = ANALYSIS_FREE_SPACE_BRAKE_BYTES - 1, playbackPass = true)))
        assertFalse(showPrintGateBlocks(ShowPrintGate(freeBytes = null)))
        assertFalse(showPrintGateBlocks(ShowPrintGate(lowBattery = true)))
        assertTrue(showPrintGateBlocks(ShowPrintGate(lowBattery = true, playbackPass = true)))
        assertFalse(SegmentGuess.ENABLED)
        assertTrue(SegmentGuess.rangesFor(MediaAnalysisResult(key = "x", analyzedAtMs = 0L, durationMs = 1L)).isEmpty())
    }

    @Test
    fun theSamplePrintRecordIsCanonicalAndFitsThePlaintextCap() {
        assertEquals("AQAAAAIAAAADAAAABAAAAA==", encodeFingerprint(intArrayOf(1, 2, 3, 4)))
        val wire = ShowPrintWire(
            id = "print/tmdb:100/s1e1/intro/intro/0",
            showId = "tmdb:100",
            season = 1,
            episode = 1,
            segmentType = "intro",
            markId = "intro",
            part = 0,
            parts = 1,
            startMs = 20_000L,
            endMs = 30_000L,
            originMs = 15_000L,
            durationMs = 1_800_000L,
            stepMs = PRINT_STEP_MS,
            padMs = PRINT_PAD_MS,
            frames = "AQAAAAIAAAADAAAABAAAAA==",
            wallMs = 1_700_000_000_000L,
            counter = 1,
            deviceId = "device-a",
        )
        val text = wire.canonical()
        assertEquals(SAMPLE_PRINT, text)
        assertTrue(text.encodeToByteArray().size < MAX_SYNC_PLAINTEXT_BYTES)
        assertEquals(wire.frames, parseShowPrintWire(text)?.frames)
        val tombstone = tombstonePrint(wire.id, ShowPrintClock(1_700_000_000_001L, 0, "device-b"))
        assertTrue(mergeShowPrintWires(listOf(wire, tombstone)).isEmpty())
    }

    private fun save(
        show: String,
        episode: Int,
        head: IntArray,
        durationMs: Long,
        introStartMs: Long? = null,
        introEndMs: Long? = null,
        introConfidence: SegmentConfidence? = null,
        userSegments: Map<String, UserSegment> = emptyMap(),
    ) {
        val key = MediaAnalysisStore.keyFor(show, 1, episode)
        MediaAnalysisStore.save(
            MediaAnalysisResult(
                key = key,
                analyzedAtMs = episode.toLong(),
                durationMs = durationMs,
                fingerprintStepMs = PRINT_STEP_MS,
                headFingerprint = encodeFingerprint(head),
                introStartMs = introStartMs,
                introEndMs = introEndMs,
                introConfidence = introConfidence,
                userSegments = userSegments,
            ),
        )
    }

    /** Stores a mark and matches the show without the older season pass. */
    private fun teachFromMark(show: String, episode: Int, type: String, startMs: Long, endMs: Long, durationMs: Long) {
        val key = MediaAnalysisStore.keyFor(show, 1, episode)
        val existing = load(show, episode)
        MediaAnalysisStore.save(
            existing.copy(userSegments = existing.userSegments + (type to UserSegment(startMs, endMs, durationMs = durationMs))),
        )
        ShowPrints.syncEpisode(show, key, reteach = true, gate = ShowPrintGate.Open)
    }

    private fun load(show: String, episode: Int): MediaAnalysisResult =
        assertNotNull(MediaAnalysisStore.load(MediaAnalysisStore.keyFor(show, 1, episode)))

    private fun audio(count: Int, seed: Int): IntArray {
        val random = Random(seed)
        return IntArray(count) { random.nextInt() }
    }

    private companion object {
        const val SAMPLE_PRINT =
            "{\"algo\":\"nuvio-print-1\",\"deleted\":false,\"durationMs\":1800000,\"endMs\":30000,\"episode\":1," +
                "\"frames\":\"AQAAAAIAAAADAAAABAAAAA==\",\"hlc\":{\"counter\":1,\"deviceId\":\"device-a\"," +
                "\"wallMs\":1700000000000},\"id\":\"print/tmdb:100/s1e1/intro/intro/0\",\"kind\":\"print\"," +
                "\"markId\":\"intro\",\"originMs\":15000,\"padMs\":5000,\"part\":0,\"parts\":1,\"season\":1," +
                "\"segmentType\":\"intro\",\"showId\":\"tmdb:100\",\"startMs\":20000,\"stepMs\":100,\"v\":1}"
    }
}
