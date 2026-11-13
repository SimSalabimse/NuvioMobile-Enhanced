import type { SyncEnv } from "./env";

export function spaceBucket(spaceId: string): string {
  return `space:${spaceId}`;
}

export function windowStart(nowMs: number, windowMs: number): number {
  return nowMs - (nowMs % windowMs);
}

export function overLimit(hits: number, limit: number): boolean {
  return hits > limit;
}

export interface RateResult {
  allowed: boolean;
  retryAfterSeconds: number;
}

/**
 * One row per space per window. The write is the abuse counter.
 * Callers skip this until the bearer token is on the allowlist, so a stranger
 * does not get a D1 write.
 */
export async function takeRateHit(env: SyncEnv, bucket: string, limit: number, windowMs: number, nowMs: number): Promise<RateResult> {
  const start = windowStart(nowMs, windowMs);
  const row = await env.DB.prepare(
    `INSERT INTO rate_windows (bucket, window_start, hits)
     VALUES (?1, ?2, 1)
     ON CONFLICT(bucket, window_start) DO UPDATE SET hits = hits + 1
     RETURNING hits`,
  )
    .bind(bucket, start)
    .first<{ hits: number }>();
  const hits = row?.hits ?? limit + 1;
  if (hits === 1) {
    await env.DB.prepare("DELETE FROM rate_windows WHERE bucket = ?1 AND window_start < ?2")
      .bind(bucket, start)
      .run();
  }
  const retryAfterSeconds = Math.max(1, Math.ceil((start + windowMs - nowMs) / 1000));
  return { allowed: !overLimit(hits, limit), retryAfterSeconds };
}
