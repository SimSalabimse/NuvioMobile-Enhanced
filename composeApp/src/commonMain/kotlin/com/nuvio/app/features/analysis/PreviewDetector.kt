package com.nuvio.app.features.analysis

/**
 * "Next time on" previews.
 *
 * The ending of this episode, stored in its tail with the credits masked out, is matched
 * against the next episode: the opening before its intro, and its tail with credits masked.
 * A preview is usually several short clips with narration between them. Those clips join
 * while the gap stays inside [NARRATION_GAP_MS]. The block has to sit against the credits
 * (just before them, or just after). One next episode is enough. It does not go through
 * the intro vote.
 *
 * Without a credits time, a single ending match is not called a preview: it may be the
 * credits theme. Two or more clips that finish the file still count.
 *
 * A TheIntroDB preview time is only a confidence note. It does not create a range when the
 * audio does not match. Footage from the middle of the next episode is not stored, so a
 * preview made only of that is not found.
 */
internal object PreviewDetector {
    private const val MIN_CLIP_MS = 2_000L
    private const val MIN_PREVIEW_MS = 5_000L
    private const val MAX_PREVIEW_MS = 1_800_000L
    /** Opening searched when the next episode has no intro time yet. */
    private const val COLD_OPEN_PRIOR_MS = 90_000L
    private const val BESIDE_CREDITS_MS = 10_000L
    private const val NARRATION_GAP_MS = 8_000L

    fun detect(
        episode: MediaAnalysisResult,
        others: List<MediaAnalysisResult>,
        trace: SegmentTrace? = null,
        prior: Pair<Long, Long>? = null,
    ): Pair<Long, Long>? {
        val found = search(episode, others)
        val foundWhy = found.why
        val why = when {
            found.range != null -> "accepted-next-start"
            foundWhy != null && foundWhy != "rejected-no-match" -> foundWhy
            prior != null -> "rejected-audio-miss-prior-unused"
            else -> foundWhy ?: "rejected-no-match"
        }
        trace?.add(previewLine(found.range, found.candidates, why, prior))
        return found.range
    }

    private data class Found(
        val range: Pair<Long, Long>? = null,
        val candidates: Int = 0,
        val why: String? = null,
    )

    private fun search(episode: MediaAnalysisResult, others: List<MediaAnalysisResult>): Found {
        val next = siblingByDelta(episode, others, 1) ?: return Found(why = "rejected-no-next")
        val step = episode.fingerprintStepMs.coerceAtLeast(1).toLong()
        if (next.fingerprintStepMs.toLong() != step) return Found(why = "rejected-step")
        val tail = decodeFingerprint(episode.tailFingerprint)
        if (tail.isEmpty()) return Found(why = "rejected-no-match")
        val maskedTail = maskRange(tail, episode.creditsStartMs, episode.creditsEndMs, step, episode.tailStartMs)
        val minFrames = (MIN_CLIP_MS / step).toInt().coerceAtLeast(1)
        val runs = mutableListOf<FingerprintMatcher.SharedSegment>()
        val head = decodeFingerprint(next.headFingerprint)
        if (head.isNotEmpty()) {
            val introStart = next.introStartMs
            val windowMs = when {
                introStart == null -> COLD_OPEN_PRIOR_MS
                introStart < MIN_CLIP_MS -> 0L
                else -> minOf(introStart, MAX_PREVIEW_MS)
            }
            val windowFrames = minOf(head.size, (windowMs / step).toInt())
            if (windowFrames >= minFrames) {
                val window = maskRange(head, next.introStartMs, next.introEndMs, step, 0L).copyOfRange(0, windowFrames)
                runs += FingerprintMatcher.topSharedSegments(maskedTail, window, minFrames = minFrames, limit = 8)
            }
        }
        val nextTail = maskRange(
            decodeFingerprint(next.tailFingerprint),
            next.creditsStartMs,
            next.creditsEndMs,
            step,
            next.tailStartMs,
        )
        if (nextTail.isNotEmpty()) {
            runs += FingerprintMatcher.topSharedSegments(maskedTail, nextTail, minFrames = minFrames, limit = 8)
        }
        if (runs.isEmpty()) {
            val noColdOpen = head.isNotEmpty() && next.introStartMs != null && next.introStartMs < MIN_CLIP_MS
            return Found(why = if (noColdOpen) "rejected-no-cold-open" else "rejected-no-match")
        }
        val clips = runs.map { segment ->
            (episode.tailStartMs + segment.startA * step) to (episode.tailStartMs + segment.endA * step)
        }.filter { (start, end) -> !overlapsCredits(start, end, episode) }
        if (clips.isEmpty()) return Found(candidates = runs.size, why = "rejected-no-match")
        val chain = previewChain(clips, episode) ?: return Found(candidates = runs.size, why = "rejected-not-beside-credits")
        val snapped = episode.snapSegmentEdges(chain.first, chain.second)
            ?: return Found(candidates = runs.size, why = "rejected-short")
        val trimmed = trimOffCredits(snapped, episode)
        if (trimmed.second - trimmed.first !in MIN_PREVIEW_MS..MAX_PREVIEW_MS) {
            return Found(candidates = runs.size, why = "rejected-short")
        }
        if (overlapsCredits(trimmed.first, trimmed.second, episode)) {
            return Found(candidates = runs.size, why = "rejected-no-match")
        }
        if (episode.rejectedSegments.covers("preview", trimmed.first, trimmed.second)) {
            return Found(candidates = runs.size, why = "rejected-no-match")
        }
        if (speechEnergyHigh(episode.energy, episode.energyStepMs, trimmed.first, trimmed.second) &&
            clipGroups(clips.filter { it.second > trimmed.first && it.first < trimmed.second }) <= 1
        ) {
            return Found(candidates = runs.size, why = "rejected-dialog-overlay")
        }
        return Found(range = trimmed, candidates = runs.size)
    }

