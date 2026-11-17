package com.nuvio.app.core.uifixture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.SegmentMarkingLayer
import com.nuvio.app.features.player.SubmitBarLabels
import kotlin.math.min
import kotlin.math.roundToInt

internal const val PlayerSubmitBarFixtureTag = "playerSubmitBarFixture"

/** 1:23:45, the long clock used by `player-submit-bar-long`. */
internal const val PlayerSubmitBarLongClockSec = 1.0 * 3600.0 + 23.0 * 60.0 + 45.0

internal fun longSubmitBarLabels(): SubmitBarLabels = SubmitBarLabels(
    intro = "Introduction sequence",
    recap = "Previously on this story",
    credits = "Closing credits roll",
    preview = "Next episode preview",
    start = "Start time",
    end = "End time",
    noneIntro = "No introduction in this episode",
    noneRecap = "No recap in this episode",
    noneCredits = "No credits in this episode",
    nonePreview = "No preview in this episode",
    playRange = "Play the marked range",
    submit = "Submit timestamps",
    runsToEnd = "Runs to the end",
)

@Composable
internal fun PlayerSubmitBarFixtureScreen() {
    val fixture = currentUiFixture()
    val long = fixture == UiFixture.PlayerSubmitBarLong
    val width = fixture?.resolvedSubmitBarWidthDp(UiFixtureSession.submitBarWidthDp)
        ?: PlayerSubmitBarDefaultWidthDp
    PlayerSubmitBarFixture(
        widthDp = width,
        longLabels = long,
        clocksTouched = fixture == UiFixture.PlayerSubmitBarTouchedInvalid,
    )
}

/**
 * The real submit bar on a dark video frame. [widthDp] is the frame width, so a
 * portrait simulator can show a landscape phone. The frame scales down to fit
 * the window; the bar itself is laid out at [widthDp].
 */
@Composable
internal fun PlayerSubmitBarFixture(
    widthDp: Int,
    longLabels: Boolean,
    clocksTouched: Boolean = false,
    modifier: Modifier = Modifier,
    isMovie: Boolean = false,
) {
    val stageWidth = widthDp.coerceIn(1, 4_000).dp
    val stageHeight = 390.dp
    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF050608))
            .testTag(PlayerSubmitBarFixtureTag),
    ) {
        LandscapeStage(stageWidth, stageHeight, Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color(0xFF10141C))) {
                PlayerSubmitBarFixtureContent(longLabels, clocksTouched = clocksTouched, isMovie = isMovie)
            }
        }
    }
}

@Composable
internal fun PlayerSubmitBarFixtureContent(
    longLabels: Boolean,
    clocksTouched: Boolean = false,
    isMovie: Boolean = false,
) {
    SegmentMarkingLayer(
        segmentType = "preview",
        isMovie = isMovie,
        episodeCode = if (isMovie) null else "S2 E4",
        year = "2024",
        startSec = 0.0,
        endSec = if (longLabels) PlayerSubmitBarLongClockSec else 0.0,
        startShowsTenth = false,
        endShowsTenth = false,
        durationSec = 7_200.0,
        absent = false,
        absentAllowed = true,
        sent = false,
        submitting = false,
        keyProblem = false,
        absentLine = "",
        addKeyLabel = "Add a key",
        blockLine = null,
        errorLine = null,
        onBack = {},
        onSegment = {},
        onAbsent = {},
        onSetToNow = {},
        onNudge = { _, _ -> },
        onAddKey = {},
        onSubmit = {},
        submittedTypes = if (isMovie) {
            setOf("intro", "recap", "outro", "preview")
        } else {
            setOf("intro", "recap")
        },
        labels = if (longLabels) longSubmitBarLabels() else null,
        clocksTouched = clocksTouched,
    )
}

@Composable
private fun LandscapeStage(
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val stageW = width.roundToPx().coerceAtLeast(1)
        val stageH = height.roundToPx().coerceAtLeast(1)
        val child = measurables.first().measure(Constraints.fixed(stageW, stageH))
        val maxW = constraints.maxWidth.coerceAtLeast(1)
        val maxH = constraints.maxHeight.coerceAtLeast(1)
        val scale = min(maxW / stageW.toFloat(), maxH / stageH.toFloat()).coerceAtMost(1f)
        val visualW = stageW * scale
        val visualH = stageH * scale
        val x = (maxW - visualW) / 2f
        val y = (maxH - visualH) / 2f
        layout(maxW, maxH) {
            child.placeWithLayer(x.roundToInt(), y.roundToInt()) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}
