# Nuvio sync Worker

Cloudflare Worker and D1 store for analyzed-details sync format v1. The normative rules are [`docs/sync/SYNC_FORMAT_v1.md`](../docs/sync/SYNC_FORMAT_v1.md). This process stores ciphertext, a salt, a key-check value, and routing ids. It does not see the passphrase, the encryption key, or the plaintext.

Nothing in this directory has been deployed. `database_id` in `wrangler.jsonc` is the placeholder `00000000-0000-0000-0000-000000000000`.

## Endpoints

| Method and path | Auth | Role |
|---|---|---|
| `GET /health` | No | Liveness. Does not touch D1. |
| `GET /v1/spaces` | Enroll secret | List space ids. No salt and no key check. |
| `POST /v1/spaces` | Enroll secret and bearer | Create a space, or return the same header when the call is repeated. |
| `DELETE /v1/spaces/:id` | Enroll secret | Revoke a space. Blob rows stay until the database is deleted. |
| `POST /v1/spaces/:id/join` | Bearer | Return salt and key check. Empty body. |
| `POST /v1/spaces/:id/batches` | Bearer | Store a batch of blobs. Same id and same ciphertext is a duplicate. |
| `GET /v1/spaces/:id/blobs?cursor=&limit=` | Bearer | Pull blobs with `seq` greater than `cursor`. |

Errors are JSON `{ "error", "message" }`. There is no CORS header. A push response has per-blob `seq` values and no pull cursor. The pull cursor moves only from pull responses.

## Auth

The bearer token is derived on the device from the passphrase and the space salt. Join and data calls send it as `Authorization: Bearer`. The Worker stores SHA-256 of that token and compares the digest in constant time.

Creating, listing, and revoking spaces require a different secret: the Worker secret `ENROLL_SECRET`. The header is `X-Nuvio-Enroll`. The Worker hashes both sides and compares the digests with `timingSafeEqual`. The secret is at least 16 characters from a CSPRNG. It is not the passphrase. Deploy does not need the passphrase, and the passphrase is never pasted into a Wrangler secret.

A missing enroll secret, a wrong enroll secret, a missing bearer, or a bearer that does not belong to the space is rejected before any rate-limit write. A well-formed bearer for an unknown space is a D1 read and a 404, not a write.

## Free-plan usage (estimate)

The Workers Free plan, as published in the Workers and D1 limits, allows 100,000 requests a day, 100,000 D1 rows written a day, 5,000,000 D1 rows read a day, and 5 GB of D1 storage. Those are the published allowances, not a measurement of this Worker. The figures in the table below are an estimate from the protocol, not a measurement.

Assume one profile, three devices, and eight syncs a day on each device (the app comes to the foreground). A steady sync is one pull and, on two of those eight, one push of a few new blobs. Joins happen once per device.

| | Per device per day | Three devices |
|---|---|---|
| Requests | about 10 | about 30 |
| D1 rows written | about 20 (rate-limit rows, plus a handful of blob rows) | about 60 |

A one-time backfill of 1,000 episodes with four segment records each is about 4,000 blob rows and well under the daily write cap if it is spread over a day. A stored blob is a few hundred bytes of base64 plus indexes. Tens of thousands of episodes stay far under 5 GB. The request and row totals above are an estimate.

The per-space limit in `wrangler.jsonc` is 60 requests in each 600000 ms window. Three devices share that budget. Saturated, that is `60 * (86400000 / 600000) = 8640` rate-limit updates a day. That ceiling is arithmetic from the settings, not a measured usage figure, and it stays under the 100,000 row-write allowance. A space also stops at 20000 blobs. Each new blob writes the table row plus the primary key and two unique indexes; D1's pricing page counts an extra written row per index. Bodies are capped at 256 KiB, batches at 50 blobs, a ciphertext at 24576 decoded bytes, and a pull page at 100 rows.

`GET /health` and rejected requests still spend Worker requests. They do not get a D1 write. A route that fail-opens after 100,000 requests would bypass the Worker, so leave the Worker on `workers.dev` or put the route in fail-closed mode. A wrong enroll secret or a malformed bearer does not read D1. A random well-formed bearer reads one space row and writes nothing.

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

Do this on a machine logged into the owner's Cloudflare account. These steps create the database and the Worker. They were not run for the pull request. None of them read the passphrase.

1. Install Node 22 and run `npm ci` in `sync-worker/`.
2. `npx wrangler login`.
3. `npx wrangler d1 create nuvio-sync`.
4. Replace the placeholder `database_id` in `wrangler.jsonc` with the id that command prints. Leave `database_name` as `nuvio-sync`.
5. `npx wrangler d1 migrations apply nuvio-sync --remote`.
6. `npx wrangler deploy`. Leave the Worker on the `workers.dev` hostname. If you add a route later, set that route to fail closed. A fail-open route bypasses the Worker after the daily request limit.
7. Generate an enroll secret into a file outside the repo. This is random bytes, not the passphrase:

   ```sh
   node -e "process.stdout.write(require('crypto').randomBytes(32).toString('base64'))" > /path/outside/the/repo/enroll-secret
   ```

8. `npx wrangler secret put ENROLL_SECRET`. Paste the file. Do not pass the secret as a command argument. `secret put` deploys a new version. Delete the file after the secret is stored. A missing or short secret makes create, list, and revoke return 401.
9. On the first device, paste the workers.dev URL and the enroll secret once, and type the passphrase. The device creates the space. It shows a join code. Other devices get the join code and the passphrase. They do not get the enroll secret.
10. Leave traces off. `wrangler.jsonc` sets `traces.enabled` to false and `invocation_logs` to false because platform traces can include request headers. The Worker logs an error name only, and only when a handler throws.

To turn a space off, `DELETE /v1/spaces/<space id>` with the `X-Nuvio-Enroll` header. `GET /v1/spaces` with the same header lists ids if you did not keep them. Revoke does not free the blob rows. Deleting the D1 database does.

A second profile is a second space, created the same way with the same enroll secret and a different passphrase. To rotate a passphrase, create a new space. Blobs in the old space were encrypted to the old key. Rotate a leaked enroll secret with `npx wrangler secret put ENROLL_SECRET` and revoke spaces you do not recognize.

`test/reference/derive-stdin.ts` prints a token hash for debugging the KDF. It is not a deploy step.

## Layout

| Path | Role |
|---|---|
| `src/` | Worker. No merge and no KDF. |
| `migrations/0001_init.sql` | D1 tables. |
| `test/reference/` | KDF, AES-GCM, merge, folder reader, vector generator. |
| `wrangler.jsonc` | Bindings. `database_id` stays a placeholder until step 4. |

## Review notes

- The nonce is 12 bytes from a CSPRNG, once per blob under one encryption key. The Worker rejects a repeated nonce. Reuse would break AES-GCM.
- Associated data binds the format version, the space id, the blob id, the uploading device id, and the record kind. `seq` and `receivedAtMs` are assigned after encryption, so a change to them does not change the plaintext.
- The pull cursor moves only from pull responses. A push `seq` is not a cursor. Using it skips another device's blobs.
- Tombstones are kept until `purgeAfterMs` (90 × 86400000 ms after the delete clock). A device whose last successful sync is at least that long ago pulls from cursor 0 and does not upload local records older than the window.
- `keyCheck` lets the holder of the database guess the passphrase offline. The database stays on the owner's account, and the passphrase is not a short PIN. 600000 PBKDF2 iterations were not timed on a phone.
