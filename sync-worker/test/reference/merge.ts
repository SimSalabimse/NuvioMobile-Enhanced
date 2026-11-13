/**
 * Reference merge. Playback uses `effective`. Suppressed rows stay so a conflict
 * is visible. Submissions never become marks. not_found never becomes presence none.
 */
import { canonicalJson } from "./canonical";
import { clockIsAcceptable, TOMBSTONE_KEEP_MS } from "./clock";
import {
  SEGMENT_TYPES,
  compareParsed,
  episodeKey,
  normShowId,
  parseRecord,
  type Hlc,
  type LiveAlias,
  type LiveDetection,
  type LiveMark,
  type LiveSubmission,
  type ParsedRecord,
  type SegmentType,
} from "./records";

export const CUT_TOLERANCE_MS = 30_000;
export { TOMBSTONE_KEEP_MS };

export interface MarkView {
  applied: boolean;
  durationMs: number;
  endMs: number | null;
  hlc: Hlc;
  id: string;
  presence: "range" | "none";
  segmentIndex: number | null;
  segmentType: SegmentType;
  startMs: number | null;
  suppressedBy: "none" | "newer-none" | "range" | null;
}

export interface DetectionView {
  algorithm: string;
  algorithmVersion: number;
  applied: boolean;
  durationMs: number;
  endMs: number | null;
  hlc: Hlc;
  id: string;
  outcome: "found" | "not_found";
  releaseId: string | null;
  segmentIndex: number;
  segmentType: SegmentType;
  startMs: number | null;
  suppressedBy: "mark" | "newer-detection" | null;
}

export interface EffectiveSegment {
  algorithm: string | null;
  durationMs: number;
  endMs: number | null;
  id: string;
  presence: "range" | "none";
  segmentIndex: number | null;
  segmentType: SegmentType;
  source: "detection" | "mark";
  startMs: number | null;
}

export interface EpisodeState {
  detections: DetectionView[];
  effective: EffectiveSegment[];
  episode: number | null;
  episodeKey: string;
  marks: MarkView[];
  season: number | null;
  userNoneTypes: SegmentType[];
}

export interface ShowState {
  aliases: string[];
  episodes: EpisodeState[];
  showId: string;
}

export interface SubmissionState {
  accountScope: string;
  durationMs: number;
  endMs: number;
  episode: number | null;
  hlc: Hlc;
  id: string;
  mediaType: "tv" | "movie";
  remoteId: string;
  season: number | null;
  segmentType: SegmentType;
  showId: string;
  startMs: number;
  status: string;
}

export interface TombstoneState {
  hlc: Hlc;
  id: string;
  kind: string;
  purgeAfterMs: number;
}

export interface MergedState {
  rejected: number;
  shows: ShowState[];
  submissions: SubmissionState[];
  tombstones: TombstoneState[];
}

export interface AnalyseQuery {
  showId: string;
  season: number | null;
  episode: number | null;
  segmentType: SegmentType;
  segmentIndex: number;
  fileDurationMs: number;
  localAlgorithm: number;
}

