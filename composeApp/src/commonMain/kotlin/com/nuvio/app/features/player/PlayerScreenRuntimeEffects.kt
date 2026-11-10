package com.nuvio.app.features.player

import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.perf.PerformanceMetrics
import com.nuvio.app.core.perf.PlaybackQuietMode
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.LocalFold
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.party.PartyRepository
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.shuffle.EpisodeShuffleRepository
import com.nuvio.app.features.shuffle.ShuffleSurface
import com.nuvio.app.features.shuffle.watchedShuffleEpisodes
import com.nuvio.app.features.shuffle.shuffleEpisodeProgress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.SkipUndo
import com.nuvio.app.features.player.skip.segmentCategory
import com.nuvio.app.features.player.skip.shouldAutoSkip
import com.nuvio.app.features.player.skip.internalSkipAction
import com.nuvio.app.features.player.skip.intervalsAtSeekPositions
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.FinishedDownloadDeletion
import com.nuvio.app.features.downloads.SmartDownloads
import com.nuvio.app.features.downloads.PartialPlaybackLease
import com.nuvio.app.features.downloads.partialPlaybackDownloadId
import com.nuvio.app.features.servers.ServerPlayback
import com.nuvio.app.features.servers.ServerStreams
import com.nuvio.app.features.streams.BingeGroupCacheRepository
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.hasLikelyExpiringPlaybackCredentials
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.allowsProgressWrite
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

private const val SKIP_INTERVAL_RESOLVE_TIMEOUT_MS = 4_000L

/**
 * Coarse view of the playback snapshot. The engine publishes a snapshot every 250ms, while the
 * effects below only care about the state flags and about the position at second granularity.
 */
private data class PlayerSnapshotSignals(
    val isPlaying: Boolean,
    val isLoading: Boolean,
    val isEnded: Boolean,
    val durationMs: Long,
    val positionSeconds: Long,
)

/**
 * True when the app already knows it has no usable connection. Unknown/Checking states are
 * treated as "not offline" so we never suppress work we are not sure about.
 */
internal fun isNetworkOffline(): Boolean =
    NetworkStatusRepository.uiState.value.isOfflineLike

@Composable
private fun rememberNetworkCondition(): NetworkCondition {
    val uiState by remember { NetworkStatusRepository.uiState }.collectAsStateWithLifecycle()
    return uiState.condition
}

