package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.autosync_label_synced
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.player_tools_clock_empty
import nuvio.composeapp.generated.resources.player_tools_clock_value
import nuvio.composeapp.generated.resources.player_tools_credits
import nuvio.composeapp.generated.resources.player_tools_delay
import nuvio.composeapp.generated.resources.player_tools_end_after_start
import nuvio.composeapp.generated.resources.player_tools_heard_it
import nuvio.composeapp.generated.resources.player_tools_intro
import nuvio.composeapp.generated.resources.player_tools_limit
import nuvio.composeapp.generated.resources.player_tools_mark_hint
import nuvio.composeapp.generated.resources.player_tools_movie
import nuvio.composeapp.generated.resources.player_tools_nudge_back_5
import nuvio.composeapp.generated.resources.player_tools_nudge_forward_5
import nuvio.composeapp.generated.resources.player_tools_nudge_minus_1
import nuvio.composeapp.generated.resources.player_tools_nudge_minus_5
import nuvio.composeapp.generated.resources.player_tools_nudge_plus_1
import nuvio.composeapp.generated.resources.player_tools_nudge_plus_5
import nuvio.composeapp.generated.resources.player_tools_past_video
import nuvio.composeapp.generated.resources.player_tools_play_range
import nuvio.composeapp.generated.resources.player_tools_preview
import nuvio.composeapp.generated.resources.player_tools_recap
import nuvio.composeapp.generated.resources.player_tools_runs_to_end
import nuvio.composeapp.generated.resources.player_tools_sent
import nuvio.composeapp.generated.resources.player_tools_starts_at_zero
import nuvio.composeapp.generated.resources.player_tools_reset
import nuvio.composeapp.generated.resources.player_tools_saw_it
import nuvio.composeapp.generated.resources.player_tools_sent
import nuvio.composeapp.generated.resources.player_tools_set_to_now
import nuvio.composeapp.generated.resources.player_tools_status_ok
import nuvio.composeapp.generated.resources.player_tools_status_problem
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
private val NoneGold = Color(0xFFFBBF24)
private val BarShape = RoundedCornerShape(22.dp)
private const val PopoverHeightDp = 40f
private const val PopoverGapDp = 6f
private const val PopoverWidthDp = 292f

internal const val SubmitBarScrollTag = "submitBarScroll"
internal const val SubmitBarScrollContentTag = "submitBarScrollContent"
internal const val SubmitBarSubmitTag = "submitBarSubmit"
internal const val SubmitBarCloseTag = "submitBarClose"

/**
 * Optional copy for the debug fixture. Null keeps the string resources.
 * Production never sets this.
 */
internal data class SubmitBarLabels(
    val intro: String,
    val recap: String,
    val credits: String,
    val preview: String,
    val start: String,
    val end: String,
    val noneIntro: String,
    val noneRecap: String,
    val noneCredits: String,
    val nonePreview: String,
    val playRange: String,
    val submit: String,
    val runsToEnd: String,
)

