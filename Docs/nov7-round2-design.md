# Nov 7 round 2

Build 204 on `sync/upstream-0.6.7-on-batch11`. No merge to enhanced or integration. Public name: Simsalabim.

## Portrait hero

The tall phone slot currently fits the whole picture inside the hero, so a wide banner sits in empty bars. Landscape keeps the existing crop and extra scale.

Portrait wide art draws as a full-bleed 16:9 band at the top (crop, no extra scale, no scroll zoom, horizontal focus). The title and actions stay in the hero below that band, on the surface colour. A tall poster fits a 2:3 column and does not stretch. Unknown size does not apply the landscape extra scale.

Files: `HeroArtworkFrame.kt`, `DetailHero.kt`, `HomeHeroSection.kt`, `TabletDetailHero.kt`. Risk: the home scroll reserve stays the full hero height so the rows below do not jump. Acceptance: landscape decisions unchanged; portrait wide art reports a band shorter than the frame. Screenshots stay on the Mac. This VM does not substitute them.

## Analysis queue

`Analysing 72%` is `AnalysisStage.Matching`. That share stays 0.72 for the whole stage, so a long match looks frozen. Audio cannot display 72. The owner watched one file sit at 72% for more than 10 minutes and then finish by itself. That run was slow work with no visible activity, not a dead queue. The 60s, 90s and 120s cutoffs added for a stall would have cancelled that run. They are gone. A thrown exception and a missing file still record a reason and the queue continues. Cancellation still stops the pass. A paused or partial download is not eligible. The next id is the one after the finished id.

The timings line now splits match, reference clip, season teach and season backfill. Each step logs its start, and the row shows the step plus the minutes it has been running. The percent is not animated. The fingerprint matcher is unchanged.

Settings › Advanced › Debug Logs has Export analysis log. It uses the existing system share sheet. The log is a separate bounded file of job, stage, timings, errors, and queue changes. Export is only when the user asks, nothing is uploaded, and the diagnostics log is left as it is. URLs keep the host only. Tokens, emails, and personal paths are removed before the text is shared. Which step used the 10 minutes is still unknown until a log from that run is read.

The store lock on iOS is still not re-entrant, and `update` still refuses to take it twice. That guard stays. This finished run did not hit it, so it is not the cause of the 10 minutes. JVM tests still hide a real re-entry because that lock re-enters there.

Files: `MediaAnalysisStore.kt`, `MediaAnalysisScheduler.kt`, `AnalysisQueue.kt`, `AnalysisStatusUi.kt`, `AnalysisStageTimings.kt`. Acceptance: the next id is chosen after a finished id; a failure skips that id; a paused file is not eligible; matching stays at 0.72 while the elapsed minutes advance; `update` does not re-enter the lock.

## Movies

Public GET (`submission_count`, or a range when the count field is absent) is separate from this account's confirmed and pending ledger and from on-device detection. A check is confirmed-only. Movies use all four segment types and a query with no season or episode. HTTP 404 / `no_submissions` is an empty public result. No automatic submit.

Files: `TheIntroDb.kt`, `MovieSegmentStatus.kt`, `SubmitIntroDialog.kt`.

## Season overview and downloads header

Rows are an inset list. Segment chips show only when they are not missing, with a short label. Watched is a word, not the submission check. Download selection is a menu action, not a checkbox. Watch privately is a swipe and a menu item. The downloads season title gets the full width; actions wrap on the next line.

Files: `SeasonOverview.kt`, `SeasonOverviewSheet.kt`, `DownloadsScreen.kt`.
