package com.nuvio.app.features.player.skip

import com.nuvio.app.features.player.sameCut
import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration

/** A published credits row. A null [endSec] means the roll runs to the end of the media. */
internal data class PublishedCreditRow(
    val startSec: Double,
    val endSec: Double?,
)

/** Gap between two credit runs that counts as a scene, in seconds. */
internal const val MOVIE_SCENE_MIN_GAP_SEC = 8.0

/**
 * A real movie runtime. Zero and the short placeholder clips
 * ([isShortPlaceholderDuration]) are not long enough to close an open credits end.
 */
internal fun trustworthyMovieDurationSec(durationMs: Long): Double? {
    if (durationMs <= 0L || isShortPlaceholderDuration(durationMs)) return null
    return durationMs / 1000.0
}

/**
 * Open credits may be closed at [durationMs] when that length is a real runtime and, if
 * [referenceDurationMs] is also a real runtime, the two describe the same cut
 * ([sameCut]). A mismatch leaves the end open.
 */
internal fun canCloseOpenMovieCredits(durationMs: Long, referenceDurationMs: Long = 0L): Boolean {
    if (trustworthyMovieDurationSec(durationMs) == null) return false
    if (referenceDurationMs > 0L &&
        trustworthyMovieDurationSec(referenceDurationMs) != null &&
        !sameCut(referenceDurationMs, durationMs)
    ) {
        return false
    }
    return true
}

/** 30-second bucket of a trustworthy duration, or 0 when the length cannot close credits. */
internal fun movieSkipDurationBucket(durationMs: Long): Long {
    val sec = trustworthyMovieDurationSec(durationMs)?.toLong() ?: return 0L
    return sec - (sec % 30L)
}

/**
 * Movie credits from published rows. An empty end is the end of [durationMs] only when
 * [canCloseOpenMovieCredits] allows it, and only for the last row (an earlier open end runs
 * through anything after it, so it is not a gap). A gap of at least [MOVIE_SCENE_MIN_GAP_SEC]
 * between a published end and the next row's start is a `post-credits` scene. The later row
 * stays `movie-credits`.
 */
internal fun movieCreditIntervals(
    rows: List<PublishedCreditRow>,
    durationMs: Long,
    provider: String,
    referenceDurationMs: Long = 0L,
): List<SkipInterval> {
    val sorted = rows
        .filter { it.startSec.isFinite() && it.startSec >= 0.0 }
        .sortedBy { it.startSec }
    if (sorted.isEmpty()) return emptyList()
    val durationSec = if (canCloseOpenMovieCredits(durationMs, referenceDurationMs)) {
        durationMs / 1000.0
    } else {
        null
    }
    val out = mutableListOf<SkipInterval>()
    for (index in sorted.indices) {
        val row = sorted[index]
        if (durationSec != null && row.startSec >= durationSec) continue
        val later = sorted.getOrNull(index + 1)
        val publishedEnd = row.endSec?.takeIf { it.isFinite() && it > row.startSec }
        val end = when {
            publishedEnd != null -> publishedEnd
            later == null && durationSec != null && row.startSec < durationSec -> durationSec
            else -> null
        }
        if (end != null) {
            out += SkipInterval(row.startSec, end, "movie-credits", provider)
        }
        if (publishedEnd != null && later != null && later.startSec - publishedEnd >= MOVIE_SCENE_MIN_GAP_SEC) {
            out += SkipInterval(publishedEnd, later.startSec, "post-credits", provider)
        }
    }
    return out
}
