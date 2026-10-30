# Nuvio Enhanced iOS verification map

This directory is the maintained source for verifying the user-facing iOS app. Read this index, then use the matching feature file.

## Baseline preconditions

- Run from a Mac with Xcode. The Debug bundle id is `com.nuvio.app`. The product is `Nuvio Enhanced.app`.
- Start with `.cursor/skills/verify-nuvio-ios/scripts/launch.sh`. It builds, installs, seeds a local profile named `Verify`, marks What's New seen for the built version, and clears the one-time Diagnostics intro flag.
- Ready means `build/verify-nuvio-ios/evidence/readiness.txt` contains `signal=[Nuvio] app ready true` and `.cursor/skills/verify-nuvio-ios/scripts/doctor.sh` prints `doctor: ok`.
- Evidence goes to `build/verify-nuvio-ios/evidence/` and must still be there after `.cursor/skills/verify-nuvio-ios/scripts/cleanup.sh`.
- One simulator hosts one verification session. Do not drive an app this session did not launch.
- There is no debrid account and no sample stream in this harness.

## Driving conventions

- Start every recipe from the baseline state unless its preconditions say otherwise.
- Prefer accessibility identifiers, then accessibility labels. Do not tap coordinates.
- The Diagnostics intro is `VerifyUITests/DiagnosticsIntroUITest`. Other recipes use the same XCUITest queries against the labels in the feature file.
- `xcrun simctl` launches, terminates, screenshots, and reads logs. It does not tap.
- Do not satisfy a step by writing preferences or calling a Kotlin setter. Seeded preferences only get the app to the home shell.
- Restore nothing by deleting evidence. Cleanup may uninstall the simulator app this run installed.

## Proof and skip reporting

- Capture the action and the resulting state.
- UI proof is a screenshot plus the accessibility hierarchy.
- Mutation proof is a second read of the stored value (the container plist or a file under the app container).
- Record the feature id in `feature.txt`.
- An unreachable path is a failure. Name the command and the missing precondition. Do not mark it verified through a different screen.

## Feature entry contract

Each feature file has an H1, one paragraph, then these H2 sections in order: `Sub-features`, `How to get to it (user POV)`, `Driving it with verify-nuvio-ios`, `Gotchas`.

## Features

- [Diagnostics](./diagnostics.md) covers the one-time Diagnostics log sheet, the default-on toggle, and Export and Clear on the Diagnostics page.
- [Performance optimizations](./performance-optimizations.md) covers the nine Performance rows and their timing and memory readouts.
- [Cold launch to cached home](./cold-launch-cached-home.md) covers a quit and relaunch onto Home, including the cached-home launch readout.
- [Downloads](./downloads.md) covers the Library downloads screen and its empty state.
- [Playback skip intro](./playback-skip-intro.md) covers Skip Intro and Flag / Submit Timestamps for a Preview segment. It cannot be completed without a real stream.
