package com.nuvio.app.features.analysis

import com.nuvio.app.features.player.sameCut
import kotlin.math.abs

/**
 * Finds audio shared by two episodes (an intro or the end credits) from their sub-fingerprints,
 * the way Jellyfin's intro-skipper does with chromaprint: try every alignment of the two
 * sequences and keep the longest run of near-identical frames along one alignment.
 *
 * A frame matches when the two 32-bit sub-fingerprints differ in at most [maxBitErrors] bits;
 * short dropouts of up to [maxGapFrames] are bridged.
 */
internal object FingerprintMatcher {
    data class SharedSegment(
        val startA: Int,
        val endA: Int,
        val startB: Int,
        val endB: Int,
        /** Mean Hamming distance of the frames that matched inside this run. */
        val avgBitErrors: Double = 0.0,
    ) {
        val length: Int get() = endA - startA
    }

    fun longestSharedSegment(
        a: IntArray,
        b: IntArray,
        minFrames: Int,
        maxFrames: Int = Int.MAX_VALUE,
        maxBitErrors: Int = 6,
        maxGapFrames: Int = 5,
    ): SharedSegment? {
        if (a.isEmpty() || b.isEmpty()) return null
        var best: SharedSegment? = null
        // shift = indexB - indexA
        for (shift in -(a.size - 1) until b.size) {
            val startI = maxOf(0, -shift)
            val endI = minOf(a.size, b.size - shift)
            if (endI - startI < minFrames) continue
            var runStart = -1
            var lastMatch = -1
            var bitErrorSum = 0
            var matchCount = 0
            var i = startI
            while (i < endI) {
                val diff = (a[i] xor b[i + shift]).countOneBits()
                if (diff <= maxBitErrors) {
                    if (runStart < 0 || i - lastMatch > maxGapFrames + 1) {
                        runStart = i
                        bitErrorSum = 0
                        matchCount = 0
                    }
                    bitErrorSum += diff
                    matchCount++
                    lastMatch = i
                    val length = lastMatch + 1 - runStart
                    if (length >= minFrames && length <= maxFrames && length > (best?.length ?: 0) && matchCount > 0) {
                        best = SharedSegment(
                            runStart,
                            lastMatch + 1,
                            runStart + shift,
                            lastMatch + 1 + shift,
                            bitErrorSum.toDouble() / matchCount,
                        )
                    }
                }
                i++
            }
        }
        return best
    }
}

/** Start times within this window count as the same intro, credits, or recap hit. */
internal const val SEGMENT_AGREE_MS = 2_000L

/** Intro and credits found for one episode by matching against other episodes of its season. */
internal data class DetectedSegments(
    val introStartMs: Long?,
    val introEndMs: Long?,
    val creditsStartMs: Long?,
    val creditsEndMs: Long?,
    val introConfidence: SegmentConfidence? = null,
    val creditsConfidence: SegmentConfidence? = null,
)

internal fun List<RejectedSegment>.covers(type: String, startMs: Long?, endMs: Long?): Boolean {
    if (startMs == null || endMs == null) return false
    return any { rejected ->
        rejected.type == type &&
            abs(rejected.startMs - startMs) <= SEGMENT_AGREE_MS &&
            abs(rejected.endMs - endMs) <= SEGMENT_AGREE_MS
    }
}

/**
 * Writes [detected] onto this episode. A user mark for this cut always wins. A stored range stays
 * unless the new score is strictly higher. A range the user already moved off is dropped.
 */
