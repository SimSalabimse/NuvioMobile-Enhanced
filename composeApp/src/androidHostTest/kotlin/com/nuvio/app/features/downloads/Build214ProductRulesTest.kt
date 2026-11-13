package com.nuvio.app.features.downloads

import android.app.Application
import android.content.Context
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.analysis.compareReferenceOffCaller
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Build214ProductRulesTest {
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
        MetaDetailsRepository.clear()
        SmartDownloads.onWifiForTest = null
        NuvioToastController.dismiss()
    }

    @Test
    fun comparisonRunsOffTheCallerThread() = runBlocking {
        val caller = Thread.currentThread().name
        val worker = compareReferenceOffCaller { Thread.currentThread().name }
        assertTrue(worker.contains("DefaultDispatcher"), worker)
        assertNotEquals(caller, worker)
    }

    @Test
    fun changingTheCountWithNothingToQueueSaysZero() {
        SmartDownloadSettings.installFixture(enabled = true, count = 1, goalKey = null)
        NuvioToastController.dismiss()
        SmartDownloadSettings.changeCount(2)
        assertEquals("Queued 0 episodes", awaitToast())
        assertEquals(2, SmartDownloadSettings.snapshot().count)
    }

    @Test
    fun theStorageSetterDoesNotAnnounceAQueue() {
        SmartDownloadSettings.installFixture(enabled = true, count = 1, goalKey = null)
        NuvioToastController.dismiss()
        SmartDownloadSettings.setCount(3)
        Thread.sleep(400)
        assertEquals(null, NuvioToastController.currentToast.value?.message)
    }

    @Test
    fun aWifiGateDoesNotQueueTheNextEpisodes() {
        val showId = "wifi-gate-show"
        installShow(showId, episodes = 3)
        DownloadsRepository.installFixtureItems(listOf(completedEpisode(showId, 1)))
        SmartDownloadSettings.installFixture(enabled = true, count = 1, goalKey = null)
        SmartDownloadSettings.setOnlyOnWifi(true)
        SmartDownloads.onWifiForTest = false
        NuvioToastController.dismiss()
        SmartDownloadSettings.changeCount(2)
        assertEquals("Queued 0 episodes", awaitToast())
        val deadline = System.currentTimeMillis() + 3_000
        var shows = SmartDownloads.pendingShowIdsForTest()
        while (System.currentTimeMillis() < deadline && shows.isEmpty()) {
            Thread.sleep(50)
            shows = SmartDownloads.pendingShowIdsForTest()
        }
        assertEquals(emptyList(), shows, "a blocked Wi-Fi gate must not leave a pending request")
        assertEquals(listOf(1), DownloadsRepository.uiState.value.items.map { it.episodeNumber })
    }

    @Test
    fun theQueueToastMatchesEpisodesThatActuallyStart() {
        val showId = "toast-count-show"
        installShow(showId, episodes = 3)
        DownloadsRepository.installFixtureItems(listOf(completedEpisode(showId, 1)))
        SmartDownloadSettings.installFixture(enabled = true, count = 1, goalKey = null)
        SmartDownloads.onWifiForTest = true
        NuvioToastController.dismiss()
        SmartDownloadSettings.changeCount(2)
        val toast = awaitToast()
        Thread.sleep(1_500)
        val started = DownloadsRepository.uiState.value.items.count { it.episodeNumber != 1 }
        assertEquals(started, queuedCount(toast), "toast=$toast started=$started")
    }

    @Test
    fun liftingWifiRetriesAPendingRequestWhenNothingIsDownloaded() {
        val showId = "lift-wifi-show"
        installShow(showId, episodes = 1)
        SmartDownloadSettings.installFixture(enabled = true, count = 1, goalKey = null)
        SmartDownloadSettings.setOnlyOnWifi(true)
        SmartDownloads.installPendingForTest(showId, season = 1, episode = 1)
        SmartDownloads.onWifiForTest = true
        NuvioToastController.dismiss()
        SmartDownloadSettings.changeOnlyOnWifi(false)
        val deadline = System.currentTimeMillis() + 8_000
        var shows = SmartDownloads.pendingShowIdsForTest()
        while (System.currentTimeMillis() < deadline && shows.isNotEmpty()) {
            Thread.sleep(50)
            shows = SmartDownloads.pendingShowIdsForTest()
        }
        assertEquals(emptyList(), shows, "lifting Wi-Fi-only should run the pending request")
    }

    private fun installShow(id: String, episodes: Int) {
        MetaDetailsRepository.installFixture(
            MetaDetails(
                id = id,
                type = "series",
                name = "Show",
                videos = (1..episodes).map { episode ->
                    MetaVideo(
                        id = "$id:1:$episode",
                        title = "Episode $episode",
                        season = 1,
                        episode = episode,
                        released = "2020-01-15",
                    )
                },
            ),
        )
    }

    private fun completedEpisode(showId: String, episode: Int) = DownloadItem(
        id = "$showId-$episode",
        contentType = "series",
        parentMetaId = showId,
        parentMetaType = "series",
        videoId = "$showId:1:$episode",
        title = "Show",
        seasonNumber = 1,
        episodeNumber = episode,
        streamTitle = "1080p",
        providerName = "test",
        sourceUrl = "https://cdn.example/$showId-$episode.mp4",
        fileName = "$showId-$episode.mp4",
        status = DownloadStatus.Completed,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )

    private fun queuedCount(message: String): Int =
        Regex("Queued (\\d+) episode").find(message)?.groupValues?.get(1)?.toInt()
            ?: error("not a queue toast: $message")

    private fun awaitToast(): String {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val message = NuvioToastController.currentToast.value?.message
            if (message != null) return message
            Thread.sleep(50)
        }
        error("no toast")
    }
}
