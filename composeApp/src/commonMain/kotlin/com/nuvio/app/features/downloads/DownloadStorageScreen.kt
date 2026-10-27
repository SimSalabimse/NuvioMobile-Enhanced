package com.nuvio.app.features.downloads

import com.nuvio.app.core.format.countText
import org.jetbrains.compose.resources.pluralStringResource
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
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
 * Storage summary for [items], recomputed when it appears, right after a download is added,
 * deleted, finishes or changes status (again after 1.5 s and 4 s, since iOS updates its free-space
 * figure a moment after a delete), then every 5 s while downloading and every 15 s otherwise,
 * for as long as it is on screen. Each refresh also retries downloads that stopped for space.
 */
@Composable
internal fun rememberDownloadStorageSummary(items: List<DownloadItem>, refresh: Int = 0): DownloadStorageSummary? {
    var summary by remember { mutableStateOf<DownloadStorageSummary?>(null) }
    val key = remember(items) { items.map { it.id to it.status }.hashCode() }
    val downloading = items.any { it.status == DownloadStatus.Downloading }
    LaunchedEffect(key, refresh, downloading) {
        suspend fun recompute() {
            summary = withContext(Dispatchers.Default) {
                runCatching { DownloadsRepository.storageSummary() }.getOrNull()
            } ?: summary
        }
        recompute()
        delay(1_500L)
        recompute()
        delay(2_500L)
        recompute()
        while (true) {
            delay(if (downloading) 5_000L else 15_000L)
            recompute()
            DownloadsRepository.retrySpaceFailures()
        }
    }
    return summary
}

/** Top of Downloads: a bar with what Nuvio uses, everything else, and free space. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun DownloadStorageCard(
    summary: DownloadStorageSummary,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onCleaned: () -> Unit = {},
) {
    var confirmCleanUp by remember { mutableStateOf(false) }
    var confirmIncomplete by remember { mutableStateOf(false) }
    if (confirmIncomplete && summary.incompleteIds.isNotEmpty()) {
        NuvioStatusModal(
            title = "Delete failed / incomplete downloads?",
            message = "${countText(summary.incompleteIds.size, "download")} that stopped part way (failed or paused) and their " +
                "${formatDownloadBytes(summary.incompleteBytes)} on disk will be deleted.",
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                DownloadsRepository.deleteIncompleteDownloads(summary.incompleteIds)
                com.nuvio.app.core.ui.NuvioToastController.show("Deleted ${formatDownloadBytes(summary.incompleteBytes)}")
                confirmIncomplete = false
                onCleaned()
            },
            onDismiss = { confirmIncomplete = false },
        )
    }
    val leftoverCount = summary.orphans.size + (summary.app?.leftovers?.size ?: 0)
    val leftoverBytes = summary.allLeftoverBytes
    if (confirmCleanUp && leftoverCount > 0) {
        NuvioStatusModal(
            title = stringResource(Res.string.downloads_storage_leftovers_confirm_title),
            message = pluralStringResource(
                Res.plurals.downloads_storage_leftovers_confirm_message,
                leftoverCount,
                leftoverCount,
                formatDownloadBytes(leftoverBytes),
            ),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                val freed = DownloadsRepository.cleanUpLeftovers()
                com.nuvio.app.core.ui.NuvioToastController.show("Cleaned up ${formatDownloadBytes(freed)}")
                confirmCleanUp = false
                onCleaned()
            },
            onDismiss = { confirmCleanUp = false },
        )
    }
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
                        text = if (summary.app != null) {
                            "Nuvio uses ${formatDownloadBytes(summary.appTotalBytes)}"
                        } else {
                            stringResource(
                                Res.string.downloads_storage_used_by_downloads,
                                formatDownloadBytes(summary.nuvioBytes),
                            )
                        },
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
                    // What the app total is made of, so it matches iOS Settings > Documents & Data.
                    Text(
                        text = storageBreakdownText(summary),
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
                    nuvio = summary.appTotalBytes,
                    other = summary.otherBytes ?: 0L,
                    free = volume.availableBytes,
                    modifier = Modifier.padding(top = 10.dp),
                )
                // Wraps on narrow phones instead of cutting the last size off.
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Legend(MaterialTheme.colorScheme.primary, stringResource(Res.string.downloads_storage_nuvio, formatDownloadBytes(summary.appTotalBytes)))
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
            // Always shown on the card; greyed out with "0 B" when there is nothing to clean.
            androidx.compose.material3.FilledTonalButton(
                onClick = { confirmCleanUp = true },
                enabled = leftoverCount > 0 && leftoverBytes >= 0L,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) {
                Text("Clean up leftovers · ${formatDownloadBytes(if (leftoverCount > 0) leftoverBytes else 0L)}")
            }
            // Rows that stopped part way keep their partial bytes; listed apart from leftovers.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Failed / incomplete downloads: ${formatDownloadBytes(summary.incompleteBytes)}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.TextButton(
                    onClick = { confirmIncomplete = true },
                    enabled = summary.incompleteIds.isNotEmpty(),
                ) {
                    Text("Delete", color = if (summary.incompleteIds.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // The health check on demand: damaged files are downloaded again (through the space check).
            androidx.compose.material3.TextButton(
                onClick = {
                    val damaged = DownloadsRepository.verifyDownloads()
                    com.nuvio.app.core.ui.NuvioToastController.show(
                        if (damaged == 0) "Downloads checked: none damaged" else "$damaged damaged, re-downloading",
                    )
                    onCleaned()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Verify downloads")
            }
        }
    }
}

/**
 * Same sections as [DownloadStorageCard] (title, space, bar, legend, the three actions),
 * drawn as bars so the list slot does not grow when the summary arrives.
 * The low-space warning is not reserved: it only appears once storage is known to be low.
 */