internal fun MediaAnalysisResult.applyingDetection(detected: DetectedSegments): MediaAnalysisResult {
    val intro = appliedMark("intro")?.let { mark ->
        val changed = introStartMs != mark.startMs || introEndMs != mark.endMs
        SegmentChoice(mark.startMs, mark.endMs, if (changed) null else introConfidence)
    } ?: chooseSegment(
        currentStart = introStartMs,
        currentEnd = introEndMs,
        currentConfidence = introConfidence,
        detectedStart = detected.introStartMs,
        detectedEnd = detected.introEndMs,
        detectedConfidence = detected.introConfidence,
        storedIsRejected = rejectedSegments.covers("intro", introStartMs, introEndMs),
    )
    val credits = appliedMark("outro")?.let { mark ->
        val changed = creditsStartMs != mark.startMs || creditsEndMs != mark.endMs
        SegmentChoice(mark.startMs, mark.endMs, if (changed) null else creditsConfidence)
    } ?: chooseSegment(
        currentStart = creditsStartMs,
        currentEnd = creditsEndMs,
        currentConfidence = creditsConfidence,
        detectedStart = detected.creditsStartMs,
        detectedEnd = detected.creditsEndMs,
        detectedConfidence = detected.creditsConfidence,
        storedIsRejected = rejectedSegments.covers("outro", creditsStartMs, creditsEndMs),
    )
    val introChanged = intro.startMs != introStartMs || intro.endMs != introEndMs
    var updated = copy(
        introStartMs = intro.startMs,
        introEndMs = intro.endMs,
        introConfidence = intro.confidence,
        creditsStartMs = credits.startMs,
        creditsEndMs = credits.endMs,
        creditsConfidence = credits.confidence,
    )
    if (introChanged) {
        updated = updated.copy(derivedVersion = 0)
        if (updated.appliedMark("recap") == null) {
            updated = updated.copy(recapStartMs = null, recapEndMs = null)
        }
    }
    return updated
}

private data class SegmentChoice(
    val startMs: Long?,
    val endMs: Long?,
    val confidence: SegmentConfidence?,
)

private fun chooseSegment(
    currentStart: Long?,
    currentEnd: Long?,
    currentConfidence: SegmentConfidence?,
    detectedStart: Long?,
    detectedEnd: Long?,
    detectedConfidence: SegmentConfidence?,
    storedIsRejected: Boolean,
): SegmentChoice {
    val missing = currentStart == null || currentEnd == null || storedIsRejected
    if (detectedStart == null || detectedEnd == null || detectedConfidence == null) {
        return if (storedIsRejected) SegmentChoice(null, null, null) else SegmentChoice(currentStart, currentEnd, currentConfidence)
    }
    if (missing || (currentConfidence != null && detectedConfidence.strictlyBeats(currentConfidence))) {
        return SegmentChoice(detectedStart, detectedEnd, detectedConfidence)
    }
    return SegmentChoice(currentStart, currentEnd, currentConfidence)
}

private fun MediaAnalysisResult.appliedMark(type: String): UserSegment? {
    val mark = userSegments[type] ?: return null
    return mark.takeIf { sameCut(it.durationMs, durationMs) }
}

internal object SeasonSegmentDetector {
    private const val MIN_INTRO_MS = 15_000L
    private const val MAX_INTRO_MS = 150_000L
    private const val MIN_CREDITS_MS = 15_000L
    private const val CREDITS_END_SNAP_MS = 10_000L

    /**
     * Intro and credits of [episode] against [others]. With three or more siblings the start
     * time needs two agreeing matches; fewer siblings keep the single longest run.
     */
    fun detect(episode: MediaAnalysisResult, others: List<MediaAnalysisResult>): DetectedSegments {
        val head = decodeFingerprint(episode.headFingerprint)
        val tail = decodeFingerprint(episode.tailFingerprint)
        val step = episode.fingerprintStepMs.coerceAtLeast(1)
        val intros = mutableListOf<TimedMatch>()
        val credits = mutableListOf<TimedMatch>()
        var comparable = 0
        for (other in others) {
            if (other.fingerprintStepMs != episode.fingerprintStepMs) continue
            comparable++
            introMatch(head, decodeFingerprint(other.headFingerprint), step)?.let { intros += it }
            creditsMatch(episode, tail, decodeFingerprint(other.tailFingerprint), step)?.let { credits += it }
        }
        val intro = vote(
            intros.filterNot { episode.rejectedSegments.covers("intro", it.startMs, it.endMs) },
            comparable,
        )
        val credit = vote(
            credits.filterNot { episode.rejectedSegments.covers("outro", it.startMs, it.endMs) },
            comparable,
        )
        return DetectedSegments(
            introStartMs = intro?.first?.startMs,
            introEndMs = intro?.first?.endMs,
            creditsStartMs = credit?.first?.startMs,
            creditsEndMs = credit?.first?.endMs,
            introConfidence = intro?.second,
            creditsConfidence = credit?.second,
        )
    }

