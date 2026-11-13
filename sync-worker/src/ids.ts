/** Transport identifiers. Plaintext record ids are inside the ciphertext. */

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

/** Starts with an alphanumeric so "." and ".." cannot be folder path segments. */
const DEVICE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

export function isUuidV4(value: string): boolean {
  return UUID_V4.test(value);
}

export function isDeviceId(value: string): boolean {
  return DEVICE_ID.test(value);
}

export function isCursor(value: string): boolean {
  return /^(0|[1-9][0-9]{0,15})$/.test(value) && Number(value) <= Number.MAX_SAFE_INTEGER;
}
