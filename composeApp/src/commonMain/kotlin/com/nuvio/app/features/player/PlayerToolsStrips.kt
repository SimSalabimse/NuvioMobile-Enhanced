package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.nuvio.app.core.ui.Menu
import com.nuvio.app.core.ui.MenuItem
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.player_tools_clock_empty
import nuvio.composeapp.generated.resources.player_tools_clock_value
import nuvio.composeapp.generated.resources.player_tools_copy_link
import nuvio.composeapp.generated.resources.player_tools_credits
import nuvio.composeapp.generated.resources.player_tools_delay
import nuvio.composeapp.generated.resources.player_tools_end_after_start
import nuvio.composeapp.generated.resources.player_tools_heard_it
import nuvio.composeapp.generated.resources.player_tools_hold_tenth
import nuvio.composeapp.generated.resources.player_tools_intro
import nuvio.composeapp.generated.resources.player_tools_limit
import nuvio.composeapp.generated.resources.player_tools_marked_none_episode
import nuvio.composeapp.generated.resources.player_tools_marked_none_movie
import nuvio.composeapp.generated.resources.player_tools_mark_hint
import nuvio.composeapp.generated.resources.player_tools_more
import nuvio.composeapp.generated.resources.player_tools_movie
import nuvio.composeapp.generated.resources.player_tools_name_credits
import nuvio.composeapp.generated.resources.player_tools_name_intro
import nuvio.composeapp.generated.resources.player_tools_name_preview
import nuvio.composeapp.generated.resources.player_tools_name_recap
import nuvio.composeapp.generated.resources.player_tools_none_edit_again
import nuvio.composeapp.generated.resources.player_tools_nudge_back_5
import nuvio.composeapp.generated.resources.player_tools_nudge_end
import nuvio.composeapp.generated.resources.player_tools_nudge_forward_5
import nuvio.composeapp.generated.resources.player_tools_nudge_minus_1
import nuvio.composeapp.generated.resources.player_tools_nudge_minus_5
import nuvio.composeapp.generated.resources.player_tools_nudge_plus_1
import nuvio.composeapp.generated.resources.player_tools_nudge_plus_5
import nuvio.composeapp.generated.resources.player_tools_nudge_start
import nuvio.composeapp.generated.resources.player_tools_past_video
import nuvio.composeapp.generated.resources.player_tools_play_range
import nuvio.composeapp.generated.resources.player_tools_preview
import nuvio.composeapp.generated.resources.player_tools_recap
import nuvio.composeapp.generated.resources.player_tools_reset
import nuvio.composeapp.generated.resources.player_tools_reset_marks
import nuvio.composeapp.generated.resources.player_tools_saw_it
import nuvio.composeapp.generated.resources.player_tools_sent
import nuvio.composeapp.generated.resources.player_tools_set_to_now
import nuvio.composeapp.generated.resources.player_tools_status_problem
import nuvio.composeapp.generated.resources.player_tools_status_valid
import nuvio.composeapp.generated.resources.player_tools_submit
import nuvio.composeapp.generated.resources.player_tools_subs_help
import nuvio.composeapp.generated.resources.submit_intro_end_short
import nuvio.composeapp.generated.resources.submit_intro_none_credits
import nuvio.composeapp.generated.resources.submit_intro_none_intro
import nuvio.composeapp.generated.resources.submit_intro_none_preview
import nuvio.composeapp.generated.resources.submit_intro_none_recap
import nuvio.composeapp.generated.resources.submit_intro_start_short
import nuvio.composeapp.generated.resources.submit_intro_time_nudge_back_1
import nuvio.composeapp.generated.resources.submit_intro_time_nudge_forward_1
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

private val NoneGold = Color(0xFFFBBF24)
private val NoneInk = Color(0xFF1A1204)
private val MarkStatusInlineMinWidth = 840.dp

