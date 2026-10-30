---
name: verify-nuvio-ios
description: Drive the Nuvio Enhanced iOS app (Kotlin Multiplatform Compose inside the SwiftUI host in iosApp/) on the iOS Simulator the way a user does, and save screenshots, logs, and side effects. Use when a build needs the same iOS check, or when proving Diagnostics, Performance, home launch, downloads, or playback on the simulator.
---

# Verify Nuvio Enhanced on the iOS Simulator

The primary surface is the iOS app. Android and the sideload IPA are separate. One simulator shares one app container: do not start a second verification session against the same simulator. `launch.sh` exits if the session it recorded is still running.

Build, install, preference seeding, and launch come from `scripts/release/smoke-ios-simulator.sh` (the build-196 simulator smoke). That script does not open `nuvio://` links. The in-app `SMOKE_DEEPLINK` hooks are not in this tree. Taps go through XCUITest on accessibility identifiers and labels.

The app bundle id in the Debug build is `com.nuvio.app`. The product name is `Nuvio Enhanced.app`. Version and build come from `iosApp/Configuration/Version.xcconfig`.

## Launch

From the repository root, on a Mac with Xcode:

```bash
.cursor/skills/verify-nuvio-ios/scripts/launch.sh
```

What it does:

1. Creates a gitignored `local.properties` stub when that file is missing, using the same public client URL the sideload workflow uses. It does not print secret values.
2. Runs `scripts/prepare-ios-dependencies.sh`.
3. Runs `scripts/release/smoke-ios-simulator.sh`, which:
   - boots an iPhone simulator if none is booted (preferred name `iPhone 17 Pro`, otherwise the first available iPhone)
   - builds Debug `iosApp` for that simulator into `build/ios-derived-full-simulator` with ad-hoc signing (`CODE_SIGN_IDENTITY=-`)
   - uninstalls and installs `com.nuvio.app`
   - launches once to create the data container, terminates, then runs `scripts/release/seed-simulator-prefs.sh`
   - launches again and waits until the unified log contains `[Nuvio] app ready true`

The seed writes the preferences the app already reads: `anonymous_user_id`, `profile_payload` (one profile named `Verify`, remember-last-profile on), and `whats_new_last_seen_v1` set to the built `version(build)` so What's New does not cover the shell. It removes `NuvioDiagnosticsIntroSeen` so the one-time Diagnostics sheet can appear. Quit the app before seeding. A running process overwrites the container plist on exit.

Ready means the process is alive and `build/verify-nuvio-ios/evidence/readiness.txt` contains:

```text
signal=[Nuvio] app ready true
```

`launch.png` in that directory is the screen at launch, which may be the Diagnostics sheet on a fresh container. Teardown is `cleanup.sh` in Cleanup. Launch does not delete evidence.

Useful overrides: `SMOKE_SIMULATOR_UDID`, `SMOKE_SIMULATOR_NAME`, `SMOKE_SKIP_BUILD=1` (reuse the built app), `SMOKE_ANON_ID`.

## Doctor

After launch, before driving:

```bash
.cursor/skills/verify-nuvio-ios/scripts/doctor.sh
```

Read-only. It exits 0 only when the session simulator is booted, `com.nuvio.app` is the installed bundle, the installed short version and build match `Version.xcconfig`, and the process is running. It prints one `doctor: ok` line. It does not boot, tap, or write preferences.

## Drive

Drive with XCUITest (`iosApp/VerifyUITests`, scheme `VerifyNuvioIOS`) and `xcrun simctl` for install, launch, screenshots, and logs. Prefer the accessibility identifiers below. Labels are the fallback the test uses when an identifier is missing.

| Handle | What it is |
| --- | --- |
| `diagnostics.intro.title` | Text "Diagnostics log" on the one-time sheet |
| `diagnostics.intro.saveToggle` | Switch "Save diagnostics log" on that sheet. Default on (`value` 1) |
| `diagnostics.intro.done` | Toolbar button "Done" |
| `settings.diagnostics` | Settings root row "Diagnostics" / "On-device crash and error log" |
| `diagnostics.settings.saveToggle` | Switch "Save diagnostics log" on the Diagnostics page |
| `diagnostics.settings.export` | Button "Export log" |
| `diagnostics.settings.clear` | Button "Clear log" |
| Tab buttons `Home`, `Profile` (label is `Settings` until titles load) | Native tab bar |
| Tab button `Library`, then button `Downloads` | Downloads screen |
| Text "No downloads yet" | Empty downloads screen |
| Text "No Home content available" | Home when no catalog rows are available |

