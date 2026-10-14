# On-device analysis of downloaded files

Research notes and the plan for using files downloaded to the phone to improve playback:
(a) subtitle-to-audio sync, (b) intro and credits detection, (c) loudness normalisation,
(d) seeking in partial or downloaded MKV, and other ideas. Requested by Simsalabim.
Status of each piece is listed at the end ("What is built in 0.5.9.2 (160)").

## What the app already links

The iOS app links MPVKit (Nuvio fork), which ships libmpv 0.41 and a full FFmpeg build:
`Libavcodec`, `Libavformat`, `Libavutil`, `Libswscale`, `Libswresample`, `Libavfilter`
(plus libass, libplacebo, dav1d). Existing users of FFmpeg in the app:

* `FFmpegSeekPreviewGenerator.swift`: decodes keyframes with avformat/avcodec for scrub thumbnails.
* `MpvPcmEnergyCapture.swift`: a second, headless libmpv (`vo=null`, `ao=pcm`) that writes a WAV
  for the live subtitle auto-sync, because AVAssetReader cannot open Matroska.
* `SubtitleAutoSyncEngine.kt`: an ffsubsync-style aligner (speech mask from RMS energy, FFT
  cross-correlation, edge penalty, confidence from the rival peak).

So offline PCM decoding of a local MKV/MP4 needs no new dependency: open it with avformat, set
`discard = AVDISCARD_ALL` on every stream but the chosen audio track (the demuxer then skips video
packets cheaply), decode with avcodec and read the planar/interleaved samples directly. That is what
the new `MediaAnalyzer.swift` does. AAC/AC-3/E-AC-3/Opus decode runs far faster than real time,
so a 45-minute episode is one background pass of roughly tens of seconds on a recent iPhone.

## (a) Subtitle-to-audio sync

Approaches compared:

| Approach | How | Fit for us |
| --- | --- | --- |
| ffsubsync | Voice activity (WebRTC VAD or energy) on the audio -> binary speech signal; subtitle cues -> binary signal; FFT cross-correlation finds the best constant offset. | Already ported (`SubtitleAutoSyncEngine`). Needs PCM, which we now have offline. |
| alass | Aligns subtitle to subtitle or to a VAD track and also handles split points (different cuts, ad breaks) with dynamic programming. | Best for "framerate/cut" mismatches. A port is bigger; worth it later for the split case. |
| Apple SoundAnalysis | `SNClassifySoundRequest` built-in classifier has a "speech" class, gives a speech probability per window. | More robust than energy against music beds, but it needs an `AVAudioFile`/buffers we would feed ourselves (fine, we have PCM). iOS 15+. Good upgrade for the speech mask. |
| WebRTC VAD port | GMM-based VAD on 10/20/30 ms frames at 8/16/32/48 kHz; tiny C library. | Robust and cheap; would replace the energy threshold. Needs vendoring C into the iOS app. |
| Speech recognition | On-device `SFSpeechRecognizer` to time words. | Overkill and language dependent. |

Top pick: keep the existing ffsubsync-style aligner, feed it a stored energy track of the whole
file (one byte per 100 ms) measured offline, and run it at several points of the episode; apply
the median only when at least two windows agree. Next upgrades, in order: SoundAnalysis speech
probability instead of RMS, then an alass-style split search for subtitles cut for another release.

## (b) Intro and credits detection

Jellyfin's intro-skipper plugin fingerprints the first part of every episode with chromaprint
(`fpcalc -raw`), then for each pair of episodes in a season finds the time shift that best aligns
the two fingerprint sequences and the longest run of nearly identical points along it (bit
distance under a threshold). That run is the intro. Credits are found the same way on the end of
the file, with black-frame and silence detection as a fallback for shows whose credits differ.

Our constraint: often only one episode of a season is on the phone at a time (delete after
watching). So the fingerprint is the thing to keep, not the file:

* store a compact fingerprint of the first 10 minutes and the last 8 minutes of every analysed
  episode (32-bit sub-fingerprint every 100 ms: about 24 KB + 19 KB), and keep it after the file
  is deleted;
* when a new episode is analysed, match it against the stored fingerprints of the other episodes
  of the same season (latest four), and also back-fill episodes that had nobody to match against.

Fingerprint choice: chromaprint itself is not linked; its core is a chroma (12 pitch classes)
image and 16 trained filters. A Haitsma-Kalker (Philips) style sub-fingerprint is simpler and good
for "same recording" matching: 33 log-spaced bands between 300 and 2000 Hz, bit m = sign of the
band-energy difference across frequency differenced across time. Built with vDSP FFTs in Swift.

Fallbacks in the player, in order: online timestamp sources (TheIntroDB, IntroDB, AniSkip,
Anime-Skip, filtered by the new "TheIntroDB only" setting), then the on-device detection if the
"Use on-device detection as fallback" setting is on. A detected intro also pre-fills the Submit
Timestamps dialog so it can be sent to TheIntroDB with one tap. Silence/black-frame heuristics
for credits are not built yet (see stubs).