@Composable
internal fun SegmentMarkingLayer(
    title: String,
    segmentType: String,
    isMovie: Boolean,
    episodeCode: String?,
    year: String?,
    startSec: Double?,
    endSec: Double?,
    startShowsTenth: Boolean,
    endShowsTenth: Boolean,
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
    onSetToNow: (MarkHandle) -> Unit,
    onNudge: (MarkHandle, Double) -> Unit,
    onSeek: (Double) -> Unit,
    onDragStart: (Double) -> Unit,
    onDragEnd: (Double) -> Unit,
    onPreview: () -> Unit,
    onCopyLink: () -> Unit,
    showCopyLink: Boolean,
    onAddKey: () -> Unit,
    onReset: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = markRangeStatus(segmentType, startSec, endSec, durationSec.takeIf { it > 0.0 })
    val timeOk = range.bothSet && range.problem == null
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
    val noneLabel = noSegmentLabel(segmentType)
    val movieLabel = stringResource(Res.string.player_tools_movie)
    val caption = if (absent) {
        if (isMovie) listOfNotNull(noneLabel, movieLabel, year).joinToString(" · ")
        else listOfNotNull(noneLabel, episodeCode).joinToString(" · ")
    } else if (isMovie) {
        listOfNotNull(segmentLabel, movieLabel, year).joinToString(" · ")
    } else {
        listOfNotNull(segmentLabel, episodeCode).joinToString(" · ")
    }
    var handle by remember { mutableStateOf(MarkHandle.Start) }
    LaunchedEffect(markSegmentId(segmentType)) { handle = MarkHandle.Start }
    val side = if (safeInset < 12.dp) 12.dp else safeInset
    Box(modifier.fillMaxSize().testTag("submitPanel")) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .consumeToolTaps()
                .padding(start = side, end = side, top = 8.dp),
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
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }
        BoxWithConstraints(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            val wide = maxWidth >= MarkStatusInlineMinWidth
            val submitWidth = if (maxWidth < 760.dp) 148.dp else 188.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(16.dp, StripShape)
                    .clip(StripShape)
                    .background(PlayerToolSurface.copy(alpha = 0.92f))
                    .border(1.dp, PlayerToolEdge, StripShape),
            ) {
                Box(Modifier.matchParentSize().consumeToolTaps())
                Column(
                    modifier = Modifier.padding(start = side, end = side, top = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MarkHeader(
                        segmentType = segmentType,
                        isMovie = isMovie,
                        absent = absent,
                        editable = editable,
                        wide = wide,
                        range = range,
                        segmentLabel = segmentLabel,
                        absentAllowed = absentAllowed,
                        keyProblem = keyProblem,
                        absentLine = absentLine,
                        addKeyLabel = addKeyLabel,
                        blockLine = blockLine,
                        errorLine = errorLine,
                        showCopyLink = showCopyLink,
                        timeOk = timeOk,
                        onSegment = onSegment,
                        onAbsent = onAbsent,
                        onCopyLink = onCopyLink,
                        onPreview = onPreview,
                        onReset = onReset,
                        onAddKey = onAddKey,
                        onBack = onBack,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().height(156.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            if (absent) {
                                NoneMarkedCopy(
                                    isMovie = isMovie,
                                    segmentType = segmentType,
                                    segmentLabel = segmentLabel,
                                    modifier = Modifier.fillMaxHeight(),
                                )
                            } else {
                                MarkingSeekBar(
                                    playheadSec = playheadSec,
                                    startSec = startSec,
                                    endSec = endSec,
                                    durationSec = durationSec,
                                    previewEnabled = editable && timeOk,
                                    onSeek = onSeek,
                                    onStart = { seconds ->
                                        handle = MarkHandle.Start
                                        onDragStart(seconds)
                                    },
                                    onEnd = { seconds ->
                                        handle = MarkHandle.End
                                        onDragEnd(seconds)
                                    },
                                    onPreview = onPreview,
                                )
                                ClockRow(
                                    handle = handle,
                                    startSec = startSec,
                                    endSec = endSec,
                                    startShowsTenth = startShowsTenth,
                                    endShowsTenth = endShowsTenth,
                                    enabled = editable,
                                    onSelect = { handle = it },
                                    onSetToNow = { onSetToNow(handle) },
                                )
                                NudgeRow(
                                    handle = handle,
                                    enabled = editable,
                                    onNudge = { delta -> onNudge(handle, delta) },
                                )
                            }
                        }
                        SubmitBlock(
                            enabled = canSend,
                            sent = sent,
                            caption = caption,
                            onClick = onSubmit,
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .width(submitWidth)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkHeader(
    segmentType: String,
    isMovie: Boolean,
    absent: Boolean,
    editable: Boolean,
    wide: Boolean,
    range: MarkRangeStatus,
    segmentLabel: String,
    absentAllowed: Boolean,
    keyProblem: Boolean,
    absentLine: String,
    addKeyLabel: String,
    blockLine: String?,
    errorLine: String?,
    showCopyLink: Boolean,
    timeOk: Boolean,
    onSegment: (String) -> Unit,
    onAbsent: () -> Unit,
    onCopyLink: () -> Unit,
    onPreview: () -> Unit,
    onReset: () -> Unit,
    onAddKey: () -> Unit,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SegmentedTabs(
                    isMovie = isMovie,
                    segmentType = segmentType,
                    absent = absent,
                    enabled = editable,
                    onSegment = onSegment,
                )
            }
            NoneChip(
                label = noSegmentLabel(segmentType),
                selected = absent,
                enabled = editable,
                onClick = onAbsent,
            )
            if (wide) {
                MarkStatus(
                    range = range,
                    segmentType = segmentType,
                    segmentLabel = segmentLabel,
                    absent = absent,
                    absentAllowed = absentAllowed,
                    keyProblem = keyProblem,
                    absentLine = absentLine,
                    addKeyLabel = addKeyLabel,
                    blockLine = blockLine,
                    errorLine = errorLine,
                    onAddKey = onAddKey,
                    modifier = Modifier.weight(1f),
                )
            }
            MarkOverflowMenu(
                editable = editable,
                showCopyLink = showCopyLink,
                playEnabled = editable && timeOk && !absent,
                onCopyLink = onCopyLink,
                onPreview = onPreview,
                onReset = onReset,
            )
            PlayerToolIconButton(
                icon = Icons.Rounded.Close,
                description = stringResource(Res.string.action_close),
                onClick = onBack,
                enabled = editable,
            )
        }
        if (!wide) {
            MarkStatus(
                range = range,
                segmentType = segmentType,
                segmentLabel = segmentLabel,
                absent = absent,
                absentAllowed = absentAllowed,
                keyProblem = keyProblem,
                absentLine = absentLine,
                addKeyLabel = addKeyLabel,
                blockLine = blockLine,
                errorLine = errorLine,
                onAddKey = onAddKey,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SegmentedTabs(
    isMovie: Boolean,
    segmentType: String,
    absent: Boolean,
    enabled: Boolean,
    onSegment: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        markSegmentTypes(isMovie).forEach { type ->
            val selected = !absent && markSegmentId(segmentType) == type
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) tokens.colors.accent else Color.Transparent)
                    .clickable(enabled = enabled, onClick = { onSegment(type) })
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = markSegmentLabel(type),
                    color = when {
                        !enabled -> Color.White.copy(alpha = 0.35f)
                        selected -> tokens.colors.onAccent
                        else -> Color.White.copy(alpha = 0.82f)
                    },
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun NoneChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) NoneGold else Color.White.copy(alpha = 0.08f))
            .then(if (selected) Modifier else Modifier.noneChipBorder())
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> Color.White.copy(alpha = 0.35f)
                selected -> NoneInk
                else -> NoneGold
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun Modifier.noneChipBorder(): Modifier = drawBehind {
    val inset = 1.dp.toPx()
    drawRoundRect(
        color = NoneGold,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2, size.height - inset * 2),
        cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
        style = Stroke(
            width = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())),
        ),
    )
}

@Composable
private fun MarkOverflowMenu(
    editable: Boolean,
    showCopyLink: Boolean,
    playEnabled: Boolean,
    onCopyLink: () -> Unit,
    onPreview: () -> Unit,
    onReset: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        PlayerToolIconButton(
            icon = Icons.Rounded.MoreHoriz,
            description = stringResource(Res.string.player_tools_more),
            onClick = { open = true },
            enabled = editable,
        )
        Menu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem(
                text = stringResource(Res.string.player_tools_copy_link),
                enabled = showCopyLink,
                onClick = {
                    open = false
                    onCopyLink()
                },
            )
            MenuItem(
                text = stringResource(Res.string.player_tools_play_range),
                enabled = playEnabled,
                onClick = {
                    open = false
                    onPreview()
                },
            )
            MenuItem(
                text = stringResource(Res.string.player_tools_reset_marks),
                enabled = editable,
                onClick = {
                    open = false
                    onReset()
                },
            )
        }
    }
}

