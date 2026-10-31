# Movie intro, credits, and post-credit scenes

Design only. No player behavior changes in this note. Base: `enhanced` at `0f459582`. iOS (SideStore sideload) is the target. Android is a later port of the same decision, because the on-device analyzer there is still a stub.

Simsalabim's wishes, in the order the player should honor them:

1. Use a published TheIntroDB or IntroDB time when one exists. Detect only when it does not.
2. Credits are a near-black picture with high-contrast text rows moving upward, or a clear audio change. Both signals are described below. Silence alone is not enough: a lot of credit rolls play music.
3. If normal footage comes back after that roll, say so on "Watch credits" and do not skip the scene.
4. A guess stays on the phone. Nothing in this design calls the submit API.
5. Battery and storage stay small. A finished download and a stream are different jobs.

Studio logos and fanfares follow TheIntroDB's published rules and nothing else. There is no separate logo skip. The rules are copied in the next section so a later change does not invent a segment TheIntroDB does not have.

## What the app can already read

### Published times

Movies are loaded in `PlayerScreenRuntimeEffects.kt` (`contentType` or `parentMetaType` equals `movie`). That calls `SkipIntroRepository.getMovieSkipIntervals` (`composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/skip/SkipIntroRepository.kt`).

- TheIntroDB first: `TheIntroDb.fetchMovieIntervals` (`TheIntroDb.kt`). `GET /v4/media` with `imdb_id` or `tmdb_id`. Optional `duration_ms` is already on `getMedia`, and the FAQ uses it to pick the cut (theatrical, extended, and so on). The movie caller does not pass it. The process cache key is `movie:<imdbId>` with no duration, timeout 5 seconds.
- IntroDB second, only when `IntroDbConfig.URL` is set: `SkipIntroApi.getIntroDbMovieSegments`, mapped by `IntroDbSegmentsResponse.movieSkipIntervals` (`SkipModels.kt`). IntroDB has `intro`, `recap`, `outro`, and `post_credits`. `mergeByPriority` keeps the first provider that has each category (opening, ending, post-credits, preview, recap).
- AniSkip and Anime-Skip are not asked for movies.
- "TheIntroDB only" (`MediaAnalysisSettings.Flag.TheIntroDbOnly`) drops every other provider, including on-device and the `heuristic` gap guess. The filter is `filterSkipSources` in `PlayerMediaAnalysis.kt`.

Shared mapper, and the hole it leaves: `toTheIntroDbIntervals` (`TheIntroDb.kt`) and IntroDB's `movieIntervalOrNull` (`SkipModels.kt`) both require `end_ms`. TheIntroDB's own credits rule is that an empty end means "runs to the end of the media". Those rows are discarded, so a credits-only movie with an open end draws no button. A second credits row is relabeled `post-credits` (`movieSkipIntervals` in `TheIntroDb.kt`). On TheIntroDB that second row is more credits after a gap, not the scene. The scene is the gap. A null end on that second row is discarded with the rest, so the gap disappears too.

`InternalSkipAction.kt` refuses a seek when the duration is the short placeholder. For `movie-credits` it seeks to an explicit `post-credits` start, otherwise to the credits end. If the credits end is more than 5 seconds before the duration (`POST_CREDITS_GAP_MS`), the label switches to "Skip to Post-Credits" (`player_skip_to_post_credits`) even when nobody marked a scene. The same 5-second idea lives in `PlayerNextEpisodeRules.findFollowingPostCreditsScene`, which invents a `post-credits` interval with provider `heuristic` (chip text "Guess"). That path is for episode outro types (`outro`, `ed`, `mixed-ed`), not `movie-credits`. It is the wrong signal for a movie scene: a short fade or end card becomes a scene.

