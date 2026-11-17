# Upstream 1.0.0 sync design

Design only. This file does not change the app. The sync itself is not started.

Target tree: `origin/enhanced` at `81572208faeaf871239a1a3087f250d5cf3d630c` (build 224, marketing `0.6.10.1`).

Upstream repository: `luqmanfadlli/NuvioMobile-Enhanced`. Do not sync any other Nuvio remote.

Resulting version, when the sync is implemented: marketing `1.0.0.1`, build `225`. The build number continues from 225 and never resets. Do not take upstream's `157` or a bare `1.0.0`.

Rules for every conflict:

- Our features and the simple UI win.
- The playback lane stays exactly as it is on `81572208` (one low-priority next episode; serious heat keeps 30/30 and does not suspend a running transfer; critical holds).
- The idle heat gate stays exactly as it is (nominal 2 normal, fair 2 low, serious 1 low, critical pause, Low Power Mode 1).
- Analysis pause wording stays: playing, warm download, heat, Low Power, and unknown reasons say `Paused`.
- Season overview stays the four icons and the `Yours` / `Public` / `Missing` key.

Before/after timings, sizes, and frame rates for this sync: not measured.

## 1. What 1.0.0 is, and what changed since 0.6.10-beta

Tag `1.0.0` is commit `6c2ad7937171965608d05005080b96ca19345379` (16 Nov 2026, subject `docs(readme): sync the latest enhanced features`). There is no `v1.0.0` tag.

The last upstream version this fork synced is tag `0.6.10-beta`, commit `03536e36f34daacac7fb27d7e12276cd7bf410d4` (`fix(ios): close parked PiP via main queue`). That tag is an ancestor of upstream `1.0.0`. It is not an ancestor of `enhanced`. The content base is still that tag: `docs/upstream-0.6.10-sync-review.md` records the earlier sync whose second parent is `03536e36f`.

Range `0.6.10-beta..1.0.0`, measured with git:

- 145 commits
- 241 files
- 12007 insertions, 1263 deletions

`git merge-base enhanced 1.0.0` is empty. `git rev-list --count enhanced..1.0.0` is 4169, because the rebuilt `enhanced` history does not contain the upstream commits. Use the 145-commit range for the content review. Do not treat 4169 as the size of the feature delta.

Grouped by area, from the commit subjects and the file diff:

- Player. Keyboard, volume arrows, More Like This source, HLS quality, seek-preview prefetch, loading stages, chapters, error wording, audio clock when video time drops to 0, source fallback, DASH MIME routing.
- iOS playback. Picture in Picture Dolby Vision (RPU reshaping and Profile 5), HDR colours when subtitles are drawn in PiP, mpv backing scale and native-resolution drawable, lighter mpv setup under the jetsam limit, memory freed before playback. New file `iosApp/iosApp/Player/PictureInPictureDolbyVisionConverter.swift` (410 lines). MPVKit gitlink moves from `7f40522ab9a18c7f14c04316f71c7dd47e2a7e05` to `359a885342d4eeda757f8e1d2c24fb01fe6d39c2` (nuvio.7).
- Home and hero. Hero waits until its catalogs load, fewer startup recompositions, trailer mute in the top-right, backdrop framing, details cinematic trailer.
- Streams and Continue Watching. Debrid cache once per search, tokenized links, episode header artwork, Continue Watching click and dismiss, stream badge pushed before the next pull (`b8a4dabfb`).
- Live TV. Buffering and black-screen fix, HLS parameter-set init, header parsing.
- Blu-ray. ExoPlayer path for raw Blu-ray files, plus new `bluray` sources and tests. Android only.
- Auth and lists. Session stays signed in when refresh fails, Simkl OAuth v1 to v2, MDBList remap cache.
- i18n. French, Vietnamese, Slovak, Dutch, Bulgarian, and other locale updates.
- Release. Store publishes `0.6.11-beta` through `0.6.13-beta`, then `1.0.0`. `ci(release)` adds stable and beta. `Version.xcconfig` on the tag is `MARKETING_VERSION=1.0.0` and `CURRENT_PROJECT_VERSION=157`.

Upstream did not touch these trees between the two tags (0 files): `features/analysis`, `features/downloads`, `features/player/skip`, `SeasonOverview.kt`, `sync-worker`, `iosApp/iosApp/Diagnostics`, `scripts/build-ios-ipa.sh`, `scripts/ci`. No `PlaybackPriority` path is in the upstream diff.

## 2. Overlap and decisions

Overlap means a path both sides changed relative to `0.6.10-beta`.