@Composable
private fun NoneMarkedCopy(
    isMovie: Boolean,
    segmentType: String,
    segmentLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(
                if (isMovie) Res.string.player_tools_marked_none_movie
                else Res.string.player_tools_marked_none_episode,
                noneWord(segmentType),
            ),
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 16.sp,
            lineHeight = 20.sp,
            maxLines = 2,
            softWrap = true,
        )
        Text(
            text = stringResource(Res.string.player_tools_none_edit_again, segmentLabel),
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 13.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            softWrap = true,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ClockRow(
    handle: MarkHandle,
    startSec: Double?,
    endSec: Double?,
    startShowsTenth: Boolean,
    endShowsTenth: Boolean,
    enabled: Boolean,
    onSelect: (MarkHandle) -> Unit,
    onSetToNow: () -> Unit,
) {
    val empty = stringResource(Res.string.player_tools_clock_empty)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ClockChip(
            title = stringResource(Res.string.submit_intro_start_short),
            clock = startSec?.let { formatMarkClock(it, showTenth = startShowsTenth) } ?: empty,
            selected = handle == MarkHandle.Start,
            enabled = enabled,
            onClick = { onSelect(MarkHandle.Start) },
        )
        ClockChip(
            title = stringResource(Res.string.submit_intro_end_short),
            clock = endSec?.let { formatMarkClock(it, showTenth = endShowsTenth) } ?: empty,
            selected = handle == MarkHandle.End,
            enabled = enabled,
            onClick = { onSelect(MarkHandle.End) },
        )
        SetToNowButton(enabled = enabled, onClick = onSetToNow)
    }
}

