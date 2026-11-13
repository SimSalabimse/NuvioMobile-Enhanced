import { decodeHex, decodeToken, encodeHex } from "./b64";
import { allowlistCandidates, bytesEqual, matchesAnyHash, sha256 } from "./body";
import type { SyncEnv } from "./env";

export interface Authed {
  tokenHash: Uint8Array;
  tokenHashHex: string;
}

/** Bearer token is 32 raw bytes, base64url, no padding. The server stores SHA-256 only. */
export async function authenticate(request: Request, env: SyncEnv): Promise<Authed | null> {
  const header = request.headers.get("authorization");
  if (header === null) return null;
  const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(header);
  if (match === null) return null;
  const token = decodeToken(match[1]!);
  if (token === null) return null;
  const tokenHash = await sha256(token);
  const candidates = await allowlistCandidates(env);
  if (!matchesAnyHash(tokenHash, candidates)) return null;
  return { tokenHash, tokenHashHex: encodeHex(tokenHash) };
}

export function sameToken(storedHex: string, auth: Authed): boolean {
  const stored = decodeHex(storedHex.trim().toLowerCase());
  if (stored === null) return false;
  return bytesEqual(stored, auth.tokenHash);
}
