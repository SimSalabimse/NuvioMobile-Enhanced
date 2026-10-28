package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.player_tools_copy_link
import nuvio.composeapp.generated.resources.player_tools_credits
import nuvio.composeapp.generated.resources.player_tools_delay
import nuvio.composeapp.generated.resources.player_tools_end_after_start
import nuvio.composeapp.generated.resources.player_tools_fix_time
import nuvio.composeapp.generated.resources.player_tools_heard_it
import nuvio.composeapp.generated.resources.player_tools_intro
import nuvio.composeapp.generated.resources.player_tools_limit
import nuvio.composeapp.generated.resources.player_tools_mark_end
import nuvio.composeapp.generated.resources.player_tools_mark_start
import nuvio.composeapp.generated.resources.player_tools_movie
import nuvio.composeapp.generated.resources.player_tools_none
import nuvio.composeapp.generated.resources.player_tools_past_video
import nuvio.composeapp.generated.resources.player_tools_mark_hint
import nuvio.composeapp.generated.resources.player_tools_play_range
import nuvio.composeapp.generated.resources.player_tools_preview
import nuvio.composeapp.generated.resources.player_tools_range
import nuvio.composeapp.generated.resources.player_tools_recap
import nuvio.composeapp.generated.resources.player_tools_reset
import nuvio.composeapp.generated.resources.player_tools_runs_to_end
import nuvio.composeapp.generated.resources.player_tools_saw_it
import nuvio.composeapp.generated.resources.player_tools_sent
import nuvio.composeapp.generated.resources.player_tools_starts_at_zero
import nuvio.composeapp.generated.resources.player_tools_submit
import nuvio.composeapp.generated.resources.player_tools_subs_help
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

private val StripShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
private val MarkError = Color(0xFFFF5C5C)
private val MarkOk = Color(0xFF3DDC84)
private val MarkSent = Color(0xFF22A85A)
private val MarkDisabled = Color(0xFF3A3424)

@Composable
internal fun SubtitleSyncStrip(
    delayMs: Int,
    heard: Boolean,
    saw: Boolean,
    safeInset: Dp,
    onNudge: (Int) -> Unit,
    onHeard: () -> Unit,
    onSaw: () -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(90.dp)
            .shadow(12.dp, StripShape)
            .clip(StripShape)
            .background(PlayerToolSurface.copy(alpha = 0.88f))
            .border(1.dp, PlayerToolEdge, StripShape)
            .consumeToolTaps()
            .padding(start = safeInset, end = safeInset, top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(Res.string.player_tools_subs_help),
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerToolTextButton("−0.1", onClick = { onNudge(-SUBTITLE_DELAY_STEP_MS) })
            Text(
                text = "${stringResource(Res.string.player_tools_delay)}  ${formatSubtitleDelayLabel(delayMs)}",
                color = MaterialTheme.nuvio.colors.accent,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            PlayerToolTextButton("+0.1", onClick = { onNudge(SUBTITLE_DELAY_STEP_MS) })
            PlayerToolTextButton(
                label = stringResource(Res.string.player_tools_heard_it),
                onClick = onHeard,
                selected = heard,
            )
            PlayerToolTextButton(
                label = stringResource(Res.string.player_tools_saw_it),
                onClick = onSaw,
                selected = saw,
            )
            PlayerToolTextButton(stringResource(Res.string.player_tools_reset), onClick = onReset)
            PlayerToolTextButton(
                label = stringResource(Res.string.action_done),
                onClick = onDone,
                selected = true,
            )
        }
    }
}