export function mergeRecords(inputs: readonly unknown[], nowMs: number): MergedState {
  const parsed: ParsedRecord[] = [];
  let rejected = 0;
  for (const input of inputs) {
    const record = parseRecord(input);
    // A far-future clock is dropped. It must not win a merge or pin a tombstone.
    if (record === null || !clockIsAcceptable(record.record.hlc.wallMs, nowMs)) rejected += 1;
    else parsed.push(record);
  }
  const winners = collapse(parsed);
  const graph = buildGraph(winners);
  const tombstones: TombstoneState[] = [];
  const marks: LiveMark[] = [];
  const detections: LiveDetection[] = [];
  const submissions: LiveSubmission[] = [];
  for (const winner of winners) {
    if (winner.record.deleted) {
      const purgeAfterMs = winner.record.hlc.wallMs + TOMBSTONE_KEEP_MS;
      if (nowMs < purgeAfterMs) {
        tombstones.push({
          hlc: winner.record.hlc,
          id: winner.record.id,
          kind: winner.record.kind,
          purgeAfterMs,
        });
      }
      continue;
    }
    if (winner.record.kind === "mark") marks.push(winner.record);
    else if (winner.record.kind === "detection") detections.push(winner.record);
    else if (winner.record.kind === "submission") submissions.push(winner.record);
  }
  const episodes = new Map<string, { showId: string; season: number | null; episode: number | null; marks: LiveMark[]; detections: LiveDetection[] }>();
  const addEpisode = (showId: string, season: number | null, episode: number | null) => {
    const canonical = graph.canonicalOf(showId);
    const key = `${canonical}\n${season ?? ""}\n${episode ?? ""}`;
    const existing = episodes.get(key);
    if (existing) return existing;
    const created = { showId: canonical, season, episode, marks: [] as LiveMark[], detections: [] as LiveDetection[] };
    episodes.set(key, created);
    return created;
  };
  for (const mark of marks) addEpisode(mark.showId, mark.season, mark.episode).marks.push(mark);
  for (const detection of detections) addEpisode(detection.showId, detection.season, detection.episode).detections.push(detection);

  const shows = new Map<string, ShowState>();
  for (const bucket of episodes.values()) {
    const show = shows.get(bucket.showId) ?? {
      aliases: graph.aliasesOf(bucket.showId),
      episodes: [],
      showId: bucket.showId,
    };
    const markResult = resolveMarks(bucket.marks);
    const detectionResult = resolveDetections(bucket.detections, markResult.views);
    const effective = [...markResult.effective, ...detectionResult.effective].sort(compareEffective);
    show.episodes.push({
      detections: detectionResult.views,
      effective,
      episode: bucket.episode,
      episodeKey: episodeKey(bucket.season, bucket.episode) ?? "movie",
      marks: markResult.views,
      season: bucket.season,
      userNoneTypes: SEGMENT_TYPES.filter((type) =>
        markResult.views.some((mark) => mark.applied && mark.presence === "none" && mark.segmentType === type),
      ),
    });
    shows.set(bucket.showId, show);
  }
  for (const show of shows.values()) {
    show.episodes.sort((left, right) => episodeRank(left) - episodeRank(right) || cmp(left.episodeKey, right.episodeKey));
  }
  const submissionStates = submissions
    .map((submission) => ({
      accountScope: submission.accountScope,
      durationMs: submission.durationMs,
      endMs: submission.endMs,
      episode: submission.episode,
      hlc: submission.hlc,
      id: submission.id,
      mediaType: submission.mediaType,
      remoteId: submission.remoteId,
      season: submission.season,
      segmentType: submission.segmentType,
      showId: graph.canonicalOf(submission.showId),
      startMs: submission.startMs,
      status: submission.status,
    }))
    .sort((left, right) => cmp(left.accountScope, right.accountScope) || cmp(left.id, right.id));
  tombstones.sort((left, right) => cmp(left.kind, right.kind) || cmp(left.id, right.id));
  return {
    rejected,
    shows: [...shows.values()].sort((left, right) => cmp(left.showId, right.showId)),
    submissions: submissionStates,
    tombstones,
  };
}

export function shouldAnalyse(state: MergedState, query: AnalyseQuery): boolean {
  const show = state.shows.find((item) => item.showId === query.showId || item.aliases.includes(query.showId));
  if (!show) return true;
  const episode = show.episodes.find((item) => item.season === query.season && item.episode === query.episode);
  if (!episode) return true;
  if (episode.userNoneTypes.includes(query.segmentType)) return false;
  const markCovers = episode.marks.some((mark) =>
    mark.applied &&
    mark.presence === "range" &&
    mark.segmentType === query.segmentType &&
    mark.segmentIndex === query.segmentIndex &&
    durationCovers(mark.durationMs, query.fileDurationMs),
  );
  if (markCovers) return false;
  const detectionCovers = episode.detections.some((detection) =>
    detection.applied &&
    detection.outcome === "found" &&
    detection.segmentType === query.segmentType &&
    detection.segmentIndex === query.segmentIndex &&
    detection.algorithmVersion >= query.localAlgorithm &&
    cutsMatch(detection.durationMs, query.fileDurationMs),
  );
  return !detectionCovers;
}

export function mergedCanonical(inputs: readonly unknown[], nowMs: number): string {
  return canonicalJson(mergeRecords(inputs, nowMs));
}

