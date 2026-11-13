package com.nuvio.app.features.analyzedsync.cursor

import com.nuvio.app.features.analyzedsync.SYNC_MAX_SAFE_INTEGER

internal class PullCursorError(message: String) : Exception(message)

internal data class PullCursorPage(val cursor: Long, val blobSeqs: List<Long>)

/** The pull cursor moves only to the last seq on a pull page. A push seq is not a cursor. */
internal fun advancePullCursor(previous: Long, page: PullCursorPage): Long {
    if (previous !in 0..SYNC_MAX_SAFE_INTEGER) throw PullCursorError("bad cursor")
    if (page.cursor !in 0..SYNC_MAX_SAFE_INTEGER) throw PullCursorError("bad cursor")
    if (page.blobSeqs.isEmpty()) {
        if (page.cursor != previous) throw PullCursorError("empty page must keep the cursor")
        return previous
    }
    var prevSeq = previous
    for (seq in page.blobSeqs) {
        if (seq !in 0..SYNC_MAX_SAFE_INTEGER || seq <= prevSeq) throw PullCursorError("seq went backwards")
        prevSeq = seq
    }
    if (page.cursor != prevSeq) throw PullCursorError("cursor must be the last pulled seq")
    return page.cursor
}
