# Review of the 0.6.10 sync

Hostile pass over merge `9d5d91c86` (parents `2e76ccee1` and `03536e36f`). Comparisons below are `git diff 2e76ccee1` against the tree after the fixes in this change. iOS build, app size, and FPS: not measured.

## 1. The iOS embedded-subtitle claim was wrong

The merge note said iOS `EmbeddedSubtitleTimelineLoader.load` returns null and that this is what iOS already did. That is not what `2e76ccee1` did.

On `2e76ccee1` the loader lived only at `composeApp/src/androidMain/.../EmbeddedSubtitleTimelineLoader.kt`. There was no iOS file, no `expect`, and no call from `PlayerEngine.ios.kt`. `git grep EmbeddedSubtitle 2e76ccee1` hits Android Auto Sync only. iOS never entered `load`, so it did not return null. Returning null is a new function.

What iOS Auto Sync actually does, and still does, is audio energy, not an AVAsset subtitle-track read:

- `AVAssetAudioEnergyCapture.swift` builds an `AVURLAsset` and an `AVAssetReader` on the audio track, then emits RMS samples. It does not read `AVMediaSelection` legible tracks or subtitle samples.
- `MpvPcmEnergyCapture.swift` asks `SubtitleAutoSyncEngine.planPcmCapture` and `cacheDumpWins`.
- `SubtitleDriftCorrection.kt` calls `SubtitleAutoSyncEngine.computeOptimalOffset`.
- Norwegian subtitle choice is `SubtitleLanguageMatching` in `PlayerTrackSelection.kt`. The charset sniff still contains `norwegian`, `nynorsk`, `norsk`, `bokmal`, and `bokmål`.
- Startup de-dupe is `AutoSyncPreferencesRepository.claimStartupRun(hashCode(), activePlaybackIdentity)` in `PlayerRuntimeAutoSync.kt`.

`git diff 2e76ccee1` is empty for all of those files, and for `MPVAudioCaptureProcessor.swift`, `MpvSubtitleSync.swift`, and `MediaAnalyzer.swift`.

The merge did change the iOS call site. `PlayerEngine.ios.kt` stopped passing `controller` into `onControllerReady` and passed an `AutoSyncMpvEngineController` instead. That wrapper implements `AutoSyncPlayerController`, so `PlayerAutoSyncAdapter.setSubtitleUriWithAutoSync` no longer falls through to `setSubtitleUri`. It starts `AutoSyncMpvCoordinator`, which calls `EmbeddedSubtitleTimelineLoader.prefetch` and `AutomaticSubtitleSync.findTimelineRetime` with `allowLiveReference = false`. On iOS that load is the new stub, so the reference timeline is null and a confident result would replace the external subtitle through `replaceExternalSubtitle`. `MainViewController` also called `AutoSyncPreferencesIos.initialize()`, which `2e76ccee1` did not. The same edit declared `latestExternalSubtitles` a second time in one function. Android host tests do not compile `iosMain` (the iOS targets are disabled on this machine), so that duplicate would have failed on the Mac.

Restored:

- `onControllerReady` receives `controller` again.
- `MainViewController` does not call `AutoSyncPreferencesIos.initialize()`.
- `IosAutoSyncCallSiteTest` reads those two files and fails if the wrapper or the preference install comes back. It also fails if `PlayerEngine.ios.kt` mentions `EmbeddedSubtitleTimelineLoader`.

The iOS `load` stub remains, because the shared `expect` needs an `actual`. Nothing in the iOS player calls it. `replaceExternalSubtitle` stays on `NuvioPlayerBridge` so the Swift class still matches the protocol. The player does not call it.

Image-memory trim on the iOS surface (`trimImageMemoryForPlayback`, `collectAfterPlayback`) is unchanged by this fix. It is not a subtitle call.

## 2. Our behavior in the 14 conflict files and the 5 moved Auto Sync files

"Changed" means `git diff 2e76ccee1` is not empty. Intended means the approved plan asked for it, or the line is the same work with the declaration moved.

