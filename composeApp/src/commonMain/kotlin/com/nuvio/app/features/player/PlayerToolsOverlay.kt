package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.features.party.PartyRepository
import com.nuvio.app.features.settings.TheIntroDbApiKeyDialog
import com.nuvio.app.features.player.skip.IntroSubmitKeyPrepareResult
import com.nuvio.app.features.player.skip.IntroSubmitKeyProblem
import com.nuvio.app.features.player.skip.SharedSkipMark
import com.nuvio.app.features.player.skip.toLink
import com.nuvio.app.features.player.skip.defaultIntroSubmitService
import com.nuvio.app.features.player.skip.introSubmitKeyProblem
import com.nuvio.app.features.player.skip.prepareIntroSubmitKey
import com.nuvio.app.features.player.skip.savedKeyForService
import com.nuvio.app.features.player.skip.submitManualSkipSegment
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_tools_add_key
import nuvio.composeapp.generated.resources.player_tools_could_not_send
import nuvio.composeapp.generated.resources.player_tools_key_missing
import nuvio.composeapp.generated.resources.player_tools_link_copied
import nuvio.composeapp.generated.resources.player_tools_sent_detail
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerScreenRuntime.PlayerToolsOverlay() {
    val panelOpen = showSubtitleModal || showAudioModal || showVideoSettingsModal ||
        showStreamInfoModal || showSourcesPanel || showEpisodesPanel
    val open = panelOpen || showSubmitIntroModal || showSubtitleSyncByEar
    var submitting by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    val couldNotSend = stringResource(Res.string.player_tools_could_not_send)
    val linkCopied = stringResource(Res.string.player_tools_link_copied)
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        snapshotFlow { markPreviewEndMs to playbackClock.positionMs }
            .collect { (endMs, positionMs) ->
                val end = endMs ?: return@collect
                val startMs = parseMarkClock(submitIntroStartTimeStr)?.let { (it * 1000.0).toLong() } ?: return@collect
                if (!markPreviewArmed && markPreviewArmed(positionMs, startMs, end)) {
                    markPreviewArmed = true
                }
                if (shouldStopMarkPreview(positionMs, end, markPreviewArmed)) {
                    playerController?.pause()
                    shouldPlay = false
                    markPreviewEndMs = null
                    markPreviewArmed = false
                }
            }
    }

    if (!open) return

    val settings = playerSettingsUiState
    val service = defaultIntroSubmitService(settings.introDbApiKey, settings.theIntroDbApiKey)
    val savedKey = savedKeyForService(service, settings.introDbApiKey, settings.theIntroDbApiKey)
    val keyMissing = introSubmitKeyProblem(service, savedKey) != IntroSubmitKeyProblem.NONE
    val durationSec = playbackSnapshot.durationMs / 1000.0
    val startSec = parseMarkClock(submitIntroStartTimeStr)
    val endSec = parseMarkClock(submitIntroEndTimeStr)
    val timeOk = startSec != null && endSec != null &&
        markProblems(submitIntroSegmentType, startSec, endSec, durationSec.takeIf { it > 0.0 }).isEmpty()

    PlatformBackHandler(enabled = true) {
        when {
            playerStyleOpen -> playerStyleOpen = false
            showEpisodesPanel && episodeStreamsPanelState.showStreams -> {
                episodeStreamsPanelState = EpisodeStreamsPanelState()
                PlayerStreamsRepository.clearEpisodeStreams()
            }
            else -> dismissPlayerTools()
        }
    }

    if (showKeyDialog) {
        TheIntroDbApiKeyDialog(
            initialValue = settings.theIntroDbApiKey,
            onSave = { value ->
                PlayerSettingsRepository.setTheIntroDbApiKey(value)
                showKeyDialog = false
            },
            onDismiss = { showKeyDialog = false },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = playerToolDimAlpha(null)))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dismissPlayerTools() },
                ),
        )
        if (showSubmitIntroModal) {
            val meta = metaUiState.meta?.takeIf { it.id == parentMetaId }
            val year = meta?.releaseInfo?.let { YEAR.find(it)?.value }
            val episodeCode = localizedSeasonEpisodeCode(activeSeasonNumber, activeEpisodeNumber)
            SegmentMarkingLayer(
                title = markingTitle(year, episodeCode),
                segmentType = submitIntroSegmentType,
                isMovie = isMoviePlayback,
                episodeCode = episodeCode,
                year = year,
                startSec = startSec,
                endSec = endSec,
                durationSec = durationSec,
                playheadSec = (scrubbingPositionMs ?: playbackClock.positionMs) / 1000.0,
                absent = markAbsent,
                sent = markSent,
                submitting = submitting,
                keyProblem = keyMissing,
                keyMessage = stringResource(Res.string.player_tools_key_missing),
                addKeyLabel = stringResource(Res.string.player_tools_add_key),
                errorLine = markSubmitError,
                safeInset = horizontalSafePadding,
                onBack = { dismissPlayerTools() },
                onSegment = { switchSubmitSegmentType(it) },
                onAbsent = {
                    markAbsent = true
                    markSent = false
                    markSubmitError = null
                },
                onMarkStart = { setMarkClock(start = true, playbackClock.positionMs / 1000.0) },
                onMarkEnd = { setMarkClock(start = false, playbackClock.positionMs / 1000.0) },
                onNudgeStart = { delta ->
                    setMarkClock(true, nudgeMarkSeconds(startSec, delta, durationSec.takeIf { it > 0.0 }))
                },
                onNudgeEnd = { delta ->
                    setMarkClock(false, nudgeMarkSeconds(endSec, delta, durationSec.takeIf { it > 0.0 }))
                },
                onStartAtZero = { setMarkClock(true, 0.0) },
                onRunsToEnd = { setMarkClock(false, runsToEndSec(durationSec)) },
                onSeek = { seekWhileMarking(it) },
                onDragStart = { setMarkClock(true, it) },
                onDragEnd = { setMarkClock(false, it) },
                onPreview = {
                    if (startSec == null || endSec == null || endSec <= startSec) return@SegmentMarkingLayer
                    markPreviewEndMs = (endSec * 1000.0).toLong()
                    markPreviewArmed = false
                    seekWhileMarking(startSec)
                    shouldPlay = true
                    playerController?.play()
                },
                showCopyLink = timeOk && !markAbsent,
                onCopyLink = {
                    val mark = sharedMark(startSec, endSec) ?: return@SegmentMarkingLayer
                    clipboard.setText(AnnotatedString(mark.toLink()))
                    NuvioToastController.show(linkCopied)
                },
                onAddKey = { showKeyDialog = true },
                onSubmit = {
                    if (submitting || markSent) return@SegmentMarkingLayer
                    submitting = true
                    markSubmitError = null
                    scope.launch {
                        val posted = postMark(service, savedKey, startSec, endSec, durationSec)
                        submitting = false
                        if (posted) {
                            noteMarkingSubmitted(
                                IntroSubmitResult(
                                    segmentType = markSegmentId(submitIntroSegmentType),
                                    startSec = if (markAbsent) 0.0 else startSec ?: 0.0,
                                    endSec = if (markAbsent) 0.0 else endSec ?: 0.0,
                                    absentSegment = markAbsent,
                                ),
                            )
                        } else if (markSubmitError == null) {
                            markSubmitError = couldNotSend
                        }
                    }
                },
            )
            if (markSent) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(PlayerToolSurface.copy(alpha = 0.92f))
                        .border(1.dp, PlayerToolEdge, RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = Color(0xFF3DDC84))
                    Text(
                        text = stringResource(Res.string.player_tools_sent_detail),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        if (showSubtitleSyncByEar) {
            SubtitleSyncStrip(
                delayMs = subtitleDelayMs,
                heard = subtitleSyncHeardPositionMs != null,
                saw = subtitleSyncSawPositionMs != null,
                safeInset = horizontalSafePadding,
                onNudge = { delta -> setSubtitleDelay(subtitleDelayMs + delta) },
                onHeard = { captureSubtitleSyncHeard() },
                onSaw = { captureSubtitleSyncSaw() },
                onReset = { setSubtitleDelay(subtitleDelayAfterReset()) },
                onDone = { dismissPlayerTools() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (panelOpen) {
            val tabs = playerToolTabs()
            val tab = playerToolTab.takeIf { it in tabs } ?: tabs.first()
            PlayerToolsPanel(
                tab = tab,
                tabs = tabs,
                onTab = { selectToolTab(it) },
                onDismiss = { dismissPlayerTools() },
                endInset = horizontalSafePadding,
                subtitleTracks = serverSubtitleTracks.ifEmpty { subtitleTracks },
                selectedSubtitleIndex = if (serverSubtitleTracks.isEmpty()) {
                    selectedSubtitleIndex
                } else {
                    serverSubtitleTracks.firstOrNull { it.isSelected }?.index ?: -1
                },
                addonSubtitles = visibleAddonSubtitles,
                selectedAddonSubtitle = selectedAddonSubtitle,
                isLoadingAddonSubtitles = isLoadingAddonSubtitles,
                preferredSubtitleLanguage = playerSettingsUiState.preferredSubtitleLanguage,
                secondaryPreferredSubtitleLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
                subtitleStyle = subtitleStyle,
                subtitleDelayMs = subtitleDelayMs,
                subtitleAutoSyncState = subtitleAutoSyncState,
                onBuiltInSubtitle = { index -> selectBuiltInSubtitle(index) },
                onAddonSubtitle = { addon -> selectAddonSubtitle(addon) },
                onFetchAddonSubtitles = { fetchAddonSubtitlesForActiveItem() },
                onStyleChanged = PlayerSettingsRepository::setSubtitleStyle,
                onDelayChanged = { setSubtitleDelay(it) },
                onDelayReset = { setSubtitleDelay(subtitleDelayAfterReset()) },
                onSync = { openSubtitleSyncByEar() },
                onAutoSyncAutomatic = { performAutomaticSubtitleSync() },
                onAutoSyncCapture = { captureSubtitleAutoSyncTime() },
                onAutoSyncCue = { applySubtitleAutoSyncCue(it) },
                onAutoSyncReload = { loadSubtitleAutoSyncCues(force = true) },
                audioTracks = serverAudioTracks.ifEmpty { audioTracks },
                selectedAudioIndex = serverAudioTracks.firstOrNull { it.isSelected }?.index ?: selectedAudioIndex,
                onAudio = { index -> selectAudio(index) },
                streams = sourceStreamsState,
                currentStreamUrl = activeSourceUrl,
                currentStreamName = activeStreamTitle,
                onSourceFilter = PlayerStreamsRepository::selectSourceFilter,
                onSource = { switchToSource(it) },
                onReloadSources = { reloadToolSources() },
                showEpisodes = isSeries,
                episodes = playerMetaVideos,
                parentMetaType = parentMetaType,
                parentMetaId = parentMetaId,
                currentSeason = activeSeasonNumber,
                currentEpisode = activeEpisodeNumber,
                progressByVideoId = watchProgressUiState.byVideoIdForContent(parentMetaId),
                watchedKeys = watchedUiState.watchedKeys,
                onEpisode = { episode ->
                    val openedDownload = selectDownloadedEpisodeForPlayback(
                        parentMetaId = parentMetaId,
                        episode = episode,
                        onDownloadedEpisodeSelected = { item, video -> switchToDownloadedEpisode(item, video) },
                    )
                    if (!openedDownload) {
                        PlayerStreamsRepository.loadEpisodeStreams(
                            type = contentType ?: parentMetaType,
                            videoId = episode.id,
                            season = episode.season,
                            episode = episode.episode,
                        )
                        episodeStreamsPanelState = EpisodeStreamsPanelState(showStreams = true, selectedEpisode = episode)
                    }
                },
                episodeStreams = episodeStreamsPanelState.copy(streamsUiState = episodeStreamsRepoState),
                onEpisodeStreamFilter = PlayerStreamsRepository::selectEpisodeStreamsFilter,
                onEpisodeStream = { stream, episode ->
                    switchToEpisodeStream(
                        stream,
                        episode,
                        saveForReuse = stream != PartyRepository.sharedStream.value?.toStreamItem(),
                    )
                },
                onBackToEpisodes = {
                    episodeStreamsPanelState = EpisodeStreamsPanelState()
                    PlayerStreamsRepository.clearEpisodeStreams()
                },
                onReloadEpisodeStreams = { reloadEpisodeStreams() },
                episodesLoading = episodesLoading,
                episodesFailed = episodesLoadFailed,
                onRetryEpisodes = { retryEpisodesLoad() },
                showTitle = title,
                episodeLine = episodeCodeLine(),
                aired = metaUiState.meta?.takeIf { it.id == parentMetaId }?.releaseInfo,
                mediaInfoJson = playbackSnapshot.mediaInfoJson,
                selectedQuality = playerQualityState.selectedVariantFor(selectedPlayerQualityId),
                selectedQualityIsAuto = selectedPlayerQualityId == null && playerQualityState.hasSelectableQualities,
                audioLine = (serverAudioTracks.ifEmpty { audioTracks })
                    .firstOrNull { it.index == (serverAudioTracks.firstOrNull { track -> track.isSelected }?.index ?: selectedAudioIndex) }
                    ?.label,
                fileName = activeSourceFilename ?: activeStreamTitle,
                resizeMode = resizeMode,
                onResize = { applyResizeMode(it) },
                playbackSpeed = playbackSnapshot.playbackSpeed,
                onSpeed = { setPlaybackSpeedFromPicker(it) },
                videoSettings = playerSettingsUiState,
                onVideoSettingsChanged = {
                    playerController?.configureIosVideoOutput(PlayerSettingsRepository.uiState.value)
                },
                onResetVideo = { resetVideoSettings() },
                styleOpen = playerStyleOpen,
                onStyleOpenChange = { playerStyleOpen = it },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

private fun PlayerScreenRuntime.playerToolTabs(): List<PlayerToolTab> = buildList {
    add(PlayerToolTab.Subs)
    add(PlayerToolTab.Audio)
    if (!isLiveTvPlayback && activeVideoId != null) add(PlayerToolTab.Sources)
    if (isSeries) add(PlayerToolTab.Episodes)
    add(PlayerToolTab.Info)
    add(PlayerToolTab.Video)
}

private fun PlayerScreenRuntime.markingTitle(year: String?, episodeCode: String?): String =
    if (isMoviePlayback) listOfNotNull(title, year).joinToString(" · ")
    else listOfNotNull(title, episodeCode).joinToString(" · ")

private fun PlayerScreenRuntime.episodeCodeLine(): String? {
    val season = activeSeasonNumber ?: return null
    val episode = activeEpisodeNumber ?: return null
    return "S$season E$episode"
}

private fun PlayerScreenRuntime.sharedMark(startSec: Double?, endSec: Double?): SharedSkipMark? {
    if (startSec == null || endSec == null || endSec <= startSec) return null
    val id = parentMetaId.ifBlank { metaUiState.meta?.id }.orEmpty()
    if (id.isBlank()) return null
    val (season, episode) = movieSubmitSeasonEpisode(isMoviePlayback, activeSeasonNumber, activeEpisodeNumber)
    return SharedSkipMark(
        parentMetaId = id,
        season = season,
        episode = episode,
        type = markSegmentId(submitIntroSegmentType),
        startMs = (startSec * 1000.0).toLong(),
        endMs = (endSec * 1000.0).toLong(),
    )
}

private suspend fun PlayerScreenRuntime.postMark(
    service: com.nuvio.app.features.player.skip.IntroSubmitService,
    savedKey: String,
    startSec: Double?,
    endSec: Double?,
    durationSec: Double,
): Boolean {
    val ready = prepareIntroSubmitKey(service, savedKey, savedKey)
    if (ready !is IntroSubmitKeyPrepareResult.Ready) return false
    val times = if (markAbsent) {
        0.0 to 0.0
    } else {
        if (startSec == null || endSec == null) return false
        if (markProblems(submitIntroSegmentType, startSec, endSec, durationSec.takeIf { it > 0.0 }).isNotEmpty()) {
            return false
        }
        startSec to endSec
    }
    val (season, episode) = movieSubmitSeasonEpisode(isMoviePlayback, activeSeasonNumber, activeEpisodeNumber)
    val imdbId = activeVideoId?.split(":")?.firstOrNull()?.takeIf { it.startsWith("tt") }
        ?: parentMetaId.takeIf { it.startsWith("tt") }
        ?: metaUiState.meta?.id?.takeIf { it.startsWith("tt") }
        ?: metaUiState.meta?.imdbId?.takeIf { it.startsWith("tt") }
        ?: ""
    return submitManualSkipSegment(
        imdbId = imdbId,
        season = season ?: 0,
        episode = episode ?: 0,
        startSec = times.first,
        endSec = times.second,
        segmentType = markSegmentId(submitIntroSegmentType),
        durationMs = playbackSnapshot.durationMs,
        isMovie = isMoviePlayback,
        videoId = activeVideoId ?: parentMetaId.ifBlank { metaUiState.meta?.id },
        parentMetaType = parentMetaType.ifBlank { metaUiState.meta?.type.orEmpty() },
        service = service,
    )
}

private fun PlayerScreenRuntime.selectBuiltInSubtitle(index: Int) {
    clearAutoSubtitleState()
    if (serverSubtitleTracks.isNotEmpty() && index >= 0) {
        selectServerSubtitleTrack(index)
        return
    }
    val wasCustom = useCustomSubtitles
    isUserExplicitSubtitleSelection = true
    preferredSubtitleSelectionApplied = true
    subtitleOffRequested = index < 0
    selectedSubtitleIndex = index
    selectedAddonSubtitleId = null
    useCustomSubtitles = false
    persistInternalSubtitlePreference(subtitleTracks.firstOrNull { it.index == index })
    if (wasCustom) {
        playerController?.clearExternalSubtitleAndSelect(index)
    } else {
        playerController?.selectSubtitleTrack(index)
    }
    if (hasBurnedInServerSubtitle) clearServerSubtitleTrack()
}

private fun PlayerScreenRuntime.selectAddonSubtitle(addon: AddonSubtitle) {
    clearAutoSubtitleState()
    isUserExplicitSubtitleSelection = true
    selectedAddonSubtitleId = addon.selectionKey
    selectedSubtitleIndex = -1
    useCustomSubtitles = true
    preferredSubtitleSelectionApplied = true
    persistAddonSubtitlePreference(addon)
    attachSelectedAddonSubtitleWithAutoSync(addon.url)
    if (hasBurnedInServerSubtitle) clearServerSubtitleTrack()
}

private fun PlayerScreenRuntime.selectAudio(index: Int) {
    if (serverAudioTracks.isNotEmpty()) {
        selectServerAudioTrack(index)
    } else {
        selectedAudioIndex = index
        persistAudioPreference(audioTracks.firstOrNull { it.index == index })
        playerController?.selectAudioTrack(index)
    }
}

private fun PlayerScreenRuntime.reloadToolSources() {
    val vid = activeVideoId ?: return
    PlayerStreamsRepository.loadSources(
        type = contentType ?: parentMetaType,
        videoId = vid,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
        forceRefresh = true,
    )
}

private fun PlayerScreenRuntime.reloadEpisodeStreams() {
    val episode = episodeStreamsPanelState.selectedEpisode ?: return
    PlayerStreamsRepository.loadEpisodeStreams(
        type = contentType ?: parentMetaType,
        videoId = episode.id,
        season = episode.season,
        episode = episode.episode,
        forceRefresh = true,
    )
}

private fun PlayerScreenRuntime.resetVideoSettings() {
    PlayerSettingsRepository.resetIosVideoOutputTuning()
    applyResizeMode(PlayerResizeMode.Fit)
    setPlaybackSpeedFromPicker(1f)
    playerController?.configureIosVideoOutput(PlayerSettingsRepository.uiState.value)
}

private val YEAR = Regex("""(19|20)\d{2}""")
