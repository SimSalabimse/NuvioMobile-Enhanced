/**
 * What a device uploads. A gap of TOMBSTONE_KEEP_MS since the last successful
 * sync means tombstones for deletes in that gap may already be gone. Uploading
 * the old local copy would recreate the deleted record.
 */
import { TOMBSTONE_KEEP_MS } from "./clock";
import { mergeRecords, type MergedState } from "./merge";

export type SyncMode = "incremental" | "full-resync";

export function syncMode(lastSuccessfulSyncMs: number | null, nowMs: number): SyncMode {
  if (lastSuccessfulSyncMs === null) return "incremental";
  if (!Number.isSafeInteger(lastSuccessfulSyncMs) || !Number.isSafeInteger(nowMs)) {
    throw new Error("bad sync clock");
  }
  if (nowMs - lastSuccessfulSyncMs >= TOMBSTONE_KEEP_MS) return "full-resync";
  return "incremental";
}

export function recordsToUpload<T extends { hlc: { wallMs: number } }>(
  local: readonly T[],
  lastSuccessfulSyncMs: number | null,
  nowMs: number,
): T[] {
  if (syncMode(lastSuccessfulSyncMs, nowMs) === "incremental") return [...local];
  return local.filter((record) => record.hlc.wallMs > nowMs - TOMBSTONE_KEEP_MS);
}

/** Remote records are the store. Local records older than the keep window are not merged back in. */
export function adoptFullResync(remoteRecords: readonly unknown[], freshLocal: readonly unknown[], nowMs: number): MergedState {
  return mergeRecords([...remoteRecords, ...freshLocal], nowMs);
}
