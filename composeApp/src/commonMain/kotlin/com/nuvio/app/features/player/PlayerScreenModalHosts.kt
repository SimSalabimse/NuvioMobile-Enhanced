package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.livetv.LiveTvChannel
import com.nuvio.app.features.p2p.P2pConsentDialog
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.streams.StreamItem

@Composable
internal fun PlayerScreenModalHosts(
    pendingP2pSwitch: PendingPlayerP2pSwitch?,
    onPendingP2pSwitchChanged: (PendingPlayerP2pSwitch?) -> Unit,
    onP2pEpisodeStreamSelected: (StreamItem, MetaVideo, Boolean) -> Unit,
    onP2pSourceStreamSelected: (StreamItem) -> Unit,
    onNextEpisodeAutoPlaySearchingChanged: (Boolean) -> Unit,
    onNextEpisodeAutoPlayCountdownChanged: (Int?) -> Unit,
    onNextEpisodeAutoPlaySourceNameChanged: (String?) -> Unit,
    showQualityPanel: Boolean,
    playerQualityState: PlayerQualitySelectionState,
    selectedPlayerQualityId: String?,
    currentQualityLabel: String?,
    onPlayerQualitySelected: (String?) -> Unit,
    onQualityPanelDismissed: () -> Unit,
    showLiveChannelsPanel: Boolean,
    liveTvChannels: List<LiveTvChannel>,
    activeLiveChannelId: String?,
    onLiveChannelSelected: (LiveTvChannel) -> Unit,
    onLiveChannelsPanelDismissed: () -> Unit,
    showChaptersPanel: Boolean = false,
    chapters: List<PlayerChapter> = emptyList(),
    displayedPositionMs: Long,
    durationMs: Long = 0L,
    onChapterSelected: (Int) -> Unit = {},
    onChaptersPanelDismissed: () -> Unit = {},
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
        durationMs = durationMs,
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
