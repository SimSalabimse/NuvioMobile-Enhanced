import type { Authed } from "./auth";
import { decodeB64, encodeB64 } from "./b64";
import { bytesEqual } from "./body";
import type { Limits, SyncEnv } from "./env";
import { fail, json } from "./http";
import { isCursor, isDeviceId, isUuidV4 } from "./ids";
import { denySpace, loadSpace } from "./spaces";

const KINDS = ["mark", "detection", "submission", "alias"] as const;
type BlobKind = (typeof KINDS)[number];

interface StoredBlob {
  id: string;
  v: 1;
  kind: BlobKind;
  nonce: string;
  ciphertext: string;
  cipherBytes: Uint8Array;
}

interface ExistingBlob {
  blob_id: string;
  seq: number;
  ciphertext_b64: string;
}

export async function pushBatch(
  env: SyncEnv,
  auth: Authed,
  spaceId: string,
  body: unknown,
  limits: Limits,
  nowMs: number,
): Promise<Response> {
  const parsed = parseBatch(body, limits);
  if (parsed === null) return fail(400, "bad_request", "Invalid batch");
  const space = await loadSpace(env, spaceId);
  const denied = denySpace(space, auth);
  if (denied) return denied;

  const existing = await selectExisting(env, spaceId, parsed.blobs.map((blob) => blob.id));
  const previous = new Map(existing.map((row) => [row.blob_id, row]));
  for (const blob of parsed.blobs) {
    const prior = previous.get(blob.id);
    if (!prior) continue;
    const stored = decodeB64(prior.ciphertext_b64);
    if (stored === null || !bytesEqual(stored, blob.cipherBytes)) {
      return fail(409, "conflict", "Blob already exists");
    }
  }

  const fresh = parsed.blobs.filter((blob) => !previous.has(blob.id));
  if (fresh.length > 0) {
    const seenNonce = new Set<string>();
    for (const blob of fresh) {
      if (seenNonce.has(blob.nonce)) return fail(409, "conflict", "Nonce already used");
      seenNonce.add(blob.nonce);
    }
    const nonceHits = await selectNonces(env, spaceId, fresh.map((blob) => blob.nonce));
    if (nonceHits.length > 0) return fail(409, "conflict", "Nonce already used");
    const counter = await env.DB.prepare("SELECT next_seq FROM space_counters WHERE space_id = ?1")
      .bind(spaceId)
      .first<{ next_seq: number }>();
    if (!counter) return fail(404, "not_found", "Not found");
    if (counter.next_seq + fresh.length > limits.maxSpaceBlobs) return fail(409, "quota_exceeded", "Space is full");

    const count = fresh.length;
    const statements = [
      env.DB.prepare("UPDATE space_counters SET next_seq = next_seq + ?1 WHERE space_id = ?2").bind(count, spaceId),
      ...fresh.map((blob, index) =>
        env.DB.prepare(
          `INSERT INTO blobs (space_id, seq, blob_id, v, kind, device_id, nonce_b64, ciphertext_b64, received_at)
           VALUES (?1, (SELECT next_seq FROM space_counters WHERE space_id = ?1) - ?2 + ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)`,
        ).bind(spaceId, count, index + 1, blob.id, blob.v, blob.kind, parsed.deviceId, blob.nonce, blob.ciphertext, nowMs),
      ),
    ];
    try {
      await env.DB.batch(statements);
    } catch (error) {
      const message = error instanceof Error ? error.message : "";
      if (message.includes("UNIQUE")) return fail(409, "conflict", "Retry the batch");
      throw error;
    }
  }

  const after = await selectExisting(env, spaceId, parsed.blobs.map((blob) => blob.id));
  const seqs = new Map(after.map((row) => [row.blob_id, row.seq]));
  return json({
    v: 1,
    results: parsed.blobs.map((blob) => ({
      id: blob.id,
      seq: seqs.get(blob.id) ?? 0,
      status: previous.has(blob.id) ? "duplicate" : "stored",
    })),
  });
}