    private fun introMatch(head: IntArray, otherHead: IntArray, step: Int): TimedMatch? {
        val segment = FingerprintMatcher.longestSharedSegment(
            head,
            otherHead,
            minFrames = (MIN_INTRO_MS / step).toInt(),
            maxFrames = (MAX_INTRO_MS / step).toInt(),
        ) ?: return null
        return TimedMatch(
            startMs = segment.startA * step.toLong(),
            endMs = segment.endA * step.toLong(),
            length = segment.length,
            avgBitErrors = segment.avgBitErrors,
        )
    }

    private fun creditsMatch(
        episode: MediaAnalysisResult,
        tail: IntArray,
        otherTail: IntArray,
        step: Int,
    ): TimedMatch? {
        val segment = FingerprintMatcher.longestSharedSegment(
            tail,
            otherTail,
            minFrames = (MIN_CREDITS_MS / step).toInt(),
        ) ?: return null
        val start = episode.tailStartMs + segment.startA * step.toLong()
        val rawEnd = episode.tailStartMs + segment.endA * step.toLong()
        val end = if (episode.durationMs > 0L && episode.durationMs - rawEnd < CREDITS_END_SNAP_MS) {
            episode.durationMs
        } else {
            rawEnd
        }
        return TimedMatch(start, end, segment.length, segment.avgBitErrors)
    }
}

private data class TimedMatch(
    val startMs: Long,
    val endMs: Long,
    val length: Int,
    val avgBitErrors: Double,
)

/**
 * [siblingCount] under 3: the longest run, scored by how many starts sit within [SEGMENT_AGREE_MS].
 * Three or more: a start-time cluster of at least two siblings. Ties prefer the cleaner cluster.
 */
private fun vote(matches: List<TimedMatch>, siblingCount: Int): Pair<TimedMatch, SegmentConfidence>? {
    if (matches.isEmpty()) return null
    if (siblingCount < 3) {
        val best = matches.maxBy { it.length }
        val agreeing = matches.filter { abs(it.startMs - best.startMs) <= SEGMENT_AGREE_MS }
        return best to agreeing.confidence()
    }
    val sorted = matches.sortedBy { it.startMs }
    var bestGroup: List<TimedMatch>? = null
    for (i in sorted.indices) {
        val group = ArrayList<TimedMatch>()
        for (j in i until sorted.size) {
            if (sorted[j].startMs - sorted[i].startMs > SEGMENT_AGREE_MS) break
            group += sorted[j]
        }
        if (group.size < 2) continue
        if (bestGroup == null || group.outranks(bestGroup)) bestGroup = group
    }
    val chosen = bestGroup ?: return null
    val representative = chosen.minWith(compareBy<TimedMatch> { it.avgBitErrors }.thenByDescending { it.length })
    return representative to chosen.confidence()
}

private fun List<TimedMatch>.confidence(): SegmentConfidence = SegmentConfidence(
    agreeingSiblings = size,
    avgBitErrors = sumOf { it.avgBitErrors } / size,
)

private fun List<TimedMatch>.outranks(other: List<TimedMatch>): Boolean {
    if (size != other.size) return size > other.size
    val errors = sumOf { it.avgBitErrors } / size
    val otherErrors = other.sumOf { it.avgBitErrors } / other.size
    if (errors != otherErrors) return errors < otherErrors
    return maxOf { it.length } > other.maxOf { it.length }
}

