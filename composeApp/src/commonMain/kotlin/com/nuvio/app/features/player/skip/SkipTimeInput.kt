package com.nuvio.app.features.player.skip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.atLeastIosHitTarget
import kotlin.math.abs
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.submit_intro_capture_button
import nuvio.composeapp.generated.resources.submit_intro_time_hours
import nuvio.composeapp.generated.resources.submit_intro_time_minutes
import nuvio.composeapp.generated.resources.submit_intro_time_nudge_back_1
import nuvio.composeapp.generated.resources.submit_intro_time_nudge_forward_1
import nuvio.composeapp.generated.resources.submit_intro_time_seconds
import org.jetbrains.compose.resources.stringResource

internal const val EMPTY_SKIP_TIMESTAMP = "00:00:00"

internal const val MAX_SKIP_TIMESTAMP_SECONDS = 99 * 3600 + 59 * 60 + 59

internal enum class SkipTimePart {
    HOURS,
    MINUTES,
    SECONDS,
}

internal fun formatSecondsToHms(seconds: Double): String {
    val total = seconds.toInt().coerceIn(0, MAX_SKIP_TIMESTAMP_SECONDS)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return listOf(hours, minutes, secs).joinToString(":") { it.toString().padStart(2, '0') }
}

/**
 * Accepts `HH:MM:SS`, legacy `MM:SS` (minutes may exceed 59), or a raw second count.
 * `.` is treated as a separator so values typed before hours existed still parse.
 */
internal fun parseTimeToSeconds(input: String): Double? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val separator = when {
        trimmed.contains(':') -> ':'
        trimmed.contains('.') -> '.'
        else -> null
    }
    if (separator == null) {
        return trimmed.toDoubleOrNull()?.takeIf { it >= 0.0 }
    }
    val parts = trimmed.split(separator)
    if (parts.any { it.isBlank() }) return null
    return when (parts.size) {
        3 -> {
            val hours = parts[0].toIntOrNull() ?: return null
            val minutes = parts[1].toIntOrNull() ?: return null
            val secs = parts[2].toIntOrNull() ?: return null
            if (hours < 0 || minutes !in 0..59 || secs !in 0..59) return null
            (hours * 3600 + minutes * 60 + secs).toDouble()
        }
        2 -> {
            val minutes = parts[0].toIntOrNull() ?: return null
            val secs = parts[1].toIntOrNull() ?: return null
            if (minutes < 0 || secs !in 0..59) return null
            (minutes * 60 + secs).toDouble()
        }
        else -> null
    }
}

internal fun skipTimeParts(input: String): Triple<Int, Int, Int> {
    val total = (parseTimeToSeconds(input) ?: 0.0).toInt().coerceIn(0, MAX_SKIP_TIMESTAMP_SECONDS)
    return Triple(total / 3600, (total % 3600) / 60, total % 60)
}

internal fun stepSkipTimestamp(input: String, deltaSeconds: Int): String {
    val total = ((parseTimeToSeconds(input) ?: 0.0).toInt() + deltaSeconds)
        .coerceIn(0, MAX_SKIP_TIMESTAMP_SECONDS)
    return formatSecondsToHms(total.toDouble())
}

internal fun stepSkipTimePart(input: String, part: SkipTimePart, delta: Int): String {
    val unit = when (part) {
        SkipTimePart.HOURS -> 3600
        SkipTimePart.MINUTES -> 60
        SkipTimePart.SECONDS -> 1
    }
    return stepSkipTimestamp(input, delta * unit)
}

internal data class WheelRow(val index: Int, val offset: Int, val size: Int)

/** The row whose center is closest to the middle of the wheel viewport. */
internal fun centeredWheelIndex(viewportStart: Int, viewportEnd: Int, rows: List<WheelRow>): Int? {
    if (rows.isEmpty()) return null
    val center = (viewportStart + viewportEnd) / 2
    return rows.minBy { abs((it.offset + it.size / 2) - center) }.index
}

internal fun replaceSkipTimePart(input: String, part: SkipTimePart, digits: String): String {
    val (hours, minutes, seconds) = skipTimeParts(input)
    val parsed = digits.filter(Char::isDigit).take(2).toIntOrNull() ?: 0
    val next = when (part) {
        SkipTimePart.HOURS -> Triple(parsed.coerceIn(0, 99), minutes, seconds)
        SkipTimePart.MINUTES -> Triple(hours, parsed.coerceIn(0, 59), seconds)
        SkipTimePart.SECONDS -> Triple(hours, minutes, parsed.coerceIn(0, 59))
    }
    return formatSecondsToHms((next.first * 3600 + next.second * 60 + next.third).toDouble())
}

