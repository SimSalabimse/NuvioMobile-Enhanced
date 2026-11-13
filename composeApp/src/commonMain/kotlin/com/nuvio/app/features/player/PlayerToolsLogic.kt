package com.nuvio.app.features.player

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.player.skip.parseTimeToSeconds
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Time, limit, and layout math for the player tools. The panel and the strips
 * call these so the rules can be tested without a device.
 */

internal const val PlayerToolPanelWidthDp = 380
internal const val PlayerToolDimWhenUnsampled = 0.15f
internal const val PlayerToolDimWhenBright = 0.35f
internal const val PlayerToolBrightLuminance = 0.72f

/** ±2 minutes, the marking seek bar's window around the playhead or the range. */
internal const val MarkingZoomPaddingSec = 120.0

internal const val IntroMinSec = 5.0
/** Above this, an intro is warned and still accepted. [IntroMaxSec] blocks. */
internal const val IntroWarnSec = 200.0
internal const val IntroMaxSec = 300.0
internal const val RecapMinSec = 5.0
internal const val RecapMaxSec = 1_200.0
internal const val CreditsMinSec = 5.0
internal const val CreditsMaxSec = 1_800.0
internal const val PreviewMinSec = 5.0
internal const val PreviewMaxSec = 1_800.0

internal enum class MarkProblem {
    EndNotAfterStart,
    PastDuration,
    TooShort,
    TooLong,
}

internal data class MarkLimits(val minSec: Double, val maxSec: Double)

internal data class MarkClocks(val startSec: Double?, val endSec: Double?)

/**
 * A posted range. [absentSegment] is "no intro" (and the other none submits):
 * both clocks are 0 and must not be stored as a skip mark.
 */
internal data class IntroSubmitResult(
    val segmentType: String,
    val startSec: Double,
    val endSec: Double,
    val absentSegment: Boolean,
)

/** IntroDB / TheIntroDB segment id used by the chips and the existing submit call. */
internal fun markSegmentId(raw: String): String = when (raw.trim().lowercase()) {
    "intro", "op", "mixed-op", "opening" -> "intro"
    "recap" -> "recap"
    "outro", "ed", "mixed-ed", "credits", "ending", "movie-credits" -> "outro"
    "preview" -> "preview"
    else -> raw.trim().lowercase()
}

internal fun markSegmentLimits(segmentType: String): MarkLimits = when (markSegmentId(segmentType)) {
    "intro" -> MarkLimits(IntroMinSec, IntroMaxSec)
    "recap" -> MarkLimits(RecapMinSec, RecapMaxSec)
    "preview" -> MarkLimits(PreviewMinSec, PreviewMaxSec)
    else -> MarkLimits(CreditsMinSec, CreditsMaxSec)
}

internal enum class IntroLengthAdvisory { Ok, Warn, Block }

/** Intro only. Above 200 s warns; above 300 s blocks. Other segments are [IntroLengthAdvisory.Ok]. */
internal fun introLengthAdvisory(segmentType: String, lengthSec: Double): IntroLengthAdvisory {
    if (markSegmentId(segmentType) != "intro") return IntroLengthAdvisory.Ok
    if (lengthSec - 1e-9 > IntroMaxSec) return IntroLengthAdvisory.Block
    if (lengthSec - 1e-9 > IntroWarnSec) return IntroLengthAdvisory.Warn
    return IntroLengthAdvisory.Ok
}

/**
 * Tabs on the submit bar, in chip order: Intro, Recap, Credits, Preview.
 * A movie offers the same four as an episode. The download analysis chip still
 * shows Credits only for a movie; that rule stays on the chip and does not apply here.
 */
@Suppress("UNUSED_PARAMETER")
internal fun markSegmentTypes(isMovie: Boolean): List<String> =
    listOf("intro", "recap", "outro", "preview")

internal enum class NoneChip {
    Intro,
    Recap,
    Credits,
    Preview,
}

internal fun noneChip(segmentType: String): NoneChip = when (markSegmentId(segmentType)) {
    "recap" -> NoneChip.Recap
    "outro" -> NoneChip.Credits
    "preview" -> NoneChip.Preview
    else -> NoneChip.Intro
}

internal enum class MarkHandle { Start, End }

internal enum class SegmentTap { Stay, ReturnToEditing, Switch }

internal fun segmentTap(currentType: String, tappedType: String, absent: Boolean): SegmentTap {
    val same = markSegmentId(currentType) == markSegmentId(tappedType)
    return when {
        !same -> SegmentTap.Switch
        absent -> SegmentTap.ReturnToEditing
        else -> SegmentTap.Stay
    }
}