@Composable
internal fun SegmentMarkingLayer(
    title: String,
    segmentType: String,
    isMovie: Boolean,
    episodeCode: String?,
    year: String?,
    startSec: Double?,
    endSec: Double?,
    durationSec: Double,
    playheadSec: Double,
    absent: Boolean,
    absentAllowed: Boolean,
    sent: Boolean,
    submitting: Boolean,
    keyProblem: Boolean,
    absentLine: String,
    addKeyLabel: String,
    blockLine: String?,
    errorLine: String?,
    safeInset: Dp,
    onBack: () -> Unit,
    onSegment: (String) -> Unit,
    onAbsent: () -> Unit,
    onMarkStart: () -> Unit,
    onMarkEnd: () -> Unit,
    onNudgeStart: (Double) -> Unit,
    onNudgeEnd: (Double) -> Unit,
    onStartAtZero: () -> Unit,
    onRunsToEnd: () -> Unit,
    onSeek: (Double) -> Unit,
    onDragStart: (Double) -> Unit,
    onDragEnd: (Double) -> Unit,
    onPreview: () -> Unit,
    onCopyLink: () -> Unit,
    showCopyLink: Boolean,
    onAddKey: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val problems = if (startSec != null && endSec != null) {
        markProblems(segmentType, startSec, endSec, durationSec.takeIf { it > 0.0 })
    } else {
        emptyList()
    }
    val bothSet = startSec != null && endSec != null
    val timeOk = bothSet && problems.isEmpty()
    val canSend = markSubmitEnabled(
        sent = sent,
        submitting = submitting,
        keyProblem = keyProblem,
        blocked = !blockLine.isNullOrBlank(),
        absent = absent,
        absentAllowed = absentAllowed,
        timeOk = timeOk,
    )
    val editable = !submitting
    val segmentLabel = markSegmentLabel(segmentType)
    val where = if (isMovie) {
        listOfNotNull(segmentLabel, stringResource(Res.string.player_tools_movie), year).joinToString(" · ")
    } else {
        listOfNotNull(segmentLabel, episodeCode).joinToString(" · ")
    }
    Box(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .consumeToolTaps()
                .padding(start = safeInset, end = safeInset, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerToolIconButton(
                icon = Icons.Rounded.Close,
                description = stringResource(Res.string.action_close),
                onClick = onBack,
                enabled = editable,
            )
            Text(
                text = title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        MarkingSeekBar(
            playheadSec = playheadSec,
            startSec = startSec,
            endSec = endSec,
            durationSec = durationSec,
            safeInset = safeInset,
            onSeek = onSeek,
            onStart = onDragStart,
            onEnd = onDragEnd,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 176.dp),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .fillMaxWidth()
                .height(164.dp)
                .shadow(16.dp, StripShape)
                .clip(StripShape)
                .background(PlayerToolSurface.copy(alpha = 0.88f))
                .border(1.dp, PlayerToolEdge, StripShape)
                .consumeToolTaps()
                .padding(start = safeInset, end = safeInset, top = 8.dp, bottom = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MarkChipScroller(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    markSegmentTypes(isMovie).forEach { type ->
                        PlayerToolChip(
                            label = markSegmentLabel(type),
                            selected = !absent && markSegmentId(segmentType) == type,
                            onClick = { onSegment(type) },
                            enabled = editable,
                            modifier = Modifier.wrapContentWidth(unbounded = true),
                        )
                    }
                    PlayerToolChip(
                        label = stringResource(Res.string.player_tools_none),
                        selected = absent,
                        onClick = onAbsent,
                        enabled = editable,
                        modifier = Modifier.wrapContentWidth(unbounded = true),
                    )
                }
                MarkStatus(
                    absent = absent,
                    bothSet = bothSet,
                    problems = problems,
                    startSec = startSec,
                    endSec = endSec,
                    segmentType = segmentType,
                    segmentLabel = segmentLabel,
                    keyProblem = keyProblem,
                    absentLine = absentLine,
                    addKeyLabel = addKeyLabel,
                    blockLine = blockLine,
                    errorLine = errorLine,
                    onAddKey = onAddKey,
                )
                if (showCopyLink) {
                    PlayerToolTextButton(stringResource(Res.string.player_tools_copy_link), onClick = onCopyLink)
                }
                val playRange = stringResource(Res.string.player_tools_play_range)
                PlayerToolTextButton(
                    label = playRange,
                    onClick = onPreview,
                    enabled = editable && timeOk && !absent,
                    contentDescription = playRange,
                )
                PlayerToolIconButton(
                    icon = Icons.Rounded.Close,
                    description = stringResource(Res.string.action_close),
                    onClick = onBack,
                    enabled = editable,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MarkClockRow(
                        title = stringResource(Res.string.player_tools_mark_start),
                        clock = startSec?.let(::formatMarkClock) ?: "–",
                        onMark = onMarkStart,
                        onNudge = onNudgeStart,
                        enabled = editable,
                        shortcut = if (!absent && markSegmentOffersStartAtZero(segmentType)) {
                            stringResource(Res.string.player_tools_starts_at_zero)
                        } else {
                            null
                        },
                        onShortcut = onStartAtZero,
                    )
                    MarkClockRow(
                        title = stringResource(Res.string.player_tools_mark_end),
                        clock = endSec?.let(::formatMarkClock) ?: "–",
                        onMark = onMarkEnd,
                        onNudge = onNudgeEnd,
                        enabled = editable,
                        shortcut = if (!absent && markSegmentOffersRunsToEnd(segmentType)) {
                            stringResource(Res.string.player_tools_runs_to_end)
                        } else {
                            null
                        },
                        onShortcut = onRunsToEnd,
                        runsToEnd = markSegmentOffersRunsToEnd(segmentType) && endSec != null &&
                            durationSec > 0.0 && abs(endSec - durationSec) < 0.2,
                    )
                }
                SubmitBlock(
                    enabled = canSend,
                    sent = sent,
                    where = where,
                    onClick = onSubmit,
                )
            }
        }
    }
}

@Composable
private fun markSegmentLabel(type: String): String = when (markSegmentId(type)) {
    "intro" -> stringResource(Res.string.player_tools_intro)
    "recap" -> stringResource(Res.string.player_tools_recap)
    "preview" -> stringResource(Res.string.player_tools_preview)
    else -> stringResource(Res.string.player_tools_credits)
}

@Composable
private fun MarkChipScroller(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    val fade = PlayerToolSurface.copy(alpha = 0.96f)
    Box(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
        if (scroll.canScrollBackward) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(18.dp)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(fade, Color.Transparent))),
            )
        }
        if (scroll.canScrollForward) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(18.dp)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, fade))),
            )
        }
    }
}

