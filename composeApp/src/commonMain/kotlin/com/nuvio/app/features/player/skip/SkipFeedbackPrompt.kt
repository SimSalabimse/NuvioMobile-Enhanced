package com.nuvio.app.features.player.skip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val PROMPT_VISIBLE_MS = 8_000L

private fun segmentLabel(type: String): String = when (SkipLabelType.of(type)) {
    "intro" -> "intro"
    "recap" -> "recap"
    "outro" -> "credits"
    "preview" -> "preview"
    else -> "segment"
}

private object SkipLabelType {
    fun of(type: String): String = when (type.lowercase()) {
        "intro", "op", "mixed-op", "opening" -> "intro"
        "recap" -> "recap"
        "outro", "ed", "mixed-ed", "credits", "ending", "movie-credits" -> "outro"
        "preview" -> "preview"
        else -> type.lowercase()
    }
}

/**
 * Small prompt about 4 s after a skip lands: "Skipped the intro right?" with Good / Wrong and ✕,
 * gone after 8 seconds. Wrong turns it into a nudge panel to move the start and end before saving,
 * with "Rewind 5s" to watch the landing spot again. Never pauses playback.
 */
@Composable
fun SkipFeedbackPrompt(
    interval: SkipInterval,
    nudging: Boolean,
    durationSec: Double,
    onGood: () -> Unit,
    onWrong: () -> Unit,
    onRewind: () -> Unit = {},
    onSave: (Double, Double) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!nudging) {
        LaunchedEffect(interval) {
            delay(PROMPT_VISIBLE_MS)
            onDismiss()
        }
    }
    val shape = RoundedCornerShape(14.dp)
    val panel = modifier
        .clip(shape)
        .background(Color.Black.copy(alpha = 0.72f))
    val label = segmentLabel(interval.type)
    if (!nudging) {
        Row(
            modifier = panel.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Skipped the $label right?", color = Color.White, fontSize = 13.sp)
            PromptChip("Good", highlighted = true, onClick = onGood)
            PromptChip("Wrong", highlighted = false, onClick = onWrong)
            PromptChip("✕", highlighted = false, onClick = onDismiss)
        }
        return
    }
    var start by remember(interval) { mutableStateOf(interval.startTime.coerceAtLeast(0.0)) }
    var end by remember(interval) { mutableStateOf(interval.endTime) }
    val maxSec = if (durationSec > 0) durationSec else Double.MAX_VALUE
    Column(
        modifier = panel.widthIn(max = 360.dp).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Fix the $label", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        NudgeRow("Start", start) { delta -> start = (start + delta).coerceIn(0.0, (end - 1.0).coerceAtLeast(0.0)) }
        NudgeRow("End", end) { delta -> end = (end + delta).coerceIn(start + 1.0, maxSec) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PromptChip("Rewind 5s", highlighted = false, onClick = onRewind)
            PromptChip("Cancel", highlighted = false, onClick = onDismiss)
            PromptChip("Save", highlighted = true, onClick = { if (end > start) onSave(start, end) })
        }
    }
}

@Composable
private fun NudgeRow(label: String, valueSec: Double, onNudge: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "$label ${formatSecondsToHms(valueSec)}",
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.widthIn(min = 96.dp),
        )
        listOf(-5.0, -1.0, 1.0, 5.0).forEach { delta ->
            PromptChip(if (delta > 0) "+${delta.toInt()}s" else "${delta.toInt()}s", highlighted = false) { onNudge(delta) }
        }
    }
}

@Composable
private fun PromptChip(text: String, highlighted: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (highlighted) Color.Black else Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (highlighted) Color.White else Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
