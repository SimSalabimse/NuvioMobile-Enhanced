# Nuvio analyzed-details sync format v1

Normative spec for the shared sync format. The iOS/KMP client and the Desktop client implement this document and the vectors in `docs/sync/test-vectors/v1/`. The reference implementation is `sync-worker/test/reference/`. It generates those vectors. A change that breaks a vector is a format break.

This version is `1`. A reader that sees any other `v` stops.

The Worker in `sync-worker/` stores ciphertext and routing metadata. It does not implement merge. Both transports carry the same blobs:

- **d)** HTTPS to the Cloudflare Worker.
- **e)** Files in a folder (iCloud Drive or any directory the user picks).

A device can move a blob between the two without re-encrypting it.

## 1. What a sync space contains

One Nuvio profile uses one sync space. Two profiles use two spaces, two salts, and two bearer tokens. Records from one space are never applied to another.

A space carries three kinds of user data, each as its own record:

| Kind | What it is |
|---|---|
| `mark` | The user's own skip mark, including "none here". |
| `detection` | A time the analyzer found, or an explicit "not found" after the analyzer ran. |
| `submission` | One row of this profile's TheIntroDB submissions list, scoped by a hash of the account key. |
| `alias` | Two show ids that name the same title. |

These values stay on the device and have no field in this format: audio fingerprints, energy, loudness, thumbnail sheets, file names, the season checked/not-checked cache, `skip_source_ledger_v1`, temporary download ids, debrid keys, the TorBox key, addon URLs, Nuvio and Trakt tokens, the TheIntroDB key itself, watch progress, and watch history.

An inferred "not found" is a `detection` with `outcome: "not_found"`. It is never written as a `mark` with `presence: "none"`. Only a person sets "none here".

## 2. Identifiers

### Show id

A show id is one of:

- `tmdb:` + a decimal integer from 1 to 9999999999, no leading zero. Example: `tmdb:100`.
- `imdb:tt` + 1 to 10 decimal digits, at least one digit not zero. Leading zeros are allowed. Example: `imdb:tt0000100`.
- `raw:` + 1 to 128 characters from `A-Z a-z 0-9 . _ -`. For an id that is neither TMDB nor IMDb. v1 clients that only have TMDB and IMDb do not emit `raw:`.

`imdb:tt0000100` and `imdb:tt100` are spellings of the same IMDb title. The numeric value is the integer after `tt`. They merge with no alias record. TMDB ids are not zero-padded, so `tmdb:100` and `tmdb:0100` are not both valid; only `tmdb:100` is.

A TMDB id and an IMDb id of the same title are linked only by an `alias` record.

Today's on-disk keys, which this format replaces when the clients land:

- iOS analysis files are one JSON per episode, named `{show}__s{season}e{episode}` or `{show}__movie`, plus a `show-aliases` file. `show` is the first id that profile stored (`tmdb:…` or `tt…`).
- Desktop marks use `nuvio_skip_marks` keyed `id:season:episode`.

Writers translate those into the ids in this section. `outro` in the analysis file is `credits` on the wire. `intro`, `recap`, and `preview` keep their names.

### Episode

`season` and `episode` are either both JSON `null` (a movie) or both integers. `season` is 0 or greater (0 is a specials season). `episode` is 1 or greater. The episode key is `movie` or `s{season}e{episode}`.

### Segment type and index

`segmentType` is `intro`, `recap`, `credits`, or `preview`.

`segmentIndex` is an integer from 0 to 99. A type may have several ranges. Index 0 is the first. "None here" has no index.

### Device id

`deviceId` matches `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`. `.` and `..` are not device ids. The id is stable for that install and is what the UI shows as the source of a time ("From iPhone 16 Pro" is a local label for this id).

### Record ids

| Record | `id` |
|---|---|
| Range mark | `mark/` + a lowercase UUID v4 |
| None-here mark | `none/{showId}/{episodeKey}/{segmentType}` |
| Detection | `det/{showId}/{episodeKey}/{segmentType}/{segmentIndex}/{durationMs}/a{algorithmVersion}` |
| Submission | `submission/{accountScope}/{remoteId}` |
| Alias | `alias/{from}` |

UUID v4 is lowercase and matches `^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`. Space ids and blob ids use the same form.

