package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.nuvio.app.core.ui.nuvio
import kotlin.math.abs

internal val PlayerPlaybackSpeedOptions: List<Float> =
    listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

@Composable
internal fun PlayerSpeedPopup(
    visible: Boolean,
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val positionProvider = remember(gapPx) { AboveAnchorPositionProvider(gapPx) }
    val accent = MaterialTheme.nuvio.colors.accent

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(76.dp)
                .heightIn(max = 300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlayerSleepTimerOptions(accent)
            PlayerPlaybackSpeedOptions.asReversed().forEach { speed ->
                val selected = abs(currentSpeed - speed) < 0.01f
                Text(
                    text = formatPlaybackSpeedLabel(speed),
                    color = if (selected) accent else Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable { onSpeedSelected(speed) }
                        .padding(vertical = 6.dp)
                        .width(64.dp),
                )
            }
        }
    }
}

/** Sleep timer choices on top of the speed list (one popup for both player layouts). */
@Composable
private fun PlayerSleepTimerOptions(accent: Color) {
    val current by PlayerSleepTimer.choice.collectAsState()
    Text(
        text = "Sleep",
        color = Color.White.copy(alpha = 0.6f),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(vertical = 2.dp),
    )
    SleepTimerChoice.entries.forEach { choice ->
        val selected = choice == current
        Text(
            text = choice.label,
            color = if (selected) accent else Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                .clickable { PlayerSleepTimer.set(choice) }
                .padding(vertical = 4.dp)
                .width(64.dp),
        )
    }
    Text(
        text = "Speed",
        color = Color.White.copy(alpha = 0.6f),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

private class AboveAnchorPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxX)
        val above = anchorBounds.top - gapPx - popupContentSize.height
        val y = if (above >= 0) above else (anchorBounds.bottom + gapPx)
        return IntOffset(x, y)
    }
}
