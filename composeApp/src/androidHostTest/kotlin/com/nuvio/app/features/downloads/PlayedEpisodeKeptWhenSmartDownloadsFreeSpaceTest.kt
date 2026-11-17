package com.nuvio.app.features.downloads

import android.app.Application
import android.content.Context
import android.os.Looper
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.WatchedStorage
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plays a downloaded episode to the end through the real watch-progress upsert
 * (the same path the player uses) and then runs storage autopilot, which is what
 * Smart Downloads calls when the next episode does not fit. Free space is made
 * short by setting Keep free space above the real volume, not by stubbing the
 * delete-when-finished gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayedEpisodeKeptWhenSmartDownloadsFreeSpaceTest {
    private lateinit var directory: File
    private var freeBytes: Long = 0L

    @Before
    fun initialize() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_downloads_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nuvio_downloads", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nuvio_watched", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nuvio_watch_progress", Context.MODE_PRIVATE).edit().clear().commit()
        DownloadsSettingsStorage.initialize(context)
        DownloadsStorage.initialize(context)
        WatchedStorage.initialize(context)
        WatchProgressStorage.initialize(context)
        DownloadsPlatformDownloader.initialize(context)
        DownloadsSettingsRepository.clearLocalState()
        DownloadsRepository.clearLocalState()
        WatchedRepository.clearLocalState()
        WatchProgressRepository.clearLocalState()
        FinishedDownloadDeletion.reset()
        FinishedDownloadDeletion.closeGraceMs = 0L
        DownloadsSettingsStorage.saveString("smart_downloads_pending", null)
        SmartDownloadSettings.setEnabled(false)
        SmartDownloadSettings.setOnlyOnWifi(false)
        SmartDownloadSettings.setKeepFreeGb(0)
        InAppLogger.clear()
        directory = File(context.filesDir, "downloads").apply { mkdirs() }
        // Robolectric's StatFs is empty until registered. This is the figure DownloadDiskInfo reads.
        val availableBlocks = 2_500_000
        ShadowStatFs.registerStats(directory, 16_000_000, availableBlocks, availableBlocks)
        ShadowStatFs.registerStats(context.filesDir, 16_000_000, availableBlocks, availableBlocks)
        freeBytes = DownloadDiskInfo.volumeSpace()?.availableBytes ?: error("volume space unavailable")
        assertTrue(freeBytes > 2_000_000_000L, "need more than 2 GB free to separate ok from short, was $freeBytes")
    }

    @After
    fun clearState() {
        FinishedDownloadDeletion.reset()
        FinishedDownloadDeletion.closeGraceMs = 5_000L
        DownloadsRepository.clearLocalState()
        DownloadsSettingsRepository.clearLocalState()
        SmartDownloadSettings.setEnabled(false)
        SmartDownloadSettings.setOnlyOnWifi(true)
        SmartDownloadSettings.setKeepFreeGb(0)
        DownloadsSettingsStorage.saveString("smart_downloads_pending", null)
        WatchedRepository.clearLocalState()
        WatchProgressRepository.clearLocalState()
        ShadowStatFs.reset()
        Dispatchers.resetMain()
    }

    @Test
    fun playingToTheEndWithSmartDownloadsOnAndShortSpaceKeepsTheFileWhenDeleteWhenFinishedIsOff() {
        val played = seedEpisode("just-played")
        DownloadsRepository.replaceItems(listOf(played.item))
        DownloadsSettingsRepository.setDeleteWhenFinished(false)
        SmartDownloadSettings.setEnabled(true)
        assertTrue(SmartDownloads.isEnabled())
        assertFalse(DownloadsSettingsRepository.isDeleteWhenFinishedEnabled())
        armSpace(short = true)
        settle()

        playToTheEnd(played.item)
        assertTrue(
            WatchedRepository.isWatched(played.item.parentMetaId, "series", 1, 1),
            "playing to the end did not mark the episode watched",
        )
        // SmartDownloads.noSpace() calls this once the next episode cannot fit.
        StorageAutopilot.run()

        assertTrue(
            awaitStillThere(played),
            "just-played episode was removed with delete-when-finished off and smart downloads on",
        )
        assertTrue(removalLogs().none { it.contains(played.item.id) })
    }

    @Test
    fun playingToTheEndWithSmartDownloadsOnAndShortSpaceDeletesTheFileWhenDeleteWhenFinishedIsOn() {
        val played = seedEpisode("toggle-on")
        DownloadsRepository.replaceItems(listOf(played.item))
        DownloadsSettingsRepository.setDeleteWhenFinished(true)
        SmartDownloadSettings.setEnabled(true)
        armSpace(short = true)
        settle()

        playToTheEnd(played.item)
        StorageAutopilot.run()
        settle()

        assertFalse(played.file.exists(), "file should be deleted when delete-when-finished is on")
        assertTrue(DownloadsRepository.uiState.value.items.none { it.id == played.item.id })
        val logs = removalLogs()
        assertTrue(logs.any { it.contains(played.item.id) && it.contains("reason=finished-toggle") }, logs.joinToString())
        assertTrue(logs.none { it.contains(played.item.id) && it.contains("reason=autopilot") }, logs.joinToString())
    }

    @Test
    fun shortSpaceRemovesOlderWatchedDownloadsAndNotTheEpisodeJustPlayed() {
        val older = seedEpisode("older-watched", updatedAt = 1L)
        val played = seedEpisode("just-played-with-older", updatedAt = 50L)
        val transferring = seedEpisode("still-transferring", updatedAt = 0L, status = DownloadStatus.Downloading)
        DownloadsRepository.replaceItems(listOf(older.item, played.item, transferring.item))
        DownloadsSettingsRepository.setDeleteWhenFinished(false)
        SmartDownloadSettings.setEnabled(true)
        // Arm while nothing is watched, so this pass deletes nothing. The pass after playback is the one under test.
        armSpace(short = true)
        settle()
        markWatched(older.item)
        markWatched(transferring.item)
        assertTrue(stillThere(older), "older watched episode was removed before playback")
        assertTrue(stillThere(transferring))

        val toastBefore = NuvioToastController.currentToast.value?.id
        playToTheEnd(played.item)
        assertTrue(WatchedRepository.isWatched(played.item.parentMetaId, "series", 1, 1))
        StorageAutopilot.run()
        val olderGone = awaitGone(older)
        settle()

        assertTrue(olderGone, "older watched episode should be removed to keep free space")
        assertFalse(older.file.exists())
        assertTrue(DownloadsRepository.uiState.value.items.none { it.id == older.item.id })
        assertTrue(stillThere(played), "just-played episode was removed with the older watched ones")
        assertTrue(stillThere(transferring), "a transfer in progress was removed")

        val toast = NuvioToastController.currentToast.value
        assertTrue(toast != null && toast.id != toastBefore, "removal was silent")
        assertTrue(toast.message.contains("Removed 1"), toast.message)
        assertTrue(toast.message.contains("to keep free space"), toast.message)
        val logs = removalLogs()
        assertTrue(logs.any { it.contains(older.item.id) && it.contains("reason=autopilot") }, logs.joinToString())
        assertTrue(logs.none { it.contains(played.item.id) }, logs.joinToString())
        assertTrue(logs.none { it.contains(transferring.item.id) }, logs.joinToString())
        assertTrue(InAppLogger.entries.value.any { entry -> entry.tag == "Downloads" && entry.message.contains("reason=autopilot") })
    }

    @Test
    fun deletionFollowsToggleSmartDownloadsSpaceAndEpisodeKind() {
        val failures = mutableListOf<String>()
        for (toggle in listOf(false, true)) {
            for (smart in listOf(false, true)) {
                for (spaceShort in listOf(false, true)) {
                    for (kind in EpisodeKind.entries) {
                        val removed = exercise(toggle, smart, spaceShort, kind)
                        val expected = expectRemoved(toggle, spaceShort, kind)
                        if (removed != expected) {
                            failures += "toggle=$toggle smart=$smart spaceShort=$spaceShort kind=$kind removed=$removed expected=$expected"
                        }
                    }
                }
            }
        }
        assertEquals(emptyList(), failures)
    }

    private fun exercise(toggle: Boolean, smart: Boolean, spaceShort: Boolean, kind: EpisodeKind): Boolean {
        val id = "m-$toggle-$smart-$spaceShort-$kind-${System.nanoTime()}"
        FinishedDownloadDeletion.reset()
        DownloadsSettingsStorage.saveString("smart_downloads_pending", null)
        InAppLogger.clear()
        DownloadsSettingsRepository.setDeleteWhenFinished(toggle)
        SmartDownloadSettings.setEnabled(smart)
        SmartDownloadSettings.setOnlyOnWifi(false)
        val seeded = seedEpisode(id, updatedAt = 20L)
        DownloadsRepository.replaceItems(listOf(seeded.item))
        armSpace(spaceShort)
        settle()
        if (kind == EpisodeKind.OlderWatched) markWatched(seeded.item)
        if (kind == EpisodeKind.JustPlayed) playToTheEnd(seeded.item)
        StorageAutopilot.run()
        val expectDelete = expectRemoved(toggle, spaceShort, kind)
        return if (expectDelete) awaitGone(seeded, timeoutMs = 2_000) else !awaitStillThere(seeded, timeoutMs = 700)
    }

    /**
     * Just played: only "delete when finished". Older watched: only when free space is short
     * of the keep-free setting. Unwatched: never. Smart Downloads does not change this.
     */
    private fun expectRemoved(toggle: Boolean, spaceShort: Boolean, kind: EpisodeKind): Boolean = when (kind) {
        EpisodeKind.JustPlayed -> toggle
        EpisodeKind.OlderWatched -> spaceShort
        EpisodeKind.Unwatched -> false
    }

    private fun armSpace(short: Boolean) {
        val keepGb = if (short) (freeBytes / 1_000_000_000L).toInt() + 2 else 1
        if (short) {
            assertTrue(keepGb * 1_000_000_000L > freeBytes, "keep ${keepGb}GB does not exceed free $freeBytes")
        }
        SmartDownloadSettings.setKeepFreeGb(keepGb)
    }

    private fun playToTheEnd(stored: DownloadItem) {
        val sourceUrl = checkNotNull(stored.localFileUri)
        val durationMs = 3_600_000L
        FinishedDownloadDeletion.playbackOpened(stored.videoId, sourceUrl)
        val session = WatchProgressPlaybackSession(
            profileId = ProfileRepository.activeProfileId,
            contentType = "series",
            parentMetaId = stored.parentMetaId,
            parentMetaType = "series",
            videoId = stored.videoId,
            title = stored.title,
            seasonNumber = stored.seasonNumber,
            episodeNumber = stored.episodeNumber,
            episodeTitle = stored.episodeTitle,
            lastSourceUrl = sourceUrl,
        )
        val ended = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = false,
            isEnded = true,
            positionMs = durationMs,
            durationMs = durationMs,
        )
        WatchProgressRepository.upsertPlaybackProgress(session, ended, syncRemote = false)
        WatchProgressRepository.flushPlaybackProgress(session, ended, syncRemote = false)
        FinishedDownloadDeletion.playbackEnded(stored.videoId)
        FinishedDownloadDeletion.playbackOpened("${stored.videoId}:next", "https://example.com/next.mkv")
        FinishedDownloadDeletion.playbackPlaying("${stored.videoId}:next")
    }

    private fun markWatched(item: DownloadItem) {
        WatchedRepository.markWatched(
            WatchedItem(
                id = item.parentMetaId,
                type = "series",
                name = item.title,
                season = item.seasonNumber,
                episode = item.episodeNumber,
                markedAtEpochMs = 1L,
            ),
        )
    }

    private fun seedEpisode(
        id: String,
        updatedAt: Long = 10L,
        status: DownloadStatus = DownloadStatus.Completed,
    ): SeededEpisode {
        val file = File(directory, "$id.mkv").apply { writeText("video-$id") }
        val item = downloadItem(id = id).copy(
            contentType = "series",
            parentMetaId = id,
            parentMetaType = "series",
            videoId = "$id:1:1",
            title = "Show",
            seasonNumber = 1,
            episodeNumber = 1,
            episodeTitle = "Pilot",
            status = status,
            localFileUri = file.toURI().toString(),
            fileName = file.name,
            downloadedBytes = 1024L,
            totalBytes = 1024L,
            earlyPlayReady = status == DownloadStatus.Downloading,
            createdAtEpochMs = updatedAt,
            updatedAtEpochMs = updatedAt,
        )
        return SeededEpisode(item, file)
    }

    private fun stillThere(seeded: SeededEpisode): Boolean =
        seeded.file.isFile && DownloadsRepository.uiState.value.items.any { it.id == seeded.item.id }

    private fun awaitGone(seeded: SeededEpisode, timeoutMs: Long = 2_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pump()
            if (!stillThere(seeded)) return true
            Thread.sleep(20)
        }
        pump()
        return !stillThere(seeded)
    }

    /** Waits long enough for autopilot to have run, then reports that the episode is still stored. */
    private fun awaitStillThere(seeded: SeededEpisode, timeoutMs: Long = 1_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pump()
            if (!stillThere(seeded)) return false
            Thread.sleep(20)
        }
        pump()
        return stillThere(seeded)
    }

    private fun settle() {
        val deadline = System.currentTimeMillis() + 250
        while (System.currentTimeMillis() < deadline) {
            pump()
            Thread.sleep(20)
        }
    }

    private fun pump() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun removalLogs(): List<String> =
        InAppLogger.entries.value.map { it.message }.filter { it.contains("reason=") }
}

private enum class EpisodeKind { JustPlayed, OlderWatched, Unwatched }

private data class SeededEpisode(val item: DownloadItem, val file: File)