`accountScope` is 64 lowercase hex characters: SHA-256 of the UTF-8 TheIntroDB account key. The key is never stored in a record, a file, or the Worker. This checkout fetches that list from the API and does not persist a hash under `own_theintrodb_submissions_v2`; v1 clients use this SHA-256 as the scope.

`remoteId` is the submission's id at TheIntroDB, 1 to 128 characters from `A-Z a-z 0-9 . _ -`.

`releaseId`, when present, is a show id for the cut (the release the analyzer recognized). It is omitted when unknown. It is never a file name.

## 3. Times

Every time and duration is an integer number of milliseconds, from 0 up to `Number.MAX_SAFE_INTEGER` (2^53 − 1). There are no floats.

`startMs` is the first millisecond of the range. `endMs` is the first millisecond after the range, so the length is `endMs - startMs` and `endMs > startMs`.

`durationMs` is the length of the video the mark or detection was made on. `0` means the cut length was unknown.

Two positive durations are the same cut when the absolute difference is at most `30000` milliseconds. This is the same tolerance as `sameCut` in the player. A `0` duration does not match a positive duration, except where a mark says so in section 6.

## 4. Hybrid logical clock

Every record has:

```json
"hlc": { "wallMs": 1700000000000, "counter": 0, "deviceId": "device-a" }
```

`wallMs` is Unix time in milliseconds. `counter` is an integer from 0 to 1000000. `deviceId` is the device that produced this version of the record.

Producers keep a per-device last clock `L`, starting at `{wallMs: 0, counter: 0}`.

- **tick(nowMs):** if `nowMs > L.wallMs`, set `L = {wallMs: nowMs, counter: 0}`. Otherwise set `L = {wallMs: L.wallMs, counter: L.counter + 1}`. The new record carries `L` plus this device id.
- **receive(remote):** if `remote.wallMs > L.wallMs`, or the wall times are equal and `remote.counter > L.counter`, set `L = {wallMs: remote.wallMs, counter: remote.counter}`.

Comparison, greater wins:

1. Larger `wallMs`.
2. Larger `counter`.
3. Larger `deviceId`, compared by UTF-16 code unit, the same order as Kotlin `String.compareTo` and JavaScript `>`.
4. If the clocks and the record id are equal, the record with the greater canonical JSON (section 8) wins. Equal canonical JSON is the same record.

## 5. Records

A record is one JSON object. Unknown keys are rejected. Unknown `kind` values are rejected. Key order on the wire inside a blob does not matter, because the plaintext that is encrypted is canonical JSON (section 8).

`deleted: false` is a live record. `deleted: true` is a tombstone. A tombstone has only `kind`, `id`, `deleted`, and `hlc`.

### Mark

Range:

```json
{
  "kind": "mark",
  "id": "mark/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa02",
  "deleted": false,
  "hlc": { "wallMs": 1000, "counter": 0, "deviceId": "device-a" },
  "showId": "tmdb:100",
  "season": 1,
  "episode": 8,
  "segmentType": "intro",
  "presence": "range",
  "segmentIndex": 0,
  "startMs": 30000,
  "endMs": 40000,
  "durationMs": 3600000
}
```

None here omits `segmentIndex`, `startMs`, and `endMs`:

```json
{
  "kind": "mark",
  "id": "none/tmdb:100/s1e8/intro",
  "deleted": false,
  "hlc": { "wallMs": 2000, "counter": 0, "deviceId": "device-b" },
  "showId": "tmdb:100",
  "season": 1,
  "episode": 8,
  "segmentType": "intro",
  "presence": "none",
  "durationMs": 0
}
```

`presence` is `range` or `none`. `durationMs` may be 0.

### Detection

`algorithm` matches `^nuvio-analysis-([1-9][0-9]{0,8})$`. The captured integer is `algorithmVersion`. Today's analyzer constant `MEDIA_ANALYSIS_VERSION` is `1`, so the string is `nuvio-analysis-1`. `derivedVersion` is not this field.

`outcome` is `found` or `not_found`. `found` includes `startMs` and `endMs`. `not_found` omits them. `releaseId` is omitted when unknown.

The id is computed from `showId`, the episode key, `segmentType`, `segmentIndex`, `durationMs`, and `algorithmVersion`. A writer uses that id and no other.

