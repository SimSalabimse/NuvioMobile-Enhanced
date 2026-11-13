import { env, exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { decodeB64, decodeHex, encodeB64 } from "../src/b64";
import { encodeB64Url } from "./reference/bytes";
import { canonicalJson } from "./reference/canonical";
import { associatedData, decryptBlob, encryptBlob } from "./reference/crypto";
import { advancePullCursor } from "./reference/cursor";
import { parseRecord } from "./reference/records";
import cryptoVector from "../../docs/sync/test-vectors/v1/crypto.json";

const TOKEN = cryptoVector.authTokenBase64Url;
const ENROLL = "test-enroll-not-a-real-secret";

function space(n: number): string {
  return `12121212-1212-4121-8121-1212121212${n.toString(16).padStart(2, "0")}`;
}
const encKeyBytes = decodeHex(cryptoVector.encKeyHex);
if (encKeyBytes === null) throw new Error("vector enc key");
const encKey = encKeyBytes;

function nonceFor(n: number): string {
  const bytes = new Uint8Array(12);
  bytes[11] = n;
  return encodeB64(bytes);
}

function bearer(token: string, jsonBody = true): HeadersInit {
  return {
    authorization: `Bearer ${token}`,
    ...(jsonBody ? { "content-type": "application/json" } : {}),
  };
}

function enrolled(token: string, jsonBody = true): HeadersInit {
  return { ...bearer(token, jsonBody), "x-nuvio-enroll": ENROLL };
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
    headers: enrolled(token),
    body: JSON.stringify(createBody(spaceId)),
  });
}

