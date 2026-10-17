package com.nuvio.app.features.downloads

import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration

/**
 * When a playback counts as truly over for delete-after-watch and smart downloads. The watched
 * threshold (90 %, or Trakt/MDBList marking an episode watched at 80 %) and the player's "ended"
 * flag (which partial downloads and source switches can raise early) are not used: only the
 * position the player reported, against the detected credits start or the last minutes.
 */
internal object PlaybackEndRules {
    /** Delete only within this much of the end (or from the credits start, if that is earlier). */
    const val END_WINDOW_MS = 120_000L
    /** Smart downloads start the next episodes within this much of the end (or at credits). */
    const val NEAR_END_WINDOW_MS = 300_000L
    /** Credits detected before this share of the runtime are treated as a false match. */
    private const val MIN_CREDITS_FRACTION = 0.9

    fun usableCreditsStart(creditsStartMs: Long?, durationMs: Long): Long? {
        val credits = creditsStartMs ?: return null
        if (durationMs <= 0L || credits >= durationMs) return null
        return credits.takeIf { it >= (durationMs * MIN_CREDITS_FRACTION).toLong() }
    }

    private fun threshold(durationMs: Long, creditsStartMs: Long?, windowMs: Long): Long? {
        if (durationMs <= 0L || isShortPlaceholderDuration(durationMs)) return null
        val byWindow = (durationMs - windowMs).coerceAtLeast(0L)
        val credits = usableCreditsStart(creditsStartMs, durationMs)
        return if (credits != null) minOf(credits, byWindow) else byWindow
    }

    /** The episode is over: at the credits or in the last [END_WINDOW_MS]. */
    fun reachedEnd(positionMs: Long, durationMs: Long, creditsStartMs: Long?): Boolean {
        val at = threshold(durationMs, creditsStartMs, END_WINDOW_MS) ?: return false
        return positionMs >= at
    }

    /** Smart downloads start this long before the credits. */
    const val SMART_BEFORE_CREDITS_MS = 180_000L
    /** Without credit times: this long before the end, or [SMART_FALLBACK_FRACTION], whichever is first. */
    const val SMART_BEFORE_END_MS = 480_000L
    const val SMART_FALLBACK_FRACTION = 0.85
    /** Next episode this big (or larger): start at half-way instead. */
    const val SMART_LARGE_EPISODE_BYTES = 2L * 1024 * 1024 * 1024
    /** Credits earlier than this share of the runtime are not trusted for the early trigger. */
    private const val SMART_MIN_CREDITS_FRACTION = 0.6

    /** Where smart downloads fetch the next episodes while this one plays. */
    fun smartDownloadTriggerMs(durationMs: Long, creditsStartMs: Long?, largeNextEpisode: Boolean): Long? {
        if (durationMs <= 0L || isShortPlaceholderDuration(durationMs)) return null
        val credits = creditsStartMs?.takeIf { it < durationMs && it >= (durationMs * SMART_MIN_CREDITS_FRACTION).toLong() }
        val byTimes = if (credits != null) {
            credits - SMART_BEFORE_CREDITS_MS
        } else {
            minOf(durationMs - SMART_BEFORE_END_MS, (durationMs * SMART_FALLBACK_FRACTION).toLong())
        }
        val at = if (largeNextEpisode) minOf(byTimes, durationMs / 2) else byTimes
        return at.coerceAtLeast(0L)
    }

    /** Close enough to the end to fetch what comes next. */
    fun nearEnd(positionMs: Long, durationMs: Long, creditsStartMs: Long?): Boolean {
        val at = threshold(durationMs, creditsStartMs, NEAR_END_WINDOW_MS) ?: return false
        return positionMs >= at
    }
}
