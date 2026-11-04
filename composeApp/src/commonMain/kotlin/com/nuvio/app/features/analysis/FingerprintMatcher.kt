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
        keepEnd: Boolean = false,
    ): SharedSegment? {
        if (a.isEmpty() || b.isEmpty()) return null
        var best: SharedSegment? = null
        var bestRun = 0
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
                    if (matchCount > 0 && length >= minFrames) {
                        val average = bitErrorSum.toDouble() / matchCount
                        if (keepEnd && length > bestRun) {
                            bestRun = length
                            val end = lastMatch + 1
                            val start = if (length > maxFrames) end - maxFrames else runStart
                            best = SharedSegment(start, end, start + shift, end + shift, average)
                        } else if (!keepEnd && length <= maxFrames && length > (best?.length ?: 0)) {
                            best = SharedSegment(runStart, lastMatch + 1, runStart + shift, lastMatch + 1 + shift, average)
                        }
                    }
                }
                i++
            }
        }
        return best
    }

    /**
     * Every shared run at least [minFrames] long, keeping the [limit] longest.
     * One pair can share a short theme and a longer one-off clip; the caller votes on all of them.
     * Same scan as [longestSharedSegment], so it does not add a second pass over the fingerprints.
     */
    fun topSharedSegments(
        a: IntArray,
        b: IntArray,
        minFrames: Int,
        maxFrames: Int = Int.MAX_VALUE,
        maxBitErrors: Int = 6,
        maxGapFrames: Int = 5,
        limit: Int = 4,
    ): List<SharedSegment> {
        if (a.isEmpty() || b.isEmpty() || minFrames <= 0 || limit <= 0) return emptyList()
        val found = mutableListOf<SharedSegment>()
        fun consider(segment: SharedSegment) {
            if (segment.length < minFrames || segment.length > maxFrames) return
            val near = found.indexOfFirst {
                kotlin.math.abs(it.startA - segment.startA) <= 20 && kotlin.math.abs(it.startB - segment.startB) <= 20
            }
            if (near >= 0) {
                if (segment.length > found[near].length) found[near] = segment
                return
            }
            if (found.size < limit) {
                found += segment
                return
            }
            val worst = found.indices.minBy { found[it].length }
            if (segment.length > found[worst].length) found[worst] = segment
        }
        for (shift in -(a.size - 1) until b.size) {
            val startI = maxOf(0, -shift)
            val endI = minOf(a.size, b.size - shift)
            if (endI - startI < minFrames) continue
            var runStart = -1
            var lastMatch = -1
            var bitErrorSum = 0
            var matchCount = 0
            var pending: SharedSegment? = null
            fun flush() {
                pending?.let { consider(it) }
                pending = null
            }
            var i = startI
            while (i < endI) {
                val diff = (a[i] xor b[i + shift]).countOneBits()
                if (diff <= maxBitErrors) {
                    if (runStart < 0 || i - lastMatch > maxGapFrames + 1) {
                        flush()
                        runStart = i
                        bitErrorSum = 0
                        matchCount = 0
                    }
                    bitErrorSum += diff
                    matchCount++
                    lastMatch = i
                    val length = lastMatch + 1 - runStart
                    if (matchCount > 0 && length >= minFrames && length <= maxFrames) {
                        val average = bitErrorSum.toDouble() / matchCount
                        pending = SharedSegment(runStart, lastMatch + 1, runStart + shift, lastMatch + 1 + shift, average)
                    }
                }
                i++
            }
            flush()
        }
        return found.sortedByDescending { it.length }
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

/**
 * TheIntroDB times may raise a score that already landed on the same range.
 * A different time, or no time, leaves the fingerprint result as it is.
 */
internal fun DetectedSegments.withTheIntroDbAgreement(
    intro: Pair<Long, Long>? = null,
    credits: Pair<Long, Long>? = null,
): DetectedSegments = copy(
    introConfidence = introConfidence.raisedWhen(agreesWith(introStartMs, introEndMs, intro)),
    creditsConfidence = creditsConfidence.raisedWhen(agreesWith(creditsStartMs, creditsEndMs, credits)),
)

private fun agreesWith(startMs: Long?, endMs: Long?, external: Pair<Long, Long>?): Boolean {
    if (startMs == null || endMs == null || external == null) return false
    return abs(startMs - external.first) <= SEGMENT_AGREE_MS && abs(endMs - external.second) <= SEGMENT_AGREE_MS
}

private fun SegmentConfidence?.raisedWhen(agrees: Boolean): SegmentConfidence? =
    if (this == null || !agrees) this else copy(theIntroDbAgrees = true)

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
    private const val MAX_CREDITS_MS = 180_000L
    private const val CREDITS_END_SNAP_MS = 10_000L

    /**
     * Intro and credits of [episode] against [others]. With three or more siblings the start
     * time needs two agreeing matches; fewer siblings keep the single longest run.
     */
    fun detect(
        episode: MediaAnalysisResult,
        others: List<MediaAnalysisResult>,
        theIntroDbIntro: Pair<Long, Long>? = null,
        theIntroDbCredits: Pair<Long, Long>? = null,
        trace: SegmentTrace? = null,
    ): DetectedSegments {
        val head = decodeFingerprint(episode.headFingerprint)
        val tail = decodeFingerprint(episode.tailFingerprint)
        val step = episode.fingerprintStepMs.coerceAtLeast(1)
        val intros = mutableListOf<TimedMatch>()
        val credits = mutableListOf<TimedMatch>()
        var comparable = 0
        for ((index, other) in others.withIndex()) {
            if (other.fingerprintStepMs != episode.fingerprintStepMs) continue
            comparable++
            intros += introMatches(head, decodeFingerprint(other.headFingerprint), step, index)
            creditsMatch(episode, tail, decodeFingerprint(other.tailFingerprint), step)?.let {
                credits += it.copy(sibling = index)
            }
        }
        val introPool = intros.filterNot { episode.rejectedSegments.covers("intro", it.startMs, it.endMs) }
        val creditPool = credits.filterNot { episode.rejectedSegments.covers("outro", it.startMs, it.endMs) }
        val introVote = vote(introPool, comparable)
        val intro = introVote ?: priorRescue(introPool, theIntroDbIntro)
        val credit = vote(creditPool, comparable)
        val detected = episode.snappingEdges(
            DetectedSegments(
                introStartMs = intro?.first?.startMs,
                introEndMs = intro?.first?.endMs,
                creditsStartMs = credit?.first?.startMs,
                creditsEndMs = credit?.first?.endMs,
                introConfidence = intro?.second,
                creditsConfidence = credit?.second,
            ),
        ).withTheIntroDbAgreement(theIntroDbIntro, theIntroDbCredits)
        trace?.add(
            segmentDecision(
                type = "intro",
                candidates = introPool.size,
                picked = detected.introStartMs != null,
                startMs = detected.introStartMs,
                endMs = detected.introEndMs,
                confidence = detected.introConfidence,
                why = introWhy(others.isEmpty(), comparable, introVote != null, intro != null),
                signal = introSignal(comparable, introVote != null, intro != null),
                thresholds = "minMs=$MIN_INTRO_MS,maxMs=$MAX_INTRO_MS,agreeMs=$SEGMENT_AGREE_MS,maxBits=6,minCluster=2",
            ),
        )
        trace?.add(
            segmentDecision(
                type = "credits",
                candidates = creditPool.size,
                picked = detected.creditsStartMs != null,
                startMs = detected.creditsStartMs,
                endMs = detected.creditsEndMs,
                confidence = detected.creditsConfidence,
                why = if (others.isEmpty()) "rejected-no-siblings" else if (detected.creditsStartMs != null) {
                    if (comparable < 3) "accepted-longest" else "accepted-cluster"
                } else {
                    "rejected-no-cluster"
                },
                signal = if (detected.creditsStartMs != null && comparable < 3) "fingerprint-longest" else "fingerprint-cluster",
                thresholds = "minMs=$MIN_CREDITS_MS,maxMs=$MAX_CREDITS_MS,agreeMs=$SEGMENT_AGREE_MS,maxBits=6,endSnapMs=$CREDITS_END_SNAP_MS",
            ),
        )
        return detected
    }

    private fun introMatches(head: IntArray, otherHead: IntArray, step: Int, sibling: Int): List<TimedMatch> {
        val minFrames = (MIN_INTRO_MS / step).toInt()
        val maxFrames = (MAX_INTRO_MS / step).toInt()
        return FingerprintMatcher.topSharedSegments(
            head,
            otherHead,
            minFrames = minFrames,
            maxFrames = maxFrames,
        ).map { segment ->
            TimedMatch(
                startMs = segment.startA * step.toLong(),
                endMs = segment.endA * step.toLong(),
                length = segment.length,
                avgBitErrors = segment.avgBitErrors,
                sibling = sibling,
            )
        }
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
            maxFrames = (MAX_CREDITS_MS / step).toInt().coerceAtLeast(1),
            keepEnd = true,
        ) ?: return null
        val start = episode.tailStartMs + segment.startA * step.toLong()
        if (start < episode.tailStartMs) return null
        val rawEnd = episode.tailStartMs + segment.endA * step.toLong()
        if (rawEnd <= episode.tailStartMs) return null
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
    val sibling: Int = 0,
)

