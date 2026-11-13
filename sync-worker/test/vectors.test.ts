import { describe, expect, it } from "vitest";
import { decodeB64 } from "../src/b64";
import { encodeB64, encodeB64Url, encodeHex } from "./reference/bytes";
import { canonicalJson } from "./reference/canonical";
import {
  associatedData,
  decryptBlob,
  deriveKeys,
  encryptBlob,
  keyCheckMatches,
  sha256,
} from "./reference/crypto";
import { mergeRecords, shouldAnalyse, type AnalyseQuery } from "./reference/merge";
import cryptoVector from "../../docs/sync/test-vectors/v1/crypto.json";
import nfcVector from "../../docs/sync/test-vectors/v1/passphrase-nfc.json";
import markBeats from "../../docs/sync/test-vectors/v1/mark-beats-detection.json";
import twoCuts from "../../docs/sync/test-vectors/v1/two-cuts.json";
import tombstone from "../../docs/sync/test-vectors/v1/tombstone.json";
import clockTie from "../../docs/sync/test-vectors/v1/clock-tie.json";
import aliasMerge from "../../docs/sync/test-vectors/v1/alias-merge.json";
import notFound from "../../docs/sync/test-vectors/v1/not-found-vs-none.json";
import submissions from "../../docs/sync/test-vectors/v1/submission-scope.json";
import zeroDuration from "../../docs/sync/test-vectors/v1/zero-duration-mark.json";
import rejected from "../../docs/sync/test-vectors/v1/rejected.json";

function expectScenario(file: {
  nowMs: number;
  records: readonly unknown[];
  queries: ReadonlyArray<{ expectAnalyse: boolean }>;
  mergedCanonical: string;
}): void {
  const state = mergeRecords(file.records, file.nowMs);
  expect(canonicalJson(state)).toBe(file.mergedCanonical);
  for (const query of file.queries) {
    expect(shouldAnalyse(state, query as unknown as AnalyseQuery)).toBe(query.expectAnalyse);
  }
}

describe("crypto vectors", () => {
  it("derives the published keys and ciphertext", async () => {
    const salt = decodeB64(cryptoVector.saltBase64);
    const nonce = decodeB64(cryptoVector.nonceBase64);
    if (salt === null || nonce === null) throw new Error("vector encoding");
    const keys = await deriveKeys(cryptoVector.passphrase, salt);
    expect(encodeHex(keys.master)).toBe(cryptoVector.masterKeyHex);
    expect(encodeHex(keys.encKey)).toBe(cryptoVector.encKeyHex);
    expect(encodeHex(keys.authToken)).toBe(cryptoVector.authTokenHex);
    expect(encodeB64Url(keys.authToken)).toBe(cryptoVector.authTokenBase64Url);
    expect(encodeB64(keys.keyCheck)).toBe(cryptoVector.keyCheckBase64);
    expect(encodeHex(await sha256(keys.authToken))).toBe(cryptoVector.tokenHashHex);
    const cipher = await encryptBlob(
      keys.encKey,
      nonce,
      associatedData(cryptoVector.spaceId, cryptoVector.blobId, cryptoVector.deviceId),
      new TextEncoder().encode(cryptoVector.plaintext),
    );
    expect(encodeB64(cipher)).toBe(cryptoVector.ciphertextBase64);
    const plain = await decryptBlob(
      keys.encKey,
      nonce,
      associatedData(cryptoVector.spaceId, cryptoVector.blobId, cryptoVector.deviceId),
      cipher,
    );
    expect(new TextDecoder().decode(plain)).toBe(cryptoVector.plaintext);
    const wrong = await deriveKeys(cryptoVector.wrongPassphrase, salt);
    expect(encodeB64(wrong.keyCheck)).toBe(cryptoVector.wrongKeyCheckBase64);
    expect(keyCheckMatches(wrong.keyCheck, keys.keyCheck)).toBe(false);
    expect(cryptoVector.joinCode).toBe(
      `nuvio-sync-v1:${cryptoVector.spaceId}:${encodeB64Url(salt)}:${encodeB64Url(keys.keyCheck)}`,
    );
  });

  it("normalizes the passphrase to NFC before PBKDF2", async () => {
    const salt = decodeB64(nfcVector.saltBase64);
    if (salt === null) throw new Error("vector encoding");
    expect(nfcVector.nfdPassphrase.includes("\u0301")).toBe(true);
    expect(nfcVector.nfdPassphrase.normalize("NFC")).toBe(nfcVector.nfcPassphrase);
    expect(nfcVector.nfdPassphrase.length).toBeGreaterThan(nfcVector.nfcPassphrase.length);
    const fromNfd = await deriveKeys(nfcVector.nfdPassphrase, salt);
    const fromNfc = await deriveKeys(nfcVector.nfcPassphrase, salt);
    expect(encodeHex(fromNfd.master)).toBe(nfcVector.masterKeyHex);
    expect(encodeHex(fromNfc.master)).toBe(nfcVector.masterKeyHex);
  });
});