internal fun absentAfterNoneChip(): Boolean = true

internal fun absentAfterClockEdit(): Boolean = false

/** Intro and recap can start at the first frame. Credits and preview do not offer that chip. */
internal fun markSegmentOffersStartAtZero(segmentType: String): Boolean =
    markSegmentId(segmentType) == "intro" || markSegmentId(segmentType) == "recap"

/** Credits and preview can end on the last frame. Intro and recap do not offer that chip. */
internal fun markSegmentOffersRunsToEnd(segmentType: String): Boolean =
    markSegmentId(segmentType) == "outro" || markSegmentId(segmentType) == "preview"

/** The End chip is selected when the end clock is the stream length. */
internal fun markRunsToEndSelected(endSec: Double?, durationSec: Double): Boolean {
    if (endSec == null || durationSec <= 0.0) return false
    return abs(endSec - durationSec) < 0.2
}

/** Play range needs a valid marked range and is off while a submit is in flight. */
internal fun markPlayRangeEnabled(absent: Boolean, timeOk: Boolean, submitting: Boolean): Boolean =
    !submitting && !absent && timeOk

internal const val NudgeHoldDelayMs = 400L

internal const val NudgeHoldRepeatMs = 110L

internal fun holdNudgeDelta(buttonDeltaSec: Double): Double = if (buttonDeltaSec < 0.0) -0.1 else 0.1

internal fun nudgeKeepsTenth(deltaSec: Double, alreadyShowing: Boolean): Boolean =
    alreadyShowing || abs(deltaSec) < 1.0

internal data class MarkRangeStatus(
    val lengthLabel: String?,
    val minSec: Int,
    val maxSec: Int,
    val problem: MarkProblem?,
    val bothSet: Boolean,
)

internal fun markRangeStatus(
    segmentType: String,
    startSec: Double?,
    endSec: Double?,
    durationSec: Double?,
): MarkRangeStatus {
    val limits = markSegmentLimits(segmentType)
    val duration = durationSec?.takeIf { it > 0.0 }
    val start = startSec?.let { clampMarkTimeToDuration(it, duration) }
    val end = endSec?.let { clampMarkTimeToDuration(it, duration) }
    val problems = if (start != null && end != null) {
        markProblems(segmentType, start, end, duration)
    } else {
        emptyList()
    }
    val length = markLengthSec(start, end)
    return MarkRangeStatus(
        lengthLabel = length?.let(::markStatusLengthLabel),
        minSec = limits.minSec.toInt(),
        maxSec = limits.maxSec.toInt(),
        problem = problems.firstOrNull(),
        bothSet = start != null && end != null,
    )
}

/**
 * The red "End must be after the start" line.
 * A fresh tab keeps its clocks (including 0:00:00) and still fails Submit,
 * but the line stays hidden until this tab's clocks have been edited.
 * Other problems, and a valid range, are unchanged.
 */
internal fun showEndAfterStartMessage(clocksTouched: Boolean, problem: MarkProblem?): Boolean =
    clocksTouched && problem == MarkProblem.EndNotAfterStart

/** Same H:MM:SS clock as Mark start / Mark end. A tenth shows only when the length is not a whole second. */
internal fun markStatusLengthLabel(lengthSec: Double): String =
    formatMarkClock(lengthSec, showTenth = true)

/**
 * Half of one displayed tenth. Rounding a clock onto a 0.1 s grid, or a
 * millisecond of float error, can land this far past the stream length.
 * Anything further is a real overshoot.
 */
internal const val MarkDurationSlopSec = 0.05

internal fun clampMarkTimeToDuration(timeSec: Double, durationSec: Double?): Double {
    val time = timeSec.coerceAtLeast(0.0)
    val duration = durationSec?.takeIf { it > 0.0 } ?: return time
    if (time > duration && time <= duration + MarkDurationSlopSec) return duration
    return time
}

/**
 * Hard checks. 5.0 s and 300.0 s are valid; 4.9 s and 300.1 s are not.
 * An unknown duration (null or 0) does not count as past the end.
 * An end equal to the stream length is valid, including Runs to end.
 * Intro and recap may leave the start empty (from 0). Credits and preview may
 * leave the end empty (through the end of the file). Both clocks empty is the
 * none vote. When both clocks are present, the end has to be after the start.
 */
