# Upstream 0.6.10 sync plan

Design only. This document does not change the app. The sync itself is a later change.

Upstream is `luqmanfadlli/NuvioMobile-Enhanced` only. No other upstream was fetched. The comparison is tag `0.6.10-beta` against our `cursor/batch12-build207-e33c`.

| | Commit | What it is |
|---|---|---|
| Our base | `2e76ccee1` | `cursor/batch12-build207-e33c`. Marketing `0.6.9.1`, build **210**. |
| Last synced upstream | `01879330a` | Tag `0.6.9-beta`. Second parent of our merge `ba3bbcee2` ("Merge tag 0.6.9-beta onto the sync branch."). |
| Upstream release | `03536e36f` | Tag `0.6.10-beta`, published 2026-11-09. Subject: `fix(ios): close parked PiP via main queue`. Their `Version.xcconfig` says marketing `0.6.10`, build **153**. |
| Branch tip after the tag | `c2ef32c91` | `enhanced` is one commit past the tag: `chore(store): publish 0.6.10-beta`, `store.json` only. Not part of the release. Do not merge it. |

`01879330a` is the merge base of `2e76ccee1` and `03536e36f`. From that base, upstream has **41** commits we do not have. We have **615** commits upstream does not have.

## 1. What upstream changed since 0.6.9

Range for everything below: `01879330a..03536e36f` (41 commits). Dates below are the commit dates, not the tag publish dates.

`enhanced` moves in two steps. Six commits are on the first-parent line. The other 35 arrive in `73881b5a6` (`Merge upstream/cmp-rewrite into enhanced (0.6.10-beta)`). Take the tree at the tag. Do not cherry-pick a commit and its pull-request merge as well. Several translations exist twice, once on the branch and once as the merge that landed them.

### iOS player safety

First-parent, apart from the merge and the store publish.

| Range | Subject |
|---|---|
| `e46f8a1e0` | `fix(ios): detach the mpv metal layer before destroying mpv (#336)`. One file: `iosApp/iosApp/Player/MPVPlayerBridge.swift`. |
| `e7d2372cb` | `perf(ios): memory optimization long sessions and 4K HDR/DV playback`. 20 files, 297 insertions, 31 deletions. Image loader, Skia animated image, parked-playback release, seek preview, Picture in Picture subtitles, and the same player bridge. Also touches `LiquidGlass.kt` and `ThemeSettingsRepository.kt`. |
| `03536e36f` | `fix(ios): close parked PiP via main queue`. Three lines in `MPVPlayerBridge.swift`. This commit is the tag. |

### Anime4K

| Range | Subject |
|---|---|
| `89f7ae19a` | `feat(player): add Anime4K 2x upscaler and line sharpener for ExoPlayer and mpv (#337)`. 35 files. New GLSL under `composeApp/src/androidMain/assets/anime4k/`, new `features/player/anime/` code, settings flags, and player strings. Both switches default to false. |

### Auto Sync on iOS

These sit on `cmp-rewrite` and enter through `73881b5a6`. The landing merge is `f0191e1a0` (`Merge pull request #2535`).

| Range | Subject |
|---|---|
| `df099ca85` | `refactor(autosync): move engine to common, drop Media3 for MP4`. 21 files. Renames the Android engine into `commonMain` and adds an MP4 sample-table reader. |
| `4a40dda9a` | `feat(autosync): enable on iOS`. 9 files. New `AutoSyncPlatform.ios.kt`, `AutoSyncMpvPlatform.ios.kt`, `AutoSyncPreferencesIos.kt`, and player-bridge hooks. |

The rename moves these files from `androidMain` to `commonMain`:

- `AutoSyncDelayPreflight.kt` (similarity 100)
- `AutoSyncSampledReferenceAligner.kt` (similarity 100)
- `AutomaticSubtitleSync.kt` (similarity 98)
- `EmbeddedSubtitleTimelineLoader.kt` (similarity 74)
- `SubtitleCharsetDetector.kt` (similarity 80)

### MDBList episode numbers

| Range | Subject |
|---|---|
| `620acb92c` | `fix(mdblist): remap anime episodes between addon and TMDB numbering`. 9 files, including a new `MdbListEpisodeMappingService.kt` and `MdbListEpisodeMappingTest.kt`. |
| `6d0a7daa8` | `Merge pull request #2516`. Lands that fix. Do not cherry-pick both. |

### Player subtitle reset

