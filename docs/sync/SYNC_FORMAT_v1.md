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

A writer normalizes an IMDb id before storing it in a record and before placing it in a record id. The normalized spelling is `imdb:tt` plus the integer in decimal, left-padded with `0` to at least 7 digits. `tt100`, `imdb:tt100`, `imdb_tt100`, and `tt0000100` are all written `imdb:tt0000100`. An integer that already has more than 7 digits keeps every digit (`tt12345678` is written `imdb:tt12345678`). Extra leading zeros beyond that padding are not written. Readers still accept every spelling of 1 to 10 digits above, and those spellings still merge with no alias record. `{showId}` in a none id, a detection id, and an alias id (`{from}`) is this normalized spelling when the writer created the record.

A disk show id `tt…`, `imdb:tt…`, or `imdb_tt…` is that same IMDb id and is normalized the same way.

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

`accountScope` is 64 lowercase hex characters: SHA-256 of the trimmed UTF-8 TheIntroDB account key. Trim leading and trailing whitespace with `String.prototype.trim` (Unicode `White_Space` and line terminators), then hash the UTF-8 bytes of what remains. A key that is empty after trimming has no scope and is not written. The key is never stored in a record, a file, or the Worker. This checkout fetches that list from the API and does not persist a hash under `own_theintrodb_submissions_v2`; v1 clients use this SHA-256 as the scope.

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

- **tick(nowMs):** if `nowMs > L.wallMs`, or `L.wallMs > nowMs + 86400000`, set `L = {wallMs: nowMs, counter: 0}`. The second case is a saved clock more than 24 hours ahead of the device's current clock; keeping it would keep emitting records other devices reject. Otherwise set `L = {wallMs: L.wallMs, counter: L.counter + 1}`. If that counter would be greater than 1000000, the tick fails and the device waits until `nowMs` moves. The new record carries `L` plus this device id.
- **receive(remote, nowMs):** if `remote.wallMs > nowMs + 86400000`, leave `L` unchanged. Otherwise, if `remote.wallMs > L.wallMs`, or the wall times are equal and `remote.counter > L.counter`, set `L = {wallMs: remote.wallMs, counter: remote.counter}`.

`86400000` is 24 × 60 × 60 × 1000. A record with `wallMs` greater than `nowMs + 86400000` is rejected by merge and does not delete, pin a tombstone, or move another device's clock. A record inside that window is eligible. The reference functions are `tick` and `receive` in `sync-worker/test/reference/clock.ts`.

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
2. Otherwise the smallest IMDb number. When several stored spellings share that number, the one with the most digits wins, then UTF-16 order. Writers of new records store the normalized IMDb spelling from section 2; merge still groups every accepted spelling by numeric value.
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

A found detection is applied when it won its cluster and no applied mark suppresses it. A mark suppresses it when the mark is applied, the type matches, and either the mark is "none here", or the mark's index matches and (`mark.durationMs === 0` or the durations are the same cut). Other found rows in the cluster are `suppressedBy: "newer-detection"`. A `not_found` row is `suppressedBy: "mark"` when a mark suppresses it, and otherwise has `suppressedBy: null`. It is never applied, never appears in `effective`, and never becomes a mark of either presence. A slot with no record is the same as a slot that was never analysed: it is not `presence: "none"`, and it is not a tombstone. Only a mark a person saved with `presence: "none"` sets `userNoneTypes`.

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

A device that last synced successfully at least `7776000000` ms ago has missed tombstones that other devices are allowed to drop. It pulls from cursor 0 and adopts the remote merge. It does not upload a local record whose `hlc.wallMs` is less than or equal to `nowMs - 7776000000`. Uploading that copy would recreate a delete whose tombstone has already aged out. A local record newer than that cutoff may be uploaded after the pull; a tombstone that could still delete it would still be in the pull. A device that has never completed a sync may upload its local records. That is how the first phone fills a new space. The reference is `syncMode` and `recordsToUpload` in `sync-worker/test/reference/sync-plan.ts`.

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

The passphrase is the only secret a person types on each device. The enroll secret in section 10 is a separate random Worker secret. It is not derived from the passphrase. Everything else is derived or random.