@Composable
internal fun DownloadStorageCardPlaceholder(modifier: Modifier = Modifier) {
    val loading = stringResource(Res.string.downloads_storage_loading)
    val skeleton = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = loading },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            SkeletonLine(MaterialTheme.typography.titleSmall, 0.62f)
            SkeletonLine(MaterialTheme.typography.bodySmall, 0.78f)
            SkeletonLine(MaterialTheme.typography.bodySmall, 0.9f)
            Box(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(skeleton),
            )
            SkeletonLine(MaterialTheme.typography.labelSmall, 0.72f, top = 6.dp)
            Box(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .height(ButtonDefaults.MinHeight)
                    .clip(RoundedCornerShape(20.dp))
                    .background(skeleton),
            )
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(ButtonDefaults.MinHeight)
                    .clip(RoundedCornerShape(20.dp))
                    .background(skeleton),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ButtonDefaults.MinHeight)
                    .clip(RoundedCornerShape(20.dp))
                    .background(skeleton),
            )
        }
    }
}

@Composable
private fun SkeletonLine(style: TextStyle, widthFraction: Float, top: Dp = 0.dp) {
    Text(
        text = " ",
        modifier = Modifier
            .padding(top = top)
            .fillMaxWidth(widthFraction)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(4.dp)),
        style = style,
        color = Color.Transparent,
        maxLines = 1,
    )
}

/** "Downloads 16.2 GB · Subtitles & extras 20 MB · Cache 150 MB · Leftovers 1.0 GB · App data 80 MB". */
internal fun storageBreakdownText(summary: DownloadStorageSummary): String = buildList {
    add("Downloads ${formatDownloadBytes(summary.downloadBytes)}")
    if (summary.subtitleBytes > 0L) add("Subtitles & extras ${formatDownloadBytes(summary.subtitleBytes)}")
    summary.app?.cacheBytes?.takeIf { it > 0L }?.let { add("Cache ${formatDownloadBytes(it)}") }
    if (summary.allLeftoverBytes > 0L) add("Leftovers ${formatDownloadBytes(summary.allLeftoverBytes)}")
    if (summary.app != null && summary.appOtherBytes > 0L) add("App data ${formatDownloadBytes(summary.appOtherBytes)}")
}.joinToString(" · ")

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
    var reanalyzeTarget by remember { mutableStateOf<ReanalyzeTarget?>(null) }
    val specialsLabel = stringResource(Res.string.downloads_storage_specials)

    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.downloads_storage_title),
                onBack = onBack,
            )
        }
        val current = summary ?: return@NuvioScreen
        item(key = "storage-card") { DownloadStorageCard(summary = current, onClick = null, onCleaned = { refresh++ }) }

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
                    subtitle = pluralStringResource(Res.plurals.downloads_storage_count, title.downloadIds.size, title.downloadIds.size) +
                        " · " + formatDownloadBytes(title.bytes),
                    onClick = if (title.isShow) {
                        { expanded = if (isExpanded) expanded - titleKey else expanded + titleKey }
                    } else {
                        null
                    },
                    leadingExpand = if (title.isShow) isExpanded else null,
                    trailing = {
                        ReanalyzeButton { reanalyzeTarget = ReanalyzeTarget(title.title, title.downloadIds) }
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
                            subtitle = pluralStringResource(Res.plurals.downloads_storage_count, season.downloadIds.size, season.downloadIds.size) +
                                " · " + formatDownloadBytes(season.bytes),
                            indent = true,
                            trailing = {
                                ReanalyzeButton {
                                    reanalyzeTarget = ReanalyzeTarget("${title.title} – $label", season.downloadIds)
                                }
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

    ReanalyzeModal(target = reanalyzeTarget, onDismiss = { reanalyzeTarget = null })

    pendingDelete?.let { pending ->
        NuvioStatusModal(
            title = stringResource(Res.string.downloads_storage_delete_title, pending.label),
            message = pluralStringResource(Res.plurals.downloads_storage_delete_message, pending.ids.size, pending.ids.size, formatDownloadBytes(pending.bytes)),
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

}

@Composable
private fun ReanalyzeButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Rounded.Refresh,
            contentDescription = "Re-analyze",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
