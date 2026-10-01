package com.nuvio.app.features.player.skip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.submit_intro_time_now
import nuvio.composeapp.generated.resources.submit_intro_time_nudge_back_1
import nuvio.composeapp.generated.resources.submit_intro_time_nudge_forward_1
import nuvio.composeapp.generated.resources.submit_intro_use_end
import nuvio.composeapp.generated.resources.submit_intro_use_end_hint
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
    onUseDuration: (() -> Unit)? = null,
) {
    val nowLabel = stringResource(Res.string.submit_intro_time_now)
    val backDescription = stringResource(Res.string.submit_intro_time_nudge_back_1)
    val forwardDescription = stringResource(Res.string.submit_intro_time_nudge_forward_1)
    val endLabel = stringResource(Res.string.submit_intro_use_end)
    val endDescription = stringResource(Res.string.submit_intro_use_end_hint)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.width(44.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            ),
            keyboardOptions = KeyboardOptions.Default,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            decorationBox = { inner ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    inner()
                }
            },
        )
        TimeChip(text = "−", contentDescription = backDescription, onClick = {
            onValueChange(stepSkipTimestamp(value, -1))
        })
        TimeChip(text = "+", contentDescription = forwardDescription, onClick = {
            onValueChange(stepSkipTimestamp(value, 1))
        })
        TimeChip(text = nowLabel, contentDescription = nowLabel, onClick = onCapture)
        if (onUseDuration != null) {
            TimeChip(text = endLabel, contentDescription = endDescription, onClick = onUseDuration)
        }
    }
}

@Composable
private fun TimeChip(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .widthIn(min = 30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