600000 iterations is the OWASP 2023 minimum for PBKDF2-HMAC-SHA256. This format does not lower it. The KDF runs on the device, not on the Worker. Workers Free allows 10 ms of CPU time per request, which this iteration count is not aimed at. No phone timing was measured for this change. A later client may report a measured duration on a real device; a number from a desktop or a CI runner is not that measurement. `keyCheck` is 16 bytes so a wrong passphrase fails closed. Truncation does not reduce the cost of a guess: the guesser still runs the full KDF, then compares 128 bits. A false match at that width is not a practical outcome. Anyone who can read D1 or the folder can run that offline attack. The passphrase has to be one the owner can remember and a stranger cannot guess.

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
- Nonce: 12 bytes from a CSPRNG (`crypto.getRandomValues` or the platform equivalent), a new value for every encryption under that `encKey`. A counter is not a nonce. Reusing a nonce with two plaintexts breaks confidentiality of both. The Worker rejects a batch that repeats a nonce inside the batch or reuses a nonce already stored in the space, including when the blob id is new. A retry of the same blob id and the same ciphertext is a duplicate and is not a reuse. A 96-bit random nonce stays far from the birthday bound for the number of blobs a profile will write. The reference generator is `randomNonce` in `sync-worker/test/reference/crypto.ts`. The test vector's nonce is a fixed value and is not what a device emits.
- Plaintext: UTF-8 canonical JSON of exactly one record, at most 8192 bytes, no trailing newline.
- Associated data: UTF-8 of `nuvio-sync/{formatVersion}\n{spaceId}\n{blobId}\n{deviceId}\n{kind}\n` with the newlines shown. `formatVersion` is `1`. `{deviceId}` is the uploading device. `{kind}` is the record kind on the envelope. The client builds this from the routing fields as received, not from a locally corrected guess. After a successful decrypt, the plaintext's `kind` must equal the envelope kind; otherwise the blob is skipped. `seq` and `receivedAtMs` are assigned by the server after encryption, so they are not in the associated data and they do not change the plaintext. The record's `hlc.deviceId` may differ from the uploading device.
- Ciphertext on the wire: the AES-GCM output used by WebCrypto and by Java `Cipher.doFinal`, which is `ciphertext || tag` (tag last, 16 bytes). One base64 string. There is no separate tag field.

Decrypt with the same key, nonce, and associated data. If authentication fails, skip that blob. Do not merge an empty record in its place. Do not log the plaintext or the failure body.

`encKey`, `authToken`, and `keyCheck` are three different HMAC outputs. Decrypting with `authToken` fails. The server never receives `encKey`.

