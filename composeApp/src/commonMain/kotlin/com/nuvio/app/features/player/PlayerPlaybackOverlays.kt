package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.p2p.P2pLoadingStatus
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.player.skip.MovieRecommendationCard
import com.nuvio.app.features.player.skip.NextEpisodeCard
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.SkipIntroButton
import com.nuvio.app.features.player.skip.SkipInterval

@Composable
internal fun BoxScope.PlayerPlaybackOverlays(
    playerControlsLocked: Boolean,
    useLegacyLayout: Boolean,
    lockedOverlayVisible: Boolean,
    showRemainingTime: Boolean = false,
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    playbackClock: PlayerPlaybackClock? = null,
    scrubbingPositionMs: Long? = null,
    metrics: PlayerLayoutMetrics,
    horizontalSafePadding: Dp,
    onUnlock: () -> Unit,
    showOpeningOverlay: Boolean,
    backdropArtwork: String?,
    logo: String?,
    title: String,
    onBackWithProgress: () -> Unit,
    openingLoadingMessage: String?,
    p2pInitialLoadingProgress: Float?,
    showP2pRebufferStats: Boolean,
    p2pRebufferMessage: String?,
    p2pRebufferProgress: Float?,
    p2pDownloadedBytes: Long? = null,
    p2pDeliveredBytes: Long? = null,
    p2pPeerInfo: String? = null,
    p2pDownloadSpeed: String? = null,
    currentGestureFeedback: GestureFeedbackState?,
    renderedGestureFeedback: GestureFeedbackState?,
    initialLoadCompleted: Boolean,
    pausedOverlayVisible: Boolean,
    activeSkipInterval: SkipInterval?,
    skipsToPostCredits: Boolean,
    skipIntervalDismissed: Boolean,
    controlsVisible: Boolean,
    onSkipInterval: (SkipInterval) -> Unit,
    onDismissSkipInterval: () -> Unit,
    sliderEdgePadding: Dp,
    overlayBottomPadding: Dp,
    isSeries: Boolean,
    nextEpisodeInfo: NextEpisodeInfo?,
    showNextEpisodeCard: Boolean,
    nextEpisodeAutoPlaySearching: Boolean,
    nextEpisodeAutoPlaySourceName: String?,
    nextEpisodeAutoPlayCountdown: Int?,
    blurUnwatchedEpisodes: Boolean,
    onPlayNextEpisode: () -> Unit,
    onDismissNextEpisode: () -> Unit,
    movieRecommendations: List<MetaPreview> = emptyList(),
    showMovieRecommendationCard: Boolean = false,
    onOpenMovieRecommendation: (MetaPreview) -> Unit = {},
    onDismissMovieRecommendations: () -> Unit = {},
    errorMessage: String?,
    onDismissError: () -> Unit,
    suppressOnVideoOverlays: Boolean = false,
) {
    if (suppressOnVideoOverlays) {
        if (errorMessage != null) {
            ErrorModal(
                message = errorMessage,
                onDismiss = onDismissError,
            )
        }
        return
    }
    AnimatedVisibility(
        visible = playerControlsLocked && lockedOverlayVisible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        LockedPlayerOverlay(
            playbackSnapshot = playbackSnapshot,
            displayedPositionMs = displayedPositionMs,
            playbackClock = playbackClock,
            scrubbingPositionMs = scrubbingPositionMs,
            metrics = metrics,
            horizontalSafePadding = horizontalSafePadding,
            onUnlock = onUnlock,
            useLegacyLayout = useLegacyLayout,
            showRemainingTime = showRemainingTime,
            modifier = Modifier.fillMaxSize(),
        )
    }

    AnimatedVisibility(
        visible = showOpeningOverlay,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        OpeningOverlay(
            artwork = backdropArtwork,
            logo = logo,
            title = title,
            onBack = onBackWithProgress,
            horizontalSafePadding = horizontalSafePadding,
            modifier = Modifier.fillMaxSize(),
            message = openingLoadingMessage,
            progress = p2pOpeningProgress(
                playbackClock = playbackClock,
                downloadedBytes = p2pDownloadedBytes,
                deliveredBytes = p2pDeliveredBytes,
                fallback = p2pInitialLoadingProgress,
            ),
        )
    }

    if (showP2pRebufferStats && errorMessage == null) {
        P2pRebufferStatus(
            playbackClock = playbackClock,
            peerInfo = p2pPeerInfo,
            downloadSpeed = p2pDownloadSpeed,
            fallbackMessage = p2pRebufferMessage,
            fallbackProgress = p2pRebufferProgress,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
                .padding(top = 12.dp),
        )
    }

    PlayerGestureOverlay(
        currentFeedback = currentGestureFeedback,
        renderedFeedback = renderedGestureFeedback,
        useLegacyLayout = useLegacyLayout,
        horizontalSafePadding = horizontalSafePadding,
        horizontalPadding = metrics.horizontalPadding,
    )

    val skipVisible = !playerControlsLocked &&
        initialLoadCompleted &&
        !pausedOverlayVisible &&
        activeSkipInterval != null &&
        !skipIntervalDismissed
    val nextVisible = isSeries && !playerControlsLocked &&
        (showNextEpisodeCard || nextEpisodeAutoPlaySearching || nextEpisodeAutoPlayCountdown != null)
    val recommendationsVisible = !isSeries && !playerControlsLocked && showMovieRecommendationCard
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val safeWidth = (maxWidth - sliderEdgePadding * 2).coerceAtLeast(0.dp)
        val cardMaxWidth = when {
            metrics.sizeClass == PlayerSizeClass.S -> 240.dp
            recommendationsVisible -> 320.dp
            else -> 292.dp
        }
        val cardCrossesCenter = cardMaxWidth > safeWidth / 2
        val stackCardAboveSkip = skipVisible &&
            (nextVisible || recommendationsVisible) &&
            (140.dp + cardMaxWidth + 12.dp > safeWidth)
        val cardBottom = if (stackCardAboveSkip) overlayBottomPadding + 56.dp else overlayBottomPadding
        if (!playerControlsLocked) {
            SkipIntroButton(
                interval = if (!initialLoadCompleted || pausedOverlayVisible) null else activeSkipInterval,
                skipsToPostCredits = skipsToPostCredits,
                dismissed = skipIntervalDismissed,
                controlsVisible = controlsVisible,
                onSkip = {
                    activeSkipInterval?.let(onSkipInterval)
                },
                onDismiss = onDismissSkipInterval,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = sliderEdgePadding, bottom = overlayBottomPadding),
            )
        }
        if (isSeries && !playerControlsLocked) {
            NextEpisodeCard(
                nextEpisode = nextEpisodeInfo,
                visible = nextVisible,
                isAutoPlaySearching = nextEpisodeAutoPlaySearching,
                autoPlaySourceName = nextEpisodeAutoPlaySourceName,
                autoPlayCountdownSec = nextEpisodeAutoPlayCountdown,
                blurred = blurUnwatchedEpisodes && nextEpisodeInfo?.isWatched == false,
                maxCardWidth = if (metrics.sizeClass == PlayerSizeClass.S) 240.dp else 292.dp,
                showThumbnail = !(metrics.sizeClass == PlayerSizeClass.S && cardCrossesCenter),
                onPlayNext = onPlayNextEpisode,
                onDismiss = onDismissNextEpisode,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = sliderEdgePadding, bottom = cardBottom),
            )
        }
        if (!isSeries && !playerControlsLocked) {
            MovieRecommendationCard(
                recommendations = movieRecommendations,
                visible = recommendationsVisible,
                maxCardWidth = if (metrics.sizeClass == PlayerSizeClass.S) 240.dp else 320.dp,
                showPosters = !(metrics.sizeClass == PlayerSizeClass.S && cardCrossesCenter),
                onOpen = onOpenMovieRecommendation,
                onDismiss = onDismissMovieRecommendations,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = sliderEdgePadding, bottom = cardBottom),
            )
        }
    }

    if (errorMessage != null) {
        ErrorModal(
            message = errorMessage,
            onDismiss = onDismissError,
        )
    }
}

