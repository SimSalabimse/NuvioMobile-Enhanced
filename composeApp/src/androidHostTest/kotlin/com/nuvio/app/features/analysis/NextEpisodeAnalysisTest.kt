package com.nuvio.app.features.analysis

import com.nuvio.app.features.player.MpvSubtitleProperties
import com.nuvio.app.features.player.SubtitleDriftCorrection
import com.nuvio.app.features.player.energySubtitleObservations
import com.nuvio.app.features.player.SubtitleDriftOutcome
import com.nuvio.app.features.player.SubtitleObservationSource
import com.nuvio.app.features.player.SubtitleSourceMemory
import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.player.SubtitleSyncTransport
import com.nuvio.app.features.player.correctSubtitleDrift
import com.nuvio.app.features.player.shownSubtitleMs
import com.nuvio.app.features.player.subtitleSourceKey
import com.nuvio.app.features.player.subtitleSyncCandidate
import com.nuvio.app.features.streams.StreamSubtitle
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextEpisodeAnalysisTest {
    private val durationMs = 2_400_000L
    private val energyMs = 300_000L
    private val tailMs = 360_000L
    private lateinit var head: IntArray
    private lateinit var tail: IntArray
    private var energy = ""
    private var windowCalls = 0
    private var holdAfterIntro = false

    @Before
    fun initialize() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        MediaAnalysisFiles.retainForTests = true
        MediaAnalysisFiles.clearForTests()
        MediaAnalysisStore.clearCacheForTests()
        SubtitleSourceMemory.clearForTests()
        NextEpisodeAnalysisScheduler.resetForTest()
        val random = Random(7)
        fun noise(size: Int) = IntArray(size) { random.nextInt() }
        val intro = noise(400)
        val credits = noise(800)
        head = noise(50) + intro + noise(3_000 - 50 - 400)
        tail = noise(3_600 - 800) + credits
        MediaAnalysisStore.save(
            MediaAnalysisResult(
                key = MediaAnalysisStore.keyFor("show1", 1, 1),
                analyzedAtMs = 1L,
                durationMs = durationMs,
                fingerprintStepMs = 100,
                headFingerprint = encodeFingerprint(head),
                tailFingerprint = encodeFingerprint(tail),
                tailStartMs = durationMs - tailMs,
                introStartMs = 5_000L,
                introEndMs = 45_000L,
            ),
        )
    }

    @After
    fun clearState() {
        NextEpisodeAnalysisScheduler.resetForTest()
        SubtitleSourceMemory.clearForTests()
        MediaAnalysisStore.clearCacheForTests()
        MediaAnalysisFiles.clearForTests()
        MediaAnalysisFiles.retainForTests = false
        Dispatchers.resetMain()
    }

    @Test
    fun nextEpisodeIsMeasuredWhileTheCurrentOneIsOpen() = runTest {
        val cues = cuesAt(energyMs, scale = 25.0 / 23.976, extraFileLagMs = 0L)
        arm(playing(), cues)
        NextEpisodeAnalysisScheduler.scopeForTest = this
        NextEpisodeAnalysisScheduler.targetOverrideForTest = { _, _, _, _ -> target(cues) }
        NextEpisodeAnalysisScheduler.watchPlayingNext("show1", 1, 2, "video-2")
        advanceUntilIdle()

        val stored = storedEpisode()
        assertNotNull(stored.introStartMs)
        assertNotNull(stored.introEndMs)
        assertTrue(stored.introEndMs!! - stored.introStartMs!! >= 15_000L)
        assertNotNull(stored.creditsStartMs)
        assertEquals(durationMs, stored.creditsEndMs)
        assertTrue(stored.userSegments.isEmpty())
        val speed = stored.subtitleSyncSpeed
        assertNotNull(speed)
        assertTrue(speed < 0.99, "speed $speed")
        assertEquals("opensubtitles|no|release-a", stored.subtitleSyncSourceKey)
        assertTrue(stored.subtitleSyncSourceKey!!.contains("://").not())
        assertEquals("done", NextEpisodeAnalysisScheduler.status.value.token)
        val remembered = SubtitleSourceMemory.recall("opensubtitles|no|release-a")
        assertNotNull(remembered)
        assertTrue(remembered.subSpeed < 0.99)
    }

    @Test
    fun aFinishedDownloadIsMeasuredWhileThePlayerIsOpen() = runTest {
        val cues = cuesAt(energyMs, scale = 25.0 / 23.976, extraFileLagMs = 0L)
        arm(playing(), cues)
        NextEpisodeAnalysisScheduler.scopeForTest = this
        NextEpisodeAnalysisScheduler.downloadLookupForTest = { id -> if (id == "dl-2") target(cues) else null }
        NextEpisodeAnalysisScheduler.onDownloadCompleted("dl-2")
        advanceUntilIdle()

        assertNotNull(storedEpisode().introStartMs)
        assertNotNull(storedEpisode().subtitleSyncDelayMs)
        assertTrue(windowCalls > 0)
    }

    @Test
    fun anIdleDownloadStaysOnTheFullFileQueue() = runTest {
        val cues = cuesAt(energyMs, scale = 1.0, extraFileLagMs = 0L)
        arm(playing().copy(videoPlaying = false), cues)
        NextEpisodeAnalysisScheduler.scopeForTest = this
        NextEpisodeAnalysisScheduler.downloadLookupForTest = { target(cues) }
        NextEpisodeAnalysisScheduler.onDownloadCompleted("dl-2")
        advanceUntilIdle()

        assertEquals(0, windowCalls)
        assertNull(MediaAnalysisStore.load(MediaAnalysisStore.keyFor("show1", 1, 2)))
    }

    @Test
    fun streamSlicesAreStitchedBeforeTheEpisodeStarts() = runTest {
        val cues = cuesAt(energyMs, scale = 25.0 / 23.976, extraFileLagMs = 0L)
        energy = quantizedEnergy(energyMs, speechAt(energyMs, 25.0 / 23.976, 0L))
        windowCalls = 0
        NextEpisodeAnalysisScheduler.conditionsForTest = { playing() }
        NextEpisodeAnalysisScheduler.windowsForTest = streamWindows()
        val result = NextEpisodeAnalysisScheduler.runNow(target(cues, local = false))

        assertEquals(NextEpisodeAnalysisHold.None, result.hold)
        assertTrue(!result.playbackStarted)
        assertTrue(windowCalls > 1)
        val stored = storedEpisode()
        assertNotNull(stored.introStartMs)
        assertNotNull(stored.creditsStartMs)
        assertTrue((stored.subtitleSyncSpeed ?: 1.0) < 0.99)
    }

    @Test
    fun aShortTrackKeepsAConstantOffset() = runTest {
        val cues = cuesAt(energyMs, scale = 1.0, extraFileLagMs = 2_000L)
        arm(playing(), cues)
        val result = NextEpisodeAnalysisScheduler.runNow(target(cues))

        assertEquals(NextEpisodeAnalysisHold.None, result.hold)
        val stored = storedEpisode()
        val speed = stored.subtitleSyncSpeed
        assertNotNull(speed)
        assertTrue(abs(speed - 1.0) < 0.02, "speed $speed")
        val delay = stored.subtitleSyncDelayMs
        assertNotNull(delay)
        assertTrue(abs(delay + 2_000L) <= 500L, "delay $delay")
    }

    @Test
    fun brakesSkipThePass() = runTest {
        val cues = cuesAt(energyMs, scale = 1.0, extraFileLagMs = 0L)
        val blocked = listOf(
            playing(freeBytes = ANALYSIS_FREE_SPACE_BRAKE_BYTES - 1) to NextEpisodeAnalysisHold.Disk,
            playing(lowPowerMode = true) to NextEpisodeAnalysisHold.LowPower,
            playing(lowBattery = true) to NextEpisodeAnalysisHold.LowBattery,
            playing(thermal = AnalysisThermal.Serious) to NextEpisodeAnalysisHold.Thermal,
            playing(thermal = AnalysisThermal.Critical) to NextEpisodeAnalysisHold.Thermal,
        )
        for ((conditions, hold) in blocked) {
            windowCalls = 0
            NextEpisodeAnalysisScheduler.conditionsForTest = { conditions }
            NextEpisodeAnalysisScheduler.windowsForTest = localWindows()
            val result = NextEpisodeAnalysisScheduler.runNow(target(cues))
            assertEquals(hold, result.hold)
            assertEquals(0, windowCalls)
            assertNull(MediaAnalysisStore.load(MediaAnalysisStore.keyFor("show1", 1, 2)))
            assertEquals(nextEpisodeHoldToken(hold), NextEpisodeAnalysisScheduler.status.value.token)
        }
    }

    @Test
    fun theDiskLineAndPlaybackDoNotBlockACoolPass() {
        assertEquals(
            NextEpisodeAnalysisHold.None,
            nextEpisodeAnalysisHold(playing(freeBytes = ANALYSIS_FREE_SPACE_BRAKE_BYTES)),
        )
        assertEquals(NextEpisodeAnalysisHold.None, nextEpisodeAnalysisHold(playing(freeBytes = null)))
        assertEquals(NextEpisodeAnalysisHold.Disk, nextEpisodeAnalysisHold(playing(freeBytes = ANALYSIS_FREE_SPACE_BRAKE_BYTES - 1)))
        assertEquals(NextEpisodeAnalysisHold.None, nextEpisodeAnalysisHold(playing()))
    }

    @Test
    fun aHoldBetweenWindowsDoesNotStoreAPartialResult() = runTest {
        val cues = cuesAt(energyMs, scale = 1.0, extraFileLagMs = 0L)
        arm(playing(), cues)
        holdAfterIntro = true
        val result = NextEpisodeAnalysisScheduler.runNow(target(cues))

        assertEquals(NextEpisodeAnalysisHold.LowPower, result.hold)
        assertEquals(1, windowCalls)
        assertNull(MediaAnalysisStore.load(MediaAnalysisStore.keyFor("show1", 1, 2)))
        assertEquals("paused-power", NextEpisodeAnalysisScheduler.status.value.token)
    }

    @Test
    fun cancellationDropsThePass() = runTest {
        val cues = cuesAt(energyMs, scale = 1.0, extraFileLagMs = 0L)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        windowCalls = 0
        NextEpisodeAnalysisScheduler.conditionsForTest = { playing() }
        NextEpisodeAnalysisScheduler.windowsForTest = object : NextEpisodeWindows {
            override suspend fun localWindow(fileUri: String, windowMs: Long, startMs: Long): RawMediaAnalysis? {
                windowCalls += 1
                started.complete(Unit)
                gate.await()
                return introWindow()
            }

            override suspend fun streamWindow(
                url: String,
                headers: Map<String, String>,
                startMs: Long,
                endMs: Long,
            ): RawClipFingerprint? = null
        }
        val running = async { NextEpisodeAnalysisScheduler.runNow(target(cues)) }
        started.await()
        running.cancel()
        gate.cancel()
        advanceUntilIdle()

        assertNull(MediaAnalysisStore.load(MediaAnalysisStore.keyFor("show1", 1, 2)))
    }

    @Test
    fun frameRateScaleAndConstantOffsetAreBothCorrected() {
        val duration = 2_400_000L
        val scaled = playFit(duration, 25.0 / 23.976, extraFileLagMs = 0L, restoredDelayMs = 1_500L)
        assertTrue(scaled.subSpeed < 0.99, "speed ${scaled.subSpeed}")
        val endFile = (duration * (25.0 / 23.976)).toLong()
        val shown = shownSubtitleMs(endFile, scaled)
        assertTrue(abs(shown - duration) < 3_000.0, "end error ${shown - duration}")

        val constant = playFit(duration, scale = 1.0, extraFileLagMs = 2_000L, restoredDelayMs = 1_500L)
        assertTrue(abs(constant.subSpeed - 1.0) < 0.001, "speed ${constant.subSpeed}")
        assertTrue(abs(constant.subDelayMs + 2_000L) <= 200L, "delay ${constant.subDelayMs}")
    }

    @Test
    fun aManualNudgeStillCancelsAutomaticSync() {
        val duration = 2_400_000L
        val analysis = analysisFor(duration, speechAt(duration, 1.0, 2_000L))
        val cues = cuesAt(duration, 1.0, 2_000L)
        val correction = SubtitleDriftCorrection(RecordingTransport())
        val ticket = correction.begin()
        correction.noteManualDelay(1_500L)
        val outcome = correction.correct(
            source = SubtitleObservationSource { energySubtitleObservations(analysis, cues) },
            ticket = ticket,
            provider = "OpenSubtitles",
            release = "release-a",
            automatic = true,
        )
        assertEquals(SubtitleDriftOutcome.Cancelled, outcome)
    }

    @Test
    fun aRememberedSourceFitAppliesWhenTheFileHasNoEnergy() {
        val key = subtitleSourceKey("OpenSubtitles", "no", "release-a")
        SubtitleSourceMemory.remember(key, 23.976 / 25.0, -300L)
        val correction = SubtitleDriftCorrection(RecordingTransport())
        val ticket = correction.begin()
        val outcome = correctSubtitleDrift(
            analysis = null,
            cues = emptyList(),
            correction = correction,
            ticket = ticket,
            provider = "OpenSubtitles",
            language = "no",
            release = "release-a",
        )
        assertEquals(SubtitleDriftOutcome.Applied, outcome)
        val fit = correction.rememberableFit()
        assertNotNull(fit)
        assertEquals(23.976 / 25.0, fit.first, 0.0001)
        assertEquals(-300L, fit.second)
    }

    @Test
    fun sourceKeysDropAddressesAndDownloadedSidecarsAreEligible() {
        val key = subtitleSourceKey("bad://example", "no", "release-a")
        assertEquals("-|no|release-a", key)
        assertTrue(!key.contains("example"))
        val chosen = subtitleSyncCandidate(
            selected = null,
            useCustomSubtitles = false,
            external = listOf(StreamSubtitle(url = "file:///tmp/no.srt", language = "no", name = "OpenSubtitles")),
            sourceUrl = "file:///tmp/episode.mkv",
            preferredLanguage = "no",
        )
        assertEquals("file:///tmp/no.srt", chosen?.url)
        assertNull(
            subtitleSyncCandidate(
                selected = null,
                useCustomSubtitles = false,
                external = emptyList(),
                sourceUrl = "http://video.local/file",
                preferredLanguage = "no",
            ),
        )
    }

    @Test
    fun aPlaybackPassKeepsMarksAndTheLongerPrint() = runTest {
        val key = MediaAnalysisStore.keyFor("show1", 1, 2)
        val fullEnergy = quantizedEnergy(durationMs, emptyList())
        // The one-time import cleanup runs on the first load and would drop this mark
        // before the pass can show that it keeps it. Record the cleanup first.
        MediaAnalysisStore.load(key)
        MediaAnalysisStore.save(
            MediaAnalysisResult(
                key = key,
                analyzedAtMs = 1L,
                durationMs = durationMs,
                fileName = "episode.mkv",
                energyStepMs = 100,
                energy = fullEnergy,
                fingerprintStepMs = 100,
                headFingerprint = encodeFingerprint(head),
                tailFingerprint = encodeFingerprint(tail),
                tailStartMs = durationMs - tailMs,
                introStartMs = 10_000L,
                introEndMs = 70_000L,
                introConfidence = SegmentConfidence(agreeingSiblings = 3, avgBitErrors = 1.0, theIntroDbAgrees = true),
                userSegments = mapOf(
                    "intro" to UserSegment(
                        startMs = 10_000L,
                        endMs = 70_000L,
                        markedAtMs = 5L,
                        imported = true,
                        durationMs = durationMs,
                    ),
                ),
                rejectedSegments = listOf(RejectedSegment("outro", 1_000L, 2_000L)),
                learnedTypes = setOf("intro"),
                thumbnails = ThumbnailSheets(
                    intervalMs = 10_000L,
                    count = 1,
                    tileWidth = 160,
                    tileHeight = 90,
                    columns = 10,
                    rows = 10,
                    sheets = 1,
                ),
            ),
        )
        val cues = cuesAt(energyMs, scale = 1.0, extraFileLagMs = 0L)
        arm(playing(), cues)
        val result = NextEpisodeAnalysisScheduler.runNow(target(cues))

        assertEquals(NextEpisodeAnalysisHold.None, result.hold)
        val stored = storedEpisode()
        val kept = stored.userSegments["intro"]
        assertNotNull(kept)
        assertEquals(10_000L, kept.startMs)
        assertEquals(70_000L, kept.endMs)
        assertTrue(kept.imported)
        assertEquals(10_000L, stored.introStartMs)
        assertEquals(70_000L, stored.introEndMs)
        assertEquals(true, stored.introConfidence?.theIntroDbAgrees)
        assertEquals("outro", stored.rejectedSegments.single().type)
        assertTrue(stored.learnedTypes.contains("intro"))
        assertEquals(1, stored.thumbnails?.sheets)
        assertEquals(fullEnergy, stored.energy)
    }

    @Test
    fun thePassStopsWhenPlaybackClosesBetweenWindows() = runTest {
        val cues = cuesAt(energyMs, scale = 1.0, extraFileLagMs = 0L)
        arm(playing(), cues)
        NextEpisodeAnalysisScheduler.windowsForTest = object : NextEpisodeWindows {
            override suspend fun localWindow(fileUri: String, windowMs: Long, startMs: Long): RawMediaAnalysis? {
                windowCalls += 1
                NextEpisodeAnalysisScheduler.conditionsForTest = { playing().copy(videoPlaying = false) }
                return introWindow()
            }

            override suspend fun streamWindow(
                url: String,
                headers: Map<String, String>,
                startMs: Long,
                endMs: Long,
            ): RawClipFingerprint? = null
        }
        val result = NextEpisodeAnalysisScheduler.runNow(target(cues))

        assertEquals(NextEpisodeAnalysisHold.None, result.hold)
        assertEquals(1, windowCalls)
        assertNull(MediaAnalysisStore.load(MediaAnalysisStore.keyFor("show1", 1, 2)))
    }

    @Test
    fun aBrakeOnAFinishedDownloadLeavesTheOpenEpisodePassRunning() = runTest {
        val cues = cuesAt(energyMs, scale = 1.0, extraFileLagMs = 0L)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var downloadHold = false
        windowCalls = 0
        NextEpisodeAnalysisScheduler.conditionsForTest = {
            if (downloadHold) playing(lowPowerMode = true) else playing()
        }
        NextEpisodeAnalysisScheduler.windowsForTest = object : NextEpisodeWindows {
            override suspend fun localWindow(fileUri: String, windowMs: Long, startMs: Long): RawMediaAnalysis? {
                windowCalls += 1
                if (startMs == 0L) {
                    started.complete(Unit)
                    gate.await()
                    return introWindow()
                }
                return RawMediaAnalysis(
                    durationMs = durationMs,
                    fingerprintStepMs = 100,
                    tail = encodeFingerprint(tail),
                    tailStartMs = durationMs - tailMs,
                )
            }

            override suspend fun streamWindow(
                url: String,
                headers: Map<String, String>,
                startMs: Long,
                endMs: Long,
            ): RawClipFingerprint? = null
        }
        NextEpisodeAnalysisScheduler.scopeForTest = this
        NextEpisodeAnalysisScheduler.targetOverrideForTest = { _, _, _, _ -> target(cues) }
        NextEpisodeAnalysisScheduler.watchPlayingNext("show1", 1, 2, "video-2")
        started.await()
        downloadHold = true
        NextEpisodeAnalysisScheduler.downloadLookupForTest = { id ->
            if (id == "other") target(cues).copy(episode = 3, downloadId = "other") else null
        }
        NextEpisodeAnalysisScheduler.onDownloadCompleted("other")
        advanceUntilIdle()
        downloadHold = false
        gate.complete(Unit)
        advanceUntilIdle()

        assertNotNull(MediaAnalysisStore.load(MediaAnalysisStore.keyFor("show1", 1, 2))?.introStartMs)
    }

    @Test
    fun aFinishedDownloadRetriesWhenTheBrakeLiftsWhileThePlayerStaysOpen() = runTest {
        val cues = cuesAt(energyMs, scale = 1.0, extraFileLagMs = 0L)
        var reads = 0
        windowCalls = 0
        energy = quantizedEnergy(energyMs, speechAt(energyMs, 1.0, 0L))
        NextEpisodeAnalysisScheduler.conditionsForTest = {
            reads += 1
            if (reads < 3) playing(lowPowerMode = true) else playing()
        }
        NextEpisodeAnalysisScheduler.windowsForTest = localWindows()
        NextEpisodeAnalysisScheduler.scopeForTest = this
        NextEpisodeAnalysisScheduler.holdRetryMsForTest = 1L
        NextEpisodeAnalysisScheduler.downloadLookupForTest = { target(cues) }
        NextEpisodeAnalysisScheduler.onDownloadCompleted("dl-2")
        advanceUntilIdle()

        assertTrue(windowCalls > 0)
        assertNotNull(MediaAnalysisStore.load(MediaAnalysisStore.keyFor("show1", 1, 2)))
    }

    @Test
    fun aPartialStreamSliceIsNotStored() = runTest {
        windowCalls = 0
        NextEpisodeAnalysisScheduler.conditionsForTest = { playing() }
        NextEpisodeAnalysisScheduler.windowsForTest = object : NextEpisodeWindows {
            override suspend fun localWindow(fileUri: String, windowMs: Long, startMs: Long): RawMediaAnalysis? = null

            override suspend fun streamWindow(
                url: String,
                headers: Map<String, String>,
                startMs: Long,
                endMs: Long,
            ): RawClipFingerprint? {
                windowCalls += 1
                if (windowCalls > 1) return null
                return RawClipFingerprint(
                    startMs = startMs,
                    stepMs = 100,
                    fingerprint = encodeFingerprint(intArrayOf(1, 2, 3, 4)),
                    energy = encodeEnergyLevels(intArrayOf(1, 2, 3, 4)),
                    energyStepMs = 100,
                    windowComplete = true,
                    stopReason = "window-end",
                )
            }
        }
        val result = NextEpisodeAnalysisScheduler.runNow(target(emptyList(), local = false))

        assertEquals(NextEpisodeAnalysisHold.None, result.hold)
        assertTrue(windowCalls > 1)
        assertNull(MediaAnalysisStore.load(MediaAnalysisStore.keyFor("show1", 1, 2)))
    }

    @Test
    fun aManualNudgeIsNotReplacedByARememberedFit() {
        val key = subtitleSourceKey("OpenSubtitles", "no", "release-a")
        SubtitleSourceMemory.remember(key, 23.976 / 25.0, -2_000L)
        val transport = RecordingTransport()
        val correction = SubtitleDriftCorrection(transport)
        val ticket = correction.begin()
        correction.noteManualDelay(400L)
        val outcome = correctSubtitleDrift(
            analysis = analysisFor(durationMs, speechAt(durationMs, 1.0, 0L)),
            cues = cuesAt(durationMs, 1.0, 0L),
            correction = correction,
            ticket = ticket,
            provider = "OpenSubtitles",
            language = "no",
            release = "release-a",
        )

        assertEquals(SubtitleDriftOutcome.Cancelled, outcome)
        assertTrue(transport.fits.isEmpty())
        assertEquals(listOf(400L), transport.userDelays)
    }

    @Test
    fun aRememberedFitIsNotAppliedWhenThisFileCanBeMeasured() {
        val key = subtitleSourceKey("OpenSubtitles", "no", "release-a")
        SubtitleSourceMemory.remember(key, 23.976 / 25.0, -2_000L)
        val transport = RecordingTransport()
        val correction = SubtitleDriftCorrection(transport)
        val ticket = correction.begin()
        val outcome = correctSubtitleDrift(
            analysis = analysisFor(durationMs, emptyList()),
            cues = cuesAt(durationMs, 1.0, 0L),
            correction = correction,
            ticket = ticket,
            provider = "OpenSubtitles",
            language = "no",
            release = "release-a",
        )

        assertTrue(outcome != SubtitleDriftOutcome.Applied || transport.fits.all { abs(it.first - 1.0) < 0.02 })
        assertTrue(transport.fits.none { it.first < 0.99 })
    }

    @Test
    fun aRememberedFrameRateIsNotAppliedTwice() {
        val key = subtitleSourceKey("OpenSubtitles", "no", "release-a")
        SubtitleSourceMemory.remember(key, 23.976 / 25.0, 0L)
        val fit = playFit(2_400_000L, 25.0 / 23.976, extraFileLagMs = 0L, restoredDelayMs = 0L)
        assertTrue(abs(fit.subSpeed - (23.976 / 25.0)) < 0.02, "speed ${fit.subSpeed}")
        val endFile = (2_400_000L * (25.0 / 23.976)).toLong()
        val shown = shownSubtitleMs(endFile, fit)
        assertTrue(abs(shown - 2_400_000.0) < 3_000.0, "end ${shown - 2_400_000.0}")
    }

    @Test
    fun playbackSlicesStayOffTheMainQueueAndDoNotLogAddresses() {
        val analyzer = locateRepoFile("iosApp/iosApp/Player/MediaAnalyzer.swift").readText()
        val playback = analyzer.substringAfter("func fingerprintClipDuringPlayback")
            .substringBefore("func cancelPlayback")
        assertFalse(playback.contains("DispatchQueue.main"))
        assertTrue(playback.contains("DispatchQueue.global"))
        assertTrue(analyzer.contains("func cancelPlayback(generation: Int64)"))
        assertTrue(analyzer.contains("av_log_set_level(-8)"))
        val clip = analyzer.substringAfter("private final class ClipFingerprintRun")
            .substringBefore("private enum ClipAddressLog")
        assertFalse(Regex("""log\([^)]*\\\(url\)""").containsMatchIn(clip))
        assertFalse(Regex("""log\([^)]*header""").containsMatchIn(clip))
        val downloads = locateRepoFile(
            "composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/DownloadsRepository.kt",
        ).readText()
        val success = downloads.substringAfter("onSuccess = onSuccess@").substringBefore("onFailure")
        assertTrue(success.contains("NextEpisodeAnalysisScheduler.onDownloadCompleted"))
    }

    private fun arm(conditions: NextEpisodeAnalysisConditions, cues: List<SubtitleSyncCue>) {
        energy = quantizedEnergy(energyMs, speechAt(energyMs, 1.0, 0L))
        if (cues.isNotEmpty()) {
            val scale = if (cues.first().startTimeMs > 8_000L) 25.0 / 23.976 else 1.0
            val extra = if (abs(scale - 1.0) < 0.0001 && cues.first().startTimeMs > 10_000L) 2_000L else 0L
            energy = quantizedEnergy(energyMs, speechAt(energyMs, scale, extra))
        }
        windowCalls = 0
        holdAfterIntro = false
        NextEpisodeAnalysisScheduler.conditionsForTest = { conditions }
        NextEpisodeAnalysisScheduler.windowsForTest = localWindows()
    }

    private fun target(cues: List<SubtitleSyncCue>, local: Boolean = true) = NextEpisodeTarget(
        parentMetaId = "show1",
        season = 1,
        episode = 2,
        videoId = "video-2",
        localFileUri = if (local) "file:///tmp/episode.mkv" else null,
        streamUrl = if (local) null else "http://video.local/next",
        cues = cues,
        provider = "OpenSubtitles",
        language = "no",
        release = "release-a",
        fileName = "episode.mkv",
        durationMs = durationMs,
        downloadId = "dl-2",
    )

    private fun storedEpisode(): MediaAnalysisResult {
        val stored = MediaAnalysisStore.load(MediaAnalysisStore.keyFor("show1", 1, 2))
        assertNotNull(stored)
        return stored
    }

    private fun playing(
        freeBytes: Long? = 20_000_000_000L,
        lowPowerMode: Boolean = false,
        lowBattery: Boolean = false,
        thermal: AnalysisThermal = AnalysisThermal.Nominal,
    ) = NextEpisodeAnalysisConditions(
        videoPlaying = true,
        freeBytes = freeBytes,
        thermal = thermal,
        lowPowerMode = lowPowerMode,
        lowBattery = lowBattery,
    )

    private fun localWindows() = object : NextEpisodeWindows {
        override suspend fun localWindow(fileUri: String, windowMs: Long, startMs: Long): RawMediaAnalysis? {
            windowCalls += 1
            if (startMs == 0L) {
                if (holdAfterIntro) {
                    NextEpisodeAnalysisScheduler.conditionsForTest = { playing(lowPowerMode = true) }
                }
                return introWindow()
            }
            return RawMediaAnalysis(
                durationMs = durationMs,
                fingerprintStepMs = 100,
                tail = encodeFingerprint(tail),
                tailStartMs = durationMs - tailMs,
            )
        }

        override suspend fun streamWindow(
            url: String,
            headers: Map<String, String>,
            startMs: Long,
            endMs: Long,
        ): RawClipFingerprint? = null
    }

    private fun introWindow() = RawMediaAnalysis(
        durationMs = durationMs,
        energy = energy,
        energyStepMs = 100,
        fingerprintStepMs = 100,
        head = encodeFingerprint(head),
    )

    private fun streamWindows() = object : NextEpisodeWindows {
        override suspend fun localWindow(fileUri: String, windowMs: Long, startMs: Long): RawMediaAnalysis? = null

        override suspend fun streamWindow(
            url: String,
            headers: Map<String, String>,
            startMs: Long,
            endMs: Long,
        ): RawClipFingerprint? {
            windowCalls += 1
            val intro = startMs < INTRO_WINDOW_MS
            val origin = if (intro) 0L else durationMs - tailMs
            val frames = slice(if (intro) head else tail, startMs - origin, endMs - startMs)
            if (frames.isEmpty()) return null
            val levels = if (intro) slice(decodeEnergyLevels(energy), startMs, endMs - startMs) else IntArray(0)
            return RawClipFingerprint(
                startMs = startMs,
                stepMs = 100,
                fingerprint = encodeFingerprint(frames),
                energy = if (levels.isEmpty()) null else encodeEnergyLevels(levels),
                energyStepMs = 100,
                windowComplete = true,
                stopReason = "window-end",
            )
        }
    }

    private fun slice(values: IntArray, startMs: Long, lengthMs: Long): IntArray {
        val from = (startMs / 100L).toInt()
        val count = (lengthMs / 100L).toInt()
        if (from >= values.size || count <= 0) return IntArray(0)
        return values.copyOfRange(from, min(from + count, values.size))
    }

    private fun playFit(duration: Long, scale: Double, extraFileLagMs: Long, restoredDelayMs: Long): MpvSubtitleProperties {
        val analysis = analysisFor(duration, speechAt(duration, scale, extraFileLagMs))
        val cues = cuesAt(duration, scale, extraFileLagMs)
        val correction = SubtitleDriftCorrection(RecordingTransport())
        val ticket = correction.begin()
        correction.noteRestoredDelay(restoredDelayMs)
        val outcome = correctSubtitleDrift(
            analysis = analysis,
            cues = cues,
            correction = correction,
            ticket = ticket,
            provider = "OpenSubtitles",
            language = "no",
            release = "release-a",
        )
        assertEquals(SubtitleDriftOutcome.Applied, outcome)
        val fit = correction.rememberableFit()
        assertNotNull(fit)
        return MpvSubtitleProperties(fit.first, fit.second, confident = true)
    }

    private fun analysisFor(duration: Long, speech: List<Pair<Long, Long>>) = MediaAnalysisResult(
        key = "show1__s1e2",
        analyzedAtMs = 1L,
        durationMs = duration,
        energyStepMs = 100,
        energy = quantizedEnergy(duration, speech),
    )

    private fun speechAt(duration: Long, scale: Double, extraFileLagMs: Long): List<Pair<Long, Long>> {
        if (scale == 0.0 && extraFileLagMs == 0L) return emptyList()
        return centers(duration).flatMap { maskAround(it) }
    }

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

    private fun maskAround(center: Long): List<Pair<Long, Long>> {
        val shift = center - 20_000L
        return listOf(
            8_000L to 10_000L,
            12_000L to 14_500L,
            17_000L to 19_000L,
            22_000L to 25_000L,
        ).map { (start, end) -> start + shift to end + shift }
    }

    private fun quantizedEnergy(durationMs: Long, speech: List<Pair<Long, Long>>): String {
        val step = 100
        val count = (durationMs / step).toInt()
        val levels = IntArray(count) { index ->
            val time = index.toLong() * step
            val speaking = speech.any { (start, end) -> time >= start && time < end }
            energyLevelForLinear(if (speaking) 0.5 else 0.02)
        }
        return encodeEnergyLevels(levels)
    }
}

private class RecordingTransport : SubtitleSyncTransport {
    val fits = mutableListOf<Pair<Double, Long>>()
    val userDelays = mutableListOf<Long>()

    override fun applyFit(speed: Double, fitDelayMs: Long) {
        fits += speed to fitDelayMs
    }

    override fun applyUserDelay(delayMs: Long) {
        userDelays += delayMs
    }

    override fun resetFit() = Unit
}

private fun locateRepoFile(relative: String): File {
    val start = File(System.getProperty("user.dir").orEmpty().ifEmpty { "." })
    return generateSequence(start) { it.parentFile }
        .take(6)
        .map { File(it, relative) }
        .firstOrNull { it.isFile }
        ?: error("Missing $relative from ${start.absolutePath}")
}
