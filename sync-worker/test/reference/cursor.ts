/**
 * The pull cursor moves only to the last seq on a pull page.
 * A push assigns seqs. It does not return a cursor. Using a push seq as the
 * next pull cursor skips blobs another device stored under an earlier seq.
 */

export interface PullPage {
  cursor: number;
  blobs: readonly { seq: number }[];
}

export function advancePullCursor(previous: number, page: PullPage): number {
  if (!Number.isSafeInteger(previous) || previous < 0) throw new Error("bad cursor");
  if (!Number.isSafeInteger(page.cursor) || page.cursor < 0) throw new Error("bad cursor");
  if (page.blobs.length === 0) {
    if (page.cursor !== previous) throw new Error("empty page must keep the cursor");
    return previous;
  }
  let prevSeq = previous;
  for (const blob of page.blobs) {
    if (!Number.isSafeInteger(blob.seq) || blob.seq <= prevSeq) throw new Error("seq went backwards");
    prevSeq = blob.seq;
  }
  if (page.cursor !== prevSeq) throw new Error("cursor must be the last pulled seq");
  return page.cursor;
}