A detection is written when the analyzer ran for that slot. A slot that was never analysed has no record. `not_found` means it ran and found no range. Copying a user mark from another episode, when the app does that, is a `found` detection for the file it was applied to, and it stays a detection.

### Submission

```json
{
  "kind": "submission",
  "id": "submission/<64 hex>/<remoteId>",
  "deleted": false,
  "hlc": { "wallMs": 1000, "counter": 0, "deviceId": "device-a" },
  "accountScope": "<64 lowercase hex>",
  "remoteId": "remote-1",
  "showId": "tmdb:100",
  "mediaType": "tv",
  "season": 1,
  "episode": 8,
  "segmentType": "intro",
  "status": "approved",
  "startMs": 40000,
  "endMs": 90000,
  "durationMs": 3600000
}
```

`mediaType` is `tv` or `movie`. A movie has null season and episode. A TV row has both integers. `status` is 1 to 32 characters from `A-Z a-z 0-9 space . _ -`. `startMs >= 0`, `endMs >= startMs`, `durationMs >= 0`.

A submission never becomes a mark and never suppresses analysis. Two account scopes stay in separate lists even inside one space.

### Alias

```json
{
  "kind": "alias",
  "id": "alias/imdb:tt0000100",
  "deleted": false,
  "hlc": { "wallMs": 1000, "counter": 0, "deviceId": "device-a" },
  "from": "imdb:tt0000100",
  "to": "tmdb:100"
}
```

`from` and `to` are distinct show ids. The id is `alias/` + `from`. One live alias per source id; a newer clock can point it somewhere else. The link is undirected for merging.

## 6. Merge

Inputs are the decrypted records from every blob the device has, from the Worker and from the folder. Records that fail section 5 are counted as `rejected` and dropped. The rest are merged as follows. `nowMs` is the device's current Unix time in milliseconds.

### 6.1 One winner per kind and id

Group by `kind` + `id`. The greater record under section 4 wins. A newer tombstone deletes the live record. A newer live record resurrects it.

### 6.2 Show components

Normalize IMDb and TMDB spellings to a numeric key (`imdb:{integer}`, `tmdb:{integer}`). `raw:` keys are themselves.

Build an undirected graph. Every live record contributes its show id. A live alias connects `from` and `to`. Tombstoned aliases add no edge. Connected numeric keys form one title.

The canonical show id of a component is:

1. The smallest TMDB number, written `tmdb:{number}`, if any TMDB id is present.
2. Otherwise the smallest IMDb number. When several stored spellings share that number, the one with the most digits wins, then UTF-16 order.
3. Otherwise the UTF-16-smallest `raw:` id.

`aliases` is every stored spelling in the component, sorted by UTF-16, including the canonical id. Lookup uses exact spelling. An unseen zero-padding of an IMDb id does not match until some record has used that spelling.

Episode buckets are `(canonical show id, season, episode)`.

### 6.3 Marks

Per episode, per `segmentType`:

- The greatest `presence: "none"` record is the winning none. Older nones are suppressed with `suppressedBy: "newer-none"`.
- A range whose clock is less than the winning none is suppressed with `suppressedBy: "none"`.
- Every other range is applied.
- The winning none is applied when no range was applied. When a range was applied, the none is kept and marked `suppressedBy: "range"`.

Applied marks become effective segments. `durationMs: 0` on an applied range covers every file length, including an unknown length. A positive `durationMs` covers a file when both lengths are positive and they are the same cut (section 3).

An applied none blocks that segment type for the whole episode, for every file length. "None here" is the user's statement about the episode, not about one file.

### 6.4 Detections

Detections never overwrite an applied mark.

Group by `segmentType` + `segmentIndex`. Split `outcome: "found"` rows into cut clusters: sort by `durationMs`, and start a new cluster when the duration is more than 30000 ms above the cluster's smallest duration. `not_found` is not placed in a cluster.

Inside a cluster the winner is the greatest `algorithmVersion`, then the greater record under section 4. A different cut stays in its own cluster, so a synced detection never blocks analysis of a different cut.

A found detection is applied when it won its cluster and no applied mark suppresses it. A mark suppresses it when the mark is applied, the type matches, and either the mark is "none here", or the mark's index matches and (`mark.durationMs === 0` or the durations are the same cut). Other found rows in the cluster are `suppressedBy: "newer-detection"`. A `not_found` row is `suppressedBy: "mark"` when a mark suppresses it, and otherwise has `suppressedBy: null`. It is never applied and never appears in `effective`.

