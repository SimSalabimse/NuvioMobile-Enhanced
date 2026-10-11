package com.nuvio.app.features.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.DownloadsSettingsScreen
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    initialShowId: String? = null,
    onNavigateToShow: ((showId: String, title: String) -> Unit)? = null,
    onBackFromShow: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenStorage: (() -> Unit)? = null,
) {
    val uiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.completedItems.size) {
        DownloadsRepository.markCompletedSeen()
    }

    var selectedShowId by rememberSaveable(initialShowId) { mutableStateOf(initialShowId) }
    var downloadPendingDeletionId by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedSeasonGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var groupPendingCancel by remember { mutableStateOf<ActiveSeasonGroup?>(null) }
    val openDownloadsDirectoryFailedText = stringResource(Res.string.downloads_open_directory_failed)
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showStorage by rememberSaveable { mutableStateOf(false) }
    // Held here, not inside NuvioScreen, so the list keeps its scroll while Storage or the
    // settings page is drawn in its place (Android, or iOS without native navigation).
    val listState = rememberLazyListState()

    // Finished and unfinished episodes: a show with only waiting or paused episodes still has a
    // Shows entry, so its season can be stopped from there.
    val allEpisodes = remember(uiState.items) {
        uiState.items
            .filter { it.isEpisode }
            .sortedForSeriesDownloads()
    }

    val selectedShowTitle = remember(selectedShowId, allEpisodes) {
        selectedShowId?.let { showId ->
            allEpisodes.firstOrNull { it.parentMetaId == showId }?.title
        }
    }

    if (showSettings) {
        PlatformBackHandler(enabled = true) { showSettings = false }
        DownloadsSettingsScreen(
            onBack = { showSettings = false },
        )
        return
    }

    if (showStorage) {
        PlatformBackHandler(enabled = true) { showStorage = false }
        DownloadStorageScreen(items = uiState.items, onBack = { showStorage = false })
        return
    }

    val storageSummary = rememberDownloadStorageSummary(uiState.items)
    val tokens = MaterialTheme.nuvio

    NuvioScreen(listState = listState) {
        stickyHeader {
            NuvioScreenHeader(
                title = if (selectedShowId == null) {
                    stringResource(Res.string.compose_settings_root_downloads_title)
                } else {
                    selectedShowTitle ?: stringResource(Res.string.downloads_show_downloads)
                },
                onBack = {
                    if (selectedShowId != null) {
                        onBackFromShow?.invoke() ?: run { selectedShowId = null }
                    } else {
                        onBack()
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!DownloadsPlatformDownloader.openDownloadsDirectory()) {
                                NuvioToastController.show(openDownloadsDirectoryFailedText)
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = stringResource(Res.string.downloads_open_directory),
                            tint = tokens.colors.textPrimary,
                        )
                    }
                    if (selectedShowId == null) {
                        IconButton(onClick = {
                            onOpenSettings?.invoke() ?: run { showSettings = true }
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = stringResource(Res.string.compose_settings_page_root),
                                tint = tokens.colors.textPrimary,
                            )
                        }
                    }
                },
            )
        }

        if (selectedShowId == null) {
            storageSummary?.let { summary ->
                item(key = "storage-card") {
                    DownloadStorageCard(
                        summary = summary,
                        onClick = { onOpenStorage?.invoke() ?: run { showStorage = true } },
                    )
                }
            }
            downloadsRootContent(
                uiState = uiState,
                onOpenDownload = onOpenDownload,
                onOpenShow = { showId, title ->
                    onNavigateToShow?.invoke(showId, title) ?: run { selectedShowId = showId }
                },
                onDeleteDownload = { downloadPendingDeletionId = it },
                expandedSeasonGroups = expandedSeasonGroups,
                onToggleSeasonGroup = { key ->
                    expandedSeasonGroups = if (key in expandedSeasonGroups) {
                        expandedSeasonGroups - key
                    } else {
                        expandedSeasonGroups + key
                    }
                },
                onCancelSeasonGroup = { groupPendingCancel = it },
            )
        } else {
            downloadsShowContent(
                showId = selectedShowId.orEmpty(),
                episodes = allEpisodes,
                allItems = uiState.items,
                onOpenDownload = onOpenDownload,
                onDeleteDownload = { downloadPendingDeletionId = it },
                onCancelSeasonGroup = { groupPendingCancel = it },
            )
        }
    }

    val pendingDeletionId = downloadPendingDeletionId
    if (pendingDeletionId != null) {
        NuvioStatusModal(
            title = stringResource(Res.string.action_delete_confirm_title),
            message = stringResource(Res.string.action_delete_confirm_message),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                DownloadsRepository.cancelDownload(pendingDeletionId)
                downloadPendingDeletionId = null
            },
            onDismiss = { downloadPendingDeletionId = null },
        )
    }

    val pendingGroup = groupPendingCancel
    if (pendingGroup != null) {
        NuvioStatusModal(
            title = stringResource(Res.string.downloads_group_cancel_title, pendingGroup.items.size),
            message = stringResource(Res.string.downloads_group_cancel_message, pendingGroup.label),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                DownloadsRepository.cancelDownloads(pendingGroup.items.map { it.id })
                groupPendingCancel = null
            },
            onDismiss = { groupPendingCancel = null },
        )
    }
}

