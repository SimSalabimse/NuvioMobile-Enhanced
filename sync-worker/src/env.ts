/** Bindings from wrangler.jsonc. ALLOWLIST_TOKEN_HASHES is a secret, not a var. */
export interface SyncEnv extends Env {
  ALLOWLIST_TOKEN_HASHES?: string;
  TEST_MIGRATIONS?: unknown;
}

export interface Limits {
  maxBody: number;
  maxBlobs: number;
  maxCiphertext: number;
  rateLimit: number;
  rateWindowMs: number;
  pullDefault: number;
  pullMax: number;
}

export function limitsOf(env: SyncEnv): Limits {
  return {
    maxBody: positiveInt(env.MAX_BODY_BYTES, 262144),
    maxBlobs: positiveInt(env.MAX_BLOBS, 50),
    maxCiphertext: positiveInt(env.MAX_CIPHERTEXT_BYTES, 24576),
    rateLimit: positiveInt(env.RATE_LIMIT_PER_WINDOW, 60),
    rateWindowMs: positiveInt(env.RATE_WINDOW_MS, 60000),
    pullDefault: positiveInt(env.PULL_DEFAULT_LIMIT, 100),
    pullMax: positiveInt(env.PULL_MAX_LIMIT, 100),
  };
}

function positiveInt(value: unknown, fallback: number): number {
  const text = typeof value === "number" ? String(value) : value;
  if (typeof text !== "string" || !/^[1-9][0-9]{0,8}$/.test(text)) return fallback;
  const parsed = Number(text);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) return fallback;
  return parsed;
}