private val LocalSubmitBarLabels = staticCompositionLocalOf<SubmitBarLabels?> { null }

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
    segmentType: String,
    isMovie: Boolean,
    episodeCode: String?,
    year: String?,
    startSec: Double?,
    endSec: Double?,
    startShowsTenth: Boolean,
    endShowsTenth: Boolean,
    durationSec: Double,
    absent: Boolean,
    absentAllowed: Boolean,
    sent: Boolean,
    submitting: Boolean,
    keyProblem: Boolean,
    absentLine: String,
    addKeyLabel: String,
    blockLine: String?,
    errorLine: String?,
    onBack: () -> Unit,
    onSegment: (String) -> Unit,
    onAbsent: () -> Unit,
    onSetToNow: (MarkHandle) -> Unit,
    onNudge: (MarkHandle, Double) -> Unit,
    onAddKey: () -> Unit,
    onSubmit: () -> Unit,
    submittedTypes: Set<String> = emptySet(),
    onStartAtZero: () -> Unit = {},
    onRunsToEnd: () -> Unit = {},
    onPreview: () -> Unit = {},
    autoSyncedTypes: Set<String> = emptySet(),
    labels: SubmitBarLabels? = null,
    modifier: Modifier = Modifier,
) {
  CompositionLocalProvider(LocalSubmitBarLabels provides labels) {
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
    val noneLabel = noSegmentLabel(segmentType)
    val movieLabel = stringResource(Res.string.player_tools_movie)
    val pillText = when {
        sent -> stringResource(Res.string.player_tools_sent)
        absent && isMovie -> listOfNotNull(noneLabel, movieLabel, year).joinToString(" · ")
        absent -> listOfNotNull(noneLabel, episodeCode).joinToString(" · ")
        else -> LocalSubmitBarLabels.current?.submit ?: stringResource(Res.string.player_tools_submit)
    }
    var popover by remember { mutableStateOf<MarkHandle?>(null) }
    LaunchedEffect(absent, markSegmentId(segmentType)) { popover = null }
    val activePopover = if (absent) null else popover
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val window = WindowInsets.safeDrawing
    var boxX by remember { mutableFloatStateOf(0f) }
    var startWindowX by remember { mutableFloatStateOf(0f) }
    var endWindowX by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(modifier.fillMaxSize().testTag("submitPanel")) {
        val topInset = with(density) { window.getTop(this).toDp().value }
        val bounds = submitBarBounds(
            screenWidth = maxWidth.value,
            screenHeight = maxHeight.value,
            insets = SubmitBarInsets(
                left = with(density) { window.getLeft(this, direction).toDp().value },
                top = topInset,
                right = with(density) { window.getRight(this, direction).toDp().value },
                bottom = with(density) { window.getBottom(this).toDp().value },
            ),
        )
        val showPopover = activePopover != null
        val popoverTop = (bounds.top - PopoverGapDp - PopoverHeightDp).coerceAtLeast(topInset)
        val chromeTop = if (showPopover) popoverTop else bounds.top
        val chromeHeight = (bounds.top + bounds.height - chromeTop).coerceAtLeast(bounds.height)
        val labelSize = if (bounds.height < 40f) 11.sp else 13.sp
        val chipHeight = (bounds.height - if (bounds.height < 40f) 6f else 12f).coerceAtLeast(22f)
        val hPad = if (bounds.height < 40f) 6.dp else 8.dp
        if (showPopover) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { popover = null },
                    ),
            )
        }
        Box(
            Modifier
                .offset(x = bounds.left.dp, y = chromeTop.dp)
                .width(bounds.width.dp)
                .height(chromeHeight.dp)
                .onGloballyPositioned { boxX = it.positionInWindow().x },
        ) {
            if (showPopover) {
                val anchorPx = (if (activePopover == MarkHandle.End) endWindowX else startWindowX) - boxX
                val anchorDp = with(density) { anchorPx.toDp().value }
                    .coerceIn(0f, (bounds.width - PopoverWidthDp).coerceAtLeast(0f))
                NudgePopover(
                    enabled = editable,
                    labelSize = labelSize,
                    onNudge = { delta -> onNudge(activePopover ?: MarkHandle.Start, delta) },
                    onSetToNow = { onSetToNow(activePopover ?: MarkHandle.Start) },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = anchorDp.dp)
                        .height(PopoverHeightDp.dp),
                )
            }
            val labelOverrides = LocalSubmitBarLabels.current
            val playRange = labelOverrides?.playRange ?: stringResource(Res.string.player_tools_play_range)
            val runsToEndLabel = labelOverrides?.runsToEnd ?: stringResource(Res.string.submit_intro_end_short)
            PinnedEndRow(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(bounds.height.dp)
                    .clip(BarShape)
                    .background(PlayerToolSurface.copy(alpha = 0.94f))
                    .border(1.dp, PlayerToolEdge, BarShape)
                    .consumeToolTaps()
                    .padding(horizontal = 4.dp),
            ) {
                // Chips, clocks, status, the none label, and Play range scroll.
                // Submit and close are measured first and pinned to the end.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(SubmitBarScrollTag),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.testTag(SubmitBarScrollContentTag),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            SegmentTabs(
                                segmentType = segmentType,
                                isMovie = isMovie,
                                enabled = editable,
                                submittedTypes = submittedTypes,
                                chipHeight = chipHeight.dp,
                                labelSize = labelSize,
                                hPad = hPad,
                                onSegment = onSegment,
                            )
                            if (markSegmentId(segmentType) in autoSyncedTypes) {
                                AutoSyncedMarkChip(
                                    chipHeight = chipHeight.dp,
                                    labelSize = labelSize,
                                    hPad = hPad,
                                )
                            }
                            if (!absent) {
                                ClockChip(
                                    title = labelOverrides?.start
                                        ?: stringResource(Res.string.submit_intro_start_short),
                                    clock = startSec?.let { formatMarkClock(it, showTenth = startShowsTenth) }
                                        ?: stringResource(Res.string.player_tools_clock_empty),
                                    selected = activePopover == MarkHandle.Start,
                                    enabled = editable,
                                    chipHeight = chipHeight.dp,
                                    labelSize = labelSize,
                                    hPad = hPad,
                                    onClick = {
                                        popover = if (popover == MarkHandle.Start) null else MarkHandle.Start
                                    },
                                    modifier = Modifier.onGloballyPositioned { startWindowX = it.positionInWindow().x },
                                )
                                if (markSegmentOffersStartAtZero(segmentType)) {
                                    BarChip(
                                        label = "0:00",
                                        description = stringResource(Res.string.player_tools_starts_at_zero),
                                        enabled = editable,
                                        selected = false,
                                        chipHeight = chipHeight.dp,
                                        labelSize = labelSize,
                                        hPad = hPad,
                                        onClick = onStartAtZero,
                                    )
                                }
                                ClockChip(
                                    title = labelOverrides?.end
                                        ?: stringResource(Res.string.submit_intro_end_short),
                                    clock = endSec?.let { formatMarkClock(it, showTenth = endShowsTenth) }
                                        ?: stringResource(Res.string.player_tools_clock_empty),
                                    selected = activePopover == MarkHandle.End,
                                    enabled = editable,
                                    chipHeight = chipHeight.dp,
                                    labelSize = labelSize,
                                    hPad = hPad,
                                    onClick = {
                                        popover = if (popover == MarkHandle.End) null else MarkHandle.End
                                    },
                                    modifier = Modifier.onGloballyPositioned { endWindowX = it.positionInWindow().x },
                                )
                                if (markSegmentOffersRunsToEnd(segmentType)) {
                                    BarChip(
                                        label = runsToEndLabel,
                                        description = stringResource(Res.string.player_tools_runs_to_end),
                                        enabled = editable && durationSec > 0.0,
                                        selected = markRunsToEndSelected(endSec, durationSec),
                                        chipHeight = chipHeight.dp,
                                        labelSize = labelSize,
                                        hPad = hPad,
                                        onClick = onRunsToEnd,
                                    )
                                }
                            }
                            MarkStatus(
                                range = range,
                                segmentType = segmentType,
                                segmentLabel = markSegmentLabel(segmentType),
                                absent = absent,
                                absentAllowed = absentAllowed,
                                keyProblem = keyProblem,
                                absentLine = absentLine,
                                addKeyLabel = addKeyLabel,
                                blockLine = blockLine,
                                errorLine = errorLine,
                                onAddKey = onAddKey,
                                labelSize = labelSize,
                            )
                            if (!absent) {
                                Text(
                                    text = noneLabel,
                                    color = if (editable) NoneGold else Color.White.copy(alpha = 0.35f),
                                    fontSize = labelSize,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable(enabled = editable, onClick = onAbsent)
                                        .padding(horizontal = hPad, vertical = 4.dp),
                                )
                            }
                            BarChip(
                                label = playRange,
                                description = playRange,
                                enabled = markPlayRangeEnabled(absent, timeOk, submitting),
                                selected = false,
                                chipHeight = chipHeight.dp,
                                labelSize = labelSize,
                                hPad = hPad,
                                onClick = onPreview,
                            )
                        }
                    }
                }
                SubmitPill(
                    text = pillText,
                    enabled = canSend,
                    sent = sent,
                    labelSize = labelSize,
                    chipHeight = chipHeight.dp,
                    onClick = onSubmit,
                    modifier = Modifier.testTag(SubmitBarSubmitTag),
                )
                Box(
                    modifier = Modifier
                        .testTag(SubmitBarCloseTag)
                        .size(chipHeight.dp)
                        .clip(CircleShape)
                        .clickable(enabled = editable, onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.action_close),
                        tint = Color.White.copy(alpha = if (editable) 1f else 0.35f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
  }
}

