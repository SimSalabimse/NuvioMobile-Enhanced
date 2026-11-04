# On-device analysis v2: why a cold open hides the intro, and why recap and preview never arrived

This note is the code-backed root cause for two owner reports: a 2019 show with a cold open and a theme that does not sit at a fixed offset (Evil, imdb `tt8550800`) does not get an intro, and a recap or a next-episode preview has never been caught. The second report is a detection gap. Those segments were not found and then hidden. The stages below say where each type stopped.

Nothing here is a hit rate on real episodes. The table at the end is synthetic fingerprints only. The phone run at the bottom is what produces real numbers.

TheIntroDB public timestamps for Evil season 1 were not recorded. `GET https://api.theintrodb.org/v4/media?imdb_id=tt8550800&season=1&episode=1` (and episode 2, and the v1 URL) returned HTTP 403 from Cloudflare ("Sorry, you have been blocked") from this environment, including with a browser user agent. That is a network block, not evidence that the show has no rows.

## What the audio pass actually stores

`iosApp/iosApp/Player/MediaAnalyzer.swift` decodes the main audio track only. Every other stream is discarded (`AVDISCARD_ALL`, around line 311). The pass stores a 32-bit sub-fingerprint every 100 ms for the first 10 minutes (`HEAD_FINGERPRINT_MS` in `MediaAnalysisModels.kt`) and the last 8 minutes (`TAIL_FINGERPRINT_MS`). Video black frames are not measured. `MediaAnalysisResult.blackRanges` exists so a caller that already has them, including the synthetic harness, can snap an edge. On a phone the decision line's black count is zero because the list stays empty.

Silence (level at or below the existing -60 dBFS cut, at least 300 ms) and an audio scene change (four of the previous five fingerprint diffs at 8 bits or fewer, then a jump of 20 bits or more) still move an edge that is already within 2 seconds. Black, when a caller supplies it, wins over silence, which wins over the scene change (`SilenceEdges.kt`, `snapEdge`).

## Intro: the longest shared run was the wrong clip

`SeasonSegmentDetector.detect` (`FingerprintMatcher.kt`) compares this episode's head print with up to four siblings on the live pass (`MediaAnalysisScheduler` `MAX_SIBLINGS`) and up to 16 on the season back-fill.

Previously each pair contributed only `FingerprintMatcher.longestSharedSegment`. With three or more siblings, `vote` keeps a start only when at least two of those longest runs begin within `SEGMENT_AGREE_MS` (2 seconds) on *this* episode. A theme whose position changes from episode to episode still survives that vote when the theme itself is the longest match, because the start that is voted is this file's start, and the sibling offset is absorbed by the alignment. That is why a show whose intro moves, but whose theme is the longest thing the episodes share, can still hit.

A cold open breaks it. A serialised show reuses a different earlier scene against each sibling. That reused scene is often longer than the theme. The longest-run list is then three different starts, none within 2 seconds, and `vote` returns nothing (`rejected-no-cluster`). The theme never entered the vote.

`topSharedSegments` keeps up to four shared runs from the same scan. `vote` clusters by distinct sibling, so one sibling's extra runs cannot inflate the cluster. With fewer than three siblings the longest run is still kept, which is the previous behaviour.

A TheIntroDB interval already sitting in `SkipIntroRepository.skipIntervalCache` is read by `cachedSegmentPriors` (`SegmentDecision.kt`). No new request and no new service. `priorRescue` may accept a fingerprint candidate whose start and end are already within 2 seconds of that interval when the vote found nothing. It does not invent a segment when the audio does not match. The scheduler passes those cached times into `applyingSeasonDetection` (`MediaAnalysisScheduler.kt`, audio pass and the derived rematch).

Limit that is still true: an intro that begins after minute 10 is not in the stored head print. Extending that window would decode more audio. Both the old rule and the new rule miss it. The synthetic scorecard records that miss on purpose.

## Recap: the search ran, then threw the real hit away

Recap detection does run. `MediaAnalysisScheduler.derivedAnalysis` calls `RecapDetector.detect` when Recap detection is on, and `seasonFingerprintPass` calls it again when a fingerprint is saved.