internal fun markProblems(
    segmentType: String,
    startSec: Double?,
    endSec: Double?,
    durationSec: Double?,
): List<MarkProblem> {
    val problems = mutableListOf<MarkProblem>()
    val duration = durationSec?.takeIf { it > 0.0 }
    val start = startSec?.let { clampMarkTimeToDuration(it, duration) }
    val end = endSec?.let { clampMarkTimeToDuration(it, duration) }
    if (start == null && end == null) return problems
    val openStart = start == null && markSegmentOffersStartAtZero(segmentType)
    val openEnd = end == null && markSegmentOffersRunsToEnd(segmentType)
    val effectiveStart = start ?: if (openStart) 0.0 else null
    val effectiveEnd = end ?: if (openEnd) duration else null
    val outOfOrder = when {
        start != null && end != null -> end <= start
        effectiveStart == null -> true
        end == null && !openEnd -> true
        effectiveEnd != null && effectiveEnd <= effectiveStart -> true
        else -> false
    }
    if (outOfOrder) problems += MarkProblem.EndNotAfterStart
    if (duration != null &&
        ((start != null && start > duration) || (end != null && end > duration))
    ) {
        problems += MarkProblem.PastDuration
    }
    if (effectiveStart != null && effectiveEnd != null && effectiveEnd > effectiveStart) {
        val length = effectiveEnd - effectiveStart
        val limits = markSegmentLimits(segmentType)
        if (length + 1e-9 < limits.minSec) problems += MarkProblem.TooShort
        if (length - 1e-9 > limits.maxSec) problems += MarkProblem.TooLong
    }
    return problems
}

internal fun markLengthSec(startSec: Double?, endSec: Double?): Double? {
    if (startSec == null || endSec == null || endSec <= startSec) return null
    return endSec - startSec
}

/** Log-only. A long intro still submits; the strip keeps its existing validation line. */
internal fun logIntroLengthWarning(
    segmentType: String,
    startSec: Double?,
    endSec: Double?,
    durationSec: Double?,
) {
    val start = startSec ?: if (markSegmentOffersStartAtZero(segmentType)) 0.0 else return
    val end = endSec ?: durationSec?.takeIf { markSegmentOffersRunsToEnd(segmentType) } ?: return
    val length = markLengthSec(start, end) ?: return
    if (introLengthAdvisory(segmentType, length) != IntroLengthAdvisory.Warn) return
    InAppLogger.info("Player/TheIntroDB", "intro length ${length.toInt()}s is above 200s")
}

/** Same clock as [markStatusLengthLabel]. */
internal fun markLengthLabel(lengthSec: Double): String = markStatusLengthLabel(lengthSec)

/**
 * Clock shown on Mark start / Mark end. Whole seconds (`0:44:08`), the same
 * second the video length uses. A tenth (`0:44:08.9`) is shown only after a
 * 0.1 s nudge left a fraction. Hours stay unpadded so the two clocks line up.
 */
internal fun formatMarkClock(seconds: Double, showTenth: Boolean = false): String {
    val totalMs = (seconds.coerceAtLeast(0.0) * 1000.0).roundToInt().coerceAtLeast(0)
    val whole = totalMs / 1000
    val ms = totalMs % 1000
    val base = formatMarkHms(whole)
    if (!showTenth || ms == 0) return base
    val tenths = (ms + 50) / 100
    if (tenths <= 0) return base
    if (tenths >= 10) return formatMarkHms(whole + 1)
    return "$base.$tenths"
}

/** True when a 0.1 s nudge should keep the tenth on screen. A whole second does not. */
internal fun markClockShowsTenth(seconds: Double, allowTenth: Boolean): Boolean {
    if (!allowTenth) return false
    val ms = (seconds.coerceAtLeast(0.0) * 1000.0).roundToInt() % 1000
    return ms != 0
}

/**
 * Value stored for Submit. Milliseconds stay in the string so the payload is
 * not rounded to a tenth or to a whole second.
 */
internal fun formatMarkTimestamp(seconds: Double): String {
    val totalMs = (seconds.coerceAtLeast(0.0) * 1000.0).roundToInt().coerceAtLeast(0)
    val ms = totalMs % 1000
    val whole = totalMs / 1000
    return formatMarkHms(whole) + "." + ms.toString().padStart(3, '0')
}

private fun formatMarkHms(totalSeconds: Int): String {
    val whole = totalSeconds.coerceAtLeast(0)
    val hours = whole / 3600
    val minutes = (whole % 3600) / 60
    val secs = whole % 60
    return "$hours:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
}

internal fun parseMarkClock(text: String): Double? = parseTimeToSeconds(text)

