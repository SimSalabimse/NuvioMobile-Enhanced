package com.nuvio.app.core.uifixture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.details.components.EpisodeWatchedActionSheet
import com.nuvio.app.features.downloads.DownloadSheetRequest
import com.nuvio.app.features.downloads.DownloadSourceSheet
import com.nuvio.app.features.downloads.SeasonDownloadSheet
import com.nuvio.app.features.player.AddonSubtitle
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.EpisodeStreamsPanelState
import com.nuvio.app.features.player.PlayerChaptersPanel
import com.nuvio.app.features.player.PlayerChapter
import com.nuvio.app.features.player.PlayerCurrentStream
import com.nuvio.app.features.player.PlayerEpisodesPanel
import com.nuvio.app.features.player.PlayerQualityPanel
import com.nuvio.app.features.player.PlayerQualitySelectionState
import com.nuvio.app.features.player.PlayerQualityVariant
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.PlayerSourcesPanel
import com.nuvio.app.features.player.PlayerToolTab
import com.nuvio.app.features.player.PlayerToolsPanel
import com.nuvio.app.features.player.SubtitleAutoSyncUiState
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState

/**
 * Panels, sheets, and menus that are not a navigation route. Each one is the
 * real composable, filled with the walk's synthetic titles.
 */
@Composable
internal fun UiWalkOverlay() {
    val screen = UiWalkScreens.current() ?: return
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF10141C)),
    ) {
        when (screen.kind) {
            WalkKind.DownloadSeason -> SeasonSheet()
            WalkKind.DownloadSheetDismiss -> DismissableSeasonSheet()
            WalkKind.EpisodeDownload -> EpisodeDownloadSheet()
            WalkKind.EpisodeMenu -> EpisodeMenuSheet()
            WalkKind.PlayerEpisodes -> PlayerEpisodes()
            WalkKind.PlayerSources -> PlayerSources()
            WalkKind.PlayerChapters -> PlayerChapters()
            WalkKind.PlayerQuality -> PlayerQuality()
            WalkKind.PlayerTools -> PlayerTools(styleOpen = false)
            WalkKind.PlayerSubtitles -> PlayerTools(styleOpen = true)
            WalkKind.PlayerSubmitBarMovie -> MovieSubmitBar()
            else -> Unit
        }
    }
}

@Composable
private fun MovieSubmitBar() {
    PlayerSubmitBarFixture(
        widthDp = PlayerSubmitBarDefaultWidthDp,
        longLabels = false,
        isMovie = true,
    )
}

@Composable
private fun SeasonSheet() {
    val meta = remember { UiFixtureCatalog.fixtureSeries() }
    SeasonDownloadSheet(
        meta = meta,
        seasonLabel = "Season 1",
        episodes = meta.videos.filter { it.season == 1 },
        isEpisodeWatched = { _ -> false },
        onOpenEpisode = {},
        onDismiss = {},
    )
}

@Composable
private fun DismissableSeasonSheet() {
    val dismiss by UiWalkSession.dismissSignal.collectAsStateWithLifecycle()
    var show by remember { mutableStateOf(true) }
    LaunchedEffect(dismiss) {
        if (dismiss) show = false
    }
    if (!show) return
    val meta = remember { UiFixtureCatalog.fixtureSeries() }
    SeasonDownloadSheet(
        meta = meta,
        seasonLabel = "Season 1",
        episodes = meta.videos.filter { it.season == 1 },
        isEpisodeWatched = { _ -> false },
        onOpenEpisode = {},
        onDismiss = { show = false },
    )
}

@Composable
private fun EpisodeDownloadSheet() {
    val meta = remember { UiFixtureCatalog.fixtureSeries() }
    val episode = meta.videos.first { it.season == 1 }
    DownloadSourceSheet(
        request = DownloadSheetRequest(
            type = meta.type,
            videoId = episode.id,
            parentMetaId = meta.id,
            parentMetaType = meta.type,
            title = meta.name,
            logo = meta.logo,
            poster = meta.poster,
            background = meta.background,
            seasonNumber = episode.season,
            episodeNumber = episode.episode,
            episodeTitle = episode.title,
            episodeThumbnail = episode.thumbnail,
        ),
        onDismiss = {},
    )
}