| Range | Subject |
|---|---|
| `82ca7d119` | `fix(player): reset addon subtitles only when the source changes`. 4 files: `PlayerScreenRuntimeEffects.kt`, `PlayerScreenRuntimePlaybackActions.kt`, `PlayerScreenRuntimeState.kt`, and a host test. |

### Android crash fixes

| Range | Subject |
|---|---|
| `8172e682b` | `fix(watched): copy unsynced keys before saving` |
| `d534d14e9` | `fix(network): treat SSL setup errors as failed requests` |
| `81dc761ee` | `fix(player): guard caption setting reads` |
| `6ad2d9baf` | `fix(player): cap buffer start times at the min buffer` |
| `c1dfd9898` | `fix(player): start playback service as a normal service first` |
| `788322b13` | `Merge branch 'mobile-crash-fixes' into cmp-rewrite` |

Files include `WatchedRepository.kt`, `NuvioApplication.kt`, `NetworkErrorInterceptor.kt`, `PlayerEngine.android.kt`, `PlayerNowPlayingService.android.kt`, `PlaybackBufferSettings.kt`, and `EpisodeReleaseNotificationPlatform.android.kt`.

### Translations

Take the strings that are new. Do not replay both the branch commit and the pull-request merge.

Branch commits: `807924f9d`, `3f35b2e14`, `ac7c78ef9`, `1d7c61bb2`, `71e81b495`, `eba68a8a3`, `063b84c57`, `571dfedce`, `2fae04b2c`, `26e5ae953`, `5ebfbd04a`.

The same work merged again as `fcb25b439`, `23d2d465b`, `fb60f2189`, `aadef4a0d`, `47b338ee6`, `901895a0d`, `c5353f6b9`, `5b82bc8bd`, `e9183fd82`, `8eed3aba1`.

Locales: Hebrew ratings, MDBList, Auto Sync, picture in picture, servers, and watch party. Japanese ratings and the rest of the missing Japanese strings. Greek audio and ratings brought in line with 0.6.9. French ratings. Vietnamese ratings.

### Reject from this range

| Commit | Subject | Why |
|---|---|---|
| `a159060c4` | `chore(store): publish 0.6.9-beta` | A later `store.json` publish of the release we already merged. |
| `de60c5a16` | `chore(store): publish 0.6.10-beta` | Their listing, their IPA, their build 153. |
| `20fb87e66` | `bump version` | Sets marketing `0.6.10` and build 153. We do not take that. |
| `c2ef32c91` | `chore(store): publish 0.6.10-beta` | Not in the tag. `store.json` only. |

## 2. Our features, and the decision

Each decision keeps our behavior. Upstream fills a hole only where it does not replace that behavior, and it does not add chrome to the simple UI.

### Auto Sync

