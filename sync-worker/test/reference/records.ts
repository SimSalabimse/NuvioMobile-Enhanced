/** Closed v1 record parser. Unknown keys are rejected so clients cannot drift. */

import { canonicalJson } from "./canonical";
import { MAX_HLC_COUNTER } from "./clock";

export const SEGMENT_TYPES = ["intro", "recap", "credits", "preview"] as const;
export type SegmentType = (typeof SEGMENT_TYPES)[number];

const DEVICE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const TMDB = /^tmdb:[1-9][0-9]{0,9}$/;
const IMDB = /^imdb:tt[0-9]{1,10}$/;
const RAW = /^raw:[A-Za-z0-9._-]{1,128}$/;
const ALGORITHM = /^nuvio-analysis-([1-9][0-9]{0,8})$/;
const ACCOUNT = /^[0-9a-f]{64}$/;
const REMOTE_ID = /^[A-Za-z0-9._-]{1,128}$/;
const STATUS = /^[A-Za-z0-9 ._-]{1,32}$/;

export interface Hlc {
  wallMs: number;
  counter: number;
  deviceId: string;
}

export interface Tombstone {
  kind: "mark" | "detection" | "submission" | "alias" | "print";
  id: string;
  deleted: true;
  hlc: Hlc;
}

export interface LiveMark {
  kind: "mark";
  id: string;
  deleted: false;
  hlc: Hlc;
  showId: string;
  season: number | null;
  episode: number | null;
  segmentType: SegmentType;
  presence: "range" | "none";
  segmentIndex: number | null;
  startMs: number | null;
  endMs: number | null;
  durationMs: number;
}

export interface LiveDetection {
  kind: "detection";
  id: string;
  deleted: false;
  hlc: Hlc;
  showId: string;
  season: number | null;
  episode: number | null;
  segmentType: SegmentType;
  segmentIndex: number;
  outcome: "found" | "not_found";
  startMs: number | null;
  endMs: number | null;
  durationMs: number;
  algorithm: string;
  algorithmVersion: number;
  releaseId: string | null;
}

export interface LiveSubmission {
  kind: "submission";
  id: string;
  deleted: false;
  hlc: Hlc;
  accountScope: string;
  remoteId: string;
  showId: string;
  mediaType: "tv" | "movie";
  season: number | null;
  episode: number | null;
  segmentType: SegmentType;
  status: string;
  startMs: number;
  endMs: number;
  durationMs: number;
}

export interface LiveAlias {
  kind: "alias";
  id: string;
  deleted: false;
  hlc: Hlc;
  from: string;
  to: string;
}

export interface LivePrint {
  kind: "print";
  id: string;
  deleted: false;
  hlc: Hlc;
  v: 1;
  algo: "nuvio-print-1";
  showId: string;
  season: number | null;
  episode: number | null;
  segmentType: SegmentType;
  markId: string;
  part: number;
  parts: number;
  startMs: number;
  endMs: number;
  originMs: number;
  durationMs: number;
  stepMs: 100;
  padMs: number;
  frames: string;
}

export type LiveRecord = LiveMark | LiveDetection | LiveSubmission | LiveAlias | LivePrint;
export type SyncRecord = Tombstone | LiveRecord;

export interface ParsedRecord {
  record: SyncRecord;
  wire: Record<string, unknown>;
}

export function parseRecord(value: unknown): ParsedRecord | null {
  if (!isRecord(value)) return null;
  const hlc = parseHlc(value.hlc);
  if (hlc === null || typeof value.kind !== "string" || typeof value.id !== "string" || typeof value.deleted !== "boolean") {
    return null;
  }
  if (value.deleted === true) return parseTombstone(value, hlc);
  if (value.kind === "mark") return parseMark(value, hlc);
  if (value.kind === "detection") return parseDetection(value, hlc);
  if (value.kind === "submission") return parseSubmission(value, hlc);
  if (value.kind === "alias") return parseAlias(value, hlc);
  if (value.kind === "print") return parsePrint(value, hlc);
  return null;
}

export function episodeKey(season: number | null, episode: number | null): string | null {
  if (season === null && episode === null) return "movie";
  if (season === null || episode === null) return null;
  if (!safeInt(season) || season < 0 || !safeInt(episode) || episode < 1) return null;
  return `s${season}e${episode}`;
}

