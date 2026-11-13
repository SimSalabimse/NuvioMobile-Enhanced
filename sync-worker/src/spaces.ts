import { sameToken, type Authed } from "./auth";
import { decodeB64, encodeB64 } from "./b64";
import { fail, json } from "./http";
import { isUuidV4 } from "./ids";
import type { SyncEnv } from "./env";

const KDF = "pbkdf2-sha256";
const ITERATIONS = 600_000;

export interface SpaceRow {
  space_id: string;
  token_hash_hex: string;
  salt_b64: string;
  key_check_b64: string;
  kdf: string;
  iterations: number;
}

interface CreateBody {
  spaceId: string;
  salt: string;
  keyCheck: string;
  kdf: typeof KDF;
  iterations: typeof ITERATIONS;
}

export function readSpaceId(body: unknown): string | null {
  if (!isRecord(body)) return null;
  const spaceId = body.spaceId;
  if (typeof spaceId !== "string" || !isUuidV4(spaceId)) return null;
  return spaceId;
}

export async function loadSpace(env: SyncEnv, spaceId: string): Promise<SpaceRow | null> {
  return env.DB.prepare(
    "SELECT space_id, token_hash_hex, salt_b64, key_check_b64, kdf, iterations FROM spaces WHERE space_id = ?1",
  )
    .bind(spaceId)
    .first<SpaceRow>();
}

export async function createSpace(env: SyncEnv, auth: Authed, body: unknown, nowMs: number): Promise<Response> {
  const parsed = parseCreate(body);
  if (parsed === null) return fail(400, "bad_request", "Invalid space");
  const existing = await loadSpace(env, parsed.spaceId);
  if (existing) {
    if (!sameToken(existing.token_hash_hex, auth)) return fail(401, "unauthorized", "Unauthorized");
    if (!sameHeader(existing, parsed)) return fail(409, "conflict", "Space already exists");
    return json(toHeader(existing), 200);
  }
  try {
    await env.DB.batch([
      env.DB.prepare(
        `INSERT INTO spaces (space_id, token_hash_hex, salt_b64, key_check_b64, kdf, iterations, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)`,
      ).bind(parsed.spaceId, auth.tokenHashHex, parsed.salt, parsed.keyCheck, parsed.kdf, parsed.iterations, nowMs),
      env.DB.prepare("INSERT INTO space_counters (space_id, next_seq) VALUES (?1, 0)").bind(parsed.spaceId),
    ]);
  } catch (error) {
    const message = error instanceof Error ? error.message : "";
    if (!message.includes("UNIQUE")) throw error;
    const winner = await loadSpace(env, parsed.spaceId);
    if (!winner || !sameToken(winner.token_hash_hex, auth)) return fail(401, "unauthorized", "Unauthorized");
    if (!sameHeader(winner, parsed)) return fail(409, "conflict", "Space already exists");
    return json(toHeader(winner), 200);
  }
  return json(toHeader({
    space_id: parsed.spaceId,
    token_hash_hex: auth.tokenHashHex,
    salt_b64: parsed.salt,
    key_check_b64: parsed.keyCheck,
    kdf: parsed.kdf,
    iterations: parsed.iterations,
  }), 201);
}

export async function joinSpace(env: SyncEnv, auth: Authed, spaceId: string): Promise<Response> {
  const existing = await loadSpace(env, spaceId);
  if (!existing) return fail(404, "not_found", "Not found");
  if (!sameToken(existing.token_hash_hex, auth)) return fail(401, "unauthorized", "Unauthorized");
  return json(toHeader(existing), 200);
}

function parseCreate(body: unknown): CreateBody | null {
  if (!isRecord(body)) return null;
  const keys = Object.keys(body);
  for (const key of keys) {
    if (!["spaceId", "salt", "keyCheck", "kdf", "iterations"].includes(key)) return null;
  }
  if (keys.length !== 5) return null;
  if (typeof body.spaceId !== "string" || !isUuidV4(body.spaceId)) return null;
  if (body.kdf !== KDF) return null;
  if (body.iterations !== ITERATIONS) return null;
  if (typeof body.salt !== "string" || typeof body.keyCheck !== "string") return null;
  const salt = decodeB64(body.salt);
  const keyCheck = decodeB64(body.keyCheck);
  if (salt === null || salt.length !== 16) return null;
  if (keyCheck === null || keyCheck.length !== 16) return null;
  return {
    spaceId: body.spaceId,
    salt: encodeB64(salt),
    keyCheck: encodeB64(keyCheck),
    kdf: KDF,
    iterations: ITERATIONS,
  };
}

function sameHeader(existing: SpaceRow, parsed: CreateBody): boolean {
  return (
    existing.salt_b64 === parsed.salt &&
    existing.key_check_b64 === parsed.keyCheck &&
    existing.kdf === parsed.kdf &&
    existing.iterations === parsed.iterations
  );
}

function toHeader(row: SpaceRow): {
  v: 1;
  spaceId: string;
  kdf: string;
  iterations: number;
  salt: string;
  keyCheck: string;
} {
  return {
    v: 1,
    spaceId: row.space_id,
    kdf: row.kdf,
    iterations: row.iterations,
    salt: row.salt_b64,
    keyCheck: row.key_check_b64,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
