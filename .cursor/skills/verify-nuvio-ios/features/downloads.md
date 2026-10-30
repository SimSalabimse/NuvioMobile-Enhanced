# Downloads

The downloads screen lists movies and shows saved on the device. With nothing downloaded it says `No downloads yet`. This is the Library downloads screen, not Settings, Downloads.

## Sub-features

- `downloads-open` opens the screen from the Library tab.
- `downloads-empty` shows `No downloads yet` when the library has no downloads.
- `downloads-folder` exposes Open downloads folder. On the simulator this can fail with a toast. The empty list is still the proof.

## How to get to it (user POV)

- Dismiss the Diagnostics sheet if it is covering the tabs.
- Open the Library tab.
- Tap the downloads button (the download icon, accessibility label `Downloads`).
- The screen title is `Downloads`.

## Driving it with verify-nuvio-ios

Preconditions:

- `doctor.sh` prints `doctor: ok`.
- The intro sheet is dismissed.
- The seeded profile has no downloads. Do not enqueue a title. There is no stream in this harness, and this recipe must not start a download.

- **Open Library.** Tap the tab button `Library`.
- **Open Downloads.** Tap the button whose label is `Downloads`. The header reads `Downloads`.
- **See the empty state.** The text `No downloads yet` is on screen. Screenshot to `build/verify-nuvio-ios/evidence/downloads-empty.png` and save the hierarchy beside it.
- **Leave without downloading.** Tap back. Library is visible again. The container's downloads database has no new item. Do not treat Settings, Downloads as this screen.

## Gotchas

- The Library button and the screen title share the label `Downloads`. The tap target on Library is the icon button. The screen that opens has the header `Downloads` and, when empty, `No downloads yet`.
- `Show Downloads` is a different string, used inside a show group, not the root header.
- Opening the folder action can toast `Couldn't open downloads folder` on the simulator. That toast is not the empty-state proof.
- Do not change download throttling, queue a real file, or add a debrid link to reach a non-empty list. An empty list is the honest result for this profile.
- A download that exists only because a preference was written by the harness is not this feature.
