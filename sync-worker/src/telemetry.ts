import { readBearer } from "./auth";
import { readBounded, secretEquals, sha256 } from "./body";
import { encodeHex } from "./b64";
import type { SyncEnv } from "./env";
import { fail, json, tooMany } from "./http";
import { takeRateHit } from "./rate";

const TELEMETRY_MAX_BYTES = 512 * 1024;
const THIRTY_DAYS_MS = 30 * 24 * 60 * 60 * 1000;
const DAY_MS = 24 * 60 * 60 * 1000;
const DAILY_BATCHES = 20;
const ROW_CAP = 5000;
const MIN_INGEST_LENGTH = 16;

const ALLOWED_NUVIO_HEADERS = new Set([
  "x-nuvio-ingest",
  "x-nuvio-install",
  "x-nuvio-app-build",
  "x-nuvio-kind",
]);

/**
 * Write-only behaviour batches. A sync bearer or the ingest key may authorize
 * the write. There is no read route. The ingest key is never stored.
 */
export async function storeTelemetry(request: Request, env: SyncEnv, now: number): Promise<Response> {
  const url = new URL(request.url);
  if (url.searchParams.size > 0) return fail(400, "bad_request", "Unknown field");
  for (const [name] of request.headers) {
    if (name.startsWith("x-nuvio-") && !ALLOWED_NUVIO_HEADERS.has(name)) {
      return fail(400, "bad_request", "Unknown field");
    }
  }
  const contentType = request.headers.get("content-type")?.split(";", 1)[0]?.trim().toLowerCase() ?? "";
  if (contentType !== "" && contentType !== "application/gzip" && contentType !== "application/octet-stream") {
    return fail(400, "bad_request", "Unknown field");
  }

  const auth = await authorize(request, env);
  if (auth === null) return fail(401, "unauthorized", "Unauthorized");

  const encoding = request.headers.get("content-encoding")?.trim().toLowerCase() ?? "";
  if (encoding !== "gzip") return fail(415, "unsupported_media", "Expected gzip");
  const body = await readBounded(request, TELEMETRY_MAX_BYTES);
  if (body === "too_large") return fail(413, "payload_too_large", "Payload too large");
  if (body.byteLength === 0) return fail(400, "bad_request", "Empty batch");

  const rate = await takeRateHit(env, `telemetry:${auth.device}`, DAILY_BATCHES, DAY_MS, now);
  if (!rate.allowed) return tooMany(rate.retryAfterSeconds);

  const appBuild = headerToken(request, "x-nuvio-app-build", /^[0-9.]{1,32}$/) ?? "0";
  const kind = headerToken(request, "x-nuvio-kind", /^[a-z0-9-]{1,24}$/) ?? "behaviour";

  await env.DB.prepare(
    "INSERT INTO telemetry_batches (device, received_at, app_build, kind, body) VALUES (?1, ?2, ?3, ?4, ?5)",
  ).bind(auth.device, now, appBuild, kind, body).run();
  await env.DB.prepare(
    "DELETE FROM telemetry_batches WHERE received_at < ?1",
  ).bind(now - THIRTY_DAYS_MS).run();
  const count = await env.DB.prepare("SELECT COUNT(*) AS n FROM telemetry_batches").first<{ n: number }>();
  if ((count?.n ?? 0) > ROW_CAP) {
    await env.DB.prepare(
      "DELETE FROM telemetry_batches WHERE id IN (SELECT id FROM telemetry_batches ORDER BY received_at ASC, id ASC LIMIT 100)",
    ).run();
  }
  return json({ ok: true });
}

async function authorize(request: Request, env: SyncEnv): Promise<{ device: string } | null> {
  const ingest = request.headers.get("x-nuvio-ingest");
  if (ingest !== null) {
    const expected = env.TELEMETRY_INGEST_KEY ?? "";
    const matches = await secretEquals(ingest, expected);
    if (expected.length < MIN_INGEST_LENGTH || ingest.length < MIN_INGEST_LENGTH || !matches) return null;
    const install = headerToken(request, "x-nuvio-install", /^[a-z0-9-]{8,64}$/);
    if (install === null) return null;
    return { device: encodeHex(await sha256(new TextEncoder().encode(install))) };
  }
  const bearer = await readBearer(request);
  if (bearer === null) return null;
  const known = await env.DB.prepare(
    "SELECT space_id FROM spaces WHERE token_hash_hex = ?1 AND revoked_at IS NULL LIMIT 1",
  ).bind(bearer.tokenHashHex).first<{ space_id: string }>();
  if (known === null) return null;
  return { device: bearer.tokenHashHex };
}

function headerToken(request: Request, name: string, pattern: RegExp): string | null {
  const value = request.headers.get(name)?.trim() ?? "";
  return pattern.test(value) ? value : null;
}