| File | What changed versus `2e76ccee1` | Intended |
| --- | --- | --- |
| `NuvioApplication.kt` | No diff. Poster client stays `performanceImageHttpClient`. | Yes. The SSL interceptor was added on that client, not by replacing it. |
| `EpisodeReleaseNotificationPlatform.android.kt` | The OkHttp engine now adds `NetworkErrorInterceptor`. | Yes. A `RuntimeException` whose cause is an `IOException` is rethrown as that `IOException`. Covered by `NetworkErrorInterceptorTest`. |
| `PlayerEngine.android.kt` | Caption reads go through `captioningFlagOrFalse` and `captioningValueOrNull`, which are the same `runCatching` the merge already had. Anime shader setup runs only when `animeContent` is true and the matching setting is true. Both settings default to false and have no settings row. Snapshot calls stay the named fork arguments, with no badge field. | The caption guard and the default-off shaders are the upstream player fixes the plan kept. The badge was not added. |
| `values-id/strings.xml` | No diff. | Yes. |
| `values-in/strings.xml` | No diff. | Yes. |
| `values-ja/strings.xml` | 595 string lines added, none removed. | Yes. New copy only. Existing Japanese strings were not rewritten. |
| `PlayerScreenModalHosts.kt` | No diff. | Yes. The audio, subtitle, video, and source panels, and the Anime4K panel, stayed out. |
| `PlayerScreenRuntimePlaybackActions.kt` | `submitMode` stays. `resetAddonSubtitlesIfSourceChanged()` is new and clears `SubtitleRepository` when `sourcePlaybackKey` changes. The auto-fetched subtitle flag moved from `activePlaybackKey` to `sourcePlaybackKey`, so an engine change keeps the fetched add-on subtitles. | Yes. `PlayerEngineSwitchTrackPreferenceTest.switchingEngineKeepsAddonSubtitlesForTheSameSource` fails if the flag still clears on an engine change. Putting that clear back on `activePlaybackKey` failed that test and was reverted. |
| `PlayerScreenRuntimeUi.kt` | Passes `animeContent`. The value latches true once `isAnimePlayback` is true and does not return to false for a later item on the same surface. | The parameter exists so the Android signature matches. Shaders still require a setting that defaults to false and has no switch. The latch does nothing visible while those settings stay false. |
| `ThemeSettingsRepository.kt` | `clearLocalState` again calls `applyLiquidGlassEffectsEnabled(true)`, as on `2e76ccee1`. `applyLiquidGlassEffectsEnabled` writes `LiquidGlassSettings.userChoice` because `enabled` is now a getter (`userChoice ?: !glassUiActive`). Load still uses `forkNavBarGlassMigration`. `onGlassUiActiveChanged` is new and is called from `AppBackgroundHost`. After a stored choice, the getter ignores `glassUiActive`. | The migration is the fork rule and was the conflict resolution. The `clearLocalState` rewrite was an automatic edit and is reverted. The getter change is required by the new `LiquidGlassSettings` type. An explicit choice still wins. The solid fallback in `LiquidGlass.kt` was put back, so an unsupported or off glass surface is not the new static glass layer. |
| `MainViewController.kt` | Back to `PerformanceMetrics.markProcessStart()` only. | Yes. See section 1. |
| `PlayerEngine.ios.kt` | Memory trim remains. The Auto Sync wrapper does not. | Yes. See section 1. |
| `Version.xcconfig` | `212` and `0.6.10.1`. The base head took `211`. | Yes. Marketing stays `0.6.10.1`. |
| `store.json` | No diff. | Yes. |
| `AutoSyncDelayPreflight.kt` | Moved from androidMain to commonMain. File body matches `2e76ccee1`. | Yes. |
| `AutoSyncSampledReferenceAligner.kt` | Same move, body matches. | Yes. |
| `AutomaticSubtitleSync.kt` | Moved to commonMain. The body swap is the clock (`autoSyncElapsedRealtimeMs`), `SynchronizedObject`, `AutoSyncLruCache`, and `AutoSyncTrackFlags`. The flag values match Media3: forced `2`, subtitle `128`, transcribes-dialog `4096`. Android still calls it. iOS playback does not. | Yes. The move is what lets one matcher compile for both targets. iOS must not start it, and after section 1 it does not. |
| `EmbeddedSubtitleTimelineLoader.kt` | Android body matches `2e76ccee1` except `actual` and the data class, which now sits on the `expect` file with the same fields. Defaults moved to the `expect`. iOS `load` returns null and is not called. | Android behavior is the same parser. The iOS stub is not a claim that iOS used to return null. |
| `SubtitleCharsetDetector.kt` | Moved to commonMain. Decoding goes through `autoSyncDecodeBytes` instead of `java.nio`. The Norwegian predicates are the same lines. | Yes. `SubtitleLanguageMatching`, which chooses Norwegian tracks, is unchanged. |