function collapse(parsed: ParsedRecord[]): ParsedRecord[] {
  const winners = new Map<string, ParsedRecord>();
  for (const record of parsed) {
    const key = `${record.record.kind}\n${record.record.id}`;
    const previous = winners.get(key);
    if (!previous || compareParsed(record, previous) > 0) winners.set(key, record);
  }
  return [...winners.values()];
}

function buildGraph(winners: ParsedRecord[]): { canonicalOf: (showId: string) => string; aliasesOf: (showId: string) => string[] } {
  const parent = new Map<string, string>();
  const spellings = new Map<string, Set<string>>();
  const find = (norm: string): string => {
    const current = parent.get(norm);
    if (current === undefined) {
      parent.set(norm, norm);
      return norm;
    }
    if (current === norm) return norm;
    const root = find(current);
    parent.set(norm, root);
    return root;
  };
  const addNorm = (norm: string): string => {
    if (!parent.has(norm)) parent.set(norm, norm);
    return find(norm);
  };
  const addSpelling = (showId: string) => {
    const norm = normShowId(showId);
    addNorm(norm);
    const set = spellings.get(norm) ?? new Set<string>();
    set.add(showId);
    spellings.set(norm, set);
  };
  const union = (left: string, right: string) => {
    const a = addNorm(left);
    const b = addNorm(right);
    if (a !== b) parent.set(a, b);
  };
  for (const winner of winners) {
    const record = winner.record;
    if (record.deleted) continue;
    if (record.kind === "alias") {
      addSpelling(record.from);
      addSpelling(record.to);
      union(normShowId(record.from), normShowId(record.to));
    } else {
      addSpelling(record.showId);
    }
  }
  const byRoot = new Map<string, string[]>();
  for (const norm of parent.keys()) {
    const root = find(norm);
    const list = byRoot.get(root) ?? [];
    list.push(norm);
    byRoot.set(root, list);
  }
  const canonicalByNorm = new Map<string, string>();
  const aliasesByNorm = new Map<string, string[]>();
  for (const norms of byRoot.values()) {
    const canonical = canonicalShow(norms, spellings);
    const aliases = [...new Set(norms.flatMap((norm) => [...(spellings.get(norm) ?? [])]))].sort(cmp);
    for (const norm of norms) {
      canonicalByNorm.set(norm, canonical);
      aliasesByNorm.set(norm, aliases);
    }
  }
  return {
    canonicalOf: (showId: string) => canonicalByNorm.get(normShowId(showId)) ?? showId,
    aliasesOf: (showId: string) => aliasesByNorm.get(normShowId(showId)) ?? [showId],
  };
}

function canonicalShow(norms: string[], spellings: Map<string, Set<string>>): string {
  const tmdb = norms.filter((norm) => norm.startsWith("tmdb:")).map((norm) => Number(norm.slice("tmdb:".length)));
  if (tmdb.length > 0) return `tmdb:${Math.min(...tmdb)}`;
  const imdb = norms.filter((norm) => norm.startsWith("imdb:")).map((norm) => Number(norm.slice("imdb:".length)));
  if (imdb.length > 0) {
    const best = Math.min(...imdb);
    const options = [...(spellings.get(`imdb:${best}`) ?? new Set<string>())];
    options.sort((left, right) => {
      const leftDigits = left.slice("imdb:tt".length);
      const rightDigits = right.slice("imdb:tt".length);
      if (leftDigits.length !== rightDigits.length) return rightDigits.length - leftDigits.length;
      return cmp(left, right);
    });
    return options[0] ?? `imdb:tt${best}`;
  }
  const raw = norms.filter((norm) => norm.startsWith("raw:")).sort(cmp);
  return raw[0] ?? norms[0] ?? "";
}

function resolveMarks(marks: LiveMark[]): { views: MarkView[]; effective: EffectiveSegment[] } {
  const views: MarkView[] = [];
  const effective: EffectiveSegment[] = [];
  for (const type of SEGMENT_TYPES) {
    const group = marks.filter((mark) => mark.segmentType === type);
    const nones = group.filter((mark) => mark.presence === "none");
    const ranges = group.filter((mark) => mark.presence === "range");
    const winningNone = maxRecord(nones);
    for (const none of nones) {
      if (winningNone && none.id !== winningNone.id) views.push(markView(none, false, "newer-none"));
    }
    const appliedRanges: LiveMark[] = [];
    for (const range of ranges) {
      if (winningNone && compareParsed(wrap(winningNone), wrap(range)) > 0) views.push(markView(range, false, "none"));
      else {
        appliedRanges.push(range);
        views.push(markView(range, true, null));
      }
    }
    if (winningNone) {
      const applied = appliedRanges.length === 0;
      views.push(markView(winningNone, applied, applied ? null : "range"));
      if (applied) effective.push(effectiveFromMark(winningNone));
    }
    for (const range of appliedRanges) effective.push(effectiveFromMark(range));
  }
  views.sort((left, right) => cmp(left.id, right.id));
  return { views, effective };
}