    private fun overlapsCredits(startMs: Long, endMs: Long, episode: MediaAnalysisResult): Boolean {
        val creditsStart = episode.creditsStartMs ?: return false
        val creditsEnd = episode.creditsEndMs ?: return false
        return startMs < creditsEnd && endMs > creditsStart
    }

    /** Pulls a snapped end back off the credits. A snap must not swallow the credit roll. */
    private fun trimOffCredits(range: Pair<Long, Long>, episode: MediaAnalysisResult): Pair<Long, Long> {
        val creditsStart = episode.creditsStartMs ?: return range
        if (range.first >= (episode.creditsEndMs ?: Long.MAX_VALUE)) return range
        if (range.second > creditsStart && range.first < creditsStart) return range.first to creditsStart
        return range
    }

    /**
     * The chain of clips with narration-sized gaps that sits against the credits, or, when the
     * credits time is unknown, a multi-clip chain that finishes the file.
     */
    private fun previewChain(
        clips: List<Pair<Long, Long>>,
        episode: MediaAnalysisResult,
    ): Pair<Long, Long>? {
        val sorted = clips.distinct().sortedBy { it.first }
        var best: Pair<Long, Long>? = null
        var bestClips = 0
        var index = 0
        while (index < sorted.size) {
            var end = sorted[index].second
            var count = 1
            var next = index + 1
            while (next < sorted.size && sorted[next].first <= end + NARRATION_GAP_MS) {
                if (sorted[next].second > end) end = sorted[next].second
                count++
                next++
            }
            val start = sorted[index].first
            val span = start to end
            if (count >= bestClips && beside(span, count, episode)) {
                best = span
                bestClips = count
            }
            index = next
        }
        return best
    }

    private fun beside(span: Pair<Long, Long>, clips: Int, episode: MediaAnalysisResult): Boolean {
        val creditsStart = episode.creditsStartMs
        val creditsEnd = episode.creditsEndMs
        if (creditsStart != null && creditsEnd != null && creditsEnd > creditsStart) {
            val before = span.second <= creditsStart && creditsStart - span.second <= BESIDE_CREDITS_MS
            val after = span.first >= creditsEnd && span.first - creditsEnd <= BESIDE_CREDITS_MS
            return before || after
        }
        val finishesFile = episode.durationMs > 0L && episode.durationMs - span.second <= BESIDE_CREDITS_MS
        return finishesFile && clips >= 2
    }

    private fun clipGroups(clips: List<Pair<Long, Long>>): Int {
        val starts = clips.map { it.first }.distinct().sorted()
        if (starts.isEmpty()) return 0
        var groups = 1
        for (index in 1 until starts.size) {
            if (starts[index] - starts[index - 1] > 1_000L) groups++
        }
        return groups
    }

    private fun previewLine(
        range: Pair<Long, Long>?,
        candidates: Int,
        why: String,
        prior: Pair<Long, Long>?,
    ): SegmentDecision {
        val agrees = range != null && prior != null &&
            kotlin.math.abs(range.first - prior.first) <= SEGMENT_AGREE_MS &&
            kotlin.math.abs(range.second - prior.second) <= SEGMENT_AGREE_MS
        return SegmentDecision(
            type = "preview",
            candidates = candidates,
            signal = "next-cold-open",
            accepted = range != null,
            startMs = range?.first,
            endMs = range?.second,
            confidence = "prior=${if (agrees) 1 else 0}",
            why = why,
            thresholds = "minClipMs=$MIN_CLIP_MS,minMs=$MIN_PREVIEW_MS,maxMs=$MAX_PREVIEW_MS," +
                "coldOpenMs=$COLD_OPEN_PRIOR_MS,besideMs=$BESIDE_CREDITS_MS,gapMs=$NARRATION_GAP_MS,maxBits=6",
        )
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
