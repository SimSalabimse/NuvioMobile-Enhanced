# Nuvio Enhanced (mobile): features and settings

Built from the code at build 198 (0.6.5.1). This comparison build merges the luqmanfadlli enhanced tag 0.6.5-beta (313ca3d1) on top of the player tools work and the download-reason work. Docs/sync-overlaps-0.6.5.md lists each overlap and the decision. The tools panel, marking strip, Play range, Sync strip, TheIntroDB Flag, download reasons, Storage, Diagnostics and Auto Sync are still here. Notch spacing and the player button bar are the fork's. Sync is our bottom strip. The title fades while Sleep or Speed is open, and the seek bar does not move. "You're watching" stays hidden while a panel or strip is open. Each line shows the feature, where it is in the app, and the build or version it was added in when the changelog records it ("upstream" means it came from the base app). Settings pages are under the Settings tab.

## Playback
- Player with gestures (swipe to seek, hold for speed and a hold-speed value): Settings › Playback › Player / Touch Gestures. Upstream.
- Playback engine and decoder (libmpv renderer, hardware decoding, YUV420P compatibility, DV7→HEVC fallback, audio passthrough, buffers, VOD disk cache): Settings › Playback › Decoder. Upstream.
- iOS audio output and video output (hardware decoder, extended dynamic range, colour hints): Settings › Playback › iOS Audio/Video Output. Upstream.
- Preferred and secondary audio and subtitle languages, strip SDH, forced subtitles, show only preferred languages, auto-show subtitles on rewind or mute: Settings › Playback › Subtitle and Audio. Upstream.
- Subtitle rendering (size, offset, bold, colours, outline, shadow, libass on by default): Settings › Playback › Subtitle Rendering. Upstream; shadow and imported subtitle fonts in 0.6.4.1. An imported font is used for Latin subtitles; Chinese, Japanese and Korean keep the bundled face.
- Player tools open in one right-hand panel (Subtitles, Audio, Sources, Episodes, Info, Video settings) over the playing video. Subtitles keeps Off, language tracks, a pinned Sync row and Style in the same panel. The older separate subtitle, audio, sources, episodes, playback-info and video-settings panels were replaced. Sync drops the panel into a bottom strip (−0.1 / +0.1, Heard it / Saw it, Reset, Done). While that strip is open the subtitle cue is lifted by the strip height and put back on Done. Reset also clears a half-finished Heard it / Saw it. Unwatched episode pictures blur again when that setting is on, and a failed or unfinished episode list offers Retry. Changing episode closes the open tool. Aspect, speed, brightness, contrast, saturation and tone-map stay on the Video tab, with Reset pinned.
- Audio and subtitle choices and playback speed remembered per show: automatic, in the player. 0.5.10.3.
- Sleep timer: player › speed popup. 181.
- Mini player (off by default): Settings › Playback › Mini Player. 0.5.10.1; default off in 190.
- Private Viewing, Content Warnings, Pause Overlay, Loading Overlay, Movie Recommendations: Settings › Playback › Player. Upstream.
- Playback debug readout (position, duration and every skip segment with its source): Settings › Playback. 190.
- Skip backward and skip forward lengths: Settings › Playback. Upstream 0.6.4.
- Tap the on-screen duration to switch between elapsed time and time left. The clock time the stream ends sits above the seek bar. Upstream 0.6.4.
- Player badges for what is playing, and a choice of badge pack: player. Upstream 0.6.3–0.6.4.
- Switch playback engine from the player, with a confirmation first. Upstream 0.6.3.
- Player controls fade out instead of disappearing at once. Upstream 0.6.4.
- External player app (sends subtitles and intro/outro times): Settings › Playback › Player. Upstream.
- Seek bar thumbnails: Settings › Playback; downloads use on-device scrub previews. 0.5.10.2. The fork's faster thumbnail path is in 0.6.5.1.
- Player buttons: Settings › Playback › Player buttons. Choose which buttons stay on the bar, which sit behind the arrow, and their order. 0.6.5.1 from the fork. Mark segment and Live TV stay on the bar as extra icons. The arrow grows that row.
- While Sleep or Speed is open, the title, version and episode fade out and the seek bar stays put.
- Show Chapters applies to every engine, with marks on the seek bar and a chapters list. 0.6.5.1 from the fork.
- Pinch to zoom shows a zoom icon while the picture scales. 0.6.5.1 from the fork.
- TMDB title language and original-language titles, logos, posters and backdrops: Settings › TMDB, and the home hero. 0.6.5.1 from the fork.
- Auto-play next episode, binge group, preloading next-episode sources, threshold mode, and the up-next countdown: Settings › Playback › Next Episode. Upstream.
- Stream auto-play (first stream or regex, source scope, allowed addons and plugins), reuse last link: Settings › Playback › Stream Auto-Play / Stream Selection. Upstream.
- Debrid not cached: every play and download entry point (Continue Watching menu, episode rows, show page, season sheet, Library, search, smart downloads, finish a series, sources list, player source switch, next episode, auto-play) goes through one shared fallback that tries the next good source (up to 3) and says which one it switched to. 193–194; all entry points since 195, checked by DebridSourceGuardTest.
- Add to TorBox: when a source isn't cached and nothing else works, it can be added to TorBox (create torrent, not only-if-cached) to be ready later, from any play or download entry point (one app-wide prompt). 194; everywhere since 195.
- P2P streaming (torrent profile, cache size, hide stats): Settings › Playback › P2P Streaming. Upstream.
- Watch Party: Settings › Account › Watch party, nuvio:// party links. 173. Party chat from the phone uses the same profile, and the party's source is shown as plain text. Upstream 0.6.3.
- Subtitle Auto Sync (subtitles synced to the audio): Settings › Playback › Auto Sync Subtitle; for downloads, Settings › Downloads › Sync subtitles to audio. 0.5.10.1.
- Reopening the app from a Live Activity closes the open player instead of leaving it playing hidden. 193.

