package com.nuvio.app.features.player.skip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.atLeastIosHitTarget
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.submit_intro_capture_button
import nuvio.composeapp.generated.resources.submit_intro_time_decrease
import nuvio.composeapp.generated.resources.submit_intro_time_hours
import nuvio.composeapp.generated.resources.submit_intro_time_increase
import nuvio.composeapp.generated.resources.submit_intro_time_minutes
import nuvio.composeapp.generated.resources.submit_intro_time_nudge_back_1
import nuvio.composeapp.generated.resources.submit_intro_time_nudge_back_10
import nuvio.composeapp.generated.resources.submit_intro_time_nudge_forward_1
import nuvio.composeapp.generated.resources.submit_intro_time_nudge_forward_10
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
    val hourFocus = remember { FocusRequester() }
    val minuteFocus = remember { FocusRequester() }
    val secondFocus = remember { FocusRequester() }
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
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SkipTimePartField(
                caption = "HH",
                unitName = hoursUnit,
                part = SkipTimePart.HOURS,
                partValue = hours,
                source = value,
                onValueChange = onValueChange,
                focusRequester = hourFocus,
                nextFocus = minuteFocus,
                modifier = Modifier.weight(1f),
            )
            TimeColon()
            SkipTimePartField(
                caption = "MM",
                unitName = minutesUnit,
                part = SkipTimePart.MINUTES,
                partValue = minutes,
                source = value,
                onValueChange = onValueChange,
                focusRequester = minuteFocus,
                nextFocus = secondFocus,
                modifier = Modifier.weight(1f),
            )
            TimeColon()
            SkipTimePartField(
                caption = "SS",
                unitName = secondsUnit,
                part = SkipTimePart.SECONDS,
                partValue = seconds,
                source = value,
                onValueChange = onValueChange,
                focusRequester = secondFocus,
                nextFocus = null,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkipTimeNudgeButton(
                text = "−10s",
                contentDescription = stringResource(Res.string.submit_intro_time_nudge_back_10),
                onClick = { onValueChange(stepSkipTimestamp(value, -10)) },
                modifier = Modifier.weight(1f),
            )
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
            SkipTimeNudgeButton(
                text = "+10s",
                contentDescription = stringResource(Res.string.submit_intro_time_nudge_forward_10),
                onClick = { onValueChange(stepSkipTimestamp(value, 10)) },
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

@Composable
private fun SkipTimePartField(
    caption: String,
    unitName: String,
    part: SkipTimePart,
    partValue: Int,
    source: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    nextFocus: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    var draft by remember(part) { mutableStateOf<String?>(null) }
    LaunchedEffect(partValue) {
        val current = draft ?: return@LaunchedEffect
        val typed = current.toIntOrNull()
        val draftMatches = current.isNotEmpty() && typed == partValue
        if (!draftMatches) draft = null
    }
    val shown = draft ?: partValue.toString().padStart(2, '0')
    val decrease = stringResource(Res.string.submit_intro_time_decrease, unitName)
    val increase = stringResource(Res.string.submit_intro_time_increase, unitName)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BasicTextField(
                value = shown,
                onValueChange = { raw ->
                    val digits = raw.filter(Char::isDigit).take(2)
                    draft = digits
                    onValueChange(replaceSkipTimePart(source, part, digits))
                    if (digits.length == 2) nextFocus?.requestFocus()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        draft = if (state.isFocused) "" else null
                    },
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = if (nextFocus != null) ImeAction.Next else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { nextFocus?.requestFocus() },
                    onDone = { },
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (shown.isEmpty()) {
                            Text(
                                text = "00",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SkipTimeNudgeButton(
                text = "−",
                contentDescription = decrease,
                onClick = {
                    draft = null
                    onValueChange(stepSkipTimePart(source, part, -1))
                },
                modifier = Modifier.weight(1f),
            )
            SkipTimeNudgeButton(
                text = "+",
                contentDescription = increase,
                onClick = {
                    draft = null
                    onValueChange(stepSkipTimePart(source, part, 1))
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TimeColon() {
    Text(
        text = ":",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 26.dp),
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
