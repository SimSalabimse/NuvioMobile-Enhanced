package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.collectAsState
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
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.streams.BingeGroupCacheRepository
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Episodes whose picked source is looked up at once. Each asks only the picked source. */
private const val SEASON_MATCH_CONCURRENCY = 3

private data class SeasonEpisodeRowState(
    val key: String,
    val video: MetaVideo,
    val existing: SeasonExistingDownload? = null,
    val watched: Boolean = false,
    val userChecked: Boolean = existing == null && !watched,
    /** The user chose this episode's source in its own list; a season-level change leaves it. */
    val overridden: Boolean = false,
    val match: SeasonStreamMatch? = null,
    val matching: Boolean = false,
    val probedBytes: Long? = null,
    val probeDone: Boolean = false,
)

private val SeasonEpisodeRowState.selectable: Boolean
    get() = existing == null && match !is SeasonStreamMatch.Unmatched

private fun SeasonEpisodeRowState.selectedForTotal(): Boolean =
    userChecked && selectable

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
    isEpisodeWatched: (MetaVideo) -> Boolean,
    onOpenEpisode: (MetaVideo) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val downloads by DownloadsRepository.uiState.collectAsState()
    var rows by remember(episodes) {
        DownloadsRepository.ensureLoaded()
        mutableStateOf(
            episodes.toSeasonRows(meta.id, DownloadsRepository.uiState.value.items, isEpisodeWatched),
        )
    }
    // The first episode the sheet would queue lists the sources. It does not move when a row is unchecked.
    val anchorKey = remember(episodes) {
        (rows.firstOrNull { it.existing == null && !it.watched } ?: rows.firstOrNull { it.existing == null })?.key
    }
    var anchorStreams by remember(episodes) { mutableStateOf<List<StreamItem>>(emptyList()) }
    var pendingSources by remember(episodes) { mutableStateOf(if (anchorKey == null) 0 else 1) }
    var choice by remember(episodes) { mutableStateOf<StreamItem?>(null) }
    var autoPicked by remember(episodes) { mutableStateOf(false) }
    var pickingSource by remember(episodes) { mutableStateOf(false) }
    var queuing by remember(episodes) { mutableStateOf(false) }
    var matchJob by remember(episodes) { mutableStateOf<Job?>(null) }
    val probedIds = remember(episodes) { mutableSetOf<String>() }
    // Row whose own source list is open, with that episode's streams.
    var episodePickerKey by remember(episodes) { mutableStateOf<String?>(null) }
    var episodeStreams by remember(episodes) { mutableStateOf<List<StreamItem>>(emptyList()) }
    var episodePending by remember(episodes) { mutableStateOf(0) }
    val sizeUnknown = stringResource(Res.string.season_download_size_unknown)
    val noStreams = stringResource(Res.string.season_download_no_streams)
    val noMatch = stringResource(Res.string.season_download_no_match)
    val unsupported = stringResource(Res.string.season_download_unsupported)
    val matchingText = stringResource(Res.string.season_download_matching)
    val downloadedText = stringResource(Res.string.season_download_already_downloaded)
    val inDownloadsText = stringResource(Res.string.season_download_in_downloads)
    val watchedText = stringResource(Res.string.season_download_watched)

    // A download that finishes or starts while the sheet is open is not queued again.
    LaunchedEffect(downloads.items) {
        rows = rows.map { row ->
            val existing = seasonExistingDownload(row.video.existingStatus(meta.id, downloads.items))
            if (existing == row.existing) {
                row
            } else {
                row.copy(existing = existing, userChecked = existing == null && row.userChecked)
            }
        }
    }

    fun applyChoice(stream: StreamItem, byAutoPick: Boolean) {
        choice = stream
        autoPicked = byAutoPick
        pickingSource = false
        probedIds.clear()
        val picked = SeasonSourceChoice(stream)
        rows = rows.map { row ->
            when {
                row.existing != null -> row.copy(match = null, matching = false)
                row.overridden -> row
                row.key == anchorKey -> row.copy(
                    match = SeasonStreamMatch.Matched(stream),
                    matching = false,
                    probedBytes = null,
                    probeDone = false,
                )
                else -> row.copy(match = null, matching = true, probedBytes = null, probeDone = false)
            }
        }
        matchJob?.cancel()
        val toMatch = rows.filter { it.matching }
        matchJob = scope.launch {
            val gate = Semaphore(SEASON_MATCH_CONCURRENCY)
            toMatch.map { row ->
                launch {
                    val streams = gate.withPermit {
                        SeasonStreamCatalog.loadFrom(
                            addonId = stream.addonId,
                            type = meta.type,
                            videoId = seasonStreamVideoId(meta.id, row.video),
                            season = row.video.season,
                            episode = row.video.episode,
                        )
                    }
                    val match = matchSeasonStream(picked, streams)
                    rows = rows.map { current ->
                        if (current.key == row.key && !current.overridden) {
                            current.copy(match = match, matching = false)
                        } else {
                            current
                        }
                    }
                }
            }.forEach { it.join() }
        }
    }

    LaunchedEffect(anchorKey) {
        val anchor = rows.firstOrNull { it.key == anchorKey } ?: run {
            pendingSources = 0
            return@LaunchedEffect
        }
        PlayerSettingsRepository.ensureLoaded()
        val player = PlayerSettingsRepository.uiState.value
        val debrid = DebridSettingsRepository.snapshot()
        val autoSettings = SeasonAutoPickSettings(
            mode = player.streamAutoPlayMode,
            regexPattern = player.streamAutoPlayRegex,
            source = player.streamAutoPlaySource,
            selectedAddons = player.streamAutoPlaySelectedAddons,
            selectedPlugins = player.streamAutoPlaySelectedPlugins,
            preferredBingeGroup = if (player.streamAutoPlayPreferBingeGroup && player.streamAutoPlayReuseBingeGroup) {
                BingeGroupCacheRepository.get(meta.id)
            } else {
                null
            },
            timeoutSeconds = player.streamAutoPlayTimeoutSeconds,
            debridEnabled = debrid.canResolvePlayableLinks,
            activeResolverProviderId = debrid.activeResolverProviderId,
        )
        val installedAddonNames = AddonRepository.uiState.value.addons.enabledAddons()
            .mapNotNull { addon -> addon.manifest?.let { addon.displayTitle.ifBlank { it.name } } }
            .toSet()
        var timeoutElapsed = false
        var allAnswered = false

        fun tryAutoPick() {
            if (choice != null || pickingSource || !autoSettings.picksAutomatically()) return
            if (!seasonAutoPickMayDecide(autoSettings.timeoutSeconds, timeoutElapsed, allAnswered)) return
            val picked = autoPickSeasonStream(anchorStreams, autoSettings, installedAddonNames) ?: return
            applyChoice(picked, byAutoPick = true)
        }

        val timer = launch {
            val seconds = autoSettings.timeoutSeconds
            if (seconds in 1 until Int.MAX_VALUE) {
                delay(seconds * 1_000L)
                timeoutElapsed = true
                tryAutoPick()
            }
        }
        SeasonStreamCatalog.stream(
            type = meta.type,
            videoId = seasonStreamVideoId(meta.id, anchor.video),
            season = anchor.video.season,
            episode = anchor.video.episode,
        ).collect { snapshot ->
            anchorStreams = snapshot.streams
            pendingSources = snapshot.pendingSources
            allAnswered = snapshot.complete
            tryAutoPick()
        }
        timer.cancel()
    }

    LaunchedEffect(episodePickerKey) {
        val row = rows.firstOrNull { it.key == episodePickerKey } ?: return@LaunchedEffect
        episodeStreams = emptyList()
        episodePending = 1
        SeasonStreamCatalog.stream(
            type = meta.type,
            videoId = seasonStreamVideoId(meta.id, row.video),
            season = row.video.season,
            episode = row.video.episode,
        ).collect { snapshot ->
            episodeStreams = snapshot.streams
            episodePending = snapshot.pendingSources
        }
    }

    fun applyEpisodeChoice(key: String, stream: StreamItem) {
        probedIds.remove(key)
        rows = rows.map { row ->
            if (row.key == key) {
                row.copy(
                    match = SeasonStreamMatch.Matched(stream),
                    matching = false,
                    overridden = true,
                    userChecked = true,
                    probedBytes = null,
                    probeDone = false,
                )
            } else {
                row
            }
        }
        episodePickerKey = null
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
    val stillMatching = rows.any { it.matching && it.userChecked }
    val canQueue = !queuing && !stillMatching &&
        rows.any { it.selectedForTotal() && it.match is SeasonStreamMatch.Matched }
    val showPicker = anchorKey != null && (choice == null || pickingSource)
    val candidates = anchorStreams.filter { it.isSeasonDownloadCandidate() }

    fun dismiss() {
        scope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }

    NuvioModalBottomSheet(
        onDismissRequest = { dismiss() },
        sheetState = sheetState,
        fullHeight = true,
    ) {
        Text(
            text = stringResource(Res.string.season_download_title, seasonLabel),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
        // One list for the sources and the episodes, so a long source list scrolls instead of
        // pushing the episodes and the Download button out of the sheet.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
        ) {
            val pickerRow = rows.firstOrNull { it.key == episodePickerKey }
            if (pickerRow != null) {
                item(key = "episode-picker-title") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                Res.string.season_download_episode_source,
                                localizedSeasonEpisodeCode(pickerRow.video.season, pickerRow.video.episode).orEmpty(),
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { episodePickerKey = null }) {
                            Text(stringResource(Res.string.season_download_back))
                        }
                    }
                }
                val episodeCandidates = episodeStreams.filter { it.isSeasonDownloadCandidate() }
                itemsIndexed(episodeCandidates, key = { index, _ -> "episode-source:$index" }) { _, stream ->
                    SourceRow(stream = stream, onPick = { applyEpisodeChoice(pickerRow.key, stream) })
                }
                if (episodePending > 0) {
                    item(key = "episode-picker-pending") {
                        PendingSourcesRow(episodePending)
                    }
                } else if (episodeCandidates.isEmpty()) {
                    item(key = "episode-picker-empty") {
                        SheetNote(stringResource(Res.string.season_download_no_streams))
                    }
                }
                return@LazyColumn
            }
            if (anchorKey == null) {
                item(key = "nothing-left") {
                    SheetNote(stringResource(Res.string.season_download_nothing_left))
                }
            } else if (showPicker) {
                item(key = "picker-title") {
                    Text(
                        text = stringResource(Res.string.season_download_pick_source),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                itemsIndexed(candidates, key = { index, _ -> "source:$index" }) { _, stream ->
                    SourceRow(stream = stream, onPick = { applyChoice(stream, byAutoPick = false) })
                }
                if (pendingSources > 0) {
                    item(key = "picker-pending") {
                        PendingSourcesRow(pendingSources)
                    }
                } else if (candidates.isEmpty()) {
                    item(key = "picker-empty") {
                        SheetNote(stringResource(Res.string.season_download_no_sources))
                    }
                }
            } else {
                val picked = choice
                if (picked != null) {
                    item(key = "picked") {
                        PickedSourceRow(
                            stream = picked,
                            autoPicked = autoPicked,
                            onChange = { pickingSource = true },
                        )
                    }
                }
            }
            items(rows, key = { "episode:" + it.key }) { row ->
                EpisodeDownloadRow(
                    row = row,
                    sizeUnknown = sizeUnknown,
                    reason = when {
                        row.existing == SeasonExistingDownload.Downloaded -> downloadedText
                        row.existing == SeasonExistingDownload.InProgress -> inDownloadsText
                        row.matching -> matchingText
                        else -> when ((row.match as? SeasonStreamMatch.Unmatched)?.reason) {
                            SeasonMatchFailure.NoStreams -> noStreams
                            SeasonMatchFailure.NoMatchingSource -> noMatch
                            SeasonMatchFailure.UnsupportedFormat -> unsupported
                            null -> null
                        }
                    },
                    onCheckedChange = { checked ->
                        if (!row.selectable) return@EpisodeDownloadRow
                        rows = rows.map { current ->
                            if (current.key == row.key) current.copy(userChecked = checked) else current
                        }
                    },
                    watchedText = watchedText,
                    onChangeSource = { episodePickerKey = row.key },
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
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .padding(bottom = nuvioSafeBottomPadding(0.dp)),
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
                    if (!canQueue) return@Button
                    queuing = true
                    scope.launch {
                        try {
                            val ready = rows.mapNotNull { row ->
                                if (!row.selectedForTotal()) return@mapNotNull null
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

@Composable
private fun PendingSourcesRow(pending: Int) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(Res.string.season_download_sources_pending, pending),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SheetNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun StreamItem.sourceDetail(): String {
    val size = behaviorHints.videoSize?.takeIf { it > 0L }?.let(::formatSeasonBytes)
    return listOfNotNull(streamQualityLabel(this), streamLabel, size)
        .distinct()
        .joinToString(" · ")
}

@Composable
private fun SourceRow(
    stream: StreamItem,
    onPick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stream.addonName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stream.sourceDetail(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PickedSourceRow(
    stream: StreamItem,
    autoPicked: Boolean,
    onChange: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stream.addonName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stream.sourceDetail(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (autoPicked) {
                Text(
                    text = stringResource(Res.string.season_download_auto_picked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        TextButton(onClick = onChange) {
            Text(stringResource(Res.string.season_download_change_source))
        }
    }
}

@Composable
private fun EpisodeDownloadRow(
    row: SeasonEpisodeRowState,
    sizeUnknown: String,
    reason: String?,
    watchedText: String,
    onCheckedChange: (Boolean) -> Unit,
    onChangeSource: () -> Unit,
    onChooseStreams: () -> Unit,
) {
    val unmatched = row.match is SeasonStreamMatch.Unmatched && row.existing == null
    val checked = row.userChecked && row.selectable
    val code = localizedSeasonEpisodeCode(row.video.season, row.video.episode)
    val title = listOfNotNull(code, row.video.title.takeIf { it.isNotBlank() }).joinToString(" · ")
    val sized = row.displayBytes()
    val baseDetail = when {
        reason != null -> reason
        sized != null -> formatSeasonBytes(sized)
        else -> sizeUnknown
    }
    val detail = if (row.watched && row.existing == null) "$watchedText · $baseDetail" else baseDetail
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = if (row.selectable) onCheckedChange else null,
            enabled = row.selectable,
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
        if (row.existing == null) {
            TextButton(onClick = onChangeSource) {
                Text(stringResource(Res.string.season_download_change_source))
            }
        }
        if (unmatched) {
            TextButton(onClick = onChooseStreams) {
                Text(stringResource(Res.string.season_download_choose_streams))
            }
        }
    }
}

private fun MetaVideo.existingStatus(parentMetaId: String, items: List<DownloadItem>): DownloadStatus? {
    val key = downloadLogicalContentKey(parentMetaId, season, episode)
    return items.firstOrNull { it.logicalContentKey == key }?.status
}

private fun List<MetaVideo>.toSeasonRows(
    parentMetaId: String,
    downloads: List<DownloadItem>,
    isWatched: (MetaVideo) -> Boolean,
): List<SeasonEpisodeRowState> =
    mapIndexed { index, video ->
        SeasonEpisodeRowState(
            key = "${video.season}:${video.episode}:${video.id}:$index",
            video = video,
            existing = seasonExistingDownload(video.existingStatus(parentMetaId, downloads)),
            watched = isWatched(video),
        )
    }

private fun SeasonEpisodeRowState.toProbeTarget(): SeasonProbeTarget? {
    if (existing != null) return null
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
