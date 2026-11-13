import { describe, expect, it } from "vitest";
import { ClockError, MAX_HLC_SKEW_MS, TOMBSTONE_KEEP_MS, receive, tick } from "./reference/clock";
import { advancePullCursor } from "./reference/cursor";
import { mergeRecords } from "./reference/merge";
import { recordsToUpload, syncMode } from "./reference/sync-plan";

const NOW = 1_700_000_000_000;

describe("hybrid clock", () => {
  it("does not adopt a wall time past the skew limit", () => {
    const local = { wallMs: 1_000, counter: 0 };
    const future = { wallMs: NOW + MAX_HLC_SKEW_MS + 1, counter: 0, deviceId: "device-a" };
    expect(receive(local, future, NOW)).toEqual(local);
    const within = { wallMs: NOW + 3_600_000, counter: 4, deviceId: "device-a" };
    expect(receive(local, within, NOW)).toEqual({ wallMs: within.wallMs, counter: 4 });
  });

  it("drops a saved clock that is outside the skew window", () => {
    const next = tick({ wallMs: NOW + MAX_HLC_SKEW_MS + 50_000, counter: 5 }, NOW, "device-a");
    expect(next).toEqual({ wallMs: NOW, counter: 0, deviceId: "device-a" });
  });

  it("refuses to increment the counter past its maximum", () => {
    expect(() => tick({ wallMs: 5_000, counter: 1_000_000 }, 5_000, "device-a")).toThrow(ClockError);
    expect(tick({ wallMs: 5_000, counter: 1_000_000 }, 5_001, "device-a")).toEqual({
      wallMs: 5_001,
      counter: 0,
      deviceId: "device-a",
    });
  });

  it("rejects a far-future record inside merge and keeps an in-window one", () => {
    const mark = {
      deleted: false,
      durationMs: 3_600_000,
      endMs: 2_000,
      episode: 8,
      hlc: { counter: 0, deviceId: "device-a", wallMs: NOW + 60_000 },
      id: "mark/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa21",
      kind: "mark",
      presence: "range",
      season: 1,
      segmentIndex: 0,
      segmentType: "intro",
      showId: "tmdb:100",
      startMs: 1_000,
    };
    const future = {
      deleted: true,
      hlc: { counter: 0, deviceId: "device-b", wallMs: NOW + MAX_HLC_SKEW_MS + 1 },
      id: mark.id,
      kind: "mark",
    };
    const state = mergeRecords([mark, future], NOW);
    expect(state.rejected).toBe(1);
    expect(state.tombstones).toEqual([]);
    expect(state.shows[0]?.episodes[0]?.marks[0]?.applied).toBe(true);
  });
});

describe("tombstone window", () => {
  it("uploads nothing stale after a 90 day gap and still uploads a new edit", () => {
    const lastSync = 1_000;
    const now = lastSync + TOMBSTONE_KEEP_MS;
    expect(syncMode(lastSync, now - 1)).toBe("incremental");
    expect(syncMode(lastSync, now)).toBe("full-resync");
    expect(syncMode(null, now)).toBe("incremental");
    const stale = { id: "old", hlc: { wallMs: lastSync } };
    const fresh = { id: "new", hlc: { wallMs: now - 1_000 } };
    expect(recordsToUpload([stale, fresh], lastSync, now).map((row) => row.id)).toEqual(["new"]);
    expect(recordsToUpload([stale], null, now)).toEqual([stale]);
  });
});

describe("pull cursor", () => {
  it("advances only to the last seq on a pull page", () => {
    expect(advancePullCursor(0, { cursor: 2, blobs: [{ seq: 1 }, { seq: 2 }] })).toBe(2);
    expect(advancePullCursor(2, { cursor: 2, blobs: [] })).toBe(2);
    expect(advancePullCursor(1, { cursor: 4, blobs: [{ seq: 4 }] })).toBe(4);
    expect(() => advancePullCursor(5, { cursor: 4, blobs: [{ seq: 4 }] })).toThrow(/backwards/);
    expect(() => advancePullCursor(0, { cursor: 5, blobs: [{ seq: 1 }] })).toThrow(/cursor must be the last/);
  });
});