## (c) Loudness

* EBU R128 / ITU-R BS.1770-4 integrated loudness: K-weighting (high shelf + RLB high-pass),
  mean square per channel with surround weights (1.41) and LFE excluded, 400 ms blocks with 75 %
  overlap, absolute gate -70 LUFS, relative gate -10 LU. ReplayGain 2 uses the same measure with a
  -18 LUFS reference.
* Apply as a static gain per file: mpv `volume-gain` (dB). The app already uses `volume-gain` for
  its >100 % volume boost, so the loudness gain is added on top inside `setVolume` and removed in
  `getVolume`. Gain is capped at +6 dB / -12 dB to avoid clipping without a limiter.
* Live alternative: `af=lavfi=[loudnorm]` or `dynaudnorm` (libavfilter is linked). Works for
  streams too, but changes dynamics constantly and costs CPU; a "night mode" toggle candidate.

Top pick: measured static gain for downloads (built), dynaudnorm as an optional night mode later.

## (d) Seeking in partial or downloaded MKV

* A finished local MKV: mpv reads the Cues element directly (it is usually at the end of the file),
  so seeking is already instant; a pre-built index brings nothing.
* A partial MKV played through the loopback server: the Cues are at the tail, which is not
  downloaded yet, and the server answers 416 for ranges past the downloaded bytes. mpv then seeks
  by scanning or bitrate estimate, which is slow or imprecise. Options:
  1. Download the last few MB (the Cues) right after the head, before the middle. Needs the
     downloader to fetch one extra range and the loopback server to serve a sparse file.
  2. Build our own index while downloading (cluster offsets and timestamps from the bytes that
     arrive) and serve a synthetic Cues element. Complex.
  3. Restrict seeks to the downloaded part (the 159 watchdog already falls back to the stream).
* Verdict: not worth a pre-index for finished files; for partial files option 1 is the useful one.
  Not built (stub).

## Other on-device ideas, ranked

| Rank | Feature | Value | Effort | Notes |
| --- | --- | --- | --- | --- |
| 1 | Subtitle sync from stored energy | High | Low | Built. |
| 2 | Intro/credits via season fingerprints | High | Medium | Built (matching), no black-frame fallback yet. |
| 3 | Loudness normalisation | Medium | Low | Built, off by default. |
| 4 | Recap detection | Medium | Medium | Recaps reuse audio from earlier episodes: match the head fingerprint against the *body* of earlier episodes. Needs whole-episode fingerprints (about 140 KB per episode). |
| 5 | Audio language / mislabelled track check | Medium | Medium | Speech-language ID needs a model (Whisper tiny is ~40 MB); `SFSpeechRecognizer` per locale is a cheaper partial check. |
| 6 | Auto-pick the right audio track | Medium | Low once (5) exists | Use measured language plus channel layout and commentary detection (low loudness, speech only). |
| 7 | Subtitle language detection | Low-Medium | Low | Character n-gram detector on cue text; fixes "und" or wrong labels. |
| 8 | Thumbnail/scrub previews pre-generated | Low | Low | The live FFmpeg preview generator already covers local files well. |
| 9 | Partial MKV seek index | Medium | High | See (d). |

## What is built in 0.5.9.2 (160)

* `iosApp/iosApp/Player/MediaAnalyzer.swift`: FFmpeg decode of the main audio track; speech energy,
  BS.1770 loudness, head/tail sub-fingerprints. Cancellable (interrupt callback + per-packet check).
* `features/analysis/*` (Kotlin): result model and per-episode store (Application Support/
  NuvioAnalysis, one JSON per episode, fingerprints kept after the file is deleted), scheduler
  (after a download finishes, at launch after 20 s, waits while something plays, "only while
  charging"), season matcher, settings.
* `MediaAnalysisBackgroundTask.swift`: BGProcessingTask `com.nuvio.app.media-analysis`, scheduled
  when the app goes to the background, requires power when "Analyse only while charging" is on,
  cancels on expiry and reschedules if work is left.
* Player: loudness gain for the analysed file, automatic subtitle delay with a toast when an online
  subtitle is picked (only if no delay is set), on-device intro/credits as a fallback skip source,
  detected intro pre-filled in Submit Timestamps.
* Settings: Downloads -> Sync subtitles to audio, Find intros and credits, Even out loudness,
  Analyse only while charging. Playback -> TheIntroDB only, Use on-device detection as fallback.

Stubs / not built: Android analyzer (no-op), silence/black-frame credits fallback, recap
detection, language checks, partial MKV seek index, SoundAnalysis/WebRTC VAD speech mask.
Nothing here has been tested on a phone yet.