export async function pullBlobs(
  env: SyncEnv,
  auth: Authed,
  spaceId: string,
  url: URL,
  limits: Limits,
): Promise<Response> {
  const cursorText = url.searchParams.get("cursor") ?? "0";
  const limitText = url.searchParams.get("limit") ?? String(limits.pullDefault);
  if (!isCursor(cursorText)) return fail(400, "bad_request", "Invalid cursor");
  if (!/^[1-9][0-9]{0,8}$/.test(limitText)) return fail(400, "bad_request", "Invalid limit");
  const cursor = Number(cursorText);
  const limit = Number(limitText);
  if (limit > limits.pullMax) return fail(400, "bad_request", "Invalid limit");

  const space = await loadSpace(env, spaceId);
  const denied = denySpace(space, auth);
  if (denied) return denied;

  const { results } = await env.DB.prepare(
    `SELECT seq, blob_id, v, kind, device_id, nonce_b64, ciphertext_b64, received_at
     FROM blobs WHERE space_id = ?1 AND seq > ?2 ORDER BY seq LIMIT ?3`,
  )
    .bind(spaceId, cursor, limit + 1)
    .all<{
      seq: number;
      blob_id: string;
      v: number;
      kind: string;
      device_id: string;
      nonce_b64: string;
      ciphertext_b64: string;
      received_at: number;
    }>();
  const hasMore = results.length > limit;
  const page = hasMore ? results.slice(0, limit) : results;
  const last = page[page.length - 1];
  return json({
    v: 1,
    blobs: page.map((row) => ({
      seq: row.seq,
      id: row.blob_id,
      v: row.v,
      kind: row.kind,
      deviceId: row.device_id,
      nonce: row.nonce_b64,
      ciphertext: row.ciphertext_b64,
      receivedAtMs: row.received_at,
    })),
    cursor: last ? last.seq : cursor,
    hasMore,
  });
}

async function selectNonces(env: SyncEnv, spaceId: string, nonces: string[]): Promise<string[]> {
  if (nonces.length === 0) return [];
  const placeholders = nonces.map(() => "?").join(", ");
  const { results } = await env.DB.prepare(
    `SELECT nonce_b64 FROM blobs WHERE space_id = ? AND nonce_b64 IN (${placeholders})`,
  )
    .bind(spaceId, ...nonces)
    .all<{ nonce_b64: string }>();
  return results.map((row) => row.nonce_b64);
}

async function selectExisting(env: SyncEnv, spaceId: string, ids: string[]): Promise<ExistingBlob[]> {
  const placeholders = ids.map(() => "?").join(", ");
  const { results } = await env.DB.prepare(
    `SELECT blob_id, seq, ciphertext_b64 FROM blobs WHERE space_id = ? AND blob_id IN (${placeholders})`,
  )
    .bind(spaceId, ...ids)
    .all<ExistingBlob>();
  return results;
}

function parseBatch(body: unknown, limits: Limits): { deviceId: string; blobs: StoredBlob[] } | null {
  if (!isRecord(body)) return null;
  const keys = Object.keys(body);
  if (keys.length !== 2 || !keys.includes("deviceId") || !keys.includes("blobs")) return null;
  if (typeof body.deviceId !== "string" || !isDeviceId(body.deviceId)) return null;
  if (!Array.isArray(body.blobs) || body.blobs.length < 1 || body.blobs.length > limits.maxBlobs) return null;
  const seen = new Set<string>();
  const blobs: StoredBlob[] = [];
  for (const item of body.blobs) {
    const blob = parseBlob(item, limits.maxCiphertext);
    if (blob === null || seen.has(blob.id)) return null;
    seen.add(blob.id);
    blobs.push(blob);
  }
  return { deviceId: body.deviceId, blobs };
}

function parseBlob(item: unknown, maxCiphertext: number): StoredBlob | null {
  if (!isRecord(item)) return null;
  const keys = Object.keys(item);
  if (keys.length !== 5 || !keys.includes("id") || !keys.includes("v") || !keys.includes("kind") || !keys.includes("nonce") || !keys.includes("ciphertext")) {
    return null;
  }
  if (typeof item.id !== "string" || !isUuidV4(item.id)) return null;
  if (item.v !== 1) return null;
  if (typeof item.kind !== "string" || !(KINDS as readonly string[]).includes(item.kind)) return null;
  if (typeof item.nonce !== "string" || typeof item.ciphertext !== "string") return null;
  const nonce = decodeB64(item.nonce);
  const cipher = decodeB64(item.ciphertext);
  if (nonce === null || nonce.length !== 12) return null;
  if (cipher === null || cipher.length < 16 || cipher.length > maxCiphertext) return null;
  return {
    id: item.id,
    v: 1,
    kind: item.kind as BlobKind,
    nonce: encodeB64(nonce),
    ciphertext: encodeB64(cipher),
    cipherBytes: cipher,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