Buttons today (`SkipIntroButton.kt`, `values/strings.xml`): "Skip Intro", "Skip Credits" (`player_skip_movie_credits`), "Skip to Post-Credits", "Skip Outro". "Watch credits" is hardcoded on the series up-next card (`NextEpisodeCard.kt`) and only dismisses that card. Movies do not have that card. Auto-skip (`PlayerScreenRuntimeEffects.kt`) seeks to `internalSkipAction.targetMs` when `shouldAutoSkip` matches `AutoSkipSegmentType.MOVIE_CREDITS`. The source chip is `skipSourceLabel` (`SkipSourceLabel.kt`): TheIntroDB, IntroDB, On-device, Guess.

Submit stays manual. `TheIntroDb.submitTimestamp` runs from the marking strip (`SubmitIntroDialog.kt`, `IntroSubmitService.kt`). Payload helper `theIntroDbSubmitPayload` sends `start_ms` / `end_ms` and optional `video_duration_ms`. An absent segment is `0` and `0`. The strip's movie types are Intro and Credits. Detected episode intros can pre-fill that strip (`mergeOnDeviceSkips` in `PlayerMediaAnalysis.kt`). Movie guesses must not.

### On-device analysis (series, and the measurements a movie file already gets)

`docs/on-device-analysis.md` is the series plan. What is actually in the tree:

- iOS `iosApp/iosApp/Player/MediaAnalyzer.swift` implements `NuvioMediaAnalyzerBridge`. One audio decode of a local file (video packets discarded). It writes RMS energy, one byte per 100 ms, EBU R128 loudness, and Haitsma-style fingerprints for the first 10 minutes and the last 8 minutes (`HEAD_FINGERPRINT_MS`, `TAIL_FINGERPRINT_MS` in `MediaAnalysisModels.kt`). AVAssetReader cannot open Matroska; this pass uses the FFmpeg linked in the player. A 45-minute episode is on the order of tens of seconds on a recent iPhone. Cancellable per packet.
- Results live in Application Support `NuvioAnalysis/<key>.json` (`MediaAnalysisFiles.ios.kt`, `MediaAnalysisStore.kt`). A movie key is `<show>__movie` because `keyFor` uses that form when season and episode are null. Energy is dropped when the file is deleted; fingerprints and segment times stay. Whole-file energy for a two-hour movie is about 72 KB. The tail fingerprint is about 19 KB. Thumbnail JPEG sheets are separate files and are the bulky part.
- The scheduler (`MediaAnalysisScheduler.kt`) runs after a download finishes, about 20 seconds after launch, and from `MediaAnalysisBackgroundTask.swift` (`com.nuvio.app.media-analysis`, also in `Info.plist`). It requires a `file:` URI. It honors "Analyse only while charging". It waits while playback is strained; it does not otherwise stop for playback. Season intro/credits matching runs only when season and episode are both set, so a movie stores energy and fingerprints and then stops. No movie intro or credits time is produced.
- Sibling clip (`SiblingClipReference.kt`) fingerprints a short window of another episode's stream with `ClipFingerprintRun` inside `MediaAnalyzer.swift` (FFmpeg HTTP open, custom headers, seek, audio only, nothing written). It returns immediately when season is null. It also returns, without fetching, when "Only on Wi-Fi" is on and `DownloadNetworkGuard` says the radio is not Wi-Fi, or when free space is under the smart-download floor.
- Scrub frames: `FFmpegSeekPreviewGenerator.swift` opens a local path or a remote URL with headers, discards non-video streams, seeks, decodes one frame, scales with swscale, returns a JPEG. `ThumbnailSheetBuilder` in `MediaAnalyzer.swift` asks it for about 400 frames of the whole file, tile width 160, interval clamped to 5–20 seconds, and writes JPEG contact sheets. That spacing cannot show credit text moving. The sheets are the wrong thing to feed a detector, and this design does not add more JPEGs.
- Live Auto Sync audio, not a scanner: `AVAssetAudioEnergyCapture.swift` decodes with AVAssetReader beside MPV. It works for file and HTTP URLs that AVFoundation opens (MP4, M4V, MOV). It does not open MKV. `MpvPcmEnergyCapture.swift` is a second libmpv with `vo=null` and `ao=pcm` for the MKV case. `AudioEnergyWave.swift` is the same energy math on bytes already in memory. None of these sample pictures.
- There is no `screenshot-raw` (or any mpv screenshot command) in the tree. The headless mpv does not render, so it cannot supply frames.
- Android: `MediaAnalyzerPlatform.android.kt` is a stub (`supported = false`). ExoPlayer can grab a scrub frame (`SeekPreviewFrameSource.android.kt`) and can tap PCM while it is playing (`AudioEnergyCaptureProcessor` in `PlayerEngine.android.kt`). Neither is an offline movie scan. Chapter bytes can be read with HTTP ranges (`MatroskaChapterLoader.kt`, `PlayerChapterReader.kt`, `MatroskaChapterParser.kt`). iOS chapters during playback come from mpv's `chapter-list` (`mpvChapters` in `PlayerChapters.kt`).
- On-device skip intervals (`onDeviceSkipIntervals`) require both start and end, and they emit type `outro`, which labels "Skip Outro", not "Skip Credits". They apply to a movie only when the stored key matches the player's season/episode (null and null is the `__movie` key). They never fire for a movie today, because the matcher never fills those fields.

