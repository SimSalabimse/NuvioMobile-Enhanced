package com.nuvio.app.features.player.skip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.rememberDismissSoftwareKeyboard
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.atLeastIosHitTarget
import com.nuvio.app.features.player.LocalPlayerSizeClass
import com.nuvio.app.features.player.PlayerMenuBackdrop
import com.nuvio.app.features.player.PlayerMenuRowFill
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSidePanel
import com.nuvio.app.features.player.PlayerSizeClass
import com.nuvio.app.features.player.playerMenuSelectedColor
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.isIos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.settings_hide_secret
import nuvio.composeapp.generated.resources.settings_playback_introdb_invalid_key
import nuvio.composeapp.generated.resources.settings_show_secret
import nuvio.composeapp.generated.resources.submit_intro_api_key_introdb_hint
import nuvio.composeapp.generated.resources.submit_intro_api_key_label
import nuvio.composeapp.generated.resources.submit_intro_api_key_required
import nuvio.composeapp.generated.resources.submit_intro_api_key_saved
import nuvio.composeapp.generated.resources.submit_intro_api_key_theintrodb_hint
import nuvio.composeapp.generated.resources.submit_intro_button_submit
import nuvio.composeapp.generated.resources.submit_intro_introdb_episode
import nuvio.composeapp.generated.resources.submit_intro_introdb_movie
import nuvio.composeapp.generated.resources.submit_intro_introdb_prefix
import nuvio.composeapp.generated.resources.submit_intro_key_saved_submit_failed
import nuvio.composeapp.generated.resources.submit_intro_service_introdb
import nuvio.composeapp.generated.resources.submit_intro_service_label
import nuvio.composeapp.generated.resources.submit_intro_none_intro
import nuvio.composeapp.generated.resources.submit_intro_none_outro
import nuvio.composeapp.generated.resources.submit_intro_none_preview
import nuvio.composeapp.generated.resources.submit_intro_none_recap
import nuvio.composeapp.generated.resources.submit_intro_service_theintrodb
import nuvio.composeapp.generated.resources.submit_intro_theintrodb_prefix
import nuvio.composeapp.generated.resources.submit_intro_end_short
import nuvio.composeapp.generated.resources.submit_intro_segment_intro
import nuvio.composeapp.generated.resources.submit_intro_segment_outro
import nuvio.composeapp.generated.resources.submit_intro_segment_preview
import nuvio.composeapp.generated.resources.submit_intro_segment_recap
import nuvio.composeapp.generated.resources.submit_intro_start_short
import nuvio.composeapp.generated.resources.submit_intro_title
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubmitIntroDialog(
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
    onSuccess: () -> Unit,
    durationMs: Long = 0L,
    isMovie: Boolean = false,
    videoId: String? = null,
    parentMetaType: String = "",
    existingSegments: List<SkipInterval> = emptyList(),
    submittedTypesInSession: Set<String> = emptySet(),
) {
    val scope = rememberCoroutineScope()
    val dismissKeyboard = rememberDismissSoftwareKeyboard()
    val scrollState = rememberScrollState()
    var isSubmitting by remember { mutableStateOf(false) }
    var isSavingKey by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
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
    val noneIntroLabel = stringResource(Res.string.submit_intro_none_intro)
    val noneRecapLabel = stringResource(Res.string.submit_intro_none_recap)
    val noneOutroLabel = stringResource(Res.string.submit_intro_none_outro)
    val nonePreviewLabel = stringResource(Res.string.submit_intro_none_preview)
    val noneSegmentLabel = when (segmentType) {
        "recap" -> noneRecapLabel
        "outro" -> noneOutroLabel
        "preview" -> nonePreviewLabel
        else -> noneIntroLabel
    }
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
    val localTypes = remember(contentKey, submittedTypesInSession) {
        (FlagSubmitLedger.loadTypes(contentKey) + submittedTypesInSession)
            .mapNotNull { canonicalFlagSegmentType(it) }
            .toSet()
    }
    var remoteTypes by remember(contentKey) {
        mutableStateOf(remoteFlagTypesByContentKey[contentKey].orEmpty())
    }
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
        val types = try {
            submittedFlagTypesForTitle(
                records = TheIntroDb.listMySubmissions(apiKey, tmdbId, mediaType).mapNotNull { it.toFlagRecord() },
                tmdbId = tmdbId,
                isMovie = isMovie,
                season = season,
                episode = episode,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptySet()
        }
        if (contentKey.isNotEmpty()) {
            remoteFlagTypesByContentKey[contentKey] = types
            types.forEach { FlagSubmitLedger.rememberType(contentKey, it) }
        }
        remoteTypes = types
    }
    val disabledTypes = localTypes + remoteTypes

    val availableTypes = remember(disabledTypes) {
        listOf("intro", "recap", "outro", "preview").filterNot { it in disabledTypes }
    }
    
    androidx.compose.runtime.LaunchedEffect(segmentType, disabledTypes) {
        if (segmentType in disabledTypes && availableTypes.isNotEmpty()) {
            onSegmentTypeChange(availableTypes.first())
        }
    }

    fun beginSubmit(absentSegment: Boolean) {
        dismissKeyboard()
        val times = resolveIntroSubmitTimes(
            service = selectedService,
            absentSegment = absentSegment,
            startTimeStr = startTimeStr,
            endTimeStr = endTimeStr,
        )
        if (times == null) {
            errorMessage = "Set a start and end time first"
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
            return
        }
        isSubmitting = true
        errorMessage = null
        keyStatusMessage = null
        scope.launch {
            var keyWasNew = false
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
                        return@launch
                    }
                    is IntroSubmitKeyPrepareResult.Rejected -> {
                        errorMessage = problemMessage(prepared.problem)
                        return@launch
                    }
                    IntroSubmitKeyPrepareResult.Ready -> Unit
                }
                val result = submitManualSkipSegment(
                    imdbId = imdbId,
                    season = season,
                    episode = episode,
                    startSec = start,
                    endSec = end,
                    segmentType = segmentType,
                    durationMs = durationMs,
                    isMovie = isMovie,
                    videoId = videoId,
                    parentMetaType = parentMetaType,
                    service = service,
                )
                if (result) {
                    val submittedKey = flagSubmitContentKey(imdbId, season, episode, isMovie)
                    FlagSubmitLedger.rememberType(submittedKey, segmentType)
                    canonicalFlagSegmentType(segmentType)?.let { type ->
                        if (submittedKey.isNotEmpty()) {
                            remoteFlagTypesByContentKey[submittedKey] =
                                remoteFlagTypesByContentKey[submittedKey].orEmpty() + type
                        }
                    }
                    onSuccess()
                } else {
                    errorMessage = if (keyWasNew) {
                        keySavedSubmitFailedMessage
                    } else {
                        "Submit failed. Check the selected service and try again."
                    }
                }
            } finally {
                isSubmitting = false
            }
        }
    }

    val phonePanel = LocalPlayerSizeClass.current != PlayerSizeClass.T
    val formContent: @Composable (Modifier) -> Unit = { formModifier ->
        Column(
            modifier = if (phonePanel) formModifier.fillMaxSize() else formModifier.verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.submit_intro_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.action_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            val submitIntroBody: @Composable () -> Unit = {
                if (showServiceSection) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(Res.string.submit_intro_service_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
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
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 0.dp.atLeastIosHitTarget()),
                                shape = RoundedCornerShape(12.dp),
                                color = PlayerMenuRowFill,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                            ) {
                                BasicTextField(
                                    value = keyDraft,
                                    onValueChange = ::updateKeyDraft,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    visualTransformation = if (keyVisible) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    singleLine = true,
                                    decorationBox = { inner ->
                                        Box {
                                            if (keyDraft.isEmpty()) {
                                                Text(
                                                    text = stringResource(Res.string.submit_intro_api_key_label),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(
                            modifier = Modifier.heightIn(min = 0.dp.atLeastIosHitTarget()),
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
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            } else {
                                Text(stringResource(Res.string.action_save))
                            }
                        }
                        if (keyStatusMessage != null) {
                            Text(
                                text = keyStatusMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                }
                }

                if (isIos) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SegmentTypeButton(
                                label = stringResource(Res.string.submit_intro_segment_intro),
                                icon = Icons.Rounded.PlayCircleOutline,
                                selected = segmentType == "intro",
                                onClick = { onSegmentTypeChange("intro") },
                                disabled = "intro" in disabledTypes,
                                modifier = Modifier.weight(1f),
                            )
                            SegmentTypeButton(
                                label = stringResource(Res.string.submit_intro_segment_recap),
                                icon = Icons.Rounded.Replay,
                                selected = segmentType == "recap",
                                onClick = { onSegmentTypeChange("recap") },
                                disabled = "recap" in disabledTypes,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SegmentTypeButton(
                                label = stringResource(Res.string.submit_intro_segment_outro),
                                icon = Icons.Rounded.StopCircle,
                                selected = segmentType == "outro",
                                onClick = { onSegmentTypeChange("outro") },
                                disabled = "outro" in disabledTypes,
                                modifier = Modifier.weight(1f),
                            )
                            SegmentTypeButton(
                                label = stringResource(Res.string.submit_intro_segment_preview),
                                icon = Icons.Rounded.Visibility,
                                selected = segmentType == "preview",
                                onClick = { onSegmentTypeChange("preview") },
                                disabled = "preview" in disabledTypes,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SegmentTypeButton(
                            label = stringResource(Res.string.submit_intro_segment_intro),
                            icon = Icons.Rounded.PlayCircleOutline,
                            selected = segmentType == "intro",
                            onClick = { onSegmentTypeChange("intro") },
                            disabled = "intro" in disabledTypes,
                            modifier = Modifier.weight(1f),
                        )
                        SegmentTypeButton(
                            label = stringResource(Res.string.submit_intro_segment_recap),
                            icon = Icons.Rounded.Replay,
                            selected = segmentType == "recap",
                            onClick = { onSegmentTypeChange("recap") },
                            disabled = "recap" in disabledTypes,
                            modifier = Modifier.weight(1f),
                        )
                        SegmentTypeButton(
                            label = stringResource(Res.string.submit_intro_segment_outro),
                            icon = Icons.Rounded.StopCircle,
                            selected = segmentType == "outro",
                            onClick = { onSegmentTypeChange("outro") },
                            disabled = "outro" in disabledTypes,
                            modifier = Modifier.weight(1f),
                        )
                        SegmentTypeButton(
                            label = stringResource(Res.string.submit_intro_segment_preview),
                            icon = Icons.Rounded.Visibility,
                            selected = segmentType == "preview",
                            onClick = { onSegmentTypeChange("preview") },
                            disabled = "preview" in disabledTypes,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (selectedService == IntroSubmitService.THE_INTRODB) {
                    val noneDisabled = segmentType in disabledTypes
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (noneDisabled) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                } else {
                                    PlayerMenuRowFill
                                },
                            )
                            .clickable(enabled = !busy && !noneDisabled) {
                                beginSubmit(absentSegment = true)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = noneSegmentLabel,
                            color = if (noneDisabled) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = if (noneDisabled) TextDecoration.LineThrough else null,
                        )
                    }
                }

                SkipTimestampEditor(
                    label = stringResource(Res.string.submit_intro_start_short),
                    value = startTimeStr,
                    onValueChange = onStartTimeChange,
                    onCapture = { onStartTimeChange(formatSecondsToHms(currentTimeSec)) },
                )

                SkipTimestampEditor(
                    label = stringResource(Res.string.submit_intro_end_short),
                    value = endTimeStr,
                    onValueChange = onEndTimeChange,
                    onCapture = { onEndTimeChange(formatSecondsToHms(currentTimeSec)) },
                    onUseDuration = if (durationMs > 0L) {
                        { onEndTimeChange(formatSecondsToHms(durationMs / 1000.0)) }
                    } else {
                        null
                    },
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (phonePanel) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        submitIntroBody()
                    }
                }
            } else {
                submitIntroBody()
            }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(PlayerMenuRowFill)
                            .clickable(enabled = !busy, onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(Res.string.action_cancel),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(2f)
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(enabled = !busy) {
                                beginSubmit(absentSegment = false)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSubmitting) {
                            NuvioLoadingIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp),
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Rounded.Send, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                                Text(
                                    text = stringResource(Res.string.submit_intro_button_submit),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

    if (phonePanel) {
        PlayerSidePanel(visible = true, onDismiss = onDismiss) {
            formContent(Modifier.fillMaxSize())
        }
    } else if (isIos) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        NuvioModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            showDragHandle = false,
            fullHeight = false,
            liquidGlass = true,
        ) {
            formContent(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .padding(top = 10.dp, bottom = 8.dp),
            )
        }
    } else {
        BasicAlertDialog(onDismissRequest = onDismiss) {
            BoxWithConstraints {
                val sheetHeight = minOf(640.dp, maxHeight * 0.86f)
                val cardShape = RoundedCornerShape(28.dp)
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .widthIn(max = 420.dp)
                        .heightIn(max = sheetHeight)
                        .clip(cardShape)
                        .border(1.dp, Color.White.copy(alpha = 0.22f), cardShape),
                ) {
                    PlayerMenuBackdrop(Modifier.matchParentSize(), cardShape)
                    formContent(
                        Modifier
                            .padding(24.dp)
                            .heightIn(max = sheetHeight),
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
    val backgroundColor = when {
        disabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        selected -> playerMenuSelectedColor(MaterialTheme.colorScheme.primary)
        else -> PlayerMenuRowFill
    }
    val contentColor = when {
        disabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
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

private fun normalizeSegmentTypeForSubmit(type: String): String? {
    return when (type.trim().lowercase()) {
        "intro", "op", "mixed-op" -> "intro"
        "credits", "outro", "ed", "mixed-ed", "ending", "movie-credits" -> "outro"
        "recap" -> "recap"
        "preview" -> "preview"
        else -> null
    }
}
