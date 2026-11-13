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
  openBlob,
  randomNonce,
  sha256,
  SyncCryptoError,
} from "./reference/crypto";
import { mergeRecords, shouldAnalyse, type AnalyseQuery } from "./reference/merge";
import { canonicalImdbShowId, parseRecord } from "./reference/records";
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
import clockJsonTie from "../../docs/sync/test-vectors/v1/clock-json-tie.json";
import clockSkew from "../../docs/sync/test-vectors/v1/clock-skew.json";
import offlineResync from "../../docs/sync/test-vectors/v1/offline-resync.json";
import idNormalization from "../../docs/sync/test-vectors/v1/id-normalization.json";

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
    const routing = {
      formatVersion: cryptoVector.formatVersion,
      spaceId: cryptoVector.spaceId,
      blobId: cryptoVector.blobId,
      deviceId: cryptoVector.deviceId,
      kind: cryptoVector.kind,
    };
    const cipher = await encryptBlob(
      keys.encKey,
      nonce,
      associatedData(routing),
      new TextEncoder().encode(cryptoVector.plaintext),
    );
    expect(encodeB64(cipher)).toBe(cryptoVector.ciphertextBase64);
    expect(cryptoVector.aad).toBe(`nuvio-sync/1\n${cryptoVector.spaceId}\n${cryptoVector.blobId}\n${cryptoVector.deviceId}\n${cryptoVector.kind}\n`);
    expect(cryptoVector.masterKeyHex).not.toBe(cryptoVector.encKeyHex);
    expect(cryptoVector.encKeyHex).not.toBe(cryptoVector.authTokenHex);
    expect(keys.keyCheck.length).toBe(16);
    const plain = await openBlob(keys.encKey, cryptoVector.spaceId, {
      id: cryptoVector.blobId,
      v: 1,
      kind: cryptoVector.kind,
      deviceId: cryptoVector.deviceId,
      nonce,
      ciphertext: cipher,
    });
    expect(new TextDecoder().decode(plain)).toBe(cryptoVector.plaintext);
    await expect(openBlob(keys.encKey, cryptoVector.spaceId, {
      id: cryptoVector.blobId,
      v: 1,
      kind: "detection",
      deviceId: cryptoVector.deviceId,
      nonce,
      ciphertext: cipher,
    })).rejects.toBeInstanceOf(SyncCryptoError);
    await expect(decryptBlob(keys.authToken, nonce, associatedData(routing), cipher)).rejects.toBeInstanceOf(SyncCryptoError);
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

describe("nonce", () => {
  it("draws 12 bytes from the injected CSPRNG and does not count", () => {
    const filled: number[] = [];
    const nonce = randomNonce((bytes) => {
      expect(bytes.length).toBe(12);
      bytes.set([9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 1, 2]);
      filled.push(bytes.length);
    });
    expect(filled).toEqual([12]);
    expect([...nonce]).toEqual([9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 1, 2]);
    const first = randomNonce();
    const second = randomNonce();
    expect(first.length).toBe(12);
    expect(second.length).toBe(12);
    const incremented = first.every((byte, index) => second[index] === (byte + 1) % 256);
    expect(incremented).toBe(false);
    expect(encodeHex(first)).not.toBe(encodeHex(second));
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

  it("breaks a full clock tie by canonical JSON", () => {
    expectScenario(clockJsonTie);
    const wires = clockJsonTie.records.map((record) => canonicalJson(record));
    const greater = wires[0]! > wires[1]! ? clockJsonTie.records[0] : clockJsonTie.records[1];
    const state = mergeRecords(clockJsonTie.records, clockJsonTie.nowMs);
    const applied = state.shows[0]?.episodes[0]?.marks.filter((row) => row.applied) ?? [];
    expect(applied).toHaveLength(1);
    expect(applied[0]?.startMs).toBe((greater as { startMs: number }).startMs);
    expect(applied[0]?.hlc.deviceId).toBe("device-a");
  });

  it("drops a clock past the skew limit", () => {
    expectScenario(clockSkew);
    const state = mergeRecords(clockSkew.records, clockSkew.nowMs);
    expect(state.rejected).toBe(1);
    expect(state.tombstones).toEqual([]);
    expect(state.shows[0]?.episodes[0]?.marks.filter((row) => row.applied)).toHaveLength(1);
  });

  it("does not resurrect a delete after the tombstone window", () => {
    expect(offlineResync.mode).toBe("full-resync");
    expect(offlineResync.withinWindowMode).toBe("incremental");
    expect(offlineResync.pushLocal).toHaveLength(1);
    expect(offlineResync.pushLocal[0]?.id).toBe(offlineResync.staleLocalRecords[1]?.id);
    expect(canonicalJson(mergeRecords([...offlineResync.remoteRecords, ...offlineResync.pushLocal], offlineResync.nowMs))).toBe(offlineResync.adoptedCanonical);
    expect(JSON.parse(offlineResync.adoptedCanonical).shows[0].episodes[0].marks[0].id).toBe(offlineResync.pushLocal[0]?.id);
    const resurrected = mergeRecords([offlineResync.staleLocalRecords[0]], offlineResync.nowMs);
    expect(canonicalJson(resurrected)).toBe(offlineResync.resurrectedCanonical);
    expect(resurrected.shows[0]?.episodes[0]?.marks[0]?.id).toBe(offlineResync.staleLocalRecords[0]?.id);
    expect(JSON.stringify(JSON.parse(offlineResync.adoptedCanonical))).not.toContain(offlineResync.staleLocalRecords[0]!.id);
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
    expect(alone.shows[0]?.episodes[0]?.marks).toEqual([]);
    expect(alone.shows[0]?.episodes[0]?.effective).toEqual([]);
    expect(alone.shows[0]?.episodes[0]?.detections[0]?.outcome).toBe("not_found");
    const missing = mergeRecords(notFound.scenarios[2]!.records, notFound.scenarios[2]!.nowMs);
    expect(missing.shows).toEqual([]);
    expect(JSON.stringify(missing)).not.toContain("\"presence\":\"none\"");
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

  it("pads imdb ids in record ids and trims the account key", async () => {
    for (const row of idNormalization.imdb) {
      expect(canonicalImdbShowId(row.input)).toBe(row.showId);
    }
    expect(idNormalization.detection.id).toBe("det/imdb:tt0000100/s1e8/intro/0/3600000/a1");
    expect(idNormalization.none.id).toBe("none/imdb:tt0000100/s1e8/intro");
    expect(idNormalization.alias.id).toBe("alias/imdb:tt0000100");
    expect(parseRecord(idNormalization.detection)).not.toBeNull();
    expect(parseRecord(idNormalization.none)).not.toBeNull();
    expect(parseRecord(idNormalization.alias)).not.toBeNull();
    const scope = idNormalization.accountScope;
    expect(scope.trimmed).toBe(scope.key.trim());
    expect(scope.trimmed).not.toBe(scope.key);
    expect(encodeHex(await sha256(new TextEncoder().encode(scope.trimmed)))).toBe(scope.accountScope);
    expect(scope.accountScope).toBe(submissions.records[0]?.accountScope);
  });

  it("counts malformed records and drops them", () => {
    expectScenario(rejected);
    expect(mergeRecords(rejected.records, rejected.nowMs).rejected).toBe(2);
  });
});