The search that existed required a detected intro of at least 10 seconds, then matched the opening against every sibling's stored head and tail. With three or more siblings, `RecapHit.agreed` (`FingerprintMatcher.kt`) keeps a hit only when two siblings share that start frame. A "previously on" matches the previous episode's ending and no one else's, so a normal season always rejected it (`rejected-agreement`). `needsRecapFromStoredFingerprints` also refused to run until an intro was already stored, so a recap with no intro was never searched.

The montage path is still there. It is the fallback, and the tests that depend on a clip shared by several openings still pass. Ahead of it, `matchPreviousEnding` matches this episode's opening (or the first 240 seconds when no intro is stored) against the previous episode's tail, with that episode's credits masked. One previous episode is enough. The match has to start within 20 seconds of the beginning. A result shorter than 10 seconds or longer than 240 seconds is rejected. The season back-fill no longer requires an intro before it tries this.

Middle-of-episode footage is still not stored, so a recap built only from those scenes cannot be found. The tail print used to be skipped when a credits chapter already covered the ending (`fingerprintPartsToSkip`). That tail is the previous ending the next episode reads, so the tail print is no longer skipped (`ChapterSkipClassifier.kt`).

## Preview: the fingerprint search never existed

Before this change, nothing in the scheduler, the season pass, or `FingerprintMatcher` matched a preview. The only writer was `applyingChapterSegments`, and only for an exact English chapter title (`preview`, `next time`, `next time on`, `next episode`, `coming up`). "Next Time On Evil" does not match that list, and the list was not loosened: junk titles and non-English titles stay ignored.

`onDeviceSkipIntervalsFrom` (`PlayerMediaAnalysis.kt`) already emitted a `preview` skip interval when `previewStartMs` / `previewEndMs` were set and Intro detection was on. `skipSourcesFor` (`SkipSourceLedger.kt`) already showed "Preview · On-device" for a stored range whose file name matches. `SkipIntroButton` already labels that interval "Skip Preview". The downloads "Previews" chip is scrub thumbnails (`AnalysisChipKind.Previews`), not the next-episode preview. There is still no separate next-episode chip. The recap chip turns Ready when `recapEndMs` is stored (`MediaAnalysisStatus.kt`).

Two offer stages did not run:

- No analysis path produced a fingerprint preview, so the skip button, the source row, and the recap-style chip had nothing to show.
- `segmentTypesSyncedByAnalysis` omitted `preview` even when times were stored, so the Android submit bar never marked preview Auto synced. A stored preview is now included. iOS still does not show that chip. Preview was not added to the auto-skip setting. It is offered, not auto-jumped.

`PreviewDetector` matches this episode's tail, credits masked, to the next episode's head before its intro. The window is the cold open when an intro time exists, otherwise the first 90 seconds. A match under 5 seconds or over 180 seconds is rejected. If the next episode's intro starts at 0, the cold open is empty and the result is `rejected-no-cold-open` rather than a match against the theme. The season back-fill always keeps the previous and next episode in the reference set, even when they fall outside the newest-16 cap.

A chapter preview and a same-cut user mark are kept (`withDetectedPreview`). A TheIntroDB preview prior does not create a range.

`MEDIA_ANALYSIS_DERIVED_VERSION` is 3, so an episode that already has a fingerprint re-runs recap and preview without decoding again. The intro is re-voted in that pass only when the audio pass did not already trace it and no intro is stored.

## Decision trace

`derivedAnalysis` writes four lines, before thumbnails, through the same loggers as the timings line (`logSegmentDecisions` → `InAppLogger` and `DiagnosticsLogger.logPerformance`). Android's diagnostics logger is a no-op; iOS shows the lines in Settings › Advanced › Diagnostics.

Shape:

`segment id=<download id> type=intro|recap|credits|preview candidates=N signal=... accepted=0|1 startMs= endMs= confidence= why= thresholds=`

The id keeps letters, digits, `_`, and `-`, and an imdb-style token is stripped. The other fields drop anything outside `a-z0-9_.,:+=-`, including a URL. One line per type. A chapter result replaces the fingerprint decision (`chapter-kept`) so the line matches what was stored. Detection that is switched off is `detection-off`. A type this pass did not search is `not-run` or `stored-earlier-pass`.

Signals used now: `fingerprint-longest`, `fingerprint-cluster`, `theintrodb-prior`, `previous-ending`, `head-agreement`, `next-cold-open`, `chapters`, `user-mark`.

