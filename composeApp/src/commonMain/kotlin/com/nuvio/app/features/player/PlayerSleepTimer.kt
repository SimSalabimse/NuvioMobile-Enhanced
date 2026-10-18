package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Sleep timer choices: off, after N minutes, or at the end of the current episode. */
internal enum class SleepTimerChoice(val label: String, val minutes: Int?) {
    Off("Off", null),
    Min15("15 min", 15),
    Min30("30 min", 30),
    Min60("60 min", 60),
    EndOfEpisode("End of ep", null),
}

/** The sleep timer of the one player on screen; the player effect fades and pauses (see [BindSleepTimerEffect]). */
internal object PlayerSleepTimer {
    private val _choice = MutableStateFlow(SleepTimerChoice.Off)
    val choice: StateFlow<SleepTimerChoice> = _choice.asStateFlow()
    private var startedAt: TimeMark? = null

    fun set(choice: SleepTimerChoice) {
        _choice.value = choice
        startedAt = TimeSource.Monotonic.markNow()
    }

    /** Time left in ms for a minutes choice, else null. */
    fun minutesLeftMs(): Long? {
        val minutes = _choice.value.minutes ?: return null
        val started = startedAt ?: return null
        return (minutes.minutes - started.elapsedNow()).inWholeMilliseconds
    }
}

/** The last stretch before the pause, over which the volume fades to silence. */
private const val SLEEP_FADE_MS = 20_000L

/** Volume factor at [leftMs] before the pause: 1 until the fade starts, then down to 0. */
internal fun sleepFadeFactor(leftMs: Long): Float =
    (leftMs.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f)

/** Runs the sleep timer: fades the player volume over the last 20 s, then pauses and resets it. */
@Composable
internal fun PlayerScreenRuntime.BindSleepTimerEffect() {
    val choice by PlayerSleepTimer.choice.collectAsState()
    // Closing the player ends the timer; it never carries over to the next video session.
    DisposableEffect(Unit) { onDispose { PlayerSleepTimer.set(SleepTimerChoice.Off) } }
    LaunchedEffect(choice) {
        if (choice == SleepTimerChoice.Off) return@LaunchedEffect
        var startVolume: Float? = null
        try {
            while (true) {
                delay(500)
                val left = PlayerSleepTimer.minutesLeftMs()
                    ?: (playbackSnapshot.durationMs - playbackClock.positionMs).takeIf { playbackSnapshot.durationMs > 0L }
                    ?: continue
                val controller = playerController ?: continue
                if (left > SLEEP_FADE_MS && !playbackSnapshot.isEnded) continue
                val base = startVolume ?: controller.currentPlayerVolume().fraction.also { startVolume = it }
                if (left > 0L && !playbackSnapshot.isEnded) {
                    controller.setPlayerVolume(base * sleepFadeFactor(left))
                    continue
                }
                controller.pause()
                PlayerSleepTimer.set(SleepTimerChoice.Off)
                return@LaunchedEffect
            }
        } finally {
            // Also when the timer is changed or turned off mid-fade.
            startVolume?.let { playerController?.setPlayerVolume(it) }
        }
    }
}