function resolveDetections(detections: LiveDetection[], marks: MarkView[]): { views: DetectionView[]; effective: EffectiveSegment[] } {
  const groups = new Map<string, LiveDetection[]>();
  for (const detection of detections) {
    const key = `${detection.segmentType}\n${detection.segmentIndex}`;
    const list = groups.get(key) ?? [];
    list.push(detection);
    groups.set(key, list);
  }
  const views: DetectionView[] = [];
  const effective: EffectiveSegment[] = [];
  for (const group of groups.values()) {
    const found = group
      .filter((detection) => detection.outcome === "found")
      .sort((left, right) => left.durationMs - right.durationMs || cmp(left.id, right.id));
    const clusters: LiveDetection[][] = [];
    for (const detection of found) {
      const current = clusters[clusters.length - 1];
      const minimum = current?.[0];
      if (!current || !minimum || detection.durationMs - minimum.durationMs > CUT_TOLERANCE_MS) clusters.push([detection]);
      else current.push(detection);
    }
    const winners = new Set<string>();
    const shadowed = new Set<string>();
    for (const cluster of clusters) {
      const winner = maxDetection(cluster);
      if (!winner) continue;
      winners.add(winner.id);
      for (const other of cluster) if (other.id !== winner.id) shadowed.add(other.id);
    }
    for (const detection of group) {
      let applied = false;
      let suppressedBy: DetectionView["suppressedBy"] = null;
      const marked = markSuppresses(detection, marks);
      if (detection.outcome === "not_found") {
        suppressedBy = marked ? "mark" : null;
      } else if (shadowed.has(detection.id)) {
        suppressedBy = "newer-detection";
      } else if (winners.has(detection.id)) {
        if (marked) suppressedBy = "mark";
        else applied = true;
      }
      views.push(detectionView(detection, applied, suppressedBy));
      if (applied) effective.push(effectiveFromDetection(detection));
    }
  }
  views.sort((left, right) => cmp(left.id, right.id));
  return { views, effective };
}

function markSuppresses(detection: LiveDetection, marks: MarkView[]): boolean {
  return marks.some((mark) => {
    if (!mark.applied || mark.segmentType !== detection.segmentType) return false;
    if (mark.presence === "none") return true;
    if (mark.segmentIndex !== detection.segmentIndex) return false;
    if (mark.durationMs === 0) return true;
    return cutsMatch(mark.durationMs, detection.durationMs);
  });
}

function durationCovers(markDurationMs: number, fileDurationMs: number): boolean {
  if (markDurationMs === 0) return true;
  return cutsMatch(markDurationMs, fileDurationMs);
}

function cutsMatch(left: number, right: number): boolean {
  if (left <= 0 || right <= 0) return false;
  const delta = left > right ? left - right : right - left;
  return delta <= CUT_TOLERANCE_MS;
}

function markView(mark: LiveMark, applied: boolean, suppressedBy: MarkView["suppressedBy"]): MarkView {
  return {
    applied,
    durationMs: mark.durationMs,
    endMs: mark.endMs,
    hlc: mark.hlc,
    id: mark.id,
    presence: mark.presence,
    segmentIndex: mark.segmentIndex,
    segmentType: mark.segmentType,
    startMs: mark.startMs,
    suppressedBy,
  };
}

function detectionView(detection: LiveDetection, applied: boolean, suppressedBy: DetectionView["suppressedBy"]): DetectionView {
  return {
    algorithm: detection.algorithm,
    algorithmVersion: detection.algorithmVersion,
    applied,
    durationMs: detection.durationMs,
    endMs: detection.endMs,
    hlc: detection.hlc,
    id: detection.id,
    outcome: detection.outcome,
    releaseId: detection.releaseId,
    segmentIndex: detection.segmentIndex,
    segmentType: detection.segmentType,
    startMs: detection.startMs,
    suppressedBy,
  };
}