function blob(id: string, n: number, ciphertext = "AAAAAAAAAAAAAAAAAAAAAA==") {
  return { id, v: 1, kind: "mark", nonce: nonceFor(n), ciphertext };
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

  it("rejects create without the enroll secret and does not write", async () => {
    const missing = await call("/v1/spaces", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(createBody(space(1))),
    });
    expect(missing.status).toBe(401);
    const bearerOnly = await call("/v1/spaces", {
      method: "POST",
      headers: bearer(TOKEN),
      body: JSON.stringify(createBody(space(1))),
    });
    expect(bearerOnly.status).toBe(401);
    const wrongEnroll = await call("/v1/spaces", {
      method: "POST",
      headers: { ...bearer(TOKEN), "x-nuvio-enroll": "wrong-enroll-secret" },
      body: JSON.stringify(createBody(space(1))),
    });
    expect(wrongEnroll.status).toBe(401);
    const enrollOnly = await call("/v1/spaces", {
      method: "POST",
      headers: { "content-type": "application/json", "x-nuvio-enroll": ENROLL },
      body: JSON.stringify(createBody(space(1))),
    });
    expect(enrollOnly.status).toBe(401);
    const stranger = encodeB64Url(new Uint8Array(32).fill(0x22));
    const probe = await call(`/v1/spaces/${space(1)}/blobs?cursor=0`, { headers: bearer(stranger, false) });
    expect(probe.status).toBe(404);
    const row = await env.DB.prepare("SELECT space_id FROM spaces WHERE space_id = ?1").bind(space(1)).first();
    const rate = await env.DB.prepare("SELECT hits FROM rate_windows WHERE bucket = ?1").bind(`space:${space(1)}`).first();
    expect(row).toBeNull();
    expect(rate).toBeNull();
  });

  it("creates, replays, lists, and joins a space", async () => {
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
    const listed = await call("/v1/spaces", { headers: { "x-nuvio-enroll": ENROLL } });
    expect(listed.status).toBe(200);
    const listBody = await listed.json() as { spaces: Array<{ spaceId: string; revokedAtMs: number | null }> };
    expect(listBody.spaces.some((row) => row.spaceId === space(3) && row.revokedAtMs === null)).toBe(true);
    expect(JSON.stringify(listBody)).not.toContain(cryptoVector.saltBase64);
    expect(JSON.stringify(listBody)).not.toContain(cryptoVector.keyCheckBase64);
    const hidden = await call("/v1/spaces");
    expect(hidden.status).toBe(401);

    const joined = await call(`/v1/spaces/${space(3)}/join`, { method: "POST", headers: bearer(TOKEN, false) });
    expect(joined.status).toBe(200);
    const other = encodeB64Url(new Uint8Array(32).fill(0x33));
    const wrong = await call(`/v1/spaces/${space(3)}/join`, { method: "POST", headers: bearer(other, false) });
    expect(wrong.status).toBe(401);
    const body = await wrong.json();
    expect(JSON.stringify(body)).not.toContain(cryptoVector.saltBase64);
    const wrongRate = await env.DB.prepare("SELECT hits FROM rate_windows WHERE bucket = ?1").bind(`space:${space(3)}`).first<{ hits: number }>();
    expect(wrongRate?.hits).toBe(3);
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
      associatedData({
        formatVersion: 1,
        spaceId: space(4),
        blobId: tombId,
        deviceId: "device-a",
        kind: "mark",
      }),
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
          { id: tombId, v: 1, kind: "mark", nonce: cryptoVector.nonceBase64, ciphertext: cipher },
          blob(second, 2),
          blob(third, 3),
        ],
      }),
    });
    expect(pushed.status).toBe(200);
    const stored = await pushed.json() as { results: Array<{ status: string; seq: number }> };
    expect(stored).not.toHaveProperty("highWater");
    expect(stored).not.toHaveProperty("cursor");
    expect(stored.results.map((row) => row.status)).toEqual(["stored", "stored", "stored"]);
    expect(stored.results.map((row) => row.seq)).toEqual([1, 2, 3]);

    const again = await call(`/v1/spaces/${space(4)}/batches`, {
      method: "POST",
      headers: bearer(TOKEN),
      body: JSON.stringify({ deviceId: "device-a", blobs: [blob(second, 2)] }),
    });
    const duplicate = await again.json() as { results: Array<{ status: string; seq: number }> };
    expect(duplicate).not.toHaveProperty("highWater");
    expect(duplicate.results[0]).toEqual({ id: second, seq: 2, status: "duplicate" });

    const pages: string[] = [];
    let cursor = 0;
    let guard = 0;
    while (guard < 5) {
      guard += 1;
      const page = await call(`/v1/spaces/${space(4)}/blobs?cursor=${cursor}&limit=1`, {
        headers: bearer(TOKEN, false),
      });
      expect(page.status).toBe(200);
      const body = await page.json() as { blobs: Array<{ id: string; seq: number; ciphertext: string; nonce: string; deviceId: string; kind: string; v: number }>; cursor: number; hasMore: boolean };
      cursor = advancePullCursor(cursor, body);
      if (body.blobs.length === 0) {
        expect(body.hasMore).toBe(false);
        break;
      }
      expect(body.blobs).toHaveLength(1);
      pages.push(body.blobs[0]!.id);
      expect(body.hasMore).toBe(pages.length < 3);
    }
    expect(pages).toEqual([tombId, second, third]);

    const overLimit = await call(`/v1/spaces/${space(4)}/blobs?cursor=0&limit=101`, { headers: bearer(TOKEN, false) });
    expect(overLimit.status).toBe(400);
    const zeroLimit = await call(`/v1/spaces/${space(4)}/blobs?cursor=0&limit=0`, { headers: bearer(TOKEN, false) });
    expect(zeroLimit.status).toBe(400);

    const pulled = await call(`/v1/spaces/${space(4)}/blobs?cursor=0&limit=1`, { headers: bearer(TOKEN, false) });
    const first = (await pulled.json() as { blobs: Array<{ id: string; nonce: string; ciphertext: string; deviceId: string; kind: string; v: number }> }).blobs[0]!;
    expect(first.v).toBe(1);
    expect(first.kind).toBe("mark");
    const nonceBytes = decodeB64(first.nonce);
    const cipherBytes = decodeB64(first.ciphertext);
    if (nonceBytes === null || cipherBytes === null) throw new Error("encoding");
    const plain = await decryptBlob(encKey, nonceBytes, associatedData({
      formatVersion: first.v,
      spaceId: space(4),
      blobId: first.id,
      deviceId: first.deviceId,
      kind: first.kind,
    }), cipherBytes);
    const parsed = parseRecord(JSON.parse(new TextDecoder().decode(plain)));
    expect(parsed?.record.deleted).toBe(true);
    expect(parsed?.record.kind).toBe("mark");
    await expect(decryptBlob(encKey, nonceBytes, associatedData({
      formatVersion: first.v,
      spaceId: space(4),
      blobId: first.id,
      deviceId: first.deviceId,
      kind: "detection",
    }), cipherBytes)).rejects.toThrow();
  });

  it("keeps both devices when pushes interleave and the pull cursor comes only from pulls", async () => {
    expect((await createSpace(space(8))).status).toBe(201);
    const a1 = "88888888-8888-4888-8888-888888888881";
    const b1 = "88888888-8888-4888-8888-888888888882";
    const b2 = "88888888-8888-4888-8888-888888888883";
    const a2 = "88888888-8888-4888-8888-888888888884";
    const push = async (deviceId: string, id: string, n: number) => {
      const response = await call(`/v1/spaces/${space(8)}/batches`, {
        method: "POST",
        headers: bearer(TOKEN),
        body: JSON.stringify({ deviceId, blobs: [blob(id, n)] }),
      });
      expect(response.status).toBe(200);
      const body = await response.json() as { results: Array<{ id: string; seq: number; status: string }> };
      expect(body).not.toHaveProperty("highWater");
      expect(body).not.toHaveProperty("cursor");
      return body.results[0]!;
    };
    const firstA = await push("device-a", a1, 11);
    const firstB = await push("device-b", b1, 12);
    expect([firstA.seq, firstB.seq]).toEqual([1, 2]);

    const page = await call(`/v1/spaces/${space(8)}/blobs?cursor=0&limit=1`, { headers: bearer(TOKEN, false) });
    const firstPage = await page.json() as { blobs: Array<{ id: string; seq: number; deviceId: string }>; cursor: number; hasMore: boolean };
    const pullCursor = advancePullCursor(0, firstPage);
    expect(pullCursor).toBe(1);
    expect(firstPage.blobs.map((row) => row.id)).toEqual([a1]);

    const secondB = await push("device-b", b2, 13);
    const secondA = await push("device-a", a2, 14);
    expect([secondB.seq, secondA.seq]).toEqual([3, 4]);

    const skipped = await call(`/v1/spaces/${space(8)}/blobs?cursor=${secondA.seq}`, { headers: bearer(TOKEN, false) });
    const missed = await skipped.json() as { blobs: Array<{ id: string; seq: number }>; cursor: number };
    expect(missed.blobs).toEqual([]);
    expect(advancePullCursor(secondA.seq, missed)).toBe(secondA.seq);

    const rest = await call(`/v1/spaces/${space(8)}/blobs?cursor=${pullCursor}&limit=100`, { headers: bearer(TOKEN, false) });
    const restBody = await rest.json() as { blobs: Array<{ id: string; seq: number; deviceId: string }>; cursor: number; hasMore: boolean };
    expect(advancePullCursor(pullCursor, restBody)).toBe(4);
    expect(restBody.hasMore).toBe(false);
    expect(restBody.blobs.map((row) => `${row.deviceId}:${row.id.slice(-1)}:${row.seq}`)).toEqual([
      "device-b:2:2",
      "device-b:3:3",
      "device-a:4:4",
    ]);
  });

  it("rejects a repeated nonce and a changed ciphertext", async () => {
    expect((await createSpace(space(5))).status).toBe(201);
    const id = "55555555-5555-4555-8555-555555555555";
    const first = await call(`/v1/spaces/${space(5)}/batches`, {
      method: "POST",
      headers: bearer(TOKEN),
      body: JSON.stringify({ deviceId: "device-a", blobs: [blob(id, 21)] }),
    });
    expect(first.status).toBe(200);
    const conflict = await call(`/v1/spaces/${space(5)}/batches`, {
      method: "POST",
      headers: bearer(TOKEN),
      body: JSON.stringify({
        deviceId: "device-a",
        blobs: [blob(id, 21, encodeB64(new Uint8Array(16).fill(9)))],
      }),
    });
    expect(conflict.status).toBe(409);
    const reused = "55555555-5555-4555-8555-555555555556";
    const nonceConflict = await call(`/v1/spaces/${space(5)}/batches`, {
      method: "POST",
      headers: bearer(TOKEN),
      body: JSON.stringify({ deviceId: "device-b", blobs: [blob(reused, 21)] }),
    });
    expect(nonceConflict.status).toBe(409);
    expect(await nonceConflict.json()).toEqual({ error: "conflict", message: "Nonce already used" });
    const pair = await call(`/v1/spaces/${space(5)}/batches`, {
      method: "POST",
      headers: bearer(TOKEN),
      body: JSON.stringify({
        deviceId: "device-a",
        blobs: [
          blob("55555555-5555-4555-8555-555555555557", 30),
          blob("55555555-5555-4555-8555-555555555558", 30),
        ],
      }),
    });
    expect(pair.status).toBe(409);
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
        blobs: [{ id: "66666666-6666-4666-8666-666666666666", v: 1, kind: "mark", nonce: nonceFor(40), ciphertext: cipher }],
      }),
    });
    expect(over.status).toBe(400);
  });

  it("stops a space at the blob cap and still accepts a duplicate", async () => {
    expect((await createSpace(space(9))).status).toBe(201);
    for (let n = 1; n <= 4; n++) {
      const id = `99999999-9999-4999-8999-99999999999${n}`;
      const response = await call(`/v1/spaces/${space(9)}/batches`, {
        method: "POST",
        headers: bearer(TOKEN),
        body: JSON.stringify({ deviceId: "device-a", blobs: [blob(id, 50 + n)] }),
      });
      expect(response.status).toBe(200);
    }
    const full = await call(`/v1/spaces/${space(9)}/batches`, {
      method: "POST",
      headers: bearer(TOKEN),
      body: JSON.stringify({
        deviceId: "device-a",
        blobs: [blob("99999999-9999-4999-8999-999999999990", 70)],
      }),
    });
    expect(full.status).toBe(409);
    expect(await full.json()).toEqual({ error: "quota_exceeded", message: "Space is full" });
    const duplicate = await call(`/v1/spaces/${space(9)}/batches`, {
      method: "POST",
      headers: bearer(TOKEN),
      body: JSON.stringify({ deviceId: "device-a", blobs: [blob("99999999-9999-4999-8999-999999999991", 51)] }),
    });
    expect(duplicate.status).toBe(200);
    const rows = await env.DB.prepare("SELECT COUNT(*) AS n FROM blobs WHERE space_id = ?1").bind(space(9)).first<{ n: number }>();
    expect(rows?.n).toBe(4);
  });

  it("revokes a space with the enroll secret and keeps the salt private", async () => {
    expect((await createSpace(space(10))).status).toBe(201);
    const stranger = await call(`/v1/spaces/${space(10)}`, { method: "DELETE" });
    expect(stranger.status).toBe(401);
    const still = await call(`/v1/spaces/${space(10)}/join`, { method: "POST", headers: bearer(TOKEN, false) });
    expect(still.status).toBe(200);
    const removed = await call(`/v1/spaces/${space(10)}`, { method: "DELETE", headers: { "x-nuvio-enroll": ENROLL } });
    expect(removed.status).toBe(200);
    expect(await removed.json()).toEqual({ v: 1, spaceId: space(10), revoked: true });
    const again = await call(`/v1/spaces/${space(10)}`, { method: "DELETE", headers: { "x-nuvio-enroll": ENROLL } });
    expect(again.status).toBe(200);
    const joined = await call(`/v1/spaces/${space(10)}/join`, { method: "POST", headers: bearer(TOKEN, false) });
    expect(joined.status).toBe(401);
    expect(JSON.stringify(await joined.json())).not.toContain(cryptoVector.saltBase64);
    const pushed = await call(`/v1/spaces/${space(10)}/batches`, {
      method: "POST",
      headers: bearer(TOKEN),
      body: JSON.stringify({ deviceId: "device-a", blobs: [blob("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa10", 80)] }),
    });
    expect(pushed.status).toBe(401);
    const recreated = await createSpace(space(10));
    expect(recreated.status).toBe(409);
    const rows = await env.DB.prepare("SELECT COUNT(*) AS n FROM blobs WHERE space_id = ?1").bind(space(10)).first<{ n: number }>();
    expect(rows?.n).toBe(0);
  });

  it("rate limits a space after the configured window", async () => {
    expect((await createSpace(space(7))).status).toBe(201);
    const statuses: number[] = [];
    for (let i = 0; i < 20; i++) {
      const response = await call(`/v1/spaces/${space(7)}/blobs?cursor=0`, { headers: bearer(TOKEN, false) });
      statuses.push(response.status);
    }
    expect(statuses.slice(0, 19)).toEqual(Array(19).fill(200));
    expect(statuses[19]).toBe(429);
    const limited = await call(`/v1/spaces/${space(7)}/blobs?cursor=0`, { headers: bearer(TOKEN, false) });
    expect(limited.status).toBe(429);
    expect(limited.headers.get("retry-after")).toBeTruthy();
    expect(await limited.json()).toEqual({ error: "rate_limited", message: "Too many requests" });
  });
});
