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

## States

A cell is the tab, the mode, the selected end, whether the range is valid, and whether a submit is in flight.

| Axis | Values |
| --- | --- |
| Tab | Intro, Recap, Credits, Preview. Movies: Intro, Credits. |
| Mode | Editing, or none after the "No …" chip. |
| Selected end | Start, End. |
| Range | Valid, invalid. |
| Submitting | In flight, or idle. |

Series has 64 cells and movies have 32. Two kinds are unreachable, so a series sheet has 56 reachable cells and a movie sheet has 28:

- Submitting while editing an invalid range. Submit stays disabled, so the tap does not leave the idle cell.
- Submitting while none, when the service refuses an empty segment (IntroDB). That drops 16 more series cells (every tab, both ends, both range values).

Close is not a cell. It leaves the sheet. A key problem, a block line, or a successful send also keeps Submit off, without adding a cell: success sets sent on the same idle cell until a clock edit, a tab change, or leaving none.

| Event | Effect |
| --- | --- |
| Select another tab | Switch. Clears none, sent, and the error. The selected end returns to Start. Draft clocks for that tab come back; a first visit has empty clocks (invalid). Recap and Preview do nothing on a movie. |
| Tap the open tab | While editing, nothing. While none, return to editing and clear sent. |
| Tap the "No …" chip | Arms none. A second tap does nothing. Timeline and nudges hide. Sent clears. |
| Tap Start / Tap End | Selects that end. |
| Set to now | Writes the playhead onto the selected end. Hidden while none, and ignored while submitting. |
| Nudge −5 / −1 / +1 / +5 | Adds that step to the selected end. A hold steps 0.1 s instead. A tenth shows up only after a 0.1 step. Hidden while none. |
| Drag a handle | Moves that end and selects it. An end on the stream length, or at most half a displayed tenth past it, clamps to the length. 5 s past it stays invalid. Hidden while none. |
| Reset marks | Clears both clocks and leaves none. |
| Submit | Enters submitting only when the cell is valid: a valid range while editing, or none when the service accepts 0, 0 plus the stream length. |
| Submit success | Idle, sent. Submit stays off until the next edit. |
| Submit failure | Idle, not sent. A still-valid cell can submit again. |
| Close | Leaves the sheet. Disabled while submitting. |

Every reachable cell can return to editing: failure ends a submit, and the open tab leaves none. From editing, Close leaves the sheet. None hides the timeline and the nudges, and a nudge or a drag in that mode does not move a clock. The reducer in `PlayerToolsLogic.kt` is the check: `every submit sheet state can return to editing and only a valid one submits`.

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
- Hold step, an end on the stream length, a 1 ms or half-tenth overshoot, a 5 s overshoot, and a none payload of 0, 0 plus the stream length are logic tests.
- The status line keeps the short error strings, with no line cap and no ellipsis. A source check fails if `markProblems` compares an end with the duration without `clampMarkTimeToDuration`.
- The sheet reducer test walks every reachable cell.