@Composable
private fun EpisodeMenuSheet() {
    val meta = remember { UiFixtureCatalog.fixtureSeries() }
    val episode = meta.videos.first { it.season == 1 }
    EpisodeWatchedActionSheet(
        episode = episode,
        seasonLabel = "Season 1",
        isEpisodeWatched = false,
        canMarkPreviousEpisodes = true,
        arePreviousEpisodesWatched = false,
        isSeasonWatched = false,
        onDismiss = {},
        onToggleWatched = {},
        onTogglePreviousWatched = {},
        onToggleSeasonWatched = {},
        onDownload = {},
        onMarkUpToHereWatched = {},
    )
}

@Composable
private fun PlayerEpisodes() {
    val meta = remember { UiFixtureCatalog.fixtureSeries() }
    PlayerEpisodesPanel(
        visible = true,
        episodes = meta.videos,
        parentMetaType = meta.type,
        parentMetaId = meta.id,
        currentSeason = 1,
        currentEpisode = 2,
        progressByVideoId = emptyMap(),
        watchedKeys = emptySet(),
        blurUnwatchedEpisodes = false,
        episodeStreamsState = EpisodeStreamsPanelState(),
        onSeasonSelected = { _ -> },
        onEpisodeSelected = { _ -> },
        onEpisodeStreamFilterSelected = { _ -> },
        onEpisodeStreamSelected = { _, _ -> },
        onBackToEpisodes = {},
        onReloadEpisodeStreams = {},
        onDismiss = {},
    )
}

@Composable
private fun PlayerSources() {
    val streams = remember { fixtureStreamState() }
    PlayerSourcesPanel(
        visible = true,
        streamsUiState = streams,
        contentTitle = UiFixtureCatalog.SERIES_TITLE,
        currentSeason = 1,
        currentEpisode = 2,
        currentEpisodeTitle = "Low Tide",
        currentStream = PlayerCurrentStream(
            url = streams.allStreams.firstOrNull()?.url,
            name = streams.allStreams.firstOrNull()?.name,
        ),
        onFilterSelected = { _ -> },
        onStreamSelected = { _ -> },
        onReload = {},
        onDismiss = {},
    )
}

@Composable
private fun PlayerChapters() {
    PlayerChaptersPanel(
        visible = true,
        chapters = listOf(
            PlayerChapter(startMs = 0L, title = "Cold open"),
            PlayerChapter(startMs = 120_000L, title = "The ferry"),
            PlayerChapter(startMs = 640_000L, title = "Lanterns"),
        ),
        positionMs = 180_000L,
        durationMs = 2_400_000L,
        onChapterSelected = { _ -> },
        onDismiss = {},
    )
}

@Composable
private fun PlayerQuality() {
    PlayerQualityPanel(
        visible = true,
        state = PlayerQualitySelectionState(
            sourceUrl = "fixture://media/harbor",
            variants = listOf(qualityVariant("1080", 1920, 1080), qualityVariant("720", 1280, 720)),
            recommendedVariantId = "720",
        ),
        selectedQualityId = "720",
        currentResolutionLabel = "720p",
        onQualitySelected = { _ -> },
        onDismiss = {},
    )
}