Keep ours. The engine we ship (reference audio, no-fit, retiming, run policy, TheIntroDB's effect on a match) stays. Upstream did not edit those `commonMain` policy files.

Take the iOS platform files that we do not already have (`AutoSyncPlatform.ios.kt`, `AutoSyncMpvPlatform.ios.kt`, `AutoSyncPreferencesIos.kt`) and the bridge method that loads a retimed subtitle in place of the previous URL. Wire them to our engine. Do not replace `AutomaticSubtitleSync` with their common port as the source of truth.

The trial merge moved five Android files into `commonMain` without a conflict marker. Two of those moves are safe. Three are not a silent accept:

| File | Trial result against our `androidMain` copy |
|---|---|
| `AutoSyncDelayPreflight.kt` | Identical after the move. |
| `AutoSyncSampledReferenceAligner.kt` | Identical after the move. |
| `AutomaticSubtitleSync.kt` | 20 insertions, 28 deletions. Android clocks and Media3 role flags become common helpers. Read it. Keep our match behavior. |
| `EmbeddedSubtitleTimelineLoader.kt` | 114 insertions, 412 deletions. Their common MP4 reader replaces the Android parser. We did not edit this file since `01879330a`, but a 412-line drop is not an auto-accept. Keep our loader until the common reader is shown to load the same embedded cues. |
| `SubtitleCharsetDetector.kt` | 72 insertions, 73 deletions. Their rewrite drops our Norwegian codes (`nb`, `nob`, `nn`, `nno`, and the Norwegian names). Re-apply that change on whatever detector we keep. Since 0.6.9 that was our only edit to these five files: 4 insertions, 1 deletion. |

### TheIntroDB detection and submission

Keep ours. Upstream `01879330a..03536e36f` does not touch `TheIntroDb.kt`, the ledger, the submit payload, or `strings_theintrodb.xml`.

The trial conflict in `PlayerScreenRuntimePlaybackActions.kt` is our `submitMode` next to their "reset addon subtitles only when the source changes". Keep `submitMode`. Take their reset only where it does not clear subtitles during a submission session.

Detection stays the contract in `docs/segment-detection.md`. A public time still cannot create a skip range.

### Smart downloads

Keep ours. No upstream file in this range is a smart-download file. `SeriesSmartDownloadCount`, the charging wait, the space rule, and "played episode is kept" stay.

### Private watch

Keep ours. `privateViewingEnabled` stays off unless the profile turned it on, and it still applies on every playback entry. The watched-repository crash fix (`8172e682b`, copy unsynced keys before saving) is a one-line class of change that auto-merged. Read that hunk so a private session still does not write watch progress.

### Season overview

Keep ours. Upstream does not touch `SeasonOverview.kt`, `SeasonOverviewSheet.kt`, or `DownloadsSeasonOverview.kt`. One season sheet, large text, and ledger-only episodes stay.

### Home hero

Keep ours. Upstream does not touch `HomeHeroSection.kt` or `HomeHeroMetaRows.kt`. Full-bleed artwork, the title on the artwork, and the soft top edge stay. Do not bring back a floating glass header on Home or details.

### Diagnostics

Keep ours. Upstream does not touch the diagnostics logger or the device-error-log screen. `MainViewController.kt` conflicts because our side calls `PerformanceMetrics.markProcessStart()` and theirs does not. Keep that call.

The memory work also edits `PlatformImageLoader.ios.kt` and `SkiaAnimatedImage.ios.kt`. Those are not diagnostics, but they sit next to startup. Read them so a long session does not drop the redacted log or the launch mark.

### Swift export trimming

Keep our allowlist (`buildSrc/src/main/kotlin/SwiftExportEntryPoints.kt`). Upstream does not edit it. New iOS bridge members still have to be exported or the Mac build breaks. After the merge, the allowlist has to name:

- `NuvioPlayerBridge.replaceExternalSubtitle`
- `NuvioPlayerBridgeCreator.closeParkedPictureInPicture`

The generator lists interface members one by one. A new method that is not listed is not visible to Swift. Do not widen the allowlist to the performance package. That package stays dropped.

### Simple UI

Keep the simple UI. Live glass stays off. The trial conflict in `ThemeSettingsRepository.kt` is our migration that saves liquid-glass effects as off, against their edit of the same repository for the memory work. Keep our migration.

Do not add an Anime4K badge, a new player tool, or an "Anime enhancement" block on the player. The flags may exist in `PlayerSettingsRepository` (the trial added them, both defaulting to false) and the strings may land, but the controls stay out of the player chrome. `PlayerControls.kt`, `FoldControls.kt`, and `PlayerEngine.kt` each auto-merged with a one-line upstream insertion. Read those lines and drop anything that draws a new badge.

Indonesian (`values-id` and `values-in`) conflicts on the liquid-glass description and on our offline banner (`offline_banner_saved_data`). Keep both of our strings. Japanese conflicts are three upstream strings we do not have (collection copy, download count, open download folder). Take those.

`LiquidGlass.kt` is not in the conflict list. Upstream still edits it inside `e7d2372cb`. Read that auto-merge so the simple UI does not start painting live glass.

## 3. Trial merge

Command, from a clean `cursor/batch12-build207-e33c` at `2e76ccee1`:

```
git checkout -B scratch-upstream-0610-merge cursor/batch12-build207-e33c
git merge --no-commit --no-ff 03536e36f
```

The merge exited 1. `git diff --name-only --diff-filter=U` listed **14 files**. Those files contained **20** conflict regions (`<<<<<<<` count). The scratch branch was then removed with `git merge --abort` and `git branch -D scratch-upstream-0610-merge`. It was not pushed. The working tree was returned to `cursor/batch12-build207-e33c`.

36 files were edited on both sides since `01879330a`. 14 of them conflicted. The other 22 auto-merged and still need the read in section 2.

### Conflict files (14)

| File | Regions | Resolution |
|---|---|---|
| `composeApp/src/androidMain/kotlin/com/nuvio/app/NuvioApplication.kt` | 2 | Keep our poster image client. Take their SSL setup handling. |
| `composeApp/src/androidMain/kotlin/com/nuvio/app/features/notifications/EpisodeReleaseNotificationPlatform.android.kt` | 1 | Keep our automation-test import. Take their crash fix around it. |
| `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt` | 2 | Keep our decoder names and bitrate. Take their buffer and caption guards. |
| `composeApp/src/commonMain/composeResources/values-id/strings.xml` | 1 | Keep our offline banner and our liquid-glass wording. |
| `composeApp/src/commonMain/composeResources/values-in/strings.xml` | 1 | Same as `values-id`. |
| `composeApp/src/commonMain/composeResources/values-ja/strings.xml` | 2 | Take their new collection and download strings. |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenModalHosts.kt` | 3 | Take their new audio-modal parameters. Do not add player chrome. |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimePlaybackActions.kt` | 1 | Keep `submitMode`. Take the subtitle reset only outside a submission. |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeUi.kt` | 2 | Thread their new parameters. Keep our tool wiring. No Anime4K badge. |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/ThemeSettingsRepository.kt` | 1 | Keep liquid-glass effects off. |
| `composeApp/src/iosMain/kotlin/com/nuvio/app/MainViewController.kt` | 1 | Keep `PerformanceMetrics.markProcessStart()`. |
| `composeApp/src/iosMain/kotlin/com/nuvio/app/features/player/PlayerEngine.ios.kt` | 1 | Keep our in-app playback note. Take their memory and Auto Sync lines. |
| `iosApp/Configuration/Version.xcconfig` | 1 | Ours is `210` / `0.6.9.1`. Theirs is `153` / `0.6.10`. Write section 5 instead. |
| `store.json` | 1 | Keep ours. Theirs publishes their IPA. Our file on this branch still says `0.6.5.1` / build 198. Updating that listing is out of this sync. |

### Auto-merged overlap (22)

These did not conflict. They are still part of the predicted review, because the player bridge and the glass path are in here.

- `composeApp/src/androidMain/kotlin/com/nuvio/app/features/addons/AddonPlatform.android.kt`
- `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerPlaybackNetworking.kt`
- `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerSettingsStorage.android.kt`
- `composeApp/src/commonMain/composeResources/values-el/strings.xml`
- `composeApp/src/commonMain/composeResources/values/strings.xml`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/FoldControls.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerControls.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerEngine.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerModels.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeEffects.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeState.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerSettingsRepository.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerTimeline.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsSearch.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamsScreen.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watched/WatchedRepository.kt`
- `composeApp/src/iosMain/kotlin/com/nuvio/app/core/ui/SkiaAnimatedImage.ios.kt`
- `composeApp/src/iosMain/kotlin/com/nuvio/app/features/player/PlayerBridge.kt`
- `composeApp/src/iosMain/kotlin/com/nuvio/app/features/player/PlayerSettingsStorage.ios.kt`
- `iosApp/iosApp/Player/FFmpegSeekPreviewGenerator.swift`
- `iosApp/iosApp/Player/MPVPlayerBridge.swift` (158 insertions, 1 deletion against ours)
- `iosApp/iosAppTests/SubtitlePlaybackTests.swift`

### Renames git applied with no conflict marker

- `AutoSyncDelayPreflight.kt`
- `AutoSyncSampledReferenceAligner.kt`
- `AutomaticSubtitleSync.kt`
- `EmbeddedSubtitleTimelineLoader.kt`
- `SubtitleCharsetDetector.kt`

Treat the last three as conflicts even though git did not. Section 2 says which result to keep.

## 4. Merge order, risks, verification

One merge of tag `03536e36f` onto `cursor/batch12-build207-e33c`. Do not merge `c2ef32c91`. Do not reset the branch onto upstream. Resolve in this order so a later hunk cannot put build 153 back:

1. `Version.xcconfig` to section 5.
2. `store.json`: ours.
3. `ThemeSettingsRepository.kt`, then read the auto-merged `LiquidGlass.kt` and the liquid-glass strings. Simple UI stays.
4. `PlayerScreenRuntimePlaybackActions.kt` (`submitMode`), then `PlayerScreenRuntimeUi.kt` and `PlayerScreenModalHosts.kt`.
5. Android crash-fix conflicts: `NuvioApplication.kt`, `EpisodeReleaseNotificationPlatform.android.kt`, `PlayerEngine.android.kt`. Keep both sides as in the table.
6. iOS: `MainViewController.kt`, `PlayerEngine.ios.kt`, then read `MPVPlayerBridge.swift`, `PlayerBridge.kt`, and `SubtitlePlaybackTests.swift`. Add the two bridge methods to the Swift export allowlist.
7. Auto Sync renames. Re-apply the Norwegian charset codes. Do not accept the 412-line loader drop until the cue check passes.
8. Translations: keep our Indonesian strings, take the Japanese additions, take new Hebrew, Greek, French, and Vietnamese files that did not conflict.
9. MDBList remap and Anime4K code, flags defaulting to false, no new player chrome.
10. Changelog entry for `0.6.10.1` / build 211. The changelog on this branch still records `0.6.9.1` as build 207. `Version.xcconfig` is the source of truth and is already 210.

### Risks

- A clean Auto Sync rename deletes the Android embedded-subtitle parser and drops Norwegian detection.
- The Swift export allowlist omits `replaceExternalSubtitle` or `closeParkedPictureInPicture`, and the iOS build fails.
- Resolving the player runtime the wrong way turns off TheIntroDB submission or private watch.
- `ThemeSettingsRepository.kt` or `LiquidGlass.kt` turns live glass back on.
- `Version.xcconfig` is resolved toward upstream and the build number goes back to 153.
- `store.json` is resolved toward upstream and this fork publishes their IPA.
- `values-id` / `values-in` lose `offline_banner_saved_data`.
- The host suite's completed count moves because upstream adds tests. That is expected. A new failure in our existing tests is not.
- `MPVPlayerBridge.swift` is a large auto-merge on the file that owns playback, Picture in Picture, and the metal layer.

### Verification

This plan changes no code, so the suite was not run again for this document.

Baseline already measured on `2e76ccee1`, Linux, `./gradlew :composeApp:testAndroidHostTest` with `-Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"` and `-Pkotlin.daemon.jvmargs="-Xmx6g -XX:MaxMetaspaceSize=1g"`, `--no-daemon --console=plain`. Three runs: 3063 completed, 7 failed, 6 skipped (4m 45s, 1m 45s, 1m 45s). Median 3063 / 7 / 6. The seven failures are `AndroidDownloadLifecycleTest.missingSystemJobIsPausedInsteadOfSilentlyRescheduling` (twice, one with `[34]`), the four `DeleteDownloadWhenFinishedTest` cases, and `DownloadSubtitlesTest.backgroundDownloadSavesAddonAndStreamSubtitlesBeforeVideo`. Logs: `/opt/cursor/artifacts/host-tests/run-1.log`, `run-2.log`, `run-3.log`.

After the sync merge, run that same command three times on the sync commit. Record completed, failed, and skipped. Upstream's new tests (MDBList mapping, Auto Sync platform, Anime4K shader) should pass. The seven failures above should be the same seven, unless a crash-fix in this range actually fixes one. If it does, say which test and which upstream commit. Do not reset the count to theirs and do not treat a new failure as the baseline.

Mac, Simsalabim, on the owner's episodes. This VM has no Xcode and no episode files, so this plan does not invent a command. Re-run the harness already used for this fork and check it against `docs/segment-detection.md` and `docs/subtitle-autosync.md`:

- An intro, a recap, and credits still match the stored prints. A public TheIntroDB time still does not create a range.
- Subtitle Auto Sync still fits, including a Norwegian track.
- Submission still requires `submitMode` and does not fire on a normal play.

iOS build: not measured here. On the Mac, build the app at marketing `0.6.10.1`, build 211. Confirm the Swift export still compiles with the two new bridge methods, and that the metal-layer detach and the main-queue Picture in Picture close are in the binary's source.

Device, after that build is installed:

- Home hero is full-bleed, title on the artwork, soft top edge. No floating glass header.
- Season sheet matches the show page and Downloads, including an episode that only exists in the ledger.
- Private watch still leaves no progress.
- One smart download still obeys the saved count and does not delete the episode that was just played.
- Skip still appears only after a match. TheIntroDB status on the season row is unchanged.
- Diagnostics log still records a redacted line and can be exported.
- Player chrome has no Anime4K badge. Live glass is off.
- Park Picture in Picture, then close it. The player comes back without a stuck metal layer.
- Build number shown in the app is 211, not 153.

iOS build, app size, and FPS for this plan: not measured.

## 5. Version

Upstream's tag is marketing `0.6.10`, build 153. We do not take the build number.

Our scheme is upstream's marketing version plus our own revision, and the build number only goes up. `0.6.9` became `0.6.9.1` at build 210. The sync therefore ships:

| Field | Value |
|---|---|
| `MARKETING_VERSION` | `0.6.10.1` |
| `CURRENT_PROJECT_VERSION` | `211` |

211 is the next build after 210. It is not a reset, and it is not upstream's 153. Android reads the same `Version.xcconfig`, so both platforms move together. The changelog entry for the sync uses `0.6.10.1` and build 211.
