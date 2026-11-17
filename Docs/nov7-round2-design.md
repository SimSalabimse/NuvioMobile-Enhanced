# Nov 7 round 2

Build 206 on `sync/upstream-0.6.7-on-batch11`. No merge to enhanced or integration. Public name: Simsalabim.

## Portrait hero

The first portrait pass kept the old hero slot (home `width * 1.5`, details at least 420dp) and pinned the title to the bottom of it. A 16:9 band at the top left a large black rectangle before the title. That reserve is gone.

On a portrait phone the artwork slot is the picture: a 16:9 band for wide or not-yet-loaded art, or a 2:3 column at 0.62 of the width for tall art. The title and actions sit directly under that slot. Landscape and tablet keep the crop and the extra scale (details 1.08, home 1.14). A tall image may grow the slot once, when its size is known.

Files: `HeroArtworkFrame.kt`, `DetailHero.kt`, `HomeHeroSection.kt`. `TabletDetailHero.kt` is unchanged.

## Dynamic Type on the changed screens

`NuvioTheme` still pins Compose `fontScale` to 1, so the rest of the app does not follow the system size. The season sheet already overrides that locally, which is why it scaled and home, details, and Downloads did not. Those three screens now apply the same override. Fixture launch does not bypass it. iOS reads `preferredContentSizeCategory` (AX5 is 3.12). Android reads the configuration font scale. The theme pin stays, and the scale is not divided.

At the default size the hero titles stay on the display style. From 1.5 up, home and details pick the largest base at or under that display size whose longest word still fits the content width after the full scale. A small phone therefore wraps "Harbor Lane" on the space instead of splitting "Northwind" inside the word. The scaled title stays larger than the default display size. Smart downloads stacks the label above the choices at those sizes, wraps the choices, and gives each choice a 44dp target. The home hero adds the tab-bar inset under the synopsis so that copy can scroll clear of the bar. Details already ends with that inset.

Set the simulator content size before a cold launch, then reshoot `home-online`, `home-tall`, `details-online`, `details-tall`, `downloads-season`, and `season-overview`:

```
xcrun simctl ui <udid> content_size accessibility-extra-extra-extra-large
```

Reset with `content_size large`. Landscape and Reduce Motion are not covered here.

## Analysis queue

`Analysing 72%` is `AnalysisStage.Matching`. That share stays 0.72 for the whole stage, so a long match looks frozen. Audio cannot display 72. The owner watched one file sit at 72% for more than 10 minutes and then finish by itself. That run was slow work with no visible activity, not a dead queue. The 60s, 90s and 120s cutoffs added for a stall would have cancelled that run. They are gone. A thrown exception and a missing file still record a reason and the queue continues. Cancellation still stops the pass. A paused or partial download is not eligible. The next id is the one after the finished id.

The timings line now splits match, reference clip, season teach and season backfill. Each step logs its start, and the row shows the step plus the minutes it has been running. The percent is not animated. The fingerprint matcher is unchanged.

Settings › Advanced › Debug Logs has Export analysis log. It uses the existing system share sheet. The log is a separate bounded file of job, stage, timings, errors, and queue changes. Export is only when the user asks, nothing is uploaded, and the diagnostics log is left as it is. URLs keep the host only. Tokens, emails, and personal paths are removed before the text is shared. Reason codes that start with accepted-, rejected-, or skip- are kept. A secret in the same line is not.

The owner's diagnostics export from 19:36 Oslo on 7 Nov is mixed history. Builds 197, 198, and 199 are older and are not this run. Build 201 (0.6.9.1) on that evening is the run. Times below are Oslo (UTC+1). A timings line is written when that pass ends. totalMs and matchMs are measured durations of that pass, not the gap from the previous line.

Twelve distinct job ids have a build 201 timings line. The previously quoted 2.3s line is job mw4an0ww_1 at 18:57:08 (matchMs 336, totalMs 2269). It is a different job from the long one. The long pass is job mvpdb6ts_1, line at 19:30:08: matchMs 1338520, totalMs 1345191, decodeMs 4079, sampleLoopMs 2460, fingerprintMs 37, chaptersMs 0, throttledMs 0, qos userInitiated. Match time is 22 minutes 19 seconds of a 22 minute 25 second pass. An earlier build 201 pass, job mviqlafc_2 at 18:56:47, is also long: matchMs 670814 of totalMs 709089, throttledMs 20331, qos utility. After mvpdb6ts_1 the next timings lines are 19:32:27 (matchMs 124610), 19:33:05, 19:33:39, and 19:34:16. That cluster is the 12-of-12 finish the owner saw at 19:33. Seven distinct ids have a timings line before mvpdb6ts_1. The log does not number the on-screen rows, so it does not prove which row was the eighth.

