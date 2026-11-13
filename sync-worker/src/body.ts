import { decodeHex } from "./b64";
import type { SyncEnv } from "./env";

/** Read at most `max` bytes. A longer body is rejected without buffering the rest. */
export async function readBounded(request: Request, max: number): Promise<Uint8Array | "too_large"> {
  const declared = request.headers.get("content-length");
  if (declared !== null && (!/^\d+$/.test(declared) || Number(declared) > max)) return "too_large";
  const reader = request.body?.getReader();
  if (!reader) return new Uint8Array();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    if (!value) continue;
    total += value.byteLength;
    if (total > max) {
      await reader.cancel();
      return "too_large";
    }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return out;
}

export function isJsonContentType(request: Request): boolean {
  const header = request.headers.get("content-type");
  if (header === null) return false;
  const media = header.split(";", 1)[0]?.trim().toLowerCase();
  return media === "application/json";
}

export async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return new Uint8Array(digest);
}

export function bytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  return crypto.subtle.timingSafeEqual(left, right);
}

/** OR of constant-time comparisons. Every equal-length candidate is checked. */
export function matchesAnyHash(hash: Uint8Array, candidates: readonly Uint8Array[]): boolean {
  let acc = 0;
  for (const candidate of candidates) {
    if (candidate.length !== hash.length) continue;
    acc |= crypto.subtle.timingSafeEqual(hash, candidate) ? 1 : 0;
  }
  return acc === 1;
}

export async function allowlistCandidates(env: SyncEnv): Promise<Uint8Array[]> {
  const out: Uint8Array[] = [];
  const secret = env.ALLOWLIST_TOKEN_HASHES ?? "";
  for (const part of secret.split(/[\s,]+/)) {
    const hex = part.trim().toLowerCase();
    if (hex.length === 0) continue;
    if (!/^[0-9a-f]{64}$/.test(hex)) continue;
    const decoded = decodeHex(hex);
    if (decoded !== null) out.push(decoded);
  }
  const { results } = await env.DB.prepare(
    "SELECT token_hash_hex FROM allowlist ORDER BY token_hash_hex LIMIT 32",
  ).all<{ token_hash_hex: string }>();
  for (const row of results) {
    const hex = row.token_hash_hex.trim().toLowerCase();
    if (!/^[0-9a-f]{64}$/.test(hex)) continue;
    const decoded = decodeHex(hex);
    if (decoded !== null) out.push(decoded);
  }
  return out;
}