### 6.5 Whether to analyse

`shouldAnalyse` is false for a local file when any of these hold for that show spelling, season, episode, type, and index:

1. An applied none exists for that type.
2. An applied range mark for that index covers the file length (section 6.3).
3. An applied found detection for that index has `algorithmVersion >= localAlgorithm` and the same cut as the file.

Otherwise it is true, including when the only detection is `not_found`, when the synced detection is a different cut, and when the local analyzer is newer than the synced one. A missing show or episode is analysed.

### 6.6 Submissions

Submissions are listed apart from shows. Their `showId` is rewritten to the canonical id when an alias applies. Sort by `accountScope`, then `id`.

### 6.7 Tombstones

A tombstone is kept while `nowMs < hlc.wallMs + 7776000000`. `7776000000` is 90 × 24 × 60 × 60 × 1000, a fixed duration, not a calendar date. At that instant it may be dropped.

Devices keep the tombstone, and keep sending it, until that instant. After it is dropped they also drop any stored copy of the same kind and id whose clock is less than or equal to the tombstone. Dropping the tombstone while still sending the old live record brings the record back. A later edit, with a greater clock, may reuse the id.

Sort kept tombstones by `kind`, then `id`.

### 6.8 Output shape

The merged value is exactly:

```text
{
  rejected: <count of inputs that failed to parse>,
  shows: [ {
    aliases: [ "<spelling>", ... ],
    episodes: [ {
      detections: [ DetectionView, ... ],
      effective: [ EffectiveSegment, ... ],
      episode: <int or null>,
      episodeKey: "s1e8" | "movie",
      marks: [ MarkView, ... ],
      season: <int or null>,
      userNoneTypes: [ "intro" | "recap" | "credits" | "preview", ... ]
    } ],
    showId: "<canonical>"
  } ],
  submissions: [ SubmissionState, ... ],
  tombstones: [ { hlc, id, kind, purgeAfterMs } ]
}
```

`userNoneTypes` lists types that have an applied none, in the order intro, recap, credits, preview.

`effective` is sorted by type in that same order, then `segmentIndex` (`null` sorts as −1), then source (`detection` before `mark`), then `id`.

Mark views are sorted by `id`. Detection views are sorted by `id`. Episodes are sorted by `season * 1000000 + episode`, and a movie (null season) sorts first. Shows are sorted by `showId`.

View objects use the fields in `sync-worker/test/reference/merge.ts` (`MarkView`, `DetectionView`, `EffectiveSegment`, `SubmissionState`, `TombstoneState`). `suppressedBy: null` is present, not omitted. `algorithm` on an effective mark is JSON `null`.

The byte-for-byte oracle is `mergedCanonical` in each vector: canonical JSON of this value.

## 7. Crypto

### Why these primitives

The clients are Kotlin/Native on iOS, Kotlin/JVM on Desktop, and Kotlin on Android. The primitives have to exist on all three without a third-party crypto library.

| Piece | Choice | Reason |
|---|---|---|
| Password hashing | PBKDF2-HMAC-SHA256, 600000 iterations, 16-byte salt, 32-byte output | In CommonCrypto, `javax.crypto`, Android, and WebCrypto. OWASP's 2023 minimum for PBKDF2-HMAC-SHA256 is 600000. Argon2id resists GPUs better and is not in those libraries. |
| Key split | One HMAC-SHA256 block per output | WebCrypto HKDF always runs Extract, which would hash the master again and disagree with a raw HMAC on Apple and JVM. One block avoids that trap. |
| Encryption | AES-256-GCM, 12-byte nonce, 16-byte tag | In CommonCrypto, Java `AES/GCM/NoPadding`, Android, and WebCrypto. XChaCha20-Poly1305's longer nonce is safer against reuse and is not in those libraries. |
| Token hash | SHA-256 | The bearer token already has 256 bits from HMAC. A slow hash is not what makes it unguessable. |

The passphrase is the only secret a person types. Everything else is derived or random.

### Derivation

