package com.nuvio.app.features.analysis

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.player.CHAPTER_SKIP_PROVIDER
import com.nuvio.app.features.player.ON_DEVICE_SKIP_PROVIDER
import com.nuvio.app.features.player.onDeviceSkipIntervalsFrom
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.TheIntroDb
import com.nuvio.app.features.player.smartDownloadCreditsStartMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ChapterSkipPriorityTest {
    private val runtimeMs = 40 * 60_000L
    private val chapters = listOf(
        FileChapter("Previously", 0L, 20_000L),
        FileChapter("Intro", 20_000L, 110_000L),
        FileChapter("Episode", 110_000L, 2_250_000L),
        FileChapter("Credits", 2_250_000L, 2_370_000L),
        FileChapter("Next Time On", 2_370_000L, runtimeMs),
    )

    private fun base(key: String = "chapter-priority__s1e1") = MediaAnalysisResult(
        key = key,
        analyzedAtMs = 1L,
        durationMs = runtimeMs,
        fileName = "e.mkv",
    )

    @Test
    fun chaptersReplaceFingerprintTimesAndLeaveUncoveredKindsUntouched() {
        val fingerprint = base().copy(
            introStartMs = 1L,
            introEndMs = 2L,
            creditsStartMs = 9L,
            creditsEndMs = 10L,
        )
        val introOnly = fingerprint.applyingChapterSegments(
            chapters = listOf(FileChapter("Intro", 0L, 60_000L)),
            durationMs = runtimeMs,
            protectedKinds = emptySet(),
            introDetection = true,
            recapDetection = true,
        )
        assertEquals(0L, introOnly.introStartMs)
        assertEquals(60_000L, introOnly.introEndMs)
        assertEquals(9L, introOnly.creditsStartMs)
        assertEquals(10L, introOnly.creditsEndMs)
        assertEquals(setOf("intro"), introOnly.chapterTypes)
    }

    @Test
    fun higherTrustSourcesAreNotReplaced() {
        val user = base().copy(
            introStartMs = 5L,
            introEndMs = 6L,
            userSegments = mapOf("intro" to UserSegment(5L, 6L)),
            creditsStartMs = 7L,
            creditsEndMs = 8L,
            learnedTypes = setOf("outro"),
            recapStartMs = 1L,
            recapEndMs = 2L,
            foundUsing = mapOf("recap" to 4),
        )
        val applied = user.applyingChapterSegments(
            chapters = chapters,
            durationMs = runtimeMs,
            protectedKinds = user.userSegments.keys + user.learnedTypes + user.foundUsing.keys,
            introDetection = true,
            recapDetection = true,
        )
        assertEquals(5L, applied.introStartMs)
        assertEquals(6L, applied.introEndMs)
        assertEquals(7L, applied.creditsStartMs)
        assertEquals(8L, applied.creditsEndMs)
        assertEquals(1L, applied.recapStartMs)
        assertEquals(2L, applied.recapEndMs)
        assertEquals(setOf("preview"), applied.chapterTypes)
        assertEquals(2_370_000L, applied.previewStartMs)
        assertEquals(runtimeMs, applied.previewEndMs)
    }

    @Test
    fun disabledDetectionDoesNotRecordChapters() {
        val applied = base().applyingChapterSegments(
            chapters = chapters,
            durationMs = runtimeMs,
            protectedKinds = emptySet(),
            introDetection = false,
            recapDetection = false,
        )
        assertEquals(emptySet(), applied.chapterTypes)
        assertNull(applied.introStartMs)
        assertNull(applied.creditsStartMs)
        assertNull(applied.recapStartMs)
        assertNull(applied.previewStartMs)
    }

    @Test
    fun detectedRecapDoesNotClearAChapterRecap() {
        val chaptered = base().copy(recapStartMs = 0L, recapEndMs = 20_000L, chapterTypes = setOf("recap"))
        val kept = chaptered.withDetectedRecap(null)
        assertEquals(0L, kept.recapStartMs)
        assertEquals(20_000L, kept.recapEndMs)
        val replaced = base().copy(recapStartMs = 1L, recapEndMs = 2L).withDetectedRecap(3L to 4L)
        assertEquals(3L, replaced.recapStartMs)
        assertEquals(4L, replaced.recapEndMs)
        val keptKnown = base().copy(recapStartMs = 1L, recapEndMs = 2L).withDetectedRecap(null)
        assertEquals(1L, keptKnown.recapStartMs)
        assertEquals(2L, keptKnown.recapEndMs)
    }

    @Test
    fun aRecapturedPrintKeepsKnownIntroCreditsAndRecap() {
        val previous = base().copy(
            fileName = "episode.mkv",
            introStartMs = 10_000L,
            introEndMs = 40_000L,
            introConfidence = SegmentConfidence(agreeingSiblings = 2),
            creditsStartMs = 40_000L,
            creditsEndMs = 48_000L,
            recapStartMs = 0L,
            recapEndMs = 15_000L,
            recapOutcome = "rejected-no-match",
        )
        val fresh = base().copy(fileName = "episode.mkv", headFingerprint = "new")
        val carried = fresh.carryingKnownSegments(previous)
        assertEquals(10_000L, carried.introStartMs)
        assertEquals(40_000L, carried.introEndMs)
        assertEquals(40_000L, carried.creditsStartMs)
        assertEquals(0L, carried.recapStartMs)
        assertEquals(15_000L, carried.recapEndMs)
        assertEquals("new", carried.headFingerprint)
    }

    @Test
    fun detectedPreviewDoesNotClearAChapterPreview() {
        val chaptered = base().copy(previewStartMs = 2_370_000L, previewEndMs = runtimeMs, chapterTypes = setOf("preview"))
        val kept = chaptered.withDetectedPreview(null)
        assertEquals(2_370_000L, kept.previewStartMs)
        assertEquals(runtimeMs, kept.previewEndMs)
        val replaced = base().copy(previewStartMs = 1L, previewEndMs = 2L).withDetectedPreview(3L to 4L)
        assertEquals(3L, replaced.previewStartMs)
        assertEquals(4L, replaced.previewEndMs)
        val marked = base().copy(
            previewStartMs = 9L,
            previewEndMs = 10L,
            userSegments = mapOf("preview" to UserSegment(startMs = 5L, endMs = 6L, durationMs = runtimeMs)),
        )
        val keptMark = marked.withDetectedPreview(null)
        assertEquals(9L, keptMark.previewStartMs)
        assertEquals(10L, keptMark.previewEndMs)
    }

    @Test
    fun aMarkOnThisDeviceDropsTheChapterTag() = runBlocking {
        val key = "chapter-mark__s1e1"
        MediaAnalysisStore.save(
            base(key).copy(introStartMs = 0L, introEndMs = 60_000L, chapterTypes = setOf("intro")),
        )
        SkipLearning.recordMark("chapter-mark", 1, 1, "intro", 4_000L, 50_000L, runtimeMs)
        val stored = MediaAnalysisStore.load(key)
        assertEquals(false, stored?.chapterTypes?.contains("intro"))
        assertEquals(4_000L, stored?.introStartMs)
        assertEquals(50_000L, stored?.introEndMs)
    }

    @Test
    fun importedSubmissionDropsTheChapterTag() {
        val key = "chapter-import__s2e3"
        MediaAnalysisStore.save(
            base(key).copy(introStartMs = 0L, introEndMs = 60_000L, chapterTypes = setOf("intro")),
        )
        val added = SkipLearning.importMark("chapter-import", 2, 3, "intro", 8_000L, 70_000L, runtimeMs)
        assertTrue(added)
        val stored = MediaAnalysisStore.load(key)
        assertEquals(false, stored?.chapterTypes?.contains("intro"))
        assertEquals(8_000L, stored?.introStartMs)
        assertEquals(70_000L, stored?.introEndMs)
        assertTrue(stored?.userSegments?.get("intro")?.imported == true)
    }

    @Test
    fun chapterProviderIsNotStoredAsAnExternalDatabase() {
        val show = "chapter-ledger"
        val key = MediaAnalysisStore.keyFor(show, 1, 1)
        SkipSourceLedger.record(
            show,
            1,
            1,
            listOf(SkipInterval(0.0, 30.0, "intro", CHAPTER_SKIP_PROVIDER)),
        )
        assertNull(SkipSourceLedger.online(key)["intro"])
        SkipSourceLedger.record(
            show,
            1,
            1,
            listOf(
                SkipInterval(1.0, 40.0, "intro", TheIntroDb.PROVIDER),
                SkipInterval(2_250.0, 2_370.0, "outro", CHAPTER_SKIP_PROVIDER),
            ),
        )
        assertEquals(TheIntroDb.PROVIDER, SkipSourceLedger.online(key)["intro"])
        assertNull(SkipSourceLedger.online(key)["outro"])
    }

    @Test
    fun sourceRowPrefersTheExternalDatabaseOverChapters() {
        val item = episode("chapter-row")
        val key = MediaAnalysisStore.keyFor(item)
        MediaAnalysisStore.save(
            base(key).copy(
                introStartMs = 20_000L,
                introEndMs = 110_000L,
                chapterTypes = setOf("intro"),
                fileName = item.fileName,
            ),
        )
        assertEquals(listOf("Intro" to "Chapters"), skipSourcesFor(item).filter { it.first == "Intro" })
        SkipSourceLedger.record(
            item.parentMetaId,
            item.seasonNumber,
            item.episodeNumber,
            listOf(SkipInterval(1.0, 40.0, "intro", TheIntroDb.PROVIDER)),
        )
        assertEquals(listOf("Intro" to "TheIntroDB"), skipSourcesFor(item).filter { it.first == "Intro" })
    }

    @Test
    fun skipIntervalsUseTheChapterProviderOnlyForChapterKinds() {
        val analysis = base().applyingChapterSegments(
            chapters = listOf(FileChapter("Intro", 0L, 60_000L)),
            durationMs = runtimeMs,
            protectedKinds = emptySet(),
            introDetection = true,
            recapDetection = true,
        ).copy(creditsStartMs = 2_300_000L, creditsEndMs = runtimeMs)
        val intervals = onDeviceSkipIntervalsFrom(analysis, recapDetection = true, introDetection = true)
        assertEquals(CHAPTER_SKIP_PROVIDER, intervals.first { it.type == "intro" }.provider)
        assertEquals(ON_DEVICE_SKIP_PROVIDER, intervals.first { it.type == "outro" }.provider)
    }

    @Test
    fun aUserMarkKeepsTheHeadPrintAndTheTailStaysForTheNeighbours() {
        val key = "chapter-keep-print__s1e1"
        MediaAnalysisStore.save(base(key).copy(userSegments = mapOf("intro" to UserSegment(0L, 30_000L))))
        ChapterSkipClassifier.prepare(key, "chapter-keep-print", 1)
        try {
            val json = """
                [
                  {"title":"Previously","startMs":0,"endMs":20000},
                  {"title":"Intro","startMs":20000,"endMs":110000},
                  {"title":"Credits","startMs":2310000,"endMs":2400000}
                ]
            """.trimIndent()
            assertEquals("", ChapterSkipClassifier.fingerprintSkip(json, runtimeMs))
            assertEquals(SkipSourcePriority.USER, SkipSourcePriority.rank(SkipLearning.PROVIDER))
            assertEquals(SkipSourcePriority.EXTERNAL_DATABASE, SkipSourcePriority.rank(TheIntroDb.PROVIDER))
            assertTrue(SkipSourcePriority.rank(CHAPTER_SKIP_PROVIDER) < SkipSourcePriority.rank(ON_DEVICE_SKIP_PROVIDER))
            assertEquals(SkipSourcePriority.GUESS, SkipSourcePriority.rank("heuristic"))
        } finally {
            ChapterSkipClassifier.clear()
        }
    }

    @Test
    fun smartDownloadStillPrefersAUserMarkAndTheIntroDbOverChapters() {
        val chapters = SkipInterval(100.0, 130.0, "outro", CHAPTER_SKIP_PROVIDER)
        val user = SkipInterval(90.0, 120.0, "outro", SkipLearning.PROVIDER)
        val database = SkipInterval(50.0, 80.0, "outro", TheIntroDb.PROVIDER)
        val onDevice = SkipInterval(80.0, 100.0, "outro", ON_DEVICE_SKIP_PROVIDER)
        val introDb = SkipInterval(40.0, 70.0, "outro", "introdb")
        assertEquals(90_000L, smartDownloadCreditsStartMs(listOf(chapters, user)))
        assertEquals(50_000L, smartDownloadCreditsStartMs(listOf(chapters, database)))
        assertEquals(100_000L, smartDownloadCreditsStartMs(listOf(chapters)))
        assertEquals(80_000L, smartDownloadCreditsStartMs(listOf(onDevice, introDb)))
        assertNull(smartDownloadCreditsStartMs(emptyList()))
    }

    private fun episode(show: String) = DownloadItem(
        id = "$show-download",
        contentType = "series",
        parentMetaId = show,
        parentMetaType = "series",
        videoId = "$show:1:1",
        title = "Show",
        seasonNumber = 1,
        episodeNumber = 1,
        streamTitle = "S1E1",
        providerName = "Test",
        sourceUrl = "https://example.test/e.mkv",
        fileName = "e.mkv",
        status = DownloadStatus.Completed,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )
}