private fun segmentDecision(
    type: String,
    candidates: Int,
    picked: Boolean,
    startMs: Long?,
    endMs: Long?,
    confidence: SegmentConfidence?,
    why: String,
    signal: String,
    thresholds: String,
): SegmentDecision = SegmentDecision(
    type = type,
    candidates = candidates,
    signal = signal,
    accepted = picked,
    startMs = startMs,
    endMs = endMs,
    confidence = confidence?.let {
        "siblings=${it.agreeingSiblings},bits=${bitsText(it.avgBitErrors)},prior=${if (it.theIntroDbAgrees) 1 else 0}"
    } ?: "none",
    why = why,
    thresholds = thresholds,
)

private fun introWhy(noSiblings: Boolean, comparable: Int, voted: Boolean, picked: Boolean): String = when {
    noSiblings -> "rejected-no-siblings"
    picked && !voted -> "accepted-prior"
    picked && comparable < 3 -> "accepted-longest"
    picked -> "accepted-cluster"
    else -> "rejected-no-cluster"
}

private fun introSignal(comparable: Int, voted: Boolean, picked: Boolean): String = when {
    picked && !voted -> "theintrodb-prior"
    picked && comparable < 3 -> "fingerprint-longest"
    else -> "fingerprint-cluster"
}

/** A fingerprint run that lands on the TheIntroDB window, when the season vote found nothing. */
private fun priorRescue(matches: List<TimedMatch>, prior: Pair<Long, Long>?): Pair<TimedMatch, SegmentConfidence>? {
    if (prior == null || matches.isEmpty()) return null
    val near = matches.filter {
        abs(it.startMs - prior.first) <= SEGMENT_AGREE_MS && abs(it.endMs - prior.second) <= SEGMENT_AGREE_MS
    }
    if (near.isEmpty()) return null
    val distinct = near.groupBy { it.sibling }.map { (_, rows) -> rows.maxBy { it.length } }
    val best = distinct.minWith(compareBy<TimedMatch> { it.avgBitErrors }.thenByDescending { it.length })
    return best to distinct.confidence().copy(theIntroDbAgrees = true)
}

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
        val distinct = group.groupBy { it.sibling }.map { (_, rows) -> rows.maxBy { it.length } }
        if (distinct.size < 2) continue
        if (bestGroup == null || distinct.outranks(bestGroup)) bestGroup = distinct
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
 * "Previously on" recaps.
 *
 * First choice: the opening of this episode matches the end of the previous episode (the last
 * scenes stored in its tail, with that episode's credits masked out). One previous episode is
 * enough. A recap is not the same clip repeated in every sibling, so a vote that demands two
 * siblings share a start time never fires on a real "previously on".
 *
 * Otherwise the older montage search still runs: the part before the intro is matched against
 * what is stored of the other episodes, and with three or more siblings the hits must agree.
 * That path still needs a detected intro. Clips from the middle of earlier episodes are not
 * stored, so a recap made only of those is not found.
 */
internal object RecapDetector {
    private const val MIN_CLIP_MS = 4_000L
    private const val MIN_RECAP_MS = 10_000L
    private const val MAX_RECAP_MS = 240_000L
    private const val SNAP_TO_START_MS = 20_000L
    private const val SNAP_TO_INTRO_MS = 60_000L

    fun detect(
        episode: MediaAnalysisResult,
        others: List<MediaAnalysisResult>,
        trace: SegmentTrace? = null,
        prior: Pair<Long, Long>? = null,
    ): Pair<Long, Long>? {
        val fromPrevious = matchPreviousEnding(episode, others)
        if (fromPrevious.range != null) {
            trace?.add(recapLine(fromPrevious.range, candidates = 1, signal = "previous-ending", why = "accepted-previous-ending", prior = prior))
            return fromPrevious.range
        }
        val montage = matchMontage(episode, others)
        val why = when {
            montage.range != null -> "accepted-montage"
            fromPrevious.tooShort && montage.hits == 0 -> "rejected-short"
            episode.introStartMs == null -> "rejected-no-intro"
            episode.introStartMs < MIN_RECAP_MS -> "rejected-intro-too-early"
            montage.hits > 0 && montage.kept == 0 -> "rejected-agreement"
            prior != null -> "rejected-audio-miss-prior-unused"
            siblingByDelta(episode, others, -1) == null -> "rejected-no-previous"
            else -> "rejected-no-match"
        }
        trace?.add(
            recapLine(
                range = montage.range,
                candidates = if (fromPrevious.tooShort && montage.hits == 0) 1 else montage.hits,
                signal = if (montage.range != null) "head-agreement" else "previous-ending",
                why = why,
                prior = prior,
            ),
        )
        return montage.range
    }

    private fun recapLine(
        range: Pair<Long, Long>?,
        candidates: Int,
        signal: String,
        why: String,
        prior: Pair<Long, Long>?,
    ): SegmentDecision {
        val agrees = range != null && prior != null &&
            abs(range.first - prior.first) <= SEGMENT_AGREE_MS &&
            abs(range.second - prior.second) <= SEGMENT_AGREE_MS
        return SegmentDecision(
            type = "recap",
            candidates = candidates,
            signal = signal,
            accepted = range != null,
            startMs = range?.first,
            endMs = range?.second,
            confidence = "prior=${if (agrees) 1 else 0}",
            why = why,
            thresholds = "minClipMs=$MIN_CLIP_MS,minMs=$MIN_RECAP_MS,maxMs=$MAX_RECAP_MS,snapStartMs=$SNAP_TO_START_MS,snapIntroMs=$SNAP_TO_INTRO_MS",
        )
    }

    private data class PreviousAttempt(val range: Pair<Long, Long>? = null, val tooShort: Boolean = false)

    /** Opening audio shared with the previous episode's tail, before that episode's credits. */
    private fun matchPreviousEnding(episode: MediaAnalysisResult, others: List<MediaAnalysisResult>): PreviousAttempt {
        val previous = siblingByDelta(episode, others, -1) ?: return PreviousAttempt()
        val step = episode.fingerprintStepMs.coerceAtLeast(1).toLong()
        if (previous.fingerprintStepMs.toLong() != step) return PreviousAttempt()
        val head = decodeFingerprint(episode.headFingerprint)
        if (head.isEmpty()) return PreviousAttempt()
        val introStart = episode.introStartMs
        val windowMs = when {
            introStart == null -> MAX_RECAP_MS
            introStart < MIN_RECAP_MS -> return PreviousAttempt()
            else -> minOf(introStart, MAX_RECAP_MS + SNAP_TO_START_MS)
        }
        val windowFrames = minOf(head.size, (windowMs / step).toInt())
        val minFrames = (MIN_CLIP_MS / step).toInt()
        if (windowFrames < minFrames) return PreviousAttempt()
        val window = head.copyOfRange(0, windowFrames)
        val tail = maskRange(
            decodeFingerprint(previous.tailFingerprint),
            previous.creditsStartMs,
            previous.creditsEndMs,
            step,
            previous.tailStartMs,
        )
        val segment = FingerprintMatcher.longestSharedSegment(window, tail, minFrames = minFrames) ?: return PreviousAttempt()
        if (segment.startA * step > SNAP_TO_START_MS) return PreviousAttempt()
        val lastMs = segment.endA * step
        val endMs = if (introStart != null && introStart >= lastMs && introStart - lastMs <= SNAP_TO_INTRO_MS) introStart else lastMs
        val snapped = episode.snapSegmentEdges(0L, endMs) ?: return PreviousAttempt(tooShort = true)
        if (snapped.second - snapped.first !in MIN_RECAP_MS..MAX_RECAP_MS) return PreviousAttempt(tooShort = true)
        if (episode.rejectedSegments.covers("recap", snapped.first, snapped.second)) return PreviousAttempt()
        return PreviousAttempt(range = snapped)
    }

    private data class Montage(val range: Pair<Long, Long>?, val hits: Int, val kept: Int)

    private fun matchMontage(episode: MediaAnalysisResult, others: List<MediaAnalysisResult>): Montage {
        val step = episode.fingerprintStepMs.coerceAtLeast(1).toLong()
        val introStart = episode.introStartMs ?: return Montage(null, 0, 0)
        if (introStart < MIN_RECAP_MS) return Montage(null, 0, 0)
        val head = decodeFingerprint(episode.headFingerprint)
        val windowFrames = minOf(head.size, (minOf(introStart, MAX_RECAP_MS + SNAP_TO_START_MS) / step).toInt())
        val minFrames = (MIN_CLIP_MS / step).toInt()
        if (windowFrames < minFrames) return Montage(null, 0, 0)
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
        if (kept.isEmpty()) return Montage(null, hits.size, 0)
        val firstStart = kept.minOf { it.startFrame }
        val lastEnd = kept.maxOf { it.endFrame }
        val startMs = (firstStart * step).let { if (it <= SNAP_TO_START_MS) 0L else it }
        val lastMs = lastEnd * step
        val endMs = if (introStart >= lastMs && introStart - lastMs <= SNAP_TO_INTRO_MS) introStart else lastMs
        if (endMs - startMs !in MIN_RECAP_MS..MAX_RECAP_MS) return Montage(null, hits.size, kept.size)
        val snapped = episode.snapSegmentEdges(startMs, endMs) ?: return Montage(null, hits.size, kept.size)
        if (snapped.second - snapped.first !in MIN_RECAP_MS..MAX_RECAP_MS) return Montage(null, hits.size, kept.size)
        if (episode.rejectedSegments.covers("recap", snapped.first, snapped.second)) return Montage(null, hits.size, kept.size)
        return Montage(snapped, hits.size, kept.size)
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
