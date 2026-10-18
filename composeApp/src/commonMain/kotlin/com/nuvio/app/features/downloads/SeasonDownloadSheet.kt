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
    /** Not aired yet (or no air date): shown disabled with its date; can be queued for when it airs. */
    val unaired: Boolean = false,
    val userChecked: Boolean = existing == null && !watched && !unaired,
    /** The user chose this episode's source in its own list; a season-level change leaves it. */
    val overridden: Boolean = false,
    val match: SeasonStreamMatch? = null,
    val matching: Boolean = false,
    val probedBytes: Long? = null,
    val probeDone: Boolean = false,
)

/** Can be ticked in step 1: not already downloaded or queued, and aired. */
private val SeasonEpisodeRowState.canPick: Boolean
    get() = existing == null && !unaired

private val SeasonEpisodeRowState.selectable: Boolean
    get() = canPick && match !is SeasonStreamMatch.Unmatched

private fun SeasonEpisodeRowState.selectedForTotal(): Boolean =
    userChecked && selectable

private fun SeasonEpisodeRowState.displayBytes(): Long? {
    val matched = match as? SeasonStreamMatch.Matched ?: return null
    return downloadStreamBytes(matched.stream)
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
    // Step 1: pick episodes. Nothing is searched until the user confirms the selection.
    var selectionConfirmed by remember(episodes) { mutableStateOf(false) }
    var confirmedKeys by remember(episodes) { mutableStateOf<Set<String>>(emptySet()) }
    // The first selected episode lists the sources. It does not move when a row is unchecked.
    var anchorKey by remember(episodes) { mutableStateOf<String?>(null) }
    var anchorStreams by remember(episodes) { mutableStateOf<List<StreamItem>>(emptyList()) }
    var pendingSources by remember(episodes) { mutableStateOf(0) }
    var choice by remember(episodes) { mutableStateOf<StreamItem?>(null) }
    var autoPicked by remember(episodes) { mutableStateOf(false) }
    // Season quality selector: null follows the picked source; a bucket re-picks every episode at it.
    var seasonQuality by remember(episodes) { mutableStateOf<Int?>(null) }
    var anchorPickFrom by remember(episodes) { mutableStateOf<(List<StreamItem>) -> StreamItem?>({ null }) }
    var pickingSource by remember(episodes) { mutableStateOf(false) }
    // Auto-pick on: the source list stays folded while it decides; it opens on "Choose myself"
    // or when auto-pick finds nothing it may use.
    var autoPickOn by remember(episodes) { mutableStateOf(false) }
    var autoPickGaveUp by remember(episodes) { mutableStateOf(false) }
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
                row.copy(existing = existing, userChecked = existing == null && !row.unaired && row.userChecked)
            }
        }
    }

    fun matchRows(stream: StreamItem): Job {
        val picked = SeasonSourceChoice(stream)
        val targetQuality = seasonQuality
        val toMatch = rows.filter { it.matching }
        return scope.launch {
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
                    val match = if (targetQuality != null) {
                        matchSeasonStreamAtQuality(picked, streams, targetQuality)
                    } else {
                        matchSeasonStream(picked, streams)
                    }
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
                // Only the episodes picked in step 1 are searched.
                row.key !in confirmedKeys -> row.copy(match = null, matching = false)
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
        matchJob = matchRows(stream)
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
        var hardTimeoutElapsed = false
        var allAnswered = false
        autoPickOn = autoSettings.picksAutomatically()
        autoPickGaveUp = false
        anchorPickFrom = { list -> evaluateSeasonAutoPick(list, autoSettings, installedAddonNames).stream }

        fun tryAutoPick() {
            if (choice != null || pickingSource || autoPickGaveUp || !autoSettings.picksAutomatically()) return
            val result = evaluateSeasonAutoPick(anchorStreams.orderedForQuality(SmartDownloadSettings.snapshot().quality), autoSettings, installedAddonNames)
            // Pick when the player's auto-play would: the timeout, the last source, or at once for 0 s.
            val mayDecide = seasonAutoPickMayDecide(autoSettings.timeoutSeconds, timeoutElapsed, allAnswered) ||
                hardTimeoutElapsed
            if (result.stream != null && mayDecide) {
                applyChoice(result.stream, byAutoPick = true)
                return
            }
            if (seasonAutoPickShouldGiveUp(result, allAnswered, hardTimeoutElapsed)) autoPickGaveUp = true
        }

        val timer = launch {
            val seconds = autoSettings.timeoutSeconds
            if (seconds in 1 until Int.MAX_VALUE) {
                delay(seconds * 1_000L)
                timeoutElapsed = true
                tryAutoPick()
            }
        }
        val hardTimer = launch {
            delay(SEASON_AUTO_PICK_GIVE_UP_MS)
            hardTimeoutElapsed = true
            tryAutoPick()
        }
        SeasonStreamCatalog.stream(
            type = meta.type,
            videoId = seasonStreamVideoId(meta.id, anchor.video),
            season = anchor.video.season,
            episode = anchor.video.episode,
            annotateDebridCache = true,
        ).collect { snapshot ->
            anchorStreams = snapshot.streams
            pendingSources = snapshot.pendingSources
            allAnswered = snapshot.complete
            tryAutoPick()
        }
        // All sources answered: the last tryAutoPick decided or gave up, so the timers are not needed.
        if (choice != null || autoPickGaveUp || !autoPickOn) {
            timer.cancel()
            hardTimer.cancel()
        } else {
            timer.join()
            hardTimer.join()
        }
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
        val hinted = matched?.stream?.let(::downloadStreamBytes)
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
    val volume = remember { DownloadDiskInfo.volumeSpace() }
    val lowSpace = spaceShortfallBeforeStart(summary.knownBytes, summary.knownBytes, volume) != null
    val stillMatching = rows.any { it.matching && it.userChecked }
    val selectedBeforeSearch = rows.count { it.canPick && it.userChecked }
    val canQueue = selectionConfirmed && !queuing && !stillMatching &&
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
            if (!selectionConfirmed) {
                val open = rows.filter { it.canPick }
                item(key = "select-title") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = open.isNotEmpty() && open.all { it.userChecked },
                            enabled = open.isNotEmpty(),
                            onCheckedChange = { all ->
                                rows = rows.map { if (it.canPick) it.copy(userChecked = all) else it }
                            },
                        )
                        Text(
                            text = "Whole season",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                items(rows, key = { "select:" + it.key }) { row ->
                    EpisodeSelectRow(
                        row = row,
                        detail = when {
                            row.existing == SeasonExistingDownload.Downloaded -> downloadedText
                            row.existing == SeasonExistingDownload.InProgress -> inDownloadsText
                            row.unaired -> unairedLabel(row.video.released)
                            row.watched -> watchedText
                            else -> null
                        },
                        onCheckedChange = { checked ->
                            rows = rows.map { if (it.key == row.key && it.canPick) it.copy(userChecked = checked) else it }
                        },
                    )
                }
                item(key = "fill-by-size") {
                    val estimate = remember(downloads.items) { typicalEpisodeBytes(meta.id, downloads.items) ?: GUESSED_EPISODE_BYTES }
                    SeasonFillBySizeRow(estimateBytes = estimate) { budgetBytes ->
                        val picked = fillSelection(rows.filter { it.canPick && !it.watched }.map { it.key }, estimate, budgetBytes).toSet()
                        rows = rows.map { if (it.canPick) it.copy(userChecked = it.key in picked) else it }
                    }
                }
                val unaired = rows.filter { it.unaired && it.existing == null }
                if (unaired.isNotEmpty() && SmartDownloadSettings.snapshot().queueUnaired) {
                    item(key = "queue-unaired") {
                        TextButton(
                            onClick = {
                                unaired.forEach { row ->
                                    SmartDownloads.queueUnaired(meta.type, meta.id, row.video.season ?: return@forEach, row.video.episode ?: return@forEach)
                                }
                            },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            Text("Queue ${unaired.size} unaired episode${if (unaired.size == 1) "" else "s"}: they download once they air")
                        }
                    }
                }
                return@LazyColumn
            }
            if (anchorKey == null) {
                item(key = "nothing-left") {
                    SheetNote(stringResource(Res.string.season_download_nothing_left))
                }
            } else if (showPicker && autoPickOn && !pickingSource && !autoPickGaveUp) {
                item(key = "auto-picking") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Picking a source automatically…",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${candidates.size} found" +
                                    if (pendingSources > 0) " · $pendingSources still searching" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { pickingSource = true }) {
                            Text("Choose myself")
                        }
                    }
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
                    SourceRow(stream = stream, onPick = {
                        seasonQuality = null
                        applyChoice(stream, byAutoPick = false)
                    })
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
                    val qualityOptions = bestPerQualityOptions(anchorStreams, anchorPickFrom)
                    if (qualityOptions.isNotEmpty()) {
                        item(key = "season-quality") {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Text(
                                    text = "Quality for the season",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                DownloadQualityOptionsRow(
                                    options = qualityOptions,
                                    selectedBucket = seasonQuality ?: downloadQualityBucket(picked),
                                    showSize = false,
                                    onSelect = { option ->
                                        // Re-pick every selected episode at this quality (nearest lower if missing).
                                        seasonQuality = option.bucket
                                        rows = rows.map { if (it.key in confirmedKeys) it.copy(overridden = false) else it }
                                        applyChoice(option.stream, byAutoPick = autoPicked)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            items(rows.filter { it.key in confirmedKeys }, key = { "episode:" + it.key }) { row ->
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
        if (lowSpace && volume != null) {
            Text(
                text = stringResource(
                    Res.string.downloads_season_sheet_low_space,
                    formatSeasonBytes(summary.knownBytes),
                    formatSeasonBytes(volume.availableBytes),
                    formatSeasonBytes(downloadSpaceReserve(summary.knownBytes)),
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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
                text = if (selectionConfirmed) totalText else "$selectedBeforeSearch selected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (!selectionConfirmed) {
                Button(
                    onClick = {
                        val picked = rows.filter { it.canPick && it.userChecked }
                        if (picked.isEmpty()) return@Button
                        confirmedKeys = picked.map { it.key }.toSet()
                        anchorKey = picked.first().key
                        pendingSources = 1
                        selectionConfirmed = true
                    },
                    enabled = selectedBeforeSearch > 0,
                ) {
                    Text("Find sources")
                }
                return@Row
            }
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
private fun EpisodeSelectRow(
    row: SeasonEpisodeRowState,
    detail: String?,
    onCheckedChange: (Boolean) -> Unit,
) {
    val code = localizedSeasonEpisodeCode(row.video.season, row.video.episode)
    val title = listOfNotNull(code, row.video.title.takeIf { it.isNotBlank() }).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.canPick) { onCheckedChange(!row.userChecked) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = row.canPick && row.userChecked,
            onCheckedChange = if (row.canPick) onCheckedChange else null,
            enabled = row.canPick,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { row.video.id },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
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

private fun StreamItem.sourceDetail(): String =
    streamLabel.trim()

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
        DownloadQualitySizeChips(stream = stream, modifier = Modifier.padding(top = 4.dp))
        stream.sourceDetail().takeIf { it.isNotBlank() }?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
            DownloadQualitySizeChips(stream = stream, modifier = Modifier.padding(top = 4.dp))
            stream.sourceDetail().takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
    // The source this episode will download from, so alternatives only show on Change source.
    val sourceSummary = (row.match as? SeasonStreamMatch.Matched)?.stream
        ?.takeIf { row.existing == null }
        ?.let { stream -> listOfNotNull(stream.addonName.takeIf { it.isNotBlank() }, downloadQualityChipText(stream) ?: streamQualityLabel(stream)).joinToString(" · ") }
        ?.takeIf { it.isNotBlank() }
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
            sourceSummary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
            unaired = !video.hasAiredForDownload(),
        )
    }

private fun SeasonEpisodeRowState.toProbeTarget(): SeasonProbeTarget? {
    if (existing != null) return null
    val matched = match as? SeasonStreamMatch.Matched ?: return null
    if (downloadStreamBytes(matched.stream) != null) return null
    if (probeDone) return null
    val url = matched.stream.playableDirectUrl?.takeIf { it.isSupportedDownloadUrl() } ?: return null
    return SeasonProbeTarget(
        id = key,
        url = url,
        headers = matched.stream.behaviorHints.proxyHeaders?.request.orEmpty(),
    )
}

/** Aired only with a known air date in the past; no date or a future date is not downloadable yet. */
internal fun MetaVideo.hasAiredForDownload(
    nowEpochMs: Long = com.nuvio.app.core.time.EpisodeReleaseDatePlatform.nowEpochMs(),
): Boolean {
    if (!available) return false
    val date = released?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return com.nuvio.app.core.time.isEpisodeReleaseAired(date, nowEpochMs) == true
}

private val AIR_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "Airs 12 Nov 2026" or "Not aired yet" when there is no date. */
internal fun unairedLabel(released: String?): String {
    val parts = released?.trim()?.take(10)?.split("-")?.takeIf { it.size == 3 } ?: return "Not aired yet"
    val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return "Not aired yet"
    val day = parts[2].toIntOrNull() ?: return "Not aired yet"
    return "Airs $day ${AIR_MONTHS[month - 1]} ${parts[0]}"
}

/** "Fill X GB" choices in Download Season. */
private val SEASON_FILL_CHOICES_GB = listOf(5, 10, 20, 50)


/** The leading [keys] (episode order) whose estimated sizes fit in [budgetBytes]. */
internal fun fillSelection(keys: List<String>, episodeBytes: Long, budgetBytes: Long): List<String> =
    keys.take((budgetBytes / episodeBytes.coerceAtLeast(1L)).toInt().coerceAtLeast(0))

@Composable
private fun SeasonFillBySizeRow(estimateBytes: Long, onFill: (Long) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = "Fill with the next episodes (≈ ${formatDownloadBytes(estimateBytes)} each)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SEASON_FILL_CHOICES_GB.forEach { gb ->
                TextButton(onClick = { onFill(gb * 1_000_000_000L) }) { Text("$gb GB") }
            }
        }
    }
}
