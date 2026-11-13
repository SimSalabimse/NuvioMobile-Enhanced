import { env, exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { decodeB64, decodeHex } from "../src/b64";
import { encodeB64, encodeB64Url, encodeHex } from "./reference/bytes";
import { canonicalJson } from "./reference/canonical";
import { associatedData, decryptBlob, encryptBlob, sha256 } from "./reference/crypto";
import { parseRecord } from "./reference/records";
import cryptoVector from "../../docs/sync/test-vectors/v1/crypto.json";

const TOKEN = cryptoVector.authTokenBase64Url;

function space(n: number): string {
  return `12121212-1212-4121-8121-1212121212${n.toString(16).padStart(2, "0")}`;
}
const encKeyBytes = decodeHex(cryptoVector.encKeyHex);
if (encKeyBytes === null) throw new Error("vector enc key");
const encKey = encKeyBytes;

function bearer(token: string, jsonBody = true): HeadersInit {
  return {
    authorization: `Bearer ${token}`,
    ...(jsonBody ? { "content-type": "application/json" } : {}),
  };
}

async function call(path: string, init: RequestInit = {}): Promise<Response> {
  return exports.default.fetch(new Request(`https://nuvio-sync.test${path}`, init));
}

function createBody(spaceId: string) {
  return {
    spaceId,
    salt: cryptoVector.saltBase64,
    keyCheck: cryptoVector.keyCheckBase64,
    kdf: "pbkdf2-sha256",
    iterations: 600000,
  };
}

async function createSpace(spaceId: string, token = TOKEN): Promise<Response> {
  return call("/v1/spaces", {
    method: "POST",
    headers: bearer(token),
    body: JSON.stringify(createBody(spaceId)),
  });
}

function blob(id: string, ciphertext = "AAAAAAAAAAAAAAAAAAAAAA==") {
  return { id, nonce: "AAAAAAAAAAAAAAAA", ciphertext };
}

describe("worker protocol", () => {
  it("serves health with no auth and no CORS header", async () => {
    const response = await call("/health");
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true });
    expect(response.headers.get("access-control-allow-origin")).toBeNull();
    const options = await call("/health", { method: "OPTIONS" });
    expect(options.status).toBe(404);
    expect(options.headers.get("access-control-allow-origin")).toBeNull();
  });

  it("rejects a missing token, a malformed token, and a token off the allowlist", async () => {
    const missing = await call("/v1/spaces", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(createBody(space(1))),
    });
    expect(missing.status).toBe(401);
    const malformed = await call("/v1/spaces", {
      method: "POST",
      headers: bearer("not-a-token"),
      body: JSON.stringify(createBody(space(1))),
    });
    expect(malformed.status).toBe(401);
    const stranger = encodeB64Url(new Uint8Array(32).fill(0x22));
    const rejected = await createSpace(space(1), stranger);
    expect(rejected.status).toBe(401);
    expect(await rejected.json()).toEqual({ error: "unauthorized", message: "Unauthorized" });
    const row = await env.DB.prepare("SELECT space_id FROM spaces WHERE space_id = ?1").bind(space(1)).first();
    const rate = await env.DB.prepare("SELECT hits FROM rate_windows WHERE bucket = ?1").bind(`space:${space(1)}`).first();
    expect(row).toBeNull();
    expect(rate).toBeNull();
  });

  it("accepts a hash stored in the D1 allowlist", async () => {
    const tokenBytes = new Uint8Array(32).fill(0x11);
    const token = encodeB64Url(tokenBytes);
    const hash = encodeHex(await sha256(tokenBytes));
    await env.DB.prepare("INSERT INTO allowlist (token_hash_hex, note, created_at) VALUES (?1, 'profile', 1)")
      .bind(hash)
      .run();
    const response = await createSpace(space(2), token);
    expect(response.status).toBe(201);
  });

  it("creates, replays, and joins a space", async () => {
    const created = await createSpace(space(3));
    expect(created.status).toBe(201);
    expect(await created.json()).toEqual({
      v: 1,
      spaceId: space(3),
      kdf: "pbkdf2-sha256",
      iterations: 600000,
      salt: cryptoVector.saltBase64,
      keyCheck: cryptoVector.keyCheckBase64,
    });
    const replay = await createSpace(space(3));
    expect(replay.status).toBe(200);
    const joined = await call(`/v1/spaces/${space(3)}/join`, { method: "POST", headers: bearer(TOKEN, false) });
    expect(joined.status).toBe(200);
    const other = encodeB64Url(new Uint8Array(32).fill(0x33));
    const otherHash = encodeHex(await sha256(new Uint8Array(32).fill(0x33)));
    await env.DB.prepare("INSERT INTO allowlist (token_hash_hex, note, created_at) VALUES (?1, 'other', 1)")
      .bind(otherHash)
      .run();
    const wrong = await call(`/v1/spaces/${space(3)}/join`, { method: "POST", headers: bearer(other, false) });
    expect(wrong.status).toBe(401);
    const body = await wrong.json();
    expect(JSON.stringify(body)).not.toContain(cryptoVector.saltBase64);
  });

  it("round-trips a tombstone, pages the cursor, and treats a re-push as a duplicate", async () => {
    expect((await createSpace(space(4))).status).toBe(201);
    const tombstone = {
      deleted: true,
      hlc: { counter: 0, deviceId: "device-a", wallMs: 2_000_000 },
      id: "mark/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa04",
      kind: "mark",
    };
    const plaintext = canonicalJson(tombstone);
    const tombId = "22222222-2222-4222-8222-222222222222";
    const nonce = decodeB64(cryptoVector.nonceBase64);
    if (nonce === null) throw new Error("nonce");
    const cipher = encodeB64(await encryptBlob(
      encKey,
      nonce,
      associatedData(space(4), tombId, "device-a"),
      new TextEncoder().encode(plaintext),
    ));
    const second = "33333333-3333-4333-8333-333333333333";
    const third = "44444444-4444-4444-8444-444444444444";
    const pushed = await call(`/v1/spaces/${space(4)}/batches`, {
      method: "POST",
      headers: bearer(TOKEN),
      body: JSON.stringify({
        deviceId: "device-a",
        blobs: [
          { id: tombId, nonce: cryptoVector.nonceBase64, ciphertext: cipher },
          blob(second),
          blob(third),
        ],
      }),
    });
    expect(pushed.status).toBe(200);
    const stored = await pushed.json() as { highWater: number; results: Array<{ status: string; seq: number }> };
    expect(stored.highWater).toBe(3);
    expect(stored.results.map((row) => row.status)).toEqual(["stored", "stored", "stored"]);

    const again = await call(`/v1/spaces/${space(4)}/batches`, {
      method: "POST",
      headers: bearer(TOKEN),
      body: JSON.stringify({ deviceId: "device-a", blobs: [blob(second)] }),
    });
    const duplicate = await again.json() as { highWater: number; results: Array<{ status: string; seq: number }> };
    expect(duplicate.results[0]).toEqual({ id: second, seq: 2, status: "duplicate" });
    expect(duplicate.highWater).toBe(3);

    const pages: string[] = [];
    let cursor = "0";
    let guard = 0;
    while (guard < 5) {
      guard += 1;
      const page = await call(`/v1/spaces/${space(4)}/blobs?cursor=${cursor}&limit=1`, {
        headers: bearer(TOKEN, false),
      });
      expect(page.status).toBe(200);
      const body = await page.json() as { blobs: Array<{ id: string; seq: number; ciphertext: string; nonce: string; deviceId: string }>; cursor: number; hasMore: boolean };
      if (body.blobs.length === 0) {
        expect(body.hasMore).toBe(false);
        break;
      }
      expect(body.blobs).toHaveLength(1);
      pages.push(body.blobs[0]!.id);
      expect(body.hasMore).toBe(pages.length < 3);
      cursor = String(body.cursor);
    }
    expect(pages).toEqual([tombId, second, third]);

    const pulled = await call(`/v1/spaces/${space(4)}/blobs?cursor=0&limit=1`, { headers: bearer(TOKEN, false) });
    const first = (await pulled.json() as { blobs: Array<{ id: string; nonce: string; ciphertext: string; deviceId: string }> }).blobs[0]!;
    const nonceBytes = decodeB64(first.nonce);
    const cipherBytes = decodeB64(first.ciphertext);
    if (nonceBytes === null || cipherBytes === null) throw new Error("encoding");
    const plain = await decryptBlob(encKey, nonceBytes, associatedData(space(4), first.id, first.deviceId), cipherBytes);
    const parsed = parseRecord(JSON.parse(new TextDecoder().decode(plain)));
    expect(parsed?.record.deleted).toBe(true);
    expect(parsed?.record.kind).toBe("mark");
  });

  it("rejects a changed ciphertext for an existing blob id", async () => {
    expect((await createSpace(space(5))).status).toBe(201);
    const id = "55555555-5555-4555-8555-555555555555";
    const first = await call(`/v1/spaces/${space(5)}/batches`, {
      method: "POST",
      headers: bearer(TOKEN),
      body: JSON.stringify({ deviceId: "device-a", blobs: [blob(id)] }),
    });
    expect(first.status).toBe(200);
    const conflict = await call(`/v1/spaces/${space(5)}/batches`, {
      method: "POST",
      headers: bearer(TOKEN),
      body: JSON.stringify({
        deviceId: "device-a",
        blobs: [blob(id, encodeB64(new Uint8Array(16).fill(9)))],
      }),
    });
    expect(conflict.status).toBe(409);
    const rows = await env.DB.prepare("SELECT COUNT(*) AS n FROM blobs WHERE space_id = ?1").bind(space(5)).first<{ n: number }>();
    expect(rows?.n).toBe(1);
  });

  it("rejects an oversized body and an oversized ciphertext", async () => {
    expect((await createSpace(space(6))).status).toBe(201);
    const huge = await call(`/v1/spaces/${space(6)}/batches`, {
      method: "POST",
      headers: bearer(TOKEN),
      body: "x".repeat(300_000),
    });
    expect(huge.status).toBe(413);
    const cipher = encodeB64(new Uint8Array(24_577).fill(7));
    const over = await call(`/v1/spaces/${space(6)}/batches`, {
      method: "POST",
      headers: bearer(TOKEN),
      body: JSON.stringify({
        deviceId: "device-a",
        blobs: [{ id: "66666666-6666-4666-8666-666666666666", nonce: "AAAAAAAAAAAAAAAA", ciphertext: cipher }],
      }),
    });
    expect(over.status).toBe(400);
  });

  it("rate limits a space after the configured window", async () => {
    expect((await createSpace(space(7))).status).toBe(201);
    const statuses: number[] = [];
    for (let i = 0; i < 8; i++) {
      const response = await call(`/v1/spaces/${space(7)}/blobs?cursor=0`, { headers: bearer(TOKEN, false) });
      statuses.push(response.status);
    }
    expect(statuses.slice(0, 7)).toEqual([200, 200, 200, 200, 200, 200, 200]);
    expect(statuses[7]).toBe(429);
    const limited = await call(`/v1/spaces/${space(7)}/blobs?cursor=0`, { headers: bearer(TOKEN, false) });
    expect(limited.status).toBe(429);
    expect(limited.headers.get("retry-after")).toBeTruthy();
    expect(await limited.json()).toEqual({ error: "rate_limited", message: "Too many requests" });
  });
});
