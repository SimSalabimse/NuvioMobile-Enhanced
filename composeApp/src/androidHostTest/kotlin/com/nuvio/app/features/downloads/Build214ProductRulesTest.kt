package com.nuvio.app.features.downloads

import android.app.Application
import android.content.Context
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.analysis.compareReferenceOffCaller
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