## 3. TheIntroDB, downloads, season overview, Home hero, diagnostics

`git diff 2e76ccee1` is empty for:

- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/skip/` (detection, submission, ledger, `SkipTimeInput` `HH:MM:SS` / `MM:SS`)
- `docs/segment-detection.md`
- `strings_theintrodb.xml`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/analysis/`
- `SmartDownloads.kt`, `SeriesSmartDownloadCount.kt`, `DownloadsSeasonOverview.kt`
- `SeasonOverview.kt`, `SeasonOverviewSheet.kt`
- `HomeHeroSection.kt`, `HomeHeroMetaRows.kt`, `HomeHeroMetaRepository.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/core/logging/` and `composeApp/src/androidMain/kotlin/com/nuvio/app/core/diagnostics/`
- `ExternalPlaybackProgress.kt` (private viewing still returns before writing progress)

`PlayerSettingsRepository.kt` gained only `androidAnimeUpscaleEnabled` and `androidAnimeLineSharpenEnabled`, both defaulting to false. `privateViewingEnabled` is the same field, the same default, and the same setter. `persistsProgress` still treats submit mode, a private session, and that switch as not persisting. Those lines are outside the diff hunks.

Upstream did not replace these paths.

After that check, `3a4a87765` landed on `cursor/batch12-build207-e33c` and was merged here. It updated three download host tests to the scheduler and delete-when-finished behavior already on the fork. `bd01771c8` then removed the previous-episode stretch: a short match of the previous episode stays on the quiet run, and sibling marks do not pull it to the next cut. `SegmentDetectionRulesTest.aShortTailMatchStopsAtTheQuietRun` keeps `shorttail__s1e8` at 2.0s–17.0s with `accepted-previous-ending`. A shared season sting can still extend. The same commit shows a user's own none as `Your mark`, and dismisses a season sheet whose frame does not fit after the player returns the window. `git diff 2e76ccee1 9d5d91c86` is empty for analysis, downloads, the skip package, the Home hero, and diagnostics, so the 0.6.10 merge did not write those lines.

## 4. Swift export

Upstream added two Kotlin members that Swift implements. Both are in `SwiftExportEntryPoints.requiredEntryPoints`, and the host generate task wrote them into `composeApp/build/generated/swift-export/entry-points.txt`:

| Symbol | Why Swift needs it |
| --- | --- |
| `function com.nuvio.app.features.player.NuvioPlayerBridge.replaceExternalSubtitle` | `MPVPlayerBridgeImpl` implements `NuvioPlayerBridge`. |
| `function com.nuvio.app.features.player.NuvioPlayerBridgeCreator.closeParkedPictureInPicture` | `MPVPlayerBridgeCreator` and the test factory implement `NuvioPlayerBridgeCreator`. |
| `NuvioPlayerBridgeFactory.closeParkedPictureInPicture` | Covered by the existing `callable com.nuvio.app.features.player.NuvioPlayerBridgeFactory.*`. |

No other new Swift-called Kotlin symbol showed up in the `PlayerBridge.kt` diff. The iOS player does not call `replaceExternalSubtitle` after section 1. The method stays on the protocol so the Swift implementation still typechecks.

## 5. Version 0.6.10.1 / 212

Build 211 is the base head `bd01771c8`. This branch is 212. Marketing stays `0.6.10.1`.

| Place | What it says |
| --- | --- |
| `iosApp/Configuration/Version.xcconfig` | `CURRENT_PROJECT_VERSION = 212`, `MARKETING_VERSION=0.6.10.1` |
| `iosApp/Configuration/Config.xcconfig` | `#include? "Version.xcconfig"` |
| `iosApp/iosApp.xcodeproj/project.pbxproj` | App configurations use `baseConfigurationReferenceRelativePath = Config.xcconfig`. They do not override the version. `GENERATE_INFOPLIST_FILE = YES` with `INFOPLIST_FILE = iosApp/Info.plist`, so the built short version and bundle version come from those settings. |
| `iosApp/iosApp/Info.plist` | Does not hardcode a version. That is the generated-plist setup above, not a second number. |
| `iosApp/DownloadsWidgetExtension/Info.plist` | `CFBundleShortVersionString` is `$(MARKETING_VERSION)`. `CFBundleVersion` is `$(CURRENT_PROJECT_VERSION)`. |
| `composeApp/build.gradle.kts` and `androidApp/build.gradle.kts` | Read `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` from `Version.xcconfig`. |
| Generated `AppVersionConfig.kt` from the host compile of this tree | `VERSION_NAME = "0.6.10.1"`, `VERSION_CODE = 212`. `GIT_REVISION` is the compile's short hash, not a version. |
| About | `RunningVersionLine` formats `AppVersionConfig.VERSION_NAME` and `VERSION_CODE` with `compose_about_version_format`. |
| `changelog.json` | Top release is `0.6.10.1`, build `212`. The `0.6.9.1` entry under it is the previous release note. |