### TheIntroDB rules this design follows

Source: TheIntroDB submission guidelines (the site's English strings for that page, read for this note). Segment types are intro, recap, credits, and preview. There is no logo, fanfare, or bumper type.

- Intro. Start may be empty when the sequence starts at the beginning. End is required, at the moment the sequence has fully faded or transitioned. Length 5–300 seconds. Most published intros are 15–90 seconds.
- Credits. Start is required, when the credits start rolling. End may be empty when they run to the end of the media. Length 5–1800 seconds.
- Include the full sequence, out through the fade. Exclude cold opens (teasers before the intro or recap) and any stretch that contains important dialogue or a scene. Only an isolated segment counts. "Dual credits": credits at the start are an intro; credits at the end are credits.
- Do not guess. Compare the cut by sending the video duration.
- Streaming apps sometimes burn their own title card into the file. Those cards are not part of the segment.
- Empty credits end means "to the end". Several credits rows on one movie are several credit runs. A gap between two runs is the gap, not a second kind of segment.

IntroDB's separate "logo sting" note is a different project. This app does not grow a logo button from it. IntroDB remains only the existing fallback for a category TheIntroDB did not fill, and only when "TheIntroDB only" is off.

A studio fanfare is skipped only when it already sits inside a published intro that meets those rules, or when a later detector reports an isolated intro in the 5–300 second band and the confidence rules below accept it. A bumper in front of a cold open stays on screen. No detector in this design classifies a logo by itself.

## Movie intro

Published times win. Detection runs only when TheIntroDB and IntroDB have no intro for this cut, "TheIntroDB only" is off or TheIntroDB returned nothing, and on-device fallback is on. The result has to be an isolated sequence of 5–300 seconds. It may start at 0. It must end on a real transition. It must not swallow a cold open or a dialogue scene. Below the confidence floor, the player shows nothing.

### A. Published intro only

`fetchMovieIntervals` already returns intro rows with both ends. Pass `duration_ms` once the player knows the length, and put that length in the cache key (see Phase 1). Cost: one small JSON GET that playback already makes, a few kilobytes, no file read, works for a stream on any network the user is already playing on. Trade-off: movies with no community intro show no button. That is the correct failure. This is the path Phase 1 ships.

### B. Opening frames of a local file, same stats as the credits tail

After the download is analyzed, sample the first 6 minutes at low resolution (width 160) about once a second, but only inside a coarse pass that already looks interesting (same two-pass as credits, below). Keep a few numbers per second, not the picture. Accept a block only when all of these hold: length 5–300 seconds, starts at 0 or after a short non-matching gap that is left alone (the cold open), ends on a fade or hard cut back to a normal frame, and the middle is not ordinary dialogue energy. Prefer the 15–90 second band when scoring. A chapter whose title clearly says the opening can raise the score. It cannot create a segment by itself (`PlayerChapters.kt`, `MatroskaChapterParser.kt`). Cost for a local file: on the order of one to two minutes of keyframe decodes, a few hundred megabytes of disk read in the worst GOP layout, well under 50 KB stored, no network. Trade-off: cold opens and title cards still look like intros. The confidence floor is there so most files produce no button. This is Phase 3, and only on a finished download.

### C. Season fingerprints, or a live MPV screenshot

Season matching (`SeasonSegmentDetector`, `SiblingClipReference`) needs another episode of the same season. A movie has none. Chromaprint is not linked. Live `screenshot-raw` has no call site; the headless mpv sets `vo=null`. Sampling the visible player would hitch playback and write full frames if pointed at disk. This approach is not a candidate to build.

## Movie credits

The cheap detector, when it is built, is: near-black frames, high-contrast text rows, those rows moving upward, over a run of at least 5 seconds and at most 1800 seconds (TheIntroDB's credits bounds). Audio is a supporting vote. A drop or a sustained bed can support a visual run. A quiet ending with no text must not become credits. A loud score under real credits must still count, so low audio is not required.

### A. Two-pass low-res frames from the local file, plus the energy track already stored

New tail function beside `ThumbnailSheetBuilder` in `MediaAnalyzer.swift`, using the same FFmpeg open as `FFmpegSeekPreviewGenerator` (local path only in this phase). Do not call `frameJpeg` in a loop that keeps JPEGs.

- Coarse pass over the last 20 minutes (or from 70 percent, whichever window is shorter, but at least the last 8 minutes): one decoded frame every 5 seconds, scaled to width 160, then freed.
- If a near-black stretch shows up, dense pass at 1 fps only inside that stretch and 30 seconds either side.
- Per frame, keep mean luma, fraction of pixels under a black threshold, standard deviation of per-row mean luma (text rows), and the vertical shift of that row profile against the previous frame (positive means the picture content moved up). About 24 bytes a second. Twenty minutes at 1 Hz is under 30 KB. Store the decision and the reasons, not the samples, once the decision is made. Samples can stay in the JSON while debugging and should be dropped before the file is deleted, same as energy (`forgetFileData`).
- Audio vote: `decodeEnergy` on the analysis JSON. No second audio decode. The full-file energy pass already paid for it.

Keyframe seeks match what scrub previews already do, so MKV and MP4 both work. A credit roll still moves between frames 2–5 seconds apart; the dense pass is what makes the direction reliable. Estimated cost, once, after download, utility queue: coarse 240 frames plus dense ~480 frames for an 8-minute roll, tens of milliseconds each, so roughly half a minute to a minute of CPU if each frame is cheap, longer if HEVC software-decodes. Disk read is one GOP per seek, which can be a few hundred megabytes. No network. Do not run it while this file is on screen or while playback is strained. Honor "Analyse only while charging" when that flag is on. Trade-off: best fit for this stack, still wrong on a credits sequence that is not black (styled color plates). Those stay unpublished unless TheIntroDB has them.

### B. Audio change only, on the stored energy

Look at the last 20 minutes of the 100 ms energy for a level shift that holds. Cost: milliseconds of CPU, zero extra bytes, the JSON is already on disk. Trade-off: a quiet last scene, a song over a montage, and a silent fade all look alike, and a scored credit roll does not look like silence. This can raise or lower the score of approach A. It must not show a button by itself.

### C. AVAssetImageGenerator, or MPV screenshot-raw

`AVAssetImageGenerator` can ask for 1 fps at a small `maximumSize` and can range-read a remote MP4 when the server allows it. AVFoundation does not open the Matroska files this app downloads (`MediaAnalyzer.swift` says so, and `AVAssetAudioEnergyCapture.swift` lists the containers it does open). Most movie downloads would no-op. It can be a fast path later for a local MP4 the asset opens, writing the same stats. It is not the primary sampler.

MPV `screenshot-raw` would need a renderer. The second mpv is `vo=null`. Driving the on-screen player would contend with playback and, if it writes files, with the storage budget. Not used.

## Post-credit scene

A scene is normal footage that returns after a credits run and before the next credits run or the end of the file. "Normal" means the black-and-text pattern stops and the picture or the speech energy comes back, and that return lasts at least about 8 seconds. A dark, quiet scene fails closed: no warning, rather than a warning that then seeks. The user would rather miss a warning than skip a scene that was not there, and would rather miss a warning than be told to skip a scene that is there.

### A. Read it from the published rows

IntroDB `post_credits` is already a scene interval (`SkipModels.kt`). TheIntroDB has no `post_credits` field. Two credits rows with a gap mean: first row is the roll, the gap is the scene, the later row is more credits (often with an empty end, meaning through to EOF). The player should treat the gap as the scene and must not seek to the start of the later row. That seek skips the scene and lands on more names. Cost: none beyond the GET playback already does. This is the only scene signal Phase 1 uses.

### B. The same tail frames as the credits detector

After approach A of credits accepts a run, watch for the stats leaving black-and-upward-text while energy returns to a scene level, for at least 8 seconds, before EOF or before another black-and-text run. The second run is more credits, not the scene. Store scene start and end beside the credits. Cost is included in the credits pass. This is Phase 2, and only as a warning.

### C. "Anything more than 5 seconds after the credits end"

`InternalSkipAction` and `PlayerNextEpisodeRules` already do this. For movies it turns a short tail into "Skip to Post-Credits". Drop it for `movie-credits`. Keep it for episode outros if the series up-next card still wants it. It is not a movie scene detector.

## Cost, in one place

Figures are estimates from the decoders that exist (audio pass "tens of seconds" per episode, scrub frames already seek one keyframe at a time) and from the sample counts above. They are not device measurements.

| Job | When | Time / CPU | Radio or disk | Stored | Needs the file local? |
| --- | --- | --- | --- | --- | --- |
| TheIntroDB / IntroDB GET | On play, as today | Under the 5 s timeout, usually one short request | A few KB. Already happens on the network the user is playing on | Process cache only | No |
| Close an empty credits end with the known duration | On play, once duration is known | None | None | Nothing new | No |
| Audio-only look at stored energy | After the existing audio analysis | Milliseconds | The JSON already on disk (~72 KB energy for 2 h) | A few numbers on the movie record | Yes, the analysis JSON. Not the media, after the audio pass |
| Two-pass tail frames | After download, idle, charging if that flag is on | About half a minute to a couple of minutes | Local reads, up to a few hundred MB of GOPs. No radio | Decision plus reasons, under about 1 KB. Optional debug samples under 30 KB, dropped with the file | Yes. `file:` URI, same rule as `MediaAnalysisScheduler.analyzeOne` |
| Opening 6 minutes, same sampler | Same idle pass, Phase 3 | Similar, shorter window | Local only | Same | Yes |
| Stream tail via FFmpeg HTTP seek | Not before Phase 3, Wi-Fi only | Similar CPU, plus download time | The ranges themselves, easily hundreds of MB | Same small record | No, if the URL seeks. If the seek fails, stop. Do not download the movie in order to scan it |
| AVAssetImageGenerator | Only if a later pass finds a local MP4 AVFoundation opens | Similar to the FFmpeg pass, often hardware | Local, or ranges for a remote MP4 | Same stats | MKV: no |
| MPV screenshot while watching | Not scheduled | Contends with playback | Full frames if written out | Avoid | Either, and a bad idea for both |

Cellular: a local scan does not use the radio. The published-time GET stays as it is, because playback already does it. Any future read of a stream (the sibling-clip style open in `ClipFingerprintRun`) uses the same gate as `SiblingClipReference.apply`: if "Only on Wi-Fi" is on and `DownloadNetworkGuard.isOnWifi()` is false, skip the scan. Phase 3 should skip stream scans on cellular even when that download setting is off. Scanning is not required to watch.

## When a scan runs

- Published times: when the movie starts playing, as today. When duration becomes known, look up again if the first response was cached without `duration_ms`. Do not keep a no-duration answer in front of a duration answer (`SkipIntervalLookupCache`, key `movie:<imdbId>` today).
- Local tail and opening scans: only from `MediaAnalysisScheduler`, after the audio pass, for a completed download with a `file:` URI. Wait until nothing is playing. The current scheduler still runs during ordinary playback; a video decode should not. Honor charging. Cancel on the existing abort flag (`MediaAnalyzer.cancel`, and `releaseFile` when the download is deleted). The background task may or may not run under a SideStore install; the in-app scheduler is the one that counts.
- Never on a timer while the movie is on screen. Never as a full-file video decode. The audio pass already walks the whole file with video discarded; leave that as it is.
- Android: no scan until `MediaAnalyzerPlatform.android.kt` is real. The Kotlin decision function can be shared before that.

## Confidence, and what the screen shows

Provider rows (TheIntroDB, IntroDB) are shown. They are not given a detector score. They still have to fit the file: `sameCut` in `PlayerMediaAnalysis.kt` treats two known durations more than 30 seconds apart as a different cut. Pass `duration_ms` so TheIntroDB can make that choice itself. If the duration is still unknown, show a row that has both ends. Do not invent an end.

On-device score, 0 to 1, from the stats:

- Credits high (show a button): black fraction and row contrast stay high, upward shift stays positive, length between 5 and 1800 seconds, and either the audio level changed at the boundary or a chapter title supports it. Practical floor: 0.75.
- Credits medium: only one of those signals. Store it. Show nothing.
- Intro high: the opening rules in approach B, and the block is in the 15–90 second band or starts at 0 with a hard end. Same 0.75 floor. Anything that might be a cold open stays medium and is not shown.
- Scene high: a high credits run, then at least 8 seconds of normal footage before EOF or before another credits run. Show the warning. A scene score never creates a seek.

UI, movie playback:

- No scene. Button "Skip Credits" (`player_skip_movie_credits`). Seek to the credits end, which is the file end when the published end was empty and the duration is known. Chip: TheIntroDB, IntroDB, or On-device.
- Scene from published rows or from a high on-device score. A notice, not a seek: "Watch credits", second line "Scene after credits". New strings in `values/strings.xml`. Playback keeps going. Auto-skip does not fire (`shouldAutoSkip` false for that interval while the scene is attached, in `PlayerScreenRuntimeEffects.kt`). Chip unchanged.
- Do not use "Skip to Post-Credits" for this notice. That string seeks. A later, separate control could offer "skip the roll, land on the scene". It is not part of the first build, and it stays off the auto-skip path.
- Intro, when shown: existing "Skip Intro".
- "TheIntroDB only" continues to hide on-device and Guess.
- The playback debug readout (`PlayerDebugReadout.kt`) already prints every segment and its source. No new settings screen in the first build. On-device fallback and charging flags already exist.

## What is stored, and what is never sent

Phase 1 stores nothing. The closed end is computed from the response plus the player duration.

Phase 2 adds optional fields on `MediaAnalysisResult` (credits, scene, score, short reason codes) and bumps `MEDIA_ANALYSIS_DERIVED_VERSION`, not `MEDIA_ANALYSIS_VERSION`. `load` drops a record whose analysis version mismatches, which would decode every downloaded episode's audio again. A derived bump only re-runs the tail. Movie key stays `<show>__movie`. Do not mark the result as a user segment and do not set `learnedTypes`. `forgetFileData` may drop debug samples and must keep the decision only as long as `sameCut` still holds.

Submit: no new call, and no call from the scheduler, the detector, or the player notice. `TheIntroDb.submitTimestamp` and `IntroSubmitService` stay on the marking strip. Do not pre-fill that strip from a movie guess. The episode pre-fill in `mergeOnDeviceSkips` stays for series intros that came from a real match. A movie on-device interval must not take that branch. The user can still mark Intro or Credits by hand and tap Submit. That is the only way a time leaves the phone.

## What is not feasible here

- A logo button, or a model that recognizes a studio card. TheIntroDB has no such segment, and a bumper before a cold open is excluded by the cold-open rule.
- Telling a cold open from an intro reliably enough to show a button on most movies. The floor will leave many intros undetected. That is the point of "don't guess".
- Reading the names (OCR), in any language, on stylized type.
- Matching a movie against other movies with the season fingerprinter. There is no sibling.
- AVAssetReader or `AVAssetImageGenerator` on MKV, which is the usual download.
- A background scan through MPV `screenshot-raw` without a renderer and without hitching playback.
- Android, until an analyzer exists. ExoPlayer's live tap only runs while ExoPlayer is playing.
- A stream that does not seek. Failing the seek and stopping is the whole fallback. Downloading the file "so we can scan it" breaks the storage rule.
- A post-credit scene that is as dark and quiet as the credits. The detector will miss it. Missing it means no warning and no seek, which is safe.
- Treating the SideStore background task as guaranteed. `MediaAnalysisBackgroundTask.swift` is best-effort. The scan has to finish under the in-app scheduler.
- Raising `MEDIA_ANALYSIS_VERSION` to mean "movie tail". That redoes every episode.

## Build plan

### Phase 1 (the step-B slice)

Known movie times behave. No frame decoder, no Android, no new submit path.

Work:

- `TheIntroDb.kt`: `toTheIntroDbIntervals` / `movieSkipIntervals`. An empty credits `end_ms` becomes the known duration when the duration is known and the start is inside the file. A later credits row stays type `movie-credits` (a second run), not `post-credits`. The scene interval, type `post-credits`, is the gap between two credits rows when that gap is at least 8 seconds. Provider stays `theintrodb`.
- `SkipModels.kt`: same empty-end rule for IntroDB movies. IntroDB `post_credits` stays the scene it already is. Do not also invent a gap scene on top of it.
- `SkipIntroRepository.kt`: pass `duration_ms` into `fetchMovieIntervals`. Cache key includes the duration rounded to 30 seconds once it is known. A lookup that ran without a duration is replaced when the duration arrives.
- `PlayerScreenRuntimeEffects.kt`: the movie branch already calls `getMovieSkipIntervals`. Re-run it when `playbackSnapshot.durationMs` becomes real. When a scene interval is attached, do not auto-skip that `movie-credits` row.
- `InternalSkipAction.kt`: for `movie-credits`, do not set `skipsToPostCredits` from the 5-second-before-EOF gap. If a scene interval exists, `internalSkipAction` returns null so nothing seeks. Episode outro behavior stays.
- `SkipIntroButton.kt` and the movie branch of the player overlay (`PlayerScreenRuntimeUi.kt`): when the scene exists, show the notice instead of a skip control. `canSkip` today calls `internalSkipAction(emptyList())`, which cannot see the scene. The notice must not depend on that call.
- `values/strings.xml`: "Watch credits" and "Scene after credits". Other locales fall back to English unless a later pass translates them.
- Tests in `composeApp/src/commonTest`, next to `TheIntroDbV4Test.kt`: empty end dropped today and closed once a duration is passed; second credits row is not the scene; the gap is; a 5-second tail is not a scene; IntroDB `post_credits` still wins its category; cache key changes with duration; auto-skip predicate is false while the scene is attached; no submit helper is invoked (the detector does not exist yet, so this is a mapper test, not a network test).

Risks: a wrong duration closes the credits at the wrong time. Gate on `sameCut` and on `isShortPlaceholderDuration`. Relabeling the second credits row changes the seek target for anyone who relied on landing at the start of the second roll. That old seek skipped the scene, which is the bug. External player launch (`ExternalPlayerLaunchCoordinator.kt`) also calls `getMovieSkipIntervals`; it should receive the same list, including the scene, and must not be taught to jump past the scene.

Verification:

- Unit tests on the host that already runs `commonTest`. From this repo that is `./gradlew :composeApp:testAndroidHostTest`. No Mac, no device.
- A Mac IPA is required to see the button. This slice adds no Swift, so the IPA is the usual SideStore build, not a new native target.
- Device, three steps. (1) Install that IPA with SideStore, on Wi-Fi download two movies you already know in TheIntroDB: one whose credits have both ends, one whose credits end is empty. (2) Play each from the download. The closed one shows "Skip Credits" with the TheIntroDB chip and seeks to the published end. The open one shows "Skip Credits" and seeks to the end of the file. Leave the marking strip closed. (3) Play a movie that has a scene between two credit runs (or an IntroDB `post_credits` when TheIntroDB has no scene). The control reads "Watch credits" and "Scene after credits", the picture is not seeked, and with movie-credits auto-skip turned on it still does not seek. Afterwards, Your TheIntroDB submissions is unchanged.

### Phase 2

Local downloaded movie, tail frames, credits and scene warning only. Still no intro scan, no stream, no Android, no submit.

Work:

- `MovieTailDetector.kt` in `features/analysis`, a pure function from frame stats plus an energy series to credits and optional scene, with the floors above. Tests in `commonTest` on synthetic rows: upward text on black, a quiet non-black ending, music under a real roll, a scene that returns, a dark scene that must not warn, a second roll after the scene.
- `MediaAnalyzer.swift`: the two-pass tail, stats only, abort flag, no JPEG. Return JSON the scheduler already knows how to parse (`RawMediaAnalysis` or a sibling type).
- `MediaAnalysisScheduler.kt`: movies only (`seasonNumber == null`), after the audio pass, not while playing. Bump `MEDIA_ANALYSIS_DERIVED_VERSION`.
- `PlayerMediaAnalysis.kt`: emit `movie-credits` (not `outro`) for a high score, and a `post-credits` interval only as a scene marker that does not seek. Skip the submit-strip pre-fill. Provider `on-device`.
- `MediaAnalyzerPlatform.ios.kt` if a new bridge method is required. Android actual stays no-op.

Risks: CPU and heat if the dense pass runs on the whole tail instead of the black run; a color credit sequence never matches and that is acceptable; a false high score shows a skip. The synthetic fixtures are the guard. The first IPA that includes this pass needs the three-step device test again, on a downloaded movie that has no TheIntroDB credits row, plus one that does (published time must still win and the scanner must not override it).

Verification: `./gradlew :composeApp:testAndroidHostTest` for the detector. The Swift pass needs a Mac IPA. Device: (1) download a movie with no published credits, leave the phone on the charger, wait until the analysis row finishes, (2) play and confirm either "Skip Credits" with the On-device chip or no button, and confirm a known post-credit title shows the warning and does not seek, (3) confirm Application Support did not gain a JPEG for the tail and the submissions list is unchanged.

### Phase 3

Intro on a local file (approach B), then a Wi-Fi-only stream tail using the `ClipFingerprintRun` open path extended to video stats, then an Android sampler behind `MediaAnalyzerPlatform.android.kt`. Each of those is its own change. Stream scans stop when the seek fails. Cellular stays off. Intro guesses use the same 0.75 floor and the same "do not submit, do not pre-fill" rule.

## Step B, one run

Build Phase 1 only. It fits one medium or high implementation pass: a handful of Kotlin files, host unit tests, no Swift, no new dependency, no schema bump. The user-visible result is that a movie TheIntroDB or IntroDB already knows, including credits that run to the end, gets "Skip Credits", and a movie with a real gap or an IntroDB post-credit scene gets "Watch credits" / "Scene after credits" and is not seeked. Guesses are not in this slice, so there is nothing new that could be submitted.

Leave Phase 2's sampler and `MovieTailDetector` for a later run. Shipping the detector without frames would invite an energy-only button, and energy-only is the false-positive path this note rejects.
