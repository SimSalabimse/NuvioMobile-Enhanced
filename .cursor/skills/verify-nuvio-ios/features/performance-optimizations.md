# Performance optimizations

Settings, Advanced, Performance lists a build-time Strip symbols row and eight switches. Every switch defaults on. The same page shows launch, playback, and memory readouts that update while the page is open.

## Sub-features

- `perf-strip-symbols` shows Strip symbols with its build-time note. It is not a switch.
- `perf-poster-downsampling` toggles Poster downsampling and cache cap and shows `Downsampled this session: N`.
- `perf-parallel-startup` toggles Parallel startup. The startup preload readout gains `, parallel` when the last launch used it.
- `perf-cached-home` toggles Cached home first. The launch readout gains `saved home shown first` when that launch painted a saved home.
- `perf-request-dedupe` toggles Shared requests and short caches and shows `Cache hits: N, shared calls: N`.
- `perf-capped-caches` toggles Capped caches and shows `Evicted this session: N`.
- `perf-quiet-playback` toggles Pause prefetch during playback and shows `Deferred this session: N`.
- `perf-next-episode` toggles Prefetch next episode and shows `Prefetched: N, used: N`.
- `perf-image-compression` toggles Image compression and shows `Compressed this session: N`.
- `perf-readouts` shows Launch time, Startup preload, Time to streams, Time to playback, Memory in use, Image memory cache, and App binary size.

## How to get to it (user POV)

- Dismiss the Diagnostics sheet if it is up.
- Open the Profile tab (label Settings until titles load).
- Choose Advanced.
- Under DEBUGGING, choose Performance. The row description is `Optimization switches with launch, playback and memory readouts`.
- The page title is Performance.

## Driving it with verify-nuvio-ios

Preconditions:

- `doctor.sh` prints `doctor: ok`.
- The Diagnostics intro is dismissed, or the test taps `diagnostics.intro.done` first.
- There is no separate `drive-performance.sh`. Run one XCUITest on scheme `VerifyNuvioIOS` that uses the queries below. Do not flip the stored flags from the shell.

- **Open Performance.** Tap `Profile` (or `Settings`), then the static text `Advanced`, then the static text `Performance`. The header reads Performance.
- **Read the nine rows.** In order the screen shows `Strip symbols`, `Poster downsampling and cache cap`, `Parallel startup`, `Cached home first`, `Shared requests and short caches`, `Capped caches`, `Pause prefetch during playback`, `Prefetch next episode`, and `Image compression`. The eight switches report value `1`.
- **Read the timing block.** `Launch time` is `Not measured yet` or a millisecond count. `Memory in use` leaves `Not measured yet` within a few seconds (the page polls every 2 seconds). Screenshot while both the switches and `Memory in use` are on screen. Swipe if one list does not fit.
- **Turn one switch off and back on.** Tap `Cached home first`. Its switch value becomes `0`. Leave the page, open Performance again, and confirm it is still `0`. Tap it on. The value is `1` after reopening. The stored key is `perf_cached_home_first` in the app preferences. Read it from the container plist after terminating the app. Do not write that key yourself.

## Gotchas

- Strip symbols is a note, not a toggle. Release IPA builds strip symbols. This Debug simulator build does not. Do not fail the row because there is no switch.
- Parallel startup and Cached home first do not have their own counter line. Their effect is the Launch time and Startup preload readouts.
- Counters stay at `0` until the app does that work. A zero readout is a pass for the text. It is not proof the optimization ran.
- `Memory in use` stays `Not measured yet` until the first poll. Wait for a size, not a fixed sleep alone.
- Changing Parallel startup or Cached home first applies on the next cold launch. Reopening the page only proves the switch was stored.
- This page is under Advanced, then DEBUGGING. It is not the Diagnostics row on the settings root.
