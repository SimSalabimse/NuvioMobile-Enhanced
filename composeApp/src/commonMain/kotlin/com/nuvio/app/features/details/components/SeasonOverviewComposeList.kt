package com.nuvio.app.features.details.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import com.nuvio.app.features.details.SeasonMarkSymbolState
import com.nuvio.app.features.details.SeasonOverviewCopy
import com.nuvio.app.features.details.SeasonOverviewListUi
import com.nuvio.app.features.details.SeasonOverviewNativeListHost
import com.nuvio.app.features.details.SeasonOverviewRowUi
import com.nuvio.app.features.details.SeasonOverviewSymbolUi
import com.nuvio.app.features.details.seasonOverviewNativeList
import com.nuvio.app.features.details.seasonSwipeRevealsAction
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.analysis_part_credits
import nuvio.composeapp.generated.resources.season_overview_done
import nuvio.composeapp.generated.resources.season_overview_download
import nuvio.composeapp.generated.resources.season_overview_download_count
import nuvio.composeapp.generated.resources.season_overview_download_selected
import nuvio.composeapp.generated.resources.season_overview_empty
import nuvio.composeapp.generated.resources.season_overview_include_download
import nuvio.composeapp.generated.resources.season_overview_loading
import nuvio.composeapp.generated.resources.season_overview_offline
import nuvio.composeapp.generated.resources.season_overview_open
import nuvio.composeapp.generated.resources.season_overview_public_listed
import nuvio.composeapp.generated.resources.season_overview_public_none
import nuvio.composeapp.generated.resources.season_overview_public_not_checked
import nuvio.composeapp.generated.resources.season_overview_public_unavailable
import nuvio.composeapp.generated.resources.season_overview_remove_download
import nuvio.composeapp.generated.resources.season_overview_segment_detected
import nuvio.composeapp.generated.resources.season_overview_segment_missing
import nuvio.composeapp.generated.resources.season_overview_segment_pending
import nuvio.composeapp.generated.resources.season_overview_segment_submitted
import nuvio.composeapp.generated.resources.season_overview_select
import nuvio.composeapp.generated.resources.season_overview_watched
import nuvio.composeapp.generated.resources.submit_intro_segment_intro
import nuvio.composeapp.generated.resources.submit_intro_segment_preview
import nuvio.composeapp.generated.resources.submit_intro_segment_recap
import nuvio.composeapp.generated.resources.watch_privately
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun seasonOverviewSheetCopy(): SeasonOverviewCopy = SeasonOverviewCopy(
    intro = stringResource(Res.string.submit_intro_segment_intro),
    recap = stringResource(Res.string.submit_intro_segment_recap),
    credits = stringResource(Res.string.analysis_part_credits),
    preview = stringResource(Res.string.submit_intro_segment_preview),
    publicNone = stringResource(Res.string.season_overview_public_none),
    publicNotChecked = stringResource(Res.string.season_overview_public_not_checked),
    publicUnavailable = stringResource(Res.string.season_overview_public_unavailable),
    publicListedTemplate = stringResource(Res.string.season_overview_public_listed),
    emptySeason = stringResource(Res.string.season_overview_empty),
    offline = stringResource(Res.string.season_overview_offline),
    loading = stringResource(Res.string.season_overview_loading),
    downloadAll = stringResource(Res.string.season_overview_download),
    downloadCountTemplate = stringResource(Res.string.season_overview_download_count),
    downloadSelectedTemplate = stringResource(Res.string.season_overview_download_selected),
    cancel = stringResource(Res.string.action_cancel),
    select = stringResource(Res.string.season_overview_select),
    done = stringResource(Res.string.season_overview_done),
    open = stringResource(Res.string.season_overview_open),
    watchPrivately = stringResource(Res.string.watch_privately),
    includeDownload = stringResource(Res.string.season_overview_include_download),
    removeDownload = stringResource(Res.string.season_overview_remove_download),
    watched = stringResource(Res.string.season_overview_watched),
    submittedTemplate = stringResource(Res.string.season_overview_segment_submitted),
    pendingTemplate = stringResource(Res.string.season_overview_segment_pending),
    detectedTemplate = stringResource(Res.string.season_overview_segment_detected),
    missingTemplate = stringResource(Res.string.season_overview_segment_missing),
)

/**
 * Android draws the same grouped list the iOS sheet shows in SwiftUI.
 * Marks swap in place. Nothing here animates while the list scrolls.
 */
