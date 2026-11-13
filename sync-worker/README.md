# Nuvio sync Worker

Cloudflare Worker and D1 store for analyzed-details sync format v1. The normative rules are [`docs/sync/SYNC_FORMAT_v1.md`](../docs/sync/SYNC_FORMAT_v1.md). This process stores ciphertext, a salt, a key-check value, and routing ids. It does not see the passphrase, the encryption key, or the plaintext.

Nothing in this directory has been deployed. `database_id` in `wrangler.jsonc` is the placeholder `00000000-0000-0000-0000-000000000000`.

## Endpoints

| Method and path | Auth | Role |
|---|---|---|
| `GET /health` | No | Liveness. Does not touch D1. |
| `POST /v1/spaces` | Bearer | Create a space, or return the same header when the call is repeated. |
| `POST /v1/spaces/:id/join` | Bearer | Return salt and key check. Empty body. |
| `POST /v1/spaces/:id/batches` | Bearer | Store a batch of blobs. Same id and same ciphertext is a duplicate. |
| `GET /v1/spaces/:id/blobs?cursor=&limit=` | Bearer | Pull blobs with `seq` greater than `cursor`. |

Errors are JSON `{ "error", "message" }`. There is no CORS header.

## Auth

The bearer token is derived on the device from the passphrase and the space salt. The Worker compares SHA-256 of that token, in constant time, with an allowlist:

- Worker secret `ALLOWLIST_TOKEN_HASHES`: hex digests separated by commas or whitespace.
- D1 table `allowlist`: one hex digest per row. The read uses the first 32 rows in hex order.

A token off the allowlist is rejected before any rate-limit write. Each space still checks that the token hash equals the hash stored on that space, so one profile's token does not open another profile's space.

## Free-plan usage (estimate)

Cloudflare's free plan, as stated in the approved design, allows 100,000 Worker requests a day, 100,000 D1 rows written a day, and 5 GB of D1. The figures below are an estimate from the protocol, not a measurement.

Assume one profile, three devices, and eight syncs a day on each device (the app comes to the foreground). A steady sync is one pull and, on two of those eight, one push of a few new blobs. Joins happen once per device.

| | Per device per day | Three devices |
|---|---|---|
| Requests | about 10 | about 30 |
| D1 rows written | about 20 (rate-limit rows, plus a handful of blob rows) | about 60 |

A one-time backfill of 1,000 episodes with four segment records each is about 4,000 blob rows and well under the daily write cap if it is spread over a day. A stored blob is a few hundred bytes of base64 plus indexes. Tens of thousands of episodes stay far under 5 GB. The request and row totals above are an estimate.

The per-space limit in `wrangler.jsonc` is 60 requests in each 60-second window. Three devices share that budget. Bodies are capped at 256 KiB, batches at 50 blobs, and a ciphertext at 24576 decoded bytes.

Strangers who find the public URL fail the allowlist. They can still spend Worker requests on 401s. They do not get D1 writes.

## Local checks

Node 22 or newer.

```sh
cd sync-worker
npm ci
npm test
npx wrangler deploy --dry-run
```

`npm test` regenerates Worker types, typechecks, and runs Vitest in the Workers pool. `.npmrc` sets `legacy-peer-deps=true` because npm 10.9 can crash while resolving Vitest's optional peers.

Regenerate vectors after a change to the reference merger or KDF:

```sh
npm run generate-vectors
```

That command runs PBKDF2. Its runtime is printed on stderr. Commit the files it writes under `docs/sync/test-vectors/v1/`.

## Deploy

Do this on a machine logged into the owner's Cloudflare account. These steps create the database and the Worker. They were not run for the pull request.

1. Install Node 22 and run `npm ci` in `sync-worker/`.
2. `npx wrangler login`.
3. `npx wrangler d1 create nuvio-sync`.
4. Replace the placeholder `database_id` in `wrangler.jsonc` with the id that command prints. Leave `database_name` as `nuvio-sync`.
5. `npx wrangler d1 migrations apply nuvio-sync --remote`.
6. On the first device, generate a space id and a 16-byte salt, then derive the token hash without writing the passphrase into the repo or the shell history:

   ```sh
   npx tsx test/reference/derive-stdin.ts < /path/outside/the/repo/input.json
   ```

   `input.json` is `{ "passphrase": "...", "saltBase64": "..." }`. The command prints `tokenHashHex` and `keyCheckBase64`. Delete the input file afterwards.
7. Put that hash in the allowlist before the device calls create. Either:

   ```sh
   npx wrangler secret put ALLOWLIST_TOKEN_HASHES
   ```

   Paste one hash, or several separated by commas. Or insert a row (the note is a label, never the passphrase or the token):

   ```sh
   npx wrangler d1 execute nuvio-sync --remote --command \
     "INSERT INTO allowlist (token_hash_hex, note, created_at) VALUES ('<64 hex chars>', 'profile', 0)"
   ```

8. `npx wrangler deploy`.
9. Give the devices the `*.workers.dev` URL, the space id, the salt, and the passphrase. The join code in the spec carries the space id, the salt, and the key check. The passphrase is typed on each device.
10. Leave traces off. `wrangler.jsonc` sets `traces.enabled` to false and `invocation_logs` to false because platform traces can include request headers. The Worker logs an error name only, and only when a handler throws.

A second profile is a second space. Derive its hash and add it to the same secret or as another allowlist row. Keep the list at 32 D1 rows or fewer; the Worker reads 32.

To rotate a passphrase, create a new space. Blobs in the old space were encrypted to the old key.

## Layout

| Path | Role |
|---|---|
| `src/` | Worker. No merge and no KDF. |
| `migrations/0001_init.sql` | D1 tables. |
| `test/reference/` | KDF, AES-GCM, merge, folder reader, vector generator. |
| `wrangler.jsonc` | Bindings. `database_id` stays a placeholder until step 4. |

## Review notes

- The nonce is random per blob under one encryption key. Reuse breaks AES-GCM. The server cannot see reuse because it cannot see the plaintext.
- Associated data binds the space id, the blob id, and the uploading device id. A copied blob fails decryption in a different space.
- The pull cursor moves only from pull responses. `highWater` on a push is the space counter, and using it as a cursor can skip another device.
- Tombstones have to keep being sent until `purgeAfterMs` (90 × 86400000 ms after the delete clock).
- `keyCheck` lets the holder of the database guess the passphrase offline. The database stays on the owner's account, and the passphrase is not a short PIN.
