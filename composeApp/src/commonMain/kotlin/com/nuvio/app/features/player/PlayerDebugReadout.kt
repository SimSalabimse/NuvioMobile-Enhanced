package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.storage.AppFileCache
import com.nuvio.app.features.player.skip.SkipInterval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Hidden diagnostic: the live position, duration and skip segments the player compares. */
internal object PlayerDebugReadout {
    private const val FILE = "debug/player_readout"
    private val state = MutableStateFlow(AppFileCache.read(FILE)?.trim() == "1")
    val enabled: StateFlow<Boolean> = state

    fun setEnabled(enabled: Boolean) {
        state.value = enabled
        AppFileCache.write(FILE, if (enabled) "1" else "0")
    }
}

internal fun playerDebugReadoutText(
    positionMs: Long,
    durationMs: Long,
    intervals: List<SkipInterval>,
    active: SkipInterval?,
): String = buildString {
    append("pos ").append(positionMs / 1000.0).append(" s · dur ").append(durationMs / 1000.0).append(" s")
    intervals.forEach { interval ->
        append('\n').append(if (interval == active) "▶ " else "  ")
        append(interval.type).append(' ').append(interval.startTime).append('–').append(interval.endTime)
        append(" s (").append(interval.provider).append(')')
    }
}

@Composable
internal fun PlayerScreenRuntime.PlayerDebugReadoutOverlay(modifier: Modifier) {
    val enabled by PlayerDebugReadout.enabled.collectAsStateWithLifecycle()
    if (!enabled) return
    Text(
        text = playerDebugReadoutText(playbackClock.positionMs, playbackSnapshot.durationMs, skipIntervals, activeSkipInterval),
        color = Color.White,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier.background(Color.Black.copy(alpha = 0.6f)).padding(6.dp),
    )
}