internal fun nudgeMarkSeconds(currentSec: Double?, deltaSec: Double, durationSec: Double?): Double {
    val duration = durationSec?.takeIf { it > 0.0 }
    val max = duration ?: (99 * 3600 + 59 * 60 + 59).toDouble()
    val base = currentSec ?: 0.0
    val stepped = if (abs(deltaSec) < 1.0) {
        ((base + deltaSec) * 10.0).roundToInt() / 10.0
    } else {
        base + deltaSec
    }
    return clampMarkTimeToDuration(stepped.coerceIn(0.0, max), duration)
}

/** Credits / Preview shortcut: the end handle sits on the last frame. */
internal fun runsToEndSec(durationSec: Double): Double = durationSec.coerceAtLeast(0.0)

/**
 * Clocks shown for one segment. The same rule covers every segment type.
 * A same-episode edit wins, including a blank edit. A detected start and end
 * for this segment win over the zero default. Anything else is 0 and 0.
 * [segmentType] is not switched on: intro, recap, credits, preview, and every
 * other id share this body.
 */
@Suppress("UNUSED_PARAMETER")
internal fun shownSegmentClocks(
    segmentType: String,
    detectedStartSec: Double?,
    detectedEndSec: Double?,
    edited: MarkClocks?,
    sameEpisode: Boolean,
): MarkClocks {
    if (sameEpisode && edited != null) return edited
    if (detectedStartSec != null && detectedEndSec != null) {
        return MarkClocks(detectedStartSec, detectedEndSec)
    }
    return MarkClocks(0.0, 0.0)
}

/**
 * Clocks after a chip change. A saved draft wins, including a blank draft.
 * A known interval is used only when it is the same kind of segment.
 * Movie and series share [shownSegmentClocks]: credits never take intro times,
 * and a segment with nothing detected starts at 0.
 */
internal fun clocksForSegment(
    segmentType: String,
    draft: MarkClocks?,
    knownStartSec: Double?,
    knownEndSec: Double?,
    knownSegmentType: String?,
): MarkClocks {
    val wanted = markSegmentId(segmentType)
    val known = knownSegmentType?.let(::markSegmentId)
    val matches = known != null && known == wanted
    return shownSegmentClocks(
        segmentType = segmentType,
        detectedStartSec = knownStartSec.takeIf { matches },
        detectedEndSec = knownEndSec.takeIf { matches },
        edited = draft,
        sameEpisode = true,
    )
}

/** An absent intro is not a skip mark, even if the form still holds the playhead. */
internal fun submittedSkipMarkToSave(result: IntroSubmitResult): IntroSubmitResult? {
    if (result.absentSegment) return null
    if (result.endSec <= result.startSec) return null
    return result
}

/**
 * Movies send no season and no episode. The payload builder already drops them
 * for `type = movie`; this is the rule the strip and the tests share.
 */
internal fun movieSubmitSeasonEpisode(isMovie: Boolean, season: Int?, episode: Int?): Pair<Int?, Int?> =
    if (isMovie) null to null else season to episode

/**
 * Seek-bar window while marking. It covers the playhead and the marked range,
 * then grows by [paddingSec] on each side, and stays inside the video.
 */
internal fun markingZoomWindow(
    playheadSec: Double,
    startSec: Double?,
    endSec: Double?,
    durationSec: Double,
    paddingSec: Double = MarkingZoomPaddingSec,
): Pair<Double, Double> {
    if (durationSec <= 0.0) return 0.0 to 0.0
    val points = buildList {
        add(playheadSec.coerceIn(0.0, durationSec))
        startSec?.let { add(it.coerceIn(0.0, durationSec)) }
        endSec?.let { add(it.coerceIn(0.0, durationSec)) }
    }
    val lo = (points.min() - paddingSec).coerceAtLeast(0.0)
    val hi = (points.max() + paddingSec).coerceAtMost(durationSec)
    return lo to hi.coerceAtLeast(lo)
}

internal fun zoomFraction(timeSec: Double, windowStartSec: Double, windowEndSec: Double): Float {
    val span = windowEndSec - windowStartSec
    if (span <= 0.0) return 0f
    return ((timeSec - windowStartSec) / span).toFloat().coerceIn(0f, 1f)
}

/** A live stream or an unknown duration has nothing to seek. */
internal fun markingSeekAllowed(durationSec: Double): Boolean = durationSec > 0.0

/**
 * How close the playback clock must be to a requested seek before Mark start /
 * Mark end trust the clock again. Until then the requested target is the playhead.
 */
internal const val MarkingPlayheadCatchUpMs = 750L

internal data class MarkingPlayhead(val positionMs: Long, val pendingSeekMs: Long?)

/**
 * [loadingScrubMs] wins while the engine is still buffering the seek.
 * Otherwise the last requested target stands until [clockMs] lands on it.
 */
