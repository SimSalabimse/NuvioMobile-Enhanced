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

A full walk of every screen is `./scripts/ui-shots.sh --all`. A pass limited to what changed is `./scripts/ui-shots.sh --changed <base-ref>`. Both are described under "Full walk". The command above, with no `--all` or `--changed`, is unchanged: it still shoots `ALL_STATES` and the long offline/online/offline transition.

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
| `player-submit-bar` | The real submit bar on a dark video frame, laid out at 844 dp wide (a standard landscape phone) and scaled to fit a portrait simulator. Preview is open. Intro and Recap are checked. Start and End are 0:00:00, so Submit is disabled. No preview, Play range, and close are on the bar. |
| `player-submit-bar@667` | Same bar, frame width 667 dp (small iPhone landscape). |
| `player-submit-bar@844` | Same bar, frame width 844 dp. |
| `player-submit-bar@932` | Same bar, frame width 932 dp (Pro Max landscape). |
| `player-submit-bar-long` | Same bar with long chip labels, Start 0:00:00 and End 1:23:45. Default frame width is 667 dp. `@dp` works here too, for example `player-submit-bar-long@932`. |

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
2. Teach `UiFixtureCatalog.install` to fill the same repositories the screen already reads. A screen that draws itself, such as the submit bar, can skip the repositories and return a null launch route.
3. Append the wire name to `ALL_STATES` at the top of `scripts/ui-shots.sh`. Width suffixes such as `player-submit-bar@667` are listed there too, because the script matches the launch string exactly.
4. Extend `UiFixturesTest` so the new name parses.

`player-submit-bar@<dp>` and `player-submit-bar-long@<dp>` set the frame width in dp. Any other fixture rejects an `@` suffix. The bar composable is `SegmentMarkingLayer`. Submit and close stay pinned; the chips scroll. The frame is scaled down so a portrait simulator shows the whole landscape width.

Keep artwork on `fixture://`. Do not put real hosts, keys, or account data in the catalog.

## Reuse from another iOS app

InkList can copy `scripts/ui-shots.sh`, `scripts/ui-manifest.sh`, and the manifest format in `scripts/ui-manifest.tsv`. The Nuvio-specific values are the variables at the top of `scripts/ui-shots.sh`:

- `SCHEME` (`iosApp`)
- `PROJECT_REL` (`iosApp/iosApp.xcodeproj`)
- `CONFIGURATION` (`Debug`)
- `IOS_DISTRIBUTION` (default `full`, from `NUVIO_IOS_DISTRIBUTION`)
- `BUNDLE_ID` (starting guess `com.nuvio.app`; the script then reads `CFBundleIdentifier` from the built app)
- `FIXTURE_ARG` (`-NuvioUiFixture`)
- `CONNECTIVITY_FILE` (`nuvio-ui-connectivity.txt`)
- `ACTION_FILE` (`nuvio-ui-action.txt`)
- `MANIFEST_REL` (`scripts/ui-manifest.tsv`)
- `DEVICE_TOKEN_LARGE`, `DEVICE_TOKEN_SMALL`, `DEVICE_TOKEN_LANDSCAPE`
- `ALL_STATES` and `DEFAULT_DEVICES`
- `TRANSITION_FIXTURE` and the toggle times

The script builds with `xcodebuild -scheme "$SCHEME" -configuration Debug -destination id=<udid>`, installs that `.app`, and launches with `"$FIXTURE_ARG" <state>`. The other app needs the same launch argument, a debug-only gate, and a Documents file the script can write. The Gradle embed task and the fixture catalog stay in the app. Point `MANIFEST_REL` at that app's own manifest; the columns stay the same.

## Full walk

`scripts/ui-manifest.tsv` is the list of screens. `./scripts/ui-shots.sh --all` shoots every still and strip row. `./scripts/ui-shots.sh --changed <base-ref>` runs `git diff --name-only <base-ref>...HEAD`, matches those paths against the globs, and shoots only the screens that match. `./scripts/ui-shots.sh --changed-files <file>` does the same match from a newline list of paths, which is what the self-test uses. `./scripts/ui-shots.sh --explain` prints the selection and exits. Each selected screen is printed as:

```text
[ui-shots] screen	<id>	<why>
```

An unmatched path is printed as `[ui-shots] unmatched	<path>`. When nothing matches, the script says `no screens selected` and does not boot a simulator.

One Debug build, then each distinct simulator is booted and installed once and reused. The run stops if it passes `NUVIO_UI_SHOTS_BUDGET_SECONDS` (default 1800, including the build). That cap is a stop, not a measured duration. On success the script deletes the mp4s it recorded and, unless `NUVIO_UI_SHOTS_DERIVED` was set, the derived data it created at `build/ios-ui-shots-derived`. It does not delete anything else. `--all` and `--changed` copy into `~/Library/Mobile Documents/com~apple~CloudDocs/SL/Nuvio/ui-shots/<label>/` unless `--no-icloud` is set. The legacy pass still copies only when `--icloud` is passed.

