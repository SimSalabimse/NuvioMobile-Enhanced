/** Read at most `max` bytes. A longer body is rejected without buffering the rest. */
export async function readBounded(request: Request, max: number): Promise<Uint8Array | "too_large"> {
  const declared = request.headers.get("content-length");
  if (declared !== null && (!/^\d+$/.test(declared) || Number(declared) > max)) return "too_large";
  const reader = request.body?.getReader();
  if (!reader) return new Uint8Array();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    if (!value) continue;
    total += value.byteLength;
    if (total > max) {
      await reader.cancel();
      return "too_large";
    }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return out;
}

export function declaredTooLarge(request: Request, max: number): boolean {
  const declared = request.headers.get("content-length");
  if (declared === null) return false;
  return !/^\d+$/.test(declared) || Number(declared) > max;
}

export function isJsonContentType(request: Request): boolean {
  const header = request.headers.get("content-type");
  if (header === null) return false;
  const media = header.split(";", 1)[0]?.trim().toLowerCase();
  return media === "application/json";
}

export async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return new Uint8Array(digest);
}

export function bytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  return crypto.subtle.timingSafeEqual(left, right);
}

/**
 * Compare two strings without leaking the expected value through length or
 * early exit. Both sides are hashed to 32 bytes first.
 */
export async function secretEquals(provided: string, expected: string): Promise<boolean> {
  const encoder = new TextEncoder();
  const [providedHash, expectedHash] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(provided)),
    crypto.subtle.digest("SHA-256", encoder.encode(expected)),
  ]);
  return crypto.subtle.timingSafeEqual(new Uint8Array(providedHash), new Uint8Array(expectedHash));
}