internal fun markingPlayheadAfterSample(
    clockMs: Long,
    pendingSeekMs: Long?,
    loadingScrubMs: Long?,
    catchUpMs: Long = MarkingPlayheadCatchUpMs,
): MarkingPlayhead {
    if (loadingScrubMs != null) return MarkingPlayhead(loadingScrubMs, pendingSeekMs)
    val pending = pendingSeekMs ?: return MarkingPlayhead(clockMs, null)
    return if (abs(clockMs - pending) <= catchUpMs) {
        MarkingPlayhead(clockMs, null)
    } else {
        MarkingPlayhead(pending, pending)
    }
}

/**
 * Preview arms once the playhead is inside the range (the seek has landed),
 * then stops on the end frame. A stale playhead from before the seek does not arm it.
 */
internal fun markPreviewArmed(positionMs: Long, startMs: Long, endMs: Long): Boolean =
    endMs > startMs && positionMs in startMs until endMs

internal fun shouldStopMarkPreview(positionMs: Long, endMs: Long, armed: Boolean): Boolean =
    armed && positionMs >= endMs

/** Position and play state captured when Preview starts, restored when it ends or the strip closes. */
internal data class MarkPreviewRestore(
    val positionMs: Long,
    val shouldPlay: Boolean,
)

internal data class MarkPreviewPlayback(
    val positionMs: Long,
    val shouldPlay: Boolean,
)

internal fun markPreviewSession(positionMs: Long, shouldPlay: Boolean): MarkPreviewRestore =
    MarkPreviewRestore(positionMs.coerceAtLeast(0L), shouldPlay)

internal fun restoredMarkPreview(saved: MarkPreviewRestore): MarkPreviewPlayback =
    MarkPreviewPlayback(saved.positionMs, saved.shouldPlay)

/**
 * Extra subtitle bottom offset while the sync strip covers the cue.
 * Added to the style for the apply call only; the saved offset is left alone.
 */
internal const val SubtitleSyncStripLift = 102

internal fun subtitleBottomOffsetForSyncStrip(savedOffset: Int): Int =
    (savedOffset + SubtitleSyncStripLift).coerceAtLeast(0)

/**
 * What a submit was when the viewer tapped Send, copied before the first suspend.
 * [generation] is the strip's generation at that tap.
 */
internal data class MarkSubmitSnapshot(
    val segmentType: String,
    val absent: Boolean,
    val startSec: Double,
    val endSec: Double,
    val generation: Int,
)

internal fun markSubmitSnapshot(
    segmentType: String,
    absent: Boolean,
    startSec: Double?,
    endSec: Double?,
    generation: Int,
): MarkSubmitSnapshot = MarkSubmitSnapshot(
    segmentType = markSegmentId(segmentType),
    absent = absent,
    startSec = startSec ?: 0.0,
    endSec = endSec ?: 0.0,
    generation = generation,
)

/** A late result is dropped when the strip closed or a newer submit replaced it. */
internal fun markSubmitResultApplies(
    snapshotGeneration: Int,
    currentGeneration: Int,
    stripOpen: Boolean,
): Boolean = stripOpen && snapshotGeneration == currentGeneration

/**
 * Submit is enabled for a valid range or an allowed empty segment.
 * Playing the range is never part of this check.
 */
internal fun markSubmitEnabled(
    sent: Boolean,
    submitting: Boolean,
    keyProblem: Boolean,
    blocked: Boolean,
    absent: Boolean,
    absentAllowed: Boolean,
    timeOk: Boolean,
): Boolean = !sent && !submitting && !keyProblem && !blocked && (if (absent) absentAllowed else timeOk)

internal fun subtitleDelayAfterReset(): Int = 0

internal fun subtitleDelayAfterEar(currentDelayMs: Int, heardMs: Long, sawMs: Long): Int =
    (currentDelayMs.toLong() + heardMs - sawMs)
        .coerceIn(SUBTITLE_DELAY_MIN_MS.toLong(), SUBTITLE_DELAY_MAX_MS.toLong())
        .toInt()

internal fun formatSubtitleDelayLabel(delayMs: Int): String {
    val sign = when {
        delayMs > 0 -> "+"
        delayMs < 0 -> "−"
        else -> ""
    }
    val absTenths = abs(delayMs) / 100
    val whole = absTenths / 10
    val tenth = absTenths % 10
    return "$sign$whole.$tenth s"
}

/**
 * Dismissing a tool never engages the lock. A lock the viewer already turned
 * on stays on, and the controls stay hidden behind it.
 */
