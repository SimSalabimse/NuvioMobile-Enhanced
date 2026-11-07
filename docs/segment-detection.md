# On-device intro, recap, credits, and preview

Downloaded episodes are fingerprinted on the phone. The player can offer Skip for a range only after that audio match. TheIntroDB times already on screen can raise the score of a match they agree with. They never create a range, and a guess is never submitted.

This is the detection contract. It is not a measurement of any titled show.

## What is stored

| Print | Window | Used for |
|---|---|---|
| Head | First 25 minutes, or the whole file when it is shorter | Intro, recap |
| Tail | Last 15 minutes, or the whole file when it is shorter | Credits, preview, the previous episode's ending for the next recap |
| Energy | One loudness byte per 100 ms of the file | Dialog-overlay gate and silence edges |

A title that starts after 25:00 is not in the head print, so it cannot be found. Credits longer than the last 15 minutes are only found for the captured part. Footage from the middle of another episode is not stored. A recap or preview made only of that footage cannot be found.

Prints saved by the older 10-minute / 8-minute capture are decoded again when the file is still on the device and this device can analyse audio. Marks and rejected ranges are kept. The Android analyser does not decode files, so it does not queue that recapture.

The match of a long print tries a stride-4 search first, then checks at most 24 alignments at full resolution. Prints that fit in 8,000 frames are compared on every alignment. Playback is not blocked; the work runs on the analysis pass.

## Bounds

These match TheIntroDB. A range outside them is not offered.

| Segment | Minimum | Maximum |
|---|---|---|
| Intro | 5 s | 300 s |
| Recap | 5 s | 1200 s |
| Credits | 5 s | 1800 s, and only what the tail captured |
| Preview | 5 s | 1800 s, and only what the tail captured |

An intro under 15 seconds is kept only when two siblings agree and the average bit error is at most 2, or the same range is already a TheIntroDB time. A single short sting is not an intro. A shared run longer than 300 seconds is not an intro.

Credits under 15 seconds are kept only when they finish the file. A short shared hit earlier in the tail is left alone.

## How a range is chosen

**Intro.** Shared audio in the head, aligned even when each episode's cold open has a different length. With three or more siblings the start times on the episode being analysed have to agree. The end is the cluster's fade, not one sibling that runs on much longer. A match at 0:00 is dropped when other episodes in the season already have an intro that starts later. Opening credits in the first 10 minutes are the intro, not the credits.

**Recap.** Clips of at least 2 seconds in the opening are matched to the previous episode's ending (credits masked) and to other stored heads and tails. Narration may sit between the clips; a gap of more than 12 seconds ends the recap. The recap can snap forward to the intro only across an 8 second transition. It does not jump a cold open. With three or more siblings, a clip that is not from the previous episode has to agree with a second sibling. One loud clip, with no second clip, is not a recap.

**Credits.** Shared audio in the tail that is not the opening. The end snaps to the file when it is already within 10 seconds of it. Loud energy across the range drops it.

**Preview.** Clips in this episode's tail, credits masked, matched to the next episode's cold open and to its tail. The block has to sit against the credits, just before or just after. Without a credits time, one ending match is not a preview. Two or more clips that finish the file still count. One loud clip is not a preview.

**Dialog.** The energy track is loudness, not a speech transcript. When most of a candidate is at or above the dialog level, intro and credits are not prefilled, and a single-clip recap or preview is not either. No energy track means this gate does not fire. A normal theme with quieter bars stays under the fraction.

**Absent.** No shared audio means no range. The stored value stays null.

## What this does not do

- It does not auto-submit a detected range.
- It does not treat a published TheIntroDB time as one of your own submissions.
- It does not write playback progress while submitting.
- It does not put API keys or stream URLs in the decision log. The log is a `segment` line per type: candidates, signal, accepted, times, and why.
