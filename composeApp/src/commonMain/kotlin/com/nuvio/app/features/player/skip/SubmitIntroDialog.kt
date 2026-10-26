package com.nuvio.app.features.player.skip

import com.nuvio.app.features.player.formatPlaybackTime
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.TextButton
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.rememberDismissSoftwareKeyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.PlayerGlassFallback
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerToolPanel
import com.nuvio.app.features.player.PlayerToolPanelCloseSize
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.settings_hide_secret
import nuvio.composeapp.generated.resources.settings_playback_introdb_invalid_key
import nuvio.composeapp.generated.resources.settings_show_secret
import nuvio.composeapp.generated.resources.submit_intro_api_key_introdb_hint
import nuvio.composeapp.generated.resources.submit_intro_api_key_label
import nuvio.composeapp.generated.resources.submit_intro_api_key_required
import nuvio.composeapp.generated.resources.submit_intro_api_key_saved
import nuvio.composeapp.generated.resources.submit_intro_api_key_theintrodb_hint
import nuvio.composeapp.generated.resources.submit_intro_introdb_episode
import nuvio.composeapp.generated.resources.submit_intro_introdb_movie
import nuvio.composeapp.generated.resources.submit_intro_introdb_prefix
import nuvio.composeapp.generated.resources.submit_intro_key_saved_submit_failed
import nuvio.composeapp.generated.resources.submit_intro_service_introdb
import nuvio.composeapp.generated.resources.submit_intro_service_label
import nuvio.composeapp.generated.resources.submit_intro_service_theintrodb
import nuvio.composeapp.generated.resources.submit_intro_theintrodb_prefix
import nuvio.composeapp.generated.resources.submit_intro_end_short
import nuvio.composeapp.generated.resources.submit_intro_segment_intro
import nuvio.composeapp.generated.resources.submit_intro_segment_outro
import nuvio.composeapp.generated.resources.submit_intro_segment_preview
import nuvio.composeapp.generated.resources.submit_intro_segment_recap
import nuvio.composeapp.generated.resources.submit_intro_start_short
import nuvio.composeapp.generated.resources.submit_intro_title
import nuvio.composeapp.generated.resources.submit_intro_segment_credits
import nuvio.composeapp.generated.resources.submit_intro_submit_named
import nuvio.composeapp.generated.resources.submit_intro_sent_thanks
import nuvio.composeapp.generated.resources.submit_intro_try_again
import nuvio.composeapp.generated.resources.submit_intro_end_after_start
import nuvio.composeapp.generated.resources.submit_intro_past_duration
import nuvio.composeapp.generated.resources.submit_intro_soft_long
import nuvio.composeapp.generated.resources.submit_intro_soft_short
import nuvio.composeapp.generated.resources.submit_intro_length
import nuvio.composeapp.generated.resources.submit_intro_your_submissions
import nuvio.composeapp.generated.resources.submit_intro_none_episode
import nuvio.composeapp.generated.resources.submit_intro_end_of_video
import nuvio.composeapp.generated.resources.submit_intro_copy_link
import nuvio.composeapp.generated.resources.submit_intro_none_for_title
import nuvio.composeapp.generated.resources.submit_intro_nudge_back_5
import nuvio.composeapp.generated.resources.submit_intro_nudge_forward_5
import nuvio.composeapp.generated.resources.submit_intro_time_now
import nuvio.composeapp.generated.resources.submit_intro_time_nudge_back_1
import nuvio.composeapp.generated.resources.submit_intro_time_nudge_forward_1
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SubmitIntroDialog(
    imdbId: String,
    season: Int,
    episode: Int,
    currentTimeSec: Double,
    segmentType: String,
    onSegmentTypeChange: (String) -> Unit,
    startTimeStr: String,
    onStartTimeChange: (String) -> Unit,
    endTimeStr: String,
    onEndTimeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSuccess: (IntroSubmitResult) -> Unit,
    durationMs: Long = 0L,
    isMovie: Boolean = false,
    videoId: String? = null,
    parentMetaType: String = "",
    existingSegments: List<SkipInterval> = emptyList(),
    submittedTypesInSession: Set<String> = emptySet(),
    /** Show/movie id for "Copy share link"; null hides it. */
    shareMetaId: String? = null,
    episodeTitle: String? = null,
    releaseInfo: String? = null,
    onPreviewSeekMs: (Long) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val dismissKeyboard = rememberDismissSoftwareKeyboard()
    val scrollState = rememberScrollState()
    var isSubmitting by remember { mutableStateOf(false) }
    var isSavingKey by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }
    var submitFailed by remember { mutableStateOf(false) }
    var successDelivered by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedField by remember { mutableStateOf(SubmitTimeField.START) }
    var wheelOpen by remember { mutableStateOf(false) }
    var showSubmissions by remember { mutableStateOf(false) }
    var pendingResult by remember { mutableStateOf<IntroSubmitResult?>(null) }
    val onSuccessUpdated by rememberUpdatedState(onSuccess)
    fun finishSuccess() {
        if (successDelivered) return
        val posted = pendingResult ?: return
        successDelivered = true
        onSuccessUpdated(posted)
    }
    var keyStatusMessage by remember { mutableStateOf<String?>(null) }
    val initialSettings = remember { PlayerSettingsRepository.uiState.value }
    var selectedService by remember {
        mutableStateOf(defaultIntroSubmitService(initialSettings.introDbApiKey, initialSettings.theIntroDbApiKey))
    }
    var introDbKeyDraft by remember { mutableStateOf(savedKeyForService(IntroSubmitService.INTRODB, initialSettings.introDbApiKey, initialSettings.theIntroDbApiKey)) }
    var theIntroDbKeyDraft by remember { mutableStateOf(savedKeyForService(IntroSubmitService.THE_INTRODB, initialSettings.introDbApiKey, initialSettings.theIntroDbApiKey)) }
    var keyVisible by remember { mutableStateOf(false) }
    val playerSettings by PlayerSettingsRepository.uiState.collectAsState()
    val savedKeyForSelected = savedKeyForService(
        selectedService,
        playerSettings.introDbApiKey,
        playerSettings.theIntroDbApiKey,
    )
    val showServiceSection = introSubmitShowsServiceSection(selectedService, savedKeyForSelected)
    val keyDraft = if (selectedService == IntroSubmitService.INTRODB) introDbKeyDraft else theIntroDbKeyDraft
    val requiredKeyMessage = stringResource(Res.string.submit_intro_api_key_required)
    val introDbPrefixMessage = stringResource(Res.string.submit_intro_introdb_prefix)
    val theIntroDbPrefixMessage = stringResource(Res.string.submit_intro_theintrodb_prefix)
    val introDbMovieMessage = stringResource(Res.string.submit_intro_introdb_movie)
    val introDbEpisodeMessage = stringResource(Res.string.submit_intro_introdb_episode)
    val invalidKeyMessage = stringResource(Res.string.settings_playback_introdb_invalid_key)
    val keySavedMessage = stringResource(Res.string.submit_intro_api_key_saved)
    val keySavedSubmitFailedMessage = stringResource(Res.string.submit_intro_key_saved_submit_failed)
    val keyHint = stringResource(
        if (selectedService == IntroSubmitService.INTRODB) {
            Res.string.submit_intro_api_key_introdb_hint
        } else {
            Res.string.submit_intro_api_key_theintrodb_hint
        },
    )
    val busy = isSubmitting || isSavingKey
    fun problemMessage(problem: IntroSubmitKeyProblem): String = when (problem) {
        IntroSubmitKeyProblem.MISSING -> requiredKeyMessage
        IntroSubmitKeyProblem.INTRODB_PREFIX -> introDbPrefixMessage
        IntroSubmitKeyProblem.THEINTRODB_PREFIX -> theIntroDbPrefixMessage
        IntroSubmitKeyProblem.NONE -> ""
    }
    fun blockMessage(reason: IntroSubmitBlockReason): String = when (reason) {
        IntroSubmitBlockReason.MISSING_KEY -> requiredKeyMessage
        IntroSubmitBlockReason.INTRODB_KEY_SHAPE -> introDbPrefixMessage
        IntroSubmitBlockReason.THEINTRODB_KEY_SHAPE -> theIntroDbPrefixMessage
        IntroSubmitBlockReason.INTRODB_MOVIE -> introDbMovieMessage
        IntroSubmitBlockReason.INTRODB_EPISODE -> introDbEpisodeMessage
    }
    fun updateKeyDraft(value: String) {
        if (selectedService == IntroSubmitService.INTRODB) {
            introDbKeyDraft = value
        } else {
            theIntroDbKeyDraft = value
        }
        errorMessage = null
        keyStatusMessage = null
    }
    
    val contentKey = flagSubmitContentKey(imdbId, season, episode, isMovie)
    val latestSubmittedTypes by rememberUpdatedState(submittedTypesInSession)
    var localTypes by remember(contentKey, submittedTypesInSession) {
        mutableStateOf(flagTypesShownForContent(contentKey, submittedTypesInSession))
    }
    var remoteTypes by remember(contentKey) {
        mutableStateOf(remoteFlagTypesByContentKey[contentKey].orEmpty())
    }
    /** Null until listed (no TheIntroDB key, or the title has no TMDB id): the button stays hidden. */
    var mySubmissions by remember(contentKey) { mutableStateOf<List<TheIntroDbUserSubmission>?>(null) }
    var mySubmissionsError by remember(contentKey) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(
        contentKey,
        imdbId,
        season,
        episode,
        isMovie,
        videoId,
        playerSettings.theIntroDbApiKey,
        playerSettings.introDbApiKey,
    ) {
        val apiKey = savedKeyForService(
            IntroSubmitService.THE_INTRODB,
            playerSettings.introDbApiKey,
            playerSettings.theIntroDbApiKey,
        )
        if (apiKey.isBlank()) return@LaunchedEffect
        val mediaType = if (isMovie) "movie" else "tv"
        val tmdbId = try {
            TmdbService.ensureTmdbId(
                videoId = videoId?.takeIf { it.isNotBlank() } ?: imdbId,
                mediaType = mediaType,
                fallbackImdbId = imdbId.takeIf { it.startsWith("tt", ignoreCase = true) },
            )?.toIntOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@LaunchedEffect
        val records = try {
            TheIntroDb.listMySubmissions(apiKey, tmdbId, mediaType)
                .also {
                    mySubmissions = it
                    mySubmissionsError = TheIntroDb.lastListError
                }
                .mapNotNull { it.toFlagRecord(tmdbId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@LaunchedEffect
        }
        val types = submittedFlagTypesForTitle(
            records = records,
            tmdbId = tmdbId,
            isMovie = isMovie,
            season = season,
            episode = episode,
        )
        if (contentKey.isNotEmpty()) {
            remoteFlagTypesByContentKey[contentKey] = types
            FlagSubmitLedger.replaceTypes(contentKey, types)
            latestSubmittedTypes.forEach { FlagSubmitLedger.rememberType(contentKey, it) }
            localTypes = flagTypesShownForContent(contentKey, latestSubmittedTypes)
        }
        remoteTypes = types
    }
    val disabledTypes = localTypes + remoteTypes

    val panelTypes = remember(isMovie) { submitPanelSegmentTypes(isMovie) }
    val availableTypes = remember(disabledTypes, panelTypes) {
        panelTypes.filterNot { it in disabledTypes }
    }
    
    androidx.compose.runtime.LaunchedEffect(segmentType, disabledTypes) {
        if (segmentType in disabledTypes && availableTypes.isNotEmpty()) {
            onSegmentTypeChange(availableTypes.first())
        } else if (segmentType !in panelTypes && availableTypes.isNotEmpty()) {
            onSegmentTypeChange(availableTypes.first())
        }
    }

    fun beginSubmit(absentSegment: Boolean) {
        if (showSuccess || isSubmitting) return
        dismissKeyboard()
        submitFailed = false
        val times = resolveIntroSubmitTimes(
            service = selectedService,
            absentSegment = absentSegment,
            startTimeStr = startTimeStr,
            endTimeStr = endTimeStr,
        )
        if (times == null) {
            errorMessage = "Set a start and end time first"
            submitFailed = true
            return
        }
        val (start, end) = times
        val service = selectedService
        val savedNow = savedKeyForService(
            service,
            PlayerSettingsRepository.uiState.value.introDbApiKey,
            PlayerSettingsRepository.uiState.value.theIntroDbApiKey,
        )
        val draft = if (introSubmitShowsApiKeyField(service, savedNow)) keyDraft else savedNow
        val block = introSubmitBlockReason(
            service = service,
            apiKey = draft,
            isMovie = isMovie,
            imdbId = imdbId,
            season = season,
            episode = episode,
        )
        if (block != null) {
            errorMessage = blockMessage(block)
            submitFailed = true
            return
        }
        isSubmitting = true
        errorMessage = null
        keyStatusMessage = null
        val captured = IntroSubmitResult(
            segmentType = segmentType,
            startSec = start,
            endSec = end,
            absentSegment = absentSegment,
        )
        scope.launch {
            var keyWasNew = false
            var succeeded = false
            try {
                val saved = savedKeyForService(
                    service,
                    PlayerSettingsRepository.uiState.value.introDbApiKey,
                    PlayerSettingsRepository.uiState.value.theIntroDbApiKey,
                )
                keyWasNew = draft.trim() != saved.trim()
                when (val prepared = prepareIntroSubmitKey(service, draft, saved)) {
                    IntroSubmitKeyPrepareResult.Invalid -> {
                        errorMessage = invalidKeyMessage
                        submitFailed = true
                        return@launch
                    }
                    is IntroSubmitKeyPrepareResult.Rejected -> {
                        errorMessage = problemMessage(prepared.problem)
                        submitFailed = true
                        return@launch
                    }
                    IntroSubmitKeyPrepareResult.Ready -> Unit
                }
                val result = submitManualSkipSegment(
                    imdbId = imdbId,
                    season = season,
                    episode = episode,
                    startSec = captured.startSec,
                    endSec = captured.endSec,
                    segmentType = captured.segmentType,
                    durationMs = durationMs,
                    isMovie = isMovie,
                    videoId = videoId,
                    parentMetaType = parentMetaType,
                    service = service,
                )
                if (result) {
                    val submittedKey = flagSubmitContentKey(imdbId, season, episode, isMovie)
                    FlagSubmitLedger.rememberType(submittedKey, captured.segmentType)
                    canonicalFlagSegmentType(captured.segmentType)?.let { type ->
                        if (submittedKey.isNotEmpty()) {
                            remoteFlagTypesByContentKey[submittedKey] =
                                remoteFlagTypesByContentKey[submittedKey].orEmpty() + type
                        }
                    }
                    SkipIntroRepository.clearCache()
                    pendingResult = captured
                    succeeded = true
                } else {
                    submitFailed = true
                    errorMessage = if (keyWasNew) {
                        keySavedSubmitFailedMessage
                    } else {
                        TheIntroDb.lastSubmitError.takeIf { service == IntroSubmitService.THE_INTRODB }
                            ?: "Submit failed. Check the selected service and try again."
                    }
                }
            } finally {
                isSubmitting = false
            }
            if (succeeded) {
                showSuccess = true
                kotlinx.coroutines.delay(1_500)
                finishSuccess()
            }
        }
    }

    val durationSec = if (durationMs > 0L) durationMs / 1000.0 else null
    val startSec = parseTimeToSeconds(startTimeStr)
    val endSec = parseTimeToSeconds(endTimeStr)
    val problems = submitTimestampProblems(startSec, endSec, durationSec)
    val warning = submitTimestampWarning(segmentType, startSec, endSec)
    val rangeSpan = submitRangeSpan(startSec, endSec, durationSec)
    val panelSubtitle = submitPanelSubtitle(
        isMovie = isMovie,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        releaseInfo = releaseInfo,
    )
    val tokens = MaterialTheme.nuvio
    val endAfterStartMessage = stringResource(Res.string.submit_intro_end_after_start)
    val pastDurationMessage = stringResource(Res.string.submit_intro_past_duration)
    val softLongMessage = stringResource(Res.string.submit_intro_soft_long)
    val softShortMessage = stringResource(Res.string.submit_intro_soft_short)
    val nowLabel = stringResource(Res.string.submit_intro_time_now)
    val endOfVideoLabel = stringResource(Res.string.submit_intro_end_of_video)
    val introLabel = stringResource(Res.string.submit_intro_segment_intro)
    val recapLabel = stringResource(Res.string.submit_intro_segment_recap)
    val outroLabel = stringResource(Res.string.submit_intro_segment_outro)
    val creditsLabel = stringResource(Res.string.submit_intro_segment_credits)
    val previewLabel = stringResource(Res.string.submit_intro_segment_preview)
    val noneEpisodeLabel = stringResource(Res.string.submit_intro_none_episode)
    val sentThanksLabel = stringResource(Res.string.submit_intro_sent_thanks)
    val tryAgainLabel = stringResource(Res.string.submit_intro_try_again)
    val copyLinkDescription = stringResource(Res.string.submit_intro_copy_link)
    val nudgeBack5 = stringResource(Res.string.submit_intro_nudge_back_5)
    val nudgeBack1 = stringResource(Res.string.submit_intro_time_nudge_back_1)
    val nudgeForward1 = stringResource(Res.string.submit_intro_time_nudge_forward_1)
    val nudgeForward5 = stringResource(Res.string.submit_intro_nudge_forward_5)
    val noneForTitle = stringResource(Res.string.submit_intro_none_for_title)
    val bringIntoViewRequester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    fun labelForType(type: String): String = when (type) {
        "recap" -> recapLabel
        "outro" -> if (isMovie) creditsLabel else outroLabel
        "preview" -> previewLabel
        else -> introLabel
    }
    fun copyShareLink() {
        val metaId = shareMetaId
        val start = parseTimeToSeconds(startTimeStr)
        val end = parseTimeToSeconds(endTimeStr)
        if (metaId == null || start == null || end == null || end <= start) {
            errorMessage = endAfterStartMessage
            return
        }
        val link = SharedSkipMark(
            parentMetaId = metaId,
            season = season.takeIf { !isMovie },
            episode = episode.takeIf { !isMovie },
            type = segmentType,
            startMs = (start * 1000).toLong(),
            endMs = (end * 1000).toLong(),
        ).toLink()
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(link))
        com.nuvio.app.core.ui.NuvioToastController.show("Link copied: open it on another Nuvio to import this mark")
    }
    fun applyNudge(delta: Int) {
        val next = if (selectedField == SubmitTimeField.START) {
            nudgeSkipTimestamp(startTimeStr, delta, durationSec)
        } else {
            nudgeSkipTimestamp(endTimeStr, delta, durationSec)
        }
        if (selectedField == SubmitTimeField.START) onStartTimeChange(next) else onEndTimeChange(next)
    }
    fun editDigits(field: SubmitTimeField) {
        if (isSubmitting || showSuccess) return
        if (selectedField == field && wheelOpen) {
            wheelOpen = false
        } else {
            selectedField = field
            wheelOpen = true
        }
    }
    val checksFail = problems.isNotEmpty()
    val inputsLocked = isSubmitting || showSuccess
    val submitLabel = when {
        showSuccess -> sentThanksLabel
        submitFailed && !checksFail -> tryAgainLabel
        else -> stringResource(Res.string.submit_intro_submit_named, labelForType(segmentType))
    }
    val lengthLabel = if (startSec != null && endSec != null && endSec > startSec) {
        stringResource(Res.string.submit_intro_length, formatSecondsToHms(endSec - startSec))
    } else {
        null
    }
    val submissionsTitle = stringResource(
        Res.string.submit_intro_your_submissions,
        mySubmissions?.size ?: 0,
    )
    val showEndOfVideo = submitShowsEndOfVideo(segmentType) && durationMs > 0L

    PlayerToolPanel(
        visible = true,
        title = if (showSubmissions) submissionsTitle else stringResource(Res.string.submit_intro_title),
        subtitle = if (showSubmissions) null else panelSubtitle,
        onBack = if (showSubmissions) {
            { showSubmissions = false }
        } else {
            null
        },
        onDismiss = { if (showSuccess) finishSuccess() else onDismiss() },
        dismissLocked = isSubmitting || showSuccess,
        headerActions = {
            if (!showSubmissions && shareMetaId != null) {
                SubmitPanelIconButton(
                    icon = Icons.Rounded.Link,
                    contentDescription = copyLinkDescription,
                    onClick = ::copyShareLink,
                )
            }
        },
        footer = if (showSubmissions) {
            null
        } else {
            {
                if (!isMovie && segmentType == "intro" && selectedService == IntroSubmitService.THE_INTRODB) {
                    val noneDisabled = "intro" in disabledTypes || busy || showSuccess
                    Text(
                        text = noneEpisodeLabel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !noneDisabled) { beginSubmit(absentSegment = true) }
                            .padding(vertical = 8.dp),
                        color = Color.White.copy(alpha = if (noneDisabled) 0.38f else 0.72f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                if (errorMessage != null && !showSuccess) {
                    Text(
                        text = errorMessage.orEmpty(),
                        modifier = Modifier.padding(bottom = 6.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val buttonColor = when {
                    showSuccess -> tokens.colors.success
                    checksFail -> Color.White.copy(alpha = 0.12f)
                    else -> tokens.colors.accent
                }
                val buttonContent = when {
                    showSuccess -> Color.White
                    checksFail -> Color.White.copy(alpha = 0.38f)
                    else -> tokens.colors.onAccent
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(buttonColor)
                        .clickable(enabled = !checksFail && !busy && !showSuccess) {
                            beginSubmit(absentSegment = false)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSubmitting) {
                        NuvioLoadingIndicator(
                            color = buttonContent,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (showSuccess) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = buttonContent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Text(
                                text = submitLabel,
                                color = buttonContent,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
                if (mySubmissions != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showSubmissions = true }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = submissionsTitle,
                            modifier = Modifier.weight(1f),
                            color = Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "›",
                            color = Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        },
    ) {
        if (showSubmissions) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val rows = mySubmissions
                if (rows.isNullOrEmpty() || mySubmissionsError != null) {
                    Text(
                        text = mySubmissionsError ?: noneForTitle,
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                rows.orEmpty()
                    .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
                    .forEach { submission ->
                        Text(
                            text = submissionLine(submission),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showServiceSection) {
                    Text(
                        text = stringResource(Res.string.submit_intro_service_label),
                        color = MaterialTheme.nuvio.colors.textMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SegmentTypeButton(
                            label = stringResource(Res.string.submit_intro_service_introdb),
                            icon = Icons.Rounded.PlayCircleOutline,
                            selected = selectedService == IntroSubmitService.INTRODB,
                            onClick = {
                                selectedService = IntroSubmitService.INTRODB
                                errorMessage = null
                                keyStatusMessage = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        SegmentTypeButton(
                            label = stringResource(Res.string.submit_intro_service_theintrodb),
                            icon = Icons.Rounded.Public,
                            selected = selectedService == IntroSubmitService.THE_INTRODB,
                            onClick = {
                                selectedService = IntroSubmitService.THE_INTRODB
                                errorMessage = null
                                keyStatusMessage = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = keyHint,
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .bringIntoViewRequester(bringIntoViewRequester),
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.08f),
                            border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.22f)),
                        ) {
                            BasicTextField(
                                value = keyDraft,
                                onValueChange = ::updateKeyDraft,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { focus ->
                                        if (focus.isFocused) {
                                            scope.launch { bringIntoViewRequester.bringIntoView() }
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                                visualTransformation = if (keyVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                cursorBrush = SolidColor(tokens.colors.accent),
                                singleLine = true,
                                decorationBox = { inner ->
                                    Box {
                                        if (keyDraft.isEmpty()) {
                                            Text(
                                                text = stringResource(Res.string.submit_intro_api_key_label),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = Color.White.copy(alpha = 0.72f),
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                        }
                        IconButton(onClick = { keyVisible = !keyVisible }, enabled = !busy) {
                            Icon(
                                imageVector = if (keyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = stringResource(
                                    if (keyVisible) Res.string.settings_hide_secret else Res.string.settings_show_secret,
                                ),
                                tint = Color.White.copy(alpha = 0.72f),
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            dismissKeyboard()
                            isSavingKey = true
                            errorMessage = null
                            keyStatusMessage = null
                            val service = selectedService
                            val draft = keyDraft
                            scope.launch {
                                val saved = savedKeyForService(
                                    service,
                                    PlayerSettingsRepository.uiState.value.introDbApiKey,
                                    PlayerSettingsRepository.uiState.value.theIntroDbApiKey,
                                )
                                when (val result = prepareIntroSubmitKey(service, draft, saved)) {
                                    IntroSubmitKeyPrepareResult.Ready -> keyStatusMessage = keySavedMessage
                                    IntroSubmitKeyPrepareResult.Invalid -> errorMessage = invalidKeyMessage
                                    is IntroSubmitKeyPrepareResult.Rejected -> errorMessage = problemMessage(result.problem)
                                }
                                isSavingKey = false
                            }
                        },
                        enabled = !busy,
                    ) {
                        if (isSavingKey) {
                            NuvioLoadingIndicator(
                                color = tokens.colors.accent,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Text(stringResource(Res.string.action_save))
                        }
                    }
                    if (keyStatusMessage != null) {
                        Text(
                            text = keyStatusMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.accent,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    panelTypes.forEach { type ->
                        SubmitTypeChip(
                            label = labelForType(type),
                            selected = segmentType == type,
                            submitted = type in disabledTypes,
                            enabled = !inputsLocked,
                            onClick = { onSegmentTypeChange(type) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                SubmitTimeRow(
                    label = stringResource(Res.string.submit_intro_start_short),
                    value = startTimeStr,
                    selected = selectedField == SubmitTimeField.START,
                    nowLabel = nowLabel,
                    enabled = !inputsLocked,
                    onSelect = { selectedField = SubmitTimeField.START },
                    onEditDigits = { editDigits(SubmitTimeField.START) },
                    onNow = {
                        selectedField = SubmitTimeField.START
                        onStartTimeChange(formatSecondsToHms(currentTimeSec))
                    },
                )
                SubmitTimeRow(
                    label = stringResource(Res.string.submit_intro_end_short),
                    value = endTimeStr,
                    selected = selectedField == SubmitTimeField.END,
                    nowLabel = nowLabel,
                    enabled = !inputsLocked,
                    onSelect = { selectedField = SubmitTimeField.END },
                    onEditDigits = { editDigits(SubmitTimeField.END) },
                    onNow = {
                        selectedField = SubmitTimeField.END
                        onEndTimeChange(formatSecondsToHms(currentTimeSec))
                    },
                    endOfVideoLabel = if (showEndOfVideo) endOfVideoLabel else null,
                    onEndOfVideo = if (showEndOfVideo) {
                        {
                            selectedField = SubmitTimeField.END
                            onEndTimeChange(formatSecondsToHms(durationMs / 1000.0))
                        }
                    } else {
                        null
                    },
                )
                SubmitNudgeRow(
                    onNudge = ::applyNudge,
                    enabled = !inputsLocked,
                    backFiveDescription = nudgeBack5,
                    backOneDescription = nudgeBack1,
                    forwardOneDescription = nudgeForward1,
                    forwardFiveDescription = nudgeForward5,
                )
                if (wheelOpen && !inputsLocked) {
                    val wheelValue = if (selectedField == SubmitTimeField.START) startTimeStr else endTimeStr
                    SkipTimeWheels(
                        value = wheelValue,
                        onPartChange = { part, digits ->
                            val next = replaceSkipTimePart(wheelValue, part, digits.toString())
                            if (selectedField == SubmitTimeField.START) {
                                onStartTimeChange(next)
                            } else {
                                onEndTimeChange(next)
                            }
                        },
                    )
                }
                SubmitRangeBar(
                    span = rangeSpan,
                    onClick = click@{
                        val start = startSec ?: return@click
                        onPreviewSeekMs((start * 1000.0).toLong())
                    },
                )
                if (lengthLabel != null) {
                    Text(
                        text = lengthLabel,
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (SubmitTimestampProblem.EndNotAfterStart in problems) {
                    Text(
                        text = endAfterStartMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (
                    SubmitTimestampProblem.StartPastDuration in problems ||
                    SubmitTimestampProblem.EndPastDuration in problems
                ) {
                    Text(
                        text = pastDurationMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val warningText = when (warning) {
                    SubmitTimestampWarning.LongerThanUsual -> softLongMessage
                    SubmitTimestampWarning.ShorterThanUsual -> softShortMessage
                    null -> null
                }
                if (warningText != null) {
                    Text(
                        text = warningText,
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

internal sealed interface IntroSubmitKeyPrepareResult {
    data object Ready : IntroSubmitKeyPrepareResult
    data object Invalid : IntroSubmitKeyPrepareResult
    data class Rejected(val problem: IntroSubmitKeyProblem) : IntroSubmitKeyPrepareResult
}

internal suspend fun prepareIntroSubmitKey(
    service: IntroSubmitService,
    apiKey: String,
    savedKey: String,
): IntroSubmitKeyPrepareResult {
    val trimmed = apiKey.trim()
    val problem = introSubmitKeyProblem(service, trimmed)
    if (problem != IntroSubmitKeyProblem.NONE) return IntroSubmitKeyPrepareResult.Rejected(problem)
    if (trimmed != savedKey.trim()) {
        val valid = try {
            when (service) {
                IntroSubmitService.INTRODB -> SkipIntroRepository.verifyIntroDbApiKey(trimmed)
                IntroSubmitService.THE_INTRODB -> TheIntroDb.verifyApiKey(trimmed)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!valid) return IntroSubmitKeyPrepareResult.Invalid
        when (service) {
            IntroSubmitService.INTRODB -> PlayerSettingsRepository.setIntroDbApiKey(trimmed)
            IntroSubmitService.THE_INTRODB -> PlayerSettingsRepository.setTheIntroDbApiKey(trimmed)
        }
    }
    if (trimmed.isNotEmpty()) PlayerSettingsRepository.setIntroSubmitEnabled(true)
    return IntroSubmitKeyPrepareResult.Ready
}

internal suspend fun submitManualSkipSegment(
    imdbId: String,
    season: Int,
    episode: Int,
    startSec: Double,
    endSec: Double,
    segmentType: String,
    durationMs: Long,
    isMovie: Boolean,
    videoId: String?,
    parentMetaType: String,
    service: IntroSubmitService,
): Boolean {
    val settings = PlayerSettingsRepository.uiState.value
    if (!settings.introSubmitEnabled) return false
    val key = savedKeyForService(service, settings.introDbApiKey, settings.theIntroDbApiKey)
    if (introSubmitBlockReason(service, key, isMovie, imdbId, season, episode) != null) return false
    return when (service) {
        IntroSubmitService.INTRODB -> SkipIntroRepository.submitIntro(
            imdbId = imdbId,
            season = season,
            episode = episode,
            startSec = startSec,
            endSec = endSec,
            segmentType = segmentType,
        )
        IntroSubmitService.THE_INTRODB -> submitTheIntroDbSegment(
            apiKey = key,
            imdbId = imdbId,
            season = season,
            episode = episode,
            startSec = startSec,
            endSec = endSec,
            segmentType = segmentType,
            durationMs = durationMs,
            isMovie = isMovie,
            videoId = videoId,
            parentMetaType = parentMetaType,
        )
    }
}

private suspend fun submitTheIntroDbSegment(
    apiKey: String,
    imdbId: String,
    season: Int,
    episode: Int,
    startSec: Double,
    endSec: Double,
    segmentType: String,
    durationMs: Long,
    isMovie: Boolean,
    videoId: String?,
    parentMetaType: String,
): Boolean {
    val mediaType = when {
        isMovie -> "movie"
        parentMetaType.equals("movie", ignoreCase = true) -> "movie"
        else -> "tv"
    }
    val resolvedTmdb = TmdbService.ensureTmdbId(
        videoId = videoId?.takeIf { it.isNotBlank() } ?: imdbId,
        mediaType = mediaType,
        fallbackImdbId = imdbId.takeIf { it.startsWith("tt", ignoreCase = true) },
    )?.toIntOrNull()
    if (resolvedTmdb == null || resolvedTmdb <= 0) return false
    return TheIntroDb.submitTimestamp(
        apiKey = apiKey,
        tmdbId = resolvedTmdb,
        imdbId = imdbId.takeIf { it.startsWith("tt", ignoreCase = true) },
        type = mediaType,
        segment = segmentType,
        season = season.takeIf { mediaType == "tv" && season > 0 },
        episode = episode.takeIf { mediaType == "tv" && episode > 0 },
        startSec = startSec,
        endSec = endSec,
        videoDurationMs = durationMs.takeIf { it > 0L },
    )
}

@Composable
private fun SegmentTypeButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    disabled: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    val backgroundColor = when {
        disabled -> Color.White.copy(alpha = 0.08f)
        selected -> tokens.colors.accent
        else -> PlayerGlassFallback
    }
    val contentColor = when {
        disabled -> Color.White.copy(alpha = 0.38f)
        selected -> tokens.colors.onAccent
        else -> Color.White
    }

    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (disabled) TextDecoration.LineThrough else null,
            )
        }
    }
}

private val remoteFlagTypesByContentKey = mutableMapOf<String, Set<String>>()

private fun flagTypesShownForContent(contentKey: String, sessionTypes: Set<String>): Set<String> {
    return (FlagSubmitLedger.loadTypes(contentKey) + sessionTypes)
        .mapNotNull { canonicalFlagSegmentType(it) }
        .toSet()
}

private fun normalizeSegmentTypeForSubmit(type: String): String? {
    return when (type.trim().lowercase()) {
        "intro", "op", "mixed-op" -> "intro"
        "credits", "outro", "ed", "mixed-ed", "ending", "movie-credits" -> "outro"
        "recap" -> "recap"
        "preview" -> "preview"
        else -> null
    }
}

@Composable
private fun SubmitTypeChip(
    label: String,
    selected: Boolean,
    submitted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio
    val background = if (selected) tokens.colors.accent else PlayerGlassFallback
    val content = if (selected) tokens.colors.onAccent else Color.White
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(enabled = enabled && !submitted, onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = content.copy(alpha = if (submitted && !selected) 0.72f else 1f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (submitted) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = content,
                modifier = Modifier.padding(start = 4.dp).size(14.dp),
            )
        }
    }
}

@Composable
private fun SubmitPanelIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(PlayerToolPanelCloseSize)
            .clip(CircleShape)
            .background(PlayerGlassFallback)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

internal fun submissionLine(submission: TheIntroDbUserSubmission): String {
    val episode = if (submission.season != null && submission.episode != null) {
        "S${submission.season}E${submission.episode} · "
    } else ""
    val start = submission.startMs?.let(::formatPlaybackTime) ?: "start"
    val end = submission.endMs?.let(::formatPlaybackTime) ?: "end"
    val status = submission.status?.takeIf { it.isNotBlank() } ?: "pending"
    return "$episode${submission.segment.orEmpty()} $start–$end · $status"
}
