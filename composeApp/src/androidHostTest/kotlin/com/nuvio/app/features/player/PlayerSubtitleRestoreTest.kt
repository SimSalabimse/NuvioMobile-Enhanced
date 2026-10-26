package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import androidx.compose.ui.Modifier
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerSubtitleRestoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val episodeOneSubtitle = subtitle(1)
    private val episodeTwoSubtitle = subtitle(2)

    @Before
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        listOf("nuvio_player_track_preferences", "nuvio_watch_progress", "nuvio_player_settings").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        PlayerTrackPreferenceStorage.initialize(context)
        PlayerSettingsStorage.initialize(context)
        WatchProgressStorage.initialize(context)
        WatchProgressRepository.clearLocalState()
    }

    @After
    fun cleanup() {
        scope.cancel()
        WatchProgressRepository.clearLocalState()
    }

    @Test
    fun nextEpisodeReplacesManuallySelectedAddonSubtitle() {
        val runtime = runtime()
        runtime.persistAddonSubtitlePreference(episodeOneSubtitle)
        runtime.restorePersistedTrackPreferenceIfNeeded()
        assertTrue(runtime.isUserExplicitSubtitleSelection)

        val controller = advanceToEpisodeTwo(runtime)
        runtime.refreshTracks()
        runtime.refreshTracks()

        assertEquals("tt2026:1:2", runtime.activeVideoId)
        assertEquals(listOf(episodeTwoSubtitle.url), runtime.addonSubtitles.map { it.url })
        assertEquals(episodeTwoSubtitle.url, controller.subtitleUrls.lastOrNull())
    }

    @Test
    fun nextEpisodeSelectsCurrentSubtitleWithoutStoredManualSelection() {
        val runtime = runtime()
        val controller = advanceToEpisodeTwo(runtime)

        runtime.refreshTracks()

        assertEquals(listOf(episodeTwoSubtitle.url), controller.subtitleUrls)
    }

    @Test
    fun sameEpisodeRestoresItsStoredManualSelectionWhileOtherAddonsLoad() {
        runtime().persistAddonSubtitlePreference(episodeOneSubtitle)
        val reopened = runtime()
        val controller = RecordingController()
        reopened.playerController = controller
        reopened.isLoadingAddonSubtitles = true

        reopened.refreshTracks()

        assertEquals(listOf(episodeOneSubtitle.url), controller.subtitleUrls)
    }

    @Test
    fun restoreSelectsTheSavedProviderAsSoonAsItsSubtitlesArrive() {
        val runtime = runtime()
        runtime.persistAddonSubtitlePreference(episodeOneSubtitle)
        val controller = advanceToEpisodeTwo(runtime)
        val otherProvider = episodeTwoSubtitle.copy(url = "https://other.example/episode-2.srt", addonName = "Other")
        runtime.addonSubtitles = listOf(otherProvider)
        runtime.isLoadingAddonSubtitles = true

        runtime.refreshTracks()

        assertTrue(controller.subtitleUrls.isEmpty())
        assertFalse(runtime.trackPreferenceRestoreApplied)
        assertFalse(runtime.preferredSubtitleSelectionApplied)

        runtime.addonSubtitles = listOf(otherProvider, episodeTwoSubtitle)
        runtime.refreshTracks()

        assertEquals(listOf(episodeTwoSubtitle.url), controller.subtitleUrls)
        assertTrue(runtime.trackPreferenceRestoreApplied)

        runtime.isLoadingAddonSubtitles = false
        runtime.refreshTracks()

        assertEquals(listOf(episodeTwoSubtitle.url), controller.subtitleUrls)
    }

    @Test
    fun savedLanguageTakesPrecedenceOverGlobalLanguageAndTrackOrder() {
        val runtime = runtime()
        runtime.persistAddonSubtitlePreference(episodeOneSubtitle)
        val controller = advanceToEpisodeTwo(runtime)
        runtime.playerSettingsUiState = runtime.playerSettingsUiState.copy(preferredSubtitleLanguage = "fr")
        runtime.addonSubtitles = listOf(
            episodeTwoSubtitle.copy(url = "https://example.com/episode-2-fr.srt", language = "fr"),
            episodeTwoSubtitle,
        )

        runtime.refreshTracks()

        assertEquals(listOf(episodeTwoSubtitle.url), controller.subtitleUrls)
    }

    @Test
    fun missingSavedProviderFallsBackToCurrentEpisodeWithSameLanguage() {
        val runtime = runtime()
        runtime.persistAddonSubtitlePreference(episodeOneSubtitle)
        val controller = advanceToEpisodeTwo(runtime)
        val replacement = episodeTwoSubtitle.copy(addonName = "Other")
        runtime.addonSubtitles = listOf(replacement)
        runtime.isLoadingAddonSubtitles = true

        runtime.refreshTracks()

        assertTrue(controller.subtitleUrls.isEmpty())
        assertFalse(runtime.trackPreferenceRestoreApplied)

        runtime.isLoadingAddonSubtitles = false

        runtime.refreshTracks()

        assertEquals(listOf(replacement.url), controller.subtitleUrls)
    }

    @Test
    fun missingCurrentEpisodeSubtitlesNeverReopensTheSavedUrl() {
        val runtime = runtime()
        runtime.persistAddonSubtitlePreference(episodeOneSubtitle)
        val controller = advanceToEpisodeTwo(runtime)
        runtime.addonSubtitles = emptyList()

        runtime.refreshTracks()

        assertTrue(controller.subtitleUrls.isEmpty())
        assertFalse(runtime.isUserExplicitSubtitleSelection)
        assertTrue(runtime.trackPreferenceRestoreApplied)
    }

    @Test
    fun disabledSubtitlesStayDisabledOnNextEpisode() {
        val runtime = runtime()
        runtime.persistInternalSubtitlePreference(null)
        val controller = advanceToEpisodeTwo(runtime)

        runtime.refreshTracks()

        assertTrue(controller.subtitleUrls.isEmpty())
        assertEquals(listOf(-1), controller.subtitleSelections)
        assertTrue(runtime.isUserExplicitSubtitleSelection)
    }

    @Test
    fun savedOffStaysOffWhenTheEngineLaterSelectsBengali() {
        val runtime = openedRuntime(preferredSubtitleLanguage = SubtitleLanguageOption.NONE)
        val controller = runtime.playerController as RecordingController
        controller.reportedSubtitleTracks = listOf(
            subtitleTrack(index = 0, id = "1", label = "English", language = "en"),
            subtitleTrack(index = 1, id = "2", label = "Bengali", language = "bn"),
        )

        runtime.refreshTracks()

        assertEquals(-1, runtime.selectedSubtitleIndex)
        controller.reportedSubtitleTracks = listOf(
            subtitleTrack(index = 0, id = "1", label = "English", language = "en"),
            subtitleTrack(index = 1, id = "2", label = "Bengali", language = "bn", isSelected = true),
        )
        runtime.refreshTracks()

        assertEquals(-1, runtime.selectedSubtitleIndex)
        assertEquals(-1, controller.subtitleSelections.last())
        assertTrue(controller.subtitleSelections.none { it >= 0 })
    }

    @Test
    fun savedOffStaysOffWhenTracksArriveAlreadySelectedInEitherOrder() {
        listOf(0, 2).forEach { bengaliIndex ->
            val runtime = openedRuntime(preferredSubtitleLanguage = SubtitleLanguageOption.NONE)
            val controller = runtime.playerController as RecordingController
            runtime.refreshTracks()
            controller.reportedSubtitleTracks = listOf(
                subtitleTrack(index = 0, id = "1", label = "English", language = "en", isSelected = bengaliIndex == 0),
                subtitleTrack(index = 1, id = "2", label = "French", language = "fr"),
                subtitleTrack(index = 2, id = "3", label = "Bengali", language = "bn", isSelected = bengaliIndex == 2),
            )

            runtime.refreshTracks()

            assertEquals(-1, runtime.selectedSubtitleIndex)
            assertEquals(-1, controller.subtitleSelections.last())
        }
    }

    @Test
    fun explicitOffIsNotReplacedByALateSelectedTrack() {
        val runtime = openedRuntime(preferredSubtitleLanguage = "en")
        runtime.persistInternalSubtitlePreference(null)
        val controller = runtime.playerController as RecordingController

        runtime.refreshTracks()

        controller.reportedSubtitleTracks = listOf(
            subtitleTrack(index = 0, id = "1", label = "Bengali", language = "bn", isSelected = true),
            subtitleTrack(index = 1, id = "2", label = "English", language = "en"),
        )
        runtime.refreshTracks()

        assertEquals(-1, runtime.selectedSubtitleIndex)
        assertEquals(-1, controller.subtitleSelections.last())
    }

    @Test
    fun explicitLanguageIsNotReplacedWhenAnotherTrackBecomesSelected() {
        val runtime = openedRuntime(preferredSubtitleLanguage = SubtitleLanguageOption.NONE)
        val english = subtitleTrack(index = 1, id = "2", label = "English", language = "en", isSelected = true)
        runtime.persistInternalSubtitlePreference(english)
        val controller = runtime.playerController as RecordingController
        controller.reportedSubtitleTracks = listOf(
            subtitleTrack(index = 0, id = "1", label = "Bengali", language = "bn"),
            english,
        )

        runtime.refreshTracks()

        assertEquals(1, runtime.selectedSubtitleIndex)
        controller.reportedSubtitleTracks = listOf(
            subtitleTrack(index = 0, id = "1", label = "Bengali", language = "bn", isSelected = true),
            subtitleTrack(index = 1, id = "2", label = "English", language = "en"),
        )
        runtime.refreshTracks()

        assertEquals(1, runtime.selectedSubtitleIndex)
        assertEquals(1, controller.subtitleSelections.last())
    }

    @Test
    fun savedLanguageIsAppliedWhenTracksArriveAfterOpening() {
        val runtime = openedRuntime(preferredSubtitleLanguage = SubtitleLanguageOption.NONE)
        runtime.persistInternalSubtitlePreference(
            subtitleTrack(index = 1, id = "2", label = "English", language = "en"),
        )
        val controller = runtime.playerController as RecordingController

        runtime.refreshTracks()

        assertEquals(-1, runtime.selectedSubtitleIndex)
        controller.reportedSubtitleTracks = listOf(
            subtitleTrack(index = 0, id = "1", label = "Bengali", language = "bn", isSelected = true),
            subtitleTrack(index = 1, id = "8", label = "English", language = "eng"),
        )
        runtime.refreshTracks()

        assertEquals(1, runtime.selectedSubtitleIndex)
        assertEquals(1, controller.subtitleSelections.last())
    }

    @Test
    fun explicitAudioIsNotReplacedWhenTheEngineSelectsAnotherLanguage() {
        val runtime = openedRuntime(preferredSubtitleLanguage = SubtitleLanguageOption.NONE)
        val japanese = AudioTrack(index = 1, id = "2", label = "Japanese", language = "ja", isSelected = true)
        runtime.persistAudioPreference(japanese)
        val controller = runtime.playerController as RecordingController
        controller.reportedAudioTracks = listOf(
            AudioTrack(index = 0, id = "1", label = "English", language = "en"),
            japanese,
        )

        runtime.refreshTracks()

        assertEquals(1, runtime.selectedAudioIndex)
        controller.reportedAudioTracks = listOf(
            AudioTrack(index = 0, id = "1", label = "English", language = "en", isSelected = true),
            AudioTrack(index = 1, id = "2", label = "Japanese", language = "ja"),
        )
        runtime.refreshTracks()

        assertEquals(1, runtime.selectedAudioIndex)
        assertEquals(1, controller.audioSelections.last())
    }

    private fun advanceToEpisodeTwo(runtime: PlayerScreenRuntime): RecordingController {
        runtime.switchToEpisodeStream(
            StreamItem(url = "https://example.com/episode-2.mp4", addonName = "Test", addonId = "test"),
            MetaVideo(id = "tt2026:1:2", title = "Episode 2", season = 1, episode = 2),
        )
        runtime.resetIdentityStateIfNeeded()
        runtime.addonSubtitles = listOf(episodeTwoSubtitle)
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false)
        return RecordingController().also {
            runtime.playerController = it
            runtime.playerControllerSourceUrl = runtime.activeSourceUrl
        }
    }

    private fun runtime() = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "Series",
            sourceUrl = "https://example.com/episode-1.mp4",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Test",
            streamTitle = "Episode 1",
            streamSubtitle = null,
            initialBingeGroup = null,
            pauseDescription = null,
            onBack = {},
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 1,
            episodeTitle = "Episode 1",
            episodeThumbnail = null,
            contentType = "series",
            videoId = "tt2026:1:1",
            parentMetaId = "tt2026",
            parentMetaType = "series",
            providerAddonId = "test",
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        ),
    ).apply {
        scope = this@PlayerSubtitleRestoreTest.scope
        playerSettingsUiState = PlayerSettingsUiState(preferredSubtitleLanguage = "en")
        addonSubtitles = listOf(episodeOneSubtitle)
        playerController = RecordingController()
        resetIdentityStateIfNeeded()
    }

    private fun openedRuntime(preferredSubtitleLanguage: String): PlayerScreenRuntime {
        val controller = RecordingController()
        return runtime().apply {
            playerSettingsUiState = PlayerSettingsUiState(preferredSubtitleLanguage = preferredSubtitleLanguage)
            addonSubtitles = emptyList()
            playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false)
            playerController = controller
            playerControllerSourceUrl = activeSourceUrl
        }
    }

    private fun subtitleTrack(
        index: Int,
        id: String,
        label: String,
        language: String?,
        isSelected: Boolean = false,
    ) = SubtitleTrack(
        index = index,
        id = id,
        label = label,
        language = language,
        isSelected = isSelected,
    )

    private fun subtitle(episode: Int) = AddonSubtitle(
        id = "episode-$episode",
        url = "https://example.com/episode-$episode.srt",
        language = "en",
        display = "English",
        addonName = "Test",
    )

    private class RecordingController : PlayerEngineController {
        val subtitleUrls = mutableListOf<String>()
        val subtitleSelections = mutableListOf<Int>()
        val audioSelections = mutableListOf<Int>()
        var reportedSubtitleTracks: List<SubtitleTrack> = emptyList()
        var reportedAudioTracks: List<AudioTrack> = emptyList()

        override fun setSubtitleUri(url: String) {
            subtitleUrls += url
        }

        override fun selectSubtitleTrack(index: Int) {
            subtitleSelections += index
        }

        override fun getAudioTracks() = reportedAudioTracks
        override fun getSubtitleTracks() = reportedSubtitleTracks
        override fun selectAudioTrack(index: Int) {
            audioSelections += index
        }
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    }
}
