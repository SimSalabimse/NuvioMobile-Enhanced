package com.nuvio.app.core.automation

import android.app.Application
import android.content.Context
import com.nuvio.app.core.auth.DeviceSessionRegistration
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.downloads.DownloadDiskInfo
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsSettingsRepository
import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.downloads.SeasonEnqueueRow
import com.nuvio.app.features.downloads.SeasonStreamCatalog
import com.nuvio.app.features.downloads.enqueueSeasonDownloads
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.homeHasActiveSources
import com.nuvio.app.features.home.homeSectionsMissingFromSettings
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.player.skip.IntroSubmitKeyPrepareResult
import com.nuvio.app.features.player.skip.IntroSubmitService
import com.nuvio.app.features.player.skip.ManualSkipSubmitOutcome
import com.nuvio.app.features.player.skip.introSubmitKeySatisfied
import com.nuvio.app.features.player.skip.prepareIntroSubmitKey
import com.nuvio.app.features.player.skip.submitManualSkipSegment
import com.nuvio.app.features.search.SearchRepository
import com.nuvio.app.features.streams.PlaybackAvailability
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.isSelectableForPlayback
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        SearchRepository.reset()
        PlayerSettingsRepository.clearLocalState()
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
        assertEquals("fixture://art/season/1/Season_1", series.seasonPosters[1])
        assertEquals("fixture://art/season/2/Season_2", series.seasonPosters[2])
        assertEquals(AUTOMATION_SEASON_POSTER_URL, series.seasonPosters[3])
        assertEquals(AUTOMATION_SEASON_POSTER_URL, series.videos.first { it.season == 3 }.seasonPoster)
        assertTrue(com.nuvio.app.core.ui.automationImageRequestAllowed(AUTOMATION_SEASON_POSTER_URL))
        assertTrue(
            com.nuvio.app.core.ui.automationImageRequestAllowed(
                "https://image.tmdb.org/t/p/original/1E5baAaEse26fej7uHcjOgEE2t2.jpg",
            ),
        )
        assertFalse(com.nuvio.app.core.ui.automationImageRequestAllowed("https://cdn.example/other.jpg"))
        assertEquals("season_poster_3", com.nuvio.app.features.details.seasonPosterAccessibilityId(3))
        assertEquals(AUTOMATION_PROFILE_NAME, "Test")
        assertEquals(AUTOMATION_MOVIE_NAME, AutomationStubs.movie().name)
    }

    @Test
    fun flagOnShowsStubRowsAndEnablesPlayback() {
        val sections = AutomationStubs.homeState().sections
        AutomationTestMode.installForTests(active = false)
        assertFalse(homeHasActiveSources(installedAddonManifests = false, sections = sections))
        assertTrue(homeSectionsMissingFromSettings(sections, emptySet()).isEmpty())
        SearchRepository.refreshDiscover(emptyList())
        SearchRepository.search("automation", emptyList())
        assertTrue(SearchRepository.discoverUiState.value.items.isEmpty())
        assertTrue(SearchRepository.uiState.value.sections.isEmpty())
        val playableWithoutTheFlag = PlaybackAvailability.current()
            .canPlay("movie", AUTOMATION_MOVIE_ID, AUTOMATION_MOVIE_ID)

        AutomationTestMode.installForTests(active = true)
        assertTrue(homeHasActiveSources(installedAddonManifests = false, sections = sections))
        val extra = homeSectionsMissingFromSettings(sections, emptySet())
        assertEquals(listOf(AUTOMATION_SERIES_NAME, AUTOMATION_MOVIE_NAME), extra.single().items.map { it.name })
        assertTrue(homeSectionsMissingFromSettings(sections, setOf(extra.single().key)).isEmpty())
        SearchRepository.refreshDiscover(emptyList())
        SearchRepository.search("automation", emptyList())
        assertEquals(
            listOf(AUTOMATION_SERIES_NAME, AUTOMATION_MOVIE_NAME),
            SearchRepository.discoverUiState.value.items.map { it.name },
        )
        assertEquals(
            listOf(AUTOMATION_SERIES_NAME, AUTOMATION_MOVIE_NAME),
            SearchRepository.uiState.value.sections.single().items.map { it.name },
        )
        assertNull(SearchRepository.discoverUiState.value.emptyStateReason)
        assertTrue(PlaybackAvailability.current().canPlay("movie", AUTOMATION_MOVIE_ID, AUTOMATION_MOVIE_ID))
        AutomationTestMode.installForTests(active = false)
        assertEquals(
            playableWithoutTheFlag,
            PlaybackAvailability.current().canPlay("movie", AUTOMATION_MOVIE_ID, AUTOMATION_MOVIE_ID),
        )
        assertEquals(0, AutomationStubs.blockedNetworkCalls)
        assertEquals(0, AutomationStubs.realClientCalls)
    }

    @Test
    fun flagOnCompletesThreeQueuedEpisodesIntoTheDownloadsFolder() {
        AutomationTestMode.installForTests(active = true)
        AutomationStubs.resetCounts()
        val series = AutomationStubs.series()
        val episodes = series.videos.filter { it.season == 1 }.sortedBy { it.episode }
        assertEquals(3, episodes.size)
        val result = runBlocking {
            val streams = SeasonStreamCatalog.load(
                type = series.type,
                videoId = episodes.first().id,
                season = 1,
                episode = 1,
            )
            enqueueSeasonDownloads(
                meta = series,
                rows = episodes.map { episode ->
                    SeasonEnqueueRow(video = episode, stream = streams.first())
                },
            )
        }
        assertEquals(3, result.queued)
        assertNull(result.failureMessage)
        val items = DownloadsRepository.uiState.value.items
        assertEquals(3, items.size)
        assertTrue(items.all { it.status == DownloadStatus.Completed })
        assertTrue(items.all { it.downloadedBytes == AUTOMATION_CLIP_BYTES.size.toLong() })
        items.forEach { item ->
            val file = File(URI(checkNotNull(item.localFileUri)))
            assertTrue(file.isFile)
            assertEquals(item.fileName, file.name)
            assertTrue(file.readBytes().contentEquals(AUTOMATION_CLIP_BYTES))
        }
        val listed = DownloadDiskInfo.listDownloadFiles().map { it.name }.toSet()
        assertTrue(items.all { it.fileName in listed })
        assertEquals(
            AUTOMATION_CLIP_BYTES.size.toLong() * 3,
            DownloadsRepository.storageSummary().downloadBytes,
        )
        assertEquals(0, AutomationStubs.realClientCalls)
        assertEquals(0, AutomationStubs.blockedNetworkCalls)
    }

    @Test
    fun flagOnSubmitUsesTheStubStatusAndDoesNotPost() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE).edit().clear().commit()
        PlayerSettingsStorage.initialize(context)
        PlayerSettingsRepository.clearLocalState()
        PlayerSettingsRepository.ensureLoaded()
        assertFalse(PlayerSettingsRepository.uiState.value.introSubmitEnabled)
        assertFalse(introSubmitKeySatisfied(IntroSubmitService.THE_INTRODB, ""))

        val blocked = runBlocking {
            prepareIntroSubmitKey(IntroSubmitService.THE_INTRODB, "", "")
        }
        assertTrue(blocked is IntroSubmitKeyPrepareResult.Rejected)

        AutomationTestMode.installForTests(active = true, submitStatus = 409)
        TheIntroDbSubmitProbe.networkPosts = 0
        AutomationStubs.resetCounts()
        assertTrue(introSubmitKeySatisfied(IntroSubmitService.THE_INTRODB, ""))
        assertEquals(
            IntroSubmitKeyPrepareResult.Ready,
            runBlocking { prepareIntroSubmitKey(IntroSubmitService.THE_INTRODB, "", "") },
        )
        assertFalse(PlayerSettingsRepository.uiState.value.introSubmitEnabled)
        assertEquals(ManualSkipSubmitOutcome.FAILED, runBlocking { submitAutomationMovie() })
        assertEquals(0, TheIntroDbSubmitProbe.networkPosts)
        assertEquals(0, AutomationStubs.blockedNetworkCalls)
        assertEquals(0, AutomationStubs.realClientCalls)

        AutomationTestMode.installForTests(active = true, submitStatus = 200)
        assertEquals(ManualSkipSubmitOutcome.SENT, runBlocking { submitAutomationMovie() })
        assertEquals(0, TheIntroDbSubmitProbe.networkPosts)
        assertEquals(0, AutomationStubs.blockedNetworkCalls)

        AutomationTestMode.installForTests(active = false)
        assertEquals(ManualSkipSubmitOutcome.FAILED, runBlocking { submitAutomationMovie() })
        assertEquals(0, TheIntroDbSubmitProbe.networkPosts)
    }

    @Test
    fun clearRestoresStubHomeOnlyWhileTheFlagIsOn() {
        AutomationTestMode.installForTests(active = false)
        HomeRepository.clear()
        assertTrue(HomeRepository.uiState.value.sections.none { section ->
            section.items.any { it.id == AUTOMATION_SERIES_ID }
        })
        assertTrue(HomeRepository.uiState.value.heroItems.none { it.id == AUTOMATION_MOVIE_ID })

        AutomationTestMode.installForTests(active = true)
        HomeRepository.clear()
        val cleared = HomeRepository.uiState.value
        assertEquals(
            listOf(AUTOMATION_SERIES_NAME, AUTOMATION_MOVIE_NAME),
            cleared.sections.single().items.map { it.name },
        )
        assertEquals(
            listOf(AUTOMATION_SERIES_NAME, AUTOMATION_MOVIE_NAME),
            cleared.heroItems.map { it.name },
        )
        HomeRepository.applyCurrentSettings()
        assertEquals(
            listOf(AUTOMATION_SERIES_NAME, AUTOMATION_MOVIE_NAME),
            HomeRepository.uiState.value.sections.single().items.map { it.name },
        )

        AutomationTestMode.installForTests(active = false)
        HomeRepository.clear()
        assertTrue(HomeRepository.uiState.value.sections.none { section ->
            section.items.any { it.id == AUTOMATION_SERIES_ID }
        })
    }

    @Test
    fun flagOnResolvesTheMovieSourceToTheLocalClip() {
        AutomationTestMode.installForTests(active = false)
        val plain = AutomationStubs.sources().first()
        assertFalse(plain.isSelectableForPlayback(debridEnabled = false))
        assertFalse(DirectDebridPlaybackResolver.shouldResolveToPlayableStream(plain))

        AutomationTestMode.installForTests(active = true)
        AutomationStubs.resetCounts()
        StreamsRepository.load(
            type = "movie",
            videoId = AUTOMATION_MOVIE_ID,
            parentMetaId = AUTOMATION_MOVIE_ID,
            manualSelection = true,
        )
        val source = StreamsRepository.uiState.value.groups.single().streams.first { it.name == "Automation 1080p" }
        assertTrue(source.isSelectableForPlayback(debridEnabled = false))
        assertTrue(DirectDebridPlaybackResolver.shouldResolveToPlayableStream(source))
        val resolved = runBlocking {
            DirectDebridPlaybackResolver.resolveToPlayableStream(source, season = null, episode = null)
        }
        val playable = (resolved as DirectDebridPlayableResult.Success).stream
        val url = assertNotNull(playable.playableDirectUrl)
        assertTrue(AutomationStubs.isLocalClip(url))
        assertTrue(File(URI(url)).isFile)
        assertTrue(File(URI(url)).readBytes().contentEquals(AUTOMATION_CLIP_BYTES))
        assertFalse(DirectDebridPlaybackResolver.shouldResolveToPlayableStream(playable))
        assertEquals(0, AutomationStubs.realResolveCalls)
        assertEquals(0, AutomationStubs.realClientCalls)
        assertEquals(0, AutomationStubs.blockedNetworkCalls)
    }

    @Test
    fun flagOnDoesNotRegisterTheDeviceSession() = runBlocking {
        AutomationTestMode.installForTests(active = true)
        AutomationStubs.resetCounts()
        assertFalse(DeviceSessionRegistration.registerIfAuthenticated(force = true))
        assertEquals(0, AutomationStubs.blockedNetworkCalls)
        assertEquals(0, AutomationStubs.realClientCalls)
    }

    private suspend fun submitAutomationMovie(): ManualSkipSubmitOutcome = submitManualSkipSegment(
        imdbId = "",
        season = 0,
        episode = 0,
        startSec = 0.0,
        endSec = 5.0,
        segmentType = "intro",
        durationMs = 8_000,
        isMovie = true,
        videoId = AUTOMATION_MOVIE_ID,
        parentMetaType = "movie",
        service = IntroSubmitService.THE_INTRODB,
    )
}
