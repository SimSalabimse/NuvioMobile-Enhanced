/**
 * Reference KDF and AES-256-GCM. Runs in Node (vector generator) and in the
 * Workers test isolate. The deployed Worker does not import this file.
 *
 * PBKDF2-HMAC-SHA256 -> 32-byte master. One HMAC-SHA256 block expands it.
 * Do not run HKDF-Extract on the master: Web Crypto HKDF always extracts.
 */
import { bytesEqual, concat, encodeB64Url } from "./bytes";

export const KDF_ITERATIONS = 600_000;
export const SALT_BYTES = 16;
export const NONCE_BYTES = 12;
export const KEY_BYTES = 32;
export const KEY_CHECK_BYTES = 16;
export const MAX_PLAINTEXT_BYTES = 8192;
export const FORMAT_VERSION = 1;

const ENC_INFO = "nuvio-sync-v1 enc";
const AUTH_INFO = "nuvio-sync-v1 auth";
const CHECK_INFO = "nuvio-sync-v1 check";
const textEncoder = new TextEncoder();

export class SyncCryptoError extends Error {
  constructor(readonly code: "wrong_passphrase" | "corrupt_ciphertext" | "bad_input") {
    super(code);
  }
}

export interface DerivedKeys {
  master: Uint8Array;
  encKey: Uint8Array;
  authToken: Uint8Array;
  keyCheck: Uint8Array;
}

export async function deriveKeys(passphrase: string, salt: Uint8Array): Promise<DerivedKeys> {
  if (salt.length !== SALT_BYTES) throw new SyncCryptoError("bad_input");
  const master = await pbkdf2(passphrase.normalize("NFC"), salt, KDF_ITERATIONS);
  const encKey = await hmacBlock(master, ENC_INFO);
  const authToken = await hmacBlock(master, AUTH_INFO);
  const check = await hmacBlock(master, CHECK_INFO);
  return { master, encKey, authToken, keyCheck: check.slice(0, KEY_CHECK_BYTES) };
}

/** Routing fields the server stores beside the ciphertext and could alter. */
export interface RoutingAad {
  formatVersion: number;
  spaceId: string;
  blobId: string;
  deviceId: string;
  kind: string;
}

/**
 * Associated data, one field per line. formatVersion, space, blob id, device,
 * and record kind are all outside the ciphertext. Seq and receivedAtMs are
 * assigned by the server after encryption, so they are not here.
 */
export function associatedDataText(routing: RoutingAad): string {
  return `nuvio-sync/${routing.formatVersion}\n${routing.spaceId}\n${routing.blobId}\n${routing.deviceId}\n${routing.kind}\n`;
}

export function associatedData(routing: RoutingAad): Uint8Array {
  return textEncoder.encode(associatedDataText(routing));
}

/** 12 bytes from the platform CSPRNG. Never a counter. */
export function randomNonce(fill: (bytes: Uint8Array) => void = (bytes) => {
  crypto.getRandomValues(bytes);
}): Uint8Array {
  const nonce = new Uint8Array(NONCE_BYTES);
  fill(nonce);
  if (nonce.length !== NONCE_BYTES) throw new SyncCryptoError("bad_input");
  return nonce;
}

export function joinCode(spaceId: string, salt: Uint8Array, keyCheck: Uint8Array): string {
  return `nuvio-sync-v1:${spaceId}:${encodeB64Url(salt)}:${encodeB64Url(keyCheck)}`;
}

export async function encryptBlob(
  encKey: Uint8Array,
  nonce: Uint8Array,
  aad: Uint8Array,
  plaintext: Uint8Array,
): Promise<Uint8Array> {
  if (encKey.length !== KEY_BYTES || nonce.length !== NONCE_BYTES) throw new SyncCryptoError("bad_input");
  if (plaintext.length > MAX_PLAINTEXT_BYTES) throw new SyncCryptoError("bad_input");
  const key = await crypto.subtle.importKey("raw", asBuffer(encKey), { name: "AES-GCM" }, false, ["encrypt"]);
  const cipher = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: asBuffer(nonce), additionalData: asBuffer(aad), tagLength: 128 },
    key,
    asBuffer(plaintext),
  );
  return new Uint8Array(cipher);
}

export async function decryptBlob(
  encKey: Uint8Array,
  nonce: Uint8Array,
  aad: Uint8Array,
  ciphertext: Uint8Array,
): Promise<Uint8Array> {
  if (encKey.length !== KEY_BYTES || nonce.length !== NONCE_BYTES || ciphertext.length < 16) {
    throw new SyncCryptoError("bad_input");
  }
  const key = await crypto.subtle.importKey("raw", asBuffer(encKey), { name: "AES-GCM" }, false, ["decrypt"]);
  try {
    const plain = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: asBuffer(nonce), additionalData: asBuffer(aad), tagLength: 128 },
      key,
      asBuffer(ciphertext),
    );
    return new Uint8Array(plain);
  } catch {
    throw new SyncCryptoError("corrupt_ciphertext");
  }
}

export function keyCheckMatches(actual: Uint8Array, expected: Uint8Array): boolean {
  return bytesEqual(actual, expected);
}

export interface OpenEnvelope {
  id: string;
  v: number;
  kind: string;
  deviceId: string;
  nonce: Uint8Array;
  ciphertext: Uint8Array;
}

/** Decrypt with the routing fields as received. The plaintext kind must match. */
export async function openBlob(encKey: Uint8Array, spaceId: string, envelope: OpenEnvelope): Promise<Uint8Array> {
  const plain = await decryptBlob(
    encKey,
    envelope.nonce,
    associatedData({
      formatVersion: envelope.v,
      spaceId,
      blobId: envelope.id,
      deviceId: envelope.deviceId,
      kind: envelope.kind,
    }),
    envelope.ciphertext,
  );
  let parsed: { kind?: unknown };
  try {
    parsed = JSON.parse(new TextDecoder().decode(plain)) as { kind?: unknown };
  } catch {
    throw new SyncCryptoError("corrupt_ciphertext");
  }
  if (parsed.kind !== envelope.kind) throw new SyncCryptoError("corrupt_ciphertext");
  return plain;
}

async function pbkdf2(passphrase: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  const material = await crypto.subtle.importKey("raw", asBuffer(textEncoder.encode(passphrase)), "PBKDF2", false, [
    "deriveBits",
  ]);
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: asBuffer(salt), iterations },
    material,
    KEY_BYTES * 8,
  );
  return new Uint8Array(bits);
}

async function hmacBlock(master: Uint8Array, label: string): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey("raw", asBuffer(master), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const data = concat([textEncoder.encode(label), Uint8Array.of(0x01)]);
  const signature = await crypto.subtle.sign("HMAC", key, asBuffer(data));
  return new Uint8Array(signature);
}

export async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", asBuffer(bytes)));
}

function asBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}