- Upstream: 241 paths (`git diff --name-only 0.6.10-beta 1.0.0`)
- This fork: 1174 paths (`git diff --name-only 0.6.10-beta origin/enhanced`)
- Intersection: **109 paths**

Each row is one decision. Paths not in the 109 are upstream-only and follow the same rule when they sit next to a feature we own.

| Area | What upstream changed | Decision |
| --- | --- | --- |
| Auto Sync | Android coordinator, engine controller, `AutoSyncMpvCoordinator`, contracts, preferences, run policy, settings rows. Those seven files on `enhanced` still match `0.6.10-beta`. Overlap is `PlayerRuntimeAutoSync.kt`. Upstream also defaults Auto Sync to on and allows enabling it with no preferred language. | Combine. Take the search-further scope (`SELECTED_THEN_SEARCH`) and the settings-row fix. Keep our default off (`MutableStateFlow(false)` and the persisted fallback `false`). In `PlayerRuntimeAutoSync.kt`, keep our call sites. Auto Sync must not start work on the playback lane or bypass the idle heat gate. |
| TheIntroDB | 0 files under `features/player/skip`. | Keep ours. |
| Segment analysis and season overview | 0 files under `features/analysis` and no `SeasonOverview` diff. | Keep ours, including the pause sentences and the four-icon key. |
| Downloads and the heat gate | 0 files under `features/downloads`. No `PlaybackPriority` diff. | Keep ours, byte for byte. A player-file conflict is not a reason to edit the gate. |
| Player and PiP | Overlap: `PlayerEngine.kt`, `PlayerEngine.android.kt`, `PlayerEngine.ios.kt`, `PlayerBridge.kt`, `PlayerControls.kt`, `PlayerControlActions.kt`, `FoldControls.kt`, `OpeningOverlay.kt`, `PlayerModels.kt`, `PlayerScreen.kt`, `PlayerScreenArgs.kt`, `PlayerScreenRuntime*.kt`, `PlayerSettingsRepository.kt`, `PlayerSettingsStorage.android.kt`, `PlayerSettingsStorage.ios.kt`, `PlayerStreamsRepository.kt`, `PlayerTimeline.kt`, `PlayerTrackSelection.kt`, `SeekPreviewController.kt`, `MetalLayer.swift`, `MPVPlayerBridge.swift`, `PlayerPictureInPictureController.swift`, plus the player tests in the 109. New: `PictureInPictureDolbyVisionConverter.swift`. | Combine. Take Dolby Vision PiP, HDR subtitle compositing, backing-scale rendering, chapters, HLS init, seek-preview pause, and error wording when they compile against our player. Keep our playback lane, stale playback flag, analysis pause, and next-episode behaviour exactly. PiP teardown stays on the main queue, as `0.6.10-beta` fixed. The Dolby converter does its frame work off the main actor and publishes the layer back on main. See Swift Concurrency below. Simple controls win over new panels. |
| MPVKit | Gitlink only, to `359a88534`. Our pin on `enhanced` is `7f40522ab`. | Keep our pin in the sync commit. Adopt `359a88534` only if a taken PiP or mpv source does not compile against `7f40522ab`. Adopting the pin does not rewrite the lane or the heat gate. |
| Home hero | Overlap: `HomeHeroSection.kt`, `HomeScreen.kt`, `HomeRepository.kt`, `PosterGrid.kt`, `BackdropFocus.kt`, `DetailHero.kt`, `TabletDetailHero.kt`, `ContentView.swift`. Upstream-only nearby: `HomeHeroMetaRepository.kt` (not in the 109). | Combine the catalog-load gate, startup recomposition cuts, mute-button placement, and backdrop focus. Keep our layout: artwork starts below the status inset, and that inset is applied once (`HomeInsets.kt`). Do not take a treatment that draws the picture under the Dynamic Island. The A/C follow-up is a separate change in the same build, after the sync (section 5). |
| Sync worker and telemetry | 0 files under `sync-worker`. | Keep ours, including `/telemetry`, `migrations/0002_telemetry.sql`, and `src/telemetry.ts`. Do not copy a placeholder database id or turn logs on. |
| Diagnostics | 0 files under `iosApp/iosApp/Diagnostics`. | Keep ours, including the behaviour log. |
| Build and release scripts | Overlap: `.github/workflows/update-store-source.yml`, `store.json`, `README.md`. Our `scripts/build-ios-ipa.sh` and `scripts/ci` are untouched upstream. | Keep our IPA scripts and `store.json` (the personal SideStore source). Take release-workflow ideas only when they do not publish, do not rewrite `store.json`, and do not change the IPA path. Combine `README.md`: keep our `### Sync` section and add upstream feature notes that are still true. |
| Versioning xcconfig | Overlap: `iosApp/Configuration/Version.xcconfig`. Ours is `CURRENT_PROJECT_VERSION = 224` and `MARKETING_VERSION=0.6.10.1`, plus empty telemetry placeholders. Theirs is `157` and `1.0.0` and drops the placeholders. | Keep ours, then set marketing `1.0.0.1` and build `225`. Leave the telemetry placeholders empty. Never commit a key. |
| Strings | 28 `values*/strings.xml` files are in the 109, including `values/strings.xml`. | Combine. Add new upstream keys. Do not drop our analysis, season-overview, or diagnostics strings. |
| Auth, profiles, sync badge | Overlap: `AuthRepository.kt`, `AuthStorage.android.kt`, `AuthStorage.ios.kt`, `LocalAccountDataCleaner.kt`, `ProfileSettingsSync.kt`, `ProfileRepository.kt`, `AppGate.kt`. Upstream-only: `AuthSessionErrors.kt`. | Combine. Take the session-refresh stay-signed-in fix and the stream-badge push. Keep our profile and account-cleaner behaviour where the hunks are ours. |
| Streams, debrid, Live TV, Continue Watching, Simkl, Discord | Overlap includes `StreamsRepository.kt`, `StreamsScreen.kt`, `StreamCard.kt`, `StreamModels.kt`, `ProviderFilterRow.kt`, `DirectDebridResolver.kt`, `LiveTvRepository.kt`, `ContinueWatchingPreferencesRepository.kt`, `ContinueWatchingSettingsPage.kt`, `WatchProgressModels.kt`, `SimklAuthRepository.kt`, `SimklRelatedRepository.kt`, `SimklPlatform.ios.kt`, `DiscordRpcRepository.kt`, `MetaDetailsRepository.kt`, `MetaDetailsScreen.kt`, `SearchScreen.kt`, `FolderDetailScreen.kt`, `MainAppContent.kt`, `PlayerDestination.kt`, `StreamDestination.kt`. | Take their bugfixes (debrid cache, tokenized links, Live TV buffering, Continue Watching dismiss, Simkl related titles). Keep our simple stream and details UI when both sides restyle the same composable. |
| Settings chrome | Overlap: `SettingsScreen.kt`, `SettingsComponents.kt`, `SettingsFullScreenPages.kt`, `SettingsSearch.kt`, `PlaybackInterfaceSettings.kt`, `PlaybackPlayerSettings.kt`, `Components.kt`, `LiquidGlass.kt`, `PlatformImageLoader.ios.kt`. | Keep our simple settings UI. Take new setting rows only for features we are taking (chapters, device subtitles). Do not adopt a new glass treatment. The SwiftUI skill says to adopt Liquid Glass only when someone asks. Nobody has asked. |
| Player subtitle restore test | `PlayerSubtitleRestoreTest.kt`, `PlayerTrackSelectionTest.kt`, `BackdropFocusTest.kt`. | Combine. Keep our assertions. Add upstream cases that still match the kept behaviour. |

