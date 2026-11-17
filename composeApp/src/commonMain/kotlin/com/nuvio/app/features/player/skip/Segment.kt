package com.nuvio.app.features.player.skip

import kotlin.math.max
import kotlin.math.min

/**
 * One segment is an id plus a type plus a range.
 * A skip is one segment, except two of the same type whose gap is under
 * [SEGMENT_JOIN_GAP_SEC] become one skip.
 * Detection still stores one range per type. This type is the playback and submit model.
 */
internal const val SEGMENT_JOIN_GAP_SEC = 2.0

/** Submissions of one type on one episode. The fourth is refused. */
internal const val MAX_SUBMISSIONS_PER_TYPE = 3

internal fun canAcceptSubmission(existingCount: Int): Boolean = existingCount < MAX_SUBMISSIONS_PER_TYPE

/**
 * What the submit chip draws for a type that already has [count] submissions.
 * [Another] is a static digit. Nothing here animates, so Reduce Motion has nothing to follow.
 */
internal enum class SubmitChipMark {
    Open,
    Another,
    Full,
}

internal fun submitChipMark(count: Int): SubmitChipMark = when {
    count <= 0 -> SubmitChipMark.Open
    canAcceptSubmission(count) -> SubmitChipMark.Another
    else -> SubmitChipMark.Full
}

/**
 * A count of 0 is still open. The full check is only for a missing count on a type
 * that was already submitted, so an omitted count map can still show Sent.
 */
internal fun shownSubmitChip(count: Int?, alreadySubmitted: Boolean): SubmitChipMark = when {
    count != null -> submitChipMark(count)
    alreadySubmitted -> SubmitChipMark.Full
    else -> SubmitChipMark.Open
}

internal const val TheIntroDbSegmentCapMessage = "This episode already has 3 of that segment."

/** Stable playback type. Movie credits stay distinct from a later post-credits scene. */
internal fun skipTypeKey(type: String): String = when (type.trim().lowercase()) {
    "intro", "op", "mixed-op", "opening" -> "intro"
    "recap" -> "recap"
    "preview" -> "preview"
    "post-credits" -> "post-credits"
    "movie-credits" -> "movie-credits"
    "outro", "ed", "mixed-ed", "credits", "ending" -> "outro"
    else -> type.trim().lowercase()
}

/**
 * One row this account submitted.
 * A legacy ledger token is one segment whose id is the type and whose times are unknown.
 */
internal data class AccountSegment(
    val id: String,
    val type: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
) {
    fun isLegacy(): Boolean = id == type && startMs == null && endMs == null

    companion object {
        fun legacy(type: String): AccountSegment = AccountSegment(id = type, type = type)
    }
}

internal fun nextSubmissionId(type: String, existing: List<AccountSegment>): String {
    val ids = existing.map { it.id }.toSet()
    if (type !in ids && existing.none { it.type == type }) return type
    var n = 2
    while ("$type-$n" in ids) n += 1
    return "$type-$n"
}

/**
 * How many of [type] this account already has.
 * The profile flag list is not an input: it is shared by every key and by IntroDB.
 */
internal fun accountSubmissionCount(
    ledgerRows: List<AccountSegment>,
    type: String,
    remoteCount: Int,
): Int = maxOf(ledgerRows.count { it.type == type }, remoteCount)

/**
 * Providers are in priority order. The first provider to offer a category keeps
 * every segment of that category. A later provider does not add that category.
 */
internal fun mergeSkipProviders(providerResults: List<List<SkipInterval>>): List<SkipInterval> {
    val chosen = mutableListOf<SkipInterval>()
    val taken = mutableSetOf<String>()
    for (result in providerResults) {
        val batch = mutableListOf<SkipInterval>()
        for (interval in result) {
            val category = segmentCategory(interval.type) ?: continue
            if (category in taken) continue
            batch += interval
        }
        for (interval in batch) {
            segmentCategory(interval.type)?.let { taken += it }
        }
        chosen += batch
    }
    return chosen
}

internal fun rangesOverlap(a: SkipInterval, b: SkipInterval): Boolean =
    a.startTime < b.endTime && b.startTime < a.endTime

/**
 * Type used when a mark meets an interval.
 * A credits mark is stored as outro, and movie credits are that same mark.
 * Post-credits stays its own type, and [skipTypeKey] is unchanged for joining skips.
 */