## Skip times, analysis, TheIntroDB
- Skip Intro / Automatic Skipping, choosing segment types (intro, recap, credits): Settings › Playback › Skip Segments. Upstream; recaps 0.5.10.2.
- Per-show auto-skip (Default / Always / Never): Home Continue Watching hold menu › "Auto-skip: …". Shortened label in 193.
- TheIntroDB community times, "TheIntroDB only", on-device fallback: Settings › Playback › Skip Segments. 0.5.10.3.
- TheIntroDB import of your own submissions, labelled "TheIntroDB (yours)" (same label in the player and the skip list, 196): Settings › Playback › TheIntroDB import / Your TheIntroDB submissions. 0.5.10.3; same-length check in 192.
- Intro submission (IntroDB and TheIntroDB API keys): Settings › Playback › Skip Segments. Upstream.
- Submit Timestamps is a bottom marking strip on the playing video (Intro, Recap, Credits, Preview; movies are Intro and Credits). Mark start and end at the playhead, nudge them, and Submit stays on screen. The seek bar zooms to about two minutes around the range and stays disabled when the length is unknown. Play range plays the marked range and puts the picture back where it was, including if the strip is closed mid-preview. Play range is optional and is not required before Submit. The series segment chip is still called Preview. Beside those chips, a fixed button follows the selected segment: No intro, No recap, No credits, or No preview. Tapping it marks that segment absent. The strip then shows the line that this sends 00:00:00 for the start and end and still sends the stream length, and Submit sends that empty segment to TheIntroDB (start 0, end 0, plus the stream length). Tapping the button again, or editing a start or end time, returns to marking a range. An absent segment and a timed range are never sent together. IntroDB refuses an empty segment and keeps that refusal line, and a movie with an IntroDB key shows the IntroDB movie message. A failed send shows the real reason. The Flag in the top pill opens the same strip pre-filled; Mark segment in the more menu opens it blank. A missing key is a row in the strip that opens the existing key dialog. The older timestamp dialog is no longer opened from the player.
- AnimeSkip: Settings › Playback › Skip Segments. Upstream.
- Ask after Skip (Good/Wrong): Settings › Playback › Skip Segments. 0.5.10.3.
- Your marks and shared nuvio://skip links: player skip menu. 191/192.
- On-device analysis of downloads (intros/credits, recaps, loudness, scrub previews, subtitle sync, "analyse only while charging"): Settings › Downloads (analysis section). 0.5.10.1–0.5.10.2.
- Sibling clip (fingerprints a nearby episode's intro window from its stream): automatic. 189.
- Analysis queue order: lowest season and episode first, per show. 193.
- "Playing an episode that isn't analysed yet" prompt (Play now / Finish first / In background / Don't ask again): Settings › Playback. Only shown when the file has no stored analysis (193).

## Downloads
- Download one episode or movie, or a whole season (Download Season sheet with auto-pick): details page / streams list. 0.5.10.3.
- Download quality preference, plus a confirmation showing the size: Settings › Downloads › Download quality. 0.5.10.2.
- **Allow mobile data**: Settings › Downloads (top). Upstream.
- **Only on Wi-Fi** (smart downloads, queued episodes and Finish-a-series goals wait for Wi-Fi; on by default): Settings › Downloads. 0.5.10.2. Shown even with Smart downloads off since 193; shows 'Waiting now: …' since 194.
- Only while charging: Settings › Downloads. 0.5.10.2.
- Smart downloads, "Episodes to keep ready" (1–3): Settings › Downloads. 0.5.10.2.
- Finish a series (flag a show; downloads continue from the furthest watched episode): details chip, Continue Watching hold menu, flag badge on cards. 178.
- Finish a series with Smart downloads on: keeps exactly 'Episodes to keep ready' downloaded, lowest first, topped up as you watch. 194.
- **Finish a series downloads the rest** (only with Smart downloads off; on: every remaining episode, lowest first; off: the next one): Settings › Downloads. 193; scoped in 194.
- Queue unaired episodes: Settings › Downloads. Upstream/0.5.10.x.
- Pause downloads while watching: Settings › Downloads. 181.
- Downloads throttled during playback (1 while playing, 0 while buffering or with Pause while watching) resume by themselves when the cap rises or the player closes, and new ones start; a Smart download whose debrid link fails is retried in 1 h with the reason shown instead of being dropped: automatic. Checked by PlaybackCapRestartTest and SmartDownloadRetryTest.
- While watching an unfinished download, that file keeps downloading whatever the playback cap, also when it was waiting in line (it takes the slot); an episode waiting for the cap shows a still clock in the player, not a spinner: automatic. Checked by PlaybackThrottleRepositoryTest.
- A Downloads row shows Downloading only while its transfer runs and Waiting only while none does, also for a transfer stopped while it was still opening; a file that finishes as the cap stops it shows Completed. A row the open player holds back says "Waiting while you watch" with its bytes; after a stall the 30 s cool-down also runs out while paused or at the end of the episode: automatic. Checked by PlaybackThrottleRepositoryTest.
- An unfinished download opens at the resume point only when its bytes on disk reach it (otherwise the stream plays); reaching the end of the downloaded part buffers until the download is further and plays on by itself, also when the file finishes there: no Play next, no watched mark, no jump to the start. If the download is not moving, the player says why (paused, failed, no space, Wi-Fi, or paused while you watch): automatic. Checked by PartialFileEndTest.
- Pause downloads while watching starts nothing new (a file already running may keep going). A progress or finish report from an older attempt cannot overwrite the current one: automatic. Checked by PlaybackThrottleRepositoryTest.
- Keep free space (storage autopilot frees watched downloads): Settings › Downloads › Keep free space. 181.
- Delete when finished (auto-delete watched downloads): Settings › Downloads. Upstream/enhanced.
- Switch to the downloaded file while watching: Settings › Downloads. Enhanced.
- Show download button, Download Location, Reset to Default: Settings › Downloads. Upstream.
- Resume validation (same file and exact position, otherwise restart) and damaged-download check: automatic. 186.
- Download Live Activity / Dynamic Island with progress and speed (iOS); tapping it opens Downloads: automatic. 0.5.9.1.
- Season rows on Downloads: time left is never shorter than the slowest running episode (queued episodes add to it); with several episodes running the speed is labelled "total": Library › Downloads. Checked by SeasonEtaTest.
- Downloads search, storage screen, offline artwork for downloaded items: Library › Downloads. 0.5.10.2.
- A download that is not moving shows one short reason on its row (paused while you watch, slowed while the stream buffers, waiting behind other downloads, waiting for Wi-Fi, device too hot, or retrying) and no reason while it is moving: Library › Downloads. The line follows the device within about 2 s and clears when the cause is gone. Checked by DownloadSlowReasonTest.
- The Storage card on Downloads keeps a fixed-height placeholder until storage has been read, so a cold start does not shift the list: Library › Downloads.

## Library
- Library from Nuvio, Trakt, Simkl, MDBList, AniList, WeTrakr: Settings › Account › Tracking › Library Source. Upstream.
- Horizontal shelves or vertical grid with section, type and sort filters: Library tab. Upstream.
- **Random pick** (shuffle button on each shelf header, "Random" chip in grid view): Library tab. 193.
- Release calendar: Library tab. Upstream. The week starts on Monday. Upstream 0.6.4.
- Cloud library (debrid/TorBox): Library tab, Settings › Debrid › Cloud library. Upstream.
- Library lists editor: Library tab. Upstream.
- Media servers library: Settings › Content & Discovery › Media servers. Upstream.

## Home
- Hero section, trailers, catalogs, catalog type labels, hide unreleased: Settings › Layout › Home Layout. Upstream. The hero also shows ratings and the age rating. Upstream 0.6.4.
- Continue Watching (show/hide, episode thumbnails, Next Up rules, sort order, resume prompt on launch, pause untouched items): Settings › Layout › Continue Watching. Upstream/enhanced.
- Finish a series row: Settings › Layout › Continue Watching › Finish a series. 178.
- Up Next Tonight: Home. 181.
- Continue Watching hold menu (details, play manually, start from beginning, download, auto-skip, Finish a series, remove): Home. Long labels wrap since 193.
- A show counts as finished only once its last aired episode passes 90% of a known runtime (the player's end flag counts only when the runtime is unknown), so a half-watched last episode stays a resume: Continue Watching, details button, Library. Automatic.

## Details and discovery
- Episode Shuffle (random episode): details page, Settings › Layout › Detail Page › Shuffle. 188.
- Cast search: Search. 180.
- Detail page layout (background mode, poster transition, tab layout, blur unwatched): Settings › Layout › Detail Page. Upstream.
- Search with recent searches: Search tab, Settings › Content & Discovery. Upstream.
- Addons, plugins, media servers: Settings › Content & Discovery. Upstream.
- TMDB enrichment modules, MDBList and OMDb ratings: Settings › Integrations. Upstream.
- Rating map (colour-coded episode ratings) on show and anime pages: Settings › Layout. Upstream 0.6.4. OMDb season ratings are cached so a cancelled load can run again.
- OMDb season ratings cache is locked against parallel season fetches, and a ratings failure keeps the details page with the data it has instead of crashing iOS: automatic.
- Live TV (playlists, EPG): Settings › Integrations › Live TV. Upstream.

## Profiles, sync and the app
- Profiles, Account, Nuvio Sync, Trakt, Simkl, MDBList, AniList, WeTrakr scrobbling: Settings › Account. Upstream. The active profile is shown as a card with its avatar. Upstream 0.6.4.
- In-app rating can be turned off per profile: Settings › Account › Tracking. Upstream 0.6.3.
- Discord Rich Presence: Settings › Account. Upstream 0.6.3. Login on iOS and the Keychain token are in 0.6.5.1 from the fork.
- iPad side bar (duo): when the system provides a vertical bar, Home, details, streams, library and the profile picker sit beside it. Upstream 0.6.4. The player turns that bar off.
- Watch stats / profile insights: Settings › Profile. 0.5.10.2.
- Episode release alerts: Settings › Notifications. Upstream.
- Debrid (accounts, link preparation, result filters and formatting): Settings › Integrations › Connected Services. Upstream.
- Appearance (themes, AMOLED, Liquid Glass tab bar, app icon, language, navigation bar, poster card style): Settings › Layout. Upstream.
- What's New, check for updates, debug logs, Sentry crash reports: Settings › About / Advanced. Upstream/enhanced.
- Backup export and import: Settings › Advanced. Upstream 0.6.4.
- DNS over HTTPS: Settings › Advanced › Network. Upstream 0.6.4. Android only; iOS keeps the system DNS.
- Performance (optimization switches and launch, playback and memory readouts): Settings › Advanced › Performance. 196.
- Startup preload also runs on iOS (after the first home frame, off the main thread), so Settings › Performance shows a Startup preload time and Parallel startup applies: automatic.

## Parked / ideas
- Movie intro/credits detection: PARKED.
- Post-credits detection: PARKED.
- Rest of the UI audit: PARKED.
- Next-episode-downloaded note: PARTIAL. The Finish-a-series chip shows "The next episode is downloaded"; there's no note in the player or on Continue Watching.
- Auto-delete watched downloads: EXISTS ("Delete when finished", plus Keep free space).
- Wi-Fi-only downloads: EXISTS ("Only on Wi-Fi", "Allow mobile data").
- Sleep timer: EXISTS (player speed popup).
- Smart downloads (keep the next 2–3): EXISTS ("Episodes to keep ready" 1–3).
- Auto-skip recaps: EXISTS (recap segment type plus on-device recap detection).
- Up-next countdown: EXISTS (next-episode auto-play countdown).
- Download quality picker with size: EXISTS (Download quality plus a size confirmation in the quality sheet).
- Watch stats: EXISTS (Profile insights / WatchStats).
- Offline mode: PARTIAL (downloads keep their artwork offline; there's no app-wide offline mode).