1. Normalize the passphrase to Unicode NFC. Encode that as UTF-8. Do not trim. Do not lowercase. Clients refuse a passphrase shorter than 8 characters before calling the KDF. The KDF itself accepts any string, so the vectors can use a fixed test passphrase.
2. `master = PBKDF2-HMAC-SHA256(passphrase, salt, 600000, 32 bytes)`. `salt` is 16 bytes from a CSPRNG, chosen once per space and then fixed.
3. `encKey = HMAC-SHA256(master, utf8("nuvio-sync-v1 enc") || 0x01)`.
4. `authToken = HMAC-SHA256(master, utf8("nuvio-sync-v1 auth") || 0x01)`.
5. `keyCheck = HMAC-SHA256(master, utf8("nuvio-sync-v1 check") || 0x01)[0:16]`.

`||` is concatenation. The `0x01` byte matches HKDF's one-block counter so a later reader can see why it is there. Do not run HKDF-Extract on `master`.

`encKey` encrypts. `authToken` is the bearer token. They are different 32-byte values. The Worker never receives `master`, `encKey`, the passphrase, or the plaintext.

`keyCheck` is stored on the server and in `header.json` so a wrong passphrase fails before any blob is decrypted. Compare it in constant time. Anyone who can read D1 or the folder can run the KDF offline against `salt` and `keyCheck`. The passphrase has to be one the owner can remember and a stranger cannot guess. The free-plan database is private to the Cloudflare account; the folder is as private as the drive it sits on.

### AEAD

Encrypt with AES-256-GCM, tag length 128.

- Key: `encKey`.
- Nonce: 12 bytes from a CSPRNG, a new value for every encryption under that `encKey`. Reusing a nonce with two plaintexts breaks confidentiality of both. The Worker does not detect reuse. A 96-bit random nonce stays far from the birthday bound for the number of blobs a profile will write.
- Plaintext: UTF-8 canonical JSON of exactly one record, at most 8192 bytes, no trailing newline.
- Associated data: UTF-8 of `nuvio-sync/1\n{spaceId}\n{blobId}\n{deviceId}\n` with the newlines shown. `{deviceId}` is the uploading device, the same id stored on the blob. The record's `hlc.deviceId` may differ.
- Ciphertext on the wire: the AES-GCM output used by WebCrypto and by Java `Cipher.doFinal`, which is `ciphertext || tag` (tag last, 16 bytes). One base64 string. There is no separate tag field.

Decrypt with the same key, nonce, and associated data. If authentication fails, skip that blob. Do not merge an empty record in its place. Do not log the plaintext or the failure body.

The test vector's nonce is `00 01 …` repeated as specified in the file. It is a test value. A real device does not use it.

### Join code

A second device needs the space id, the salt, and the passphrase. The salt is not secret, and it is required before the bearer token can be derived, so it travels out of band:

```text
nuvio-sync-v1:{spaceId}:{salt base64url}:{keyCheck base64url}
```

Base64url here has no padding. The passphrase is typed, not put in the code. The device derives the keys, compares `keyCheck` to the code, and only then calls the Worker. A mismatch is a wrong passphrase. The device stores `spaceId`, `salt`, and `keyCheck` locally and the passphrase in the platform keystore.

## 8. Canonical JSON

Used for plaintext before encryption and for `mergedCanonical`.

- `null`, `true`, `false` as those spellings.
- Integers only, written in decimal with no sign on zero, no exponent, no leading zeros.
- Strings as JSON strings (quotes and the usual escapes), UTF-8.
- Arrays keep their order.
- Objects: keys sorted by UTF-16 code unit, no spaces, no trailing commas.

## 9. Blob envelope

The same object is a Worker batch item and an entry in a folder file.

```json
{
  "id": "22222222-2222-4222-8222-222222222222",
  "nonce": "<standard base64 of 12 bytes>",
  "ciphertext": "<standard base64 of ciphertext||tag>"
}
```

Standard base64 is RFC 4648 with `+` `/` and `=` padding. Non-canonical padding and extra characters are rejected. The decoded ciphertext is 16 to 24576 bytes.

## 10. Worker protocol

Base URL is the deployed Worker. JSON requests use `Content-Type: application/json`. A `charset` parameter is allowed. Responses are `application/json; charset=utf-8` plus `cache-control: no-store`. There is no CORS header. `OPTIONS` is a 404. Browsers are not a client.

Errors are `{ "error": "<code>", "message": "<short text>" }`. Codes: `bad_request` (400), `unauthorized` (401), `not_found` (404), `method_not_allowed` (405), `conflict` (409), `payload_too_large` (413), `rate_limited` (429), `internal` (500). 429 also sends `retry-after` in seconds.

