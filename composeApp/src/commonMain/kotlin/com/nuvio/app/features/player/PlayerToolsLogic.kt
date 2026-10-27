package com.nuvio.app.features.player

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
internal const val IntroMaxSec = 300.0
internal const val RecapMinSec = 5.0
internal const val RecapMaxSec = 1_200.0
internal const val CreditsMinSec = 5.0
internal const val CreditsMaxSec = 1_800.0

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
    else -> MarkLimits(CreditsMinSec, CreditsMaxSec)
}

internal fun markSegmentTypes(isMovie: Boolean): List<String> =
    if (isMovie) listOf("intro", "outro") else listOf("intro", "recap", "outro", "preview")

internal fun markSegmentOffersStartAtZero(segmentType: String): Boolean =
    markSegmentId(segmentType) == "intro" || markSegmentId(segmentType) == "recap"

internal fun markSegmentOffersRunsToEnd(segmentType: String): Boolean =
    markSegmentId(segmentType) == "outro" || markSegmentId(segmentType) == "preview"

/**
 * Hard checks. 5.0 s and 300.0 s are valid; 4.9 s and 300.1 s are not.
 * An unknown duration (null or 0) does not count as past the end.
 * A missing clock is [MarkProblem.EndNotAfterStart], so Submit stays disabled.
 */
internal fun markProblems(
    segmentType: String,
    startSec: Double?,
    endSec: Double?,
    durationSec: Double?,
): List<MarkProblem> {
    val problems = mutableListOf<MarkProblem>()
    if (startSec == null || endSec == null || endSec <= startSec) {
        problems += MarkProblem.EndNotAfterStart
    }
    val duration = durationSec?.takeIf { it > 0.0 }
    if (duration != null &&
        ((startSec != null && startSec > duration) || (endSec != null && endSec > duration))
    ) {
        problems += MarkProblem.PastDuration
    }
    if (startSec != null && endSec != null && endSec > startSec) {
        val length = endSec - startSec
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

/** "88.6 s" — one decimal, matching the marking strip. */
internal fun markLengthLabel(lengthSec: Double): String {
    val tenths = (lengthSec * 10.0).roundToInt() / 10.0
    val text = if (tenths == tenths.toInt().toDouble()) {
        "${tenths.toInt()}.0"
    } else {
        tenths.toString()
    }
    return "$text s"
}

/**
 * Clock shown on Mark start / Mark end. One decimal so a 0.1 s nudge is visible.
 * Hours are unpadded (`0:01:41.0`), matching the marking strip.
 */
internal fun formatMarkClock(seconds: Double): String {
    val clamped = seconds.coerceAtLeast(0.0)
    val totalTenths = (clamped * 10.0).roundToInt().coerceAtLeast(0)
    val tenths = totalTenths % 10
    val whole = totalTenths / 10
    val hours = whole / 3600
    val minutes = (whole % 3600) / 60
    val secs = whole % 60
    return "$hours:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}.$tenths"
}

internal fun parseMarkClock(text: String): Double? = parseTimeToSeconds(text)

internal fun nudgeMarkSeconds(currentSec: Double?, deltaSec: Double, durationSec: Double?): Double {
    val max = durationSec?.takeIf { it > 0.0 } ?: (99 * 3600 + 59 * 60 + 59).toDouble()
    val stepped = ((currentSec ?: 0.0) + deltaSec)
    val tenths = (stepped * 10.0).roundToInt() / 10.0
    return tenths.coerceIn(0.0, max)
}

/** Credits / Preview shortcut: the end handle sits on the last frame. */
internal fun runsToEndSec(durationSec: Double): Double = durationSec.coerceAtLeast(0.0)

/**
 * Clocks after a chip change. A saved draft wins, including a blank draft.
 * A known interval is used only when it is the same kind of segment.
 * Movie Credits never take the intro (or any other) times.
 */
internal fun clocksForSegment(
    segmentType: String,
    draft: MarkClocks?,
    knownStartSec: Double?,
    knownEndSec: Double?,
    knownSegmentType: String?,
    isMovie: Boolean,
): MarkClocks {
    if (draft != null) return draft
    if (knownStartSec == null || knownEndSec == null || knownSegmentType == null) {
        return MarkClocks(null, null)
    }
    val wanted = markSegmentId(segmentType)
    val known = markSegmentId(knownSegmentType)
    if (wanted != known) return MarkClocks(null, null)
    if (isMovie && wanted == "outro" && known != "outro") return MarkClocks(null, null)
    return MarkClocks(knownStartSec, knownEndSec)
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

/**
 * Preview arms once the playhead is inside the range (the seek has landed),
 * then stops on the end frame. A stale playhead from before the seek does not arm it.
 */
internal fun markPreviewArmed(positionMs: Long, startMs: Long, endMs: Long): Boolean =
    endMs > startMs && positionMs in startMs until endMs

internal fun shouldStopMarkPreview(positionMs: Long, endMs: Long, armed: Boolean): Boolean =
    armed && positionMs >= endMs

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
