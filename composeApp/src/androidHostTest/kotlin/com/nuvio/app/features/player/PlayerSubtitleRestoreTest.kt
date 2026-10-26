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
    fun autoSelectedAddonIsAddedOnceWhenSubAddChangesTheTrackList() {
        val runtime = openedRuntime(preferredSubtitleLanguage = "fr")
        runtime.playerSettingsUiState = runtime.playerSettingsUiState.copy(
            preferredSubtitleLanguage = "fr",
            secondaryPreferredSubtitleLanguage = "en",
        )
        val french = AddonSubtitle(
            id = "fr",
            url = "https://cdn.example/a1b2c3.srt",
            language = "fr",
            display = "French",
            addonName = "Test",
        )
        runtime.addonSubtitles = listOf(french)
        val controller = runtime.playerController as RecordingController
        controller.includeExternalSubtitleOnAdd = true
        controller.reportedSubtitleTracks = listOf(
            subtitleTrack(index = 0, id = "1", label = "English", language = "en"),
        )

        runtime.refreshTracks()
        runtime.refreshTracks()
        controller.reportedSubtitleTracks = controller.reportedSubtitleTracks + subtitleTrack(
            index = controller.reportedSubtitleTracks.size,
            id = "de",
            label = "German",
            language = "de",
        )
        runtime.refreshTracks()

        assertEquals(listOf(french.url), controller.subtitleUrls)
    }

    @Test
    fun autoSelectedForcedAddonIsAddedOnceWhenSubAddChangesTheTrackList() {
        val runtime = openedRuntime(preferredSubtitleLanguage = "en")
        runtime.playerSettingsUiState = runtime.playerSettingsUiState.copy(
            preferredSubtitleLanguage = "en",
            subtitleStyle = SubtitleStyleState.DEFAULT.copy(useForcedSubtitles = true),
        )
        val forced = AddonSubtitle(
            id = "forced-en",
            url = "https://cdn.example/b4c5d6.srt",
            language = "en",
            display = "English",
            addonName = "Test",
        )
        runtime.addonSubtitles = listOf(forced)
        val controller = runtime.playerController as RecordingController
        controller.includeExternalSubtitleOnAdd = true
        controller.reportedAudioTracks = listOf(
            AudioTrack(index = 0, id = "a", label = "English", language = "en", isSelected = true),
        )
        controller.reportedSubtitleTracks = listOf(
            subtitleTrack(index = 0, id = "1", label = "English", language = "en"),
        )

        repeat(3) { runtime.refreshTracks() }

        assertEquals(listOf(forced.url), controller.subtitleUrls)
    }

    @Test
    fun explicitOffDoesNotLoopWhenTheTrackListGrows() {
        val runtime = openedRuntime(preferredSubtitleLanguage = SubtitleLanguageOption.NONE)
        runtime.persistInternalSubtitlePreference(null)
        val controller = runtime.playerController as RecordingController
        controller.reportedSubtitleTracks = listOf(
            subtitleTrack(index = 0, id = "1", label = "Bengali", language = "bn", isSelected = true),
        )

        runtime.refreshTracks()
        controller.reportedSubtitleTracks = controller.reportedSubtitleTracks + subtitleTrack(
            index = 1,
            id = "2",
            label = "English",
            language = "en",
            isSelected = true,
        )
        runtime.refreshTracks()

        assertTrue(controller.subtitleUrls.isEmpty())
        assertEquals(-1, runtime.selectedSubtitleIndex)
        assertTrue(controller.subtitleSelections.all { it < 0 })
    }

    @Test
    fun explicitSubtitleStaysOnItsLanguageWhenTheSameIndexBecomesAnotherLanguage() {
        val runtime = openedRuntime(preferredSubtitleLanguage = SubtitleLanguageOption.NONE)
        runtime.trackPreferenceRestoreApplied = true
        runtime.isUserExplicitSubtitleSelection = true
        runtime.preferredSubtitleSelectionApplied = true
        runtime.selectedSubtitleIndex = 1
        runtime.subtitleTracks = listOf(
            subtitleTrack(index = 0, id = "ja", label = "Japanese", language = "ja"),
            subtitleTrack(index = 1, id = "eng", label = "English", language = "en", isSelected = true),
        )
        val controller = runtime.playerController as RecordingController
        controller.reportedSubtitleTracks = listOf(
            subtitleTrack(index = 0, id = "a", label = "Japanese", language = "ja", isSelected = true),
            subtitleTrack(index = 1, id = "bn", label = "Bengali", language = "bn"),
            subtitleTrack(index = 2, id = "en2", label = "English SDH", language = "en"),
        )

        runtime.refreshTracks()
        runtime.refreshTracks()

        assertEquals(2, runtime.selectedSubtitleIndex)
        assertEquals(2, controller.subtitleSelections.last())
        assertTrue(controller.subtitleSelections.none { it == 1 })
    }

    @Test
    fun explicitSubtitleDoesNotSelectTheStaleIndexWhenTheLanguageIsGone() {
        val runtime = openedRuntime(preferredSubtitleLanguage = SubtitleLanguageOption.NONE)
        runtime.trackPreferenceRestoreApplied = true
        runtime.isUserExplicitSubtitleSelection = true
        runtime.preferredSubtitleSelectionApplied = true
        runtime.selectedSubtitleIndex = 1
        runtime.subtitleTracks = listOf(
            subtitleTrack(index = 0, id = "ja", label = "Japanese", language = "ja"),
            subtitleTrack(index = 1, id = "eng", label = "English", language = "en", isSelected = true),
        )
        val controller = runtime.playerController as RecordingController
        controller.reportedSubtitleTracks = listOf(
            subtitleTrack(index = 0, id = "a", label = "Japanese", language = "ja", isSelected = true),
            subtitleTrack(index = 1, id = "bn", label = "Bengali", language = "bn"),
        )

        runtime.refreshTracks()

        assertTrue(controller.subtitleSelections.none { it == 1 })
    }

    @Test
    fun explicitAudioStaysOnItsLanguageWhenTheSameIndexBecomesAnotherLanguage() {
        val runtime = openedRuntime(preferredSubtitleLanguage = SubtitleLanguageOption.NONE)
        runtime.trackPreferenceRestoreApplied = true
        runtime.isUserExplicitAudioSelection = true
        runtime.preferredAudioSelectionApplied = true
        runtime.selectedAudioIndex = 1
        runtime.audioTracks = listOf(
            AudioTrack(index = 0, id = "ja", label = "Japanese", language = "ja"),
            AudioTrack(index = 1, id = "eng", label = "English", language = "en", isSelected = true),
        )
        val controller = runtime.playerController as RecordingController
        controller.reportedAudioTracks = listOf(
            AudioTrack(index = 0, id = "a", label = "Japanese", language = "ja", isSelected = true),
            AudioTrack(index = 1, id = "bn", label = "Bengali", language = "bn"),
            AudioTrack(index = 2, id = "en2", label = "English SDH", language = "en"),
        )

        runtime.refreshTracks()
        runtime.refreshTracks()

        assertEquals(2, runtime.selectedAudioIndex)
        assertEquals(2, controller.audioSelections.last())
        assertTrue(controller.audioSelections.none { it == 1 })
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
        var includeExternalSubtitleOnAdd: Boolean = false

        override fun setSubtitleUri(url: String) {
            subtitleUrls += url
            if (includeExternalSubtitleOnAdd) {
                reportedSubtitleTracks = reportedSubtitleTracks + SubtitleTrack(
                    index = reportedSubtitleTracks.size,
                    id = "external-${subtitleUrls.size}",
                    label = url,
                    language = null,
                    isSelected = true,
                )
            }
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
