package com.nuvio.app.features.downloads

import android.app.Application
import android.content.Context
import com.nuvio.app.features.player.PersistedPlayerTrackPreference
import com.nuvio.app.features.player.PlayerTrackPreferenceStorage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SeriesSmartDownloadCountStorageTest {
    @Before
    fun clearPreferences() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_player_track_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        PlayerTrackPreferenceStorage.initialize(context)
    }

    @Test
    fun overrideRoundTripsPerSeriesAndSurvivesReload() {
        SeriesSmartDownloadCounts.set("tt-alpha", SeriesSmartDownloadCount.Two)
        SeriesSmartDownloadCounts.set("tt-beta", SeriesSmartDownloadCount.Zero)
        SeriesSmartDownloadCounts.set("tt-gamma", SeriesSmartDownloadCount.Five)
        SeriesSmartDownloadCounts.set("tt-delta", SeriesSmartDownloadCount.Default)

        assertEquals(2, SeriesSmartDownloadCounts.storedCount("tt-alpha"))
        assertEquals(0, SeriesSmartDownloadCounts.storedCount("tt-beta"))
        assertEquals(5, SeriesSmartDownloadCounts.storedCount("tt-gamma"))
        assertNull(SeriesSmartDownloadCounts.storedCount("tt-delta"))
        assertNull(SeriesSmartDownloadCounts.storedCount("tt-missing"))

        PlayerTrackPreferenceStorage.initialize(RuntimeEnvironment.getApplication())

        assertEquals(SeriesSmartDownloadCount.Two, SeriesSmartDownloadCounts.selection("tt-alpha"))
        assertEquals(SeriesSmartDownloadCount.Zero, SeriesSmartDownloadCounts.selection("tt-beta"))
        assertEquals(SeriesSmartDownloadCount.Five, SeriesSmartDownloadCounts.selection("tt-gamma"))
        assertEquals(SeriesSmartDownloadCount.Default, SeriesSmartDownloadCounts.selection("tt-delta"))
        assertEquals(2, effectiveSmartDownloadCount(SeriesSmartDownloadCounts.storedCount("tt-alpha"), globalCount = 1))
        assertEquals(0, effectiveSmartDownloadCount(SeriesSmartDownloadCounts.storedCount("tt-beta"), globalCount = 3))
        assertEquals(1, effectiveSmartDownloadCount(SeriesSmartDownloadCounts.storedCount("tt-delta"), globalCount = 1))

        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("nuvio_player_track_preferences", Context.MODE_PRIVATE)
        val stored = prefs.all.values.map { it.toString() }
        assertTrue(stored.contains("2"))
        assertTrue(stored.contains("0"))
        assertTrue(stored.contains("5"))
        assertTrue(stored.all { it.length <= 5 })
    }

    @Test
    fun defaultClearsOnlyTheCountAndKeepsOtherShowPreferences() {
        PlayerTrackPreferenceStorage.save(
            "tt-show",
            PersistedPlayerTrackPreference(audioLanguage = "eng", playbackSpeed = "1.25", smartDownloadCount = "3"),
        )
        assertEquals(3, SeriesSmartDownloadCounts.storedCount("tt-show"))

        SeriesSmartDownloadCounts.set("tt-show", SeriesSmartDownloadCount.Default)

        val loaded = PlayerTrackPreferenceStorage.load("tt-show")
        assertEquals("eng", loaded?.audioLanguage)
        assertEquals("1.25", loaded?.playbackSpeed)
        assertNull(loaded?.smartDownloadCount)
        assertNull(SeriesSmartDownloadCounts.storedCount("tt-show"))
        assertEquals(1, effectiveSmartDownloadCount(SeriesSmartDownloadCounts.storedCount("tt-show"), globalCount = 1))
    }
}
