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
        forceCoarse: Boolean = false,
    ): SharedSegment? {
        val runs = collectRuns(a, b, minFrames, maxBitErrors, maxGapFrames, forceCoarse)
        var best: SharedSegment? = null
        for (run in runs) {
            if (run.length < minFrames) continue
            // A credits run past the cap is trimmed after the longest original run is chosen.
            // Trimming first would make that run look shorter than a later partial alignment.
            if (!keepEnd && run.length > maxFrames) continue
            if (best == null || run.length > best.length) best = run
        }
        val chosen = best ?: return null
        if (!keepEnd || chosen.length <= maxFrames) return chosen
        val end = chosen.endA
        val start = end - maxFrames
        val shift = chosen.startB - chosen.startA
        return SharedSegment(start, end, start + shift, end + shift, chosen.avgBitErrors)
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
        forceCoarse: Boolean = false,
    ): List<SharedSegment> {
        if (a.isEmpty() || b.isEmpty() || minFrames <= 0 || limit <= 0) return emptyList()
        val found = mutableListOf<SharedSegment>()
        for (segment in collectRuns(a, b, minFrames, maxBitErrors, maxGapFrames, forceCoarse)) {
            if (segment.length < minFrames || segment.length > maxFrames) continue
            val near = found.indexOfFirst {
                kotlin.math.abs(it.startA - segment.startA) <= 20 && kotlin.math.abs(it.startB - segment.startB) <= 20
            }
            if (near >= 0) {
                if (segment.length > found[near].length) found[near] = segment
                continue
            }
            if (found.size < limit) {
                found += segment
                continue
            }
            val worst = found.indices.minBy { found[it].length }
            if (segment.length > found[worst].length) found[worst] = segment
        }
        return found.sortedByDescending { it.length }
    }

    /**
     * Exact alignment of the two prints. A head longer than [EXACT_PAIR_LIMIT] used to keep only
     * [MAX_REFINE_SHIFTS] coarse shifts, and a real theme could be the one that was dropped.
     * [forceCoarse] is the test hook for that coarse list. Production does not use it.
     */
    private fun collectRuns(
        a: IntArray,
        b: IntArray,
        minFrames: Int,
        maxBitErrors: Int,
        maxGapFrames: Int,
        forceCoarse: Boolean,
    ): List<SharedSegment> {
        if (a.isEmpty() || b.isEmpty() || minFrames <= 0) return emptyList()
        val shifts = if (forceCoarse) {
            coarseShifts(a, b, minFrames, maxBitErrors, maxGapFrames)
        } else {
            null
        }
        val out = mutableListOf<SharedSegment>()
        if (shifts == null) {
            for (shift in -(a.size - 1) until b.size) {
                out += runsOnShift(a, b, shift, minFrames, maxBitErrors, maxGapFrames)
            }
        } else {
            for (shift in shifts) out += runsOnShift(a, b, shift, minFrames, maxBitErrors, maxGapFrames)
        }
        return out
    }

    /** Full-resolution runs along one alignment. [shift] is indexB - indexA. */
    private fun runsOnShift(
        a: IntArray,
        b: IntArray,
        shift: Int,
        minFrames: Int,
        maxBitErrors: Int,
        maxGapFrames: Int,
    ): List<SharedSegment> {
        val startI = maxOf(0, -shift)
        val endI = minOf(a.size, b.size - shift)
        if (endI - startI < minFrames) return emptyList()
        val out = mutableListOf<SharedSegment>()
        var runStart = -1
        var lastMatch = -1
        var bitErrorSum = 0
        var matchCount = 0
        fun flush() {
            if (runStart >= 0 && matchCount > 0) {
                val length = lastMatch + 1 - runStart
                if (length >= minFrames) {
                    out += SharedSegment(
                        runStart,
                        lastMatch + 1,
                        runStart + shift,
                        lastMatch + 1 + shift,
                        bitErrorSum.toDouble() / matchCount,
                    )
                }
            }
            runStart = -1
            lastMatch = -1
            bitErrorSum = 0
            matchCount = 0
        }
        var i = startI
        while (i < endI) {
            val diff = (a[i] xor b[i + shift]).countOneBits()
            if (diff <= maxBitErrors) {
                if (runStart < 0 || i - lastMatch > maxGapFrames + 1) {
                    flush()
                    runStart = i
                }
                bitErrorSum += diff
                matchCount++
                lastMatch = i
            }
            i++
        }
        flush()
        return out
    }

    /**
     * Shifts worth a full scan. Each phase offsets the downsampled pair so a title that is not
     * on a stride boundary is still a candidate. At most [MAX_REFINE_SHIFTS] shifts are kept,
     * longest coarse run first.
     */
    private fun coarseShifts(
        a: IntArray,
        b: IntArray,
        minFrames: Int,
        maxBitErrors: Int,
        maxGapFrames: Int,
    ): List<Int> {
        val stride = COARSE_STRIDE
        val minCoarse = ((minFrames - 1) / stride - 1).coerceAtLeast(3)
        val gapCoarse = (maxGapFrames / stride).coerceAtLeast(1)
        val ranked = mutableListOf<Pair<Int, Int>>()
        for (phase in 0 until stride) {
            val coarseA = a.size / stride
            val coarseB = if (b.size <= phase) 0 else (b.size - phase) / stride
            if (coarseA < minCoarse || coarseB < minCoarse) continue
            for (coarseShift in -(coarseA - 1) until coarseB) {
                val startI = maxOf(0, -coarseShift)
                val endI = minOf(coarseA, coarseB - coarseShift)
                if (endI - startI < minCoarse) continue
                var runStart = -1
                var lastMatch = -1
                var best = 0
                var i = startI
                while (i < endI) {
                    val aIndex = i * stride
                    val bIndex = phase + (i + coarseShift) * stride
                    val diff = (a[aIndex] xor b[bIndex]).countOneBits()
                    if (diff <= maxBitErrors) {
                        if (runStart < 0 || i - lastMatch > gapCoarse + 1) runStart = i
                        lastMatch = i
                        val length = lastMatch + 1 - runStart
                        if (length > best) best = length
                    }
                    i++
                }
                if (best >= minCoarse) ranked += (phase + coarseShift * stride) to best
            }
        }
        if (ranked.isEmpty()) return emptyList()
        val shifts = mutableListOf<Int>()
        for ((shift, _) in ranked.sortedByDescending { it.second }) {
            if (shifts.size >= MAX_REFINE_SHIFTS) break
            if (shift !in shifts) shifts += shift
        }
        return shifts
    }

    private const val EXACT_PAIR_LIMIT = 8_000
    private const val COARSE_STRIDE = 4
    private const val MAX_REFINE_SHIFTS = 24
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
    val intro = if (userSaidNone("intro")) {
        SegmentChoice(null, null, null)
    } else appliedMark("intro")?.let { mark ->
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
    val credits = if (userSaidNone("outro")) {
        SegmentChoice(null, null, null)
    } else appliedMark("outro")?.let { mark ->
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

private fun MediaAnalysisResult.userSaidNone(type: String): Boolean {
    val mark = userSegments[type] ?: return false
    return mark.absent && sameCut(mark.durationMs, durationMs)
}

private fun MediaAnalysisResult.appliedMark(type: String): UserSegment? {
    val mark = userSegments[type] ?: return null
    if (mark.absent || mark.endMs <= mark.startMs) return null
    return mark.takeIf { sameCut(it.durationMs, durationMs) }
}

internal object SeasonSegmentDetector {
    /** TheIntroDB intro bounds. A match under [INTRO_SHORT_MS] needs a clean multi-sibling score. */
    private const val MIN_INTRO_MS = 5_000L
    private const val MAX_INTRO_MS = 300_000L
    private const val INTRO_SHORT_MS = 15_000L
    private const val INTRO_SHORT_MAX_BIT_ERRORS = 2.0
    private const val MIN_CREDITS_MS = 5_000L
    /** TheIntroDB allows 1800s. The stored tail is what can actually be compared. */
    private const val MAX_CREDITS_MS = 1_800_000L
    private const val CREDITS_SHORT_MS = 15_000L
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
        // Opening credits are the intro. A shared clip in the first 10 minutes never stays credits.
        val openingCredits = creditPool.filter { isOpeningCreditsRange(it.startMs, it.endMs, episode.durationMs) }
        val endingCredits = creditPool.filterNot { isOpeningCreditsRange(it.startMs, it.endMs, episode.durationMs) }
        val introFromOpening = openingCredits.filter { match ->
            val length = match.endMs - match.startMs
            length in MIN_INTRO_MS..MAX_INTRO_MS
        }
        var introCandidates = introPool + introFromOpening
        var introReject: String? = null
        if (introCandidates.any { it.startMs == COLD_OPEN_START_MS } && coldOpenLikely(COLD_OPEN_START_MS, others)) {
            introCandidates = introCandidates.filterNot { it.startMs == COLD_OPEN_START_MS }
            if (introCandidates.isEmpty()) introReject = "rejected-cold-open"
        }
        if (introCandidates.any { episode.speechHigh(it) }) {
            introCandidates = introCandidates.filterNot { episode.speechHigh(it) }
            if (introCandidates.isEmpty()) introReject = "rejected-dialog-overlay"
        }
        val endingQuiet = endingCredits.filterNot { episode.speechHigh(it) }
        val creditsReject = when {
            endingQuiet.isEmpty() && endingCredits.isNotEmpty() -> "rejected-dialog-overlay"
            endingQuiet.isEmpty() && openingCredits.isNotEmpty() -> "rejected-opening-credits"
            else -> null
        }
        val introPick = pickIntro(introCandidates, comparable, theIntroDbIntro)
        val intro = introPick?.let { it.match to it.confidence }
        val credit = vote(endingQuiet, comparable)
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
                candidates = introPool.size + introFromOpening.size,
                picked = detected.introStartMs != null,
                startMs = detected.introStartMs,
                endMs = detected.introEndMs,
                confidence = detected.introConfidence,
                confidenceLabel = if (introReject != null && detected.introStartMs == null) "low" else null,
                why = introReject?.takeIf { detected.introStartMs == null }
                    ?: introWhy(others.isEmpty(), comparable, introPick?.voted == true, intro != null),
                signal = introSignal(comparable, introPick?.voted == true, intro != null),
                thresholds = "minMs=$MIN_INTRO_MS,maxMs=$MAX_INTRO_MS,shortMs=$INTRO_SHORT_MS,agreeMs=$SEGMENT_AGREE_MS,maxBits=6,minCluster=2," +
                    "openingMs=$OPENING_CREDITS_WINDOW_MS,dialogLevel=$DIALOG_OVERLAY_LEVEL," +
                    "dialogPercent=$DIALOG_OVERLAY_MIN_PERCENT,coldOpenLaterMs=$COLD_OPEN_LATER_THAN_MS",
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
                confidenceLabel = if (creditsReject == "rejected-dialog-overlay" && detected.creditsStartMs == null) "low" else null,
                why = if (others.isEmpty()) {
                    "rejected-no-siblings"
                } else if (detected.creditsStartMs != null) {
                    if (comparable < 3) "accepted-longest" else "accepted-cluster"
                } else {
                    creditsReject ?: "rejected-no-cluster"
                },
                signal = if (detected.creditsStartMs != null && comparable < 3) "fingerprint-longest" else "fingerprint-cluster",
                thresholds = "minMs=$MIN_CREDITS_MS,maxMs=$MAX_CREDITS_MS,agreeMs=$SEGMENT_AGREE_MS,maxBits=6," +
                    "endSnapMs=$CREDITS_END_SNAP_MS,openingMs=$OPENING_CREDITS_WINDOW_MS," +
                    "dialogLevel=$DIALOG_OVERLAY_LEVEL,dialogPercent=$DIALOG_OVERLAY_MIN_PERCENT",
            ),
        )
        return detected
    }

    private fun MediaAnalysisResult.speechHigh(match: TimedMatch): Boolean =
        speechEnergyHigh(energy, energyStepMs, match.startMs, match.endMs)

    private fun introMatches(head: IntArray, otherHead: IntArray, step: Int, sibling: Int): List<TimedMatch> {
        val minFrames = (MIN_INTRO_MS / step).toInt().coerceAtLeast(1)
        val maxFrames = (MAX_INTRO_MS / step).toInt().coerceAtLeast(minFrames)
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
            minFrames = (MIN_CREDITS_MS / step).toInt().coerceAtLeast(1),
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
        val lengthMs = end - start
        if (lengthMs !in MIN_CREDITS_MS..MAX_CREDITS_MS) return null
        // A sting under 15s is credits only when it finishes the file. A short shared hit
        // earlier in the tail is left alone.
        if (lengthMs < CREDITS_SHORT_MS && episode.durationMs > 0L && episode.durationMs - end > CREDITS_END_SNAP_MS) {
            return null
        }
        return TimedMatch(start, end, segment.length, segment.avgBitErrors)
    }

    private data class IntroPick(
        val match: TimedMatch,
        val confidence: SegmentConfidence,
        val voted: Boolean,
    )

    /**
     * Drops a short shared sting unless two siblings agree and the bits are clean, or the
     * fingerprint already lands on a TheIntroDB time. A longer cluster behind it is tried next.
     * The prior never creates a range the audio did not match.
     */
    private fun pickIntro(
        matches: List<TimedMatch>,
        siblingCount: Int,
        prior: Pair<Long, Long>?,
    ): IntroPick? {
        var pool = matches
        var chosen = vote(pool, siblingCount)
        while (chosen != null && !acceptedIntro(chosen.first, chosen.second)) {
            val start = chosen.first.startMs
            pool = pool.filterNot { abs(it.startMs - start) <= SEGMENT_AGREE_MS }
            chosen = if (pool.isEmpty()) null else vote(pool, siblingCount)
        }
        if (chosen != null) return IntroPick(chosen.first, chosen.second, voted = true)
        val rescued = priorRescue(matches, prior) ?: return null
        if (!acceptedIntro(rescued.first, rescued.second)) return null
        return IntroPick(rescued.first, rescued.second, voted = false)
    }

    private fun acceptedIntro(match: TimedMatch, confidence: SegmentConfidence): Boolean {
        val length = match.endMs - match.startMs
        if (length !in MIN_INTRO_MS..MAX_INTRO_MS) return false
        if (length >= INTRO_SHORT_MS) return true
        if (confidence.theIntroDbAgrees) return true
        return confidence.agreeingSiblings >= 2 && confidence.avgBitErrors <= INTRO_SHORT_MAX_BIT_ERRORS
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
    confidenceLabel: String? = null,
): SegmentDecision = SegmentDecision(
    type = type,
    candidates = candidates,
    signal = signal,
    accepted = picked,
    startMs = startMs,
    endMs = endMs,
    confidence = confidenceLabel ?: confidence?.let {
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
    val ends = chosen.map { it.endMs }.sorted()
    val medianEnd = ends[ends.size / 2]
    val endMs = chosen.filter { it.endMs <= medianEnd + INTRO_END_SLACK_MS }.maxOf { it.endMs }
    return representative.copy(endMs = endMs) to chosen.confidence()
}

private const val INTRO_END_SLACK_MS = 15_000L

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
 * A recap is narration with short clips, not one contiguous copy of the previous episode.
 * Clips of at least [MIN_CLIP_MS] are taken from the previous episode's ending (credits masked)
 * and from other stored heads and tails. One previous episode is enough. With three or more
 * other siblings, a clip that is not from the previous episode has to agree with a second sibling.
 * A clip from one sibling is kept when it starts within [STITCH_GAP_MS] after a shared opening
 * sting, and the span then ends at the next quiet run or scene change inside [AUDIO_TAIL_MS].
 * Clips join while the gap between them stays inside [NARRATION_GAP_MS]. The span snaps to the
 * intro only across a short transition, not across a cold open. Clips from the middle of earlier
 * episodes are not stored, so a recap made only of those is not found.
 * When at least two siblings carry a user recap that starts at a sting shared by the season, the
 * end may move to the first cut at or before the shortest of those marks, and never past the
 * longest. A match of only the previous episode stays on the audio, including the quiet run it
 * snaps to. It is not stretched to a later cut. The token for a shared-sting extension is
 * season-pattern: a suggestion, not an audio find. A TheIntroDB time does not create a range and
 * blocks that suggestion.
 */
internal object RecapDetector {
    private const val MIN_CLIP_MS = 2_000L
    private const val MIN_RECAP_MS = 5_000L
    private const val MAX_RECAP_MS = 1_200_000L
    /** How far to look when the intro time is still unknown. A long guess without that boundary is not made. */
    private const val NO_INTRO_WINDOW_MS = 240_000L
    private const val SNAP_TO_START_MS = 20_000L
    private const val SNAP_TO_INTRO_MS = 8_000L
    private const val NARRATION_GAP_MS = 12_000L
    /** A shared sting has to start in this window to count as the opening. */
    private const val OPENING_STING_START_MS = 500L
    /** How far a single-sibling clip may sit after that sting and still belong to it. */
    private const val STITCH_GAP_MS = 10_000L
    /** How far past the last stitched clip to look for the next quiet run or cut. */
    private const val AUDIO_TAIL_MS = 4_000L
    /** A sibling mark starts at the opening when it begins inside this window. */
    private const val SEASON_MARK_START_MS = 2_000L

    fun detect(
        episode: MediaAnalysisResult,
        others: List<MediaAnalysisResult>,
        trace: SegmentTrace? = null,
        prior: Pair<Long, Long>? = null,
    ): Pair<Long, Long>? {
        val audio = search(episode, others)
        val found = if (prior == null) withSeasonPattern(episode, others, audio) else audio
        val why = when {
            found.unchecked -> found.uncheckedWhy ?: "not-checked-no-print"
            found.range != null && found.seasonPattern -> "season-pattern"
            found.range != null && found.fromPrevious -> "accepted-previous-ending"
            found.range != null -> "accepted-montage"
            found.dialog -> "rejected-dialog-overlay"
            found.tooShort -> "rejected-short"
            episode.introStartMs != null && episode.introStartMs < MIN_RECAP_MS -> "rejected-intro-too-early"
            found.hits > 0 && found.kept == 0 -> "rejected-agreement"
            prior != null -> "rejected-audio-miss-prior-unused"
            siblingByDelta(episode, others, -1) == null && found.hits == 0 -> "rejected-no-previous"
            else -> "rejected-no-match"
        }
        val signal = when {
            found.seasonPattern -> "season-pattern"
            found.range != null && !found.fromPrevious -> "head-agreement"
            else -> "previous-ending"
        }
        trace?.add(
            recapLine(
                range = found.range,
                candidates = found.hits.coerceAtLeast(if (found.tooShort) 1 else 0),
                signal = signal,
                why = why,
                prior = prior,
            ),
        )
        return found.range
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
            accepted = range != null && why != "season-pattern",
            startMs = range?.first,
            endMs = range?.second,
            confidence = "prior=${if (agrees) 1 else 0}",
            why = why,
            thresholds = "minClipMs=$MIN_CLIP_MS,minMs=$MIN_RECAP_MS,maxMs=$MAX_RECAP_MS," +
                "snapStartMs=$SNAP_TO_START_MS,snapIntroMs=$SNAP_TO_INTRO_MS,gapMs=$NARRATION_GAP_MS," +
                "stitchMs=$STITCH_GAP_MS,tailMs=$AUDIO_TAIL_MS,noIntroWindowMs=$NO_INTRO_WINDOW_MS",
        )
    }

    private data class Found(
        val range: Pair<Long, Long>? = null,
        val hits: Int = 0,
        val kept: Int = 0,
        val tooShort: Boolean = false,
        val fromPrevious: Boolean = false,
        val dialog: Boolean = false,
        /** The search did not compare a clip. An empty or too-short print is not a completed miss. */
        val unchecked: Boolean = false,
        val uncheckedWhy: String? = null,
        /** The span starts on a sting shared by the season, inside the opening window. */
        val sharedOpening: Boolean = false,
        /** The end was moved from the audio clips using sibling marks. Not an audio find. */
        val seasonPattern: Boolean = false,
    )

    private data class Clip(val startMs: Long, val endMs: Long, val sibling: Int, val previous: Boolean)

    private fun search(episode: MediaAnalysisResult, others: List<MediaAnalysisResult>): Found {
        val step = episode.fingerprintStepMs.coerceAtLeast(1).toLong()
        val introStart = episode.introStartMs
        if (introStart != null && introStart < MIN_RECAP_MS) return Found()
        val head = decodeFingerprint(episode.headFingerprint)
        if (head.isEmpty()) return Found(unchecked = true, uncheckedWhy = "not-checked-no-print")
        val windowMs = if (introStart == null) NO_INTRO_WINDOW_MS else minOf(introStart, MAX_RECAP_MS)
        val windowFrames = minOf(head.size, (windowMs / step).toInt())
        val minFrames = (MIN_CLIP_MS / step).toInt().coerceAtLeast(1)
        if (windowFrames < minFrames) return Found(unchecked = true, uncheckedWhy = "not-checked-short-window")
        val window = head.copyOfRange(0, windowFrames)
        val previousKey = siblingByDelta(episode, others, -1)?.key
        val clips = mutableListOf<Clip>()
        var comparable = 0
        for ((index, other) in others.withIndex()) {
            if (other.fingerprintStepMs.toLong() != step) continue
            comparable++
            val previous = other.key == previousKey
            val sequences = listOf(
                maskRange(decodeFingerprint(other.headFingerprint), other.introStartMs, other.introEndMs, step, 0L),
                maskRange(decodeFingerprint(other.tailFingerprint), other.creditsStartMs, other.creditsEndMs, step, other.tailStartMs),
            )
            for (sequence in sequences) {
                if (sequence.isEmpty()) continue
                val runs = FingerprintMatcher.topSharedSegments(window, sequence, minFrames = minFrames, limit = 8)
                for (segment in runs) {
                    val startMs = segment.startA * step
                    val endMs = segment.endA * step
                    if (endMs - startMs < MIN_CLIP_MS) continue
                    clips += Clip(startMs, endMs, index, previous)
                }
            }
        }
        if (clips.isEmpty()) return Found()
        val sting = openingSting(clips)
        val kept = clips.filter { clip ->
            clip.previous || comparable < 3 || agrees(clip, clips) || followsOpeningSting(clip, sting)
        }
        if (kept.isEmpty()) return Found(hits = clips.size, kept = 0)
        val chained = chain(kept, introStart)
            ?: return Found(hits = clips.size, kept = kept.size, tooShort = kept.any { it.startMs <= SNAP_TO_START_MS })
        val prepared = episode.rememberingSilentRanges()
        val dipped = dipAfterOpening(prepared, chained, sting)
        val snapped = prepared.snapSegmentEdges(dipped.first, dipped.second)
            ?: return Found(hits = clips.size, kept = kept.size, tooShort = true)
        if (snapped.second - snapped.first !in MIN_RECAP_MS..MAX_RECAP_MS) {
            return Found(hits = clips.size, kept = kept.size, tooShort = true)
        }
        if (episode.rejectedSegments.covers("recap", snapped.first, snapped.second)) {
            return Found(hits = clips.size, kept = kept.size)
        }
        val inSpan = kept.filter { it.endMs > snapped.first && it.startMs < snapped.second }
        val oneClip = clipGroups(inSpan) <= 1
        // Groups count start times, so one spoken opening shared by the season is still one clip.
        // Dialog is a loud clip from a single sibling. Two siblings sharing it is the recap.
        val shared = inSpan.map { it.sibling }.distinct().size >= 2
        if (oneClip && !shared && speechEnergyHigh(episode.energy, episode.energyStepMs, snapped.first, snapped.second)) {
            return Found(hits = clips.size, kept = kept.size, dialog = true)
        }
        return Found(
            range = snapped,
            hits = clips.size,
            kept = kept.size,
            fromPrevious = inSpan.any { it.previous },
            sharedOpening = openingForSeason(chained, sting),
        )
    }

    /**
     * A sting shared by the season, starting in the opening window, before a quiet or black run
     * can snap the start. A previous-episode match is the recap itself. Sibling marks do not
     * stretch it to the next cut.
     */
    private fun openingForSeason(chained: Pair<Long, Long>, sting: Clip?): Boolean =
        sting != null && chained.first <= OPENING_STING_START_MS

    /** Two or more siblings sharing a clip that starts at the opening. */
    private fun openingSting(clips: List<Clip>): Clip? {
        val opening = clips.filter { it.startMs <= OPENING_STING_START_MS }
        if (opening.map { it.sibling }.distinct().size < 2) return null
        return opening.maxBy { it.endMs }
    }

    private fun agrees(clip: Clip, clips: List<Clip>): Boolean =
        clips.any { other ->
            other.sibling != clip.sibling && abs(other.startMs - clip.startMs) <= SEGMENT_AGREE_MS
        }

    /** A one-sibling clip that begins within [STITCH_GAP_MS] after the shared opening sting. */
    private fun followsOpeningSting(clip: Clip, sting: Clip?): Boolean {
        if (sting == null || clip.startMs <= OPENING_STING_START_MS) return false
        val gap = clip.startMs - sting.endMs
        return gap in 0L..STITCH_GAP_MS
    }

    /** The next quiet run or cut after a stitched opening, inside [AUDIO_TAIL_MS]. */
    private fun dipAfterOpening(
        episode: MediaAnalysisResult,
        chained: Pair<Long, Long>,
        sting: Clip?,
    ): Pair<Long, Long> {
        if (sting == null || chained.first > OPENING_STING_START_MS) return chained
        val next = episode.nextEdgeAfter(chained.second, AUDIO_TAIL_MS) ?: return chained
        if (next <= chained.second) return chained
        return chained.first to next
    }

    /**
     * Extends an opening match to the fingerprint cut at or before the shortest sibling mark,
     * and never past the longest. A pause after the matched tail is not that cut.
     * No extension when fewer than two marks qualify.
     */
    private fun withSeasonPattern(
        episode: MediaAnalysisResult,
        others: List<MediaAnalysisResult>,
        found: Found,
    ): Found {
        val range = found.range ?: return found
        if (!found.sharedOpening) return found
        val end = seasonPatternEnd(episode, others, range) ?: return found
        if (end <= range.second) return found
        if (end - range.first !in MIN_RECAP_MS..MAX_RECAP_MS) return found
        return found.copy(range = range.first to end, seasonPattern = true)
    }

    private fun seasonPatternEnd(
        episode: MediaAnalysisResult,
        others: List<MediaAnalysisResult>,
        audio: Pair<Long, Long>,
    ): Long? {
        val marks = others.mapNotNull { sibling ->
            sibling.userSegments["recap"]?.takeIf { mark ->
                !mark.absent &&
                    mark.startMs <= SEASON_MARK_START_MS &&
                    mark.endMs > mark.startMs &&
                    sameCut(mark.durationMs, sibling.durationMs)
            }
        }
        if (marks.size < 2) return null
        val shortest = marks.minOf { it.endMs }
        val longest = marks.maxOf { it.endMs }
        if (shortest <= audio.second) return null
        val cuts = episode.rememberingSilentRanges().sceneChangesAfter(audio.second, longest)
        cuts.firstOrNull { it <= shortest }?.let { return it }
        cuts.firstOrNull { it <= longest }?.let { return it }
        return shortest
    }

    /** Starts more than a second apart are different clips. The same hit in a head and a tail is one. */
    private fun clipGroups(clips: List<Clip>): Int {
        val starts = clips.map { it.startMs }.distinct().sorted()
        if (starts.isEmpty()) return 0
        var groups = 1
        for (index in 1 until starts.size) {
            if (starts[index] - starts[index - 1] > 1_000L) groups++
        }
        return groups
    }

    /**
     * Clips that stay within a narration gap of each other.
     * The skip is that span. A cold open can put the first clip after [SNAP_TO_START_MS],
     * and the skip still starts at the first clip, not at 0.
     * A bumper before the recap is its own short cluster. It does not erase a later cluster
     * that is long enough to be a recap.
     */
    private fun chain(clips: List<Clip>, introStart: Long?): Pair<Long, Long>? {
        val sorted = clips.sortedWith(compareBy({ it.startMs }, { it.endMs }))
        var index = 0
        while (index < sorted.size) {
            val start = sorted[index].startMs
            var end = sorted[index].endMs
            var cursor = end
            index++
            while (index < sorted.size && sorted[index].startMs <= cursor + NARRATION_GAP_MS) {
                if (sorted[index].endMs > end) {
                    end = sorted[index].endMs
                    cursor = end
                }
                index++
            }
            var spanEnd = end
            if (introStart != null && introStart >= spanEnd && introStart - spanEnd <= SNAP_TO_INTRO_MS) {
                spanEnd = introStart
            }
            if (spanEnd - start in MIN_RECAP_MS..MAX_RECAP_MS) return start to spanEnd
        }
        return null
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
