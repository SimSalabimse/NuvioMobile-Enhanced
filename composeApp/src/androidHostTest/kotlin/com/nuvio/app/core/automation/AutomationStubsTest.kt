package com.nuvio.app.core.automation

import android.app.Application
import android.content.Context
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsSettingsRepository
import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.downloads.SeasonEnqueueRow
import com.nuvio.app.features.downloads.SeasonStreamCatalog
import com.nuvio.app.features.downloads.enqueueSeasonDownloads
import com.nuvio.app.features.streams.StreamItem
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutomationStubsTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_downloads_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nuvio_downloads", Context.MODE_PRIVATE).edit().clear().commit()
        DownloadsSettingsStorage.initialize(context)
        DownloadsStorage.initialize(context)
        DownloadsPlatformDownloader.initialize(context)
        DownloadsSettingsRepository.clearLocalState()
        DownloadsRepository.clearLocalState()
        AutomationTestMode.resetForTests()
        AutomationStubs.resetCounts()
    }

    @After
    fun tearDown() {
        AutomationTestMode.resetForTests()
        AutomationStubs.resetCounts()
        DownloadsRepository.clearLocalState()
        Dispatchers.resetMain()
    }

    @Test
    fun flagOffUsesTheRealClients() {
        AutomationTestMode.installForTests(active = false)
        val asset = File("src/commonMain/composeResources/files/automation/color-bars.mp4")
        assertTrue(asset.isFile, asset.absolutePath)
        assertTrue(asset.readBytes().contentEquals(AUTOMATION_CLIP_BYTES))

        val streams = runBlocking {
            SeasonStreamCatalog.load(type = "series", videoId = "tt0000001", season = 1, episode = 1)
        }
        assertTrue(streams.none { it.addonId == AUTOMATION_ADDON_ID })
        assertEquals(1, AutomationStubs.realClientCalls)
        assertEquals(0, AutomationStubs.stubClientCalls)

        val plain = StreamItem(
            name = "Real",
            url = "https://cdn.example/real.mp4",
            addonName = "Example",
            addonId = "addon:example",
        )
        val resolved = runBlocking {
            DirectDebridPlaybackResolver.resolveToPlayableStream(plain, season = null, episode = null)
        }
        val success = resolved as DirectDebridPlayableResult.Success
        assertEquals("https://cdn.example/real.mp4", success.stream.url)
        assertEquals(0, AutomationStubs.stubResolveCalls)
        assertEquals(0, AutomationStubs.realResolveCalls)
    }

    @Test
    fun flagOnCompletesASeasonDownloadFromStubs() {
        AutomationTestMode.installForTests(active = true)
        AutomationStubs.resetCounts()
        val series = AutomationStubs.series()
        val episode = series.videos.first { it.season == 1 && it.episode == 1 }
        val result = runBlocking {
            val streams = SeasonStreamCatalog.load(
                type = series.type,
                videoId = episode.id,
                season = episode.season,
                episode = episode.episode,
            )
            assertEquals(listOf("Automation 1080p", "Automation 2160p"), streams.map { it.name })
            assertEquals(listOf(1_200_000_000L, 3_400_000_000L), streams.map { it.behaviorHints.videoSize })
            enqueueSeasonDownloads(
                meta = series,
                rows = listOf(SeasonEnqueueRow(video = episode, stream = streams.first())),
            )
        }
        assertEquals(1, result.queued)
        assertNull(result.failureMessage)
        val item = DownloadsRepository.uiState.value.items.single()
        assertEquals(DownloadStatus.Completed, item.status)
        assertEquals(AUTOMATION_CLIP_BYTES.size.toLong(), item.downloadedBytes)
        val file = File(URI(checkNotNull(item.localFileUri)))
        assertTrue(file.isFile)
        assertTrue(file.readBytes().contentEquals(AUTOMATION_CLIP_BYTES))
        assertTrue(AutomationStubs.stubClientCalls >= 1)
        assertTrue(AutomationStubs.stubResolveCalls >= 1)
        assertEquals(0, AutomationStubs.realClientCalls)
        assertEquals(0, AutomationStubs.realResolveCalls)
        assertEquals(0, AutomationStubs.blockedNetworkCalls)
        assertEquals(3, series.videos.count { it.season == 1 })
        assertEquals(9, series.videos.size)
        assertEquals(AUTOMATION_PROFILE_NAME, "Test")
        assertEquals(AUTOMATION_MOVIE_NAME, AutomationStubs.movie().name)
    }
}