export function isShowId(value: string): boolean {
  if (TMDB.test(value) || RAW.test(value)) return true;
  return IMDB.test(value) && /[1-9]/.test(value.slice("imdb:tt".length));
}

export function normShowId(showId: string): string {
  if (showId.startsWith("tmdb:")) return `tmdb:${Number(showId.slice("tmdb:".length))}`;
  if (showId.startsWith("imdb:tt")) return `imdb:${Number(showId.slice("imdb:tt".length))}`;
  return showId;
}

export function compareHlc(left: Hlc, right: Hlc): number {
  if (left.wallMs !== right.wallMs) return left.wallMs > right.wallMs ? 1 : -1;
  if (left.counter !== right.counter) return left.counter > right.counter ? 1 : -1;
  if (left.deviceId !== right.deviceId) return left.deviceId > right.deviceId ? 1 : -1;
  return 0;
}

export function compareParsed(left: ParsedRecord, right: ParsedRecord): number {
  const clock = compareHlc(left.record.hlc, right.record.hlc);
  if (clock !== 0) return clock;
  if (left.record.id !== right.record.id) return left.record.id > right.record.id ? 1 : -1;
  const leftWire = canonicalJson(left.wire);
  const rightWire = canonicalJson(right.wire);
  if (leftWire === rightWire) return 0;
  return leftWire > rightWire ? 1 : -1;
}

function parseTombstone(value: Record<string, unknown>, hlc: Hlc): ParsedRecord | null {
  if (!keysExact(value, ["deleted", "hlc", "id", "kind"])) return null;
  const kind = value.kind;
  const id = value.id;
  if (kind !== "mark" && kind !== "detection" && kind !== "submission" && kind !== "alias" && kind !== "print") return null;
  if (typeof id !== "string" || !tombstoneIdOk(kind, id)) return null;
  const wire = { deleted: true, hlc, id, kind };
  return { record: { kind, id, deleted: true, hlc }, wire };
}

function parseMark(value: Record<string, unknown>, hlc: Hlc): ParsedRecord | null {
  const presence = value.presence;
  if (presence !== "range" && presence !== "none") return null;
  const located = parseLocation(value);
  if (located === null || !isSegment(value.segmentType) || !safeInt(value.durationMs) || (value.durationMs as number) < 0) {
    return null;
  }
  if (presence === "none") {
    if (!keysExact(value, ["deleted", "durationMs", "episode", "hlc", "id", "kind", "presence", "season", "segmentType", "showId"])) {
      return null;
    }
    const id = `none/${located.showId}/${located.episodeKey}/${value.segmentType}`;
    if (value.id !== id) return null;
    const record: LiveMark = {
      kind: "mark",
      id,
      deleted: false,
      hlc,
      showId: located.showId,
      season: located.season,
      episode: located.episode,
      segmentType: value.segmentType,
      presence: "none",
      segmentIndex: null,
      startMs: null,
      endMs: null,
      durationMs: value.durationMs as number,
    };
    const wire = {
      deleted: false,
      durationMs: record.durationMs,
      episode: record.episode,
      hlc,
      id,
      kind: "mark",
      presence: "none",
      season: record.season,
      segmentType: record.segmentType,
      showId: record.showId,
    };
    return { record, wire };
  }
  if (!keysExact(value, ["deleted", "durationMs", "endMs", "episode", "hlc", "id", "kind", "presence", "season", "segmentIndex", "segmentType", "showId", "startMs"])) {
    return null;
  }
  const range = parseRange(value.startMs, value.endMs);
  if (range === null || !isIndex(value.segmentIndex) || typeof value.id !== "string" || !value.id.startsWith("mark/") || !UUID_V4.test(value.id.slice(5))) {
    return null;
  }
  const record: LiveMark = {
    kind: "mark",
    id: value.id,
    deleted: false,
    hlc,
    showId: located.showId,
    season: located.season,
    episode: located.episode,
    segmentType: value.segmentType,
    presence: "range",
    segmentIndex: value.segmentIndex,
    startMs: range.startMs,
    endMs: range.endMs,
    durationMs: value.durationMs as number,
  };
  const wire = { ...record };
  return { record, wire };
}

