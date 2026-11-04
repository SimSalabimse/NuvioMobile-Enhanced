package com.nuvio.app.features.downloads

import android.app.Application
import android.content.Context
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A user download of an episode must survive analysis-temp deletion, including when it arrives
 * after the empty-slot check and before the analysis row is inserted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AnalysisTempDeletionRaceTest {
    private val main = QueuedMain()
    private val fake = FakeTransfers()

    @Before
    fun initialize() {
        Dispatchers.setMain(main)
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_downloads_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nuvio_downloads", Context.MODE_PRIVATE).edit().clear().commit()
        DownloadsSettingsStorage.initialize(context)
        DownloadsStorage.initialize(context)
        DownloadsPlatformDownloader.initialize(context)
        DownloadsSettingsRepository.clearLocalState()
        DownloadsRepository.clearLocalState()
        DownloadsRepository.transfers = fake
        resetAnalysisTempLedger()
        MetaDetailsRepository.clear()
        main.drain()
    }

    @After
    fun clearState() {
        DownloadsRepository.transfers = PlatformDownloadTransfers
        DownloadsRepository.clearLocalState()
        resetAnalysisTempLedger()
        clearFixture()
        MetaDetailsRepository.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun userDownloadOfTheSameEpisodeDuringAnalysisInsertIsKept() {
        val showId = "analysis-temp-race-show"
        val analysisUrl = "https://cdn.example/analysis-720p.mp4"
        val userUrl = "https://cdn.example/user-1080p.mp4"
        val otherUrl = "https://cdn.example/user-other-episode.mp4"
        val episode = MetaVideo(
            id = "$showId:1:1",
            title = "Pilot",
            season = 1,
            episode = 1,
            streams = listOf(stream(analysisUrl)),
        )
        val meta = MetaDetails(
            id = showId,
            type = "series",
            name = "Show",
            videos = listOf(episode),
        )
        MetaDetailsRepository.installFixture(meta)

        // A user download of a different episode is already moving. Sweep must leave it alone.
        val other = DownloadsRepository.enqueueFromStream(
            contentType = "series",
            videoId = "$showId:1:2",
            parentMetaId = showId,
            parentMetaType = "series",
            title = "Show",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 2,
            episodeTitle = "Next",
            episodeThumbnail = null,
            stream = stream(otherUrl),
            startedByUser = true,
        )
        assertEquals(DownloadEnqueueResult.Started, other)

        // Sequence: analysis has already decided S1E1 is missing and is suspended before insert
        // (stream resolve hops to the main thread). The user then downloads a different stream of
        // that same episode. The insert and the later auto-delete must not take the user's file.
        main.arm()
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        Thread {
            try {
                runBlocking(Dispatchers.Default) {
                    downloadEpisodesForAnalysis(meta, listOf(episode))
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }.start()

        assertTrue(main.awaitQueued(8_000), "analysis insert was not posted to the main thread")
        val user = DownloadsRepository.enqueueFromStream(
            contentType = "series",
            videoId = episode.id,
            parentMetaId = showId,
            parentMetaType = "series",
            title = "Show",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 1,
            episodeTitle = "Pilot",
            episodeThumbnail = null,
            stream = stream(userUrl),
            startedByUser = true,
        )
        assertEquals(
            DownloadEnqueueResult.Started,
            user,
            "user download must land before the analysis insert; got $user starts=${fake.urls}",
        )
        main.drain()
        assertTrue(done.await(8, TimeUnit.SECONDS), "analysis download did not finish")
        failure.get()?.let { throw it }

        val episodeOne = DownloadsRepository.uiState.value.items.filter { it.episodeNumber == 1 }
        assertEquals(
            listOf(userUrl),
            episodeOne.map { it.sourceUrl },
            "analysis deleted or replaced the user download; tagged=${AnalysisTempDownloads.taggedIds()} starts=${fake.urls}",
        )
        val kept = episodeOne.single()
        assertTrue(kept.startedByUser)
        assertFalse(AnalysisTempDownloads.isTagged(kept.id))

        AnalysisTempDownloads.onAnalysisSettled(kept.id)
        AnalysisTempDownloads.cancelSession(setOf(kept.id))
        AnalysisTempDownloads.sweep()

        val after = DownloadsRepository.uiState.value.items
        assertEquals(listOf(userUrl), after.filter { it.episodeNumber == 1 }.map { it.sourceUrl })
        assertEquals(listOf(otherUrl), after.filter { it.episodeNumber == 2 }.map { it.sourceUrl })
        assertFalse(after.any { AnalysisTempDownloads.isTagged(it.id) })
    }

    @Test
    fun analysisTagsTheDownloadItCreatedAndSweepLeavesTheUsers() {
        val showId = "analysis-temp-tag-show"
        val analysisUrl = "https://cdn.example/analysis-only.mp4"
        val userUrl = "https://cdn.example/user-kept.mp4"
        val episode = MetaVideo(
            id = "$showId:1:1",
            title = "Pilot",
            season = 1,
            episode = 1,
            streams = listOf(stream(analysisUrl)),
        )
        val meta = MetaDetails(id = showId, type = "series", name = "Show", videos = listOf(episode))
        MetaDetailsRepository.installFixture(meta)
        val user = DownloadsRepository.enqueueFromStream(
            contentType = "series",
            videoId = "$showId:1:2",
            parentMetaId = showId,
            parentMetaType = "series",
            title = "Show",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 2,
            episodeTitle = "Next",
            episodeThumbnail = null,
            stream = stream(userUrl),
            startedByUser = true,
        )
        assertEquals(DownloadEnqueueResult.Started, user)

        val outcome = runBlocking { downloadEpisodesForAnalysis(meta, listOf(episode)) }
        val created = DownloadsRepository.uiState.value.items.single { it.episodeNumber == 1 }
        assertEquals(analysisUrl, created.sourceUrl)
        assertFalse(created.startedByUser)
        assertTrue(AnalysisTempDownloads.isTagged(created.id))
        assertEquals(setOf(created.id), outcome.taggedIds)

        AnalysisTempDownloads.sweep()
        val after = DownloadsRepository.uiState.value.items
        assertEquals(emptyList(), after.filter { it.episodeNumber == 1 })
        assertEquals(listOf(userUrl), after.filter { it.episodeNumber == 2 }.map { it.sourceUrl })
        assertFalse(after.any { AnalysisTempDownloads.isTagged(it.id) })
    }

    private fun stream(url: String) = StreamItem(
        name = "1080p",
        url = url,
        addonName = "Test",
        addonId = "test",
    )

    private class FakeTransfers : DownloadTransfers {
        val urls = mutableListOf<String>()

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            urls += request.sourceUrl
            return object : DownloadsTaskHandle {
                override fun cancel() = Unit
            }
        }

        override fun pauseRunningTransfer(item: DownloadItem) = Unit
    }

    /** Main dispatcher that holds tasks until [drain], so a test can run work in between. */
    private class QueuedMain : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        private var arrived = CountDownLatch(1)
        private var armed = false

        fun arm() {
            arrived = CountDownLatch(1)
            armed = true
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (!armed) {
                block.run()
                return
            }
            queue.add(block)
            arrived.countDown()
        }

        fun awaitQueued(timeoutMs: Long): Boolean = arrived.await(timeoutMs, TimeUnit.MILLISECONDS)

        fun drain() {
            armed = false
            while (true) {
                val block = queue.poll() ?: return
                block.run()
            }
        }
    }
}

private fun resetAnalysisTempLedger() {
    val clazz = AnalysisTempDownloads::class.java
    val instance = clazz.getField("INSTANCE").get(null)
    val cached = clazz.getDeclaredField("cached")
    cached.isAccessible = true
    cached.set(instance, null)
}

private fun clearFixture() {
    val clazz = MetaDetailsRepository::class.java
    val instance = clazz.getField("INSTANCE").get(null)
    val field = clazz.getDeclaredField("fixtureMetas")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val map = field.get(instance) as MutableMap<Any, Any>
    map.clear()
}
