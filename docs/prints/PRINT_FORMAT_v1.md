# Nuvio show print format v1

Normative spec for a compact audio print learned from one of your own marks. Version 1. A reader that sees any other version stops.

This file is the on-device catalog and the bytes inside a sync record. The sync envelope, clock, and ciphertext are in `docs/sync/SYNC_FORMAT_v1.md`. A print record there has `kind` `print` and `algo` `nuvio-print-1`.

Another app can read a print without this repository. It does not need a download, a fingerprint of the whole episode, or a TheIntroDB account.

## What a print is

A print is the sub-fingerprints of one marked intro, recap, credits, or preview, plus 5 seconds on either side when that audio was captured. It is stored for the show. Later episodes of that show are matched against it.

It is not the full-episode head or tail fingerprint. Those stay on the device that decoded the file. It is not submitted to TheIntroDB. A published time is not one of your marks, so it does not become a print.

The local analysis name `outro` is `credits` on the wire. `intro`, `recap`, and `preview` keep their names.

## Version

`v` is the number 1. `algo` is the string `nuvio-print-1`.

| Field | Value |
|---|---|
| Step | 100 ms per frame |
| Frame | 32-bit sub-fingerprint, little-endian |
| Pad | 5000 ms, clipped to the captured window |
| Frames in one sync part | 1 to 1200 |
| Plaintext of one sync record | at most 8192 bytes |

A longer window is split into parts. Part numbers start at 0. `parts` is 1 to 99. Every part of one mark repeats the same `startMs`, `endMs`, `originMs`, `durationMs`, `stepMs`, and `padMs`. Only `part` and `frames` change. Joining parts in order restores the print.

## Frame bytes

`frames` is standard base64 (RFC 4648, `+` `/` and `=` padding) of the raw bytes. Each frame is a uint32 in little-endian order. The byte length divides by 4. Non-canonical padding is rejected.

Frames 1, 2, 3, 4 encode as:

```text
AQAAAAIAAAADAAAABAAAAA==
```

That is sixteen bytes: `01 00 00 00  02 00 00 00  03 00 00 00  04 00 00 00`.

## Times

Times are milliseconds from the start of the file.

| Field | Meaning |
|---|---|
| `startMs`, `endMs` | The mark itself. The pad is not included. `endMs` is greater than `startMs`. |
| `originMs` | Absolute time of frame 0 of this print. |
| `durationMs` | Length of the file the mark was made on. 0 means unknown and matches any length. |
| `padMs` | How far the stored audio extends past the mark on each side, before clipping. 0 to 10000. This version writes 5000. |

Frame `i` is the audio at `originMs + i * stepMs`.

Intro and recap are taken from the head window (the first 25 minutes). Credits and preview are taken from the tail window (the last 15 minutes). A mark that is not inside the captured window is not stored.

A mark shorter than 8 seconds is not stored. A stored mark is offered on another episode only when the matched overlap covers the whole mark, which is at least 8 seconds. A shared bumper that covers only part of the mark is not a match. Silence, or any mark whose frames are all within 6 bits of each other, is not stored as a match: that audio is not an intro, recap, credits, or preview.

A placed range that runs past the episode, or past the captured head or tail window, is not offered. It is not shortened to fit. Credits that end within 10 seconds of the file end, and still inside the file, snap to the file end.

## Bounds

These match TheIntroDB. A range outside them is not offered.

| Segment | Minimum | Maximum |
|---|---|---|
| Intro | 5 s | 300 s |
| Recap | 5 s | 1200 s |
| Credits | 5 s | 1800 s |
| Preview | 5 s | 1800 s |

Credits that end within 10 seconds of the file end snap to the file end.

## Identity

Local print id, used in the analysis file: `{episodeKey}/{segmentType}/{markId}` with the local type (`outro` for credits). `episodeKey` is `movie` or `s{season}e{episode}`. Two episodes do not share a print id.

Sync record id:

```text
print/{showId}/{episodeKey}/{segmentType}/{markId}/{part}
```

`showId` is `tmdb:` plus a decimal integer with no leading zero, `imdb:tt` plus at least 7 digits (pad with zeros), or `raw:` plus 1 to 128 characters from `A-Z a-z 0-9 . _ -`. An IMDb id is padded to at least 7 digits, so `tt100` is `imdb:tt0000100`.

`markId` is the segment type, or the type plus `-` and an index from 2 to 99. `intro-2` is the second intro mark. The mark id starts with the segment type.

Show ids contain `:`, not `/`, so the id splits into six pieces on `/`.

One show keeps at most 16 prints of each segment type. The newest season and episode are kept. Dropping a print removes the detections that came from it. It does not remove the mark.

A movie uses JSON `null` for both `season` and `episode`. A TV episode uses both integers. `season` is 0 or greater. `episode` is 1 or greater.

## On-device catalog

One JSON file per show, named `show-prints-` plus the sanitized show key. The sanitized key replaces every character that is not a letter, digit, `.`, `_`, or `-` with `_`. `tmdb:100` is `show-prints-tmdb_100`. The name contains no `__`, so it is not an episode file.

```json
{
  "version": 1,
  "prints": [
    {
      "showId": "tmdb:100",
      "season": 1,
      "episode": 1,
      "segmentType": "intro",
      "markId": "intro",
      "startMs": 20000,
      "endMs": 30000,
      "originMs": 15000,
      "durationMs": 1800000,
      "stepMs": 100,
      "padMs": 5000,
      "frames": "<standard base64 of the whole print, not split>",
      "sourceKey": "tmdb_100__s1e1"
    }
  ]
}
```

`version` other than 1 is ignored. `frames` here is the whole print. Splitting into 1200-frame parts happens only when building a sync record. Unknown keys on a sync record are rejected. The catalog reader ignores unknown keys so a newer writer does not wipe an older phone's file.

## Matching

Compare `frames` with the other episode's head (intro, recap) or tail (credits, preview) at the same step. Keep a hit only when:

- the print did not come from that episode
- the file lengths are both unknown, or they differ by at most 30 seconds
- the episode has no mark, no "none here", and no chapter for that type
- the episode has no analysis time for that type, unless that time was written by a print
- the mark's frames are not all within 6 bits of each other
- the overlap covers the whole mark
- the placed range fits in the episode and in the captured window
- the placed range is inside the bounds above
- the range is not one the user already moved off

A print does not replace a user's mark, a TheIntroDB submission stored as that user's mark, a chapter, or a scored analysis time (an intro or credits range that still has its confidence). A recap or preview that analysis already stored is kept as well. Intro and credits with no confidence score are mark-derived, so a print may record that detection and a deleted mark takes it away. When a scored analysis time is kept, the episode does not say "Detected from your marks" for that type. A print time is not offered for submission.

At most 3 hits of one type are kept on an episode. Hits whose starts are within 2 seconds are one hit. The longer overlap wins, then the lower average bit error, then a print from the same season.

The download row says "Detected from your marks" when an episode has any of these hits. Nothing is sent to TheIntroDB.

## What this version does not do

- It does not guess a segment on a show you have never marked. That seam exists and is off.
- It does not upload by itself. A client that already speaks sync format v1 encrypts these records with the same passphrase, associated data, and blob envelope. This branch has the codec and the Worker kind. The phone upload loop is not on this branch.
- Deleting a mark drops the local print and the detections that came from it. It does not match the show again. A sync client sends a tombstone with the same id and a greater clock. The tombstone has only `kind`, `id`, `deleted`, and `hlc`.