internal data class ToolDismissState(val controlsVisible: Boolean, val locked: Boolean)

internal fun dismissToolState(locked: Boolean): ToolDismissState =
    ToolDismissState(controlsVisible = !locked, locked = locked)

/**
 * No frame sample is available in shared code, so the dim is a flat 15%.
 * A later caller can pass a 0–1 luminance; a bright frame uses 35%, a dark one stays clear.
 */
internal fun playerToolDimAlpha(sampledLuminance: Float?): Float = when {
    sampledLuminance == null -> PlayerToolDimWhenUnsampled
    sampledLuminance >= PlayerToolBrightLuminance -> PlayerToolDimWhenBright
    else -> 0f
}

internal enum class SourceQualityBucket { Uhd, P1080, P720, Other }

internal data class SourceRowFacts(
    val quality: String?,
    val bucket: SourceQualityBucket,
    val sizeLabel: String?,
    val seeders: Int?,
    val cached: Boolean,
)

internal fun sourceQualityBucket(quality: String?): SourceQualityBucket = when (quality?.lowercase()) {
    "2160p", "4k", "uhd" -> SourceQualityBucket.Uhd
    "1080p" -> SourceQualityBucket.P1080
    "720p" -> SourceQualityBucket.P720
    else -> SourceQualityBucket.Other
}

internal fun sourceRowFacts(
    name: String,
    description: String?,
    cached: Boolean,
): SourceRowFacts {
    val text = listOf(name, description.orEmpty()).joinToString("\n")
    val quality = QUALITY_PATTERN.find(text)?.value?.let { token ->
        when (token.lowercase()) {
            "4k", "uhd" -> "2160p"
            else -> token.lowercase()
        }
    }
    val size = SIZE_PATTERN.find(text)?.let { match ->
        "${match.groupValues[1]} ${match.groupValues[2].uppercase()}"
    }
    val seeders = SEEDER_PATTERN.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
    val cachedText = text.contains("cached", ignoreCase = true)
    return SourceRowFacts(
        quality = quality,
        bucket = sourceQualityBucket(quality),
        sizeLabel = size,
        seeders = seeders,
        cached = cached || cachedText,
    )
}

internal fun formatByteSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1.0) {
        val tenths = (gb * 10.0).roundToInt() / 10.0
        return "$tenths GB"
    }
    val mb = bytes / 1_000_000.0
    return "${mb.roundToInt()} MB"
}

private val QUALITY_PATTERN = Regex("""(?i)\b(2160p|1080p|720p|480p|4k|uhd)\b""")
private val SIZE_PATTERN = Regex("""(?i)\b(\d+(?:\.\d+)?)\s*(gb|mb)\b""")
private val SEEDER_PATTERN = Regex("""(?i)\b(\d[\d,]*)\s+seeders?\b""")

internal enum class SubmitPanelTab { Intro, Recap, Credits, Preview }

internal val SubmitPanelTab.segmentId: String
    get() = when (this) {
        SubmitPanelTab.Intro -> "intro"
        SubmitPanelTab.Recap -> "recap"
        SubmitPanelTab.Credits -> "outro"
        SubmitPanelTab.Preview -> "preview"
    }

internal data class SubmitSheet(
    val closed: Boolean,
    val isMovie: Boolean,
    val absentAllowed: Boolean,
    val tab: SubmitPanelTab,
    val absent: Boolean,
    val handle: MarkHandle,
    val popoverOpen: Boolean,
    val startSec: Double?,
    val endSec: Double?,
    val startShowsTenth: Boolean,
    val endShowsTenth: Boolean,
    val durationSec: Double,
    val playheadSec: Double,
    val submitting: Boolean,
    val sent: Boolean,
    val keyProblem: Boolean,
    val blocked: Boolean,
    val drafts: Map<SubmitPanelTab, MarkClocks>,
    val tenthDrafts: Map<SubmitPanelTab, Pair<Boolean, Boolean>>,
)

internal fun openSubmitSheet(
    isMovie: Boolean = false,
    absentAllowed: Boolean = true,
    durationSec: Double = 100.0,
): SubmitSheet = SubmitSheet(
    closed = false,
    isMovie = isMovie,
    absentAllowed = absentAllowed,
    tab = SubmitPanelTab.Intro,
    absent = false,
    handle = MarkHandle.Start,
    popoverOpen = false,
    startSec = 10.0,
    endSec = 40.0,
    startShowsTenth = false,
    endShowsTenth = false,
    durationSec = durationSec,
    playheadSec = 20.0,
    submitting = false,
    sent = false,
    keyProblem = false,
    blocked = false,
    drafts = emptyMap(),
    tenthDrafts = emptyMap(),
)

