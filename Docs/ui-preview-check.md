# UI preview check

Screenshots and offline/online transition strips for the iOS Simulator. Layout tests do not catch a banner that is too tall, a clipped button, or a transition that cuts. This check takes pictures of the real screens.

Nothing here is visible in a normal launch. Release never reads the fixture argument.

## Run

On a Mac with Xcode, an iOS Simulator runtime, and ffmpeg:

```sh
./scripts/ui-shots.sh
```

That builds the Debug simulator app, boots one large phone and one small phone (`iPhone 18 Pro` and `iPhone 17e`), and writes PNGs under `build/ui-shots/<UTC timestamp>-<git short sha>/`.

Useful options:

```sh
./scripts/ui-shots.sh --dry-run
./scripts/ui-shots.sh --devices "iPhone 18 Pro,iPhone 17e" --states home-online,home-offline
./scripts/ui-shots.sh --skip-build --out ./build/ui-shots/again --settle 6
./scripts/ui-shots.sh --label home-banner --icloud
```

`--dry-run` prints the plan and the commands, then exits 0. It does not build or boot a simulator. A missing device name fails with the `xcrun simctl list devices available` listing. Any real failure exits non-zero and names the step.

`--icloud` copies the folder to `~/Library/Mobile Documents/com~apple~CloudDocs/SL/Nuvio/ui-shots/<label>/`.

Each device gets:

- `<device>/<state>.png` for every requested state
- `<device>/transition.mp4` of `home-offline` going offline, online at 2s, offline at 5s
- `<device>/transition-online.png` and `<device>/transition-offline.png`, contact sheets of those windows
- `index.md` and `commands.txt` listing the files and the commands

The status bar is fixed at 9:41, charged, 100%, four cellular bars, three Wi-Fi bars. JDK 17 is used when `JAVA_HOME` is unset and Homebrew OpenJDK 17 is installed.

## States

| Name | What it shows |
| --- | --- |
| `home-online` | Hero carousel (four titles) plus Trending, New releases, and Continue Watching. Harbor Lane is 42% watched. |
| `home-offline` | Offline banner and the downloads cover (saved episodes and a movie). |
| `details-online` | Series Harbor Lane, three seasons, episodes. |
| `details-offline` | Same series while offline. |
| `details-movie-offline` | Movie Paper Kite while offline. |
| `downloads` | A few downloads, including a very long title. |
| `long-titles` | Home rows mixing that long title with short ones. |
| `smart-downloads` | Harbor Lane details. The Finish this series chip is on, the goal status is "Keeping 2 ready", and the keep-ready count is 2. Seasons have no posters, so the season row is the text chips (Season 1, Season 2, Season 3). |

Posters are generated gradients (`fixture://` SVG). Titles are synthetic. There are no addon URLs, account URLs, keys, or personal names.

The connectivity file is `Documents/nuvio-ui-connectivity.txt` (`online` or `offline`). A debug build polls it about every 300 ms and feeds the same connection watcher the real path monitor uses, including the immediate offline drop and the probe that runs when the path comes back. The script writes that file with `xcrun simctl get_app_container <udid> <bundle id> data`.

## Release gate

A fixture is active only when both are true:

1. The binary is a debug binary. On iOS that is `kotlin.native.Platform.isDebugBinary`. The Android actual never enables fixtures.
2. The process has `-NuvioUiFixture <name>` or a non-blank `NUVIO_UI_FIXTURE`.

`decideUiFixture` returns `disabled` before it calls the reader when the binary is not debug. Release therefore does not read arguments or the environment. The Xcode Debug configuration embeds the debug Kotlin framework, so `isDebugBinary` is true there. Release embeds the release framework, so the reader is not called.

`UiFixturesTest.gateStaysDisabledAndDoesNotReadWhenTheBinaryIsNotDebug` asserts the reader is not invoked and the status is `disabled`.

While a fixture is active the app installs that state into the home, details, downloads, watch-progress, and smart-download holders, skips sign-in, and does not start addon or account network work. The offline banner and the home cover still follow `NetworkStatusRepository`.

## Add a state

1. Add a `UiFixture` entry and its `wireName` in `composeApp/src/commonMain/kotlin/com/nuvio/app/core/uifixture/UiFixtures.kt`.
2. Teach `UiFixtureCatalog.install` to fill the same repositories the screen already reads.
3. Append the wire name to `ALL_STATES` at the top of `scripts/ui-shots.sh`.
4. Extend `UiFixturesTest` so the new name parses.

Keep artwork on `fixture://`. Do not put real hosts, keys, or account data in the catalog.

## Reuse from another iOS app

InkList can reuse `scripts/ui-shots.sh`. The Nuvio-specific values are the variables at the top of the script:

- `SCHEME` (`iosApp`)
- `PROJECT_REL` (`iosApp/iosApp.xcodeproj`)
- `CONFIGURATION` (`Debug`)
- `IOS_DISTRIBUTION` (default `full`, from `NUVIO_IOS_DISTRIBUTION`)
- `BUNDLE_ID` (starting guess `com.nuvio.app`; the script then reads `CFBundleIdentifier` from the built app)
- `FIXTURE_ARG` (`-NuvioUiFixture`)
- `CONNECTIVITY_FILE` (`nuvio-ui-connectivity.txt`)
- `ALL_STATES` and `DEFAULT_DEVICES`
- `TRANSITION_FIXTURE` and the toggle times

The script builds with `xcodebuild -scheme "$SCHEME" -configuration Debug -destination id=<udid>`, installs that `.app`, and launches with `"$FIXTURE_ARG" <state>`. The other app needs the same launch argument, a debug-only gate, and a Documents file the script can write. The Gradle embed task and the fixture catalog stay in the app.

## Later

XCUITest snapshot tests are not part of this check. They can sit on top of the same fixtures once the screenshots show the layouts we care about.
