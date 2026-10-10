package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private data class SeasonEpisodeRowState(
    val key: String,
    val video: MetaVideo,
    val userChecked: Boolean = true,
    val match: SeasonStreamMatch? = null,
    val probedBytes: Long? = null,
    val probeDone: Boolean = false,
)

private fun SeasonEpisodeRowState.selectedForTotal(): Boolean =
    userChecked && match !is SeasonStreamMatch.Unmatched

private fun SeasonEpisodeRowState.displayBytes(): Long? {
    val matched = match as? SeasonStreamMatch.Matched ?: return null
    return matched.stream.behaviorHints.videoSize?.takeIf { it > 0L }
        ?: probedBytes?.takeIf { it > 0L }
}

private fun SeasonEpisodeRowState.knownBytes(): Long? =
    if (selectedForTotal()) displayBytes() else null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeasonDownloadSheet(
    meta: MetaDetails,
    seasonLabel: String,
    episodes: List<MetaVideo>,
    onOpenEpisode: (MetaVideo) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val rowsSeed = remember(episodes) { episodes.toSeasonRows() }
    var rows by remember(episodes) { mutableStateOf(rowsSeed) }
    var anchorStreams by remember(episodes) { mutableStateOf<List<StreamItem>?>(null) }
    var loadingSources by remember(episodes) { mutableStateOf(true) }
    var choice by remember(episodes) { mutableStateOf<StreamItem?>(null) }
    var queuing by remember(episodes) { mutableStateOf(false) }
    var matchJob by remember(episodes) { mutableStateOf<Job?>(null) }
    val probedIds = remember(episodes) { mutableSetOf<String>() }
    val sizeUnknown = stringResource(Res.string.season_download_size_unknown)
    val noStreams = stringResource(Res.string.season_download_no_streams)
    val noMatch = stringResource(Res.string.season_download_no_match)
    val unsupported = stringResource(Res.string.season_download_unsupported)

    val anchorKey = rows.firstOrNull { it.userChecked }?.key
    LaunchedEffect(anchorKey, choice) {
        if (choice != null) return@LaunchedEffect
        if (anchorKey == null) {
            loadingSources = false
            return@LaunchedEffect
        }
        val anchor = rows.firstOrNull { it.key == anchorKey } ?: return@LaunchedEffect
        loadingSources = true
        anchorStreams = SeasonStreamCatalog.load(
            type = meta.type,
            videoId = seasonStreamVideoId(meta.id, anchor.video),
            season = anchor.video.season,
            episode = anchor.video.episode,
        )
        loadingSources = false
    }

    val probeKey = rows.joinToString(separator = "|") { row ->
        val matched = row.match as? SeasonStreamMatch.Matched
        val hinted = matched?.stream?.behaviorHints?.videoSize?.takeIf { it > 0L }
        if (matched == null || hinted != null || row.probeDone || row.key in probedIds) "" else row.key
    }
    LaunchedEffect(probeKey) {
        val pending = rows.mapNotNull { row -> row.toProbeTarget()?.takeIf { row.key !in probedIds } }
        if (pending.isEmpty()) return@LaunchedEffect
        pending.forEach { probedIds += it.id }
        scope.launch {
            probeSeasonSizes(
                targets = pending,
                probe = RemoteContentLengthProbe::probe,
            ) { id, bytes ->
                rows = rows.map { row ->
                    if (row.key == id) row.copy(probedBytes = bytes, probeDone = true) else row
                }
            }
        }
    }

    val selectedBytes = rows.filter { it.selectedForTotal() }.map { it.knownBytes() }
    val summary = summarizeSeasonSizes(selectedBytes)
    val totalText = seasonSelectionTotalText(summary, ::formatSeasonBytes)
    val canQueue = !queuing && choice != null && rows.any { it.selectedForTotal() }

    fun dismiss() {
        scope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }

    NuvioModalBottomSheet(
        onDismissRequest = { dismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(12.dp)),
        ) {
            Text(
                text = stringResource(Res.string.season_download_title, seasonLabel),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
            if (choice == null) {
                SourcePicker(
                    loading = loadingSources,
                    streams = anchorStreams.orEmpty().filter { it.isSeasonDownloadCandidate() },
                    onPick = { stream ->
                        choice = stream
                        probedIds.clear()
                        val picked = SeasonSourceChoice(stream)
                        val anchor = rows.firstOrNull { it.userChecked }
                        rows = rows.map { row ->
                            if (anchor != null && row.key == anchor.key) {
                                row.copy(match = SeasonStreamMatch.Matched(stream))
                            } else {
                                row.copy(match = null, probedBytes = null, probeDone = false)
                            }
                        }
                        matchJob?.cancel()
                        matchJob = scope.launch {
                            val matched = matchOtherEpisodes(
                                meta = meta,
                                choice = picked,
                                rows = rows.filter { anchor == null || it.key != anchor.key },
                            )
                            rows = rows.map { row -> matched[row.key]?.let { row.copy(match = it) } ?: row }
                        }
                    },
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                rows.forEach { row ->
                    EpisodeDownloadRow(
                        row = row,
                        sizeUnknown = sizeUnknown,
                        reason = when ((row.match as? SeasonStreamMatch.Unmatched)?.reason) {
                            SeasonMatchFailure.NoStreams -> noStreams
                            SeasonMatchFailure.NoMatchingSource -> noMatch
                            SeasonMatchFailure.UnsupportedFormat -> unsupported
                            null -> null
                        },
                        onCheckedChange = { checked ->
                            if (row.match is SeasonStreamMatch.Unmatched) return@EpisodeDownloadRow
                            rows = rows.map { current ->
                                if (current.key == row.key) current.copy(userChecked = checked) else current
                            }
                        },
                        onChooseStreams = {
                            dismiss()
                            onOpenEpisode(row.video)
                        },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = totalText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        if (queuing || choice == null || !rows.any { it.selectedForTotal() }) return@Button
                        queuing = true
                        scope.launch {
                            try {
                                matchJob?.join()
                                val ready = rows.mapNotNull { row ->
                                    if (!row.userChecked) return@mapNotNull null
                                    val matched = row.match as? SeasonStreamMatch.Matched ?: return@mapNotNull null
                                    SeasonEnqueueRow(video = row.video, stream = matched.stream)
                                }
                                val result = enqueueSeasonDownloads(meta, ready)
                                val message = when {
                                    result.queued > 0 -> getString(Res.string.season_download_queued, result.queued)
                                    result.failureMessage != null -> result.failureMessage
                                    else -> getString(Res.string.season_download_none_queued)
                                }
                                NuvioToastController.show(message)
                                dismiss()
                            } finally {
                                queuing = false
                            }
                        }
                    },
                    enabled = canQueue,
                ) {
                    Text(stringResource(Res.string.details_download_action))
                }
            }
        }
    }
}

@Composable
private fun SourcePicker(
    loading: Boolean,
    streams: List<StreamItem>,
    onPick: (StreamItem) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.season_download_pick_source),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        when {
            loading -> {
                CircularProgressIndicator(modifier = Modifier.padding(vertical = 8.dp))
            }
            streams.isEmpty() -> {
                Text(
                    text = stringResource(Res.string.season_download_no_sources),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                streams.forEach { stream ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(stream) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            text = stream.addonName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val size = stream.behaviorHints.videoSize?.takeIf { it > 0L }?.let(::formatSeasonBytes)
                        Text(
                            text = listOfNotNull(streamQualityLabel(stream), stream.streamLabel, size)
                                .distinct()
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeDownloadRow(
    row: SeasonEpisodeRowState,
    sizeUnknown: String,
    reason: String?,
    onCheckedChange: (Boolean) -> Unit,
    onChooseStreams: () -> Unit,
) {
    val unmatched = row.match is SeasonStreamMatch.Unmatched
    val checked = row.userChecked && !unmatched
    val code = localizedSeasonEpisodeCode(row.video.season, row.video.episode)
    val title = listOfNotNull(code, row.video.title.takeIf { it.isNotBlank() }).joinToString(" · ")
    val sized = row.displayBytes()
    val detail = when {
        reason != null -> reason
        sized != null -> formatSeasonBytes(sized)
        else -> sizeUnknown
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = if (unmatched) null else onCheckedChange,
            enabled = !unmatched,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { row.video.id },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (unmatched) {
            TextButton(onClick = onChooseStreams) {
                Text(stringResource(Res.string.season_download_choose_streams))
            }
        }
    }
}

private fun List<MetaVideo>.toSeasonRows(): List<SeasonEpisodeRowState> =
    mapIndexed { index, video ->
        SeasonEpisodeRowState(
            key = "${video.season}:${video.episode}:${video.id}:$index",
            video = video,
        )
    }

private fun SeasonEpisodeRowState.toProbeTarget(): SeasonProbeTarget? {
    val matched = match as? SeasonStreamMatch.Matched ?: return null
    if (matched.stream.behaviorHints.videoSize?.takeIf { it > 0L } != null) return null
    if (probeDone) return null
    val url = matched.stream.playableDirectUrl?.takeIf { it.isSupportedDownloadUrl() } ?: return null
    return SeasonProbeTarget(
        id = key,
        url = url,
        headers = matched.stream.behaviorHints.proxyHeaders?.request.orEmpty(),
    )
}

private suspend fun matchOtherEpisodes(
    meta: MetaDetails,
    choice: SeasonSourceChoice,
    rows: List<SeasonEpisodeRowState>,
): Map<String, SeasonStreamMatch> = coroutineScope {
    val gate = Semaphore(4)
    rows.map { row ->
        async {
            val streams = gate.withPermit {
                SeasonStreamCatalog.load(
                    type = meta.type,
                    videoId = seasonStreamVideoId(meta.id, row.video),
                    season = row.video.season,
                    episode = row.video.episode,
                )
            }
            row.key to matchSeasonStream(choice, streams)
        }
    }.awaitAll().toMap()
}