function parseDetection(value: Record<string, unknown>, hlc: Hlc): ParsedRecord | null {
  const outcome = value.outcome;
  if (outcome !== "found" && outcome !== "not_found") return null;
  const located = parseLocation(value);
  const version = typeof value.algorithm === "string" ? algorithmVersion(value.algorithm) : null;
  if (located === null || version === null || !isSegment(value.segmentType) || !isIndex(value.segmentIndex)) return null;
  if (!safeInt(value.durationMs) || (value.durationMs as number) < 0) return null;
  const release = parseRelease(value);
  if (release === "bad") return null;
  const required = ["algorithm", "deleted", "durationMs", "episode", "hlc", "id", "kind", "outcome", "season", "segmentIndex", "segmentType", "showId"];
  const withRelease = release === null ? required : [...required, "releaseId"];
  if (outcome === "found") withRelease.push("startMs", "endMs");
  if (!keysExact(value, withRelease)) return null;
  let startMs: number | null = null;
  let endMs: number | null = null;
  if (outcome === "found") {
    const range = parseRange(value.startMs, value.endMs);
    if (range === null) return null;
    startMs = range.startMs;
    endMs = range.endMs;
  }
  const id = `det/${located.showId}/${located.episodeKey}/${value.segmentType}/${value.segmentIndex as number}/${value.durationMs as number}/a${version}`;
  if (value.id !== id) return null;
  const record: LiveDetection = {
    kind: "detection",
    id,
    deleted: false,
    hlc,
    showId: located.showId,
    season: located.season,
    episode: located.episode,
    segmentType: value.segmentType,
    segmentIndex: value.segmentIndex as number,
    outcome,
    startMs,
    endMs,
    durationMs: value.durationMs as number,
    algorithm: value.algorithm as string,
    algorithmVersion: version,
    releaseId: release,
  };
  const wire: Record<string, unknown> = {
    algorithm: record.algorithm,
    deleted: false,
    durationMs: record.durationMs,
    episode: record.episode,
    hlc,
    id,
    kind: "detection",
    outcome,
    season: record.season,
    segmentIndex: record.segmentIndex,
    segmentType: record.segmentType,
    showId: record.showId,
  };
  if (outcome === "found") {
    wire.startMs = startMs;
    wire.endMs = endMs;
  }
  if (release !== null) wire.releaseId = release;
  return { record, wire };
}

function parseSubmission(value: Record<string, unknown>, hlc: Hlc): ParsedRecord | null {
  if (!keysExact(value, ["accountScope", "deleted", "durationMs", "endMs", "episode", "hlc", "id", "kind", "mediaType", "remoteId", "season", "segmentType", "showId", "startMs", "status"])) {
    return null;
  }
  const located = parseLocation(value);
  if (located === null || !isSegment(value.segmentType)) return null;
  if (typeof value.accountScope !== "string" || !ACCOUNT.test(value.accountScope)) return null;
  if (typeof value.remoteId !== "string" || !REMOTE_ID.test(value.remoteId)) return null;
  if (typeof value.status !== "string" || !STATUS.test(value.status)) return null;
  if (value.mediaType !== "tv" && value.mediaType !== "movie") return null;
  if (value.mediaType === "movie" && located.episodeKey !== "movie") return null;
  if (value.mediaType === "tv" && located.episodeKey === "movie") return null;
  if (!safeInt(value.startMs) || (value.startMs as number) < 0 || !safeInt(value.endMs) || !safeInt(value.durationMs)) return null;
  if ((value.durationMs as number) < 0 || (value.endMs as number) < (value.startMs as number)) return null;
  const id = `submission/${value.accountScope}/${value.remoteId}`;
  if (value.id !== id) return null;
  const record: LiveSubmission = {
    kind: "submission",
    id,
    deleted: false,
    hlc,
    accountScope: value.accountScope,
    remoteId: value.remoteId,
    showId: located.showId,
    mediaType: value.mediaType,
    season: located.season,
    episode: located.episode,
    segmentType: value.segmentType,
    status: value.status,
    startMs: value.startMs as number,
    endMs: value.endMs as number,
    durationMs: value.durationMs as number,
  };
  const wire = { ...record };
  return { record, wire };
}

