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