internal sealed interface SubmitSheetEvent {
    data class SelectTab(val tab: SubmitPanelTab) : SubmitSheetEvent
    data object TapSameTab : SubmitSheetEvent
    data object TapNoneChip : SubmitSheetEvent
    data object TapStart : SubmitSheetEvent
    data object TapEnd : SubmitSheetEvent
    data object DismissPopover : SubmitSheetEvent
    data object SetToNow : SubmitSheetEvent
    data class Nudge(val deltaSec: Double, val holding: Boolean) : SubmitSheetEvent
    data class DragHandle(val handle: MarkHandle, val seconds: Double) : SubmitSheetEvent
    data object ResetMarks : SubmitSheetEvent
    data object Submit : SubmitSheetEvent
    data object SubmitSuccess : SubmitSheetEvent
    data object SubmitFailure : SubmitSheetEvent
    data object Close : SubmitSheetEvent
}

internal data class SubmitSheetShape(
    val tab: SubmitPanelTab,
    val absent: Boolean,
    val handle: MarkHandle,
    val popover: Boolean,
    val valid: Boolean,
    val submitting: Boolean,
)

internal fun SubmitSheet.rangeValid(): Boolean =
    markProblems(tab.segmentId, startSec, endSec, durationSec.takeIf { it > 0.0 }).isEmpty()

internal fun SubmitSheet.canSubmit(): Boolean = !closed && markSubmitEnabled(
    sent = sent,
    submitting = submitting,
    keyProblem = keyProblem,
    blocked = blocked,
    absent = absent,
    absentAllowed = absentAllowed,
    timeOk = rangeValid(),
)

internal fun SubmitSheet.showsSegmentMarks(): Boolean = !closed && !absent

internal fun SubmitSheet.showsNudges(): Boolean = !closed && !absent && popoverOpen

internal fun SubmitSheet.shape(): SubmitSheetShape =
    SubmitSheetShape(tab, absent, handle, popoverOpen, rangeValid(), submitting)

internal fun submitSheetShapeAllowed(shape: SubmitSheetShape, absentAllowed: Boolean): Boolean {
    if (shape.popover && shape.absent) return false
    if (shape.popover && shape.submitting) return false
    if (shape.submitting && !shape.absent && !shape.valid) return false
    if (shape.submitting && shape.absent && !absentAllowed) return false
    return true
}

internal fun reduceSubmitSheet(state: SubmitSheet, event: SubmitSheetEvent): SubmitSheet {
    if (state.closed) return state
    if (state.submitting) {
        return when (event) {
            SubmitSheetEvent.SubmitSuccess -> state.copy(submitting = false, sent = true)
            SubmitSheetEvent.SubmitFailure -> state.copy(submitting = false)
            else -> state
        }
    }
    return when (event) {
        is SubmitSheetEvent.SelectTab -> state.applyTab(event.tab)
        SubmitSheetEvent.TapSameTab -> state.applyTab(state.tab)
        SubmitSheetEvent.TapNoneChip -> if (state.absent) {
            state
        } else {
            state.copy(absent = absentAfterNoneChip(), sent = false, popoverOpen = false)
        }
        SubmitSheetEvent.TapStart -> state.togglePopover(MarkHandle.Start)
        SubmitSheetEvent.TapEnd -> state.togglePopover(MarkHandle.End)
        SubmitSheetEvent.DismissPopover -> state.copy(popoverOpen = false)
        SubmitSheetEvent.SetToNow -> if (state.absent || !state.popoverOpen) {
            state
        } else {
            state.writeClock(state.handle, state.playheadSec, showTenth = false)
        }
        is SubmitSheetEvent.Nudge -> if (state.absent || !state.popoverOpen) {
            state
        } else {
            val delta = if (event.holding && abs(event.deltaSec) == 1.0) {
                holdNudgeDelta(event.deltaSec)
            } else {
                event.deltaSec
            }
            val current = if (state.handle == MarkHandle.Start) state.startSec else state.endSec
            val showing = if (state.handle == MarkHandle.Start) state.startShowsTenth else state.endShowsTenth
            state.writeClock(
                state.handle,
                nudgeMarkSeconds(current, delta, state.durationSec),
                nudgeKeepsTenth(delta, showing),
            )
        }
        is SubmitSheetEvent.DragHandle -> if (state.absent) {
            state
        } else {
            state.copy(handle = event.handle).writeClock(event.handle, event.seconds, showTenth = false)
        }
        SubmitSheetEvent.ResetMarks -> state
            .writeClock(MarkHandle.Start, null, showTenth = false)
            .writeClock(MarkHandle.End, null, showTenth = false)
        SubmitSheetEvent.Submit -> if (state.canSubmit()) {
            state.copy(submitting = true, popoverOpen = false)
        } else {
            state
        }
        SubmitSheetEvent.SubmitSuccess, SubmitSheetEvent.SubmitFailure -> state
        SubmitSheetEvent.Close -> state.copy(closed = true)
    }
}