/**
 * One row. The first child scrolls and may be wider than the space it is given.
 * The second and third children (Submit, close) are pinned to the end and stay
 * inside the row even when the scrolling child does not fit.
 */
@Composable
private fun PinnedEndRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = 4.dp.roundToPx()
        val maxWidth = constraints.maxWidth.coerceAtLeast(0)
        val maxHeight = constraints.maxHeight.coerceAtLeast(0)
        val closePlaceable = measurables[2].measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxWidth = maxWidth),
        )
        val submitMax = (maxWidth - closePlaceable.width - gap).coerceAtLeast(0)
        val submitPlaceable = measurables[1].measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxWidth = submitMax),
        )
        val scrollWidth = (maxWidth - submitPlaceable.width - closePlaceable.width - gap * 2).coerceAtLeast(0)
        val scrollPlaceable = measurables[0].measure(
            Constraints(
                minWidth = scrollWidth,
                maxWidth = scrollWidth,
                minHeight = 0,
                maxHeight = maxHeight,
            ),
        )
        layout(maxWidth, maxHeight) {
            fun yOf(height: Int) = ((maxHeight - height) / 2).coerceAtLeast(0)
            scrollPlaceable.placeRelative(0, yOf(scrollPlaceable.height))
            val submitX = scrollWidth + gap
            submitPlaceable.placeRelative(submitX, yOf(submitPlaceable.height))
            closePlaceable.placeRelative(
                submitX + submitPlaceable.width + gap,
                yOf(closePlaceable.height),
            )
        }
    }
}

