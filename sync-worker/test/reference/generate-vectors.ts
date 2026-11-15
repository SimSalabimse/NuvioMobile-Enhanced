/**
 * Writes docs/sync/test-vectors/v1. Run from sync-worker:
 *   npx tsx test/reference/generate-vectors.ts
 * Expected ciphertext and merged states come from this run, then the tests recompute them.
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { encodeB64, encodeB64Url, encodeHex } from "./bytes";
import { canonicalJson } from "./canonical";
import { MAX_HLC_SKEW_MS, TOMBSTONE_KEEP_MS } from "./clock";
import { KDF_ITERATIONS, associatedData, associatedDataText, deriveKeys, encryptBlob, joinCode, sha256 } from "./crypto";
import { mergeRecords, type AnalyseQuery } from "./merge";
import type { SegmentType } from "./records";
import { adoptFullResync, recordsToUpload, syncMode } from "./sync-plan";

const outDir = join(dirname(fileURLToPath(import.meta.url)), "../../../docs/sync/test-vectors/v1");
const NOW = 1_700_000_000_000;

function hlc(wallMs: number, counter: number, deviceId: string) {
  return { counter, deviceId, wallMs };
}

function uuid(n: number): string {
  return `aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa${n.toString(16).padStart(2, "0")}`;
}

function episodeKey(season: number | null, episode: number | null): string {
  return season === null || episode === null ? "movie" : `s${season}e${episode}`;
}

function noneId(showId: string, season: number | null, episode: number | null, segmentType: string): string {
  return `none/${showId}/${episodeKey(season, episode)}/${segmentType}`;
}

function detectionId(input: {
  showId: string;
  season: number | null;
  episode: number | null;
  segmentType: string;
  segmentIndex: number;
  durationMs: number;
  algorithm: string;
}): string {
  const version = input.algorithm.slice("nuvio-analysis-".length);
  return `det/${input.showId}/${episodeKey(input.season, input.episode)}/${input.segmentType}/${input.segmentIndex}/${input.durationMs}/a${version}`;
}

function rangeMark(input: {
  n: number;
  showId: string;
  season: number | null;
  episode: number | null;
  segmentType: SegmentType;
  segmentIndex: number;
  startMs: number;
  endMs: number;
  durationMs: number;
  wallMs: number;
  counter?: number;
  deviceId: string;
}) {
  return {
    deleted: false,
    durationMs: input.durationMs,
    endMs: input.endMs,
    episode: input.episode,
    hlc: hlc(input.wallMs, input.counter ?? 0, input.deviceId),
    id: `mark/${uuid(input.n)}`,
    kind: "mark",
    presence: "range",
    season: input.season,
    segmentIndex: input.segmentIndex,
    segmentType: input.segmentType,
    showId: input.showId,
    startMs: input.startMs,
  };
}

function noneMark(input: {
  showId: string;
  season: number | null;
  episode: number | null;
  segmentType: SegmentType;
  durationMs: number;
  wallMs: number;
  deviceId: string;
}) {
  return {
    deleted: false,
    durationMs: input.durationMs,
    episode: input.episode,
    hlc: hlc(input.wallMs, 0, input.deviceId),
    id: noneId(input.showId, input.season, input.episode, input.segmentType),
    kind: "mark",
    presence: "none",
    season: input.season,
    segmentType: input.segmentType,
    showId: input.showId,
  };
}

function detection(input: {
  showId: string;
  season: number | null;
  episode: number | null;
  segmentType: SegmentType;
  segmentIndex: number;
  durationMs: number;
  algorithm: string;
  outcome: "found" | "not_found";
  startMs?: number;
  endMs?: number;
  wallMs: number;
  deviceId: string;
  releaseId?: string;
}) {
  const id = detectionId(input);
  const base = {
    algorithm: input.algorithm,
    deleted: false,
    durationMs: input.durationMs,
    episode: input.episode,
    hlc: hlc(input.wallMs, 0, input.deviceId),
    id,
    kind: "detection",
    outcome: input.outcome,
    season: input.season,
    segmentIndex: input.segmentIndex,
    segmentType: input.segmentType,
    showId: input.showId,
    ...(input.releaseId ? { releaseId: input.releaseId } : {}),
  };
  if (input.outcome === "not_found") return base;
  return { ...base, startMs: input.startMs, endMs: input.endMs };
}

function scenario(name: string, records: unknown[], queries: Array<AnalyseQuery & { expectAnalyse: boolean }>, nowMs = NOW) {
  const state = mergeRecords(records, nowMs);
  return {
    name,
    nowMs,
    records,
    queries,
    merged: state,
    mergedCanonical: canonicalJson(state),
  };
}

async function main(): Promise<void> {
  mkdirSync(outDir, { recursive: true });
  const started = Date.now();
  const passphrase = "test-passphrase-not-a-secret";
  const salt = Uint8Array.from({ length: 16 }, (_, index) => index);
  const nonce = Uint8Array.from({ length: 12 }, (_, index) => index + 16);
  const spaceId = "11111111-1111-4111-8111-111111111111";
  const blobId = "22222222-2222-4222-8222-222222222222";
  const deviceId = "device-a";
  const keys = await deriveKeys(passphrase, salt);
  const kind = "mark";
  const plainRecord = rangeMark({
    n: 1,
    showId: "tmdb:100",
    season: 1,
    episode: 8,
    segmentType: "intro",
    segmentIndex: 0,
    startMs: 42_000,
    endMs: 92_000,
    durationMs: 3_600_000,
    wallMs: NOW,
    deviceId,
  });
  const plaintext = canonicalJson(plainRecord);
  const routing = { formatVersion: 1, spaceId, blobId, deviceId, kind };
  const aadText = associatedDataText(routing);
  const ciphertext = await encryptBlob(keys.encKey, nonce, associatedData(routing), new TextEncoder().encode(plaintext));
  const wrong = await deriveKeys(`${passphrase}-wrong`, salt);
  const tokenHash = await sha256(keys.authToken);
  const cryptoVector = {
    v: 1,
    description: "Fixed passphrase, salt and nonce. The nonce is a test value and must not be reused with a real passphrase.",
    passphrase,
    kdf: "pbkdf2-sha256",
    iterations: KDF_ITERATIONS,
    saltBase64: encodeB64(salt),
    nonceBase64: encodeB64(nonce),
    spaceId,
    blobId,
    deviceId,
    formatVersion: 1,
    kind,
    aad: aadText,
    plaintext,
    masterKeyHex: encodeHex(keys.master),
    encKeyHex: encodeHex(keys.encKey),
    authTokenHex: encodeHex(keys.authToken),
    authTokenBase64Url: encodeB64Url(keys.authToken),
    tokenHashHex: encodeHex(tokenHash),
    keyCheckBase64: encodeB64(keys.keyCheck),
    ciphertextBase64: encodeB64(ciphertext),
    joinCode: joinCode(spaceId, salt, keys.keyCheck),
    wrongPassphrase: `${passphrase}-wrong`,
    wrongKeyCheckBase64: encodeB64(wrong.keyCheck),
  };
  write("crypto.json", cryptoVector);

  const nfd = "cafe\u0301";
  const nfcKeys = await deriveKeys(nfd, salt);
  write("passphrase-nfc.json", {
    v: 1,
    description: "NFD and NFC of the same passphrase derive the same master key. The passphrase is normalized to NFC before PBKDF2.",
    nfdPassphrase: nfd,
    nfcPassphrase: nfd.normalize("NFC"),
    saltBase64: encodeB64(salt),
    iterations: KDF_ITERATIONS,
    masterKeyHex: encodeHex(nfcKeys.master),
  });

  const show = "tmdb:100";
  const markBeats = [
    rangeMark({
      n: 2,
      showId: show,
      season: 1,
      episode: 8,
      segmentType: "intro",
      segmentIndex: 0,
      startMs: 30_000,
      endMs: 40_000,
      durationMs: 3_600_000,
      wallMs: 1_000,
      deviceId: "device-a",
    }),
    rangeMark({
      n: 3,
      showId: show,
      season: 1,
      episode: 8,
      segmentType: "intro",
      segmentIndex: 1,
      startMs: 50_000,
      endMs: 60_000,
      durationMs: 3_600_000,
      wallMs: 1_000,
      deviceId: "device-a",
    }),
    detection({
      showId: show,
      season: 1,
      episode: 8,
      segmentType: "intro",
      segmentIndex: 0,
      durationMs: 3_600_000,
      algorithm: "nuvio-analysis-1",
      outcome: "found",
      startMs: 1_000,
      endMs: 2_000,
      wallMs: 9_000,
      deviceId: "device-b",
      releaseId: "imdb:tt0000100",
    }),
    detection({
      showId: show,
      season: 1,
      episode: 8,
      segmentType: "recap",
      segmentIndex: 0,
      durationMs: 3_600_000,
      algorithm: "nuvio-analysis-1",
      outcome: "found",
      startMs: 70_000,
      endMs: 80_000,
      wallMs: 1_000,
      deviceId: "device-a",
    }),
  ];
  write("mark-beats-detection.json", scenario("mark-beats-detection", markBeats, [
    { showId: show, season: 1, episode: 8, segmentType: "intro", segmentIndex: 0, fileDurationMs: 3_600_000, localAlgorithm: 1, expectAnalyse: false },
    { showId: show, season: 1, episode: 8, segmentType: "intro", segmentIndex: 1, fileDurationMs: 3_600_000, localAlgorithm: 1, expectAnalyse: false },
    { showId: show, season: 1, episode: 8, segmentType: "intro", segmentIndex: 0, fileDurationMs: 2_400_000, localAlgorithm: 1, expectAnalyse: true },
    { showId: show, season: 1, episode: 8, segmentType: "recap", segmentIndex: 0, fileDurationMs: 3_600_000, localAlgorithm: 1, expectAnalyse: false },
    { showId: show, season: 1, episode: 8, segmentType: "recap", segmentIndex: 0, fileDurationMs: 3_600_000, localAlgorithm: 2, expectAnalyse: true },
  ]));

  const twoCuts = [
    detection({
      showId: show,
      season: 1,
      episode: 8,
      segmentType: "intro",
      segmentIndex: 0,
      durationMs: 2_400_000,
      algorithm: "nuvio-analysis-1",
      outcome: "found",
      startMs: 1_000,
      endMs: 2_000,
      wallMs: 100,
      deviceId: "device-a",
    }),
    detection({
      showId: show,
      season: 1,
      episode: 8,
      segmentType: "intro",
      segmentIndex: 0,
      durationMs: 2_400_000,
      algorithm: "nuvio-analysis-2",
      outcome: "found",
      startMs: 1_500,
      endMs: 2_500,
      wallMs: 50,
      deviceId: "device-b",
    }),
    detection({
      showId: show,
      season: 1,
      episode: 8,
      segmentType: "intro",
      segmentIndex: 0,
      durationMs: 3_600_000,
      algorithm: "nuvio-analysis-1",
      outcome: "found",
      startMs: 3_000,
      endMs: 4_000,
      wallMs: 80,
      deviceId: "device-a",
    }),
  ];
  write("two-cuts.json", scenario("two-cuts", twoCuts, [
    { showId: show, season: 1, episode: 8, segmentType: "intro", segmentIndex: 0, fileDurationMs: 2_400_000, localAlgorithm: 2, expectAnalyse: false },
    { showId: show, season: 1, episode: 8, segmentType: "intro", segmentIndex: 0, fileDurationMs: 2_430_000, localAlgorithm: 2, expectAnalyse: false },
    { showId: show, season: 1, episode: 8, segmentType: "intro", segmentIndex: 0, fileDurationMs: 2_430_001, localAlgorithm: 2, expectAnalyse: true },
    { showId: show, season: 1, episode: 8, segmentType: "intro", segmentIndex: 0, fileDurationMs: 2_400_000, localAlgorithm: 3, expectAnalyse: true },
    { showId: show, season: 1, episode: 8, segmentType: "intro", segmentIndex: 0, fileDurationMs: 3_600_000, localAlgorithm: 1, expectAnalyse: false },
    { showId: show, season: 1, episode: 8, segmentType: "intro", segmentIndex: 0, fileDurationMs: 5_000_000, localAlgorithm: 1, expectAnalyse: true },
  ]));

  const tombId = `mark/${uuid(4)}`;
  const tombstoneRecords = [
    rangeMark({
      n: 4,
      showId: show,
      season: 1,
      episode: 8,
      segmentType: "intro",
      segmentIndex: 0,
      startMs: 10_000,
      endMs: 20_000,
      durationMs: 3_600_000,
      wallMs: 1_000_000,
      deviceId: "device-a",
    }),
    { deleted: true, hlc: hlc(2_000_000, 0, "device-b"), id: tombId, kind: "mark" },
  ];
  const kept = mergeRecords(tombstoneRecords, 2_000_000);
  const purgedAt = 2_000_000 + TOMBSTONE_KEEP_MS;
  const purged = mergeRecords(tombstoneRecords, purgedAt);
  write("tombstone.json", {
    v: 1,
    name: "tombstone",
    records: tombstoneRecords,
    cases: [
      { nowMs: 2_000_000, merged: kept, mergedCanonical: canonicalJson(kept) },
      { nowMs: purgedAt, merged: purged, mergedCanonical: canonicalJson(purged) },
    ],
  });

  const tiedId = `mark/${uuid(5)}`;
  const tieRecords = [
    {
      ...rangeMark({
        n: 5,
        showId: show,
        season: 1,
        episode: 8,
        segmentType: "credits",
        segmentIndex: 0,
        startMs: 1_000,
        endMs: 2_000,
        durationMs: 3_600_000,
        wallMs: 5_000,
        counter: 3,
        deviceId: "device-a",
      }),
      id: tiedId,
    },
    {
      ...rangeMark({
        n: 5,
        showId: show,
        season: 1,
        episode: 8,
        segmentType: "credits",
        segmentIndex: 0,
        startMs: 9_000,
        endMs: 10_000,
        durationMs: 3_600_000,
        wallMs: 5_000,
        counter: 3,
        deviceId: "device-b",
      }),
      id: tiedId,
    },
  ];
  write("clock-tie.json", scenario("clock-tie", tieRecords, []));

  const jsonTieId = `mark/${uuid(15)}`;
  const jsonTieRecords = [1_000, 9_000].map((startMs) => ({
    ...rangeMark({
      n: 15,
      showId: show,
      season: 1,
      episode: 8,
      segmentType: "intro",
      segmentIndex: 0,
      startMs,
      endMs: startMs + 1_000,
      durationMs: 3_600_000,
      wallMs: 5_000,
      counter: 3,
      deviceId: "device-a",
    }),
    id: jsonTieId,
  }));
  write("clock-json-tie.json", scenario("clock-json-tie", jsonTieRecords, []));

  const skewedNow = NOW;
  const skewedLive = rangeMark({
    n: 16,
    showId: show,
    season: 1,
    episode: 8,
    segmentType: "intro",
    segmentIndex: 0,
    startMs: 10_000,
    endMs: 20_000,
    durationMs: 3_600_000,
    wallMs: skewedNow - 10_000,
    deviceId: "device-a",
  });
  const futureTombstone = {
    deleted: true,
    hlc: hlc(skewedNow + MAX_HLC_SKEW_MS + 1, 0, "device-b"),
    id: skewedLive.id,
    kind: "mark",
  };
  write("clock-skew.json", scenario("clock-skew", [skewedLive, futureTombstone], [
    { showId: show, season: 1, episode: 8, segmentType: "intro", segmentIndex: 0, fileDurationMs: 3_600_000, localAlgorithm: 1, expectAnalyse: false },
  ], skewedNow));

  const staleMark = rangeMark({
    n: 17,
    showId: show,
    season: 1,
    episode: 8,
    segmentType: "intro",
    segmentIndex: 0,
    startMs: 10_000,
    endMs: 20_000,
    durationMs: 3_600_000,
    wallMs: 1_000_000,
    deviceId: "device-a",
  });
  const freshMark = rangeMark({
    n: 18,
    showId: show,
    season: 1,
    episode: 8,
    segmentType: "recap",
    segmentIndex: 0,
    startMs: 1_000,
    endMs: 2_000,
    durationMs: 3_600_000,
    wallMs: purgedAt - 1_000,
    deviceId: "device-a",
  });
  const lastSuccessfulSyncMs = 1_000_000;
  const remoteRecords: unknown[] = [];
  const staleLocalRecords = [staleMark, freshMark];
  const uploaded = recordsToUpload(staleLocalRecords, lastSuccessfulSyncMs, purgedAt);
  const adopted = adoptFullResync(remoteRecords, uploaded, purgedAt);
  write("offline-resync.json", {
    v: 1,
    name: "offline-resync",
    description: "The device's last sync is at least 90 days ago. The stale mark is not uploaded. A mark newer than the keep window still is.",
    lastSuccessfulSyncMs,
    nowMs: purgedAt,
    mode: syncMode(lastSuccessfulSyncMs, purgedAt),
    remoteRecords,
    staleLocalRecords,
    pushLocal: uploaded,
    adopted,
    adoptedCanonical: canonicalJson(adopted),
    resurrectedCanonical: canonicalJson(mergeRecords([staleMark], purgedAt)),
    withinWindowMode: syncMode(lastSuccessfulSyncMs, 2_000_000),
  });

  const aliasRecords = [
    {
      deleted: false,
      from: "imdb:tt0000100",
      hlc: hlc(1_000, 0, "device-a"),
      id: "alias/imdb:tt0000100",
      kind: "alias",
      to: "tmdb:100",
    },
    rangeMark({
      n: 6,
      showId: "imdb:tt0000100",
      season: 1,
      episode: 8,
      segmentType: "intro",
      segmentIndex: 0,
      startMs: 30_000,
      endMs: 40_000,
      durationMs: 3_600_000,
      wallMs: 2_000,
      deviceId: "device-a",
    }),
    rangeMark({
      n: 7,
      showId: "imdb:tt100",
      season: 1,
      episode: 8,
      segmentType: "intro",
      segmentIndex: 1,
      startMs: 50_000,
      endMs: 60_000,
      durationMs: 3_600_000,
      wallMs: 2_000,
      deviceId: "device-b",
    }),
    detection({
      showId: "tmdb:100",
      season: 1,
      episode: 8,
      segmentType: "intro",
      segmentIndex: 0,
      durationMs: 3_600_000,
      algorithm: "nuvio-analysis-1",
      outcome: "found",
      startMs: 1_000,
      endMs: 2_000,
      wallMs: 9_000,
      deviceId: "device-a",
    }),
  ];
  const unlinked = [
    ...aliasRecords,
    { deleted: true, hlc: hlc(3_000, 0, "device-b"), id: "alias/imdb:tt0000100", kind: "alias" },
  ];
  write("alias-merge.json", {
    v: 1,
    name: "alias-merge",
    scenarios: [
      scenario("linked", aliasRecords, [
        { showId: "imdb:tt100", season: 1, episode: 8, segmentType: "intro", segmentIndex: 0, fileDurationMs: 3_600_000, localAlgorithm: 1, expectAnalyse: false },
      ]),
      scenario("alias-removed", unlinked, [
        { showId: "tmdb:100", season: 1, episode: 8, segmentType: "intro", segmentIndex: 0, fileDurationMs: 3_600_000, localAlgorithm: 1, expectAnalyse: false },
        { showId: "imdb:tt100", season: 1, episode: 8, segmentType: "intro", segmentIndex: 0, fileDurationMs: 2_400_000, localAlgorithm: 1, expectAnalyse: true },
      ]),
    ],
  });

  const notFound = detection({
    showId: show,
    season: 1,
    episode: 8,
    segmentType: "intro",
    segmentIndex: 0,
    durationMs: 3_600_000,
    algorithm: "nuvio-analysis-1",
    outcome: "not_found",
    wallMs: 1_000,
    deviceId: "device-a",
  });
  const none = noneMark({
    showId: show,
    season: 1,
    episode: 8,
    segmentType: "intro",
    durationMs: 0,
    wallMs: 2_000,
    deviceId: "device-b",
  });
  const query: AnalyseQuery & { expectAnalyse: boolean } = {
    showId: show,
    season: 1,
    episode: 8,
    segmentType: "intro",
    segmentIndex: 0,
    fileDurationMs: 3_600_000,
    localAlgorithm: 1,
    expectAnalyse: true,
  };
  write("not-found-vs-none.json", {
    v: 1,
    name: "not-found-vs-none",
    scenarios: [
      scenario("not-found-alone", [notFound], [query]),
      scenario("none-here", [notFound, none], [{ ...query, expectAnalyse: false }]),
      scenario("no-record", [], [query]),
    ],
  });

  const keyA = "fake-theintrodb-key";
  const keyB = "fake-theintrodb-key-other";
  const scopeA = encodeHex(await sha256(new TextEncoder().encode(keyA)));
  const scopeB = encodeHex(await sha256(new TextEncoder().encode(keyB)));
  const submissions = [keyA, keyB].map((_, index) => {
    const accountScope = index === 0 ? scopeA : scopeB;
    const remoteId = `remote-${index + 1}`;
    return {
      accountScope,
      deleted: false,
      durationMs: 3_600_000,
      endMs: 90_000,
      episode: 8,
      hlc: hlc(1_000, 0, "device-a"),
      id: `submission/${accountScope}/${remoteId}`,
      kind: "submission",
      mediaType: "tv",
      remoteId,
      season: 1,
      segmentType: "intro",
      showId: show,
      startMs: 40_000,
      status: "approved",
    };
  });
  write("submission-scope.json", {
    v: 1,
    description: "The account keys below are obvious fakes used only to lock the SHA-256 scope. They are not API keys.",
    fakeAccountKeys: [keyA, keyB],
    ...scenario("submission-scope", submissions, []),
  });

  const zeroDuration = [
    rangeMark({
      n: 8,
      showId: show,
      season: 1,
      episode: 8,
      segmentType: "preview",
      segmentIndex: 0,
      startMs: 100,
      endMs: 200,
      durationMs: 0,
      wallMs: 1_000,
      deviceId: "device-a",
    }),
  ];
  write("zero-duration-mark.json", scenario("zero-duration-mark", zeroDuration, [
    { showId: show, season: 1, episode: 8, segmentType: "preview", segmentIndex: 0, fileDurationMs: 9_999_000, localAlgorithm: 1, expectAnalyse: false },
    { showId: show, season: 1, episode: 8, segmentType: "preview", segmentIndex: 1, fileDurationMs: 9_999_000, localAlgorithm: 1, expectAnalyse: true },
    { showId: show, season: 1, episode: 8, segmentType: "intro", segmentIndex: 0, fileDurationMs: 9_999_000, localAlgorithm: 1, expectAnalyse: true },
  ]));

  write("rejected.json", scenario("rejected", [
    rangeMark({
      n: 9,
      showId: show,
      season: 1,
      episode: 8,
      segmentType: "intro",
      segmentIndex: 0,
      startMs: 1_000,
      endMs: 2_000,
      durationMs: 3_600_000,
      wallMs: 1_000,
      deviceId: "device-a",
    }),
    { kind: "mark", id: "not-a-real-id" },
    { kind: "detection", outcome: "not_found", presence: "none" },
  ], []));

  const elapsed = Date.now() - started;
  process.stderr.write(`wrote vectors in ${elapsed} ms (includes PBKDF2)\n`);
}

function write(name: string, value: unknown): void {
  writeFileSync(join(outDir, name), `${JSON.stringify(value, null, 2)}\n`);
}

await main();