Device tokens in the manifest are `phone-large`, `phone-small`, and `landscape`. They map to `DEVICE_TOKEN_LARGE` (iPhone 18 Pro, portrait), `DEVICE_TOKEN_SMALL` (iPhone 17e, portrait), and `DEVICE_TOKEN_LANDSCAPE` (iPhone 18 Pro, landscapeLeft). The player panels, the submit bar, and the download-sheet dismiss strip include the small phone and landscape. The submit bar also has pinned widths: `player-submit-bar@667` on the small phone, `@844` in landscape, and `@932` on the large phone.

Output for a walk, under `build/ui-shots/<label>/` and the iCloud copy:

- `<token>/<id>.png` for a still
- `strips/<id>--<token>.png` for a strip (about 0.6s of frames, ten per second, starting 0.3s after launch)
- `contacts/<id>.png`, one contact sheet per screen (`ffmpeg -frames:v 1`)
- `index.png`, a grid of those contact sheets
- `index.md`, every selected screen and why, plus the files

`offline-banner-strip` launches `home-offline` and flips the connectivity file from offline to online during the strip. `download-sheet-dismiss` writes `dismiss` into `Documents/nuvio-ui-action.txt` during the strip. A debug build polls that file about every 300 ms, the same interval as the connectivity file.

Check the matcher without a simulator:

```sh
./scripts/ui-shots.sh --self-test
./scripts/ui-shots.sh --dry-run --all
./scripts/ui-shots.sh --changed integration/batch9 --explain
```

`--self-test` checks that a fake change list selects exactly the expected screens, that an unmatched file selects none and says so, that every manifest screen names a fixture that exists, and that ids are unique. `shellcheck -x scripts/ui-shots.sh` covers the script.

### Manifest columns

Tab-separated, one screen per row. Comments start with `#`.

| Column | Meaning |
| --- | --- |
| id | Screen id. Unique. This is the file name of the shot. |
| fixture | Launch value after `-NuvioUiFixture`. `-` on a shared row. |
| devices | `phone-large`, `phone-small`, `landscape`, comma-separated. `-` on a shared row. |
| kind | `still`, `strip`, or `shared`. |
| globs | Pipe-separated paths from the repo root. `**` crosses directories. `*` stays in one segment. |
| extra | `-` for a still. `offline-banner` or `sheet-dismiss` for a strip. Comma-separated screen ids for a shared row. |

Shared rows do not take screenshots. A changed file that matches a shared glob re-runs the screen ids in `extra`, which is a subset, not every screen:

| Shared id | What it covers |
| --- | --- |
| `@strings` | `values/strings.xml` and `values-*/strings.xml`. Home, details, settings root, search, downloads, submit bar. |
| `@theme` | Theme files. Home, details, appearance, library, submit bar. |
| `@sheets` | Bottom sheet and menu chrome. Season sheet, episode download sheet, episode menu, sheet-dismiss strip. |
| `@fixture-core` | Fixture gate, catalog, and poster. A representative set of eight screens. |
| `@walk-registry` | The walk screen list. One screen of each kind. |
| `@walk-overlay` | Overlay composables. The sheet, menu, and player-panel screens. |
| `@settings-shell` | Settings shell, models, and destinations. Every `settings-*` page. |
| `@offline` | Offline banner and network status. Home and details, online and offline, plus the banner strip. |

### Add a screen

1. Add a `WalkScreen` in `UiWalkScreens` (`composeApp/src/commonMain/kotlin/com/nuvio/app/core/uifixture/UiWalkRegistry.kt`) with a new wire name. Do not add a `UiFixture` entry for it. `UiFixture.Walk` is the carrier, and `decideUiFixture` still returns before it reads anything when the binary is not debug. A settings page is picked up from `SettingsPage` by `settingsWireName` (`Appearance` becomes `settings-appearance`).
2. If the screen is a route, return it from `UiWalkScreens.route` and install fixture data in `install`. If it is a panel, sheet, or menu, set the kind so `showsOverlay()` is true and draw the real composable from `UiWalkOverlay`.
3. Append a row to `scripts/ui-manifest.tsv`: id, wire name, device tokens, `still` or `strip`, the view, view model, and string globs that should reshoot it, and `-` or a strip action.
4. Run `./scripts/ui-shots.sh --self-test`. It fails when the id is duplicated, when the fixture name is not registered, or when a `SettingsPage` / `WalkScreen` wire is missing from the manifest.

An existing `UiFixture` (home, details, downloads, submit bar) stays in the enum and in `ALL_STATES`. Add a manifest row for it the same way. Width suffixes such as `player-submit-bar@667` stay in `ALL_STATES` and as their own manifest rows.

## Later

XCUITest snapshot tests are not part of this check. They can sit on top of the same fixtures once the screenshots show the layouts we care about.
