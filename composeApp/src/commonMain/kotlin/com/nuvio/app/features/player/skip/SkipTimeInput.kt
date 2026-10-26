package com.nuvio.app.features.player.skip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.PlayerGlassFallback
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.submit_intro_time_hours
import nuvio.composeapp.generated.resources.submit_intro_time_minutes
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

internal enum class SubmitTimestampProblem {
    EndNotAfterStart,
    StartPastDuration,
    EndPastDuration,
}

internal enum class SubmitTimestampWarning {
    LongerThanUsual,
    ShorterThanUsual,
}

internal enum class SubmitTimeField {
    START,
    END,
}

/** Soft length warning only. It never blocks submit. */
internal const val SubmitTimestampSoftMaxSeconds = 5 * 60.0
internal const val SubmitTimestampSoftMinSeconds = 3.0

/**
 * Hard checks for a typed range. A missing time counts as "end must be after start"
 * so an empty clock cannot be sent. A time equal to the duration is allowed.
 */
internal fun submitTimestampProblems(
    startSec: Double?,
    endSec: Double?,
    durationSec: Double?,
): List<SubmitTimestampProblem> {
    val problems = mutableListOf<SubmitTimestampProblem>()
    if (startSec == null || endSec == null || endSec <= startSec) {
        problems += SubmitTimestampProblem.EndNotAfterStart
    }
    if (durationSec != null && durationSec > 0.0) {
        if (startSec != null && startSec > durationSec) problems += SubmitTimestampProblem.StartPastDuration
        if (endSec != null && endSec > durationSec) problems += SubmitTimestampProblem.EndPastDuration
    }
    return problems
}

/** Intro and recap only. No warning until the range itself is valid. */
internal fun submitTimestampWarning(
    segmentType: String,
    startSec: Double?,
    endSec: Double?,
): SubmitTimestampWarning? {
    val type = segmentType.trim().lowercase()
    if (type != "intro" && type != "recap") return null
    if (startSec == null || endSec == null || endSec <= startSec) return null
    val length = endSec - startSec
    return when {
        length > SubmitTimestampSoftMaxSeconds -> SubmitTimestampWarning.LongerThanUsual
        length < SubmitTimestampSoftMinSeconds -> SubmitTimestampWarning.ShorterThanUsual
        else -> null
    }
}

/**
 * Steps a clock by whole seconds and clamps to `0..duration` when a duration is known.
 * Without a duration the clock still stops at [MAX_SKIP_TIMESTAMP_SECONDS].
 */
internal fun nudgeSkipTimestamp(input: String, deltaSeconds: Int, durationSec: Double?): String {
    val durationCap = durationSec?.takeIf { it > 0.0 }?.toInt()
    val max = if (durationCap != null) {
        minOf(durationCap, MAX_SKIP_TIMESTAMP_SECONDS)
    } else {
        MAX_SKIP_TIMESTAMP_SECONDS
    }
    val total = ((parseTimeToSeconds(input) ?: 0.0).toInt() + deltaSeconds).coerceIn(0, max)
    return formatSecondsToHms(total.toDouble())
}

/** Start and end as fractions of the duration, for the live range bar. */
internal fun submitRangeSpan(
    startSec: Double?,
    endSec: Double?,
    durationSec: Double?,
): Pair<Float, Float>? {
    if (startSec == null || endSec == null || durationSec == null || durationSec <= 0.0) return null
    if (endSec <= startSec) return null
    val start = (startSec / durationSec).toFloat().coerceIn(0f, 1f)
    val end = (endSec / durationSec).toFloat().coerceIn(0f, 1f)
    if (end <= start) return null
    return start to end
}

internal fun submitPanelSegmentTypes(isMovie: Boolean): List<String> =
    if (isMovie) listOf("intro", "outro") else listOf("intro", "recap", "outro", "preview")

/** Outro is the credits chip on a movie and the only type that offers "End of video". */
internal fun submitShowsEndOfVideo(segmentType: String): Boolean =
    segmentType.equals("outro", ignoreCase = true)

internal fun releaseYear(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return Regex("""\d{4}""").find(releaseInfo)?.value
}

