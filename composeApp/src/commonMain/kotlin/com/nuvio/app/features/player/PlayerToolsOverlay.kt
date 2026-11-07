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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.features.party.PartyRepository
import com.nuvio.app.features.player.skip.OwnMarkStatus
import com.nuvio.app.features.player.skip.OwnTheIntroDbLedger
import com.nuvio.app.features.player.skip.accountScopeForService
import com.nuvio.app.features.player.skip.ownListBucketsForTitle
import com.nuvio.app.features.player.skip.shouldRecordOwnTheIntroDb
import com.nuvio.app.features.player.skip.ownSentTypes
import com.nuvio.app.features.player.skip.IntroSubmitBlockReason
import com.nuvio.app.features.player.skip.IntroSubmitKeyPrepareResult
import com.nuvio.app.features.player.skip.IntroSubmitKeyProblem
import com.nuvio.app.features.player.skip.IntroSubmitService
import com.nuvio.app.features.player.skip.ManualSkipSubmitOutcome
import com.nuvio.app.features.player.skip.TheIntroDb
import com.nuvio.app.features.player.skip.TheIntroDbAlreadySubmittedMessage
import com.nuvio.app.features.player.skip.defaultIntroSubmitService
import com.nuvio.app.features.player.skip.flagChecksForTitle
import com.nuvio.app.features.player.skip.flagSubmitContentKey
import com.nuvio.app.features.player.skip.introSubmitBlockReason
import com.nuvio.app.features.player.skip.introSubmitKeySatisfied
import com.nuvio.app.features.player.skip.prepareIntroSubmitKey
import com.nuvio.app.features.player.skip.rejectedFlagTypesForTitle
import com.nuvio.app.features.player.skip.resolveIntroSubmitTimes
import com.nuvio.app.features.player.skip.savedKeyForService
import com.nuvio.app.features.player.skip.submitManualSkipSegment
import com.nuvio.app.features.player.skip.submittedFlagTypesForTitle
import com.nuvio.app.features.player.skip.toFlagRecord
import com.nuvio.app.features.settings.TheIntroDbApiKeyDialog
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_tools_add_key
import nuvio.composeapp.generated.resources.player_tools_could_not_send
import nuvio.composeapp.generated.resources.player_tools_none_refused
import nuvio.composeapp.generated.resources.player_tools_sent_detail
import nuvio.composeapp.generated.resources.settings_playback_introdb_invalid_key
import nuvio.composeapp.generated.resources.submit_intro_api_key_required
import nuvio.composeapp.generated.resources.submit_intro_introdb_episode
import nuvio.composeapp.generated.resources.submit_intro_introdb_movie
import nuvio.composeapp.generated.resources.submit_intro_introdb_prefix
import nuvio.composeapp.generated.resources.submit_intro_none_hint
import nuvio.composeapp.generated.resources.submit_intro_theintrodb_prefix
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerScreenRuntime.PlayerToolsOverlay() {
    val panelOpen = showSubtitleModal || showAudioModal || showVideoSettingsModal ||
        showStreamInfoModal || showSourcesPanel || showEpisodesPanel
    val open = panelOpen || showSubmitIntroModal || showSubtitleSyncByEar
    var submitting by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    val couldNotSend = stringResource(Res.string.player_tools_could_not_send)
    val noneRefused = stringResource(Res.string.player_tools_none_refused)
    val noneHint = stringResource(Res.string.submit_intro_none_hint)
    val requiredKeyMessage = stringResource(Res.string.submit_intro_api_key_required)
    val introDbPrefixMessage = stringResource(Res.string.submit_intro_introdb_prefix)
    val theIntroDbPrefixMessage = stringResource(Res.string.submit_intro_theintrodb_prefix)
    val introDbMovieMessage = stringResource(Res.string.submit_intro_introdb_movie)
    val introDbEpisodeMessage = stringResource(Res.string.submit_intro_introdb_episode)
    val invalidKeyMessage = stringResource(Res.string.settings_playback_introdb_invalid_key)
    LaunchedEffect(Unit) {
        snapshotFlow { markPreviewEndMs to playbackClock.positionMs }
            .collect { (endMs, positionMs) ->
                val end = endMs ?: return@collect
                val startMs = parseMarkClock(submitIntroStartTimeStr)?.let { (it * 1000.0).toLong() } ?: return@collect
                if (!markPreviewArmed && markPreviewArmed(positionMs, startMs, end)) {
                    markPreviewArmed = true
                }
                if (shouldStopMarkPreview(positionMs, end, markPreviewArmed)) {
                    restoreMarkPreview()
                }
            }
    }
    LaunchedEffect(Unit) {
        snapshotFlow {
            markingPlayheadAfterSample(
                clockMs = playbackClock.positionMs,
                pendingSeekMs = markingPendingSeekMs,
                loadingScrubMs = scrubbingPositionMs,
            ).pendingSeekMs
        }.collect { pending ->
            if (markingPendingSeekMs != pending) markingPendingSeekMs = pending
        }
    }

    if (!open) return

    val settings = playerSettingsUiState
    val service = defaultIntroSubmitService(settings.introDbApiKey, settings.theIntroDbApiKey)
    val savedKey = savedKeyForService(service, settings.introDbApiKey, settings.theIntroDbApiKey)
    val keyMissing = !introSubmitKeySatisfied(service, savedKey)
    val durationSec = playbackSnapshot.durationMs / 1000.0
    val startSec = parseMarkClock(submitIntroStartTimeStr)
    val endSec = parseMarkClock(submitIntroEndTimeStr)

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
        if (!showSubmitIntroModal) {
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
        }
        if (showSubmitIntroModal) {
            val meta = metaUiState.meta?.takeIf { it.id == parentMetaId }
            val year = meta?.releaseInfo?.let { YEAR.find(it)?.value }
            val episodeCode = localizedSeasonEpisodeCode(activeSeasonNumber, activeEpisodeNumber)
            val playhead = markingPlayheadAfterSample(
                clockMs = playbackClock.positionMs,
                pendingSeekMs = markingPendingSeekMs,
                loadingScrubMs = scrubbingPositionMs,
            )
            val imdbId = markingImdbId()
            val block = introSubmitBlockReason(
                service = service,
                apiKey = savedKey,
                isMovie = isMoviePlayback,
                imdbId = imdbId,
                season = activeSeasonNumber ?: 0,
                episode = activeEpisodeNumber ?: 0,
            )
            val blockLine = when (block) {
                null,
                IntroSubmitBlockReason.MISSING_KEY,
                IntroSubmitBlockReason.INTRODB_KEY_SHAPE,
                IntroSubmitBlockReason.THEINTRODB_KEY_SHAPE,
                -> null
                else -> introSubmitBlockMessage(
                    block,
                    requiredKeyMessage,
                    introDbPrefixMessage,
                    theIntroDbPrefixMessage,
                    introDbMovieMessage,
                    introDbEpisodeMessage,
                )
            }
            val absentAllowed = resolveIntroSubmitTimes(service, absentSegment = true, startTimeStr = "", endTimeStr = "") != null
            val contentKey = flagSubmitContentKey(
                imdbId,
                activeSeasonNumber ?: 0,
                activeEpisodeNumber ?: 0,
                isMoviePlayback,
            )
            var remoteTypes by remember(contentKey) { mutableStateOf<Set<String>?>(null) }
            var rejectedTypes by remember(contentKey) { mutableStateOf<Set<String>>(emptySet()) }
            val theIntroDbKey = settings.theIntroDbApiKey
            val introDbKey = settings.introDbApiKey
            LaunchedEffect(contentKey, theIntroDbKey, introDbKey, activeVideoId, activeSeasonNumber, activeEpisodeNumber, isMoviePlayback) {
                remoteTypes = null
                rejectedTypes = emptySet()
                val apiKey = savedKeyForService(IntroSubmitService.THE_INTRODB, introDbKey, theIntroDbKey)
                if (apiKey.isBlank() || contentKey.isEmpty()) return@LaunchedEffect
                val mediaType = if (isMoviePlayback) "movie" else "tv"
                val tmdbId = try {
                    TmdbService.ensureTmdbId(
                        videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: imdbId,
                        mediaType = mediaType,
                        fallbackImdbId = imdbId.takeIf { it.startsWith("tt", ignoreCase = true) },
                    )?.toIntOrNull()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: return@LaunchedEffect
                val records = try {
                    TheIntroDb.listMySubmissions(apiKey, tmdbId, mediaType).mapNotNull { it.toFlagRecord(tmdbId) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@LaunchedEffect
                }
                val seasonNow = activeSeasonNumber ?: 0
                val episodeNow = activeEpisodeNumber ?: 0
                val accepted = submittedFlagTypesForTitle(records, tmdbId, isMoviePlayback, seasonNow, episodeNow)
                val rejected = rejectedFlagTypesForTitle(records, tmdbId, isMoviePlayback, seasonNow, episodeNow)
                val buckets = ownListBucketsForTitle(records, tmdbId, isMoviePlayback, seasonNow, episodeNow)
                OwnTheIntroDbLedger.applyListing(
                    scope = accountScopeForService(IntroSubmitService.THE_INTRODB, introDbKey, theIntroDbKey),
                    contentKey = contentKey,
                    confirmed = buckets.confirmed,
                    pending = buckets.pending,
                    rejected = buckets.rejected,
                )
                remoteTypes = accepted
                rejectedTypes = rejected
            }
            val sessionTypes = submittedSegmentTypesByVideoId[activeVideoId.orEmpty()].orEmpty()
            val accountScope = accountScopeForService(IntroSubmitService.THE_INTRODB, introDbKey, theIntroDbKey)
            val submittedTypes = flagChecksForTitle(
                ledgerTypes = ownSentTypes(OwnTheIntroDbLedger.load(accountScope), contentKey),
                sessionTypes = sessionTypes,
                remoteTypes = remoteTypes,
                rejectedTypes = rejectedTypes,
            )
            SegmentMarkingLayer(
                segmentType = submitIntroSegmentType,
                isMovie = isMoviePlayback,
                episodeCode = episodeCode,
                year = year,
                startSec = startSec,
                endSec = endSec,
                startShowsTenth = markStartShowsTenth,
                endShowsTenth = markEndShowsTenth,
                durationSec = durationSec,
                absent = markAbsent,
                absentAllowed = absentAllowed,
                sent = markSent,
                submitting = submitting,
                keyProblem = keyMissing,
                absentLine = if (absentAllowed) noneHint else noneRefused,
                addKeyLabel = stringResource(Res.string.player_tools_add_key),
                blockLine = blockLine,
                errorLine = markSubmitError,
                onBack = { if (!submitting) dismissPlayerTools() },
                onSegment = { if (!submitting) handleSegmentTap(it) },
                onAbsent = {
                    if (submitting || markAbsent) return@SegmentMarkingLayer
                    markAbsent = absentAfterNoneChip()
                    markSent = false
                    markSubmitError = null
                },
                onSetToNow = { handle ->
                    setMarkClock(handle == MarkHandle.Start, playhead.positionMs / 1000.0)
                },
                onNudge = { handle, delta ->
                    val start = handle == MarkHandle.Start
                    val current = if (start) startSec else endSec
                    val showing = if (start) markStartShowsTenth else markEndShowsTenth
                    val next = nudgeMarkSeconds(current, delta, durationSec.takeIf { it > 0.0 })
                    setMarkClock(start, next, showTenth = nudgeKeepsTenth(delta, showing))
                },
                submittedTypes = submittedTypes,
                autoSyncedTypes = autoSyncedSubmitTypes(),
                clocksTouched = markSegmentId(submitIntroSegmentType) in submitIntroUserEdited,
                onStartAtZero = { if (!submitting) setMarkClock(true, 0.0) },
                onRunsToEnd = { if (!submitting) setMarkClock(false, runsToEndSec(durationSec)) },
                onPreview = {
                    if (submitting || startSec == null || endSec == null || endSec <= startSec) return@SegmentMarkingLayer
                    startMarkPreview(startSec, endSec)
                },
                onAddKey = { showKeyDialog = true },
                onSubmit = {
                    if (submitting || markSent) return@SegmentMarkingLayer
                    val generation = markSubmitGeneration
                    val snapshot = markSubmitSnapshot(
                        segmentType = submitIntroSegmentType,
                        absent = markAbsent,
                        startSec = startSec,
                        endSec = endSec,
                        generation = generation,
                    )
                    val seasonNow = activeSeasonNumber
                    val episodeNow = activeEpisodeNumber
                    val movieNow = isMoviePlayback
                    val durationNow = playbackSnapshot.durationMs
                    val videoNow = activeVideoId
                    val parentIdNow = parentMetaId
                    val parentTypeNow = parentMetaType.ifBlank { metaUiState.meta?.type.orEmpty() }
                    val imdbNow = imdbId
                    val blocked = introSubmitBlockReason(
                        service, savedKey, movieNow, imdbNow, seasonNow ?: 0, episodeNow ?: 0,
                    )
                    if (blocked != null) {
                        markSubmitError = introSubmitBlockMessage(
                            blocked,
                            requiredKeyMessage,
                            introDbPrefixMessage,
                            theIntroDbPrefixMessage,
                            introDbMovieMessage,
                            introDbEpisodeMessage,
                        )
                        return@SegmentMarkingLayer
                    }
                    val times = resolveIntroSubmitTimes(
                        service = service,
                        absentSegment = snapshot.absent,
                        startTimeStr = submitIntroStartTimeStr,
                        endTimeStr = submitIntroEndTimeStr,
                    )
                    if (times == null) {
                        markSubmitError = if (snapshot.absent) noneRefused else couldNotSend
                        return@SegmentMarkingLayer
                    }
                    if (!snapshot.absent &&
                        markProblems(snapshot.segmentType, times.first, times.second, durationSec.takeIf { it > 0.0 }).isNotEmpty()
                    ) {
                        return@SegmentMarkingLayer
                    }
                    if (!snapshot.absent) {
                        logIntroLengthWarning(
                            snapshot.segmentType,
                            times.first,
                            times.second,
                            durationSec.takeIf { it > 0.0 },
                        )
                    }
                    submitting = true
                    markSubmitError = null
                    scope.launch {
                        val posted = postMark(
                            service = service,
                            savedKey = savedKey,
                            times = times,
                            segmentType = snapshot.segmentType,
                            season = seasonNow,
                            episode = episodeNow,
                            isMovie = movieNow,
                            durationMs = durationNow,
                            videoId = videoNow,
                            parentMetaId = parentIdNow,
                            parentMetaType = parentTypeNow,
                            imdbId = imdbNow,
                        )
                        val stillCurrent = markSubmitResultApplies(
                            snapshotGeneration = snapshot.generation,
                            currentGeneration = markSubmitGeneration,
                            stripOpen = showSubmitIntroModal,
                        )
                        if (!stillCurrent) {
                            submitting = false
                            return@launch
                        }
                        submitting = false
                        when (posted) {
                            MarkPost.Sent, MarkPost.AlreadySubmitted -> {
                                noteMarkingSubmitted(
                                    IntroSubmitResult(
                                        segmentType = snapshot.segmentType,
                                        startSec = times.first,
                                        endSec = times.second,
                                        absentSegment = snapshot.absent,
                                    ),
                                )
                                if (shouldRecordOwnTheIntroDb(service, succeeded = true)) {
                                    val status = if (posted == MarkPost.AlreadySubmitted) {
                                        OwnMarkStatus.Confirmed
                                    } else {
                                        OwnMarkStatus.Pending
                                    }
                                    OwnTheIntroDbLedger.remember(
                                        accountScopeForService(service, introDbKey, theIntroDbKey),
                                        contentKey,
                                        snapshot.segmentType,
                                        status,
                                    )
                                }
                                if (posted == MarkPost.AlreadySubmitted) {
                                    NuvioToastController.show(TheIntroDbAlreadySubmittedMessage)
                                }
                            }
                            is MarkPost.Blocked -> markSubmitError = introSubmitBlockMessage(
                                posted.reason,
                                requiredKeyMessage,
                                introDbPrefixMessage,
                                theIntroDbPrefixMessage,
                                introDbMovieMessage,
                                introDbEpisodeMessage,
                            )
                            MarkPost.InvalidKey -> markSubmitError = invalidKeyMessage
                            MarkPost.Failed -> {
                                val lateBlock = introSubmitBlockReason(
                                    service, savedKey, movieNow, imdbNow, seasonNow ?: 0, episodeNow ?: 0,
                                )
                                markSubmitError = when {
                                    lateBlock != null -> introSubmitBlockMessage(
                                        lateBlock,
                                        requiredKeyMessage,
                                        introDbPrefixMessage,
                                        theIntroDbPrefixMessage,
                                        introDbMovieMessage,
                                        introDbEpisodeMessage,
                                    )
                                    service == IntroSubmitService.THE_INTRODB ->
                                        TheIntroDb.lastSubmitError?.takeIf { it.isNotBlank() }
                                    else -> null
                                } ?: couldNotSend
                            }
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
                onReset = {
                    setSubtitleDelay(subtitleDelayAfterReset())
                    subtitleSyncHeardPositionMs = null
                    subtitleSyncSawPositionMs = null
                },
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
                showAudioDelay = playerController?.supportsAudioDelay == true,
                audioDelayMs = audioDelayMs,
                onAudioDelayChanged = { setAudioDelay(it) },
                showAudioAmplification = playerController?.supportsAudioAmplification == true,
                audioAmplificationDb = audioAmplificationDb,
                keepAudioAmplification = playerSettingsUiState.persistAudioAmplification,
                onAudioAmplificationChanged = { setAudioAmplification(it) },
                onKeepAudioAmplificationChanged = { setKeepAudioAmplification(it) },
                showCenterMix = centerMixAvailable,
                centerMixLevelDb = centerMixLevelDb,
                onCenterMixLevelChanged = { setCenterMixLevel(it) },
                downmixEnabled = playerSettingsUiState.downmixEnabled,
                onDownmixChanged = PlayerSettingsRepository::setDownmixEnabled,
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
                episodesEmptyNeedsRetry = episodesEmptyNeedsRetry,
                episodesComplete = episodesComplete,
                blurUnwatchedEpisodes = metaScreenSettingsUiState.blurUnwatchedEpisodes,
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

private fun PlayerScreenRuntime.episodeCodeLine(): String? {
    val season = activeSeasonNumber ?: return null
    val episode = activeEpisodeNumber ?: return null
    return "S$season E$episode"
}

private fun PlayerScreenRuntime.markingImdbId(): String =
    activeVideoId?.split(":")?.firstOrNull()?.takeIf { it.startsWith("tt") }
        ?: parentMetaId.takeIf { it.startsWith("tt") }
        ?: metaUiState.meta?.id?.takeIf { it.startsWith("tt") }
        ?: metaUiState.meta?.imdbId?.takeIf { it.startsWith("tt") }
        ?: ""

private fun introSubmitBlockMessage(
    reason: IntroSubmitBlockReason,
    requiredKeyMessage: String,
    introDbPrefixMessage: String,
    theIntroDbPrefixMessage: String,
    introDbMovieMessage: String,
    introDbEpisodeMessage: String,
): String = when (reason) {
    IntroSubmitBlockReason.MISSING_KEY -> requiredKeyMessage
    IntroSubmitBlockReason.INTRODB_KEY_SHAPE -> introDbPrefixMessage
    IntroSubmitBlockReason.THEINTRODB_KEY_SHAPE -> theIntroDbPrefixMessage
    IntroSubmitBlockReason.INTRODB_MOVIE -> introDbMovieMessage
    IntroSubmitBlockReason.INTRODB_EPISODE -> introDbEpisodeMessage
}

private sealed interface MarkPost {
    data object Sent : MarkPost
    data object AlreadySubmitted : MarkPost
    data class Blocked(val reason: IntroSubmitBlockReason) : MarkPost
    data object InvalidKey : MarkPost
    data object Failed : MarkPost
}

private suspend fun PlayerScreenRuntime.postMark(
    service: IntroSubmitService,
    savedKey: String,
    times: Pair<Double, Double>,
    segmentType: String,
    season: Int?,
    episode: Int?,
    isMovie: Boolean,
    durationMs: Long,
    videoId: String?,
    parentMetaId: String,
    parentMetaType: String,
    imdbId: String,
): MarkPost {
    when (val ready = prepareIntroSubmitKey(service, savedKey, savedKey)) {
        IntroSubmitKeyPrepareResult.Ready -> Unit
        IntroSubmitKeyPrepareResult.Invalid -> return MarkPost.InvalidKey
        is IntroSubmitKeyPrepareResult.Rejected -> return MarkPost.Blocked(
            when (ready.problem) {
                IntroSubmitKeyProblem.MISSING -> IntroSubmitBlockReason.MISSING_KEY
                IntroSubmitKeyProblem.INTRODB_PREFIX -> IntroSubmitBlockReason.INTRODB_KEY_SHAPE
                IntroSubmitKeyProblem.THEINTRODB_PREFIX -> IntroSubmitBlockReason.THEINTRODB_KEY_SHAPE
                IntroSubmitKeyProblem.NONE -> IntroSubmitBlockReason.MISSING_KEY
            },
        )
    }
    val (sentSeason, sentEpisode) = movieSubmitSeasonEpisode(isMovie, season, episode)
    val sent = submitManualSkipSegment(
        imdbId = imdbId,
        season = sentSeason ?: 0,
        episode = sentEpisode ?: 0,
        startSec = times.first,
        endSec = times.second,
        segmentType = segmentType,
        durationMs = durationMs,
        isMovie = isMovie,
        videoId = videoId ?: parentMetaId.ifBlank { null },
        parentMetaType = parentMetaType,
        service = service,
    )
    return when (sent) {
        ManualSkipSubmitOutcome.SENT -> MarkPost.Sent
        ManualSkipSubmitOutcome.ALREADY_SUBMITTED -> MarkPost.AlreadySubmitted
        ManualSkipSubmitOutcome.FAILED -> MarkPost.Failed
    }
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
