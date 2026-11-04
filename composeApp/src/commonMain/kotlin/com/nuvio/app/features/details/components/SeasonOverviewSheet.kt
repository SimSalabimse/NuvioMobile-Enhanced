package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.analysis.MediaAnalysisStore
import com.nuvio.app.features.analysis.SkipSourceLedger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.SEASON_SEGMENT_TYPES
import com.nuvio.app.features.details.SeasonEpisodeRef
import com.nuvio.app.features.details.SeasonOverviewUiState
import com.nuvio.app.features.details.SegmentMark
import com.nuvio.app.features.details.seasonEpisodeContentKey
import com.nuvio.app.features.details.seasonOverviewModel
import com.nuvio.app.features.details.seasonSegmentOverview
import com.nuvio.app.features.downloads.AnalysisTempDownloads
import com.nuvio.app.features.downloads.downloadEpisodesForAnalysis
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
import nuvio.composeapp.generated.resources.season_overview_segment_missing
import nuvio.composeapp.generated.resources.season_overview_segment_on_theintrodb
import nuvio.composeapp.generated.resources.season_overview_segment_submitted
import nuvio.composeapp.generated.resources.season_overview_select
import nuvio.composeapp.generated.resources.season_overview_watched
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
    onDismiss: () -> Unit,
    onOpenEpisode: (MetaVideo) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val skipRevision by SkipSourceLedger.revision.collectAsStateWithLifecycle()
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
    val rows = remember(refs, skipRevision, ownRevision, tempRevision) {
        val own = OwnTheIntroDbLedger.loadAll()
        val public = refs.associate { ref ->
            ref.contentKey to SkipSourceLedger.online(
                MediaAnalysisStore.keyFor(meta.id, ref.season, ref.episode),
            )
        }
        seasonSegmentOverview(refs, own, public)
    }
    val model = seasonOverviewModel(
        loading = loading,
        offline = offline,
        episodesKnown = !loading && (episodes.isNotEmpty() || !offline),
        rows = rows,
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
    fun dismiss() {
        coroutineScope.launch {
            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = { dismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            Text(
                text = seasonLabel,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
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
                                onOpen = { if (video != null) onOpenEpisode(video) },
                            )
                        }
                    }
                }
            }
            if (model.state != SeasonOverviewUiState.Loading && model.state != SeasonOverviewUiState.EmptySeason) {
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
) {
    val selectLabel = stringResource(Res.string.season_overview_select)
    val watchedLabel = stringResource(Res.string.season_overview_watched)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = onSelectedChange,
            modifier = Modifier.semantics { contentDescription = selectLabel },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(MaterialTheme.nuvio.shapes.compactCard)
                .clickable(onClick = onOpen)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (code.isBlank()) title else "$code  $title",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SEASON_SEGMENT_TYPES.forEach { type ->
                    val mark = marks[type] ?: SegmentMark.Missing
                    SegmentMarkChip(type = type, mark = mark)
                }
            }
        }
        if (watched) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = watchedLabel,
                tint = MaterialTheme.nuvio.colors.accent,
            )
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
    val description = when (mark) {
        SegmentMark.Submitted -> stringResource(Res.string.season_overview_segment_submitted, label)
        SegmentMark.OnTheIntroDb -> stringResource(Res.string.season_overview_segment_on_theintrodb, label)
        SegmentMark.Missing -> stringResource(Res.string.season_overview_segment_missing, label)
    }
    val tokens = MaterialTheme.nuvio
    val background = if (mark == SegmentMark.Submitted) tokens.colors.accent else androidx.compose.ui.graphics.Color.Transparent
    val foreground = when (mark) {
        SegmentMark.Submitted -> tokens.colors.onAccent
        SegmentMark.OnTheIntroDb -> tokens.colors.textSecondary
        SegmentMark.Missing -> tokens.colors.textSecondary.copy(alpha = 0.45f)
    }
    val border = when (mark) {
        SegmentMark.Submitted -> tokens.colors.accent
        SegmentMark.OnTheIntroDb -> tokens.colors.borderDefault
        SegmentMark.Missing -> tokens.colors.borderDefault.copy(alpha = 0.45f)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        modifier = Modifier
            .semantics { contentDescription = description }
            .clip(tokens.shapes.compactCard)
            .border(tokens.borders.hairline, border, tokens.shapes.compactCard)
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