function effectiveFromMark(mark: LiveMark): EffectiveSegment {
  return {
    algorithm: null,
    durationMs: mark.durationMs,
    endMs: mark.endMs,
    id: mark.id,
    presence: mark.presence,
    segmentIndex: mark.segmentIndex,
    segmentType: mark.segmentType,
    source: "mark",
    startMs: mark.startMs,
  };
}

function effectiveFromDetection(detection: LiveDetection): EffectiveSegment {
  return {
    algorithm: detection.algorithm,
    durationMs: detection.durationMs,
    endMs: detection.endMs,
    id: detection.id,
    presence: "range",
    segmentIndex: detection.segmentIndex,
    segmentType: detection.segmentType,
    source: "detection",
    startMs: detection.startMs,
  };
}

function compareEffective(left: EffectiveSegment, right: EffectiveSegment): number {
  const type = SEGMENT_TYPES.indexOf(left.segmentType) - SEGMENT_TYPES.indexOf(right.segmentType);
  if (type !== 0) return type;
  const index = (left.segmentIndex ?? -1) - (right.segmentIndex ?? -1);
  if (index !== 0) return index;
  if (left.source !== right.source) return left.source < right.source ? -1 : 1;
  return cmp(left.id, right.id);
}

function cmp(left: string, right: string): number {
  if (left < right) return -1;
  if (left > right) return 1;
  return 0;
}

function episodeRank(episode: EpisodeState): number {
  if (episode.season === null) return -1;
  return episode.season * 1_000_000 + (episode.episode ?? 0);
}

function maxRecord(marks: LiveMark[]): LiveMark | null {
  let best: LiveMark | null = null;
  for (const mark of marks) {
    if (best === null || compareParsed(wrap(mark), wrap(best)) > 0) best = mark;
  }
  return best;
}

function maxDetection(detections: LiveDetection[]): LiveDetection | null {
  let best: LiveDetection | null = null;
  for (const detection of detections) {
    if (best === null || compareDetections(detection, best) > 0) best = detection;
  }
  return best;
}

function compareDetections(left: LiveDetection, right: LiveDetection): number {
  if (left.algorithmVersion !== right.algorithmVersion) return left.algorithmVersion > right.algorithmVersion ? 1 : -1;
  return compareParsed(wrap(left), wrap(right));
}

function wrap(record: LiveMark | LiveDetection): ParsedRecord {
  const parsed = parseRecord(wireOf(record));
  if (parsed === null) throw new Error("internal record failed to parse");
  return parsed;
}

function wireOf(record: LiveMark | LiveDetection | LiveAlias | LiveSubmission): Record<string, unknown> {
  if (record.kind === "mark" && record.presence === "none") {
    return {
      deleted: false,
      durationMs: record.durationMs,
      episode: record.episode,
      hlc: record.hlc,
      id: record.id,
      kind: "mark",
      presence: "none",
      season: record.season,
      segmentType: record.segmentType,
      showId: record.showId,
    };
  }
  if (record.kind === "mark") {
    return {
      deleted: false,
      durationMs: record.durationMs,
      endMs: record.endMs,
      episode: record.episode,
      hlc: record.hlc,
      id: record.id,
      kind: "mark",
      presence: "range",
      season: record.season,
      segmentIndex: record.segmentIndex,
      segmentType: record.segmentType,
      showId: record.showId,
      startMs: record.startMs,
    };
  }
  if (record.kind === "detection") {
    const wire: Record<string, unknown> = {
      algorithm: record.algorithm,
      deleted: false,
      durationMs: record.durationMs,
      episode: record.episode,
      hlc: record.hlc,
      id: record.id,
      kind: "detection",
      outcome: record.outcome,
      season: record.season,
      segmentIndex: record.segmentIndex,
      segmentType: record.segmentType,
      showId: record.showId,
    };
    if (record.outcome === "found") {
      wire.startMs = record.startMs;
      wire.endMs = record.endMs;
    }
    if (record.releaseId !== null) wire.releaseId = record.releaseId;
    return wire;
  }
  return { ...record };
}