A different ciphertext for an existing blob id is rejected and is not stored. That blocks replacing a blob with an older ciphertext. An older record inside a new blob loses the merge in section 4 to the greater clock, so replaying the plaintext under a new blob id does not roll the record back. A server that deletes rows can still hide them from a device that pulls from cursor 0. Devices that already applied a record keep their local copy until a full resync adopts the remote set, which is why section 6.7 forbids uploading stale local records after a long gap.

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
  "v": 1,
  "kind": "mark",
  "nonce": "<standard base64 of 12 bytes>",
  "ciphertext": "<standard base64 of ciphertext||tag>"
}
```

`v` is the number 1. `kind` is `mark`, `detection`, `submission`, or `alias`, and it is the kind inside the plaintext. Both are routing fields covered by the associated data.

Standard base64 is RFC 4648 with `+` `/` and `=` padding. Non-canonical padding and extra characters are rejected. The decoded ciphertext is 16 to 24576 bytes.

## 10. Worker protocol

Base URL is the deployed Worker. JSON requests use `Content-Type: application/json`. A `charset` parameter is allowed. Responses are `application/json; charset=utf-8` plus `cache-control: no-store`. There is no CORS header. `OPTIONS` is a 404. Browsers are not a client.

Errors are `{ "error": "<code>", "message": "<short text>" }`. Codes: `bad_request` (400), `unauthorized` (401), `not_found` (404), `method_not_allowed` (405), `conflict` (409), `quota_exceeded` (409), `payload_too_large` (413), `rate_limited` (429), `internal` (500). 429 also sends `retry-after` in seconds.

### Auth

Join, batch, and pull send `Authorization: Bearer <token>` where `<token>` is `authToken` in base64url with no padding (43 characters). The server hashes it with SHA-256 and compares that digest, in constant time, to the hash stored on that space. A missing or malformed bearer is 401 and does not touch D1. A well-formed bearer for a missing space is 404. A well-formed bearer that does not match the space is 401. Neither of those writes a rate-limit row, and neither response includes the salt or the key check. A matching token on a revoked space is 401 with message `Revoked`, still without the salt.

`POST /v1/spaces`, `GET /v1/spaces`, and `DELETE /v1/spaces/{spaceId}` instead require the header `X-Nuvio-Enroll`. The value is the Worker secret `ENROLL_SECRET`, a random string of at least 16 characters from a CSPRNG. The Worker hashes the header and the secret with SHA-256 and compares the digests with `timingSafeEqual`. A missing secret, a secret shorter than 16 characters, or a mismatch is 401 and does not read or write D1. The enroll secret is not the passphrase and is not the bearer token. The deployer generates it without the passphrase. The first device sends it once, together with the bearer token, to create the space. Other devices never receive it. Create also requires the bearer token so the server stores the hash of the token the device will actually send later.

`GET /v1/spaces` with the enroll header lists space ids, creation time, revoke time, and the blob counter. It does not return the salt, the key check, or the token hash. `DELETE /v1/spaces/{spaceId}` with the enroll header revokes that space. Revoke is idempotent. It does not delete blob rows, because deleting them would spend the daily write budget. A revoked space rejects join, push, and pull. Creating the same id again is 409. Access stops; the bytes remain until the owner deletes the database.

### Limits

| Limit | Value |
|---|---|
| Request body | 262144 bytes. A larger `Content-Length`, or a stream that passes the cap, is 413. The rest of the stream is discarded. |
| Blobs per batch | 1 to 50 |
| Decoded ciphertext | 16 to 24576 bytes |
| Pull page | default 100, maximum 100. `limit` outside 1 to 100 is 400. The query reads at most `limit + 1` rows. |
| Blobs stored per space | 20000. A batch that would pass that count is 409 `quota_exceeded` and writes nothing. A duplicate of an existing blob is still accepted at the cap. |
| Rate | 60 authenticated requests per space per 600000 ms |

The rate window is a fixed window aligned to the Unix epoch: `start = nowMs - (nowMs % 600000)`. The request that crosses the limit is counted and returns 429. `retry-after` is the whole seconds until the next window, at least 1. Create is limited on the new space id. The rate-limit write happens only after the enroll secret matches, or after the bearer matches that space. A stranger does not get a D1 write.

60 requests in each 600000 ms window is at most `60 * (86400000 / 600000) = 8640` rate-limit row updates in a day if the space is saturated for the whole day. That ceiling is arithmetic from these two settings and from a 86400000 ms day. It is not a measured usage figure. It stays under the 100,000 D1 rows-written daily allowance on the Workers Free plan so the bookkeeping itself cannot spend the day. Normal syncing is far below the ceiling. The allowance and the 5,000,000 rows-read daily allowance and the 5 GB storage allowance are the published Workers Free D1 limits, not a measurement of this Worker.

### `GET /health`

No auth. Response `200` `{ "ok": true }`. It does not touch D1. It does spend a Worker request. The Workers Free plan allows 100,000 requests a day. A route in fail-open mode bypasses the Worker after that limit, so this Worker stays on `workers.dev` or on a fail-closed route.

### `GET /v1/spaces`

Enroll header required. No bearer. No body. Response `200`:

```json
{ "v": 1, "spaces": [ { "spaceId": "<uuid>", "createdAtMs": 0, "revokedAtMs": null, "blobs": 0 } ] }
```

At most 100 spaces, oldest first. `blobs` is the space counter. The salt and the key check are not included.

### `POST /v1/spaces`

Enroll header and bearer token are both required. Body, no extra keys:

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
- Revoked space: `409` with message `Space revoked`. The salt is not returned.
- Unknown space id in this call is create, not join.

### `DELETE /v1/spaces/{spaceId}`

Enroll header required. No bearer. A missing space is `404`. An active space becomes revoked and the response is `200` `{ "v": 1, "spaceId", "revoked": true }`. Repeating the call returns the same object. Blob rows are left in place.

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

Bearer required. The enroll header is not accepted in its place. The body is empty. A `{}` body is 400.

- Space and token match: `200` and the header object above.
- No such space: `404`.
- Token does not match the space: `401`.

### `POST /v1/spaces/{spaceId}/batches`

Bearer required. Body, no extra keys:

```json
{
  "deviceId": "device-a",
  "blobs": [ { "id": "<uuid v4>", "v": 1, "kind": "mark", "nonce": "<base64>", "ciphertext": "<base64>" } ]
}
```

Blob ids are unique inside the batch. No extra keys on a blob. `v` has to be the number 1. `kind` has to be one of the four record kinds.

Response:

```json
{
  "v": 1,
  "results": [
    { "id": "<uuid>", "seq": 1, "status": "stored" },
    { "id": "<uuid>", "seq": 2, "status": "duplicate" }
  ]
}
```

`results` follows the request order. `stored` is a new row. `duplicate` is the same id and the same ciphertext; the original seq is kept and the counter does not move. The same id with a different ciphertext rejects the whole batch with `409` and writes none of that batch. A repeated nonce does the same with message `Nonce already used`. A unique-constraint race also returns `409` with message `Retry the batch`; the client sends the same batch again.

The response has no cursor and no high-water field. `seq` is the server-assigned sequence of that blob. It is not a pull cursor. Pull cursors move only in pull responses, to the last `seq` on that page. A client that sets its pull cursor from its own push `seq` skips another device's blobs that were stored under a lower `seq` the client has not pulled.

Seq numbers start at 1 and increase by 1 for each newly stored blob, in the order the batches commit. Two devices interleaving pushes receive distinct increasing seqs. A later pull with the cursor from the previous pull returns every seq the device has not yet seen.

### `GET /v1/spaces/{spaceId}/blobs?cursor=0&limit=100`

Bearer required. `cursor` defaults to `0`. It is an integer from 0 to `2^53 - 1` with no leading zero (except `0` itself). The page is rows with `seq > cursor`, ordered by `seq`, up to `limit`.

```json
{
  "v": 1,
  "blobs": [
    {
      "seq": 1,
      "id": "<uuid>",
      "v": 1,
      "kind": "mark",
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

`deviceId` on the row is the uploader. Associated data uses the format version, the space id, the row's `id`, this device id, and the row's `kind`. The reference `advancePullCursor` accepts only a pull page: the new cursor is the last `seq` in `blobs`, or the previous cursor when `blobs` is empty.

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
| `clock-json-tie.json` | Equal wall, counter, and device id. The greater canonical JSON wins. |
| `clock-skew.json` | A tombstone more than 24 hours ahead of `nowMs` is rejected and does not delete the mark. |
| `offline-resync.json` | After a gap of at least 90 days the stale mark is not uploaded. A newer local mark still is. |
| `alias-merge.json` | IMDb spellings and a TMDB alias become one show. A tombstoned alias splits TMDB from IMDb. Zero-padding still groups the IMDb spellings. |
| `not-found-vs-none.json` | `not_found` leaves `userNoneTypes` empty and analysis on. A none-here mark turns analysis off and does not rewrite the detection into a mark. |
| `submission-scope.json` | Two fake account keys stay in two scopes. The strings are fakes, not API keys. |
| `zero-duration-mark.json` | `durationMs: 0` on a range covers every file length of that index only. |
| `rejected.json` | Two malformed objects increment `rejected` and add no fields. |

The passphrase in the crypto vectors is `test-passphrase-not-a-secret`. It is a test fixture.

## 13. Client duties

- One space per profile. Persist `spaceId`, salt, key check, device id, the pull cursor, and the time of the last successful sync. Persist the passphrase in the platform keystore. The enroll secret is typed once on the first device and is not stored as the passphrase.
- Generate the salt once. Generate each nonce with `randomNonce`. Do not use a counter.
- Encrypt canonical JSON. Build associated data from the format version, the space, the blob id, the uploading device id, and the record kind.
- Pull before merging. Advance the pull cursor only through `advancePullCursor` on a pull response. Do not advance it from a push `seq`.
- Keep tombstones until `purgeAfterMs`. After a gap of at least `7776000000` ms, full-resync as section 6.7 describes and do not upload the stale local store.
- Skip blobs that fail authentication, including a kind that does not match the plaintext.
- Treat `receivedAtMs` and `seq` as transport. Conflict rules use the record clock. There is no push high-water field.
- Show a synced time as coming from `hlc.deviceId`. Apply it only when section 6 says it is applied and the cut matches.
- Leave fingerprints and the other out-of-scope values off the device that created them.
