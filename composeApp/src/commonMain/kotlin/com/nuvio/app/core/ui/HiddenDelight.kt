package com.nuvio.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import com.nuvio.app.isIos
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal const val HiddenDelightTapGoal = 7
internal const val HiddenDelightTapWindowMs = 2_000L
internal const val HiddenDelightMotionMs = 300

internal enum class HiddenDelightSignal {
    Counting,
    Play,
    Suppressed,
}

/** Motion length for a signal. Reduce Motion and incomplete taps stay at zero. */
internal fun HiddenDelightSignal.motionMs(): Int = when (this) {
    HiddenDelightSignal.Play -> HiddenDelightMotionMs
    HiddenDelightSignal.Counting,
    HiddenDelightSignal.Suppressed,
    -> 0
}

/** Single-tap wiggle length. Reduce Motion stays at zero. */
internal fun hiddenDelightWiggleMs(reduceMotion: Boolean): Int =
    if (reduceMotion) 0 else HiddenDelightMotionMs

/**
 * Counts quick taps from the first tap in the run. A tap after the window starts a new run.
 * A finished run, including one suppressed by Reduce Motion, starts over at zero.
 */
internal class HiddenDelightCounter(
    private val goal: Int = HiddenDelightTapGoal,
    private val windowMs: Long = HiddenDelightTapWindowMs,
) {
    private var count = 0
    private var windowStartMs = 0L

    fun onTap(nowMs: Long, reduceMotion: Boolean): HiddenDelightSignal {
        val opened = count == 0 || nowMs - windowStartMs > windowMs
        if (opened) {
            count = 1
            windowStartMs = nowMs
        } else {
            count += 1
        }
        if (count < goal) return HiddenDelightSignal.Counting
        count = 0
        return if (reduceMotion) HiddenDelightSignal.Suppressed else HiddenDelightSignal.Play
    }
}

@Composable
internal fun IosAboutMarkDelight(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!isIos) {
        Box(modifier) { content() }
        return
    }
    val reduceMotion = systemReduceMotionEnabled()
    val haptic = LocalHapticFeedback.current
    val sparkle = MaterialTheme.colorScheme.primary
    val counter = remember { HiddenDelightCounter() }
    val gate = remember { HiddenDelightGate() }
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf(false) }
    val onTap = rememberUpdatedState<() -> Unit>(
        newValue = {
            if (!gate.busy) {
                val duration = counter.onTap(
                    nowMs = Clock.System.now().toEpochMilliseconds(),
                    reduceMotion = reduceMotion,
                ).motionMs()
                if (duration != 0) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    gate.play(scope, progress, duration) { playing = it }
                }
            }
        },
    )
    Box(
        modifier = modifier
            .then(if (playing) Modifier.aboutMoment(progress, sparkle) else Modifier)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (waitForUpOrCancellation() != null) onTap.value.invoke()
                }
            },
    ) {
        content()
    }
}

@Composable
internal fun IosQuietWiggle(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!isIos) return
    val reduceMotion = systemReduceMotionEnabled()
    val gate = remember { HiddenDelightGate() }
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf(false) }
    val onTap = rememberUpdatedState<() -> Unit>(
        newValue = {
            if (!gate.busy) {
                val duration = hiddenDelightWiggleMs(reduceMotion)
                if (duration != 0) {
                    gate.play(scope, progress, duration) { playing = it }
                }
            }
        },
    )
    Box(
        modifier = modifier
            .then(if (playing) Modifier.wiggleMoment(progress) else Modifier)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (waitForUpOrCancellation() != null) onTap.value.invoke()
                }
            },
    ) {
        content()
    }
}

private class HiddenDelightGate {
    var busy: Boolean = false

    fun play(
        scope: CoroutineScope,
        progress: Animatable<Float, AnimationVector1D>,
        durationMs: Int,
        onPlaying: (Boolean) -> Unit,
    ) {
        if (busy) return
        busy = true
        onPlaying(true)
        scope.launch {
            try {
                withFrameNanos { }
                progress.snapTo(0f)
                progress.animateTo(1f, tween(durationMillis = durationMs))
                progress.snapTo(0f)
                onPlaying(false)
                busy = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onPlaying(false)
                busy = false
                throw error
            }
        }
    }
}

private fun Modifier.aboutMoment(
    progress: Animatable<Float, AnimationVector1D>,
    sparkle: Color,
): Modifier = graphicsLayer {
    rotationZ = sin(progress.value.coerceIn(0f, 1f) * PI.toFloat()) * 14f
}.drawWithContent {
    drawContent()
    drawHiddenSparkle(progress.value, sparkle)
}

private fun Modifier.wiggleMoment(
    progress: Animatable<Float, AnimationVector1D>,
): Modifier = graphicsLayer {
    rotationZ = sin(progress.value.coerceIn(0f, 1f) * (2f * PI.toFloat()) * 2f) * 6f
}

private val sparkleXY = floatArrayOf(
    -0.55f, -0.4f,
    0.62f, -0.22f,
    0.4f, 0.58f,
    -0.48f, 0.5f,
    0.02f, -0.72f,
    -0.12f, 0.74f,
)

private fun DrawScope.drawHiddenSparkle(
    progress: Float,
    color: Color,
) {
    val p = progress.coerceIn(0f, 1f)
    val alpha = (1f - p).coerceIn(0f, 1f)
    if (alpha <= 0f) return
    val reach = 0.2f + (0.9f * p)
    val radius = size.minDimension * 0.045f
    var index = 0
    while (index < sparkleXY.size) {
        drawCircle(
            color = color.copy(alpha = alpha * 0.85f),
            radius = radius,
            center = Offset(
                x = center.x + sparkleXY[index] * size.width * reach,
                y = center.y + sparkleXY[index + 1] * size.height * reach,
            ),
        )
        index += 2
    }
}