## 3. Merge method, conflict hot spots, risks

Method, when implementation starts:

1. Branch from `enhanced` at `81572208`.
2. Make a merge commit whose first parent is that `enhanced` commit and whose second parent is `6c2ad793` (tag `1.0.0`). No rebase. No force-push.
3. Do not use `git merge --allow-unrelated-histories`. There is no merge base, so that merge would treat every shared path as add/add against an empty tree.
4. Use `0.6.10-beta` (`03536e36f`) as the content base. `git merge-tree 03536e36f HEAD 6c2ad793` is the dry run. Resolve with the table in section 2, then `git commit-tree` (or an equivalent index commit) with the two parents above. The result is a merge commit, not a squash and not a rewritten `enhanced`.
5. After the merge commit, in the same build, set `Version.xcconfig` to `1.0.0.1` and `225`, then do the Home hero follow-up in section 5.

Expected conflict hot spots, from the 109 and from the empty merge base:

- `PlayerScreen.kt` and the `PlayerScreenRuntime*` files. Both sides edited playback startup, streams, and overlays.
- `PlayerEngine.ios.kt`, `MPVPlayerBridge.swift`, `MetalLayer.swift`, `PlayerPictureInPictureController.swift`. PiP, scale, and our lane hooks land in the same functions.
- `HomeHeroSection.kt`, `HomeScreen.kt`, `ContentView.swift`. Insets versus upstream trailer and framing.
- `values/strings.xml` and the translated copies. Key additions on both sides.
- `Version.xcconfig`, `store.json`, `README.md`, `update-store-source.yml`.
- `PlayerRuntimeAutoSync.kt` and `PlayerSettingsStorage.ios.kt`.