Proven command for the Diagnostics intro (toggle default on, turn off, turn on, Done, tab bar visible, defaults persisted):

```bash
.cursor/skills/verify-nuvio-ios/scripts/drive-diagnostics.sh
```

That script runs:

```bash
OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES xcodebuild test \
  -project iosApp/iosApp.xcodeproj \
  -scheme VerifyNuvioIOS \
  -configuration Debug \
  -destination "id=${SIMULATOR_UDID}" \
  -derivedDataPath build/ios-derived-full-simulator \
  -only-testing:VerifyUITests/DiagnosticsIntroUITest/testIntroSheetToggleDefaultsOnAndDismisses \
  CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=NO \
  CODE_SIGNING_ALLOWED=YES DEVELOPMENT_TEAM= ONLY_ACTIVE_ARCH=YES ARCHS=arm64
```

`drive-diagnostics.sh` terminates the app, reseeds preferences, and runs that test. The test relaunches and drives the sheet. It runs inside the simulator, so the screenshots are XCTest attachments. The script exports them into the evidence directory. Do not point this at an internal setter or a test-only URL.

Other features are mapped under `features/`. Their taps use the same identifiers and labels. Playback cannot be driven here without a real stream. See that feature's Gotchas.

## Evidence

Proof artifacts live in `build/verify-nuvio-ios/evidence/`. That directory is gitignored. Cleanup does not delete it.

A Diagnostics proof needs all of:

- `launch.png` and `readiness.txt` (`signal=[Nuvio] app ready true`)
- `diagnostics-intro-default-on.png` (sheet visible, toggle on)
- `diagnostics-intro-toggled-off.png` (same sheet, toggle off)
- `diagnostics-intro-dismissed.png` (sheet gone, tab bar up)
- `diagnostics-intro-hierarchy.txt` (accessibility tree)
- `side-effects.txt` with `NuvioDiagnosticsIntroSeen=true` and `NuvioDiagnosticsEnabled=true`
- `feature.txt` containing `diagnostics`

`side-effects.txt` is read from the app container after the test, not from the screenshot. `diagnostics.jsonl` is copied only when it exists and contains no debrid host. Text logs are scrubbed for debrid URLs, bearer tokens, and JWTs before they are left in the evidence directory. Do not put account links or secrets in this directory.

Screenshots have to show the action and the result (toggle on, toggle off, sheet dismissed), not only the last frame.

## Cleanup

```bash
.cursor/skills/verify-nuvio-ios/scripts/cleanup.sh
```

Reads `build/verify-nuvio-ios/session.env`. Terminates and uninstalls only that bundle id on that simulator. Shuts the simulator down only when this run booted it. Deletes the session file. Does not delete `build/verify-nuvio-ios/evidence/`. Does not kill by process name. Safe to run when no session exists.

After cleanup:

```bash
test ! -f build/verify-nuvio-ios/session.env
.cursor/skills/verify-nuvio-ios/scripts/confirm-evidence.sh
```

`confirm-evidence.sh` checks the Diagnostics proof files listed above are still non-empty.

## Helpers

| Script | Invocation |
| --- | --- |
| Launch the app | `.cursor/skills/verify-nuvio-ios/scripts/launch.sh` |
| Read-only health check | `.cursor/skills/verify-nuvio-ios/scripts/doctor.sh` |
| Drive the Diagnostics intro | `.cursor/skills/verify-nuvio-ios/scripts/drive-diagnostics.sh` |
| Tear down this session | `.cursor/skills/verify-nuvio-ios/scripts/cleanup.sh` |
| Check proof files after cleanup | `.cursor/skills/verify-nuvio-ios/scripts/confirm-evidence.sh` |
| Build, seed, and launch (used by launch.sh) | `scripts/release/smoke-ios-simulator.sh` |
| Seed profile and What's New prefs | `scripts/release/seed-simulator-prefs.sh <simulator-udid> <path-to-Nuvio-Enhanced.app>` |

`scripts/release/simulator-common.sh` is sourced by the scripts above. It is not a command.
The feature map is `features/README.md`.
