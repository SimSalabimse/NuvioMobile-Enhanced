package com.nuvio.app.features.analysis

import android.app.Application
import android.content.Context
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Intro and credits are separate parts. Each row is one stored result run through [MediaAnalysisStatus.statusFor].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IntroCreditsStatusTest {
    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_downloads_settings", Context.MODE_PRIVATE).edit().clear().commit()
        DownloadsSettingsStorage.initialize(context)
        MediaAnalysisFiles.retainForTests = true
        MediaAnalysisFiles.clearForTests()
        MediaAnalysisStore.clearCacheForTests()
        MediaAnalysisStatus.supportedForTests = true
        MediaAnalysisStatus.setJob(AnalysisJobState.Idle)
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.IntroDetection, true)
    }

    @After
    fun tearDown() {
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.IntroDetection, true)
        MediaAnalysisStatus.supportedForTests = null
        MediaAnalysisStatus.setJob(AnalysisJobState.Idle)
        MediaAnalysisFiles.clearForTests()
        MediaAnalysisFiles.retainForTests = false
        MediaAnalysisStore.clearCacheForTests()
    }

    @Test
    fun everyIntroAndCreditsCombination() {
        val cases = listOf(
            Case(
                name = "intro found, credits missing",
                introEndMs = 90_000L,
                creditsStartMs = null,
                withSibling = true,
                creditsOutcome = "rejected-no-cluster",
                intro = AnalysisPart.Ready,
                credits = AnalysisPart.NotFound,
            ),
            Case(
                name = "credits found, intro missing",
                introEndMs = null,
                creditsStartMs = 1_200_000L,
                withSibling = true,
                introOutcome = "rejected-no-cluster",
                intro = AnalysisPart.NotFound,
                credits = AnalysisPart.Ready,
            ),
            Case(
                name = "both found",
                introEndMs = 90_000L,
                creditsStartMs = 1_200_000L,
                withSibling = false,
                intro = AnalysisPart.Ready,
                credits = AnalysisPart.Ready,
            ),
            Case(
                name = "neither, with a sibling",
                introEndMs = null,
                creditsStartMs = null,
                withSibling = true,
                introOutcome = "rejected-no-cluster",
                creditsOutcome = "rejected-no-cluster",
                intro = AnalysisPart.NotFound,
                credits = AnalysisPart.NotFound,
            ),
            Case(
                name = "cancel leaves intro and credits unchecked",
                introEndMs = null,
                creditsStartMs = null,
                withSibling = true,
                intro = AnalysisPart.NotChecked,
                credits = AnalysisPart.NotChecked,
            ),
            Case(
                name = "neither, no sibling",
                introEndMs = null,
                creditsStartMs = null,
                withSibling = false,
                intro = AnalysisPart.NeedsMoreEpisodes,
                credits = AnalysisPart.NeedsMoreEpisodes,
            ),
            Case(
                name = "intro detection off",
                introEndMs = 90_000L,
                creditsStartMs = 1_200_000L,
                withSibling = true,
                introDetection = false,
                intro = AnalysisPart.Off,
                credits = AnalysisPart.Off,
            ),
            Case(
                name = "movie, credits only",
                episode = false,
                introEndMs = 90_000L,
                creditsStartMs = 1_200_000L,
                withSibling = false,
                intro = AnalysisPart.Off,
                credits = AnalysisPart.Ready,
            ),
            Case(
                name = "movie, credits missing",
                episode = false,
                introEndMs = 90_000L,
                creditsStartMs = null,
                withSibling = false,
                intro = AnalysisPart.Off,
                credits = AnalysisPart.NotFound,
            ),
            Case(
                name = "previews still pending",
                introEndMs = 90_000L,
                creditsStartMs = 1_200_000L,
                withSibling = false,
                thumbnails = false,
                intro = AnalysisPart.Ready,
                credits = AnalysisPart.Ready,
                complete = false,
            ),
        )

        cases.forEach { case ->
            MediaAnalysisFiles.clearForTests()
            MediaAnalysisStore.clearCacheForTests()
            MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.IntroDetection, case.introDetection)
            val item = store(case)
            val status = MediaAnalysisStatus.statusFor(item)
            val done = assertIs<ItemAnalysisStatus.Done>(status, case.name)
            assertEquals(case.intro, done.parts.intro, case.name)
            assertEquals(case.credits, done.parts.credits, case.name)
            assertEquals(case.complete, done.complete, case.name)
            val chips = analysisChipKinds(item, done.parts)
            if (case.episode && case.introDetection) {
                assertTrue(AnalysisChipKind.Intro in chips, case.name)
            } else {
                assertFalse(AnalysisChipKind.Intro in chips, case.name)
            }
            if (case.introDetection) {
                assertTrue(AnalysisChipKind.Credits in chips, case.name)
            } else {
                assertFalse(AnalysisChipKind.Credits in chips, case.name)
            }
            if (!case.complete) {
                assertEquals(AnalysisPart.Pending, done.parts.previews, case.name)
            }
        }
    }

    private fun store(case: Case): DownloadItem {
        val show = "show-" + case.name.map { if (it.isLetterOrDigit()) it else '-' }
        val season = if (case.episode) 1 else null
        val episode = if (case.episode) 2 else null
        val item = DownloadItem(
            id = show,
            contentType = if (case.episode) "series" else "movie",
            parentMetaId = show,
            parentMetaType = if (case.episode) "series" else "movie",
            videoId = show,
            title = case.name,
            streamTitle = case.name,
            providerName = "Test",
            sourceUrl = "https://example.com/$show.mkv",
            fileName = "$show.mkv",
            status = DownloadStatus.Completed,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
            seasonNumber = season,
            episodeNumber = episode,
        )
        if (case.withSibling && season != null && episode != null) {
            val siblingKey = MediaAnalysisStore.keyFor(show, season, episode + 1)
            MediaAnalysisStore.save(
                MediaAnalysisResult(
                    key = siblingKey,
                    analyzedAtMs = 1L,
                    durationMs = 1_000L,
                    headFingerprint = "fp",
                ),
            )
        }
        val key = MediaAnalysisStore.keyFor(item)
        MediaAnalysisStore.save(
            MediaAnalysisResult(
                key = key,
                analyzedAtMs = 2L,
                durationMs = 2_400_000L,
                fileName = item.fileName,
                energy = "AA",
                derivedVersion = MEDIA_ANALYSIS_DERIVED_VERSION,
                thumbnails = if (case.thumbnails) sheets() else null,
                introEndMs = case.introEndMs,
                introOutcome = case.introOutcome,
                creditsStartMs = case.creditsStartMs,
                creditsOutcome = case.creditsOutcome,
            ),
        )
        return item
    }

    private fun sheets() = ThumbnailSheets(
        intervalMs = 10_000L,
        count = 1,
        tileWidth = 16,
        tileHeight = 9,
        columns = 1,
        rows = 1,
        sheets = 1,
    )

    private data class Case(
        val name: String,
        val episode: Boolean = true,
        val introEndMs: Long?,
        val introOutcome: String? = null,
        val creditsStartMs: Long?,
        val creditsOutcome: String? = null,
        val withSibling: Boolean,
        val introDetection: Boolean = true,
        val thumbnails: Boolean = true,
        val intro: AnalysisPart,
        val credits: AnalysisPart,
        val complete: Boolean = true,
    )
}
