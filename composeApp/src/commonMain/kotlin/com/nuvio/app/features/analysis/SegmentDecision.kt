package com.nuvio.app.features.analysis

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.diagnostics.DiagnosticsLogger
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.player.skip.TheIntroDb
import com.nuvio.app.features.player.skip.skipLookupCacheKey

/**
 * One compact diagnostics line per segment type for the episode just analysed.
 * The text is tokens only: no titles, no URLs, no account keys.
 */
internal data class SegmentDecision(
    val type: String,
    val candidates: Int,
    val signal: String,
    val accepted: Boolean,
    val startMs: Long?,
    val endMs: Long?,
    val confidence: String,
    val why: String,
    val thresholds: String,
) {
    fun line(fileId: String): String {
        val id = fileId
            .replace(Regex("""tt\d{6,9}""", RegexOption.IGNORE_CASE), "show")
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(80)
            .ifEmpty { "episode" }
        return "segment id=$id type=${token(type)} candidates=$candidates signal=${token(signal)} " +
            "accepted=${if (accepted) 1 else 0} startMs=${startMs ?: -1} endMs=${endMs ?: -1} " +
            "confidence=${token(confidence)} why=${token(why)} thresholds=${token(thresholds)}"
    }

    private fun token(raw: String): String =
        raw.lowercase()
            .replace(Regex("""tt\d{6,9}"""), "")
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""[^a-z0-9_.,:+=-]"""), "")
}

internal class SegmentTrace {
    val decisions = mutableListOf<SegmentDecision>()

    fun add(decision: SegmentDecision) {
        decisions += decision
    }

    fun replace(type: String, decision: SegmentDecision) {
        val index = decisions.indexOfFirst { it.type == type }
        if (index >= 0) decisions[index] = decision else decisions += decision
    }

    fun has(type: String): Boolean = decisions.any { it.type == type }
}

internal fun logSegmentDecisions(fileId: String, decisions: List<SegmentDecision>) {
    val order = listOf("intro", "recap", "credits", "preview")
    val lines = decisions.sortedBy { order.indexOf(it.type).let { index -> if (index < 0) order.size else index } }
    for (decision in lines) {
        val line = decision.line(fileId)
        InAppLogger.info("Analysis", line)
        DiagnosticsLogger.logPerformance(line)
    }
}

internal fun chapterDecision(type: String, startMs: Long?, endMs: Long?): SegmentDecision = SegmentDecision(
    type = type,
    candidates = 1,
    signal = "chapters",
    accepted = startMs != null && endMs != null && endMs > startMs,
    startMs = startMs,
    endMs = endMs,
    confidence = "source=chapters",
    why = "chapter-kept",
    thresholds = "source-rank=2",
)

internal fun detectionOffDecision(type: String): SegmentDecision = SegmentDecision(
    type = type,
    candidates = 0,
    signal = "none",
    accepted = false,
    startMs = null,
    endMs = null,
    confidence = "none",
    why = "detection-off",
    thresholds = "flag=off",
)

internal fun userMarkDecision(type: String, startMs: Long, endMs: Long): SegmentDecision = SegmentDecision(
    type = type,
    candidates = 1,
    signal = "user-mark",
    accepted = endMs > startMs,
    startMs = startMs,
    endMs = endMs,
    confidence = "source=user",
    why = "user-mark-kept",
    thresholds = "source-rank=0",
)

internal fun missingDecision(type: String, startMs: Long?, endMs: Long?): SegmentDecision {
    val stored = startMs != null && endMs != null && endMs > startMs
    return SegmentDecision(
        type = type,
        candidates = if (stored) 1 else 0,
        signal = if (stored) "stored" else "none",
        accepted = stored,
        startMs = startMs,
        endMs = endMs,
        confidence = if (stored) "stored=1" else "none",
        why = if (stored) "stored-earlier-pass" else "not-run",
        thresholds = "none",
    )
}

/** TheIntroDB times already fetched for playback. No new request and no new service. */
internal data class SegmentPriors(
    val intro: Pair<Long, Long>? = null,
    val credits: Pair<Long, Long>? = null,
    val recap: Pair<Long, Long>? = null,
    val preview: Pair<Long, Long>? = null,
)

internal fun cachedSegmentPriors(
    parentMetaId: String,
    season: Int,
    episode: Int,
    durationMs: Long,
): SegmentPriors {
    val imdb = Regex("""tt\d{6,9}""").find(parentMetaId)?.value ?: return SegmentPriors()
    val cache = SkipIntroRepository.skipIntervalCache
    val base = "$imdb:$season:$episode"
    val intervals = cache.lookup(skipLookupCacheKey(base, durationMs)) ?: cache.lookup(base) ?: return SegmentPriors()
    fun range(types: Set<String>): Pair<Long, Long>? =
        intervals.firstOrNull {
            it.provider == TheIntroDb.PROVIDER && it.type.lowercase() in types && it.endTime > it.startTime
        }?.let { (it.startTime * 1000.0).toLong() to (it.endTime * 1000.0).toLong() }
    return SegmentPriors(
        intro = range(setOf("intro", "op", "mixed-op", "opening")),
        credits = range(setOf("outro", "ed", "mixed-ed", "credits", "ending")),
        recap = range(setOf("recap")),
        preview = range(setOf("preview")),
    )
}

internal fun episodeNumberFromKey(key: String): Int? =
    Regex("""e(\d+)$""").find(key)?.groupValues?.get(1)?.toIntOrNull()

/** The episode [delta] steps away in the same season, matched by the stored key. */
internal fun siblingByDelta(
    episode: MediaAnalysisResult,
    others: List<MediaAnalysisResult>,
    delta: Int,
): MediaAnalysisResult? {
    val number = episodeNumberFromKey(episode.key) ?: return null
    val target = number + delta
    if (target <= 0) return null
    val digits = number.toString().length
    if (episode.key.length <= digits) return null
    val wanted = episode.key.dropLast(digits) + target.toString()
    return others.firstOrNull { it.key == wanted }
}

internal fun bitsText(value: Double): String {
    val hundredths = (value * 100.0).toInt().coerceIn(0, 9999)
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}

/**
 * TheIntroDB dual-credits window. A shared clip that starts here is the intro.
 * Credits that finish at the end of the file stay credits, including on a short episode
 * whose ending happens to begin inside this window.
 */
internal const val OPENING_CREDITS_WINDOW_MS = 10 * 60_000L

/** Same end slack as chapter credits: a range that finishes this close to the file is an ending. */
internal const val END_CREDITS_TAIL_MS = 4 * 60_000L

/**
 * Dialog-overlay gate. The analyser does not store a separate per-range speech-energy field.
 * It stores one downmix RMS byte per step on [MediaAnalysisResult.energy] (0 = -90 dBFS,
 * 255 = 0 dBFS), the same track subtitle sync uses. This check reads that track.
 *
 * [DIALOG_OVERLAY_LEVEL] is 176, about -28 dBFS. [DIALOG_OVERLAY_MIN_PERCENT] of the steps
 * inside the candidate have to reach it, and the candidate has to cover at least
 * [DIALOG_OVERLAY_MIN_SPAN_MS]. A theme with quieter bars stays under the fraction, so a
 * normal intro or credits range is still prefilled. No energy track means the check does
 * not fire. Tune the two constants; both are copied onto the decision line.
 */
internal const val DIALOG_OVERLAY_LEVEL = 176
internal const val DIALOG_OVERLAY_MIN_PERCENT = 85
internal const val DIALOG_OVERLAY_MIN_SPAN_MS = 5_000L

/** An intro candidate that starts at this time can be a cold open. */
internal const val COLD_OPEN_START_MS = 0L

/** Sibling intros above this count as starting later than a candidate at [COLD_OPEN_START_MS]. */
internal const val COLD_OPEN_LATER_THAN_MS = 2_000L

/** Plural siblings: one later intro is not enough to call a 0-start a cold open. */
internal const val COLD_OPEN_MIN_LATER_SIBLINGS = 2

/**
 * True when [startMs, endMs) is an opening clip rather than end credits.
 * End credits still count when they finish near the file end and begin in the last 60%.
 */
internal fun isOpeningCreditsRange(startMs: Long, endMs: Long, durationMs: Long): Boolean {
    if (endMs <= startMs || startMs < 0L) return false
    if (startMs >= OPENING_CREDITS_WINDOW_MS) return false
    if (durationMs <= 0L) return true
    val reachesEnd = durationMs - endMs <= END_CREDITS_TAIL_MS
    val inEndingWindow = startMs * 5 >= durationMs * 2
    return !(reachesEnd && inEndingWindow)
}

/**
 * True when the stored RMS stays at or above [DIALOG_OVERLAY_LEVEL] for most of the range.
 * False when the track is missing, too short, or only partly loud.
 */
internal fun speechEnergyHigh(energy: String?, stepMs: Int, startMs: Long, endMs: Long): Boolean {
    if (endMs <= startMs) return false
    val levels = decodeEnergyLevels(energy)
    if (levels.isEmpty()) return false
    val step = stepMs.coerceAtLeast(1).toLong()
    if (endMs - startMs < DIALOG_OVERLAY_MIN_SPAN_MS) return false
    val from = (startMs / step).toInt().coerceIn(0, levels.size)
    val to = ((endMs + step - 1) / step).toInt().coerceIn(0, levels.size)
    val span = to - from
    if (span <= 0 || span * step < DIALOG_OVERLAY_MIN_SPAN_MS) return false
    var loud = 0
    for (index in from until to) {
        if (levels[index] >= DIALOG_OVERLAY_LEVEL) loud++
    }
    return loud * 100 >= span * DIALOG_OVERLAY_MIN_PERCENT
}

/**
 * True when [startMs] is a cold open: it sits at [COLD_OPEN_START_MS] and at least
 * [COLD_OPEN_MIN_LATER_SIBLINGS] siblings already have an intro that starts later.
 * A sibling intro at the start, or no stored sibling intros, keeps the candidate.
 */
internal fun coldOpenLikely(startMs: Long, siblings: List<MediaAnalysisResult>): Boolean {
    if (startMs != COLD_OPEN_START_MS) return false
    var later = 0
    var atStart = 0
    for (sibling in siblings) {
        val intro = sibling.introStartMs ?: continue
        if (intro > COLD_OPEN_LATER_THAN_MS) later++ else atStart++
    }
    return atStart == 0 && later >= COLD_OPEN_MIN_LATER_SIBLINGS
}
