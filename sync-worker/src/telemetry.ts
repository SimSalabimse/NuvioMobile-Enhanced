import { readBearer } from "./auth";
import { readBounded } from "./body";
import type { SyncEnv } from "./env";
import { fail, json } from "./http";

const TELEMETRY_MAX_BYTES = 512 * 1024;
const THIRTY_DAYS_MS = 30 * 24 * 60 * 60 * 1000;

/**
 * Authenticated behaviour batches. The bearer is the same per-device token as
 * space sync. The body is stored as gzip and is not decoded here.
 */
export async function storeTelemetry(request: Request, env: SyncEnv, now: number): Promise<Response> {
  const auth = await readBearer(request);
  if (auth === null) return fail(401, "unauthorized", "Unauthorized");
  const known = await env.DB.prepare(
    "SELECT space_id FROM spaces WHERE token_hash_hex = ?1 AND revoked_at IS NULL LIMIT 1",
  ).bind(auth.tokenHashHex).first<{ space_id: string }>();
  if (known === null) return fail(401, "unauthorized", "Unauthorized");

  const encoding = request.headers.get("content-encoding")?.trim().toLowerCase() ?? "";
  if (encoding !== "gzip") return fail(415, "unsupported_media", "Expected gzip");
  const body = await readBounded(request, TELEMETRY_MAX_BYTES);
  if (body === "too_large") return fail(413, "payload_too_large", "Payload too large");
  if (body.byteLength === 0) return fail(400, "bad_request", "Empty batch");

  const appBuild = headerToken(request, "x-nuvio-app-build", /^[0-9.]{1,32}$/) ?? "0";
  const kind = headerToken(request, "x-nuvio-kind", /^[a-z0-9-]{1,24}$/) ?? "behaviour";

  await env.DB.prepare(
    "INSERT INTO telemetry_batches (device, received_at, app_build, kind, body) VALUES (?1, ?2, ?3, ?4, ?5)",
  ).bind(auth.tokenHashHex, now, appBuild, kind, body).run();
  await env.DB.prepare(
    "DELETE FROM telemetry_batches WHERE received_at < ?1",
  ).bind(now - THIRTY_DAYS_MS).run();
  return json({ ok: true });
}

function headerToken(request: Request, name: string, pattern: RegExp): string | null {
  const value = request.headers.get(name)?.trim() ?? "";
  return pattern.test(value) ? value : null;
}