function parseAlias(value: Record<string, unknown>, hlc: Hlc): ParsedRecord | null {
  if (!keysExact(value, ["deleted", "from", "hlc", "id", "kind", "to"])) return null;
  if (typeof value.from !== "string" || typeof value.to !== "string" || !isShowId(value.from) || !isShowId(value.to)) return null;
  if (value.from === value.to) return null;
  const id = `alias/${value.from}`;
  if (value.id !== id) return null;
  const record: LiveAlias = { kind: "alias", id, deleted: false, hlc, from: value.from, to: value.to };
  return { record, wire: { ...record } };
}

const MARK_ID = /^(intro|recap|credits|preview)(-[1-9][0-9]?)?$/;
const PRINT_FRAMES = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

function parsePrint(value: Record<string, unknown>, hlc: Hlc): ParsedRecord | null {
  if (!keysExact(value, [
    "algo", "deleted", "durationMs", "endMs", "episode", "frames", "hlc", "id", "kind",
    "markId", "originMs", "padMs", "part", "parts", "season", "segmentType", "showId",
    "startMs", "stepMs", "v",
  ])) return null;
  if (value.v !== 1 || value.algo !== "nuvio-print-1") return null;
  if (typeof value.showId !== "string" || !isShowId(value.showId)) return null;
  if (!isSegment(value.segmentType) || typeof value.markId !== "string" || !MARK_ID.test(value.markId)) return null;
  if (!value.markId.startsWith(value.segmentType)) return null;
  if (!safeInt(value.part) || !safeInt(value.parts)) return null;
  const part = value.part as number;
  const parts = value.parts as number;
  if (parts < 1 || parts > 99 || part < 0 || part >= parts) return null;
  if (value.stepMs !== 100 || !safeInt(value.padMs) || (value.padMs as number) < 0 || (value.padMs as number) > 10_000) return null;
  if (!safeInt(value.startMs) || !safeInt(value.endMs) || !safeInt(value.originMs) || !safeInt(value.durationMs)) return null;
  const startMs = value.startMs as number;
  const endMs = value.endMs as number;
  const originMs = value.originMs as number;
  const durationMs = value.durationMs as number;
  if (startMs < 0 || endMs <= startMs || originMs < 0 || durationMs < 0) return null;
  const located = episodeKey(value.season as number | null, value.episode as number | null);
  if (value.season !== null && (typeof value.season !== "number" || !safeInt(value.season))) return null;
  if (value.episode !== null && (typeof value.episode !== "number" || !safeInt(value.episode))) return null;
  if (located === null) return null;
  if (typeof value.frames !== "string" || !printFramesOk(value.frames)) return null;
  const id = `print/${value.showId}/${located}/${value.segmentType}/${value.markId}/${part}`;
  if (value.id !== id) return null;
  const record: LivePrint = {
    kind: "print",
    id,
    deleted: false,
    hlc,
    v: 1,
    algo: "nuvio-print-1",
    showId: value.showId,
    season: value.season as number | null,
    episode: value.episode as number | null,
    segmentType: value.segmentType,
    markId: value.markId,
    part,
    parts,
    startMs,
    endMs,
    originMs,
    durationMs,
    stepMs: 100,
    padMs: value.padMs as number,
    frames: value.frames,
  };
  return { record, wire: { ...record } };
}

function printFramesOk(frames: string): boolean {
  if (!PRINT_FRAMES.test(frames)) return false;
  const pad = frames.endsWith("==") ? 2 : frames.endsWith("=") ? 1 : 0;
  const decoded = (frames.length / 4) * 3 - pad;
  return decoded >= 4 && decoded % 4 === 0 && decoded / 4 <= 1200;
}

function parseLocation(value: Record<string, unknown>): { showId: string; season: number | null; episode: number | null; episodeKey: string } | null {
  if (typeof value.showId !== "string" || !isShowId(value.showId)) return null;
  const season = value.season;
  const episode = value.episode;
  if (season === null && episode === null) return { showId: value.showId, season, episode, episodeKey: "movie" };
  if (typeof season !== "number" || typeof episode !== "number") return null;
  const key = episodeKey(season, episode);
  if (key === null || key === "movie") return null;
  return { showId: value.showId, season, episode, episodeKey: key };
}

