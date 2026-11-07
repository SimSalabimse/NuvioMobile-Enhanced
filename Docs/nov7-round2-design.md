# Nov 7 round 2

Build 202 on `sync/upstream-0.6.7-on-batch11`. No merge to enhanced or integration. Public name: Simsalabim.

## Portrait hero

The tall phone slot currently fits the whole picture inside the hero, so a wide banner sits in empty bars. Landscape keeps the existing crop and extra scale.

Portrait wide art draws as a full-bleed 16:9 band at the top (crop, no extra scale, no scroll zoom, horizontal focus). The title and actions stay in the hero below that band, on the surface colour. A tall poster fits a 2:3 column and does not stretch. Unknown size does not apply the landscape extra scale.

Files: `HeroArtworkFrame.kt`, `DetailHero.kt`, `HomeHeroSection.kt`, `TabletDetailHero.kt`. Risk: the home scroll reserve stays the full hero height so the rows below do not jump. Acceptance: landscape decisions unchanged; portrait wide art reports a band shorter than the frame. Screenshots stay on the Mac. This VM does not substitute them.

## Analysis queue

`Analysing 72%` is `AnalysisStage.Matching` (the overall value is fixed at 0.72). Audio cannot display 72. The timings line is written only after matching returns, so a later file still inside matching leaves E6/E7/E8 queued.

On iOS the analysis store lock is not re-entrant. `update` (and the first-run cleanup, and show linking) take the lock and then call `load`/`save`, which take it again. `teachSeason` calls `update` while the job is still in matching, before the timings line. The queue cannot start the next file. JVM tests hide this because that lock re-enters there.

Matching, the reference clip, season teaching, and a native call whose progress does not move are bounded. Teaching that runs out of time is logged and the file still finishes, so a later empty recap is not treated as a stall. A stall records a reason and the loop continues. A thrown exception does the same. Cancellation still stops the pass. Unknown progress is NaN and does not count as movement. This is not a fake progress bump. The next id is the one after the finished id, not the first id that is still in the list.

Files: `MediaAnalysisStore.kt`, `MediaAnalysisScheduler.kt`, `AnalysisQueue.kt`. Acceptance: the next id is chosen after a finished id; a failure skips that id; a stale stage is detectable; `update` does not re-enter the lock.

## Movies

Public GET (`submission_count`, or a range when the count field is absent) is separate from this account's confirmed and pending ledger and from on-device detection. A check is confirmed-only. Movies use all four segment types and a query with no season or episode. HTTP 404 / `no_submissions` is an empty public result. No automatic submit.

Files: `TheIntroDb.kt`, `MovieSegmentStatus.kt`, `SubmitIntroDialog.kt`.

## Season overview and downloads header

Rows are an inset list. Segment chips show only when they are not missing, with a short label. Watched is a word, not the submission check. Download selection is a menu action, not a checkbox. Watch privately is a swipe and a menu item. The downloads season title gets the full width; actions wrap on the next line.

Files: `SeasonOverview.kt`, `SeasonOverviewSheet.kt`, `DownloadsScreen.kt`.