@Composable
private fun p2pOpeningProgress(
    playbackClock: PlayerPlaybackClock?,
    downloadedBytes: Long?,
    deliveredBytes: Long?,
    fallback: Float?,
): Float? {
    if (playbackClock == null || downloadedBytes == null || deliveredBytes == null) return fallback
    val bufferedAheadMs = (playbackClock.bufferedPositionMs - playbackClock.positionMs).coerceAtLeast(0L)
    return p2pInitialLoadingProgress(
        bufferedAheadMs = bufferedAheadMs,
        downloadedBytes = downloadedBytes,
        deliveredBytes = deliveredBytes,
    )
}

@Composable
private fun P2pRebufferStatus(
    playbackClock: PlayerPlaybackClock?,
    peerInfo: String?,
    downloadSpeed: String?,
    fallbackMessage: String?,
    fallbackProgress: Float?,
    modifier: Modifier,
) {
    val message: String?
    val progress: Float?
    if (playbackClock != null) {
        val bufferedMs = (playbackClock.bufferedPositionMs - playbackClock.positionMs).coerceAtLeast(0L)
        message = "${bufferedMs / 1000L}s buffered · ${peerInfo.orEmpty()} · ${downloadSpeed.orEmpty()}"
        progress = ((bufferedMs / 1000f) / 10f).coerceIn(0f, 1f)
    } else {
        message = fallbackMessage
        progress = fallbackProgress
    }
    P2pLoadingStatus(
        visible = true,
        message = message,
        progress = progress,
        modifier = modifier,
    )
}