/**
 * "Previously on" recaps: the stretch before the intro that replays material from earlier
 * episodes. The part of this episode before its intro is matched, at any alignment, against what
 * is stored of the other episodes (their first 10 and last 8 minutes, their own intros masked
 * out). Recaps are a montage of short clips, so a few seconds of shared audio are enough; the
 * recap is then taken to run from the start (when the first match is near it) to the intro.
 *
 * Needs a detected intro to bound the recap. Clips from the middle of earlier episodes are not
 * stored, so a recap made only of those is not found.
 */
internal object RecapDetector {
    private const val MIN_CLIP_MS = 4_000L
    private const val MIN_RECAP_MS = 10_000L
    private const val MAX_RECAP_MS = 240_000L
    private const val SNAP_TO_START_MS = 20_000L
    private const val SNAP_TO_INTRO_MS = 60_000L

    fun detect(episode: MediaAnalysisResult, others: List<MediaAnalysisResult>): Pair<Long, Long>? {
        val step = episode.fingerprintStepMs.coerceAtLeast(1).toLong()
        val introStart = episode.introStartMs ?: return null
        if (introStart < MIN_RECAP_MS) return null
        val head = decodeFingerprint(episode.headFingerprint)
        val windowFrames = minOf(head.size, (minOf(introStart, MAX_RECAP_MS + SNAP_TO_START_MS) / step).toInt())
        val minFrames = (MIN_CLIP_MS / step).toInt()
        if (windowFrames < minFrames) return null
        val window = head.copyOfRange(0, windowFrames)
        val hits = mutableListOf<RecapHit>()
        var comparable = 0
        for ((index, other) in others.withIndex()) {
            if (other.fingerprintStepMs.toLong() != step) continue
            comparable++
            val sequences = listOf(
                maskRange(decodeFingerprint(other.headFingerprint), other.introStartMs, other.introEndMs, step, 0L),
                maskRange(decodeFingerprint(other.tailFingerprint), other.creditsStartMs, other.creditsEndMs, step, other.tailStartMs),
            )
            for (sequence in sequences) {
                val segment = FingerprintMatcher.longestSharedSegment(window, sequence, minFrames = minFrames) ?: continue
                val startMs = segment.startA * step
                val endMs = segment.endA * step
                if (episode.rejectedSegments.covers("recap", startMs, endMs)) continue
                hits += RecapHit(index, segment.startA, segment.endA)
            }
        }
        val kept = if (comparable >= 3) hits.agreed(step) else hits
        if (kept.isEmpty()) return null
        val firstStart = kept.minOf { it.startFrame }
        val lastEnd = kept.maxOf { it.endFrame }
        val startMs = (firstStart * step).let { if (it <= SNAP_TO_START_MS) 0L else it }
        val lastMs = lastEnd * step
        val endMs = if (introStart >= lastMs && introStart - lastMs <= SNAP_TO_INTRO_MS) introStart else lastMs
        if (endMs - startMs !in MIN_RECAP_MS..MAX_RECAP_MS) return null
        return startMs to endMs
    }

    private data class RecapHit(val sibling: Int, val startFrame: Int, val endFrame: Int)

    /** Hits whose start sits in a cluster of at least two siblings. */
    private fun List<RecapHit>.agreed(step: Long): List<RecapHit> {
        val window = (SEGMENT_AGREE_MS / step).toInt().coerceAtLeast(1)
        return filter { hit ->
            filter { abs(it.startFrame - hit.startFrame) <= window }
                .map { it.sibling }
                .distinct()
                .size >= 2
        }
    }

    /** Replaces frames in [startMs, endMs) (relative to [offsetMs]) with values that never match. */
    private fun maskRange(frames: IntArray, startMs: Long?, endMs: Long?, step: Long, offsetMs: Long): IntArray {
        if (startMs == null || endMs == null || frames.isEmpty()) return frames
        val from = ((startMs - offsetMs) / step).toInt().coerceIn(0, frames.size)
        val to = ((endMs - offsetMs) / step).toInt().coerceIn(0, frames.size)
        if (to <= from) return frames
        val out = frames.copyOf()
        for (i in from until to) out[i] = (i * -0x61c88647) xor 0x5A5A5A5A
        return out
    }
}