@Composable
internal fun SeasonOverviewSheetContent(
    list: SeasonOverviewListUi,
    payload: String,
    onSelecting: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (String) -> Unit,
    onWatchPrivately: (String) -> Unit,
    onInclude: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (seasonOverviewNativeList) {
        SeasonOverviewNativeListHost(
            payload = payload,
            onOpen = onOpen,
            onWatchPrivately = onWatchPrivately,
            onInclude = onInclude,
            onSelecting = onSelecting,
            onDownload = onDownload,
            onCancel = onCancel,
            modifier = modifier,
        )
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        SeasonOverviewToolbar(
            list = list,
            onSelecting = onSelecting,
            onDownload = onDownload,
            onCancel = onCancel,
        )
        val status = list.statusLine
        if (status != null) {
            Text(
                text = status,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("season-overview-status"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val scroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .verticalScroll(scroll),
                ) {
                    list.rows.forEachIndexed { index, row ->
                        SeasonOverviewComposeRow(
                            row = row,
                            list = list,
                            onOpen = { onOpen(row.contentKey) },
                            onWatchPrivately = { onWatchPrivately(row.contentKey) },
                            onInclude = { onInclude(row.contentKey) },
                        )
                        if (index < list.rows.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonOverviewToolbar(
    list: SeasonOverviewListUi,
    onSelecting: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (list.downloadOffered) {
            TextButton(
                onClick = { onSelecting(!list.selecting) },
                modifier = Modifier.testTag("season-overview-select"),
            ) {
                Text(text = list.selectLabel, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(
            text = list.title,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (list.downloadOffered) {
            TextButton(
                onClick = { if (list.running) onCancel() else onDownload() },
                enabled = list.buttonEnabled,
                modifier = Modifier.testTag("season-overview-download"),
            ) {
                if (list.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp).size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(text = list.downloadLabel, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeasonOverviewComposeRow(
    row: SeasonOverviewRowUi,
    list: SeasonOverviewListUi,
    onOpen: () -> Unit,
    onWatchPrivately: () -> Unit,
    onInclude: () -> Unit,
) {
    var menu by remember(row.contentKey) { mutableStateOf(false) }
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
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .combinedClickable(
                        onClick = { if (list.selecting) onInclude() else onOpen() },
                        onLongClick = { menu = true },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { contentDescription = row.spoken }
                    .testTag("season-overview-row-${row.contentKey}"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (list.selecting) {
                    SeasonSelectMark(included = row.included)
                }
                if (row.watched) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = list.watchedLabel,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text(
                        text = row.episodeNumber.toString(),
                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = row.publicLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.symbols.forEach { symbol ->
                        SeasonMarkGlyph(symbol)
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(list.openLabel) },
                    onClick = {
                        menu = false
                        onOpen()
                    },
                )
                DropdownMenuItem(
                    text = { Text(list.watchLabel) },
                    onClick = {
                        menu = false
                        onWatchPrivately()
                    },
                )
                if (list.downloadOffered) {
                    DropdownMenuItem(
                        text = { Text(if (row.included) list.removeLabel else list.includeLabel) },
                        onClick = {
                            menu = false
                            onInclude()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonSelectMark(included: Boolean) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp).size(22.dp)) {
        if (included) {
            drawCircle(color = color)
        } else {
            drawCircle(color = color, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

@Composable
private fun SeasonMarkGlyph(symbol: SeasonOverviewSymbolUi) {
    val scheme = MaterialTheme.colorScheme
    val letterColor = when (symbol.state) {
        SeasonMarkSymbolState.OwnConfirmed -> scheme.onPrimary
        SeasonMarkSymbolState.OwnPending -> scheme.onSurface
        SeasonMarkSymbolState.DetectedHere -> scheme.onSurface
        SeasonMarkSymbolState.Missing -> scheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .clearAndSetSemantics { contentDescription = symbol.accessibility },
        contentAlignment = Alignment.Center,
    ) {
        SeasonMarkShape(symbol)
        Text(
            text = symbol.accessibility.take(1),
            style = MaterialTheme.typography.labelSmall,
            color = letterColor,
        )
    }
}

@Composable
private fun BoxScope.SeasonMarkShape(symbol: SeasonOverviewSymbolUi) {
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val ink = scheme.onSurface
    val dim = scheme.onSurface.copy(alpha = 0.38f)
    val tint = scheme.onSurface.copy(alpha = 0.16f)
    Canvas(modifier = Modifier.matchParentSize()) {
        when (symbol.state) {
            SeasonMarkSymbolState.OwnConfirmed -> drawCircle(color = accent)
            SeasonMarkSymbolState.OwnPending -> drawCircle(
                color = ink,
                style = Stroke(width = 1.5.dp.toPx()),
            )
            SeasonMarkSymbolState.DetectedHere -> drawCircle(color = tint)
            SeasonMarkSymbolState.Missing -> drawCircle(
                color = dim,
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())),
                ),
            )
        }
    }
}
