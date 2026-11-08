package com.nuvio.app.core.uifixture

import android.app.Application
import android.content.Context
import com.nuvio.app.features.analysis.MediaAnalysisFiles
import com.nuvio.app.features.analysis.MediaAnalysisScheduler
import com.nuvio.app.features.analysis.MediaAnalysisStatus
import com.nuvio.app.features.analysis.MediaAnalysisStore
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsSettingsRepository
import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.downloads.downloadItem
import com.nuvio.app.navigation.DownloadShowRoute
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The layout clip is a real file on the download the private player route already looks up.
 * A fixture://file address is not a file. Analysis does not run for these two fixtures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LayoutClipPlaybackTest {
    @Before
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_downloads_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nuvio_downloads", Context.MODE_PRIVATE).edit().clear().commit()
        DownloadsSettingsStorage.initialize(context)
        DownloadsStorage.initialize(context)
        DownloadsPlatformDownloader.initialize(context)
        DownloadsSettingsRepository.clearLocalState()
        DownloadsRepository.clearLocalState()
        MediaAnalysisFiles.retainForTests = true
        MediaAnalysisFiles.clearForTests()
        MediaAnalysisStore.clearCacheForTests()
        MediaAnalysisScheduler.resetForTest()
        MediaAnalysisScheduler.supportedOverride = false
        UiFixtureSession.testFixture = null
    }

    @After
    fun clear() {
        UiFixtureSession.testFixture = null
        MediaAnalysisScheduler.resetForTest()
        DownloadsRepository.clearLocalState()
    }

    @Test
    fun theClipResolvesForTheSelectedEpisodeAndNotForAFixtureAddress() {
        val fileUrl = UiFixtureCatalog.layoutClipFileUrl()
        val episode = UiFixtureCatalog.playableDownloadsEpisode(fileUrl)
        val home = UiFixtureCatalog.playableHomeDownload(fileUrl)
        assertNotNull(DownloadsPlatformDownloader.resolveLocalFileUri(episode.localFileUri, episode.fileName))
        assertNotNull(DownloadsPlatformDownloader.resolveLocalFileUri(home.localFileUri, home.fileName))
        assertNull(
            DownloadsPlatformDownloader.resolveLocalFileUri(
                "fixture://file/fixture-dl-railway-1",
                "fixture-dl-railway-1.mp4",
            ),
        )
        assertEquals("${UiFixtureCatalog.DOWNLOADS_SEASON_IMDB}:1:1", episode.videoId)
        assertEquals(1, episode.episodeNumber)
        assertEquals(UiFixtureCatalog.HOME_PLAY_VIDEO_ID, home.videoId)
        assertEquals(3, home.episodeNumber)
        assertEquals(UiFixtureCatalog.SERIES_ID, home.parentMetaId)
        DownloadsRepository.replaceItems(
            listOf(
                episode,
                home,
                downloadItem(url = "fixture://media/e2", id = "fixture-dl-railway-2").copy(
                    contentType = "series",
                    parentMetaId = UiFixtureCatalog.DOWNLOADS_SEASON_SHOW_ID,
                    parentMetaType = "series",
                    videoId = "${UiFixtureCatalog.DOWNLOADS_SEASON_IMDB}:1:2",
                    seasonNumber = 1,
                    episodeNumber = 2,
                    status = DownloadStatus.Completed,
                    localFileUri = "fixture://file/fixture-dl-railway-2",
                    fileName = "fixture-dl-railway-2.mp4",
                ),
            ),
        )
        val selected = DownloadsRepository.findPlayableDownload(
            parentMetaId = UiFixtureCatalog.DOWNLOADS_SEASON_SHOW_ID,
            seasonNumber = 1,
            episodeNumber = 1,
            videoId = episode.videoId,
        )
        assertEquals(episode.id, selected?.id)
        assertNull(
            DownloadsRepository.findPlayableDownload(
                parentMetaId = UiFixtureCatalog.DOWNLOADS_SEASON_SHOW_ID,
                seasonNumber = 1,
                episodeNumber = 2,
                videoId = "${UiFixtureCatalog.DOWNLOADS_SEASON_IMDB}:1:2",
            ),
        )
        val homeMatch = DownloadsRepository.findPlayableDownload(
            parentMetaId = home.parentMetaId,
            seasonNumber = home.seasonNumber,
            episodeNumber = home.episodeNumber,
            videoId = home.videoId,
        )
        assertEquals(home.id, homeMatch?.id)
        assertTrue(UiFixture.DownloadsPlay.launchRoute() is DownloadShowRoute)
        assertNull(UiFixture.HomePlay.launchRoute())
    }

    @Test
    fun aBundledClipFixtureDoesNotStartAnalysis() {
        val episode = UiFixtureCatalog.playableDownloadsEpisode(UiFixtureCatalog.layoutClipFileUrl())
        val revision = MediaAnalysisStatus.revision.value
        UiFixtureSession.testFixture = UiFixture.DownloadsPlay
        MediaAnalysisScheduler.reanalyse(listOf(episode))
        assertEquals(revision, MediaAnalysisStatus.revision.value)
        UiFixtureSession.testFixture = null
        MediaAnalysisScheduler.reanalyse(listOf(episode))
        assertTrue(MediaAnalysisStatus.revision.value > revision)
    }
}