@Composable
internal fun SkipTimestampEditor(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (hours, minutes, seconds) = skipTimeParts(value)
    val hoursUnit = stringResource(Res.string.submit_intro_time_hours)
    val minutesUnit = stringResource(Res.string.submit_intro_time_minutes)
    val secondsUnit = stringResource(Res.string.submit_intro_time_seconds)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SkipTimeWheel(
                caption = "HH",
                unitName = hoursUnit,
                value = hours,
                count = 100,
                onValueChange = { next ->
                    onValueChange(replaceSkipTimePart(value, SkipTimePart.HOURS, next.toString()))
                },
                modifier = Modifier.weight(1f),
            )
            TimeColon()
            SkipTimeWheel(
                caption = "MM",
                unitName = minutesUnit,
                value = minutes,
                count = 60,
                onValueChange = { next ->
                    onValueChange(replaceSkipTimePart(value, SkipTimePart.MINUTES, next.toString()))
                },
                modifier = Modifier.weight(1f),
            )
            TimeColon()
            SkipTimeWheel(
                caption = "SS",
                unitName = secondsUnit,
                value = seconds,
                count = 60,
                onValueChange = { next ->
                    onValueChange(replaceSkipTimePart(value, SkipTimePart.SECONDS, next.toString()))
                },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkipTimeNudgeButton(
                text = "−1s",
                contentDescription = stringResource(Res.string.submit_intro_time_nudge_back_1),
                onClick = { onValueChange(stepSkipTimestamp(value, -1)) },
                modifier = Modifier.weight(1f),
            )
            SkipTimeNudgeButton(
                text = "+1s",
                contentDescription = stringResource(Res.string.submit_intro_time_nudge_forward_1),
                onClick = { onValueChange(stepSkipTimestamp(value, 1)) },
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onCapture),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Rounded.GpsFixed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(Res.string.submit_intro_capture_button),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private val WheelItemHeight = 36.dp

@Composable
private fun SkipTimeWheel(
    caption: String,
    unitName: String,
    value: Int,
    count: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = value.coerceIn(0, count - 1)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val flingBehavior = rememberSnapFlingBehavior(listState, SnapPosition.Center)
    val latestSelected = rememberUpdatedState(selected)
    var ready by remember { mutableStateOf(false) }
    var aligning by remember { mutableStateOf(false) }

    LaunchedEffect(selected, count) {
        aligning = true
        try {
            if (listState.currentCenteredIndex() != selected) {
                listState.centerWheelItem(selected)
            }
        } finally {
            aligning = false
            ready = true
        }
    }

    LaunchedEffect(listState, count) {
        snapshotFlow {
            Triple(ready, aligning || listState.isScrollInProgress, listState.currentCenteredIndex())
        }.collect { (isReady, busy, centered) ->
            if (!isReady || busy) return@collect
            val next = centered ?: return@collect
            if (next != latestSelected.value) onValueChange(next.coerceIn(0, count - 1))
        }
    }

    val centeredNow = listState.currentCenteredIndex() ?: selected
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$unitName ${selected.toString().padStart(2, '0')}"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(WheelItemHeight * 3)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(WheelItemHeight)
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            )
            LazyColumn(
                state = listState,
                flingBehavior = flingBehavior,
                contentPadding = PaddingValues(vertical = WheelItemHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(count) { index ->
                    val distance = abs(index - centeredNow)
                    Text(
                        text = index.toString().padStart(2, '0'),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(WheelItemHeight)
                            .wrapContentHeight(Alignment.CenterVertically),
                        textAlign = TextAlign.Center,
                        style = if (distance == 0) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.bodyLarge
                        },
                        fontWeight = if (distance == 0) FontWeight.Bold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = when (distance) {
                                0 -> 1f
                                1 -> 0.45f
                                else -> 0.18f
                            },
                        ),
                    )
                }
            }
        }
    }
}

private fun LazyListState.currentCenteredIndex(): Int? {
    val info = layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return null
    return centeredWheelIndex(
        viewportStart = info.viewportStartOffset,
        viewportEnd = info.viewportEndOffset,
        rows = info.visibleItemsInfo.map { WheelRow(it.index, it.offset, it.size) },
    )
}

private suspend fun LazyListState.centerWheelItem(index: Int) {
    scrollToItem(index)
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
    val delta = (item.offset + item.size / 2 - viewportCenter).toFloat()
    if (abs(delta) > 1f) {
        scroll { scrollBy(delta) }
    }
}

@Composable
private fun TimeColon() {
    Text(
        text = ":",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 46.dp),
    )
}

@Composable
private fun SkipTimeNudgeButton(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = contentDescription
    Box(
        modifier = modifier
            .height(36.dp.atLeastIosHitTarget())
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics(mergeDescendants = true) { this.contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}