internal fun markMatchKey(type: String): String = when (type.trim().lowercase()) {
    "movie-credits", "outro", "ed", "mixed-ed", "credits", "ending" -> "outro"
    else -> skipTypeKey(type)
}

/** Pieces of [interval] that sit outside every overlapping [marks] range. */
internal fun subtractRange(interval: SkipInterval, marks: List<SkipInterval>): List<SkipInterval> {
    var pieces = listOf(interval)
    for (mark in marks.sortedBy { it.startTime }) {
        val next = mutableListOf<SkipInterval>()
        for (piece in pieces) {
            if (!rangesOverlap(piece, mark)) {
                next += piece
                continue
            }
            if (piece.startTime < mark.startTime) {
                val left = piece.copy(endTime = mark.startTime)
                if (left.endTime > left.startTime) next += left
            }
            if (piece.endTime > mark.endTime) {
                val right = piece.copy(startTime = mark.endTime)
                if (right.endTime > right.startTime) next += right
            }
        }
        pieces = next
    }
    return pieces
}

/**
 * A user's mark covers a TheIntroDB segment only where the ranges overlap.
 * The rest of that segment stays a skip. A segment that does not overlap stays whole.
 * Any other source of that type is replaced by the mark.
 */
internal fun applyOwnMarks(online: List<SkipInterval>, own: List<SkipInterval>): List<SkipInterval> {
    if (own.isEmpty()) return online
    val ownByType = own.groupBy { markMatchKey(it.type) }
    val kept = mutableListOf<SkipInterval>()
    for (interval in online) {
        val marks = ownByType[markMatchKey(interval.type)].orEmpty()
        if (marks.isEmpty()) {
            kept += interval
        } else if (interval.provider == TheIntroDb.PROVIDER) {
            kept += subtractRange(interval, marks)
        }
    }
    return (kept + own).sortedBy { it.startTime }
}

/** Same type, gap under two seconds: one skip from the earlier start to the later end. */
internal fun joinCloseSkips(intervals: List<SkipInterval>): List<SkipInterval> {
    if (intervals.size < 2) return intervals.sortedBy { it.startTime }
    val joined = mutableListOf<SkipInterval>()
    for ((_, group) in intervals.groupBy { skipTypeKey(it.type) }) {
        val sorted = group.sortedWith(compareBy({ it.startTime }, { it.endTime }))
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            val gap = next.startTime - current.endTime
            if (gap < SEGMENT_JOIN_GAP_SEC) {
                current = current.copy(
                    startTime = min(current.startTime, next.startTime),
                    endTime = max(current.endTime, next.endTime),
                )
            } else {
                joined += current
                current = next
            }
        }
        joined += current
    }
    return joined.sortedBy { it.startTime }
}

/**
 * Online intervals, then the user's marks, then one on-device interval per type
 * that nothing else covered. Close segments of one type are joined last.
 */
internal fun resolvePlaybackSkips(
    online: List<SkipInterval>,
    own: List<SkipInterval>,
    onDevice: List<SkipInterval>,
): List<SkipInterval> {
    val withOwn = applyOwnMarks(online, own)
    val covered = withOwn.mapNotNull { segmentCategory(it.type) }.toSet()
    val extra = onDevice.filter { interval ->
        val category = segmentCategory(interval.type) ?: return@filter false
        category !in covered
    }
    return joinCloseSkips(withOwn + extra)
}

/** Every interval whose type is set to auto-skip. The playhead reaches each one in turn. */
internal fun intervalsAutoSkipped(
    intervals: List<SkipInterval>,
    selected: Set<AutoSkipSegmentType>,
): List<SkipInterval> = intervals.filter { it.shouldAutoSkip(selected) }

internal fun sameSkipTarget(a: SkipInterval, b: SkipInterval): Boolean =
    skipTypeKey(a.type) == skipTypeKey(b.type) &&
        a.startTime == b.startTime &&
        a.endTime == b.endTime &&
        a.provider == b.provider

/** Dismiss sticks to this skip. The next segment of the same type is its own skip. */
internal fun keepSkipDismissed(previous: SkipInterval?, next: SkipInterval?): Boolean =
    previous != null && next != null && sameSkipTarget(previous, next)
