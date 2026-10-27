package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import com.nuvio.app.features.details.MetaDetailsUiState
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.livetv.LiveTvChannel
import com.nuvio.app.features.p2p.P2pConsentDialog
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.watchprogress.WatchProgressEntry

@Composable
internal fun PlayerScreenModalHosts(
    pendingP2pSwitch: PendingPlayerP2pSwitch?,
    onPendingP2pSwitchChanged: (PendingPlayerP2pSwitch?) -> Unit,
    onP2pEpisodeStreamSelected: (StreamItem, MetaVideo, Boolean) -> Unit,
    onP2pSourceStreamSelected: (StreamItem) -> Unit,
    onNextEpisodeAutoPlaySearchingChanged: (Boolean) -> Unit,
    onNextEpisodeAutoPlayCountdownChanged: (Int?) -> Unit,
    onNextEpisodeAutoPlaySourceNameChanged: (String?) -> Unit,
    showAudioModal: Boolean,
    audioTracks: List<AudioTrack>,
    selectedAudioIndex: Int,
    onAudioTrackSelected: (Int) -> Unit,
    onAudioModalDismissed: () -> Unit,
    showSubtitleModal: Boolean,
    subtitleTracks: List<SubtitleTrack>,
    selectedSubtitleIndex: Int,
    addonSubtitles: List<AddonSubtitle>,
    selectedAddonSubtitleId: String?,
    isLoadingAddonSubtitles: Boolean,
    subtitleStyle: SubtitleStyleState,
    subtitleDelayMs: Int,
    selectedAddonSubtitle: AddonSubtitle?,
    subtitleAutoSyncState: SubtitleAutoSyncUiState,
    onBuiltInSubtitleTrackSelected: (Int) -> Unit,
    onAddonSubtitleSelected: (AddonSubtitle) -> Unit,
    onFetchAddonSubtitles: () -> Unit,
    onSubtitleStyleChanged: (SubtitleStyleState) -> Unit,
    onSubtitleDelayChanged: (Int) -> Unit,
    onSubtitleDelayReset: () -> Unit,
    onSyncByEarClick: () -> Unit,
    onAutoSyncAutomatic: () -> Unit,
    onAutoSyncCapture: () -> Unit,
    onAutoSyncCueSelected: (SubtitleSyncCue) -> Unit,
    onAutoSyncReload: () -> Unit,
    onSubtitleModalDismissed: () -> Unit,
    showVideoSettingsModal: Boolean,
    playerSettings: PlayerSettingsUiState,
    onVideoSettingsChanged: () -> Unit,
    onVideoSettingsModalDismissed: () -> Unit,
    showSourcesPanel: Boolean,
    sourceStreamsState: StreamsUiState,
    contentTitle: String,
    activeEpisodeTitle: String?,
    activeSourceUrl: String,
    activeStreamTitle: String,
    showQualityPanel: Boolean,
    playerQualityState: PlayerQualitySelectionState,
    selectedPlayerQualityId: String?,
    currentQualityLabel: String?,
    selectedQualityVariant: PlayerQualityVariant?,
    selectedQualityIsAuto: Boolean,
    onPlayerQualitySelected: (String?) -> Unit,
    onQualityPanelDismissed: () -> Unit,
    onSourceFilterSelected: (String?) -> Unit,
    onSourceStreamSelected: (StreamItem) -> Unit,
    onReloadSources: () -> Unit,
    onSourcesPanelDismissed: () -> Unit,
    showLiveChannelsPanel: Boolean,
    liveTvChannels: List<LiveTvChannel>,
    activeLiveChannelId: String?,
    onLiveChannelSelected: (LiveTvChannel) -> Unit,
    onLiveChannelsPanelDismissed: () -> Unit,
    isSeries: Boolean,
    showEpisodesPanel: Boolean,
    allEpisodes: List<MetaVideo>,
    parentMetaType: String,
    parentMetaId: String,
    activeSeasonNumber: Int?,
    activeEpisodeNumber: Int?,
    watchProgressByVideoId: Map<String, WatchProgressEntry>,
    watchedKeys: Set<String>,
    blurUnwatchedEpisodes: Boolean,
    episodeStreamsPanelState: EpisodeStreamsPanelState,
    episodeStreamsRepoState: StreamsUiState,
    onEpisodeSelectedForDownload: (MetaVideo) -> Boolean,
    onEpisodeStreamsRequested: (MetaVideo) -> Unit,
    onEpisodeStreamFilterSelected: (String?) -> Unit,
    onEpisodeStreamSelected: (StreamItem, MetaVideo) -> Unit,
    onBackToEpisodes: () -> Unit,
    onReloadEpisodeStreams: () -> Unit,
    onEpisodesPanelDismissed: () -> Unit,
    episodesLoading: Boolean = false,
    episodesLoadFailed: Boolean = false,
    episodesEmptyNeedsRetry: Boolean = false,
    episodesComplete: Boolean = true,
    onRetryEpisodes: () -> Unit = {},
    showChaptersPanel: Boolean = false,
    chapters: List<PlayerChapter> = emptyList(),
    onChapterSelected: (PlayerChapter) -> Unit = {},
    onChaptersPanelDismissed: () -> Unit = {},
    showSubmitIntroModal: Boolean,
    activeVideoId: String?,
    metaUiState: MetaDetailsUiState,
    playbackClock: PlayerPlaybackClock? = null,
    durationMs: Long = 0L,
    displayedPositionMs: Long,
    submitIntroSegmentType: String,
    onSubmitIntroSegmentTypeChanged: (String) -> Unit,
    submitIntroStartTimeStr: String,
    onSubmitIntroStartTimeChanged: (String) -> Unit,
    submitIntroEndTimeStr: String,
    onSubmitIntroEndTimeChanged: (String) -> Unit,
    onSubmitIntroDismissed: () -> Unit,
    onSubmitIntroSuccess: () -> Unit,
    skipIntervals: List<com.nuvio.app.features.player.skip.SkipInterval> = emptyList(),
    submittedSegmentTypesInSession: Set<String> = emptySet(),
    showStreamInfoModal: Boolean,
    mediaInfoJson: String,
    onStreamInfoModalDismissed: () -> Unit,
) {
    if (pendingP2pSwitch != null) {
        P2pConsentDialog(
            onEnableP2p = {
                val pending = pendingP2pSwitch
                onPendingP2pSwitchChanged(null)
                P2pSettingsRepository.setP2pEnabled(true)
                val episode = pending.episode
                if (episode != null) {
                    onP2pEpisodeStreamSelected(pending.stream, episode, pending.isAutoPlay)
                } else {
                    onP2pSourceStreamSelected(pending.stream)
                }
            },
            onDismiss = {
                if (pendingP2pSwitch.isAutoPlay) {
                    onNextEpisodeAutoPlaySearchingChanged(false)
                    onNextEpisodeAutoPlayCountdownChanged(null)
                    onNextEpisodeAutoPlaySourceNameChanged(null)
                }
                onPendingP2pSwitchChanged(null)
            },
        )
    }

    PlayerLiveChannelsPanel(
        visible = showLiveChannelsPanel,
        channels = liveTvChannels,
        currentStreamUrl = liveTvChannels.firstOrNull { it.id == activeLiveChannelId }?.streamUrl,
        onChannelSelected = onLiveChannelSelected,
        onDismiss = onLiveChannelsPanelDismissed,
    )

    PlayerQualityPanel(
        visible = showQualityPanel,
        state = playerQualityState,
        selectedQualityId = selectedPlayerQualityId,
        currentResolutionLabel = currentQualityLabel,
        onQualitySelected = onPlayerQualitySelected,
        onDismiss = onQualityPanelDismissed,
    )

    PlayerChaptersPanel(
        visible = showChaptersPanel,
        chapters = chapters,
        positionMs = displayedPositionMs,
        onChapterSelected = onChapterSelected,
        onDismiss = onChaptersPanelDismissed,
    )

}

internal fun selectDownloadedEpisodeForPlayback(
    parentMetaId: String,
    episode: MetaVideo,
    onDownloadedEpisodeSelected: (com.nuvio.app.features.downloads.DownloadItem, MetaVideo) -> Unit,
): Boolean {
    val downloadedEpisode = DownloadsRepository.findPlayableDownload(
        parentMetaId = parentMetaId,
        seasonNumber = episode.season,
        episodeNumber = episode.episode,
        videoId = episode.id,
    )
    if (downloadedEpisode != null) {
        onDownloadedEpisodeSelected(downloadedEpisode, episode)
        return true
    }
    return false
}
