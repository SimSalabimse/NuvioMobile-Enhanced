/** Folder paths for transport (e). Readers union snapshot and segment files. */

const DEVICE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

export function snapshotPath(deviceId: string): string {
  assertDevice(deviceId);
  return `devices/${deviceId}.json`;
}

export function segmentPath(deviceId: string, seq: number): string {
  assertDevice(deviceId);
  if (!Number.isSafeInteger(seq) || seq < 1 || seq > 9_999_999_999_999_999) {
    throw new Error("bad segment seq");
  }
  return `devices/${deviceId}/${String(seq).padStart(16, "0")}.json`;
}

function assertDevice(deviceId: string): void {
  if (!DEVICE_ID.test(deviceId)) throw new Error("bad device id");
}

export interface FolderBlob {
  id: string;
  v: 1;
  kind: "mark" | "detection" | "submission" | "alias";
  nonce: string;
  ciphertext: string;
}

export interface FolderHeader {
  v: 1;
  spaceId: string;
  kdf: "pbkdf2-sha256";
  iterations: 600000;
  salt: string;
  keyCheck: string;
}

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SEGMENT_PATH = /^devices\/([A-Za-z0-9][A-Za-z0-9._-]{0,63})\/(\d{16})\.json$/;
const SNAPSHOT_PATH = /^devices\/([A-Za-z0-9][A-Za-z0-9._-]{0,63})\.json$/;

/** Union one device's snapshot and later segments. Malformed matching files fail the read. */
export function collectFolder(files: ReadonlyMap<string, unknown>): {
  header: FolderHeader;
  blobs: Array<FolderBlob & { deviceId: string }>;
} {
  const header = parseHeader(files.get("header.json"));
  if (header === null) throw new Error("bad header");
  const snapshots = new Map<string, { throughSeq: number; blobs: FolderBlob[] }>();
  const segments = new Map<string, Map<number, FolderBlob[]>>();
  for (const [path, value] of files) {
    if (path === "header.json" || path.endsWith("/.DS_Store") || path === ".DS_Store") continue;
    const snapshot = SNAPSHOT_PATH.exec(path);
    if (snapshot) {
      const deviceId = snapshot[1]!;
      const parsed = parseSnapshot(value, header.spaceId, deviceId);
      if (parsed === null) throw new Error("bad snapshot");
      snapshots.set(deviceId, parsed);
      continue;
    }
    const segment = SEGMENT_PATH.exec(path);
    if (segment) {
      const deviceId = segment[1]!;
      const seq = Number(segment[2]);
      const parsed = parseSegment(value, header.spaceId, deviceId, seq);
      if (parsed === null) throw new Error("bad segment");
      const bySeq = segments.get(deviceId) ?? new Map<number, FolderBlob[]>();
      bySeq.set(seq, parsed);
      segments.set(deviceId, bySeq);
      continue;
    }
  }
  const deviceIds = [...new Set([...snapshots.keys(), ...segments.keys()])].sort();
  const blobs: Array<FolderBlob & { deviceId: string }> = [];
  for (const deviceId of deviceIds) {
    const snapshot = snapshots.get(deviceId);
    const through = snapshot?.throughSeq ?? 0;
    if (snapshot) {
      for (const blob of snapshot.blobs) blobs.push({ ...blob, deviceId });
    }
    const bySeq = [...(segments.get(deviceId)?.entries() ?? [])].sort((left, right) => left[0] - right[0]);
    for (const [seq, page] of bySeq) {
      if (seq <= through) continue;
      for (const blob of page) blobs.push({ ...blob, deviceId });
    }
  }
  return { header, blobs };
}

function parseHeader(value: unknown): FolderHeader | null {
  if (!isRecord(value) || !keysExact(value, ["iterations", "kdf", "keyCheck", "salt", "spaceId", "v"])) return null;
  if (value.v !== 1 || value.kdf !== "pbkdf2-sha256" || value.iterations !== 600_000) return null;
  if (typeof value.spaceId !== "string" || !UUID_V4.test(value.spaceId)) return null;
  if (typeof value.salt !== "string" || typeof value.keyCheck !== "string") return null;
  return {
    v: 1,
    spaceId: value.spaceId,
    kdf: "pbkdf2-sha256",
    iterations: 600000,
    salt: value.salt,
    keyCheck: value.keyCheck,
  };
}

function parseSnapshot(value: unknown, spaceId: string, deviceId: string): { throughSeq: number; blobs: FolderBlob[] } | null {
  if (!isRecord(value) || !keysExact(value, ["blobs", "deviceId", "spaceId", "throughSeq", "v"])) return null;
  if (value.v !== 1 || value.spaceId !== spaceId || value.deviceId !== deviceId) return null;
  if (typeof value.throughSeq !== "number" || !Number.isSafeInteger(value.throughSeq) || value.throughSeq < 0) return null;
  const blobs = parseBlobs(value.blobs);
  if (blobs === null) return null;
  return { throughSeq: value.throughSeq, blobs };
}

function parseSegment(value: unknown, spaceId: string, deviceId: string, seq: number): FolderBlob[] | null {
  if (!isRecord(value) || !keysExact(value, ["blobs", "deviceId", "seq", "spaceId", "v"])) return null;
  if (value.v !== 1 || value.spaceId !== spaceId || value.deviceId !== deviceId || value.seq !== seq) return null;
  if (seq < 1) return null;
  return parseBlobs(value.blobs);
}

function parseBlobs(value: unknown): FolderBlob[] | null {
  if (!Array.isArray(value)) return null;
  const blobs: FolderBlob[] = [];
  for (const item of value) {
    if (!isRecord(item) || !keysExact(item, ["ciphertext", "id", "kind", "nonce", "v"])) return null;
    if (item.v !== 1) return null;
    if (item.kind !== "mark" && item.kind !== "detection" && item.kind !== "submission" && item.kind !== "alias") return null;
    if (typeof item.id !== "string" || !UUID_V4.test(item.id)) return null;
    if (typeof item.nonce !== "string" || typeof item.ciphertext !== "string") return null;
    blobs.push({ id: item.id, v: 1, kind: item.kind, nonce: item.nonce, ciphertext: item.ciphertext });
  }
  return blobs;
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
