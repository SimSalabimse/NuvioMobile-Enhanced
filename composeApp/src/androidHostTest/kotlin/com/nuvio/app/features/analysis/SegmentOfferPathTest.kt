package com.nuvio.app.features.analysis

import android.app.Application
import android.content.Context
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import com.nuvio.app.features.player.onDeviceSkipIntervalsFrom
import com.nuvio.app.features.player.segmentTypesSyncedByAnalysis
import kotlin.test.assertEquals
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
 * Store, downloads source row, recap chip, and the skip intervals offered after an analysis.
 * The fingerprints that produced these times are covered by [SyntheticSegmentSignalsTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SegmentOfferPathTest {
    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_downloads_settings", Context.MODE_PRIVATE).edit().clear().commit()
        DownloadsSettingsStorage.initialize(context)
        MediaAnalysisFiles.retainForTests = true
        MediaAnalysisFiles.clearForTests()
        MediaAnalysisStore.clearCacheForTests()
        MediaAnalysisStatus.supportedForTests = true
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.IntroDetection, true)
        MediaAnalysisSettings.set(MediaAnalysisSettings.Flag.RecapDetection, true)
    }

    @After
    fun tearDown() {
        MediaAnalysisStatus.supportedForTests = null
        MediaAnalysisFiles.clearForTests()
        MediaAnalysisFiles.retainForTests = false
        MediaAnalysisStore.clearCacheForTests()
    }

    @Test
    fun aSyntheticRecapAndPreviewRoundTripAndAreOffered() {
        val item = episodeItem()
        val key = MediaAnalysisStore.keyFor(item)
        MediaAnalysisStore.save(
            MediaAnalysisResult(
                key = key,
                analyzedAtMs = 2L,
                durationMs = 2_400_000L,
                fileName = item.fileName,
                derivedVersion = MEDIA_ANALYSIS_DERIVED_VERSION,
                energy = "AA",
                integratedLufs = -20.0,
                headFingerprint = "fp",
                introStartMs = 25_000L,
                introEndMs = 65_000L,
                recapStartMs = 0L,
                recapEndMs = 25_000L,
                creditsStartMs = 2_200_000L,
                creditsEndMs = 2_400_000L,
                previewStartMs = 2_350_000L,
                previewEndMs = 2_370_000L,
            ),
        )
        MediaAnalysisStore.clearCacheForTests()
        val stored = MediaAnalysisStore.load(key)
        assertEquals(0L, stored?.recapStartMs)
        assertEquals(25_000L, stored?.recapEndMs)
        assertEquals(2_350_000L, stored?.previewStartMs)
        assertEquals(2_370_000L, stored?.previewEndMs)

        val sources = skipSourcesFor(item).toMap()
        assertEquals("Detected", sources["Recap"])
        assertEquals("Detected", sources["Preview"])

        val status = assertIs<ItemAnalysisStatus.Done>(MediaAnalysisStatus.statusFor(item))
        assertEquals(AnalysisPart.Ready, status.parts.recap)
        assertTrue(AnalysisChipKind.Recap in analysisChipKinds(item, status.parts))

        val offered = onDeviceSkipIntervalsFrom(stored!!, recapDetection = true, introDetection = true)
        assertEquals(0.0, offered.first { it.type == "recap" }.startTime)
        assertEquals(25.0, offered.first { it.type == "recap" }.endTime)
        assertEquals(2_350.0, offered.first { it.type == "preview" }.startTime)
        assertEquals(2_370.0, offered.first { it.type == "preview" }.endTime)
        assertEquals(setOf("intro", "recap", "outro", "preview"), segmentTypesSyncedByAnalysis(stored))
    }

    private fun episodeItem() = DownloadItem(
        id = "offer-path",
        contentType = "series",
        parentMetaId = "offer-path",
        parentMetaType = "series",
        videoId = "offer-path",
        title = "Offer path",
        streamTitle = "Offer path",
        providerName = "Test",
        sourceUrl = "https://example.com/offer.mkv",
        fileName = "offer.mkv",
        status = DownloadStatus.Completed,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
        seasonNumber = 1,
        episodeNumber = 2,
    )
}