On build 201, matchMs is one bucket: pairwise detection, learning from marked episodes, a sibling clip, saving and backfilling the season, and teaching the season. The row is already on Matching, whose percent is the constant 0.72, for that whole bucket. Decode, fingerprint, and throttle do not account for this wait. The line has no clip, teach, or backfill split, so which of those five used the 22 minutes is not in this log. Job ids are not titles.

The store lock on iOS is still not re-entrant, and `update` still refuses to take it twice. That guard stays. This finished run did not hit it, so it is not the cause of the 10 minutes. JVM tests still hide a real re-entry because that lock re-enters there.

Files: `MediaAnalysisStore.kt`, `MediaAnalysisScheduler.kt`, `AnalysisQueue.kt`, `AnalysisStatusUi.kt`, `AnalysisStageTimings.kt`. Acceptance: the next id is chosen after a finished id; a failure skips that id; a paused file is not eligible; matching stays at 0.72 while the elapsed minutes advance; `update` does not re-enter the lock.

## Movies

Public GET (`submission_count`, or a range when the count field is absent) is separate from this account's confirmed and pending ledger and from on-device detection. A check is confirmed-only. Movies use all four segment types and a query with no season or episode. HTTP 404 / `no_submissions` is an empty public result. No automatic submit.

Files: `TheIntroDb.kt`, `MovieSegmentStatus.kt`, `SubmitIntroDialog.kt`.

## Season overview and downloads header

Rows are an inset list. Segment chips show only when they are not missing, with a short label. Watched is a word, not the submission check. Download selection is a menu action, not a checkbox. Watch privately is a swipe and a menu item. The downloads season title gets the full width; actions wrap on the next line.

Files: `SeasonOverview.kt`, `SeasonOverviewSheet.kt`, `DownloadsScreen.kt`.

## Smart Downloads on the show page

Before this change the count chip only stored a number. Nothing was queued until playback of an episode you already had, near the end, or until a request left over from earlier was retried. Leaving the page did not start it.

The chip now runs the same next-episode check at once. The next episodes are the ones after the furthest watched episode, or after the latest download of the show when nothing is watched. Episodes already saved or already queued are skipped, including one you picked yourself. Wi-Fi, charging, free space, and air dates still apply. The page says it is looking, then "Queued N episodes", or the reason it cannot start. Choosing 0 drops a waiting next-episode request for that show and does not cancel a download. Private Viewing, a UI fixture, and automation do not start one. Playback still waits until the episode is far enough along.

Files: `SeriesSmartDownloadCount.kt`, `SmartDownloadRuleChange.kt`, `SmartDownloads.kt`.

## Baseline screenshots

The attached contact sheet is the exact 508 baseline on iOS 27.2 (24B114), synthetic fixtures, not the owner's media. Home and details show the wide art fitted whole, with empty surface around it. The season sheet shows an empty square on every row, Watch privately under every row, and Preview wrapping onto the next line. Detail fixtures that say playback is unavailable are offline on purpose. The startup fixture keychain abort no longer occurs.

The attached Downloads image is the generic downloads list (storage card, search, Active, Movies). It is not the crowded season header. That header is the `downloads-season` fixture, which the default shot list did not include.

`home-online` and `details-online` use the wide banner (800×450). `home-tall` and `details-tall` use the 2:3 poster (400×600). `movie-segment-rows` is one overlay with a public count, a confirmed check, a pending send, and a local detection. Fixture series have no cast, and this batch does not change the cast row.

Every app theme paints a dark surface. WHITE is the default dark surface with a white accent, not a light chrome. The simulator appearance switch does not change it. Set the in-app value `selected_theme_1` to WHITE and to CRIMSON before launch. Both stay dark. AX5 is the system content size Accessibility Extra Extra Extra Large. Reduce Motion is the system switch `UIAccessibilityIsReduceMotionEnabled`.
