# Submit panel redesign

Landscape phone bottom sheet for Flag / Submit timestamps. Three states: editing a tab (Intro, Credits, and the others), and "none" after the chip is tapped.

## What changes

- One segmented control: Intro, Recap, Credits, Preview. Movies keep Intro and Credits only.
- One chip beside it, named for the open tab (`No intro`, `No recap`, `No credits`, `No preview`). One tap sets that tab to none: the timeline and nudges hide, and Submit reads `No intro · S1 E22` (movie equivalent on a movie). The payload stays 0, 0 plus the stream length. Tapping the open tab returns to editing. The chip does not toggle off.
- Live status next to the chip on a wide screen, and on its own line on a narrower phone, so it is not clipped. Valid example: `22 s · valid 5–300 s`. Invalid ranges show the error. An end equal to the video length stays valid.
- Timeline inside the sheet: draggable start and end handles, playhead, and a play-range control on the marked range. The window stays the existing two-minute zoom so a short range can still be dragged.
- Start and End chips select which end is edited (the selected chip is outlined). `Set to now` writes the playhead onto that end.
- One nudge group, −5, −1, +1, +5, applied to the selected end. A tap uses that step. Holding steps by 0.1 s. Clocks show whole seconds until a 0.1 step, same as today.
- Large Submit on the right: `Type · S1 E22`, or `No intro · S1 E22` while none is armed.
- Overflow menu: Copy link, Play range, Reset marks. Close stays on the sheet and on the title row.
- Removed from the sheet: per-end nudge rows, Starts at 0:00, Runs to end, and the always-visible Copy link and Play range buttons.

IntroDB still cannot send an empty segment. Submit stays disabled and the existing refusal line stays. Preview (the segment, and playing a range) is unchanged.

## Files

- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerToolsStrips.kt` — sheet layout
- `PlayerToolsOverlay.kt` — wire the chip, the selected end, the menu, and Submit
- `PlayerToolsRuntime.kt` — same-tab return from none, reset marks
- `PlayerToolsLogic.kt` — tab / none, hold step, status facts
- `PlayerToolsLogicTest.kt` and an Android host test for the three states
- `composeApp/src/commonMain/composeResources/values/strings.xml`
- `docs/FEATURES.md` — short strip description (the file is not in the tree today)

## Risks

- A phone landscape width is tight. Status, clocks, and the nudge hint wrap or move onto their own line instead of ellipsizing. The host test uses a 640×360 dp landscape window.
- A hold must not also apply the coarse step.
- Same-tab tap used to do nothing. It now leaves none mode. Switching tabs still clears none.
- commonMain must stay free of JVM-only APIs. There is no Xcode here, so iOS is unverified.

## How to verify

- `./gradlew :composeApp:testAndroidHostTest --continue`
- Known failures stay the seven download tests. Nothing new should fail.
- Intro, Credits, and No intro: clocks, status, hidden timeline, and the Submit caption.
- Hold step is covered by the logic tests. End equal to the duration stays valid.
