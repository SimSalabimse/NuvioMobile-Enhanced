import { decodeHex, decodeToken, encodeHex } from "./b64";
import { bytesEqual, secretEquals, sha256 } from "./body";
import type { SyncEnv } from "./env";

export interface Authed {
  tokenHash: Uint8Array;
  tokenHashHex: string;
}

const ENROLL_HEADER = "x-nuvio-enroll";
const MIN_ENROLL_LENGTH = 16;

/** Bearer token is 32 raw bytes, base64url, no padding. The server stores SHA-256 only. */
export async function readBearer(request: Request): Promise<Authed | null> {
  const header = request.headers.get("authorization");
  if (header === null) return null;
  const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(header);
  if (match === null) return null;
  const token = decodeToken(match[1]!);
  if (token === null) return null;
  const tokenHash = await sha256(token);
  return { tokenHash, tokenHashHex: encodeHex(tokenHash) };
}

/**
 * The enroll secret authorizes creating, listing, and revoking spaces.
 * A missing or short secret fails closed. The compare still runs so the
 * failure does not skip the hash.
 */
export async function enrollAuthorized(request: Request, env: SyncEnv): Promise<boolean> {
  const provided = request.headers.get(ENROLL_HEADER) ?? "";
  const expected = env.ENROLL_SECRET ?? "";
  const matches = await secretEquals(provided, expected);
  if (expected.length < MIN_ENROLL_LENGTH) return false;
  return matches;
}

export function sameToken(storedHex: string, auth: Authed): boolean {
  const stored = decodeHex(storedHex.trim().toLowerCase());
  if (stored === null || stored.length !== auth.tokenHash.length) return false;
  return bytesEqual(stored, auth.tokenHash);
}
