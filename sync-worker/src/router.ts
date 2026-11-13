import { authenticate } from "./auth";
import { isJsonContentType, readBounded } from "./body";
import { limitsOf, type SyncEnv } from "./env";
import { fail, json, tooMany } from "./http";
import { isUuidV4 } from "./ids";
import { spaceBucket, takeRateHit } from "./rate";
import { pullBlobs, pushBatch } from "./blobs";
import { createSpace, joinSpace, readSpaceId } from "./spaces";

export async function route(request: Request, env: SyncEnv): Promise<Response> {
  const url = new URL(request.url);
  if (request.method === "OPTIONS") return fail(404, "not_found", "Not found");
  if (url.pathname === "/health") {
    if (request.method !== "GET") return fail(405, "method_not_allowed", "Method not allowed");
    return json({ ok: true });
  }

  const limits = limitsOf(env);
  if (url.pathname === "/v1/spaces") {
    if (request.method !== "POST") return fail(405, "method_not_allowed", "Method not allowed");
    const body = await readJson(request, limits.maxBody);
    if (body === "too_large") return fail(413, "payload_too_large", "Payload too large");
    if (body === "bad") return fail(400, "bad_request", "Expected JSON");
    const auth = await authenticate(request, env);
    if (auth === null) return fail(401, "unauthorized", "Unauthorized");
    const spaceId = readSpaceId(body);
    if (spaceId === null) return fail(400, "bad_request", "Invalid space");
    const now = Date.now();
    const rate = await takeRateHit(env, spaceBucket(spaceId), limits.rateLimit, limits.rateWindowMs, now);
    if (!rate.allowed) return tooMany(rate.retryAfterSeconds);
    return createSpace(env, auth, body, now);
  }

  const match = /^\/v1\/spaces\/([0-9a-f-]+)\/(join|batches|blobs)$/.exec(url.pathname);
  if (match === null) return fail(404, "not_found", "Not found");
  const spaceId = match[1]!;
  const action = match[2]!;
  if (!isUuidV4(spaceId)) return fail(400, "bad_request", "Invalid space id");

  if (action === "blobs" && request.method === "GET") {
    const raw = await readBounded(request, limits.maxBody);
    if (raw === "too_large") return fail(413, "payload_too_large", "Payload too large");
  }

  const auth = await authenticate(request, env);
  if (auth === null) return fail(401, "unauthorized", "Unauthorized");
  const now = Date.now();
  const rate = await takeRateHit(env, spaceBucket(spaceId), limits.rateLimit, limits.rateWindowMs, now);
  if (!rate.allowed) return tooMany(rate.retryAfterSeconds);

  if (action === "join") {
    if (request.method !== "POST") return fail(405, "method_not_allowed", "Method not allowed");
    const raw = await readBounded(request, limits.maxBody);
    if (raw === "too_large") return fail(413, "payload_too_large", "Payload too large");
    if (raw.length > 0) return fail(400, "bad_request", "Join takes no body");
    return joinSpace(env, auth, spaceId);
  }
  if (action === "batches") {
    if (request.method !== "POST") return fail(405, "method_not_allowed", "Method not allowed");
    const body = await readJson(request, limits.maxBody);
    if (body === "too_large") return fail(413, "payload_too_large", "Payload too large");
    if (body === "bad") return fail(400, "bad_request", "Expected JSON");
    return pushBatch(env, auth, spaceId, body, limits, now);
  }
  if (action === "blobs") {
    if (request.method !== "GET") {
      const raw = await readBounded(request, limits.maxBody);
      if (raw === "too_large") return fail(413, "payload_too_large", "Payload too large");
      return fail(405, "method_not_allowed", "Method not allowed");
    }
    return pullBlobs(env, auth, spaceId, url, limits);
  }
  return fail(404, "not_found", "Not found");
}

async function readJson(request: Request, max: number): Promise<unknown | "too_large" | "bad"> {
  if (!isJsonContentType(request)) return "bad";
  const raw = await readBounded(request, max);
  if (raw === "too_large") return "too_large";
  try {
    return JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(raw));
  } catch {
    return "bad";
  }
}
