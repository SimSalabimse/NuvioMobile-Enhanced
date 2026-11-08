package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.FitSheetHeightHost
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.atLeastIosHitTarget
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.dismissPresentedNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.systemReduceMotionEnabled
import com.nuvio.app.core.ui.usesNativeNuvioBottomSheet
import com.nuvio.app.features.analysis.MediaAnalysisStatus
import com.nuvio.app.features.analysis.MediaAnalysisStore
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.OverviewOpenStep
import com.nuvio.app.features.details.SEASON_SEGMENT_TYPES
import com.nuvio.app.features.details.SeasonEpisodeRef
import com.nuvio.app.features.details.SeasonOverviewUiState
import com.nuvio.app.features.details.SegmentChipVisual
import com.nuvio.app.features.details.SegmentMark
import com.nuvio.app.features.details.localDetectionTypes
import com.nuvio.app.features.details.visibleSegmentMarks
import com.nuvio.app.features.details.overviewEpisodeOpenSteps
import com.nuvio.app.features.details.overviewLedgerScope
import com.nuvio.app.features.details.seasonEpisodeContentKey
import com.nuvio.app.features.details.seasonOverviewFontScale
import com.nuvio.app.features.details.seasonSwipeRevealsAction
import com.nuvio.app.features.details.seasonOverviewModel
import com.nuvio.app.features.details.seasonSegmentOverview
import com.nuvio.app.features.details.segmentChipModel
import com.nuvio.app.features.downloads.AnalysisTempDownloads
import com.nuvio.app.features.downloads.downloadEpisodesForAnalysis
import com.nuvio.app.features.downloads.seasonSheetDismissDurationMs
import com.nuvio.app.features.player.skip.OwnTheIntroDbLedger
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.analysis_part_credits
import nuvio.composeapp.generated.resources.season_overview_confirm_body
import nuvio.composeapp.generated.resources.season_overview_confirm_download
import nuvio.composeapp.generated.resources.season_overview_confirm_title
import nuvio.composeapp.generated.resources.season_overview_download
import nuvio.composeapp.generated.resources.season_overview_download_count
import nuvio.composeapp.generated.resources.season_overview_segment_detected
import nuvio.composeapp.generated.resources.season_overview_segment_missing
import nuvio.composeapp.generated.resources.season_overview_segment_pending
import nuvio.composeapp.generated.resources.season_overview_segment_submitted
import nuvio.composeapp.generated.resources.season_overview_select
import nuvio.composeapp.generated.resources.season_overview_include_download
import nuvio.composeapp.generated.resources.season_overview_included
import nuvio.composeapp.generated.resources.season_overview_more
import nuvio.composeapp.generated.resources.season_overview_open
import nuvio.composeapp.generated.resources.season_overview_remove_download
import nuvio.composeapp.generated.resources.season_overview_watched
import nuvio.composeapp.generated.resources.watch_privately
import nuvio.composeapp.generated.resources.submit_intro_segment_intro
import nuvio.composeapp.generated.resources.submit_intro_segment_preview
import nuvio.composeapp.generated.resources.submit_intro_segment_recap
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeasonOverviewSheet(
    meta: MetaDetails,
    season: Int,
    seasonLabel: String,
    episodes: List<MetaVideo>,
    isWatched: (MetaVideo) -> Boolean,
    loading: Boolean,
    offline: Boolean,
    fromDownloads: Boolean = false,
    onDismiss: () -> Unit,
    onOpenEpisode: (MetaVideo) -> Unit,
    onWatchPrivately: (MetaVideo) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val fitSheet = remember { FitSheetHeightHost() }
    val analysisRevision by MediaAnalysisStatus.revision.collectAsStateWithLifecycle()
    val ownRevision by OwnTheIntroDbLedger.revision.collectAsStateWithLifecycle()
    val tempRevision by AnalysisTempDownloads.revision.collectAsStateWithLifecycle()
    val refs = remember(episodes, isWatched, meta.id, meta.imdbId) {
        episodes.mapNotNull { video ->
            val episode = video.episode ?: return@mapNotNull null
            val seasonNumber = video.season ?: 0
            SeasonEpisodeRef(
                season = seasonNumber,
                episode = episode,
                title = video.title,
                watched = isWatched(video),
                contentKey = seasonEpisodeContentKey(
                    metaId = meta.id,
                    imdbId = meta.imdbId,
                    videoId = video.id,
                    videoImdbId = video.imdbId,
                    season = seasonNumber,
                    episode = episode,
                ),
            )
        }
    }
    val rows = remember(refs, analysisRevision, ownRevision, tempRevision, meta.id) {
        val own = OwnTheIntroDbLedger.load(overviewLedgerScope())
        val detected = refs.associate { ref ->
            val stored = MediaAnalysisStore.load(MediaAnalysisStore.keyFor(meta.id, ref.season, ref.episode))
            ref.contentKey to localDetectionTypes(stored)
        }
        seasonSegmentOverview(refs, own, detected)
    }
    val model = seasonOverviewModel(
        loading = loading,
        offline = offline,
        episodesKnown = !loading && (episodes.isNotEmpty() || !offline),
        rows = rows,
        fromDownloads = fromDownloads,
    )
    var selected by remember(meta.id, season) { mutableStateOf(setOf<String>()) }
    var confirm by remember(meta.id, season) { mutableStateOf(false) }
    var sessionTagged by remember(meta.id, season) { mutableStateOf(setOf<String>()) }
    val running = sessionTagged.any { AnalysisTempDownloads.isTagged(it) }
    val chosen = if (selected.isEmpty()) episodes else episodes.filter { video ->
        val episode = video.episode ?: return@filter false
        val key = seasonEpisodeContentKey(
            metaId = meta.id,
            imdbId = meta.imdbId,
            videoId = video.id,
            videoImdbId = video.imdbId,
            season = video.season ?: 0,
            episode = episode,
        )
        key in selected
    }
    val reduceMotion = systemReduceMotionEnabled()
    var openingEpisode by remember(meta.id, season) { mutableStateOf(false) }
    fun dismiss() {
        coroutineScope.launch {
            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
        }
    }
    fun openEpisode(video: MetaVideo, open: () -> Unit = { onOpenEpisode(video) }) {
        if (openingEpisode) return
        openingEpisode = true
        coroutineScope.launch {
            val steps = overviewEpisodeOpenSteps()
            if (OverviewOpenStep.DismissOverlay in steps) {
                val ms = seasonSheetDismissDurationMs(reduceMotion)
                if (usesNativeNuvioBottomSheet) {
                    dismissPresentedNuvioBottomSheet(ms)
                } else if (ms > 0 && sheetState.isVisible) {
                    sheetState.hide()
                }
                onDismiss()
            }
            if (OverviewOpenStep.OpenEpisode in steps) open()
        }
    }
    val windowPx = LocalWindowInfo.current.containerSize.height
    val sheetCap = with(LocalDensity.current) {
        if (windowPx > 0) windowPx.toDp() else 852.dp
    }
    val fontScale = seasonOverviewFontScale()
    val density = LocalDensity.current

    NuvioModalBottomSheet(
        onDismissRequest = { dismiss() },
        sheetState = sheetState,
        showDragHandle = true,
        fitSheet = fitSheet,
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(density = density.density, fontScale = fontScale),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = sheetCap)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(bottom = nuvioSafeBottomPadding(16.dp)),
            ) {
                Text(
                    text = seasonLabel,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
                NuvioBottomSheetDivider()
                when (model.state) {
                    SeasonOverviewUiState.Loading,
                    SeasonOverviewUiState.Offline,
                    -> {
                        if (model.state == SeasonOverviewUiState.Loading) {
                            NuvioLoadingIndicator(
                                modifier = Modifier.padding(24.dp).align(Alignment.CenterHorizontally),
                            )
                        }
                    }
                    SeasonOverviewUiState.EmptySeason -> Unit
                    SeasonOverviewUiState.AllSubmitted,
                    SeasonOverviewUiState.NoneSubmitted,
                    SeasonOverviewUiState.Partial,
                    -> {
                        val scroll = rememberScrollState()
                        val scrollValue = scroll.value
                        val scrollMax = scroll.maxValue
                        val indicator = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        Box(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .drawWithContent {
                                    drawContent()
                                    if (scrollMax <= 0 || size.height <= 1f) return@drawWithContent
                                    val widthPx = 3.dp.toPx()
                                    val insetPx = 3.dp.toPx()
                                    val minThumb = 24.dp.toPx()
                                    val total = size.height + scrollMax
                                    val thumb = (size.height * size.height / total)
                                        .coerceIn(minOf(minThumb, size.height), size.height)
                                    val travel = (size.height - thumb).coerceAtLeast(0f)
                                    val top = travel * (scrollValue.toFloat() / scrollMax.toFloat())
                                    drawRoundRect(
                                        color = indicator,
                                        topLeft = Offset(size.width - widthPx - insetPx, top),
                                        size = Size(widthPx, thumb),
                                        cornerRadius = CornerRadius(widthPx / 2f),
                                    )
                                },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(scroll),
                            ) {
                                model.rows.forEach { row ->
                                    val video = episodes.firstOrNull { video ->
                                        (video.season ?: 0) == row.season && video.episode == row.episode
                                    }
                                    SeasonOverviewEpisodeRow(
                                        code = localizedSeasonEpisodeCode(row.season, row.episode).orEmpty(),
                                        title = row.title,
                                        watched = row.watched,
                                        marks = row.marks,
                                        selected = row.contentKey in selected,
                                        onSelectedChange = { checked ->
                                            selected = if (checked) selected + row.contentKey else selected - row.contentKey
                                        },
                                        onOpen = { if (video != null) openEpisode(video) },
                                        onWatchPrivately = { if (video != null) openEpisode(video) { onWatchPrivately(video) } },
                                    )
                                }
                            }
                        }
                    }
                }
                if (!fromDownloads &&
                    model.state != SeasonOverviewUiState.Loading &&
                    model.state != SeasonOverviewUiState.EmptySeason
                ) {
                    val count = chosen.size
                    val title = if (running) {
                        stringResource(Res.string.action_cancel)
                    } else if (selected.isEmpty()) {
                        stringResource(Res.string.season_overview_download)
                    } else {
                        stringResource(Res.string.season_overview_download_count, count)
                    }
                    NuvioBottomSheetActionRow(
                        icon = if (running) null else Icons.Rounded.Download,
                        title = title,
                        titleMaxLines = 8,
                        enabled = running || (model.downloadEnabled && count > 0),
                        onClick = {
                            if (running) {
                                AnalysisTempDownloads.cancelSession(sessionTagged)
                                sessionTagged = emptySet()
                            } else {
                                confirm = true
                            }
                        },
                    )
                }
            }
        }
    }

    if (confirm) {
        DialogSurface(
            onDismissRequest = { confirm = false },
            title = stringResource(Res.string.season_overview_confirm_title),
            message = stringResource(Res.string.season_overview_confirm_body),
        ) {
            DialogButtons {
                DialogButton(
                    text = stringResource(Res.string.action_cancel),
                    onClick = { confirm = false },
                )
                DialogButton(
                    text = stringResource(Res.string.season_overview_confirm_download),
                    style = DialogButtonStyle.Primary,
                    onClick = {
                        confirm = false
                        val batch = chosen
                        coroutineScope.launch {
                            val outcome = downloadEpisodesForAnalysis(meta, batch)
                            sessionTagged = outcome.taggedIds
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SeasonOverviewEpisodeRow(
    code: String,
    title: String,
    watched: Boolean,
    marks: Map<String, SegmentMark>,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onWatchPrivately: () -> Unit,
) {
    val reduceMotion = systemReduceMotionEnabled()
    val body = @Composable {
        SeasonOverviewEpisodeBody(
            code = code,
            title = title,
            watched = watched,
            marks = marks,
            selected = selected,
            onSelectedChange = onSelectedChange,
            onOpen = onOpen,
            onWatchPrivately = onWatchPrivately,
        )
    }
    if (reduceMotion) {
        body()
        return
    }
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            onWatchPrivately()
            dismissState.reset()
        }
    }
    val reveal = seasonSwipeRevealsAction(
        currentSettled = dismissState.currentValue == SwipeToDismissBoxValue.Settled,
        targetSettled = dismissState.targetValue == SwipeToDismissBoxValue.Settled,
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            if (reveal) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(MaterialTheme.nuvio.shapes.compactCard)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(Res.string.watch_privately),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.nuvio.shapes.compactCard),
    ) {
        body()
    }
}

@Composable
private fun SeasonOverviewEpisodeBody(
    code: String,
    title: String,
    watched: Boolean,
    marks: Map<String, SegmentMark>,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onWatchPrivately: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val moreLabel = stringResource(Res.string.season_overview_more)
    val watchedLabel = stringResource(Res.string.season_overview_watched)
    val visible = visibleSegmentMarks(marks)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(MaterialTheme.nuvio.shapes.compactCard)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (code.isBlank()) title else "$code  $title",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (watched || selected) {
                    Text(
                        text = listOfNotNull(
                            watchedLabel.takeIf { watched },
                            stringResource(Res.string.season_overview_included).takeIf { selected },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box {
                IconButton(
                    onClick = { menu = true },
                    modifier = Modifier.size(44.dp.atLeastIosHitTarget()),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreHoriz,
                        contentDescription = moreLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.watch_privately)) },
                        onClick = {
                            menu = false
                            onWatchPrivately()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.season_overview_open)) },
                        onClick = {
                            menu = false
                            onOpen()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (selected) {
                                        Res.string.season_overview_remove_download
                                    } else {
                                        Res.string.season_overview_include_download
                                    },
                                ),
                            )
                        },
                        onClick = {
                            menu = false
                            onSelectedChange(!selected)
                        },
                    )
                }
            }
        }
        if (visible.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visible.forEach { (type, mark) ->
                    SegmentMarkChip(type = type, mark = mark)
                }
            }
        }
    }
}

