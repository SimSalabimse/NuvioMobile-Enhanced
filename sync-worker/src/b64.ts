/** Strict standard base64 (RFC 4648) with padding. Rejects non-canonical forms. */

export function encodeB64(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]!);
  return btoa(binary);
}

export function decodeB64(value: string): Uint8Array | null {
  if (value.length === 0 || value.length % 4 !== 0) return null;
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(value)) return null;
  const pad = value.endsWith("==") ? 2 : value.endsWith("=") ? 1 : 0;
  if (pad === 2 && value.endsWith("===") ) return null;
  if (value.slice(0, value.length - pad).includes("=")) return null;
  let binary: string;
  try {
    binary = atob(value);
  } catch {
    return null;
  }
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  if (encodeB64(out) !== value) return null;
  return out;
}

/** 32-byte token as base64url without padding (43 characters). */
export function decodeToken(value: string): Uint8Array | null {
  if (!/^[A-Za-z0-9_-]{43}$/.test(value)) return null;
  const standard = `${value.replace(/-/g, "+").replace(/_/g, "/")}=`;
  const raw = decodeB64(standard);
  if (raw === null || raw.length !== 32) return null;
  return raw;
}

export function encodeHex(bytes: Uint8Array): string {
  let hex = "";
  for (let i = 0; i < bytes.length; i++) hex += bytes[i]!.toString(16).padStart(2, "0");
  return hex;
}

export function decodeHex(value: string): Uint8Array | null {
  if (value.length === 0 || value.length % 2 !== 0) return null;
  if (!/^[0-9a-f]+$/.test(value)) return null;
  const out = new Uint8Array(value.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = Number.parseInt(value.slice(i * 2, i * 2 + 2), 16);
  return out;
}
