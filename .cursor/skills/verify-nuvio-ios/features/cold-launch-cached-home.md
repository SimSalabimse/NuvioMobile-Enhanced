# Cold launch to cached home

Quitting Nuvio and opening it again returns to Home. When Cached home first is on (the default) and a home snapshot exists, that launch paints the saved rows before the refresh finishes. The Performance launch readout then includes `saved home shown first`.

## Sub-features

- `cold-launch-ready` reaches a running Home shell after a terminate and launch, with `[Nuvio] app ready true` in the log.
- `cold-launch-empty-home` shows `No Home content available` when the signed-in profile has no catalog rows.
- `cold-launch-cached-readout` shows `saved home shown first` on a later launch that painted a saved home.

## How to get to it (user POV)

- From Home, leave the app (swipe it away, or the verification terminate below).
- Open Nuvio again.
- Home is the selected tab. With no addons, the empty copy is `No Home content available` and `Enable or add an addon to see catalogs on Home.`
- To see the cached-home readout: open Profile, Advanced, Performance, and read Launch time.

## Driving it with verify-nuvio-ios

Preconditions:

- `doctor.sh` prints `doctor: ok` on the running session, or `launch.sh` has just done so.
- Cached home first is on. It is on by default. If a previous recipe turned it off, turn it back on from the Performance recipe and terminate before this recipe.
- The Diagnostics sheet may cover Home on a container that has not tapped Done. Dismiss `diagnostics.intro.done` first when it is visible.

- **Quit.** Run `xcrun simctl terminate "$SIMULATOR_UDID" com.nuvio.app` using the udid in `build/verify-nuvio-ios/session.env`. `doctor.sh` then fails because the process is down. That is expected until the next launch.
- **Open again.** Run `xcrun simctl launch "$SIMULATOR_UDID" com.nuvio.app`. Poll `xcrun simctl spawn "$SIMULATOR_UDID" log show --last 2m --style compact` until a line contains `[Nuvio] app ready true`. Save that line to `build/verify-nuvio-ios/evidence/cold-launch-ready.txt`.
- **See Home.** Run `xcrun simctl io "$SIMULATOR_UDID" screenshot build/verify-nuvio-ios/evidence/cold-launch-home.png`. Query the tab button `Home` and the text `No Home content available` or a catalog row. One of those is the resulting state. Write the hierarchy next to the screenshot.
- **Read the launch readout.** Open Performance the way `performance-optimizations.md` describes. Launch time is `N ms` or `N ms, saved home shown first`. Screenshot it to `cold-launch-performance.png`.

## Gotchas

- The first launch after install has no saved home. Launch time can be a millisecond count without `saved home shown first`. Do not call that a cached-home pass.
- A simulator profile with no addons keeps showing the empty home. Empty home after `[Nuvio] app ready true` proves the cold launch reached Home. It does not prove a cached catalog.
- `saved home shown first` appears only when that launch actually painted a snapshot (`HomeRepository.isShowingSnapshot`). Turning the switch on and relaunching once, with no snapshot, does not produce the phrase.
- Terminating with `simctl` is the quit. Do not delete the container between the two launches or there is no snapshot to paint and the seeded profile is gone.
- The Diagnostics sheet's 1.5 second delay can hide Home on the first launch. Dismiss it before judging the home screenshot.
