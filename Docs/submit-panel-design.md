# Submit panel

One slim bar on the playing video for Flag / Submit timestamps. It replaces the large bottom sheet. The player's own seek bar keeps the start and end marks. The bar does not draw a second timeline.

## What it is

- One row, about 64 pt tall, sitting at the bottom of the player. Safe-area insets apply on the left, top, right, and bottom, in portrait and landscape, so the bar never draws under the Dynamic Island, the notch, or the home indicator.
- The bar height is the smaller of 64 pt, one eighth of the video height, and the height left between the top and bottom insets. A short window shrinks the bar. It does not overlap the cutout.
- Left to right: Intro, Recap, Credits, Preview. The selected tab uses the accent colour. A movie offers the same four tabs as an episode.
- A Start chip and an End chip, each showing its time in H:MM:SS. Whole seconds until a 0.1 s step. Neither chip is outlined until its popover is open.
- A short status. Valid is green, for example `22 s ✓`. Invalid is red and uses the existing reason, short enough to fit and never cut off mid-word: `End must be after the start`, `Past the end of the video`, or `4.9 s · Intro must be 5–300 s`.
- An amber text button named for the tab: `No intro`, `No recap`, `No credits`, or `No preview`.
- A Submit pill, then close.

## Popover

Tapping Start or End selects that end and opens a small popover just above the chip: −5, −1, +1, +5, and an amber Set to now. Tap outside, or the same chip again, closes it. A tap uses the labelled step. Holding −1 or +1 steps by 0.1 s. Holding −5 or +5 stays on that labelled step. Set to now writes the playhead onto the open end. The popover is hidden while none is armed, and a nudge then does nothing.

## None

`No intro` (and the other names) hides the chips and the popover. Submit reads `No intro · S1 E22`, or `No intro · Movie · 2010` on a movie. The payload stays 0, 0 plus the stream length. Tapping that tab again returns to editing. The button does not toggle off. Switching tabs also leaves none and restores that tab's draft clocks.

IntroDB still cannot send an empty segment. Submit stays disabled and the line `IntroDB has no empty-segment submit.` stays in full.

## Marks

Start and end of the segment being edited are drawn on the player's seek bar. None hides those marks. The bar does not contain its own timeline.

## Rules that stay

- An end equal to the video length is valid. A time past the length by at most half a displayed tenth (0.05 s) clamps to the length. 5 s past it stays invalid.
- Clocks show whole seconds until a 0.1 step. Preview uses the same H:MM:SS clock as the other tabs, including on a long video.
- The payload is clamped to the stream length.
- Error text is not ellipsized and has no line cap.

## States

A cell is the tab, the mode, the selected end, whether the popover is open, whether the range is valid, and whether a submit is in flight.

| Axis | Values |
| --- | --- |
| Tab | Intro, Recap, Credits, Preview, on a series and on a movie. |
| Mode | Editing, or none after the "No …" button. |
| Selected end | Start, End. |
| Popover | Open, closed. |
| Range | Valid, invalid. |
| Submitting | In flight, or idle. |

A series bar and a movie bar each have 128 cells. These are unreachable, so each bar has 72 reachable cells:

- Popover open while none. None closes the popover, so that combination is not a cell you can sit in.
- Popover open while a submit is in flight. Submit closes the popover.
- Submitting while editing an invalid range. Submit stays disabled.
- Submitting while none, when the service refuses an empty segment (IntroDB). That drops 16 more cells on a series and on a movie (every tab, both ends, both range values, popover already closed).

Close is not a cell. It leaves the bar. A key problem, a block line, or a successful send also keeps Submit off. Success sets sent on the same idle cell until a clock edit, a tab change, or leaving none.

The safe area is not a reducer cell. The layout test places the bar for four windows:

| Window | Insets (left, top, right, bottom) | Bar |
| --- | --- | --- |
| Notch on the left, landscape 844×390 | 47, 0, 0, 21 | left 47, top 320.25, width 797, height 48.75 |
| Notch on the top, portrait 390×844 | 0, 59, 0, 34 | left 0, top 746, width 390, height 64 |
| Home indicator, portrait 390×844 | 0, 0, 0, 34 | top 746, height 64, bottom edge 810 |
| Short window 800×200 | 0, 59, 16, 34 | left 0, top 141, width 784, height 25 |

In each case the height is at most one eighth of the video height and at most 64, and the bar stays inside the insets.

| Event | Effect |
| --- | --- |
| Select another tab | Switch. Clears none, sent, and the popover. The selected end returns to Start. An edit of that segment on this episode comes back. With nothing detected and no edit, both clocks are 0:00:00. A detected range wins over that zero. Recap and Preview switch the same way on a movie. |
| Tap the open tab | While editing, nothing. While none, return to editing, clear sent, and leave the popover closed. |
| Tap "No …" | Arms none and closes the popover. A second tap does nothing. Chips hide. Sent clears. |
| Tap Start / Tap End | While editing, selects that end and opens its popover. The same chip again closes it. Ignored while none or while submitting. |
| Tap outside the popover | Closes the popover. The bar stays open. |
| Set to now | Writes the playhead onto the selected end. Ignored unless the popover is open. |
| Nudge −5 / −1 / +1 / +5 | Adds that step to the selected end. A hold on −1 or +1 steps 0.1 s instead. A hold on −5 or +5 keeps the labelled step. Ignored unless the popover is open. |
| Drag a handle | Test-only. Moves that end. The on-screen bar has no second timeline to drag. |
| Submit | Enters submitting only when the cell can be sent: a valid range while editing, or none when the service accepts 0, 0 plus the stream length. Closes the popover. |
| Submit success | Idle, sent. Submit stays off until the next edit. |
| Submit failure | Idle, not sent. A still-valid cell can submit again. |
| Close | Leaves the bar. Ignored while submitting. |

Every reachable cell can close the popover, and none can return to editing. Submit is enabled exactly when the cell is valid under the rules above. The reducer in `PlayerToolsLogic.kt` is the check: `every submit sheet state can return to editing and only a valid one submits`. The bounds check is `submit bar stays inside the safe area and under an eighth of the video`.

## Files

- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerToolsStrips.kt` — the bar and the popover
- `PlayerToolsOverlay.kt` — wire the tabs, the none button, and Submit. A tap outside the bar does not dismiss it; it only closes the popover
- `PlayerToolsRuntime.kt` — same-tab return from none
- `PlayerToolsLogic.kt` — reducer, safe-area bounds, seek-bar fractions
- `PlayerTimeline.kt` — start and end marks on the player's seek bar
- `PlayerToolsLogicTest.kt` and an Android host test for the bar
- `composeApp/src/commonMain/composeResources/values/strings.xml`
- `Docs/FEATURES.md` — the submit-bar lines in the feature map

## Risks

- There is no Xcode here, so iOS is unverified. The insets come from the same safe-drawing values the player already uses.
- The player's seek bar is shown while the bar is open, with the transport row hidden, so the marks have a place to sit. The centre play control is hidden for the same reason.
- commonMain stays free of JVM-only APIs.