@Composable
private fun MarkStatus(
    absent: Boolean,
    bothSet: Boolean,
    problems: List<MarkProblem>,
    startSec: Double?,
    endSec: Double?,
    segmentType: String,
    segmentLabel: String,
    keyProblem: Boolean,
    absentLine: String,
    addKeyLabel: String,
    blockLine: String?,
    errorLine: String?,
    onAddKey: () -> Unit,
) {
    when {
        keyProblem -> PlayerToolTextButton(addKeyLabel, onClick = onAddKey)
        !errorLine.isNullOrBlank() -> MarkStatusLine(errorLine, MarkError)
        !blockLine.isNullOrBlank() -> MarkStatusLine(blockLine, MarkError)
        absent -> MarkStatusLine(absentLine, Color.White.copy(alpha = 0.7f))
        bothSet && problems.isNotEmpty() -> {
            val length = markLengthSec(startSec, endSec)
            val detail = markProblemText(problems.first(), segmentType, segmentLabel)
            MarkStatusLine(
                listOfNotNull(length?.let(::markLengthLabel), detail).joinToString("  "),
                MarkError,
            )
        }
        bothSet -> {
            val limits = markSegmentLimits(segmentType)
            val length = markLengthSec(startSec, endSec)
            MarkStatusLine(
                buildString {
                    length?.let { append(markLengthLabel(it)).append("  ") }
                    append(
                        stringResource(
                            Res.string.player_tools_range,
                            segmentLabel,
                            limits.minSec.toInt(),
                            limits.maxSec.toInt(),
                        ),
                    )
                },
                MarkOk,
            )
        }
        else -> MarkStatusLine(
            stringResource(Res.string.player_tools_mark_hint),
            Color.White.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun MarkStatusLine(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 220.dp),
    )
}

@Composable
private fun markProblemText(problem: MarkProblem, segmentType: String, segmentLabel: String): String {
    val limits = markSegmentLimits(segmentType)
    return when (problem) {
        MarkProblem.EndNotAfterStart -> stringResource(Res.string.player_tools_end_after_start)
        MarkProblem.PastDuration -> stringResource(Res.string.player_tools_past_video)
        MarkProblem.TooShort, MarkProblem.TooLong -> stringResource(
            Res.string.player_tools_limit,
            segmentLabel,
            limits.minSec.toInt(),
            limits.maxSec.toInt(),
        )
    }
}

@Composable
private fun MarkClockRow(
    title: String,
    clock: String,
    onMark: () -> Unit,
    onNudge: (Double) -> Unit,
    shortcut: String?,
    onShortcut: () -> Unit,
    runsToEnd: Boolean = false,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PlayerToolTextButton(
            label = "$title  $clock",
            onClick = onMark,
            selected = runsToEnd,
            enabled = enabled,
        )
        listOf(-1.0 to "−1", -0.1 to "−.1", 0.1 to "+.1", 1.0 to "+1").forEach { (delta, label) ->
            PlayerToolTextButton(label, onClick = { onNudge(delta) }, enabled = enabled)
        }
        if (shortcut != null) {
            PlayerToolTextButton(shortcut, onClick = onShortcut, selected = runsToEnd, enabled = enabled)
        }
    }
}