private fun SubmitSheet.applyTab(next: SubmitPanelTab): SubmitSheet {
    if (next.segmentId !in markSegmentTypes(isMovie)) return this
    return when (segmentTap(tab.segmentId, next.segmentId, absent)) {
        SegmentTap.Stay -> this
        SegmentTap.ReturnToEditing -> copy(absent = false, sent = false, popoverOpen = false)
        SegmentTap.Switch -> switchTab(next)
    }
}

private fun SubmitSheet.switchTab(next: SubmitPanelTab): SubmitSheet {
    val saved = drafts + (tab to MarkClocks(startSec, endSec))
    val savedTenths = tenthDrafts + (tab to (startShowsTenth to endShowsTenth))
    val clocks = saved[next]
    val tenths = savedTenths[next]
    return copy(
        tab = next,
        absent = false,
        sent = false,
        popoverOpen = false,
        handle = MarkHandle.Start,
        startSec = clocks?.startSec,
        endSec = clocks?.endSec,
        startShowsTenth = tenths?.first == true,
        endShowsTenth = tenths?.second == true,
        drafts = saved,
        tenthDrafts = savedTenths,
    )
}

private fun SubmitSheet.writeClock(handle: MarkHandle, seconds: Double?, showTenth: Boolean): SubmitSheet {
    val clamped = seconds?.let { clampMarkTimeToDuration(it, durationSec.takeIf { it > 0.0 }) }
    val tenth = clamped?.let { markClockShowsTenth(it, showTenth) } == true
    val start = if (handle == MarkHandle.Start) clamped else startSec
    val end = if (handle == MarkHandle.End) clamped else endSec
    val startTenth = if (handle == MarkHandle.Start) tenth else startShowsTenth
    val endTenth = if (handle == MarkHandle.End) tenth else endShowsTenth
    return copy(
        startSec = start,
        endSec = end,
        startShowsTenth = startTenth,
        endShowsTenth = endTenth,
        absent = absentAfterClockEdit(),
        sent = false,
        drafts = drafts + (tab to MarkClocks(start, end)),
        tenthDrafts = tenthDrafts + (tab to (startTenth to endTenth)),
    )
}

private fun SubmitSheet.togglePopover(next: MarkHandle): SubmitSheet {
    if (closed || absent || submitting) return this
    val open = !(popoverOpen && handle == next)
    return copy(handle = next, popoverOpen = open)
}

internal data class SubmitBarInsets(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal data class SubmitBarBounds(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

internal const val SubmitBarPreferredHeight = 64f

internal const val SubmitBarMaxVideoFraction = 0.125f

internal fun submitBarBounds(
    screenWidth: Float,
    screenHeight: Float,
    insets: SubmitBarInsets,
): SubmitBarBounds {
    val videoHeight = screenHeight.coerceAtLeast(0f)
    val safeHeight = (screenHeight - insets.top - insets.bottom).coerceAtLeast(0f)
    val height = minOf(
        SubmitBarPreferredHeight,
        videoHeight * SubmitBarMaxVideoFraction,
        safeHeight,
    ).coerceAtLeast(0f)
    val left = insets.left.coerceAtLeast(0f)
    val width = (screenWidth - left - insets.right.coerceAtLeast(0f)).coerceAtLeast(0f)
    val top = (screenHeight - insets.bottom - height).coerceAtLeast(insets.top)
    return SubmitBarBounds(left = left, top = top, width = width, height = height)
}

internal data class SubmitSegmentFractions(
    val start: Float,
    val end: Float,
)

internal fun submitSegmentMarkFractions(
    open: Boolean,
    absent: Boolean,
    startSec: Double?,
    endSec: Double?,
    durationSec: Double,
): SubmitSegmentFractions? {
    if (!open || absent || durationSec <= 0.0) return null
    val start = startSec ?: return null
    val end = endSec ?: return null
    return SubmitSegmentFractions(
        start = (start / durationSec).toFloat().coerceIn(0f, 1f),
        end = (end / durationSec).toFloat().coerceIn(0f, 1f),
    )
}