@Composable
private fun SegmentMarkChip(type: String, mark: SegmentMark) {
    val label = when (type) {
        "intro" -> stringResource(Res.string.submit_intro_segment_intro)
        "recap" -> stringResource(Res.string.submit_intro_segment_recap)
        "outro" -> stringResource(Res.string.analysis_part_credits)
        else -> stringResource(Res.string.submit_intro_segment_preview)
    }
    val model = segmentChipModel(label, mark)
    val description = when (mark) {
        SegmentMark.Submitted -> stringResource(Res.string.season_overview_segment_submitted, label)
        SegmentMark.Pending -> stringResource(Res.string.season_overview_segment_pending, label)
        SegmentMark.Detected -> stringResource(Res.string.season_overview_segment_detected, label)
        SegmentMark.Missing -> stringResource(Res.string.season_overview_segment_missing, label)
    }
    val visual = model.visual
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.nuvio.shapes.chip
    val foreground = when (visual) {
        SegmentChipVisual.FilledCheck -> scheme.onPrimary
        SegmentChipVisual.Outlined -> scheme.onSurface
        SegmentChipVisual.DetectedText -> scheme.onSurface
        SegmentChipVisual.Dim -> scheme.onSurfaceVariant
    }
    val weight = if (visual == SegmentChipVisual.Dim) FontWeight.Normal else FontWeight.SemiBold
    val chipText = model.visibleText
    val checkSize = with(LocalDensity.current) { MaterialTheme.typography.labelLarge.fontSize.toDp() }
    Row(
        modifier = Modifier
            .clearAndSetSemantics { contentDescription = description }
            .then(
                if (visual == SegmentChipVisual.Dim || visual == SegmentChipVisual.DetectedText) {
                    Modifier
                } else {
                    Modifier
                        .clip(shape)
                        .then(
                            if (visual == SegmentChipVisual.Outlined) {
                                Modifier.border(1.5.dp, scheme.onSurface, shape)
                            } else {
                                Modifier
                            },
                        )
                        .background(if (visual == SegmentChipVisual.FilledCheck) scheme.primary else Color.Transparent)
                },
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (visual == SegmentChipVisual.FilledCheck) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(checkSize),
            )
        }
        Text(
            text = chipText,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = weight,
            color = foreground,
        )
    }
}