@Composable
private fun PlayerTools(styleOpen: Boolean) {
    val meta = remember { UiFixtureCatalog.fixtureSeries() }
    val streams = remember { fixtureStreamState() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        PlayerToolsPanel(
            tab = PlayerToolTab.Subs,
            tabs = PlayerToolTab.entries,
            onTab = { _ -> },
            onDismiss = {},
            endInset = 0.dp,
            subtitleTracks = listOf(
                SubtitleTrack(index = 0, id = "sub-en", label = "English", language = "en", isSelected = true),
                SubtitleTrack(index = 1, id = "sub-off", label = "Off", isSelected = false),
            ),
            selectedSubtitleIndex = 0,
            addonSubtitles = listOf(
                AddonSubtitle(
                    id = "addon-sub",
                    url = "fixture://sub/en",
                    language = "en",
                    display = "English",
                    addonName = "Fixture",
                ),
            ),
            selectedAddonSubtitle = null,
            isLoadingAddonSubtitles = false,
            preferredSubtitleLanguage = "en",
            secondaryPreferredSubtitleLanguage = null,
            subtitleStyle = SubtitleStyleState.DEFAULT,
            subtitleDelayMs = 0,
            subtitleAutoSyncState = SubtitleAutoSyncUiState(),
            onBuiltInSubtitle = { _ -> },
            onAddonSubtitle = { _ -> },
            onFetchAddonSubtitles = {},
            onStyleChanged = { _ -> },
            onDelayChanged = { _ -> },
            onDelayReset = {},
            onSync = {},
            onAutoSyncAutomatic = {},
            onAutoSyncCapture = {},
            onAutoSyncCue = { _ -> },
            onAutoSyncReload = {},
            audioTracks = listOf(
                AudioTrack(index = 0, id = "aud-en", label = "English", language = "en", isSelected = true),
            ),
            selectedAudioIndex = 0,
            onAudio = { _ -> },
            streams = streams,
            currentStreamUrl = streams.allStreams.firstOrNull()?.url,
            currentStreamName = streams.allStreams.firstOrNull()?.name,
            onSourceFilter = { _ -> },
            onSource = { _ -> },
            onReloadSources = {},
            showEpisodes = true,
            episodes = meta.videos,
            parentMetaType = meta.type,
            parentMetaId = meta.id,
            currentSeason = 1,
            currentEpisode = 2,
            progressByVideoId = emptyMap(),
            watchedKeys = emptySet(),
            onEpisode = { _ -> },
            episodeStreams = EpisodeStreamsPanelState(),
            onEpisodeStreamFilter = { _ -> },
            onEpisodeStream = { _, _ -> },
            onBackToEpisodes = {},
            onReloadEpisodeStreams = {},
            episodesLoading = false,
            episodesFailed = false,
            episodesEmptyNeedsRetry = false,
            episodesComplete = true,
            blurUnwatchedEpisodes = false,
            onRetryEpisodes = {},
            showTitle = UiFixtureCatalog.SERIES_TITLE,
            episodeLine = "S1 E2",
            aired = "2024",
            mediaInfoJson = "{}",
            selectedQuality = qualityVariant("720", 1280, 720),
            selectedQualityIsAuto = false,
            audioLine = "English",
            fileName = "harbor.mp4",
            resizeMode = PlayerResizeMode.Fit,
            onResize = { _ -> },
            playbackSpeed = 1f,
            onSpeed = { _ -> },
            videoSettings = PlayerSettingsUiState(),
            onVideoSettingsChanged = {},
            onResetVideo = {},
            styleOpen = styleOpen,
            onStyleOpenChange = { _ -> },
            showAudioDelay = false,
            audioDelayMs = 0,
            onAudioDelayChanged = {},
            showAudioAmplification = false,
            audioAmplificationDb = 0,
            keepAudioAmplification = false,
            onAudioAmplificationChanged = {},
            onKeepAudioAmplificationChanged = {},
            showCenterMix = false,
            centerMixLevelDb = 0,
            onCenterMixLevelChanged = {},
            downmixEnabled = false,
            onDownmixChanged = {},
        )
    }
}

private fun qualityVariant(name: String, width: Int, height: Int) = PlayerQualityVariant(
    id = name,
    name = name,
    width = width,
    height = height,
    bandwidth = if (height >= 1080) 5_000_000L else 2_500_000L,
    codecs = "avc1.4d401f,mp4a.40.2",
    audioGroupId = null,
    absoluteUri = "fixture://quality/$name",
    playbackUrl = "fixture://quality/$name",
)

private fun fixtureStreamState(): StreamsUiState {
    val streams = listOf(
        fixtureStream("Harbor 1080p"),
        fixtureStream("Harbor 720p"),
    )
    return StreamsUiState(
        groups = listOf(
            AddonStreamGroup(
                addonName = "Fixture",
                addonId = "fixture",
                streams = streams,
            ),
        ),
    )
}

private fun fixtureStream(name: String) = StreamItem(
    name = name,
    title = name,
    description = "Layout check",
    url = "fixture://media/$name",
    addonName = "Fixture",
    addonId = "fixture",
)