### Auth and the allowlist

Protected requests send `Authorization: Bearer <token>` where `<token>` is `authToken` in base64url with no padding (43 characters). The server hashes it with SHA-256 and compares that digest, in constant time, to every well-formed hash on the allowlist. The allowlist is the Worker secret `ALLOWLIST_TOKEN_HASHES` (hex digests separated by commas or whitespace) plus rows in the D1 `allowlist` table. A hash in either place is enough. The table read takes the first 32 rows in hex order. The server stores and compares hashes only.

A bearer token that is missing, the wrong length, or not on the allowlist gets 401 before any rate-limit write. A token that is on the allowlist and belongs to a different space also gets 401, and the response does not include the salt or key check.

The owner adds a space's token hash to the allowlist before the first request from that space. `sync-worker/test/reference/derive-stdin.ts` prints the hash from a passphrase and salt read on stdin. It does not print the passphrase or the token.

### Limits

| Limit | Value |
|---|---|
| Request body | 262144 bytes. A larger `Content-Length`, or a stream that passes the cap, is 413. The rest of the stream is discarded. |
| Blobs per batch | 1 to 50 |
| Decoded ciphertext | 16 to 24576 bytes |
| Pull page | default 100, maximum 100 |
| Rate | 60 requests per space per 60000 ms |

The rate window is a fixed window aligned to the Unix epoch: `start = nowMs - (nowMs % 60000)`. The request that crosses the limit is counted and returns 429. `retry-after` is the whole seconds until the next window, at least 1. Create is limited on the new space id.

### `GET /health`

No auth. Response `200` `{ "ok": true }`. It does not touch D1.

### `POST /v1/spaces`

Auth required. Body, no extra keys:

```json
{
  "spaceId": "<uuid v4>",
  "salt": "<standard base64 of 16 bytes>",
  "keyCheck": "<standard base64 of 16 bytes>",
  "kdf": "pbkdf2-sha256",
  "iterations": 600000
}
```

`iterations` has to be the number 600000. The only `kdf` is `pbkdf2-sha256`.

- New space: `201` and the header below. A counter row starts at seq 0.
- Same space, same token, same salt, key check, kdf, and iterations: `200` and the same header.
- Same space, same token, different header: `409`.
- Unknown space id in this call is create, not join.

```json
{
  "v": 1,
  "spaceId": "<uuid>",
  "kdf": "pbkdf2-sha256",
  "iterations": 600000,
  "salt": "<base64>",
  "keyCheck": "<base64>"
}
```

### `POST /v1/spaces/{spaceId}/join`

Auth required. The body is empty. A `{}` body is 400.

- Space and token match: `200` and the header object above.
- No such space: `404`.
- Token does not match the space: `401`.

### `POST /v1/spaces/{spaceId}/batches`

Auth required. Body, no extra keys:

```json
{
  "deviceId": "device-a",
  "blobs": [ { "id": "<uuid v4>", "nonce": "<base64>", "ciphertext": "<base64>" } ]
}
```

Blob ids are unique inside the batch. No extra keys on a blob.

Response:

```json
{
  "v": 1,
  "highWater": 2,
  "results": [
    { "id": "<uuid>", "seq": 1, "status": "stored" },
    { "id": "<uuid>", "seq": 2, "status": "duplicate" }
  ]
}
```

`results` follows the request order. `stored` is a new row. `duplicate` is the same id and the same ciphertext; the original seq is kept and the counter does not move. The same id with a different ciphertext rejects the whole batch with `409` and writes none of that batch. A unique-constraint race also returns `409` with message `Retry the batch`; the client sends the same batch again.

`highWater` is the space counter after the batch. It is not a pull cursor. A client that sets its pull cursor from `highWater` can skip another device's blobs. Pull cursors move only in pull responses.

Seq numbers start at 1 and increase by 1 for each newly stored blob, in request order.

### `GET /v1/spaces/{spaceId}/blobs?cursor=0&limit=100`

Auth required. `cursor` defaults to `0`. It is an integer from 0 to `2^53 - 1` with no leading zero (except `0` itself). The page is rows with `seq > cursor`, ordered by `seq`, up to `limit`.