/** Series: "S1 E22 · title". Movies: the release year, when one is known. */
internal fun submitPanelSubtitle(
    isMovie: Boolean,
    season: Int,
    episode: Int,
    episodeTitle: String?,
    releaseInfo: String?,
): String? {
    if (isMovie) return releaseYear(releaseInfo)
    val code = buildString {
        if (season > 0) append("S").append(season)
        if (episode > 0) {
            if (isNotEmpty()) append(' ')
            append("E").append(episode)
        }
    }
    val title = episodeTitle?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        code.isNotEmpty() && title != null -> "$code · $title"
        code.isNotEmpty() -> code
        else -> title
    }
}

@Composable
internal fun SubmitTimeRow(
    label: String,
    value: String,
    selected: Boolean,
    nowLabel: String,
    onSelect: () -> Unit,
    onEditDigits: () -> Unit,
    onNow: () -> Unit,
    modifier: Modifier = Modifier,
    endOfVideoLabel: String? = null,
    onEndOfVideo: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onEditDigits)
                .padding(vertical = 4.dp),
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Start,
        )
        if (endOfVideoLabel != null && onEndOfVideo != null) {
            Text(
                text = endOfVideoLabel,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onEndOfVideo)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PlayerGlassFallback)
                .clickable(onClick = onNow)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = nowLabel,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun SubmitNudgeRow(
    onNudge: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backFiveDescription: String,
    backOneDescription: String,
    forwardOneDescription: String,
    forwardFiveDescription: String,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NudgeKey("−5", backFiveDescription, { onNudge(-5) }, Modifier.weight(1f))
        NudgeKey("−1", backOneDescription, { onNudge(-1) }, Modifier.weight(1f))
        NudgeKey("+1", forwardOneDescription, { onNudge(1) }, Modifier.weight(1f))
        NudgeKey("+5", forwardFiveDescription, { onNudge(5) }, Modifier.weight(1f))
    }
}

@Composable
private fun NudgeKey(
    label: String,
    description: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(onPress)
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PlayerGlassFallback)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        coroutineScope {
                            val job = launch {
                                current()
                                delay(400)
                                while (isActive) {
                                    current()
                                    delay(80)
                                }
                            }
                            tryAwaitRelease()
                            job.cancel()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun SubmitRangeBar(
    span: Pair<Float, Float>?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val track = Color.White.copy(alpha = 0.12f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = span != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
        ) {
            if (span == null) {
                Box(Modifier.fillMaxHeight().weight(1f).background(track))
            } else {
                val lead = span.first.coerceIn(0f, 1f)
                val mid = (span.second - span.first).coerceIn(0f, 1f - lead)
                val tail = (1f - lead - mid).coerceAtLeast(0f)
                if (lead > 0f) Box(Modifier.fillMaxHeight().weight(lead).background(track))
                if (mid > 0f) Box(Modifier.fillMaxHeight().weight(mid).background(tokens.colors.accent))
                if (tail > 0f) Box(Modifier.fillMaxHeight().weight(tail).background(track))
            }
        }
    }
}

@Composable
internal fun SkipTimeWheels(
    value: String,
    onPartChange: (SkipTimePart, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (hours, minutes, seconds) = skipTimeParts(value)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipTimeWheel(
            caption = stringResource(Res.string.submit_intro_time_hours),
            value = hours,
            count = 100,
            onValueChange = { onPartChange(SkipTimePart.HOURS, it) },
            modifier = Modifier.weight(1f),
        )
        WheelColon()
        SkipTimeWheel(
            caption = stringResource(Res.string.submit_intro_time_minutes),
            value = minutes,
            count = 60,
            onValueChange = { onPartChange(SkipTimePart.MINUTES, it) },
            modifier = Modifier.weight(1f),
        )
        WheelColon()
        SkipTimeWheel(
            caption = stringResource(Res.string.submit_intro_time_seconds),
            value = seconds,
            count = 60,
            onValueChange = { onPartChange(SkipTimePart.SECONDS, it) },
            modifier = Modifier.weight(1f),
        )
    }
}

private val WheelItemHeight = 36.dp

@Composable
private fun SkipTimeWheel(
    caption: String,
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
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.72f),
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
                    .background(Color.White.copy(alpha = 0.08f)),
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
                        color = Color.White.copy(
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
private fun WheelColon() {
    Text(
        text = ":",
        modifier = Modifier.padding(top = 18.dp),
        style = MaterialTheme.typography.titleMedium,
        color = Color.White.copy(alpha = 0.72f),
        fontWeight = FontWeight.Bold,
    )
}