function parseRange(start: unknown, end: unknown): { startMs: number; endMs: number } | null {
  if (!safeInt(start) || !safeInt(end) || (start as number) < 0 || (end as number) <= (start as number)) return null;
  return { startMs: start as number, endMs: end as number };
}

function parseRelease(value: Record<string, unknown>): string | null | "bad" {
  if (!Object.prototype.hasOwnProperty.call(value, "releaseId")) return null;
  if (typeof value.releaseId !== "string" || !isShowId(value.releaseId)) return "bad";
  return value.releaseId;
}

function parseHlc(value: unknown): Hlc | null {
  if (!isRecord(value) || !keysExact(value, ["counter", "deviceId", "wallMs"])) return null;
  if (!safeInt(value.wallMs) || (value.wallMs as number) < 0) return null;
  if (!safeInt(value.counter) || (value.counter as number) < 0 || (value.counter as number) > MAX_HLC_COUNTER) return null;
  if (typeof value.deviceId !== "string" || !DEVICE_ID.test(value.deviceId)) return null;
  return { wallMs: value.wallMs as number, counter: value.counter as number, deviceId: value.deviceId };
}

function algorithmVersion(algorithm: string): number | null {
  const match = ALGORITHM.exec(algorithm);
  if (match === null) return null;
  return Number(match[1]);
}

function tombstoneIdOk(kind: Tombstone["kind"], id: string): boolean {
  if (kind === "mark") return /^mark\/[0-9a-f-]{36}$/.test(id) && UUID_V4.test(id.slice(5)) || isNoneId(id);
  if (kind === "alias") return id.startsWith("alias/") && isShowId(id.slice("alias/".length));
  if (kind === "submission") {
    const parts = id.split("/");
    return parts.length === 3 && parts[0] === "submission" && ACCOUNT.test(parts[1]!) && REMOTE_ID.test(parts[2]!);
  }
  if (kind === "print") return printIdOk(id);
  const parts = id.split("/");
  if (parts.length !== 7 || parts[0] !== "det") return false;
  return isShowId(parts[1]!) && /^(movie|s[0-9]+e[1-9][0-9]*)$/.test(parts[2]!) && isSegment(parts[3]) &&
    /^[0-9]{1,2}$/.test(parts[4]!) && Number(parts[4]) <= 99 &&
    /^[0-9]+$/.test(parts[5]!) && /^a[1-9][0-9]{0,8}$/.test(parts[6]!);
}

function printIdOk(id: string): boolean {
  const parts = id.split("/");
  if (parts.length !== 6 || parts[0] !== "print") return false;
  const episode = parts[2] ?? "";
  const segment = parts[3] ?? "";
  const mark = parts[4] ?? "";
  const part = parts[5] ?? "";
  return isShowId(parts[1] ?? "") && /^(movie|s[0-9]+e[1-9][0-9]*)$/.test(episode) &&
    isSegment(segment) && MARK_ID.test(mark) && mark.startsWith(segment) &&
    /^[0-9]{1,2}$/.test(part) && Number(part) <= 98;
}

function isNoneId(id: string): boolean {
  const parts = id.split("/");
  if (parts.length !== 4 || parts[0] !== "none") return false;
  return isShowId(parts[1]!) && /^(movie|s[0-9]+e[1-9][0-9]*)$/.test(parts[2]!) && isSegment(parts[3]);
}

function isSegment(value: unknown): value is SegmentType {
  return typeof value === "string" && (SEGMENT_TYPES as readonly string[]).includes(value);
}

function isIndex(value: unknown): value is number {
  return safeInt(value) && (value as number) >= 0 && (value as number) <= 99;
}

function keysExact(value: Record<string, unknown>, expected: readonly string[]): boolean {
  const keys = Object.keys(value);
  if (keys.length !== expected.length) return false;
  const allowed = new Set(expected);
  return keys.every((key) => allowed.has(key));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function safeInt(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value);
}
