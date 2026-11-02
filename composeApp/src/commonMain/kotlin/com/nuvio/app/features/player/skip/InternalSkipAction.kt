package com.nuvio.app.features.player.skip

import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration

internal data class InternalSkipAction(
    val targetMs: Long,
    val skipsToPostCredits: Boolean,
)

internal fun SkipInterval.internalSkipAction(
    intervals: List<SkipInterval>,
    durationMs: Long = 0L,
): InternalSkipAction? {
    if (isShortPlaceholderDuration(durationMs)) return null
    val normalizedType = type.trim().lowercase()
    if (normalizedType == "post-credits") return null
    val isMovieCredits = normalizedType == "movie-credits"
    // Episode providers may use Double.MAX_VALUE as an open-ended segment sentinel.
    if (isMovieCredits) {
        if (!hasValidSeekTimes()) return null
        // A real scene after the roll is a warning. Nothing seeks, including auto-skip.
        if (showsMovieSceneNotice(intervals, durationMs)) return null
        return InternalSkipAction(
            targetMs = (endTime * 1000.0).toLong(),
            skipsToPostCredits = false,
        )
    }
    // Repository intervals (including post-credits) remain intact for external players.
    val canHavePostCredits = normalizedType in PlayerNextEpisodeRules.OUTRO_SEGMENT_TYPES
    val scene = if (canHavePostCredits) {
        intervals.filter {
            it.type.trim().lowercase() == "post-credits" && it.hasValidSeekTimes() &&
                it.startTime >= endTime &&
                (durationMs <= 0L || it.startTime < durationMs / 1000.0)
        }.minByOrNull { it.startTime }
    } else null
    val hasPostCreditsTail = canHavePostCredits && hasValidSeekTimes() && durationMs > 0L &&
        durationMs - (endTime * 1000.0).toLong() > POST_CREDITS_GAP_MS
    return InternalSkipAction(
        targetMs = ((scene?.startTime ?: endTime) * 1000.0).toLong(),
        skipsToPostCredits = scene != null || hasPostCreditsTail,
    )
}

private const val POST_CREDITS_GAP_MS = 5_000L

/**
 * True when [this] movie-credits row is followed by a published scene (`post-credits`),
 * either the gap between two credit runs or IntroDB's post-credits field.
 */
internal fun SkipInterval.showsMovieSceneNotice(
    intervals: List<SkipInterval>,
    durationMs: Long = 0L,
): Boolean {
    if (type.trim().lowercase() != "movie-credits" || !hasValidSeekTimes()) return false
    return intervals.any { scene ->
        scene.type.trim().lowercase() == "post-credits" &&
            scene.hasValidSeekTimes() &&
            scene.startTime + 0.05 >= endTime &&
            scene.startTime >= endTime - 0.05 &&
            (durationMs <= 0L || scene.startTime < durationMs / 1000.0)
    }
}

/** Seek target for a skip press or auto-skip. Null when a movie scene notice is showing. */
internal fun SkipInterval.skipSeekTargetMs(
    intervals: List<SkipInterval>,
    durationMs: Long = 0L,
): Long? {
    if (showsMovieSceneNotice(intervals, durationMs)) return null
    return internalSkipAction(intervals, durationMs)?.targetMs
}

private fun SkipInterval.hasValidSeekTimes(): Boolean =
    startTime.isFinite() && endTime.isFinite() && startTime >= 0 && endTime > startTime &&
        endTime * 1000.0 < Long.MAX_VALUE.toDouble()