@Composable
private fun ClockChip(
    title: String,
    clock: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .heightIn(min = 42.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(2.dp, if (selected) Color.White else Color.Transparent, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.player_tools_clock_value, title, clock),
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun SetToNowButton(enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .heightIn(min = 42.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (enabled) NoneGold else Color.White.copy(alpha = 0.35f)),
        )
        Text(
            text = stringResource(Res.string.player_tools_set_to_now),
            color = if (enabled) NoneGold else Color.White.copy(alpha = 0.35f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun NudgeRow(
    handle: MarkHandle,
    enabled: Boolean,
    onNudge: (Double) -> Unit,
) {
    val steps = listOf(
        Triple(-5.0, stringResource(Res.string.player_tools_nudge_minus_5), stringResource(Res.string.player_tools_nudge_back_5)),
        Triple(-1.0, stringResource(Res.string.player_tools_nudge_minus_1), stringResource(Res.string.submit_intro_time_nudge_back_1)),
        Triple(1.0, stringResource(Res.string.player_tools_nudge_plus_1), stringResource(Res.string.submit_intro_time_nudge_forward_1)),
        Triple(5.0, stringResource(Res.string.player_tools_nudge_plus_5), stringResource(Res.string.player_tools_nudge_forward_5)),
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(
                if (handle == MarkHandle.Start) Res.string.player_tools_nudge_start
                else Res.string.player_tools_nudge_end,
            ),
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 14.sp,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(end = 2.dp),
        )
        steps.forEach { (delta, label, description) ->
            NudgeHoldButton(
                label = label,
                description = description,
                delta = delta,
                enabled = enabled,
                onNudge = onNudge,
            )
        }
        Text(
            text = stringResource(Res.string.player_tools_hold_tenth),
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 12.sp,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@Composable
private fun NudgeHoldButton(
    label: String,
    description: String,
    delta: Double,
    enabled: Boolean,
    onNudge: (Double) -> Unit,
) {
    val nudgeState = rememberUpdatedState(onNudge)
    val deltaState = rememberUpdatedState(delta)
    val enabledState = rememberUpdatedState(enabled)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .heightIn(min = 42.dp)
            .widthIn(min = 44.dp)
            .semantics(mergeDescendants = true) {
                onClick(label = description) {
                    if (!enabledState.value) return@onClick false
                    nudgeState.value(deltaState.value)
                    true
                }
            }
            .clip(shape)
            .background(Color.White.copy(alpha = if (enabled) 0.08f else 0.04f))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    val released = withTimeoutOrNull(NudgeHoldDelayMs) { waitForUpOrCancellation() }
                    if (!enabledState.value) return@awaitEachGesture
                    if (released != null) {
                        nudgeState.value(deltaState.value)
                        return@awaitEachGesture
                    }
                    val step = holdNudgeDelta(deltaState.value)
                    nudgeState.value(step)
                    while (enabledState.value) {
                        val event = withTimeoutOrNull(NudgeHoldRepeatMs) { awaitPointerEvent() }
                        if (event == null) {
                            nudgeState.value(step)
                            continue
                        }
                        val change = event.changes.firstOrNull()
                        if (change == null || change.changedToUp() || !change.pressed) break
                    }
                }
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun SubmitBlock(
    enabled: Boolean,
    sent: Boolean,
    caption: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(22.dp)
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
        modifier = modifier
            .clip(shape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (sent) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = foreground, modifier = Modifier.size(28.dp))
            Text(
                text = stringResource(Res.string.player_tools_sent),
                color = foreground,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        } else {
            Text(
                text = stringResource(Res.string.player_tools_submit),
                color = foreground,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = caption,
                color = foreground.copy(alpha = if (enabled) 0.88f else 0.7f),
                fontSize = 13.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                softWrap = true,
                modifier = Modifier.padding(top = 4.dp),
            )
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
private fun noSegmentLabel(segmentType: String): String = when (noneChip(segmentType)) {
    NoneChip.Intro -> stringResource(Res.string.submit_intro_none_intro)
    NoneChip.Recap -> stringResource(Res.string.submit_intro_none_recap)
    NoneChip.Credits -> stringResource(Res.string.submit_intro_none_credits)
    NoneChip.Preview -> stringResource(Res.string.submit_intro_none_preview)
}

@Composable
private fun noneWord(segmentType: String): String = when (noneChip(segmentType)) {
    NoneChip.Intro -> stringResource(Res.string.player_tools_name_intro)
    NoneChip.Recap -> stringResource(Res.string.player_tools_name_recap)
    NoneChip.Credits -> stringResource(Res.string.player_tools_name_credits)
    NoneChip.Preview -> stringResource(Res.string.player_tools_name_preview)
}

@Composable
private fun MarkStatus(
    range: MarkRangeStatus,
    segmentType: String,
    segmentLabel: String,
    absent: Boolean,
    absentAllowed: Boolean,
    keyProblem: Boolean,
    absentLine: String,
    addKeyLabel: String,
    blockLine: String?,
    errorLine: String?,
    onAddKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val problem = range.problem
    when {
        keyProblem -> PlayerToolTextButton(addKeyLabel, onClick = onAddKey, modifier = modifier)
        !errorLine.isNullOrBlank() -> MarkStatusText(errorLine, MarkError, modifier)
        !blockLine.isNullOrBlank() -> MarkStatusText(blockLine, MarkError, modifier)
        absent && !absentAllowed -> MarkStatusText(absentLine, MarkError, modifier)
        absent -> Spacer(modifier)
        !range.bothSet -> MarkStatusText(
            stringResource(Res.string.player_tools_mark_hint),
            Color.White.copy(alpha = 0.7f),
            modifier,
        )
        problem != null -> {
            val detail = markProblemText(problem, segmentType, segmentLabel)
            val text = range.lengthLabel?.let { length ->
                stringResource(Res.string.player_tools_status_problem, length, detail)
            } ?: detail
            MarkStatusText(text, MarkError, modifier)
        }
        else -> MarkStatusText(
            stringResource(
                Res.string.player_tools_status_valid,
                range.lengthLabel.orEmpty(),
                range.minSec,
                range.maxSec,
            ),
            MarkOk,
            modifier,
        )
    }
}

@Composable
private fun MarkStatusText(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        softWrap = true,
        modifier = modifier,
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

private enum class MarkDrag { Playhead, Start, End }

@Composable
private fun MarkingSeekBar(
    playheadSec: Double,
    startSec: Double?,
    endSec: Double?,
    durationSec: Double,
    previewEnabled: Boolean,
    onSeek: (Double) -> Unit,
    onStart: (Double) -> Unit,
    onEnd: (Double) -> Unit,
    onPreview: () -> Unit,
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
    val playLabel = stringResource(Res.string.player_tools_play_range)
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        val trackWidth = maxWidth
        Box(
            modifier = Modifier
                .matchParentSize()
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
                                    if (change.positionChange() != Offset.Zero) change.consume()
                                    apply(change.position.x)
                                }
                            }
                        }
                    },
                ),
        ) {
            val startFraction = startSec?.let { zoomFraction(it, window.first, window.second) }
            val endFraction = endSec?.let { zoomFraction(it, window.first, window.second) }
            val playFraction = zoomFraction(playheadSec, window.first, window.second)
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = if (seekAllowed) 0.28f else 0.12f)),
            )
            if (startFraction != null && endFraction != null && endFraction > startFraction) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = trackWidth * startFraction)
                        .width(trackWidth * (endFraction - startFraction))
                        .height(16.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.colors.accent.copy(alpha = 0.55f))
                        .border(2.dp, tokens.colors.accent, RoundedCornerShape(6.dp)),
                )
            }
            startSec?.let {
                MarkHandle(
                    x = trackWidth * zoomFraction(it, window.first, window.second),
                    color = Color.White,
                )
            }
            endSec?.let {
                MarkHandle(
                    x = trackWidth * zoomFraction(it, window.first, window.second),
                    color = tokens.colors.accent,
                )
            }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = trackWidth * playFraction - 1.5.dp)
                    .width(3.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(NoneGold.copy(alpha = if (seekAllowed) 1f else 0.35f)),
            )
        }
        val startFraction = startSec?.let { zoomFraction(it, window.first, window.second) }
        val endFraction = endSec?.let { zoomFraction(it, window.first, window.second) }
        if (startFraction != null && endFraction != null && endFraction > startFraction) {
            val center = trackWidth * ((startFraction + endFraction) / 2f)
            Box(
                modifier = Modifier
                    .zIndex(1f)
                    .align(Alignment.CenterStart)
                    .offset(x = center - 16.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(enabled = previewEnabled, onClick = onPreview),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = playLabel,
                    tint = Color.White.copy(alpha = if (previewEnabled) 1f else 0.35f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.MarkHandle(x: Dp, color: Color) {
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .offset(x = x - 7.dp)
            .width(14.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(color),
    )
}
