package com.nuvio.app.features.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioStatusModal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Storage summary for [items], recomputed when a download is added, removed or changes status,
 * and every few seconds while something is downloading.
 */
@Composable
internal fun rememberDownloadStorageSummary(items: List<DownloadItem>, refresh: Int = 0): DownloadStorageSummary? {
    var summary by remember { mutableStateOf<DownloadStorageSummary?>(null) }
    val key = remember(items) { items.map { it.id to it.status }.hashCode() }
    val downloading = items.any { it.status == DownloadStatus.Downloading }
    LaunchedEffect(key, refresh, downloading) {
        while (true) {
            summary = withContext(Dispatchers.Default) {
                runCatching { DownloadsRepository.storageSummary() }.getOrNull()
            } ?: summary
            if (!downloading) break
            delay(5_000L)
        }
    }
    return summary
}

/** Top of Downloads: a bar with what Nuvio uses, everything else, and free space. */
@Composable
internal fun DownloadStorageCard(
    summary: DownloadStorageSummary,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            Res.string.downloads_storage_used_by_downloads,
                            formatDownloadBytes(summary.nuvioBytes),
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val volume = summary.volume
                    Text(
                        text = if (volume != null) {
                            stringResource(
                                Res.string.downloads_storage_of_total,
                                formatDownloadBytes(volume.availableBytes),
                                formatDownloadBytes(volume.totalBytes),
                            )
                        } else {
                            stringResource(Res.string.downloads_storage_unknown_space)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onClick != null) {
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = stringResource(Res.string.downloads_storage_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val volume = summary.volume
            if (volume != null) {
                StorageBar(
                    nuvio = summary.nuvioBytes + summary.orphanBytes,
                    other = summary.otherBytes ?: 0L,
                    free = volume.availableBytes,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Legend(MaterialTheme.colorScheme.primary, stringResource(Res.string.downloads_storage_nuvio, formatDownloadBytes(summary.nuvioBytes + summary.orphanBytes)))
                    Legend(otherColor(), stringResource(Res.string.downloads_storage_other, formatDownloadBytes(summary.otherBytes ?: 0L)))
                    Legend(freeColor(), stringResource(Res.string.downloads_storage_free, formatDownloadBytes(volume.availableBytes)))
                }
                if (lacksSpaceFor(null, volume)) {
                    Text(
                        text = stringResource(Res.string.downloads_storage_low, formatDownloadBytes(DOWNLOAD_FREE_SPACE_MARGIN_BYTES)),
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun otherColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

@Composable
private fun freeColor(): Color = MaterialTheme.colorScheme.surfaceVariant

@Composable
private fun StorageBar(nuvio: Long, other: Long, free: Long, modifier: Modifier = Modifier) {
    val total = (nuvio + other + free).coerceAtLeast(1L).toFloat()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(freeColor()),
    ) {
        listOf(
            nuvio to MaterialTheme.colorScheme.primary,
            other to otherColor(),
            free to freeColor(),
        ).forEach { (bytes, color) ->
            // A sliver stays visible so a few hundred MB still shows up on a large disk.
            val fraction = (bytes / total).let { if (bytes > 0L) it.coerceAtLeast(0.01f) else 0f }
            if (fraction > 0f) {
                Box(modifier = Modifier.weight(fraction).fillMaxHeight().background(color))
            }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private data class PendingStorageDelete(val label: String, val ids: List<String>, val bytes: Long)

@OptIn(ExperimentalFoundationApi::class)
/** Storage: the card, sizes per title and season (largest first) with delete, and leftovers. */
@Composable
internal fun DownloadStorageScreen(
    items: List<DownloadItem>,
    onBack: () -> Unit,
) {
    var refresh by remember { mutableIntStateOf(0) }
    val summary = rememberDownloadStorageSummary(items, refresh)
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<PendingStorageDelete?>(null) }
    var confirmOrphans by remember { mutableStateOf(false) }
    val specialsLabel = stringResource(Res.string.downloads_storage_specials)

    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.downloads_storage_title),
                onBack = onBack,
            )
        }
        val current = summary ?: return@NuvioScreen
        item(key = "storage-card") { DownloadStorageCard(summary = current, onClick = null) }

        if (current.orphans.isNotEmpty()) {
            item(key = "storage-orphans") {
                StorageRow(
                    title = stringResource(Res.string.downloads_storage_leftovers),
                    subtitle = stringResource(
                        Res.string.downloads_storage_leftovers_hint,
                        current.orphans.size,
                        formatDownloadBytes(current.orphanBytes),
                    ),
                    trailing = {
                        TextButton(onClick = { confirmOrphans = true }) {
                            Text(stringResource(Res.string.downloads_storage_leftovers_clear), color = MaterialTheme.colorScheme.error)
                        }
                    },
                )
            }
        }

        item(key = "storage-by-title") {
            Text(
                text = stringResource(Res.string.downloads_storage_by_title),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (current.titles.isEmpty()) {
            item(key = "storage-empty") {
                Text(
                    text = stringResource(Res.string.downloads_storage_empty),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        current.titles.forEach { title ->
            val titleKey = title.parentMetaId + if (title.isShow) "|show" else "|movie"
            val isExpanded = titleKey in expanded
            item(key = "storage-title:$titleKey") {
                StorageRow(
                    title = title.title,
                    subtitle = stringResource(Res.string.downloads_storage_count, title.downloadIds.size) +
                        " · " + formatDownloadBytes(title.bytes),
                    onClick = if (title.isShow) {
                        { expanded = if (isExpanded) expanded - titleKey else expanded + titleKey }
                    } else {
                        null
                    },
                    leadingExpand = if (title.isShow) isExpanded else null,
                    trailing = {
                        DeleteButton { pendingDelete = PendingStorageDelete(title.title, title.downloadIds, title.bytes) }
                    },
                )
            }
            if (title.isShow && isExpanded) {
                title.seasons.forEach { season ->
                    item(key = "storage-season:$titleKey:${season.seasonNumber}") {
                        val label = if (season.seasonNumber == 0) {
                            specialsLabel
                        } else {
                            stringResource(Res.string.downloads_storage_season, season.seasonNumber)
                        }
                        StorageRow(
                            title = label,
                            subtitle = stringResource(Res.string.downloads_storage_count, season.downloadIds.size) +
                                " · " + formatDownloadBytes(season.bytes),
                            indent = true,
                            trailing = {
                                DeleteButton {
                                    pendingDelete = PendingStorageDelete("${title.title} – $label", season.downloadIds, season.bytes)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { pending ->
        NuvioStatusModal(
            title = stringResource(Res.string.downloads_storage_delete_title, pending.label),
            message = stringResource(Res.string.downloads_storage_delete_message, pending.ids.size, formatDownloadBytes(pending.bytes)),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                DownloadsRepository.cancelDownloads(pending.ids)
                pendingDelete = null
                refresh++
            },
            onDismiss = { pendingDelete = null },
        )
    }

    val orphans = summary?.orphans.orEmpty()
    if (confirmOrphans && orphans.isNotEmpty()) {
        NuvioStatusModal(
            title = stringResource(Res.string.downloads_storage_leftovers_confirm_title),
            message = stringResource(
                Res.string.downloads_storage_leftovers_confirm_message,
                orphans.size,
                formatDownloadBytes(orphans.sumOf { it.sizeBytes }),
            ),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                DownloadsRepository.clearOrphans(orphans)
                confirmOrphans = false
                refresh++
            },
            onDismiss = { confirmOrphans = false },
        )
    }
}

@Composable
private fun DeleteButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.size(4.dp))
        Text(stringResource(Res.string.downloads_storage_delete), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun StorageRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    leadingExpand: Boolean? = null,
    indent: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(start = if (indent) 32.dp else 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingExpand != null) {
            Icon(
                imageVector = if (leadingExpand) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(6.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}