Top risks:

1. A merge against the empty base drops or duplicates the playback lane and the idle heat gate. Those files are not in the upstream diff, so a correct 3-way merge leaves them untouched. An unrelated-histories merge does not.
2. The PiP and MPVKit combine changes playback timing or moves Dolby conversion onto the main thread. The lane and the heat gate must stay as they are even if the kit pin later moves to `359a88534`.
3. The hero merge draws artwork under the Dynamic Island, or applies the top inset twice (`HomeInsets.kt` already refuses the double add).
4. `Version.xcconfig` or `store.json` is resolved toward upstream, which resets the build to 157 and can publish the wrong SideStore source.
5. Auto Sync default-on, or a restored-subtitle search, starts analysis during playback and bypasses the pause gate. Default stays off. Analysis still waits on playback, heat, and Low Power.

## 4. How the sync will be verified

Nothing in this section has been run for the sync. Results of the future runs: not measured.

Host tests, from the repo root, after the merge and the hero follow-up:

- Analysis and the pause sentences: `AnalysisRunGateTest`.
- Playback lane and the heat gate: the existing playback-priority and download-gate host tests.
- Season overview: `SeasonOverviewListTest`, `SeasonOverviewTest`, `SeasonOverviewChipLayoutTest`.
- Hero insets and the new A/C switch: `HomeInsetsTest`, `HomeHeroFollowLayoutTest`, `HomeHeroSectionTest`, `HomeHeroBoundsTest`.
- Auto Sync policy tests that cover `SELECTED_THEN_SEARCH` and the default remaining off.

Sync worker, in `sync-worker/`: `npm test`. The worker sources should be unchanged. A green run confirms the merge did not touch them.

iOS CI: the existing pull-request workflow on the sync branch. Duration and result: not measured. The last measured iOS CI on current `enhanced` is run `44051970087` for `5811e1805`, success, 32 minutes 15 seconds. That number is the pre-sync tree, not a before/after of this work.

Sim gates, one pass per changed screen on the iPhone simulator, including an AX5 text size pass where the screen has a new control:

- Home, for the hero inset and the A/C toggle.
- Player, including PiP start and stop, without Dolby hardware.
- Streams, details, Continue Watching, Live TV, and Settings (the new hero toggle and any taken rows).
- Season overview and an analysis pause line, to confirm the merge left them alone.

Real device, left for Simsalabim. Not measured here:

- Idle heat gate and the playback lane under serious and critical thermal state, including Low Power Mode.
- Dolby Vision Picture in Picture and HDR subtitles in PiP.
- Home hero under the Dynamic Island: art starts below the island for both A and C, in portrait and landscape.
- Battery and jetsam during a long play, if the MPVKit pin moves.

Name and secret scan before the sync pull request: no owner legal name, no ingest key, no enroll token. The telemetry placeholders in `Version.xcconfig` stay empty.

## 5. Home hero follow-up, same build, after the sync

One component. `HomeHeroSection` already draws the picture. Do not add a second hero.

- A, soft fade, is the default. The artwork fades into the page at the bottom. The picture starts at the window's top safe inset, which on current iPhones is below the Dynamic Island. Side edges stay slightly inset so the fade reads as a card of art, not a second component.
- C, full width, is a Settings switch, off by default. Same component, same top inset, no side inset, same bottom fade. The switch does not change the card style that already exists (`HomeHeroStyle.CARD`).
- The top inset is applied once. `homeContentTopInsetDp` already takes the larger of the status bar and the window top and does not add them twice.

Files:

- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/HomeHeroSection.kt` — the only hero body. A parameter selects fade inset versus full width.
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeInsets.kt` — art top stays the single safe top inset.
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeCatalogSettingsRepository.kt` — persist the switch, default A.
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/HomescreenSettingsPage.kt` — the toggle next to the existing hero style control.
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeScreen.kt` — pass the setting through. Do not add another crossfade around the hero.
- `composeApp/src/commonMain/composeResources/values/strings.xml` — one title and one description. Other locales fall back.
- Tests: `HomeInsetsTest.kt`, `HomeHeroFollowLayoutTest.kt`, `HomeHeroSectionTest.kt`. Assert the default is A, C is full width, and both start at the safe top.
- `iosApp/iosApp/ContentView.swift` only if the host still adds its own top pad. It must not.

Skills to follow when that follow-up is written:

- SwiftUI Expert (`.agents/skills/swiftui-expert-skill/SKILL.md`), for the host chrome only. The hero itself is Compose. Read the safe area nearest the view that consumes it. Do not read `UIScreen`. Do not adopt Liquid Glass. Keep Dynamic Type on the new Settings row (`lineLimit` and a wrap that does not break one letter per line). The skill does not apply to Compose.
- Swift Concurrency (`.agents/skills/swift-concurrency/SKILL.md`), for any iOS host change and for the PiP work in the sync. The app target is Swift language version 5.0. Do not assume the Xcode 26 defaults (`SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`, approachable concurrency). Do not mark types `@MainActor` unless the work is UI-bound. Frame conversion waits off the main actor. Layer and PiP teardown hop back to the main actor.

The follow-up does not touch the playback lane, the heat gate, analysis, or the season overview.

## Adversarial review

Reviewed against a fetch of `luqmanfadlli/NuvioMobile-Enhanced` tags `1.0.0` (`6c2ad7937171965608d05005080b96ca19345379`) and `0.6.10-beta` (`03536e36f34daacac7fb27d7e12276cd7bf410d4`), compared with `81572208faeaf871239a1a3087f250d5cf3d630c`. No merge commit was created. Verdict: **ready after the listed fixes**. The content base, the untouched-directory claims, the 109-path count, and the version target are right. The merge commands are not executable as written, and a clean merge already violates two keep-decisions that protect playback.

These measurements in section 1 are true: tag subjects and shas, no `v1.0.0` tag, `0.6.10-beta` is an ancestor of `1.0.0` and not an ancestor of `81572208`, `0.6.10-beta..1.0.0` is 145 commits and 241 files (+12007 / −1263), `git diff --name-only` from the base to `81572208` is 1174 paths, the intersection is 109, and there are no renames (`git diff --find-renames`). Upstream's diff touches none of `features/analysis`, `features/downloads`, `features/player/skip`, `SeasonOverview`, `PlaybackPriority`, `sync-worker`, `iosApp/iosApp/Diagnostics`, `scripts/build-ios-ipa.sh`, or `scripts/ci`. Dolby converter is 410 lines. Idle-gate numbers in the rules match `DownloadGateDecisionTest.idleHeatTable` and `NEXT_UP_DUTY_ON_MS` / `NEXT_UP_DUTY_OFF_MS` (30s/30s) on `81572208`.

### Blocker — the written merge is not a commit, and a plain merge uses the wrong base

`git merge-base 81572208 6c2ad793` is `313ca3d15c3a31d1d1b456fbbb09d6c31711b8b2` (`chore(store): publish 0.6.5-beta`, 2026-10-27), an ancestor of both sides and of `03536e36`. Histories are related. `git rev-list --count 81572208..6c2ad793` is **552**, not 4169. 4169 is `git rev-list --count 6c2ad793`, every commit reachable from the tag. `9d5d91c86724cb81dda50ce1e23a6244ff947f68` still has second parent `03536e36`, and that merge is not an ancestor of `81572208`, which is why the tag is the content base even though git will not choose it.

`git merge 6c2ad793` will run. It will not ask for `--allow-unrelated-histories`. It will use `313ca3d15`. That merge conflicts files the plan says upstream did not touch, including `DownloadsRepository.kt`, `SkipIntroRepository.kt`, and `SubmitIntroDialog.kt` (`git merge-tree --write-tree 81572208 6c2ad793`, 164 conflicts). Resolving those toward upstream drops TheIntroDB and the download gate. Section 3's "empty merge base" and the 4169 count have to be replaced with this.

The documented dry run is the trivial form. On git 2.43, `git merge-tree 03536e36 81572208 6c2ad793` exits 0 and prints "changed in both" diffs (35722 lines). It does not print a tree oid, so `git commit-tree` cannot consume it. `git read-tree -m 03536e36 81572208 6c2ad793` leaves all 109 overlap paths unmerged. It also leaves 13 files unmerged with only stages 1 and 3. We deleted them, and upstream did not change them: seven `.github/workflows/*` issue workflows, `ProfileAvatarBorderColorTest.kt`, `NetworkOfflineCard.kt`, `AudioTrackModal.kt`, `IosVideoSettingsModal.kt`, `SubtitleModal.kt`, `SubtitleSyncByEarCard.kt`. Taking "theirs" there puts the deleted player panels back. `git merge-tree --write-tree --merge-base=03536e36` keeps those 13 deleted.

`git merge-tree --write-tree --merge-base=03536e36 81572208 6c2ad793` exits 1 and prints tree `22effb5255789d88be216db486f3b99f35764b88`. All 42 conflicted blobs in that tree contain `<<<<<<<`. `git commit-tree` of that oid would commit the markers, including into `Version.xcconfig` and `store.json`.

Fix the plan to these commands, and to no others:

```bash
BASE=03536e36f34daacac7fb27d7e12276cd7bf410d4
OURS=81572208faeaf871239a1a3087f250d5cf3d630c
THEIRS=6c2ad7937171965608d05005080b96ca19345379

# Dry run only. Exit 1 means conflicts. Do not commit the printed tree.
git merge-tree --write-tree --messages --merge-base="$BASE" "$OURS" "$THEIRS"

# Index with an explicit base, then a per-file content merge.
GIT_INDEX_FILE=.git/sync-1.0.0-index
git read-tree -m "$BASE" "$OURS" "$THEIRS"
# in a detached worktree at $OURS, using that index:
git merge-index -o git-merge-file -a

# After the hand resolutions below:
test -z "$(git grep -l -e '<<<<<<<' --cached)"
TREE=$(git write-tree)
NEW=$(git commit-tree "$TREE" -p "$OURS" -p "$THEIRS" \
  -m "Merge upstream 1.0.0 using 0.6.10-beta as the content base")
```

Point the sync branch at `$NEW` with a fast-forward. That is a merge commit with both parents and does not need a force-push.

Checked against a drop of our work: with the explicit base, all 1065 paths that only we changed match `81572208` (0 missing, 0 mismatched blobs in the `read-tree` index). The ort tree leaves `features/analysis`, `features/downloads`, `features/player/skip`, `SeasonOverview`, `sync-worker`, `Diagnostics`, `scripts/build-ios-ipa.sh`, and `scripts/ci` identical to `81572208`. In the 67 overlap paths that ort merges clean, every added line of ours is still present (0 files lost an added line, 0 files had a deletion of ours restored). `PlayerEngine.ios.kt` still contains `PlaybackPriority.sessionStarted`, `noteSnapshot`, and `sessionEnded` (1 each; upstream has 0). The lane files are safe only when the base argument is `03536e36`.

### Blocker — the same dry run already takes the MPVKit pin and turns Auto Sync on

`git merge-tree 03536e36 81572208 6c2ad793` prints:

```text
merged
  result 160000 359a885342d4eeda757f8e1d2c24fb01fe6d39c2 MPVKit
  our    160000 7f40522ab9a18c7f14c04316f71c7dd47e2a7e05 MPVKit
```

`MPVKit` is not in the 109 because `81572208` still matches `03536e36` (`7f40522ab`). A 3-way merge therefore takes `359a88534` with no conflict. The ort tree does the same. The "keep our pin unless a taken PiP source does not compile" step never runs. Fix: after the merge, `git update-index --cacheinfo 160000,7f40522ab9a18c7f14c04316f71c7dd47e2a7e05,MPVKit`, compile the taken Swift against that pin, and move the gitlink only if that compile fails. Add a gate that fails the sync commit if `git ls-tree HEAD MPVKit` is `359a88534` and the compile-failure note is absent.

The same dry run prints `AutoSyncPreferencesRepository.kt` as merged to blob `c3eb876847bcf9985bf1798a0703787ee0649eb5` (theirs). `81572208` matches the base (`5ece5c860`). Upstream `a6065c8b1` changes both the initial value and the persisted fallback:

```text
MutableStateFlow(false) -> MutableStateFlow(true)
loadPersistedValue?.invoke() ?: false -> ?: true
```

Those seven Auto Sync files are upstream-only, so they do not conflict. `AutoSyncMpvCoordinator.kt` at `6c2ad793` has no reference to `PlaybackPriority`, `DownloadGate`, or `AnalysisThermal`. With the default on, `setSubtitleUriWithRestoredAutoSync` (`SELECTED_THEN_SEARCH`) starts that coordinator during playback. The call-site edit is itself a clean merge of `PlayerRuntimeAutoSync.kt` (ours `setSubtitleUriWithSelectedAutoSync`, result `setSubtitleUriWithRestoredAutoSync`). iOS stays off that path only while `PlayerEngine.ios.kt` does not construct `AutoSyncMpvEngineController` (`IosAutoSyncCallSiteTest` on `81572208`). Android does wrap the controller. Fix: put the two `false` lines back after the merge, keep `SELECTED_THEN_SEARCH`, and add a host test that the in-memory default and the persisted fallback are `false` and that a false preference does not call `coordinator.start`. Do not treat "resolve the 42 conflicts" as covering this file.

### Major — clean overlap merges adopt behavior the table rejects

Ort conflicts 42 of the 109 and clean-merges 67. These hot spots in section 3 do **not** conflict: `PlayerEngine.ios.kt`, `PlayerPictureInPictureController.swift`, `PlayerRuntimeAutoSync.kt`, `ContentView.swift`, `values/strings.xml`, `update-store-source.yml`. A pass that only edits conflict markers will not see them.

- `ProviderFilterRow.kt` and `StreamCard.kt` clean-merge `staticFromBackdrop = true`. `StreamsScreen.kt` conflicts and their side also sets it (lines 639 and 1604 of the conflicted blob). `81572208` has no `staticFromBackdrop`. The settings row says not to adopt a new glass treatment. Fix: revert those arguments; leave the new default-false parameter in `LiquidGlass.kt` if it stays unused.
- `SubtitleAudioModels.kt` is upstream-only. The same commit `a6065c8b1` sets `useForcedSubtitles` from `false` to `true`. The dry run merges that blob (`01186883a`). The table never mentions it. Fix: keep `false` unless someone explicitly wants forced subtitles on.
- `update-store-source.yml` clean-merges one line, `if: github.event_name != 'release' || !github.event.release.prerelease`. That changes when `store.json` is published. The table allows workflow edits only when they do not publish and do not rewrite `store.json`. Fix: do not take that line unless a prerelease skip is an explicit decision.
- `PlayerPictureInPictureController.swift` clean-merges a new `submit(_:pts:isPictureInPictureFrame:doviMetadata:)` and calls `PictureInPictureDolbyVisionConverter.convert` on `frameQueue`. `MPVPlayerBridge.swift` **does** conflict. Resolving the bridge to ours fails to compile against the new `submit`. Below iOS 17 the same file sets `frameQueue = .main` (init in `6c2ad793`), so the converter runs on the main queue. Store minimum is 16.1. Fix: the bridge resolution must pass the new argument or the controller signature stays source-compatible; Dolby conversion stays off `.main` on every OS we ship, including the pre-iOS 17 branch.
- `PlayerBridge.kt` clean-merges `getIsOpening()`. No Swift file on `81572208` implements it. `MPVPlayerBridge.swift` is the conflict that has to grow that method, or the iOS export breaks. The plan's verification never mentions the Swift protocol. The 0.6.10 review did.

`values/strings.xml` clean-merges and keeps our added lines, including the analysis and season-overview copy. The eight locale files that conflict (`values-ar`, `values-bn`, `values-fr`, `values-he`, `values-id`, `values-in`, `values-sk`, `values-vi`) still need a key union so translated copies of our strings are not replaced by theirs.

### Major — two crash fixes are easy to reject, and the auth fix sits in a 94-line conflict

- `b147e233a` (lighter mpv setup under the jetsam limit) is +17/−3 in `MPVPlayerBridge.swift`, which conflicts. The take list names backing scale, Dolby, HDR, chapters, HLS, seek preview, and error wording. It does not name this hunk. Keeping "our" bridge to protect the lane drops it.
- `1b60c0a94` (free memory before playback) is +11/−2 in `PlatformImageLoader.ios.kt`, also a conflict. The table lists that path under "Keep our simple settings UI". The delta is the memory trim, so a literal keep-ours drops a jetsam fix.
- `daa966d36` (stay signed in when session refresh fails) rewrites `AuthRepository.kt` (+94/−45 in that commit's stat). That file conflicts. `AuthSessionErrors.kt` is upstream-only and will be added even if the repository is resolved to ours, which leaves the fix dead. `AuthStorage.android.kt` and `AuthStorage.ios.kt` merge clean. Fix: the repository resolution keeps their refresh-failure path. Add a host test that a failed refresh does not clear the session.

Device-subtitle migration in `PlayerSettingsStorage.ios.kt` (+131/−36) is a clean merge and is the data-preserving half of `d65fa7ab6`. Reverting that file to ours because it is "our storage" drops `migrateLegacySubtitlePreferencesToDeviceStore`. The plan should say that actual stays.

### Major — version and SideStore facts are partly wrong

`Version.xcconfig` and `store.json` are the only version-bearing paths in `git diff --name-only 03536e36 6c2ad793`. Both conflict, so a careful resolution can keep ours. Their xcconfig is `CURRENT_PROJECT_VERSION=157` and `MARKETING_VERSION=1.0.0` and has no telemetry placeholders. Ours at `81572208` is `224`, `0.6.10.1`, and empty `NUVIO_TELEMETRY_INGEST_KEY` / `NUVIO_TELEMETRY_BASE_URL`. Gradle reads the two version keys from that file. `project.pbxproj` is ours-only and does not override them. The widget plist uses `$(CURRENT_PROJECT_VERSION)`. Build `225` is not in `store.json` or the xcconfig. The four-part form matches `update-store-source.yml`'s `^[0-9]+(\.[0-9]+){2,3}(-beta)?$`.

Their `store.json` does **not** reset the build to 157. `versions[0]` at `6c2ad793` is marketing `0.6.13`, build `156`, download host `github.com/luqmanfadlli/NuvioMobile-Enhanced/`. There is no `1.0.0` row. Ours `versions[0]` is `0.6.5.1` build `198` on `github.com/SimSalabimse/NuvioMobile-Enhanced/` (`scripts/update-store-source.py` installs `versions[0]` and drops any row whose download URL is not that host). A JSON union sorts `0.6.13` above `0.6.5.1` and would offer their IPA from our source. Fix: keep `store.json` from `81572208` byte for byte in the sync commit. Do not union the arrays. Let the existing release workflow prepend `1.0.0.1` / `225` only after an IPA exists, and do not take the prerelease guard above. The source listing is already behind the app (224 is not in the file); that is not a reason to import upstream rows.

### Major — verification does not lock the risks

Nothing in section 4 fails if the wrong merge base was used, if conflict markers were committed, if the MPVKit pin moved, if Auto Sync defaulted on, if `useForcedSubtitles` flipped, if `staticFromBackdrop` landed, or if `Version.xcconfig` became 157. `npm test` in `sync-worker/` does not prove the worker was untouched: a green run can follow an edit. The same is true of the playback host tests if a merge edits a path they do not assert.

Add these gates on the sync commit, before the hero follow-up:

- `git diff 81572208 -- features/analysis features/downloads features/player/skip sync-worker iosApp/iosApp/Diagnostics scripts/build-ios-ipa.sh scripts/ci` is empty, and `SeasonOverview` / `PlaybackPriority` blobs match.
- `git grep -l '<<<<<<<'` is empty.
- `git ls-tree HEAD MPVKit` is `7f40522ab` unless the compile note above exists.
- `AutoSyncPreferencesRepository` still has both `false` sites. `IosAutoSyncCallSiteTest` still passes.
- `useForcedSubtitles` default is `false`. `staticFromBackdrop = true` is absent.
- `Version.xcconfig` is `1.0.0.1` and `225`, telemetry lines empty. `store.json` matches `81572208`.
- New `NuvioPlayerBridge` methods (`getIsOpening`) exist on the Swift implementor.
- Host tests that already exist and should be named: `DownloadGateDecisionTest`, `AnalysisRunGateTest`, `IosAutoSyncCallSiteTest`, `SeasonOverviewListTest`, `SeasonOverviewTest`, `SeasonOverviewChipLayoutTest`, `HomeInsetsTest`, `HomeHeroSectionTest`, `HomeHeroBoundsTest`, `HomeHeroFollowLayoutTest`. The last one is already on `81572208`.
- One iOS compile of the PiP Swift against the kept MPVKit pin, then a second only if the pin has to move.

Run `44051970087` is real (success, "iOS simulator compile", title "Build 222: next episode stays ready", head `5811e1805dbda73592ff8539c168c8aca7eec9c3`). That object is not in this clone of `enhanced`, and the title is build 222, not 224. Keep it labeled as an older run. It is not a before-measurement of `81572208`.

There is still no host test for PiP teardown on the main queue, Dolby conversion off the main queue, or the idle gate under a real thermal state. Those stay device work. The source gates above are what stop the merge from reaching that device with the lane already rewritten.

### Minor

- Section 1's `ci(release)` stable/beta note is a commit subject (`70b7a6c5d` touches `android-release.yml` and `scripts/release-metadata.sh`). The net `03536e36..6c2ad793` diff of those paths is empty. The only workflow delta is the one prerelease line.
- `PictureInPictureSubtitleComposer.swift` already exists on `81572208` (177 lines at `6c2ad793`). It is upstream-only, so HDR compositing replaces the file with no conflict. Name it next to the Dolby converter so that replace is reviewed, not discovered at compile time.
- `ContentView.swift` clean-merges `.ignoresSafeArea(edges: [.top, .leading, .trailing])` on the toolbar fade, not on the hero. The Dynamic Island risk stays in the `HomeHeroSection.kt` conflict and in `HomeInsets.kt`, which upstream did not touch.

### What not to redesign

Keep `03536e36` as the content base, keep a merge commit with parents `81572208` and `6c2ad793`, keep the lane and the idle gate byte for byte, keep TheIntroDB and season overview, keep build `225` and marketing `1.0.0.1`, and keep our `store.json`. Replace the merge commands, add the post-merge restores for every clean take the table rejects, name the jetsam and auth hunks as takes, and add the gates above. That is a plan fix, not a new sync design.