`store.json` is still the pre-merge file. It is the store listing, not the app version.

## Fixes in this pass

1. iOS playback hands the plain controller to the session and does not install Auto Sync preferences at launch.
2. `clearLocalState` forces liquid-glass effects on before load, as it did on `2e76ccee1`.
3. `IosAutoSyncCallSiteTest` locks the iOS call site on the host.

The auto-fetched subtitle flag stays on `sourcePlaybackKey`. Moving it back onto `activePlaybackKey` failed `switchingEngineKeepsAddonSubtitlesForTheSameSource`.

## Host suite after these fixes

Machine: Linux 6.12.113+ x86_64. Commit measured: `be22f53b2` (the Part H merge). The lines in this section were added after those runs and do not change the suite. Android SDK: `/home/ubuntu/android-sdk`. iOS build, app size, and FPS: not measured.

Command:

`./gradlew :composeApp:testAndroidHostTest --no-daemon --console=plain -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8" -Pkotlin.daemon.jvmargs="-Xmx6g -XX:MaxMetaspaceSize=1g"`

A green `testAndroidHostTest` is restored from the build cache, so runs 2 and 3 deleted the test-result directory and added `--no-build-cache`. Compile tasks stayed up to date. The counts come from the JUnit XML (`tests`, `failures`, `skipped`), because a successful run does not print the summary line.

| Run | Wall clock | Exit | Tests | Failed | Skipped | Log |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 4m 20s | 0 | 3130 | 0 | 8 | `/opt/cursor/artifacts/sync-0610-review/run-1.log` |
| 2 | 1m 16s | 0 | 3130 | 0 | 8 | `/opt/cursor/artifacts/sync-0610-review/run-2.log` |
| 3 | 1m 17s | 0 | 3130 | 0 | 8 | `/opt/cursor/artifacts/sync-0610-review/run-3.log` |

Median: 3130 tests, 0 failed, 8 skipped. Wall-clock median: 1m 17s.

The eight skips are `PlayerNowPlayingServiceTest` on SDK 26 and 34, the same four tests, because the foreground-service flag is false on this host. Before this review the suite on `9d5d91c86` was 3127 tests, 7 failed, 8 skipped. The three new tests are `IosAutoSyncCallSiteTest` (two) and `SegmentDetectionRulesTest.aShortTailMatchReachesTheFollowingCut` from Part H. The seven failures were the download host tests Part H rewrote to the current scheduler and delete-when-finished behavior. They pass now.

## Host suite on build 212

Machine: Linux 6.12.113+ x86_64. Commit measured: `5aa5b5d49`. Android SDK: `/home/ubuntu/android-sdk`. iOS build, app size, and FPS: not measured. No IPA.

Same command as above. Runs 2 and 3 deleted the test-result directory and added `--no-build-cache` so a green task was not restored from cache. Counts are the JUnit XML totals.

| Run | Wall clock | Exit | Tests | Failed | Skipped | Log |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 4m 50s | 0 | 3135 | 0 | 8 | `/opt/cursor/artifacts/sync-0610-build212/run-1.log` |
| 2 | 1m 20s | 0 | 3135 | 0 | 8 | `/opt/cursor/artifacts/sync-0610-build212/run-2.log` |
| 3 | 1m 15s | 0 | 3135 | 0 | 8 | `/opt/cursor/artifacts/sync-0610-build212/run-3.log` |

Median: 3135 tests, 0 failed, 8 skipped. Wall-clock median: 1m 20s. `aShortTailMatchStopsAtTheQuietRun` passed. The eight skips are the same now-playing tests.