@Composable
private fun SubmitBlock(
    enabled: Boolean,
    sent: Boolean,
    where: String,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(16.dp)
    val background = when {
        sent -> MarkSent
        enabled -> tokens.colors.accent
        else -> MarkDisabled
    }
    val foreground = when {
        sent -> Color.White
        enabled -> tokens.colors.onAccent
        else -> Color.White.copy(alpha = 0.45f)
    }
    Column(
        modifier = Modifier
            .width(132.dp)
            .height(102.dp)
            .clip(shape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (sent) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = foreground, modifier = Modifier.size(28.dp))
            Text(stringResource(Res.string.player_tools_sent), color = foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Text(
                text = stringResource(Res.string.player_tools_submit),
                color = foreground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (enabled) where else stringResource(Res.string.player_tools_fix_time),
                color = foreground.copy(alpha = if (enabled) 0.8f else 0.7f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

private enum class MarkDrag { Playhead, Start, End }

@Composable
private fun MarkingSeekBar(
    playheadSec: Double,
    startSec: Double?,
    endSec: Double?,
    durationSec: Double,
    safeInset: Dp,
    onSeek: (Double) -> Unit,
    onStart: (Double) -> Unit,
    onEnd: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seekAllowed = markingSeekAllowed(durationSec)
    val window = markingZoomWindow(playheadSec, startSec, endSec, durationSec)
    val windowState = rememberUpdatedState(window)
    val playheadState = rememberUpdatedState(playheadSec)
    val startState = rememberUpdatedState(startSec)
    val endState = rememberUpdatedState(endSec)
    val onSeekState = rememberUpdatedState(onSeek)
    val onStartState = rememberUpdatedState(onStart)
    val onEndState = rememberUpdatedState(onEnd)
    val tokens = MaterialTheme.nuvio
    val labelColor = Color.White.copy(alpha = if (seekAllowed) 0.8f else 0.35f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .consumeToolTaps()
            .padding(start = safeInset, end = safeInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatMarkClock(window.first), color = labelColor, fontSize = 12.sp)
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .padding(horizontal = 8.dp)
                .then(
                    if (!seekAllowed) {
                        Modifier
                    } else {
                        Modifier.pointerInput(durationSec) {
                            val width = { size.width.toFloat().coerceAtLeast(1f) }
                            awaitEachGesture {
                                val currentWindow = windowState.value
                                val playhead = playheadState.value
                                val start = startState.value
                                val end = endState.value
                                fun timeAt(x: Float): Double {
                                    val fraction = (x / width()).coerceIn(0f, 1f)
                                    return currentWindow.first + (currentWindow.second - currentWindow.first) * fraction
                                }
                                fun xOf(sec: Double): Float =
                                    zoomFraction(sec, currentWindow.first, currentWindow.second) * width()
                                val down = awaitFirstDown()
                                val slop = 28.dp.toPx()
                                val candidates = buildList {
                                    add(MarkDrag.Playhead to xOf(playhead))
                                    start?.let { add(MarkDrag.Start to xOf(it)) }
                                    end?.let { add(MarkDrag.End to xOf(it)) }
                                }
                                val nearest = candidates.minBy { abs(it.second - down.position.x) }
                                val drag = if (abs(nearest.second - down.position.x) <= slop && nearest.first != MarkDrag.Playhead) {
                                    nearest.first
                                } else {
                                    MarkDrag.Playhead
                                }
                                fun apply(x: Float) {
                                    if (!markingSeekAllowed(durationSec) && drag == MarkDrag.Playhead) return
                                    val sec = timeAt(x)
                                    when (drag) {
                                        MarkDrag.Playhead -> onSeekState.value(sec)
                                        MarkDrag.Start -> onStartState.value(sec)
                                        MarkDrag.End -> onEndState.value(sec)
                                    }
                                }
                                apply(down.position.x)
                                drag(down.id) { change ->
                                    if (change.positionChange() != Offset.Zero) {
                                        change.consume()
                                    }
                                    apply(change.position.x)
                                }
                            }
                        }
                    },
                ),
        ) {
            val trackWidth = maxWidth
            val startFraction = startSec?.let { zoomFraction(it, window.first, window.second) }
            val endFraction = endSec?.let { zoomFraction(it, window.first, window.second) }
            val playFraction = zoomFraction(playheadSec, window.first, window.second)
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = if (seekAllowed) 0.28f else 0.12f)),
            )
            if (startFraction != null && endFraction != null && endFraction > startFraction) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = trackWidth * startFraction)
                        .width(trackWidth * (endFraction - startFraction))
                        .height(4.dp)
                        .background(tokens.colors.accent),
                )
            }
            startSec?.let {
                MarkHandle(trackWidth * zoomFraction(it, window.first, window.second), tokens.colors.accent)
            }
            endSec?.let {
                MarkHandle(trackWidth * zoomFraction(it, window.first, window.second), tokens.colors.accent)
            }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = trackWidth * playFraction - 8.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (seekAllowed) 1f else 0.35f)),
            )
        }
        Text(formatMarkClock(window.second), color = labelColor, fontSize = 12.sp)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.MarkHandle(x: Dp, color: Color) {
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .offset(x = x - 4.dp)
            .width(8.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color),
    )
}