@Composable
internal fun submitBarHeightDp(): Dp {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val size = LocalWindowInfo.current.containerSize
    val insets = WindowInsets.safeDrawing
    val width = with(density) { size.width.toDp().value }
    val height = with(density) { size.height.toDp().value }
    val left = with(density) { insets.getLeft(this, direction).toDp().value }
    val top = with(density) { insets.getTop(this).toDp().value }
    val right = with(density) { insets.getRight(this, direction).toDp().value }
    val bottom = with(density) { insets.getBottom(this).toDp().value }
    return submitBarBounds(
        screenWidth = width,
        screenHeight = height,
        insets = SubmitBarInsets(left, top, right, bottom),
    ).height.dp
}

@Composable
private fun SegmentTabs(
    segmentType: String,
    isMovie: Boolean,
    enabled: Boolean,
    submittedTypes: Set<String>,
    chipHeight: Dp,
    labelSize: androidx.compose.ui.unit.TextUnit,
    hPad: Dp,
    onSegment: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val sentLabel = stringResource(Res.string.player_tools_sent)
    Row(verticalAlignment = Alignment.CenterVertically) {
        markSegmentTypes(isMovie).forEach { type ->
            val selected = markSegmentId(segmentType) == type
            val label = markSegmentLabel(type)
            val submitted = markSegmentId(type) in submittedTypes
            val shape = RoundedCornerShape(12.dp)
            Box(
                modifier = Modifier
                    .height(chipHeight)
                    .clip(shape)
                    .background(if (selected) tokens.colors.accent else Color.Transparent)
                    .clickable(enabled = enabled, onClick = { onSegment(type) })
                    .padding(horizontal = hPad),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        color = when {
                            !enabled -> Color.White.copy(alpha = 0.35f)
                            selected -> tokens.colors.onAccent
                            else -> Color.White.copy(alpha = 0.86f)
                        },
                        fontSize = labelSize,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                    )
                    if (submitted) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "$label $sentLabel",
                            tint = if (selected) tokens.colors.onAccent else MarkOk,
                            modifier = Modifier.padding(start = 3.dp).size(12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Static status for a segment type this app's analysis already has.
 * It does not move, so Reduce Motion has nothing new to follow.
 */
@Composable
private fun AutoSyncedMarkChip(
    chipHeight: Dp,
    labelSize: androidx.compose.ui.unit.TextUnit,
    hPad: Dp,
) {
    val label = stringResource(Res.string.autosync_label_synced)
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .height(chipHeight)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.10f))
            .semantics(mergeDescendants = true) {
                contentDescription = label
            }
            .padding(horizontal = hPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Sync,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = labelSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun BarChip(
    label: String,
    description: String,
    enabled: Boolean,
    selected: Boolean,
    chipHeight: Dp,
    labelSize: androidx.compose.ui.unit.TextUnit,
    hPad: Dp,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .height(chipHeight)
            .clip(shape)
            .background(
                when {
                    selected -> MaterialTheme.nuvio.colors.accent
                    else -> Color.White.copy(alpha = 0.10f)
                },
            )
            .border(if (selected) 1.5.dp else 0.dp, if (selected) Color.White else Color.Transparent, shape)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = hPad),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> Color.White.copy(alpha = 0.35f)
                selected -> MaterialTheme.nuvio.colors.onAccent
                else -> Color.White
            },
            fontSize = labelSize,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ClockChip(
    title: String,
    clock: String,
    selected: Boolean,
    enabled: Boolean,
    chipHeight: Dp,
    labelSize: androidx.compose.ui.unit.TextUnit,
    hPad: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(chipHeight)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.10f))
            .border(if (selected) 1.5.dp else 0.dp, if (selected) Color.White else Color.Transparent, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = hPad),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.player_tools_clock_value, title, clock),
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
            fontSize = labelSize,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun NudgePopover(
    enabled: Boolean,
    labelSize: androidx.compose.ui.unit.TextUnit,
    onNudge: (Double) -> Unit,
    onSetToNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = listOf(
        Triple(-5.0, stringResource(Res.string.player_tools_nudge_minus_5), stringResource(Res.string.player_tools_nudge_back_5)),
        Triple(-1.0, stringResource(Res.string.player_tools_nudge_minus_1), stringResource(Res.string.submit_intro_time_nudge_back_1)),
        Triple(1.0, stringResource(Res.string.player_tools_nudge_plus_1), stringResource(Res.string.submit_intro_time_nudge_forward_1)),
        Triple(5.0, stringResource(Res.string.player_tools_nudge_plus_5), stringResource(Res.string.player_tools_nudge_forward_5)),
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xF014161B))
            .border(1.dp, PlayerToolEdge, RoundedCornerShape(14.dp))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
            text = stringResource(Res.string.player_tools_set_to_now),
            color = if (enabled) NoneGold else Color.White.copy(alpha = 0.35f),
            fontSize = labelSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, onClick = onSetToNow)
                .padding(horizontal = 8.dp),
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
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = if (enabled) 0.10f else 0.04f))
            .semantics(mergeDescendants = true) {
                onClick(label = description) {
                    if (!enabledState.value) return@onClick false
                    nudgeState.value(deltaState.value)
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    val released = withTimeoutOrNull(NudgeHoldDelayMs) { waitForUpOrCancellation() }
                    if (!enabledState.value) return@awaitEachGesture
                    if (released != null || abs(deltaState.value) != 1.0) {
                        nudgeState.value(deltaState.value)
                        if (released == null) waitForUpOrCancellation()
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
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun SubmitPill(
    text: String,
    enabled: Boolean,
    sent: Boolean,
    labelSize: androidx.compose.ui.unit.TextUnit,
    chipHeight: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(14.dp)
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
    Box(
        modifier = modifier
            .height(chipHeight)
            .clip(shape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = foreground,
            fontSize = labelSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun markSegmentLabel(type: String): String {
    val labels = LocalSubmitBarLabels.current
    return when (markSegmentId(type)) {
        "intro" -> labels?.intro ?: stringResource(Res.string.player_tools_intro)
        "recap" -> labels?.recap ?: stringResource(Res.string.player_tools_recap)
        "preview" -> labels?.preview ?: stringResource(Res.string.player_tools_preview)
        else -> labels?.credits ?: stringResource(Res.string.player_tools_credits)
    }
}

@Composable
private fun noSegmentLabel(segmentType: String): String {
    val labels = LocalSubmitBarLabels.current
    return when (noneChip(segmentType)) {
        NoneChip.Intro -> labels?.noneIntro ?: stringResource(Res.string.submit_intro_none_intro)
        NoneChip.Recap -> labels?.noneRecap ?: stringResource(Res.string.submit_intro_none_recap)
        NoneChip.Credits -> labels?.noneCredits ?: stringResource(Res.string.submit_intro_none_credits)
        NoneChip.Preview -> labels?.nonePreview ?: stringResource(Res.string.submit_intro_none_preview)
    }
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
    labelSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    val problem = range.problem
    when {
        keyProblem -> Text(
            text = addKeyLabel,
            color = NoneGold,
            fontSize = labelSize,
            fontWeight = FontWeight.SemiBold,
            softWrap = true,
            modifier = modifier.clickable(onClick = onAddKey),
        )
        !errorLine.isNullOrBlank() -> MarkStatusText(errorLine, MarkError, labelSize, modifier)
        !blockLine.isNullOrBlank() -> MarkStatusText(blockLine, MarkError, labelSize, modifier)
        absent && !absentAllowed -> MarkStatusText(absentLine, MarkError, labelSize, modifier)
        absent -> Spacer(modifier)
        !range.bothSet -> MarkStatusText(
            stringResource(Res.string.player_tools_mark_hint),
            Color.White.copy(alpha = 0.7f),
            labelSize,
            modifier,
        )
        problem != null -> {
            val detail = markProblemText(problem, segmentType, segmentLabel)
            val text = range.lengthLabel?.let { length ->
                stringResource(Res.string.player_tools_status_problem, length, detail)
            } ?: detail
            MarkStatusText(text, MarkError, labelSize, modifier)
        }
        else -> MarkStatusText(
            stringResource(Res.string.player_tools_status_ok, range.lengthLabel.orEmpty()),
            MarkOk,
            labelSize,
            modifier,
        )
    }
}

@Composable
private fun MarkStatusText(
    text: String,
    color: Color,
    labelSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        fontSize = labelSize,
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
