# Playback skip intro

While a video plays, Skip Intro appears for an intro interval, and the flag control opens Submit Timestamps. Preview is a segment type. Start and end use `HH:MM:SS`.

## Sub-features

- `playback-skip-intro` shows a button labelled `Skip Intro` during an intro interval and skips when tapped.
- `playback-flag-open` opens Submit Timestamps from the flag button, whose accessibility label is `Submit Intro`.
- `playback-flag-preview` chooses the Preview segment and accepts a start and end in `HH:MM:SS`.

## How to get to it (user POV)

- Play an episode that has an intro interval.
- While that interval is on screen, tap `Skip Intro`.
- Open the player controls and tap the flag button (`Submit Intro`).
- In Submit Timestamps, choose Preview. The fields are `START TIME (HH:MM:SS)` and `END TIME (HH:MM:SS)`.

## Driving it with verify-nuvio-ios

Preconditions:

- A playable episode is already on the device or a debrid/stream account can resolve one.
- This harness has neither. `launch.sh` only seeds an anonymous profile named `Verify`.

- **Open a title.** There is no command that starts playback. Stop. The journey fails because no playable file and no stream account are available.
- **Skip Intro.** Unreachable. The label to tap, once an interval is showing, is `Skip Intro` (`player_skip_intro`). Tapping it seeks to the end of that interval.
- **Flag a Preview.** Unreachable. The control's label is `Submit Intro`. The sheet title is `Submit Timestamps`. The segment label is `Preview`. Enter a start and end such as `0:01:00` and `0:02:00`. The fields are labelled `START TIME (HH:MM:SS)` and `END TIME (HH:MM:SS)`. Submitting contacts TheIntroDB and needs that account's key. Do not submit.

## Gotchas

- This recipe cannot be completed on the verification simulator. Do not mark Skip Intro or the Preview form verified because Settings, Playback contains a Skip Intro switch. That switch is a preference, not playback.
- Do not add a test-only player, a sample URL, or a debrid link to make the journey pass. Do not put stream URLs or API keys in the evidence directory.
- TheIntroDB requests are not served from the addon cache. A cached response would be the wrong proof even if a key were present.
- `HH:MM:SS` is the field label. The parser also accepts `MM:SS` and a raw second count. Assert the rendered field, not a private formatter.
- Exporting logs after a failed attempt can capture a host name. Keep debrid hosts out of `build/verify-nuvio-ios/evidence/`.