```json
{
  "v": 1,
  "blobs": [
    {
      "seq": 1,
      "id": "<uuid>",
      "deviceId": "device-a",
      "nonce": "<base64>",
      "ciphertext": "<base64>",
      "receivedAtMs": 1700000000000
    }
  ],
  "cursor": 1,
  "hasMore": false
}
```

`receivedAtMs` is server time. Merge ignores it. The returned `cursor` is the last `seq` on the page, or the request cursor when the page is empty. `hasMore` is true when at least one more row exists. Clients loop while `hasMore` is true.

`deviceId` on the row is the uploader. Associated data uses this id, the row's `id`, and the space id.

## 11. Folder layout (transport e)

```text
header.json
devices/<deviceId>.json
devices/<deviceId>/<16-digit seq>.json
```

`header.json` is the join header from section 10, UTF-8, unencrypted. Salt and key check are in it. The passphrase is not.

A segment file:

```json
{
  "v": 1,
  "spaceId": "<uuid>",
  "deviceId": "<id>",
  "seq": 1,
  "blobs": [ ]
}
```

`seq` equals the filename's integer. The filename is the seq padded to 16 digits. A device appends a new file; it does not rewrite an old one.

A snapshot file `devices/<deviceId>.json`:

```json
{
  "v": 1,
  "spaceId": "<uuid>",
  "deviceId": "<id>",
  "throughSeq": 12,
  "blobs": [ ]
}
```

A reader loads `header.json`, then for each device takes the snapshot's blobs (if the file exists) and the segment files whose seq is greater than `throughSeq`. With no snapshot, every segment counts. Files that do not match the three patterns are ignored (for example `.DS_Store`). A matching file with the wrong shape, the wrong space id, or a seq that disagrees with its name fails the read. A device writes only its own snapshot and its own segment directory.

Blob bytes are the section 9 envelope. Decrypt, then merge under section 6. Order across files does not change the merge.

## 12. Vectors

`docs/sync/test-vectors/v1/` is generated by `sync-worker/test/reference/generate-vectors.ts`. Clients match `ciphertextBase64`, `mergedCanonical`, and the published key bytes.

| File | Case |
|---|---|
| `crypto.json` | Fixed passphrase, salt, and nonce. Master, enc key, auth token, key check, ciphertext. |
| `passphrase-nfc.json` | NFD and NFC derive the same master. |
| `mark-beats-detection.json` | A mark wins over a newer detection. A second index and another type stay. A different cut is analysed. A newer local algorithm is analysed. |
| `two-cuts.json` | Two durations of one slot both survive. A higher algorithm wins inside a cut even with an older clock. 30000 ms matches; 30001 ms does not. |
| `tombstone.json` | A newer tombstone removes the mark. It is kept at the delete time and gone at `wallMs + 7776000000`. |
| `clock-tie.json` | Equal wall and counter. The greater device id wins (`device-b`). |
| `alias-merge.json` | IMDb spellings and a TMDB alias become one show. A tombstoned alias splits TMDB from IMDb. Zero-padding still groups the IMDb spellings. |
| `not-found-vs-none.json` | `not_found` leaves `userNoneTypes` empty and analysis on. A none-here mark turns analysis off and does not rewrite the detection into a mark. |
| `submission-scope.json` | Two fake account keys stay in two scopes. The strings are fakes, not API keys. |
| `zero-duration-mark.json` | `durationMs: 0` on a range covers every file length of that index only. |
| `rejected.json` | Two malformed objects increment `rejected` and add no fields. |

The passphrase in the crypto vectors is `test-passphrase-not-a-secret`. It is a test fixture.

## 13. Client duties

- One space per profile. Persist `spaceId`, salt, key check, device id, and the pull cursor. Persist the passphrase in the platform keystore.
- Generate the salt once. Generate a new nonce per blob.
- Encrypt canonical JSON. Build associated data from the space, the blob id, and the uploading device id.
- Pull before merging. Advance the pull cursor only from pull responses.
- Keep tombstones until `purgeAfterMs`.
- Skip blobs that fail authentication.
- Treat `highWater`, `receivedAtMs`, and `seq` as transport. Conflict rules use the record clock.
- Show a synced time as coming from `hlc.deviceId`. Apply it only when section 6 says it is applied and the cut matches.
- Leave fingerprints and the other out-of-scope values off the device that created them.
