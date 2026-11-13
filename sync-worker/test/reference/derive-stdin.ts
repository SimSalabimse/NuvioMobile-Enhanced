/**
 * Print the allowlist hash for a passphrase and salt. Passphrase stays on stdin.
 *
 *   npx tsx test/reference/derive-stdin.ts < /path/outside/the/repo/input.json
 *
 * input.json: {"passphrase":"...","saltBase64":"..."}
 * Does not print the passphrase, the master key, or the bearer token.
 */
import { readFileSync } from "node:fs";
import { encodeB64, encodeHex } from "./bytes";
import { deriveKeys, sha256 } from "./crypto";

const input = JSON.parse(readFileSync(0, "utf8")) as { passphrase?: unknown; saltBase64?: unknown };
if (typeof input.passphrase !== "string" || typeof input.saltBase64 !== "string") {
  process.stderr.write("stdin JSON needs passphrase and saltBase64\n");
  process.exit(1);
}
const salt = Uint8Array.from(Buffer.from(input.saltBase64, "base64"));
const derived = await deriveKeys(input.passphrase, salt);
const tokenHash = await sha256(derived.authToken);
process.stdout.write(`tokenHashHex=${encodeHex(tokenHash)}\nkeyCheckBase64=${encodeB64(derived.keyCheck)}\n`);
