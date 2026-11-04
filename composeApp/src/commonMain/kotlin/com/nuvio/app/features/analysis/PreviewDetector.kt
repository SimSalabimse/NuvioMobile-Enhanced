package com.nuvio.app.features.analysis

/**
 * "Next time on" previews.
 *
 * The ending of this episode, stored in its tail with the credits masked out, is matched
 * against the opening of the next episode before that episode's intro (its cold open).
 * One next episode is enough. A preview is not a clip shared by the season, so it never
 * goes through the intro vote.
 *
 * A TheIntroDB preview time is only a confidence note. It does not create a range when the
 * audio does not match. Middle-of-episode footage is not stored, so a preview made only of
 * that is not found.
 */
internal object PreviewDetector {
    private const val MIN_PREVIEW_MS = 5_000L
    private const val MAX_PREVIEW_MS = 180_000L
    /** Opening searched when the next episode has no intro time yet. */
    private const val COLD_OPEN_PRIOR_MS = 90_000L
    private const val SNAP_TO_CREDITS_MS = 10_000L

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
        val head = decodeFingerprint(next.headFingerprint)
        if (tail.isEmpty() || head.isEmpty()) return Found(why = "rejected-no-match")
        val introStart = next.introStartMs
        val windowMs = when {
            introStart == null -> COLD_OPEN_PRIOR_MS
            introStart < MIN_PREVIEW_MS -> return Found(why = "rejected-no-cold-open")
            else -> minOf(introStart, MAX_PREVIEW_MS)
        }
        val windowFrames = minOf(head.size, (windowMs / step).toInt())
        val minFrames = (MIN_PREVIEW_MS / step).toInt().coerceAtLeast(1)
        if (windowFrames < minFrames) return Found(why = "rejected-no-cold-open")
        val window = maskRange(head, next.introStartMs, next.introEndMs, step, 0L).copyOfRange(0, windowFrames)
        val maskedTail = maskRange(tail, episode.creditsStartMs, episode.creditsEndMs, step, episode.tailStartMs)
        val runs = FingerprintMatcher.topSharedSegments(maskedTail, window, minFrames = minFrames, limit = 4)
        if (runs.isEmpty()) return Found(why = "rejected-no-match")
        val segment = runs.maxBy { it.length }
        var startMs = episode.tailStartMs + segment.startA * step
        var endMs = episode.tailStartMs + segment.endA * step
        val creditsStart = episode.creditsStartMs
        if (creditsStart != null && startMs >= creditsStart) return Found(candidates = runs.size, why = "rejected-no-match")
        if (creditsStart != null && creditsStart >= endMs && creditsStart - endMs <= SNAP_TO_CREDITS_MS) {
            endMs = creditsStart
        }
        val snapped = episode.snapSegmentEdges(startMs, endMs)
            ?: return Found(candidates = runs.size, why = "rejected-short")
        if (snapped.second - snapped.first !in MIN_PREVIEW_MS..MAX_PREVIEW_MS) {
            return Found(candidates = runs.size, why = "rejected-short")
        }
        if (episode.rejectedSegments.covers("preview", snapped.first, snapped.second)) {
            return Found(candidates = runs.size, why = "rejected-no-match")
        }
        return Found(range = snapped, candidates = runs.size)
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
            thresholds = "minMs=$MIN_PREVIEW_MS,maxMs=$MAX_PREVIEW_MS,coldOpenMs=$COLD_OPEN_PRIOR_MS,snapCreditsMs=$SNAP_TO_CREDITS_MS,maxBits=6",
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
