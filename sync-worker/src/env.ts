/** Bindings from wrangler.jsonc. ENROLL_SECRET is a secret, not a var. */
export interface SyncEnv extends Env {
  ENROLL_SECRET?: string;
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
  maxSpaceBlobs: number;
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
    maxSpaceBlobs: positiveInt(env.MAX_SPACE_BLOBS, 20_000),
  };
}

function positiveInt(value: unknown, fallback: number): number {
  const text = typeof value === "number" ? String(value) : value;
  if (typeof text !== "string" || !/^[1-9][0-9]{0,8}$/.test(text)) return fallback;
  const parsed = Number(text);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) return fallback;
  return parsed;
}
