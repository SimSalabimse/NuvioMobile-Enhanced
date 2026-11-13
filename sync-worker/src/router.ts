import { enrollAuthorized, readBearer } from "./auth";
import { declaredTooLarge, isJsonContentType, readBounded } from "./body";
import { limitsOf, type Limits, type SyncEnv } from "./env";
import { fail, json, tooMany } from "./http";
import { isUuidV4 } from "./ids";
import { spaceBucket, takeRateHit } from "./rate";
import { pullBlobs, pushBatch } from "./blobs";
import { createSpace, denySpace, joinSpace, listSpaces, loadSpace, readSpaceId, revokeSpace } from "./spaces";
import type { Authed } from "./auth";

export async function route(request: Request, env: SyncEnv): Promise<Response> {
  const url = new URL(request.url);
  if (request.method === "OPTIONS") return fail(404, "not_found", "Not found");
  if (url.pathname === "/health") {
    if (request.method !== "GET") return fail(405, "method_not_allowed", "Method not allowed");
    return json({ ok: true });
  }

  const limits = limitsOf(env);
  if (url.pathname === "/v1/spaces") {
    if (request.method === "GET") return listRoute(request, env);
    if (request.method !== "POST") return fail(405, "method_not_allowed", "Method not allowed");
    return createRoute(request, env, limits);
  }

  const oneSpace = /^\/v1\/spaces\/([0-9a-f-]+)$/.exec(url.pathname);
  if (oneSpace) {
    const spaceId = oneSpace[1]!;
    if (!isUuidV4(spaceId)) return fail(400, "bad_request", "Invalid space id");
    if (request.method !== "DELETE") return fail(405, "method_not_allowed", "Method not allowed");
    return revokeRoute(request, env, spaceId);
  }

  const match = /^\/v1\/spaces\/([0-9a-f-]+)\/(join|batches|blobs)$/.exec(url.pathname);
  if (match === null) return fail(404, "not_found", "Not found");
  const spaceId = match[1]!;
  const action = match[2]!;
  if (!isUuidV4(spaceId)) return fail(400, "bad_request", "Invalid space id");

  if (action === "join") {
    if (request.method !== "POST") return fail(405, "method_not_allowed", "Method not allowed");
    return joinRoute(request, env, spaceId, limits);
  }
  if (action === "batches") {
    if (request.method !== "POST") return fail(405, "method_not_allowed", "Method not allowed");
    return batchRoute(request, env, spaceId, limits);
  }
  if (request.method !== "GET") return fail(405, "method_not_allowed", "Method not allowed");
  return pullRoute(request, env, spaceId, url, limits);
}

async function listRoute(request: Request, env: SyncEnv): Promise<Response> {
  if (!(await enrollAuthorized(request, env))) return fail(401, "unauthorized", "Unauthorized");
  return listSpaces(env);
}

async function createRoute(request: Request, env: SyncEnv, limits: Limits): Promise<Response> {
  if (!(await enrollAuthorized(request, env))) return fail(401, "unauthorized", "Unauthorized");
  const auth = await readBearer(request);
  if (auth === null) return fail(401, "unauthorized", "Unauthorized");
  if (declaredTooLarge(request, limits.maxBody)) return fail(413, "payload_too_large", "Payload too large");
  const body = await readJson(request, limits.maxBody);
  if (body === "too_large") return fail(413, "payload_too_large", "Payload too large");
  if (body === "bad") return fail(400, "bad_request", "Expected JSON");
  const spaceId = readSpaceId(body);
  if (spaceId === null) return fail(400, "bad_request", "Invalid space");
  const now = Date.now();
  const rate = await takeRateHit(env, spaceBucket(spaceId), limits.rateLimit, limits.rateWindowMs, now);
  if (!rate.allowed) return tooMany(rate.retryAfterSeconds);
  return createSpace(env, auth, body, now);
}

async function revokeRoute(request: Request, env: SyncEnv, spaceId: string): Promise<Response> {
  if (!(await enrollAuthorized(request, env))) return fail(401, "unauthorized", "Unauthorized");
  return revokeSpace(env, spaceId, Date.now());
}

async function joinRoute(request: Request, env: SyncEnv, spaceId: string, limits: Limits): Promise<Response> {
  const auth = await readBearer(request);
  if (auth === null) return fail(401, "unauthorized", "Unauthorized");
  if (declaredTooLarge(request, limits.maxBody)) return fail(413, "payload_too_large", "Payload too large");
  const raw = await readBounded(request, limits.maxBody);
  if (raw === "too_large") return fail(413, "payload_too_large", "Payload too large");
  if (raw.length > 0) return fail(400, "bad_request", "Join takes no body");
  const denied = await gateSpace(env, auth, spaceId);
  if (denied) return denied;
  const now = Date.now();
  const rate = await takeRateHit(env, spaceBucket(spaceId), limits.rateLimit, limits.rateWindowMs, now);
  if (!rate.allowed) return tooMany(rate.retryAfterSeconds);
  return joinSpace(env, auth, spaceId);
}

async function batchRoute(request: Request, env: SyncEnv, spaceId: string, limits: Limits): Promise<Response> {
  const auth = await readBearer(request);
  if (auth === null) return fail(401, "unauthorized", "Unauthorized");
  if (declaredTooLarge(request, limits.maxBody)) return fail(413, "payload_too_large", "Payload too large");
  const body = await readJson(request, limits.maxBody);
  if (body === "too_large") return fail(413, "payload_too_large", "Payload too large");
  if (body === "bad") return fail(400, "bad_request", "Expected JSON");
  const denied = await gateSpace(env, auth, spaceId);
  if (denied) return denied;
  const now = Date.now();
  const rate = await takeRateHit(env, spaceBucket(spaceId), limits.rateLimit, limits.rateWindowMs, now);
  if (!rate.allowed) return tooMany(rate.retryAfterSeconds);
  return pushBatch(env, auth, spaceId, body, limits, now);
}

async function pullRoute(request: Request, env: SyncEnv, spaceId: string, url: URL, limits: Limits): Promise<Response> {
  const auth = await readBearer(request);
  if (auth === null) return fail(401, "unauthorized", "Unauthorized");
  const denied = await gateSpace(env, auth, spaceId);
  if (denied) return denied;
  const now = Date.now();
  const rate = await takeRateHit(env, spaceBucket(spaceId), limits.rateLimit, limits.rateWindowMs, now);
  if (!rate.allowed) return tooMany(rate.retryAfterSeconds);
  return pullBlobs(env, auth, spaceId, url, limits);
}

/** Read the space and compare the token. No rate-limit write until this passes. */
async function gateSpace(env: SyncEnv, auth: Authed, spaceId: string): Promise<Response | null> {
  const space = await loadSpace(env, spaceId);
  return denySpace(space, auth);
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