@Composable
internal fun PlayerScreenRuntime.BindPlayerRuntimeEffects() {
    BindSleepTimerEffect()
    val networkCondition = rememberNetworkCondition()
    val currentFeedback = liveGestureFeedback ?: gestureFeedback
    LaunchedEffect(currentFeedback) {
        if (currentFeedback != null) {
            renderedGestureFeedback = currentFeedback
        }
    }

    LaunchedEffect(parentMetaType, parentMetaId) {
        episodesLoadGeneration += 1
        episodesLoadJob?.cancel()
        episodesLoadJob = null
        episodesLoading = false
        episodesLoadFailed = false
        episodesEmptyNeedsRetry = false
        episodesComplete = true
        val peeked = MetaDetailsRepository.peek(parentMetaType, parentMetaId)
        playerMeta = peeked
        playerMetaVideos = peeked?.videos.orEmpty()
        val published = MetaDetailsRepository.uiState.value
        if (published.meta?.type == parentMetaType && published.meta?.id == parentMetaId && !published.episodesComplete) {
            episodesComplete = false
        }
        if (playerMetaVideos.isEmpty()) {
            val report = MetaDetailsRepository.fetchReporting(parentMetaType, parentMetaId)
            val meta = report.meta
            val incoming = meta?.videos.orEmpty()
            playerMetaVideos = episodesKeptAfterIncompleteFetch(playerMetaVideos, incoming, report.episodesComplete)
            if (meta != null && (incoming.isNotEmpty() || report.episodesComplete || playerMeta == null)) {
                playerMeta = meta
            }
            episodesComplete = report.episodesComplete
            if (!report.episodesComplete && playerMetaVideos.isEmpty()) {
                episodesLoadFailed = true
            }
        }
    }

    LaunchedEffect(metaUiState.meta, parentMetaType, parentMetaId) {
        val currentMeta = metaUiState.meta ?: return@LaunchedEffect
        if (currentMeta.type == parentMetaType && currentMeta.id == parentMetaId) {
            playerMetaVideos = mergePlayerEpisodes(playerMetaVideos, currentMeta.videos)
            if (currentMeta.videos.isNotEmpty() || playerMetaVideos.isEmpty()) {
                playerMeta = currentMeta
            }
        }
    }

    LaunchedEffect(currentStreamBingeGroup, currentStreamFilename, currentStreamInfoHash, parentMetaId) {
        val bg = currentStreamBingeGroup
        if (bg != null && parentMetaId.isNotBlank()) {
            BingeGroupCacheRepository.save(parentMetaId, bg, currentStreamFilename, currentStreamInfoHash)
        }
    }

    LaunchedEffect(activePlaybackKey, activeSourceUrl, activeSourceAudioUrl, activeSourceHeaders, activeSourceResponseHeaders) {
        errorMessage = null
        // Stream <-> download swap of the same video: same mpv, same session, nothing to reset.
        if (isDownloadSwapInFlight) return@LaunchedEffect
        playerController = null
        playerControllerSourceUrl = null
        playbackSnapshot = PlayerPlaybackSnapshot()
        playbackClock.reset()
        playbackSnapshotKey = null
        cancelNextEpisodeAutoPlay()
        isScrubbingTimeline = false
        scrubbingPositionMs = null
        liveGestureFeedback = null
        renderedGestureFeedback = null
        lockedOverlayVisible = false
        credentialRefreshJob?.cancel()
        credentialRefreshJob = null
        credentialRefreshAttemptedSourceUrl = null
        initialLoadCompleted = false
        lastProgressPersistEpochMs = 0L
        previousIsPlaying = false
        pendingSeekScrobbleRestart = false
        seekProgressSyncJob?.cancel()
        seekProgressSyncJob = null
        accumulatedSeekResetJob?.cancel()
        accumulatedSeekResetJob = null
        accumulatedSeekState = null
        cancelHoldToSpeed()
        applyShowSpeed()
        preferredAudioSelectionApplied = false
        appliedAudioPreferences = null
        preferredSubtitleSelectionApplied = false
        observedSubtitleTrackStructure = null
        subtitleOffRequested = false
        isUserExplicitAudioSelection = false
        isUserExplicitSubtitleSelection = false
        hasScannedTextTracksOnce = false
        clearAutoSubtitleState()
        wasAutoSubtitleVolumeMuted = false
        selectedSubtitleIndex = -1
        selectedAddonSubtitleId = null
        useCustomSubtitles = false
        showSourcesPanel = false
        showEpisodesPanel = false
        showChaptersPanel = false
        chapters = emptyList()
        chaptersLoadedForSourceUrl = null
        clearChapterSkipState()
        episodeStreamsPanelState = EpisodeStreamsPanelState()
        PlayerStreamsRepository.clearEpisodeStreams()
        SubtitleRepository.clear()
        autoFetchedAddonSubtitlesForKey = null
        WatchProgressRepository.ensureLoaded()
    }

    LaunchedEffect(
        activeTorrentInfoHash,
        activeTorrentFileIdx,
        activeTorrentFilename,
        activeTorrentTrackers,
        p2pSettingsUiState.p2pEnabled,
    ) {
        val infoHash = activeTorrentInfoHash
        if (infoHash == null) {
            p2pResolvedSourceUrl = null
            P2pStreamingEngine.stopStream()
            return@LaunchedEffect
        }
        if (!P2pSettingsRepository.isVisible || !p2pSettingsUiState.p2pEnabled) {
            p2pResolvedSourceUrl = null
            P2pStreamingEngine.stopStream()
            return@LaunchedEffect
        }

        p2pResolvedSourceUrl = null
        val requestedFileIdx = activeTorrentFileIdx
        val requestedFilename = activeTorrentFilename
        val requestedTrackers = activeTorrentTrackers
        errorMessage = null
        playerController = null
        playerControllerSourceUrl = null
        playbackSnapshot = PlayerPlaybackSnapshot()
        playbackClock.reset()
        initialLoadCompleted = false

        try {
            val localUrl = P2pStreamingEngine.startStream(
                P2pStreamRequest(
                    infoHash = infoHash,
                    fileIdx = requestedFileIdx,
                    filename = requestedFilename,
                    trackers = requestedTrackers,
                ),
            )
            if (activeTorrentInfoHash == infoHash && activeTorrentFileIdx == requestedFileIdx) {
                activeSourceAudioUrl = null
                activeSourceHeaders = emptyMap()
                activeSourceResponseHeaders = emptyMap()
                p2pResolvedSourceUrl = localUrl
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = getString(
                Res.string.player_error_failed_start_torrent,
                error.message ?: genericUnknownLabel,
            )
            controlsVisible = !playerControlsLocked
            initialLoadCompleted = true
        }
    }

    LaunchedEffect(p2pStreamingState, activeTorrentInfoHash) {
        val state = p2pStreamingState
        if (activeTorrentInfoHash != null && state is P2pStreamingState.Error) {
            p2pResolvedSourceUrl = null
            playerController = null
            playerControllerSourceUrl = null
            playbackSnapshot = PlayerPlaybackSnapshot()
            playbackClock.reset()
            initialLoadCompleted = true
            errorMessage = getString(Res.string.player_error_torrent, state.message)
            controlsVisible = !playerControlsLocked
        }
    }

    LaunchedEffect(playbackSession.videoId) {
        subtitleDrift.noteTrackOrStreamChanged()
        val storedDelay = PlayerTrackPreferenceStorage.loadSubtitleDelayMs(playbackSession.videoId) ?: 0
        subtitleDelayMs = storedDelay
        subtitleDrift.noteManualDelay(storedDelay.toLong())
        subtitleAutoSyncState = SubtitleAutoSyncUiState()
    }

    LaunchedEffect(playerController, subtitleDelayMs) {
        playerController?.setSubtitleDelayMs(subtitleDelayMs)
    }

    BindAudioDelayEffects()
    BindAudioAmplificationEffects()

    LaunchedEffect(selectedAddonSubtitleId, useCustomSubtitles, activeSourceUrl) {
        subtitleDrift.noteTrackOrStreamChanged()
        subtitleAutoSyncState = SubtitleAutoSyncUiState()
    }

    LaunchedEffect(playerController, subtitleStyle, showSubtitleSyncByEar) {
        val applied = if (showSubtitleSyncByEar) {
            subtitleStyle.copy(bottomOffset = subtitleBottomOffsetForSyncStrip(subtitleStyle.bottomOffset))
        } else {
            subtitleStyle
        }
        playerController?.applySubtitleStyle(applied)
    }

    // iOS recognises hardware keys in the mpv view controller, because its UIKit view can hold
    // first responder at moments when the Compose surface does not. It only reports which
    // shortcut was pressed — the action itself still runs through the shared runtime dispatcher.
    DisposableEffect(playerController) {
        playerController?.setKeyboardShortcutHandler(::handleKeyboardShortcut)
        onDispose { playerController?.setKeyboardShortcutHandler(null) }
    }

    val platformKeyboardShortcutsEnabled = !isAnyOverlayVisible && !playerControlsLocked
    LaunchedEffect(playerController, platformKeyboardShortcutsEnabled) {
        playerController?.setKeyboardShortcutsEnabled(platformKeyboardShortcutsEnabled)
    }

    val subtitlePreferenceKey = listOf(
        playerSettingsUiState.preferredSubtitleLanguage,
        playerSettingsUiState.secondaryPreferredSubtitleLanguage.orEmpty(),
        subtitleStyle.useForcedSubtitles,
    ).joinToString("|")
    var lastSubtitlePreferenceKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(
        playerController,
        subtitlePreferenceKey,
        preferredSubtitleSelectionApplied,
        selectedSubtitleIndex,
        selectedAddonSubtitleId,
        useCustomSubtitles,
    ) {
        val controller = playerController ?: return@LaunchedEffect
        val preferenceChanged = lastSubtitlePreferenceKey != null &&
            lastSubtitlePreferenceKey != subtitlePreferenceKey
        lastSubtitlePreferenceKey = subtitlePreferenceKey

        controller.applySubtitlePreferences(
            preferredLanguage = playerSettingsUiState.preferredSubtitleLanguage,
            secondaryPreferredLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
            useForcedSubtitles = subtitleStyle.useForcedSubtitles,
            autoSelectionApplied = preferredSubtitleSelectionApplied,
            hasActiveSubtitle = selectedSubtitleIndex >= 0 || selectedAddonSubtitleId != null,
            useCustomSubtitles = useCustomSubtitles,
        )

        if (preferenceChanged) {
            preferredSubtitleSelectionApplied = false
            refreshTracks()
        }
    }

    LaunchedEffect(
        playerController,
        playerControllerSourceUrl,
        activeSourceUrl,
        activePlaybackSourceUrl,
        p2pResolvedSourceUrl,
        title,
        activeStreamTitle,
        activeLogo,
        activeSeasonNumber,
        activeEpisodeNumber,
        activeEpisodeTitle,
        poster,
        background,
    ) {
        val controller = playerController ?: return@LaunchedEffect
        val controllerSourceUrl = playerControllerSourceUrl ?: return@LaunchedEffect
        if (!isControllerOnActivePlayback(controllerSourceUrl)) return@LaunchedEffect
        if (!allowsProgressWrite(persistsProgress)) {
            controller.clearNowPlayingInfo()
            return@LaunchedEffect
        }
        controller.updateNowPlayingMetadata(buildNowPlayingInfo())
    }

    LaunchedEffect(
        activeSourceUrl,
        p2pResolvedSourceUrl,
        addonSubtitleFetchKey,
        networkCondition,
    ) {
        if (activeSourceUrl.startsWith("file:") && externalSubtitles.isNotEmpty()) return@LaunchedEffect
        val fetchKey = addonSubtitleFetchKey ?: return@LaunchedEffect
        // For P2P streams, wait until the local engine URL is available before
        // triggering the fetch so that hash computation can use the real HTTP URL.
        if (activeTorrentInfoHash != null && p2pResolvedSourceUrl == null) return@LaunchedEffect
        val effectiveFetchKey = if (p2pResolvedSourceUrl != null) "$fetchKey|p2p" else fetchKey
        if (autoFetchedAddonSubtitlesForKey == effectiveFetchKey) return@LaunchedEffect
        // Offline: leave the key unset so the fetch can run once connectivity returns.
        if (isNetworkOffline()) return@LaunchedEffect
        autoFetchedAddonSubtitlesForKey = effectiveFetchKey
        fetchAddonSubtitlesForActiveItem()
    }

    LaunchedEffect(
        playerController, playerControllerSourceUrl, activeSourceUrl, activePlaybackSourceUrl, p2pResolvedSourceUrl,
        preferredAudioLanguageTargets,
    ) {
        if (isControllerOnActivePlayback()) {
            applyPreferredAudioTrack(preferredAudioLanguageTargets)
        }
    }

    LaunchedEffect(activeSourceUrl, preferredAudioLanguageTargets) {
        refreshServerTracks()
        applyPreferredServerAudioTrack()
    }

    LaunchedEffect(playbackSnapshot.isLoading, playerController, preferredAudioLanguageTargets) {
        if (!playbackSnapshot.isLoading && playerController != null) {
            refreshTracks()
        }
    }

    LaunchedEffect(
        playbackSnapshot.isLoading,
        playerController,
        playerControllerSourceUrl,
        activeSourceUrl,
        playerSettingsUiState.androidExoChaptersEnabled,
    ) {
        clearChaptersIfDisabled()
        if (!playbackSnapshot.isLoading) loadChaptersIfNeeded()
    }

    LaunchedEffect(playbackSnapshot.chapters, chapters, playerSettingsUiState.androidExoChaptersEnabled) {
        adoptSnapshotChaptersIfNeeded()
    }

    LaunchedEffect(
        playerController,
        playbackSnapshot.isLoading,
        preferredAudioSelectionApplied,
        preferredSubtitleSelectionApplied,
        trackPreferenceRestoreApplied,
        addonSubtitles,
        isLoadingAddonSubtitles,
    ) {
        if (playerController == null || playbackSnapshot.isLoading) {
            return@LaunchedEffect
        }
        if (trackPreferenceRestoreApplied && preferredAudioSelectionApplied && preferredSubtitleSelectionApplied) {
            return@LaunchedEffect
        }

        repeat(10) {
            refreshTracks()
            if (trackPreferenceRestoreApplied && preferredAudioSelectionApplied && preferredSubtitleSelectionApplied) {
                return@LaunchedEffect
            }
            delay(300)
        }
    }

    // A pending carry-over is re-applied as soon as the new engine exposes its tracks; poll
    // briefly, then give up.
    LaunchedEffect(playerController, pendingEngineSwitchTrackPreference != null) {
        if (playerController == null) return@LaunchedEffect
        if (pendingEngineSwitchTrackPreference == null) return@LaunchedEffect

        repeat(30) {
            refreshTracks()
            if (pendingEngineSwitchTrackPreference == null) return@LaunchedEffect
            delay(300)
        }

        val pending = pendingEngineSwitchTrackPreference ?: return@LaunchedEffect
        if (playerController?.playbackEngine == pending.targetEngine) {
            // The new engine never exposed a matching track: drop the carry-over.
            clearPendingEngineSwitchTrackPreference()
        }    }

    LaunchedEffect(
        playerController,
        playerControllerSourceUrl,
        activeSourceUrl,
        activePlaybackSourceUrl,
        p2pResolvedSourceUrl,
        playbackSnapshot.isLoading,
        playbackSnapshot.durationMs,
        activeInitialPositionMs,
        activeInitialProgressFraction,
        initialSeekApplied,
    ) {
        val controller = playerController ?: return@LaunchedEffect
        val controllerSourceUrl = playerControllerSourceUrl ?: return@LaunchedEffect
        if (!isControllerOnActivePlayback(controllerSourceUrl)) return@LaunchedEffect
        if (initialSeekApplied || playbackSnapshot.isLoading) return@LaunchedEffect
        if (isPartySynced && PartyRepository.playback.value != null) {
            initialSeekApplied = true
            return@LaunchedEffect
        }

        val progressFraction = activeInitialProgressFraction
            ?.takeIf { it > 0f }
            ?.coerceIn(0f, 1f)
        val targetPositionMs = when {
            activeInitialPositionMs > 0L -> activeInitialPositionMs
            progressFraction != null && playbackSnapshot.durationMs > 0L -> {
                (playbackSnapshot.durationMs.toDouble() * progressFraction.toDouble()).toLong()
            }
            progressFraction != null -> return@LaunchedEffect
            else -> 0L
        }
        if (targetPositionMs <= 0L) {
            initialSeekApplied = true
            return@LaunchedEffect
        }

        noteSeek()
        controller.seekTo(targetPositionMs)
        initialSeekApplied = true
    }

    BindPlayerUiVisibilityEffects()
    BindPlayerMetadataAndSkipEffects()
    BindPartyEffects()
    BindDiscordRpcEffects()

    DisposableEffect(activeSourceUrl) {
        val effectSourceUrl = activeSourceUrl
        val partialDownloadId = partialPlaybackDownloadId(effectSourceUrl)
        if (partialDownloadId != null) PartialPlaybackLease.acquire(partialDownloadId)
        onDispose {
            releaseServerPlayback(effectSourceUrl)
            if (partialDownloadId != null) {
                PartialPlaybackLease.release(partialDownloadId)
                DownloadsRepository.onPartialPlaybackClosed(partialDownloadId)
            }
        }
    }

    DisposableEffect(playbackSession.videoId, activeSourceUrl, activeSourceAudioUrl) {
        val effectVideoId = playbackSession.videoId
        val effectSourceUrl = activeSourceUrl
        val effectSourceAudioUrl = activeSourceAudioUrl
        FinishedDownloadDeletion.playbackOpened(effectVideoId, effectSourceUrl, persistsProgress)
        onDispose {
            onPlaybackSourceDisposed(effectVideoId, effectSourceUrl, effectSourceAudioUrl)
        }
    }

    LaunchedEffect(playbackSnapshot.isPlaying) {
        if (playbackSnapshot.isPlaying) PerformanceMetrics.recordPlaybackStarted()
    }

    DisposableEffect(Unit) {
        PlayerStreamsRepository.pauseSearchForPlayback()
        PlaybackQuietMode.playerOpened()
        onDispose {
            PlaybackQuietMode.playerClosed()
            onPlayerRouteDisposed()
            args.launchId?.let { launchId ->
                PlayerLaunchStore.update(launchId) { currentLaunch(it) }
                // The player is gone: release the launch payload once the restore window has
                // passed (a configuration change recreates the composition and the player
                // re-reads its launch from the store).
                PlayerLaunchStore.markExited(launchId)
            }
            playerController?.clearNowPlayingInfo()
            P2pStreamingEngine.shutdown()
            cancelNextEpisodePreload()
            PlayerStreamsRepository.clearAll()
            SkipIntroRepository.clearCache()
        }
    }
}

@Composable
private fun PlayerScreenRuntime.BindPlayerUiVisibilityEffects() {
    val folded = LocalFold.current != null

    // The engine publishes a playback snapshot every 250ms. Reading the raw snapshot here
    // would restart these effects four times per second, so the state flags and the position
    // are read through a derived state that only changes when the values really change.
    val snapshotSignals by remember(this) {
        derivedStateOf {
            val snapshot = playbackSnapshot
            PlayerSnapshotSignals(
                isPlaying = snapshot.isPlaying,
                isLoading = snapshot.isLoading,
                isEnded = snapshot.isEnded,
                durationMs = snapshot.durationMs,
                positionSeconds = snapshot.positionMs / 1000L,
            )
        }
    }

    LaunchedEffect(
        folded,
        controlsVisible,
        controlsActivityTick,
        isScrubbingTimeline,
        isTouchingTimeline,
        snapshotSignals.isPlaying,
        snapshotSignals.isLoading,
        showParentalGuide,
        showSpeedPanel,
        errorMessage,
    ) {
        if (
            !controlsVisible ||
            isScrubbingTimeline ||
            isTouchingTimeline ||
            !playbackSnapshot.isPlaying ||
            playbackSnapshot.isLoading ||
            showParentalGuide ||
            showSpeedPanel ||
            errorMessage != null
        ) {
            return@LaunchedEffect
        }
        delay(if (folded) 5000 else 3500)
        controlsVisible = false
    }

    LaunchedEffect(playerControlsLocked, lockedOverlayVisible) {
        if (!playerControlsLocked || !lockedOverlayVisible) return@LaunchedEffect
        delay(PlayerLockedOverlayDurationMs)
        lockedOverlayVisible = false
    }

    LaunchedEffect(
        playerSettingsUiState.pauseOverlayEnabled,
        snapshotSignals.isPlaying,
        snapshotSignals.isLoading,
        snapshotSignals.durationMs,
        errorMessage,
    ) {
        pausedOverlayVisible = false
        if (!playerSettingsUiState.pauseOverlayEnabled || playbackSnapshot.isPlaying || playbackSnapshot.isLoading || playbackSnapshot.durationMs <= 0L || errorMessage != null) {
            return@LaunchedEffect
        }
        delay(5000)
        pausedOverlayVisible = true
    }

    // Keyed on the position in whole seconds: the work below (server progress reporting, the
    // scrobble state machine and the 60s persist tick) does not need the 250ms resolution of
    // the engine snapshot, and restarting it that often steals time from the decoder.
    LaunchedEffect(
        snapshotSignals.positionSeconds,
        snapshotSignals.isPlaying,
        snapshotSignals.isLoading,
        snapshotSignals.isEnded,
        snapshotSignals.durationMs,
    ) {
        snapshotFlow { playbackClock.positionMs }.collect {
            onPlaybackProgressSample()
        }
    }
}

internal fun PlayerScreenRuntime.releaseServerPlayback(url: String) {
    ServerPlayback.stop(url, persistsProgress = persistsProgress)
}

/** Leaving the player. Submit mode does not write the live playhead back onto the launch. */
internal fun PlayerScreenRuntime.onPlayerRouteDisposed() {
    args.launchId?.let { launchId ->
        if (allowsProgressWrite(persistsProgress)) {
            PlayerLaunchStore.update(launchId) { currentLaunch(it) }
        }
    }
}

internal fun PlayerScreenRuntime.onPlaybackSourceDisposed(
    effectVideoId: String,
    effectSourceUrl: String,
    effectSourceAudioUrl: String?,
) {
    val sameVideo = playbackSession.videoId == effectVideoId
    val sameSource = activeSourceUrl == effectSourceUrl &&
        activeSourceAudioUrl == effectSourceAudioUrl
    if (sameVideo && sameSource) {
        flushWatchProgress()
        FinishedDownloadDeletion.playbackEnded(effectVideoId, persistsProgress)
    } else if (!sameVideo) {
        FinishedDownloadDeletion.playbackEnded(effectVideoId, persistsProgress)
    } else {
        FinishedDownloadDeletion.playbackSourceChanged(effectVideoId, activeSourceUrl, persistsProgress)
    }
}

/** Clock tick the player uses for pause, background, finish, periodic save, and scrobble. */
internal fun PlayerScreenRuntime.onPlaybackProgressSample() {
    ServerPlayback.onPlaybackSnapshot(
        url = activeSourceUrl,
        positionMs = playbackClock.positionMs,
        isPlaying = playbackSnapshot.isPlaying,
        isLoading = playbackSnapshot.isLoading,
        isEnded = playbackSnapshot.isEnded,
        persistsProgress = persistsProgress,
    )
    if (playbackSnapshot.isEnded) {
        flushWatchProgress(TrackingScrobbleAction.STOP)
        previousIsPlaying = false
        pendingSeekScrobbleRestart = false
        return
    }

    if (previousIsPlaying && !playbackSnapshot.isPlaying && !playbackSnapshot.isLoading) {
        pendingSeekScrobbleRestart = false
        flushWatchProgress(TrackingScrobbleAction.PAUSE)
    }

    if (playbackSnapshot.isPlaying && pendingSeekScrobbleRestart) {
        pendingSeekScrobbleRestart = false
        if (hasRequestedScrobbleStartForCurrentItem) {
            emitTrackingSeekScrobbleStart()
        } else {
            emitTrackingScrobbleStart()
        }
    } else if (!previousIsPlaying && playbackSnapshot.isPlaying) {
        emitTrackingScrobbleStart()
    }

    if (!playbackSnapshot.isLoading) {
        previousIsPlaying = playbackSnapshot.isPlaying
    }
    if (playbackSnapshot.isPlaying) {
        persistPlaybackProgressTick()
        val playing = playbackSession
        SmartDownloads.onPlaybackSample(
            parentMetaId = playing.parentMetaId,
            parentMetaType = playing.parentMetaType,
            videoId = playing.videoId,
            seasonNumber = playing.seasonNumber,
            episodeNumber = playing.episodeNumber,
            positionMs = playbackClock.positionMs,
            durationMs = playbackSnapshot.durationMs,
            playbackSpeed = playbackSnapshot.playbackSpeed.toDouble(),
            mediaBitrateBps = playbackSnapshot.mediaBitrateBps,
            persistsProgress = persistsProgress,
        )
    }
}

@Composable
private fun PlayerScreenRuntime.BindPlayerMetadataAndSkipEffects() {
    val networkCondition = rememberNetworkCondition()
    val shuffleProfile by remember {
        EpisodeShuffleRepository.ensureLoaded()
        EpisodeShuffleRepository.uiState
    }.collectAsStateWithLifecycle()
    val shuffleSettings = remember(shuffleProfile, profileId, parentMetaId, parentMetaType) {
        val profile = if (profileId == com.nuvio.app.features.profiles.ProfileRepository.activeProfileId) shuffleProfile
            else EpisodeShuffleRepository.readProfile(profileId)
        profile.settings(parentMetaId, parentMetaType)
    }

    LaunchedEffect(
        activeVideoId,
        activeSeasonNumber,
        activeEpisodeNumber,
        parentMetaId,
        parentMetaType,
        playerSettingsUiState.showParentalGuide,
        networkCondition,
    ) {
        parentalWarnings = emptyList()
        showParentalGuide = false
        parentalGuideHasShown = false
        playbackStartedForParentalGuide = false

        // The guide is only shown when the setting is enabled, and it needs the network.
        if (!playerSettingsUiState.showParentalGuide) return@LaunchedEffect
        if (networkCondition != NetworkCondition.Online) return@LaunchedEffect

        val imdbId = resolveParentalGuideImdbId() ?: return@LaunchedEffect
        val guide = ParentalGuideRepository.getParentalGuide(imdbId) ?: return@LaunchedEffect
        parentalWarnings = buildParentalWarnings(guide, parentalGuideLabels)

        if (playbackSnapshot.isPlaying) {
            tryShowParentalGuide()
        }
    }

    LaunchedEffect(playbackSnapshot.isPlaying, parentalWarnings) {
        if (playbackSnapshot.isPlaying) {
            tryShowParentalGuide()
        }
    }

    LaunchedEffect(
        activeVideoId, parentMetaId, parentMetaType, contentType, activeSeasonNumber, activeEpisodeNumber,
        playerSettingsUiState.skipIntroEnabled, networkCondition,
    ) {
        skipIntervals = emptyList()
        refreshSegmentClocks(emptyList())
        apiSkipIntervals = emptyList()
        clearChapterSkipState()
        autoSkippedIntervals.clear()
        lastManualSkipSeekPositions = null
        activeSkipInterval = null
        skipIntervalDismissed = false
        skipFeedbackDelayJob?.cancel()
        skipFeedbackInterval = null
        skipFeedbackNudging = false
        showNextEpisodeCard = false
        nextEpisodeCardDismissed = false
        cancelNextEpisodeAutoPlay()

        val season = activeSeasonNumber
        val episode = activeEpisodeNumber
        val vid = activeVideoId
        val durationMs = playbackSnapshot.durationMs
        if (!playerSettingsUiState.skipIntroEnabled) return@LaunchedEffect
        if ((contentType ?: parentMetaType).equals("movie", ignoreCase = true)) {
            val api = filterSkipSources(
                SkipIntroRepository.getMovieSkipIntervals(parentMetaId, vid, durationMs = durationMs),
            )
            apiSkipIntervals = api
            recomputeMergedSkipIntervals()
            return@LaunchedEffect
        }
        if (season == null || episode == null || vid == null) return@LaunchedEffect
        // Skip intervals come from remote providers: pointless to look them up without a network.
        if (isNetworkOffline()) return@LaunchedEffect

        launch {
            // Earlier TheIntroDB submissions become this phone's marks (once per show per session).
            val imported = runCatching {
                com.nuvio.app.features.player.skip.SkipMarkImport.importForShow(parentMetaId, vid, isMovie = false)
            }.getOrDefault(0)
            if (imported > 0) {
                skipIntervals = withOnDeviceSkips(
                    skipIntervals.filter { !com.nuvio.app.features.analysis.SkipLearning.isUserProvider(it.provider) },
                )
            }
        }
        launch {
            // Bounded so a slow provider never stalls the player.
            val resolved = withTimeoutOrNull(SKIP_INTERVAL_RESOLVE_TIMEOUT_MS) {
                val imdbFromContent = parentMetaId.takeIf { it.startsWith("tt") }
                    ?: (metaUiState.meta ?: playerMeta)
                        ?.takeIf { it.id == parentMetaId }
                        ?.imdbId
                        ?.takeIf { it.startsWith("tt") }
                when {
                    vid.startsWith("mal:") -> {
                        val malId = vid.removePrefix("mal:").substringBefore(':')
                        SkipIntroRepository.getSkipIntervalsForMal(
                            malId = malId,
                            episode = episode,
                            imdbId = imdbFromContent,
                            imdbSeason = season,
                            imdbEpisode = episode,
                            durationMs = durationMs,
                        )
                    }
                    vid.startsWith("kitsu:") -> {
                        val kitsuId = vid.removePrefix("kitsu:").substringBefore(':')
                        SkipIntroRepository.getSkipIntervalsForKitsu(
                            kitsuId = kitsuId,
                            episode = episode,
                            imdbId = imdbFromContent,
                            imdbSeason = season,
                            imdbEpisode = episode,
                            durationMs = durationMs,
                        )
                    }
                    // A TMDB-keyed show (tmdb:…) still gets TheIntroDB/IntroDB times via its IMDb id.
                    else -> SkipIntroRepository.getSkipIntervals(
                        imdbId = vid.substringBefore(':').takeIf { it.startsWith("tt") } ?: imdbFromContent
                            ?: com.nuvio.app.features.player.skip.resolveSkipImdbId(parentMetaId, vid, "tv"),
                        season = season,
                        episode = episode,
                        durationMs = durationMs,
                    )
                }
            }
            val filtered = filterSkipSources(resolved ?: return@launch)
            apiSkipIntervals = filtered
            recomputeMergedSkipIntervals()
        }
    }

    LaunchedEffect(
        playbackSnapshot.durationMs, playbackSnapshot.isPlaying, skipIntervals,
        playerSettingsUiState.autoSkipSegmentTypes, parentMetaId,
        playerSettingsUiState.skipIntroEnabled, isScrubbingTimeline, initialSeekApplied,
        lastManualSkipSeekPositions,
    ) {
        recomputeChapterSkipIntervalsIfNeeded()
        if (skipIntervals.isEmpty()) {
            activeSkipInterval = null
            return@LaunchedEffect
        }
        val autoSkipTypes = ShowPlaybackMemory.autoSkip(parentMetaId).segmentTypes(playerSettingsUiState.autoSkipSegmentTypes)
        snapshotFlow { playbackClock.positionMs }.collect { positionMs ->
            val positionSec = positionMs / 1000.0
            lastManualSkipSeekPositions?.let { (fromMs, toMs) ->
                autoSkippedIntervals += skipIntervals.intervalsAtSeekPositions(fromMs, toMs)
            }
            val eligible = skipIntervals.filter { interval ->
                // The button shows exactly from the segment start. No pre-roll.
                interval.isEligibleForSkipButton(positionSec) &&
                    interval.internalSkipAction(skipIntervals, playbackSnapshot.durationMs) != null
            }
            val inside = eligible.firstOrNull { positionSec >= it.startTime && positionSec < it.endTime }
            val current = inside ?: eligible.firstOrNull()
            if (current != activeSkipInterval) {
                val keepDismissed = current != null && activeSkipInterval != null &&
                    segmentCategory(activeSkipInterval?.type ?: "") == segmentCategory(current.type)
                activeSkipInterval = current
                if (current != null && !keepDismissed) skipIntervalDismissed = false
                if (current == null) skipIntervalDismissed = false
            }
            val controller = playerController
            if (current != null && controller != null &&
                isControllerOnActivePlayback() &&
                playerSettingsUiState.skipIntroEnabled && playbackSnapshot.isPlaying &&
                !isScrubbingTimeline && initialSeekApplied && !isPartyControlLocked &&
                positionSec >= current.startTime &&
                current.shouldAutoSkip(autoSkipTypes) &&
                current !in autoSkippedIntervals &&
                !SkipUndo.blocksAutoSkip(skipUndo, positionMs)
            ) {
                val durationMs = playbackSnapshot.durationMs
                val rawMs = current.internalSkipAction(skipIntervals, durationMs)?.targetMs ?: return@collect
                val seekPositionMs = if (durationMs > 0L) rawMs.coerceAtMost(durationMs - 1) else rawMs
                val notification = current.autoSkipNotificationMessage(seekPositionMs)
                autoSkippedIntervals.add(current)
                val fromMs = (positionSec * 1000).toLong()
                com.nuvio.app.features.stats.WatchStats.recordSkip(seekPositionMs - fromMs, persistsProgress)
                offerSkipUndo(fromMs, seekPositionMs - fromMs, current)
                noteSeek()
                controller.seekTo(seekPositionMs)
                scheduleProgressSyncAfterSeek()
                publishPartyPlayback(positionMs = seekPositionMs)
                skipIntervalDismissed = true
                notification?.let { NuvioToastController.show(it, AUTO_SKIP_NOTIFICATION_DURATION_MS) }
            }
        }
    }

    // Smart downloads: the credits start of this episode (own mark > TheIntroDB > on-device).
    LaunchedEffect(skipIntervals, activeVideoId) {
        val videoId = activeVideoId ?: return@LaunchedEffect
        com.nuvio.app.features.downloads.SmartDownloads.reportCredits(
            videoId,
            smartDownloadCreditsStartMs(skipIntervals),
        )
    }

    LaunchedEffect(
        playerMetaVideos,
        shuffleSettings,
        profileId,
        parentMetaId,
        activeSeasonNumber,
        activeEpisodeNumber,
        watchProgressUiState.entries,
        watchedUiState.watchedKeys,
    ) {
        if (!isSeries || playerMetaVideos.isEmpty()) {
            nextEpisodeInfo = null
            return@LaunchedEffect
        }
        val curSeason = activeSeasonNumber ?: return@LaunchedEffect
        val curEpisode = activeEpisodeNumber ?: return@LaunchedEffect
        val nextVideo = if (shuffleSettings.enabled) {
            EpisodeShuffleRepository.shuffle.select(
                profileId, parentMetaId, playerMetaVideos, shuffleSettings.includeWatched,
                watchedShuffleEpisodes(parentMetaId, parentMetaType, playerMetaVideos, watchedUiState.watchedKeys),
                shuffleEpisodeProgress(parentMetaId, watchProgressUiState.entries),
                ShuffleSurface.PLAYBACK, current = curSeason to curEpisode,
            )
        } else {
            EpisodeShuffleRepository.shuffle.clearSelection(profileId, parentMetaId, ShuffleSurface.PLAYBACK)
            PlayerNextEpisodeRules.resolveNextEpisode(
                videos = playerMetaVideos,
                currentSeason = curSeason,
                currentEpisode = curEpisode,
            )
        }
        val nextSeason = nextVideo?.season
        val nextEpisode = nextVideo?.episode
        nextEpisodeInfo = if (nextVideo != null && nextSeason != null && nextEpisode != null) {
            val playbackVideoId = buildPlaybackVideoId(
                parentMetaId = parentMetaId,
                seasonNumber = nextSeason,
                episodeNumber = nextEpisode,
                fallbackVideoId = nextVideo.id,
            )
            val isWatched = watchProgressUiState.progressForVideo(
                videoId = playbackVideoId,
                parentMetaId = parentMetaId,
                seasonNumber = nextSeason,
                episodeNumber = nextEpisode,
            )?.isEffectivelyCompleted == true || WatchingState.isEpisodeWatched(
                watchedKeys = watchedUiState.watchedKeys,
                metaType = parentMetaType,
                metaId = parentMetaId,
                episode = nextVideo,
            )
            NextEpisodeInfo(
                videoId = nextVideo.id,
                season = nextSeason,
                episode = nextEpisode,
                title = nextVideo.title,
                thumbnail = nextVideo.thumbnail,
                overview = nextVideo.overview,
                released = nextVideo.released,
                hasAired = PlayerNextEpisodeRules.hasEpisodeAired(nextVideo.released),
                isWatched = isWatched,
                unairedMessage = if (!PlayerNextEpisodeRules.hasEpisodeAired(nextVideo.released)) {
                    "$airsPrefix ${nextVideo.released ?: tbaLabel}"
                } else null,
            )
        } else null
    }

    LaunchedEffect(playbackSnapshot.isEnded) {
        if (playbackSnapshot.isEnded && nextEpisodeCardDismissed &&
            playerSettingsUiState.streamAutoPlayNextEpisodeEnabled &&
            nextEpisodeInfo?.hasAired == true
        ) {
            nextEpisodeCardDismissed = false
        }
    }

    LaunchedEffect(
        activePlaybackKey,
        playbackSnapshot,
        playbackSnapshotKey,
        initialSeekApplied,
        isScrubbingTimeline,
        errorMessage,
        nextEpisodeInfo,
        skipIntervals,
        playerSettingsUiState.nextEpisodeThresholdMode,
        playerSettingsUiState.nextEpisodeThresholdPercent,
        playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
        playerSettingsUiState.streamAutoPlayNextEpisodeEnabled,
        nextEpisodeCardDismissed,
        blocksAutoplay,
    ) {
        snapshotFlow { playbackClock.positionMs }.collect {
            if (blocksAutoplay) {
                if (showNextEpisodeCard || nextEpisodeAutoPlayJob != null) {
                    cancelNextEpisodeAutoPlay()
                    showNextEpisodeCard = false
                }
                return@collect
            }
            val shouldShow = nextEpisodeInfo != null && !nextEpisodeCardDismissed && isAtNextEpisodeThreshold()
            if (nextEpisodeAutoPlayAutomatic &&
                (!shouldShow || !playerSettingsUiState.streamAutoPlayNextEpisodeEnabled)
            ) {
                cancelNextEpisodeAutoPlay()
            }
            if (shouldShow && !showNextEpisodeCard) {
                showNextEpisodeCard = true
                if (playerSettingsUiState.streamAutoPlayNextEpisodeEnabled && nextEpisodeInfo?.hasAired == true && !isPartyControlLocked &&
                    // "End of episode" sleep: stop here instead of rolling on.
                    PlayerSleepTimer.choice.value != SleepTimerChoice.EndOfEpisode
                ) {
                    playNextEpisode(automatic = true)
                }
            } else if (!shouldShow) {
                showNextEpisodeCard = false
            }
        }
    }

    LaunchedEffect(
        playbackSnapshot.durationMs,
        playbackSnapshot.isEnded,
        playerMeta?.moreLikeThis,
        watchedUiState.watchedKeys,
        movieRecommendationDismissedStage,
        playerSettingsUiState.movieRecommendationsEnabled,
    ) {
        snapshotFlow { playbackClock.positionMs }.collect { positionMs ->
            if (!isMoviePlayback || !playerSettingsUiState.movieRecommendationsEnabled || movieRecommendationCandidates.isEmpty()) {
                showMovieRecommendationCard = false
                movieRecommendationStage = 0
                return@collect
            }
            val stage = PlayerNextEpisodeRules.movieRecommendationStage(
                positionMs = positionMs,
                durationMs = playbackSnapshot.durationMs,
                isEnded = playbackSnapshot.isEnded,
            )
            if (stage == 0 && movieRecommendationDismissedStage != 0) {
                movieRecommendationDismissedStage = 0
            }
            movieRecommendationStage = stage
            showMovieRecommendationCard = stage > movieRecommendationDismissedStage
        }
    }
    BindSkipUndoEffects()
}

private fun PlayerScreenRuntime.buildNowPlayingInfo(): PlayerNowPlayingInfo {
    val isEpisode = activeSeasonNumber != null && activeEpisodeNumber != null
    return PlayerNowPlayingInfo(
        title = title.ifBlank { activeStreamTitle },
        subtitle = buildNowPlayingSubtitle(
            isEpisode = isEpisode,
            seasonNumber = activeSeasonNumber,
            episodeNumber = activeEpisodeNumber,
            episodeTitle = activeEpisodeTitle,
        ),
        artworkUrl = if (isLiveTvPlayback) {
            firstNonBlankUrl(activeLogo, poster, background)
        } else {
            firstNonBlankUrl(poster, background)
        },
    )
}

private fun buildNowPlayingSubtitle(
    isEpisode: Boolean,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
): String? {
    if (!isEpisode) return null

    val episodeParts = buildList {
        localizedSeasonEpisodeCode(seasonNumber, episodeNumber)?.let { add(it) }
        episodeTitle?.takeIf { it.isNotBlank() }?.let { add(it) }
    }

    return when (episodeParts.size) {
        0 -> null
        1 -> episodeParts.first()
        else -> "${episodeParts[0]} - ${episodeParts[1]}"
    }
}

private fun firstNonBlankUrl(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()

internal fun PlayerScreenRuntime.removeFailedStreamFromCache() {
    val currentVideoId = activeVideoId ?: return
    val cacheKey = StreamLinkCacheRepository.contentKey(
        type = contentType ?: parentMetaType,
        videoId = currentVideoId,
        parentMetaId = parentMetaId,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
    StreamLinkCacheRepository.remove(cacheKey)
}

internal fun PlayerScreenRuntime.tryRefreshCredentialedSourceAfterError(message: String?): Boolean {
    if (ServerStreams.isServerSourceId(activeProviderAddonId)) return retryServerSourceAfterError(message)
    val failedUrl = activeSourceUrl
    if (!failedUrl.hasLikelyExpiringPlaybackCredentials()) return false
    if (credentialRefreshJob?.isActive == true) return true
    if (credentialRefreshAttemptedSourceUrl == failedUrl) return false

    val currentVideoId = activeVideoId ?: return false
    credentialRefreshAttemptedSourceUrl = failedUrl
    removeFailedStreamFromCache()

    val savedPositionMs = playbackClock.positionMs.coerceAtLeast(0L)
    val expectedProviderAddonId = activeProviderAddonId
    val expectedProviderName = activeProviderName
    val expectedStreamTitle = activeStreamTitle
    val expectedBingeGroup = currentStreamBingeGroup
    val type = contentType ?: parentMetaType
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber

    errorMessage = null
    controlsVisible = !playerControlsLocked

    credentialRefreshJob = scope.launch {
        try {
            PlayerStreamsRepository.loadSources(
                type = type,
                videoId = currentVideoId,
                season = season,
                episode = episode,
                forceRefresh = true,
            )

            var refreshedStream: StreamItem? = null
            var pollCount = 0
            while (pollCount < CREDENTIAL_REFRESH_POLL_COUNT && refreshedStream == null) {
                val state = PlayerStreamsRepository.sourceState.value
                refreshedStream = findCredentialRefreshCandidate(
                    streams = state.groups.flatMap { it.streams },
                    failedUrl = failedUrl,
                    expectedProviderAddonId = expectedProviderAddonId,
                    expectedProviderName = expectedProviderName,
                    expectedStreamTitle = expectedStreamTitle,
                    expectedBingeGroup = expectedBingeGroup,
                )
                if (
                    refreshedStream != null ||
                    state.emptyStateReason != null ||
                    (!state.isAnyLoading && state.groups.isNotEmpty())
                ) {
                    break
                }
                delay(CREDENTIAL_REFRESH_POLL_INTERVAL_MS)
                pollCount++
            }

            val stream = refreshedStream
            if (stream == null) {
                errorMessage = message
                controlsVisible = !playerControlsLocked
                return@launch
            }

            val refreshedUrl = stream.playableDirectUrl
            if (refreshedUrl.isNullOrBlank() || refreshedUrl == failedUrl) {
                errorMessage = message
                controlsVisible = !playerControlsLocked
                return@launch
            }

            flushWatchProgress()
            stopActiveP2pStream()
            activeSourceUrl = refreshedUrl
            activeSourceAudioUrl = null
            activeSourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
            activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
            activeStreamType = stream.streamType
            activeStreamTitle = stream.streamLabel
            activeStreamSubtitle = stream.streamSubtitle
            activeStreamFilename = streamPlaybackFilename(stream, refreshedUrl)
            activeProviderName = stream.addonName
            activeProviderAddonId = stream.addonId
            activeStream = stream
            currentStreamBingeGroup = stream.behaviorHints.bingeGroup
            activeSubtitleExtras = SubtitleRequestExtras.from(stream.behaviorHints, refreshedUrl)
            currentStreamFilename = stream.behaviorHints.filename
            currentStreamInfoHash = stream.infoHash
            activeInitialPositionMs = savedPositionMs
            activeInitialProgressFraction = null
            showSourcesPanel = false
            controlsVisible = true
        } finally {
            PlayerStreamsRepository.stopSourcesLoading()
        }
    }
    return true
}

private fun PlayerScreenRuntime.retryServerSourceAfterError(message: String?): Boolean {
    if (credentialRefreshJob?.isActive == true) return true
    val failedUrl = activeSourceUrl
    if (credentialRefreshAttemptedSourceUrl == failedUrl) return false
    credentialRefreshAttemptedSourceUrl = failedUrl
    val savedPositionMs = playbackClock.positionMs.coerceAtLeast(0L)
    errorMessage = null
    credentialRefreshJob = scope.launch {
        val playback = ServerPlayback.fallback(failedUrl)
        if (playback == null) {
            errorMessage = message
            controlsVisible = !playerControlsLocked
            return@launch
        }
        externalSubtitles = playback.subtitles
        activeSourceUrl = playback.url
        activeSourceHeaders = playback.headers
        activeInitialPositionMs = savedPositionMs
        activeInitialProgressFraction = null
    }
    return true
}

private fun findCredentialRefreshCandidate(
    streams: List<StreamItem>,
    failedUrl: String,
    expectedProviderAddonId: String?,
    expectedProviderName: String,
    expectedStreamTitle: String,
    expectedBingeGroup: String?,
): StreamItem? =
    streams
        .asSequence()
        .mapNotNull { stream ->
            val refreshedUrl = stream.playableDirectUrl?.takeIf { it.isNotBlank() && it != failedUrl }
                ?: return@mapNotNull null
            val providerMatches = if (!expectedProviderAddonId.isNullOrBlank()) {
                stream.addonId == expectedProviderAddonId
            } else {
                stream.addonName == expectedProviderName
            }
            if (!providerMatches) return@mapNotNull null

            var score = 100
            if (stream.streamLabel == expectedStreamTitle) score += 40
            if (!expectedBingeGroup.isNullOrBlank() && stream.behaviorHints.bingeGroup == expectedBingeGroup) {
                score += 20
            }
            if (refreshedUrl.hasLikelyExpiringPlaybackCredentials()) score += 5
            score to stream
        }
        .maxByOrNull { (score, _) -> score }
        ?.second

/** The button shows exactly from the segment start to its end; no pre-roll, so the start is visible. */
private fun SkipInterval.isEligibleForSkipButton(positionSec: Double): Boolean =
    positionSec >= startTime && positionSec < endTime

internal fun PlayerScreenRuntime.isControllerOnActivePlayback(
    controllerSourceUrl: String? = playerControllerSourceUrl,
): Boolean =
    controllerSourceUrl != null && (
        controllerSourceUrl == activeSourceUrl ||
            controllerSourceUrl == activePlaybackSourceUrl ||
            controllerSourceUrl == p2pResolvedSourceUrl
        )
private const val CREDENTIAL_REFRESH_POLL_COUNT = 30
private const val CREDENTIAL_REFRESH_POLL_INTERVAL_MS = 500L

/** Credits/outro start for smart downloads, by source: own mark, then TheIntroDB, then on-device. */
internal fun smartDownloadCreditsStartMs(intervals: List<com.nuvio.app.features.player.skip.SkipInterval>): Long? {
    fun rank(provider: String): Int = when {
        com.nuvio.app.features.analysis.SkipLearning.isUserProvider(provider) -> 0
        provider == com.nuvio.app.features.player.skip.TheIntroDb.PROVIDER -> 1
        provider == ON_DEVICE_SKIP_PROVIDER || provider == CHAPTER_SKIP_PROVIDER -> 2
        else -> 3
    }
    return intervals
        .filter {
            com.nuvio.app.features.player.skip.AutoSkipSegmentType.fromSkipIntervalType(it.type) ==
                com.nuvio.app.features.player.skip.AutoSkipSegmentType.OUTRO &&
                it.startTime.isFinite() && it.startTime > 0.0
        }
        .minByOrNull { rank(it.provider) * 1_000_000.0 + it.startTime }
        ?.let { (it.startTime * 1000).toLong() }
}
