import { describe, expect, it } from "vitest";
import { canonicalJson } from "./reference/canonical";
import { mergeRecords, shouldAnalyse } from "./reference/merge";
import { parseRecord } from "./reference/records";
import printVector from "../../docs/sync/test-vectors/v1/print-roundtrip.json";

describe("show print records", () => {
  it("round-trips one part and drops it when a newer tombstone wins", () => {
    const live = JSON.parse(printVector.plaintext) as unknown;
    expect(canonicalJson(live)).toBe(printVector.plaintext);
    const parsed = parseRecord(live);
    expect(parsed?.record.kind).toBe("print");
    expect(parsed?.record.deleted).toBe(false);
    if (parsed === null || parsed.record.kind !== "print" || parsed.record.deleted) return;
    expect(parsed.record.frames).toBe("AQAAAAIAAAADAAAABAAAAA==");
    expect(parsed.record.algo).toBe("nuvio-print-1");

    const kept = mergeRecords([live], printVector.nowMs);
    expect(kept.prints).toHaveLength(1);
    expect(kept.prints?.[0]?.id).toBe("print/tmdb:100/intro/intro/0");
    expect(kept.shows).toEqual([]);
    expect(shouldAnalyse(kept, {
      showId: "tmdb:100",
      season: 1,
      episode: 1,
      segmentType: "intro",
      segmentIndex: 0,
      fileDurationMs: 1_800_000,
      localAlgorithm: 1,
    })).toBe(true);

    const tombstone = {
      deleted: true,
      hlc: { counter: 0, deviceId: "device-b", wallMs: printVector.nowMs + 1 },
      id: "print/tmdb:100/intro/intro/0",
      kind: "print",
    };
    const removed = mergeRecords([live, tombstone], printVector.nowMs + 1);
    expect(removed.prints).toBeUndefined();
    expect(removed.tombstones.map((row) => row.id)).toEqual(["print/tmdb:100/intro/intro/0"]);
    expect(canonicalJson(removed).includes("\"prints\"")).toBe(false);
  });

  it("rejects an unknown key and a mark id for a different type", () => {
    const live = JSON.parse(printVector.plaintext) as Record<string, unknown>;
    expect(parseRecord({ ...live, extra: 1 })).toBeNull();
    expect(parseRecord({ ...live, markId: "credits", id: "print/tmdb:100/intro/credits/0" })).toBeNull();
  });
});