## Caller search and removal list

Callers of the touched entry points, after this change:

| Symbol | Callers |
| --- | --- |
| `RecapDetector.detect` | `MediaAnalysisScheduler.derivedAnalysis`, `seasonFingerprintPass`, tests |
| `PreviewDetector.detect` | `MediaAnalysisScheduler.derivedAnalysis`, `seasonFingerprintPass`, tests |
| `withDetectedRecap` | `MediaAnalysisScheduler.derivedAnalysis`, `ChapterSkipPriorityTest` |
| `withDetectedPreview` | `MediaAnalysisScheduler.derivedAnalysis`, `ChapterSkipPriorityTest` |
| `applyingSeasonDetection` | `MediaAnalysisScheduler` (audio pass and derived rematch), `seasonFingerprintPass`, tests |
| `segmentTypesSyncedByAnalysis` | `PlayerScreenRuntime.autoSyncedSubmitTypes`, `PlayerMediaAnalysisTest`, synthetic and offer-path tests |

Removal list: none. No detection source, setting, chip, chapter title, source rank, or ledger behaviour was removed. The tail fingerprint is no longer skipped when a credits chapter exists; that print is an input to preview and to the next episode's recap, and the samples were already decoded. Labels and internal reasons changed (`accepted-previous-ending`, `accepted-next-start`, `rejected-agreement`, and the rest of the `why=` tokens).

## Synthetic before / after

Host tests in `SyntheticSegmentSignalsTest` and `SegmentOfferPathTest`. "Before" is the previous rule copied into the test (longest run only, recap agreement of two siblings, preview always null), not a second production path. Times are milliseconds.

| Case | Before | After |
| --- | --- | --- |
| Cold open longer than the theme, three siblings, theme at 250–290 s on this file | miss | intro 250000–290000, 3 siblings |
| Theme is the longest match, different offset on each sibling | hit at 250000 | hit at 250000 |
| Theme would start at 610 s, outside the 10 minute head | miss | miss |
| Opening copies the previous episode's ending, two other siblings do not share it | miss | recap 0–25000, signal `previous-ending` |
| Ending copies the next episode's cold open | miss (no search) | preview 2350000–2370000, signal `next-cold-open` |
| Shared credits, black frame 1 s before the raw end | raw end | end moves onto the black frame |
| Recap audio match of 8 s | — | `rejected-short` |
| Preview audio match snapped under 5 s | — | `rejected-short` |
| TheIntroDB prior, no audio candidate | miss | miss (prior does not invent) |

Stage tests, each covering a synthetic recap and a synthetic preview:

1. Signal to candidate: `RecapDetector` / `PreviewDetector` return a range and a trace with `candidates >= 1` and the signal above.
2. Acceptance: a clip under the minimum is rejected; the ranges in the table are accepted.
3. Store: `MediaAnalysisStore.save`, cache cleared, `load` returns both ranges (`SegmentOfferPathTest`, `retainForTests`).
4. Ledger: `skipSourcesFor` returns Recap · On-device and Preview · On-device. The recap chip is Ready.
5. Offered: `onDeviceSkipIntervalsFrom` returns both skip intervals, and `segmentTypesSyncedByAnalysis` contains both types (the Auto synced set).

## Swift

`MediaAnalyzer.swift` has a comment only. Video is still discarded. Verify on a Mac: after one episode, Diagnostics shows a timings line and four `segment` lines, and the non-audio streams are still `AVDISCARD_ALL`.

## What a phone run must show

Play or analyse four episodes of one season, then share the diagnostics log. Each episode should have four `segment` lines. For a cold-open show the intro line should say `signal=fingerprint-cluster` with `candidates` greater than 1 when the theme was not the longest run, or `why=rejected-no-cluster` with those candidates listed as a count if the theme is outside the head print. The recap line should say `signal=previous-ending` and `why=accepted-previous-ending` when the previous episode's tail is stored, or `rejected-no-previous` / `rejected-agreement` when it is not. The preview line should say `signal=next-cold-open` and `why=accepted-next-start` when the next episode's opening is stored, or `rejected-no-next` when it is not. Credits keep `fingerprint-longest` or `fingerprint-cluster`. The lines contain thresholds and no titles, URLs, or account keys.