describe("merge vectors", () => {
  it("lets a mark beat a detection", () => {
    expectScenario(markBeats);
    const state = mergeRecords(markBeats.records, markBeats.nowMs);
    const effective = state.shows[0]?.episodes[0]?.effective ?? [];
    expect(effective.filter((row) => row.segmentType === "intro").every((row) => row.source === "mark")).toBe(true);
    expect(effective.some((row) => row.segmentType === "recap" && row.source === "detection")).toBe(true);
  });

  it("keeps two cuts and the higher algorithm inside a cut", () => {
    expectScenario(twoCuts);
    const state = mergeRecords(twoCuts.records, twoCuts.nowMs);
    const applied = state.shows[0]?.episodes[0]?.detections.filter((row) => row.applied) ?? [];
    expect(applied.map((row) => row.durationMs).sort((a, b) => a - b)).toEqual([2_400_000, 3_600_000]);
    expect(applied.find((row) => row.durationMs === 2_400_000)?.algorithm).toBe("nuvio-analysis-2");
  });

  it("keeps a tombstone for 90 days", () => {
    for (const item of tombstone.cases) {
      const state = mergeRecords(tombstone.records, item.nowMs);
      expect(canonicalJson(state)).toBe(item.mergedCanonical);
    }
    const kept = mergeRecords(tombstone.records, tombstone.cases[0]!.nowMs);
    const purged = mergeRecords(tombstone.records, tombstone.cases[1]!.nowMs);
    expect(kept.tombstones).toHaveLength(1);
    expect(kept.shows).toHaveLength(0);
    expect(purged.tombstones).toHaveLength(0);
    expect(purged.shows).toHaveLength(0);
  });

  it("breaks an equal clock by device id", () => {
    expectScenario(clockTie);
    const state = mergeRecords(clockTie.records, clockTie.nowMs);
    const applied = state.shows[0]?.episodes[0]?.marks.filter((row) => row.applied) ?? [];
    expect(applied).toHaveLength(1);
    expect(applied[0]?.hlc.deviceId).toBe("device-b");
    expect(applied[0]?.startMs).toBe(9_000);
  });

  it("merges aliases and splits them when the alias is removed", () => {
    for (const item of aliasMerge.scenarios) expectScenario(item);
    const linked = mergeRecords(aliasMerge.scenarios[0]!.records, aliasMerge.scenarios[0]!.nowMs);
    expect(linked.shows).toHaveLength(1);
    expect(linked.shows[0]?.showId).toBe("tmdb:100");
    const split = mergeRecords(aliasMerge.scenarios[1]!.records, aliasMerge.scenarios[1]!.nowMs);
    expect(split.shows.map((show) => show.showId).sort()).toEqual(["imdb:tt0000100", "tmdb:100"]);
  });

  it("keeps inferred not-found distinct from none here", () => {
    for (const item of notFound.scenarios) expectScenario(item);
    const alone = mergeRecords(notFound.scenarios[0]!.records, notFound.scenarios[0]!.nowMs);
    expect(alone.shows[0]?.episodes[0]?.userNoneTypes).toEqual([]);
    expect(alone.shows[0]?.episodes[0]?.effective).toEqual([]);
    expect(alone.shows[0]?.episodes[0]?.detections[0]?.outcome).toBe("not_found");
    const none = mergeRecords(notFound.scenarios[1]!.records, notFound.scenarios[1]!.nowMs);
    expect(none.shows[0]?.episodes[0]?.userNoneTypes).toEqual(["intro"]);
    expect(none.shows[0]?.episodes[0]?.effective[0]?.source).toBe("mark");
    expect(none.shows[0]?.episodes[0]?.effective[0]?.presence).toBe("none");
    expect(none.shows[0]?.episodes[0]?.detections[0]?.outcome).toBe("not_found");
  });

  it("keeps two submission account scopes apart", () => {
    expectScenario(submissions);
    const state = mergeRecords(submissions.records, submissions.nowMs);
    expect(state.shows).toEqual([]);
    expect(state.submissions.map((row) => row.accountScope)).toEqual(
      [...new Set(submissions.records.map((row) => (row as { accountScope: string }).accountScope))].sort(),
    );
    expect(new Set(state.submissions.map((row) => row.accountScope)).size).toBe(2);
    expect(JSON.stringify(state)).not.toContain("fake-theintrodb-key");
  });

  it("treats a zero duration mark as covering every cut of that slot", () => {
    expectScenario(zeroDuration);
  });

  it("counts malformed records and drops them", () => {
    expectScenario(rejected);
    expect(mergeRecords(rejected.records, rejected.nowMs).rejected).toBe(2);
  });
});
