package com.nuvio.app.features.downloads

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Analysis follows the picture, and a quiet player does not keep the playback hold. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaybackPresenceTest {
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
        PlaybackPriority.clearStaleSession()
    }

    @After
    fun clear() {
        PlaybackPriority.clearStaleSession()
    }

    @Test
    fun thePictureFlagClearsWhenPlaybackStops() {
        play(nowMs = 5_000L)
        assertTrue(PlaybackPriority.analysisPictureMoving(5_100L))
        PlaybackPriority.noteAppBackground(inPictureInPicture = false)
        assertFalse(PlaybackPriority.analysisPictureMoving(5_200L))

        play(nowMs = 6_000L)
        PlaybackPriority.noteSnapshot(
            wantsToPlay = false,
            buffering = false,
            positionMs = 2_000L,
            nowMs = 6_100L,
            pictureMoving = false,
            ended = true,
        )
        assertFalse(PlaybackPriority.analysisPictureMoving(6_200L))

        play(nowMs = 7_000L)
        PlaybackPriority.notePlaybackError(7_000L)
        assertFalse(PlaybackPriority.analysisPictureMoving(7_100L))

        play(nowMs = 8_000L)
        PlaybackPriority.sessionEnded()
        assertFalse(PlaybackPriority.analysisPictureMoving(8_100L))
    }

    @Test
    fun aStaleFlagDoesNotKeepThePlaybackHold() {
        play(nowMs = 9_000L)
        assertTrue(PlaybackPriority.analysisPictureMoving(9_100L))
        assertFalse(PlaybackPriority.analysisPictureMoving(9_000L + 4_000L))
        PlaybackPriority.dropStalePlaybackForManualStart()
        assertFalse(PlaybackPriority.analysisPictureMoving(9_000L + 4_000L))
    }

    private fun play(nowMs: Long) {
        PlaybackPriority.sessionStarted(isLocalFile = false)
        PlaybackPriority.noteSnapshot(
            wantsToPlay = true,
            buffering = false,
            positionMs = 1_000L,
            nowMs = nowMs,
            pictureMoving = true,
        )
    }
}