private fun LazyListScope.downloadsRootContent(
    uiState: DownloadsUiState,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenShow: (showId: String, title: String) -> Unit,
    onDeleteDownload: (String) -> Unit,
    expandedSeasonGroups: Set<String>,
    onToggleSeasonGroup: (String) -> Unit,
    onCancelSeasonGroup: (ActiveSeasonGroup) -> Unit,
) {
    val activeItems = uiState.activeItems
    val completedMovies = uiState.completedItems.filterNot(DownloadItem::isEpisode)
    val completedShows = uiState.items
        .filter(DownloadItem::isEpisode)
        .groupBy { it.parentMetaId }
        .mapNotNull { (_, episodes) ->
            episodes.firstOrNull()?.let { first ->
                first to episodes
            }
        }
        .sortedBy { (item, _) -> item.title.lowercase() }

    if (activeItems.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_active))
        }
        if (activeItems.any(DownloadItem::isEpisode)) {
            item {
                Text(
                    text = stringResource(Res.string.downloads_cancel_season_hint),
                    modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        activeDownloadEntries(activeItems).forEach { entry ->
            when (entry) {
                is ActiveDownloadEntry.Single -> item(key = entry.item.id) {
                    DownloadRow(
                        item = entry.item,
                        onOpen = { onOpenDownload(entry.item) },
                        onPause = { DownloadsRepository.pauseDownload(entry.item.id) },
                        onResume = { DownloadsRepository.resumeDownload(entry.item.id) },
                        onRetry = { DownloadsRepository.retryDownload(entry.item.id) },
                        onDelete = { onDeleteDownload(entry.item.id) },
                        onLongPress = unfinishedSeasonGroup(uiState.items, entry.item)?.let { group ->
                            { onCancelSeasonGroup(group) }
                        },
                    )
                }
                is ActiveDownloadEntry.Season -> {
                    val group = entry.group
                    val expanded = group.key in expandedSeasonGroups
                    item(key = "season-group:${group.key}") {
                        ActiveSeasonGroupHeader(
                            group = group,
                            expanded = expanded,
                            onToggle = { onToggleSeasonGroup(group.key) },
                            onPauseAll = { DownloadsRepository.pauseDownloads(group.items.map { it.id }) },
                            onResumeAll = { DownloadsRepository.resumeDownloads(group.items.map { it.id }) },
                            onCancelAll = { onCancelSeasonGroup(group) },
                        )
                    }
                    if (expanded) {
                        items(items = group.items, key = { it.id }) { item ->
                            DownloadRow(
                                item = item,
                                onOpen = { onOpenDownload(item) },
                                onPause = { DownloadsRepository.pauseDownload(item.id) },
                                onResume = { DownloadsRepository.resumeDownload(item.id) },
                                onRetry = { DownloadsRepository.retryDownload(item.id) },
                                onDelete = { onDeleteDownload(item.id) },
                                onLongPress = { onCancelSeasonGroup(group) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (completedMovies.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_movies))
        }
        items(
            items = completedMovies,
            key = { it.id },
        ) { item ->
            DownloadRow(
                item = item,
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
            )
        }
    }

    if (completedShows.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_shows))
        }
        items(
            items = completedShows,
            key = { (item, _) -> item.parentMetaId },
        ) { (item, episodes) ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable { onOpenShow(item.parentMetaId, item.title) },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val doneCount = episodes.count { it.status == DownloadStatus.Completed }
                        val unfinishedCount = episodes.size - doneCount
                        Text(
                            text = listOfNotNull(
                                stringResource(Res.string.downloads_episode_count, doneCount),
                                if (unfinishedCount > 0) {
                                    stringResource(Res.string.downloads_in_progress_count, unfinishedCount)
                                } else {
                                    null
                                },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // One season in progress: stop it right here. Several: each season has its own
                    // Cancel season inside the show.
                    val unfinishedSeasons = unfinishedSeasonGroupsOfShow(uiState.items, item.parentMetaId)
                    if (unfinishedSeasons.size == 1) {
                        val group = unfinishedSeasons.first()
                        TextButton(onClick = { onCancelSeasonGroup(group) }) {
                            Text(
                                text = stringResource(Res.string.downloads_season_cancel),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (uiState.items.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun LazyListScope.downloadsShowContent(
    showId: String,
    episodes: List<DownloadItem>,
    allItems: List<DownloadItem>,
    onOpenDownload: (DownloadItem) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onCancelSeasonGroup: (ActiveSeasonGroup) -> Unit,
) {
    val showEpisodes = episodes
        .filter { it.parentMetaId == showId }
        .sortedForSeriesDownloads()

    val seasons = showEpisodes
        .groupBy { it.seasonNumber ?: 0 }
        .toList()
        .sortedWith(
            compareBy<Pair<Int, List<DownloadItem>>> { (season, _) ->
                if (season == 0) 0 else 1
            }.thenBy { (season, _) -> if (season == 0) 0 else season },
        )

    if (seasons.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_episodes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    seasons.forEach { (seasonNumber, entries) ->
        val unfinished = unfinishedSeasonGroup(allItems, entries.first())
        item(key = "show-season:$seasonNumber") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    SectionTitle(
                        if (seasonNumber == 0) {
                            stringResource(Res.string.episodes_specials)
                        } else {
                            stringResource(Res.string.episodes_season, seasonNumber)
                        },
                    )
                }
                if (unfinished != null) {
                    SeasonGroupActions(
                        group = unfinished,
                        onCancel = { onCancelSeasonGroup(unfinished) },
                    )
                }
            }
        }

        val sortedEpisodes = entries.sortedForSeriesDownloads()

        items(
            items = sortedEpisodes,
            key = { it.id },
        ) { item ->
            DownloadRow(
                item = item,
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
                onLongPress = if (item.status != DownloadStatus.Completed && unfinished != null) {
                    { onCancelSeasonGroup(unfinished) }
                } else {
                    null
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    item: DownloadItem,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val displayTitle = item.displayTitle()
    val displaySubtitle = downloadDisplaySubtitle(
        item = item,
        displayTitle = displayTitle,
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .then(
                if (onLongPress != null) {
                    Modifier.combinedClickable(
                        onClick = { if (item.isPlayable) onOpen() },
                        onLongClick = onLongPress,
                    )
                } else {
                    Modifier.clickable(enabled = item.isPlayable, onClick = onOpen)
                },
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = displaySubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusText(item),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.playsWhenDownloadFinishes && item.status != DownloadStatus.Completed) {
                        Text(
                            text = stringResource(Res.string.downloads_plays_when_finished),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.earlyPlayReady && item.status != DownloadStatus.Completed) {
                        IconButton(onClick = onOpen) {
                            Icon(
                                imageVector = Icons.Rounded.PlayCircleOutline,
                                contentDescription = stringResource(Res.string.action_watch),
                            )
                        }
                    }
                    when (item.status) {
                        DownloadStatus.Downloading -> {
                            IconButton(onClick = onPause) {
                                Icon(
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = stringResource(Res.string.compose_action_pause),
                                )
                            }
                        }
                        DownloadStatus.Paused -> {
                            IconButton(onClick = onResume) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_resume),
                                )
                            }
                        }
                        DownloadStatus.Failed -> {
                            IconButton(onClick = onRetry) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(Res.string.action_retry),
                                )
                            }
                        }
                        DownloadStatus.Completed -> {
                            IconButton(onClick = onOpen) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_play),
                                )
                            }
                        }
                        DownloadStatus.Queued -> Unit
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(Res.string.action_delete),
                        )
                    }
                }
            }

            if (item.status == DownloadStatus.Downloading) {
                if (item.totalBytes != null && item.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = item.progressFraction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun DownloadItem.displayTitle(): String =
    if (isEpisode) {
        episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: title
    } else {
        title
    }

@Composable
private fun downloadDisplaySubtitle(
    item: DownloadItem,
    displayTitle: String,
): String {
    val seasonNumber = item.seasonNumber
    val episodeNumber = item.episodeNumber
    if (seasonNumber == null || episodeNumber == null) {
        return item.displaySubtitle
    }

    val episodeCode = stringResource(
        Res.string.compose_player_episode_code_full,
        seasonNumber,
        episodeNumber,
    )
    return listOf(
        episodeCode,
        item.episodeTitle?.trim().orEmpty().takeIf { it.isNotBlank() && it != displayTitle },
        item.title.trim().takeIf { it.isNotBlank() && it != displayTitle },
    ).filterNotNull().joinToString(" • ")
}

/**
 * The season's own card at the top of Active: name, count and progress, and labelled
 * Pause/Resume season and Cancel season buttons. Tapping the card lists its episodes.
 */
@Composable
private fun ActiveSeasonGroupHeader(
    group: ActiveSeasonGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onCancelAll: () -> Unit,
) {
    val percent = group.progressPercent
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onToggle),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(
                            stringResource(Res.string.downloads_group_count, group.items.size),
                            percent?.let { "$it%" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (percent != null) {
                LinearProgressIndicator(
                    progress = percent / 100f,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (group.anyRunning) {
                    TextButton(onClick = onPauseAll) {
                        Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(stringResource(Res.string.downloads_season_pause))
                    }
                } else if (group.anyPaused) {
                    TextButton(onClick = onResumeAll) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(stringResource(Res.string.downloads_season_resume))
                    }
                }
                TextButton(onClick = onCancelAll) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = stringResource(Res.string.downloads_season_cancel),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** Pause/Resume season and Cancel season next to a season title in a show's downloads. */
@Composable
private fun SeasonGroupActions(
    group: ActiveSeasonGroup,
    onCancel: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (group.anyRunning) {
            TextButton(onClick = { DownloadsRepository.pauseDownloads(group.items.map { it.id }) }) {
                Text(stringResource(Res.string.downloads_season_pause))
            }
        } else if (group.anyPaused) {
            TextButton(onClick = { DownloadsRepository.resumeDownloads(group.items.map { it.id }) }) {
                Text(stringResource(Res.string.downloads_season_resume))
            }
        }
        TextButton(onClick = onCancel) {
            Text(
                text = stringResource(Res.string.downloads_season_cancel),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun statusText(item: DownloadItem): String {
    val size = if (item.totalBytes != null && item.totalBytes > 0L) {
        "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}"
    } else {
        formatBytes(item.downloadedBytes)
    }

    return when (item.status) {
        DownloadStatus.Downloading -> {
            val label = stringResource(Res.string.downloads_status_downloading, size)
            val percent = item.downloadsScreenProgressPercent()
            if (percent != null) "$label • $percent%" else label
        }
        DownloadStatus.Paused -> stringResource(Res.string.downloads_status_paused, size)
        DownloadStatus.Queued -> stringResource(Res.string.downloads_status_queued)
        DownloadStatus.Completed -> stringResource(
            Res.string.downloads_status_completed,
            formatBytes(item.totalBytes ?: item.downloadedBytes),
        )
        DownloadStatus.Failed -> item.errorMessage ?: stringResource(Res.string.downloads_status_failed)
    }
}

private fun formatBytes(bytes: Long): String = formatDownloadBytes(bytes)

internal fun formatDownloadBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    val value = bytes.toDouble()
    return when {
        value >= gib -> "${((value / gib) * 10.0).toInt() / 10.0} ${localizedByteUnit("GB")}"
        value >= mib -> "${((value / mib) * 10.0).toInt() / 10.0} ${localizedByteUnit("MB")}"
        value >= kib -> "${((value / kib) * 10.0).toInt() / 10.0} ${localizedByteUnit("KB")}"
        else -> "$bytes ${localizedByteUnit("B")}"
    }
}
