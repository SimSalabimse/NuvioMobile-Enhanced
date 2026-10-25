# Nuvio Enhanced (mobile): features and settings

Built from the code at build 195 (0.6.2.1). Each line shows the feature, where it is in the app, and the build or version it was added in when the changelog records it ("upstream" means it came from the base app). Settings pages are under the Settings tab.

## Playback
- Player with gestures (swipe to seek, hold for speed and a hold-speed value): Settings › Playback › Player / Touch Gestures. Upstream.
- Playback engine and decoder (libmpv renderer, hardware decoding, YUV420P compatibility, DV7→HEVC fallback, audio passthrough, buffers, VOD disk cache): Settings › Playback › Decoder. Upstream.
- iOS audio output and video output (hardware decoder, extended dynamic range, colour hints): Settings › Playback › iOS Audio/Video Output. Upstream.
- Preferred and secondary audio and subtitle languages, strip SDH, forced subtitles, show only preferred languages, auto-show subtitles on rewind or mute: Settings › Playback › Subtitle and Audio. Upstream.
- Subtitle rendering (size, offset, bold, colours, outline, libass): Settings › Playback › Subtitle Rendering. Upstream.
- Audio and subtitle choices and playback speed remembered per show: automatic, in the player. 0.5.10.3.
- Sleep timer: player › speed popup. 181.
- Mini player (off by default): Settings › Playback › Mini Player. 0.5.10.1; default off in 190.
- Private Viewing, Content Warnings, Pause Overlay, Loading Overlay, Movie Recommendations: Settings › Playback › Player. Upstream.
- Playback debug readout (position, duration and every skip segment with its source): Settings › Playback. 190.
- External player app (sends subtitles and intro/outro times): Settings › Playback › Player. Upstream.
- Seek bar thumbnails: Settings › Playback; downloads use on-device scrub previews. 0.5.10.2.
- Auto-play next episode, binge group, preloading next-episode sources, threshold mode, and the up-next countdown: Settings › Playback › Next Episode. Upstream.
- Stream auto-play (first stream or regex, source scope, allowed addons and plugins), reuse last link: Settings › Playback › Stream Auto-Play / Stream Selection. Upstream.
- Debrid not cached: every play and download entry point (Continue Watching menu, episode rows, show page, season sheet, Library, search, smart downloads, finish a series, sources list, player source switch, next episode, auto-play) goes through one shared fallback that tries the next good source (up to 3) and says which one it switched to. 193–194; all entry points since 195, checked by DebridSourceGuardTest.
- Add to TorBox: when a source isn't cached and nothing else works, it can be added to TorBox (create torrent, not only-if-cached) to be ready later, from any play or download entry point (one app-wide prompt). 194; everywhere since 195.
- P2P streaming (torrent profile, cache size, hide stats): Settings › Playback › P2P Streaming. Upstream.
- Watch Party: Settings › Account › Watch party, nuvio:// party links. 173.
- Subtitle Auto Sync (subtitles synced to the audio): Settings › Playback › Auto Sync Subtitle; for downloads, Settings › Downloads › Sync subtitles to audio. 0.5.10.1.
- Reopening the app from a Live Activity closes the open player instead of leaving it playing hidden. 193.

## Skip times, analysis, TheIntroDB
- Skip Intro / Automatic Skipping, choosing segment types (intro, recap, credits): Settings › Playback › Skip Segments. Upstream; recaps 0.5.10.2.
- Per-show auto-skip (Default / Always / Never): Home Continue Watching hold menu › "Auto-skip: …". Shortened label in 193.
- TheIntroDB community times, "TheIntroDB only", on-device fallback: Settings › Playback › Skip Segments. 0.5.10.3.
- TheIntroDB import of your own submissions, labelled "TheIntroDB (yours)" (same label in the player and the skip list, 196): Settings › Playback › TheIntroDB import / Your TheIntroDB submissions. 0.5.10.3; same-length check in 192.
- Intro submission (IntroDB and TheIntroDB API keys): Settings › Playback › Skip Segments. Upstream.
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
- While watching an unfinished download, that file keeps downloading whatever the playback cap; an episode waiting for the cap shows a still clock in the player, not a spinner: automatic. Checked by PlaybackThrottleRepositoryTest.
- An unfinished download opens at the resume point only when its bytes on disk reach it (otherwise the stream plays); reaching the end of the downloaded part never marks the episode watched or moves the saved position past it: automatic. Checked by PartialFileEndTest.
- Keep free space (storage autopilot frees watched downloads): Settings › Downloads › Keep free space. 181.
- Delete when finished (auto-delete watched downloads): Settings › Downloads. Upstream/enhanced.
- Switch to the downloaded file while watching: Settings › Downloads. Enhanced.
- Show download button, Download Location, Reset to Default: Settings › Downloads. Upstream.
- Resume validation (same file and exact position, otherwise restart) and damaged-download check: automatic. 186.
- Download Live Activity / Dynamic Island with progress and speed (iOS); tapping it opens Downloads: automatic. 0.5.9.1.
- Downloads search, storage screen, offline artwork for downloaded items: Library › Downloads. 0.5.10.2.

## Library
- Library from Nuvio, Trakt, Simkl, MDBList, AniList, WeTrakr: Settings › Account › Tracking › Library Source. Upstream.
- Horizontal shelves or vertical grid with section, type and sort filters: Library tab. Upstream.
- **Random pick** (shuffle button on each shelf header, "Random" chip in grid view): Library tab. 193.
- Release calendar: Library tab. Upstream.
- Cloud library (debrid/TorBox): Library tab, Settings › Debrid › Cloud library. Upstream.
- Library lists editor: Library tab. Upstream.
- Media servers library: Settings › Content & Discovery › Media servers. Upstream.

## Home
- Hero section, trailers, catalogs, catalog type labels, hide unreleased: Settings › Layout › Home Layout. Upstream.
- Continue Watching (show/hide, episode thumbnails, Next Up rules, sort order, resume prompt on launch, pause untouched items): Settings › Layout › Continue Watching. Upstream/enhanced.
- Finish a series row: Settings › Layout › Continue Watching › Finish a series. 178.
- Up Next Tonight: Home. 181.
- Continue Watching hold menu (details, play manually, start from beginning, download, auto-skip, Finish a series, remove): Home. Long labels wrap since 193.

## Details and discovery
- Episode Shuffle (random episode): details page, Settings › Layout › Detail Page › Shuffle. 188.
- Cast search: Search. 180.
- Detail page layout (background mode, poster transition, tab layout, blur unwatched): Settings › Layout › Detail Page. Upstream.
- Search with recent searches: Search tab, Settings › Content & Discovery. Upstream.
- Addons, plugins, media servers: Settings › Content & Discovery. Upstream.
- TMDB enrichment modules, MDBList and OMDb ratings: Settings › Integrations. Upstream.
- Live TV (playlists, EPG): Settings › Integrations › Live TV. Upstream.

## Profiles, sync and the app
- Profiles, Account, Nuvio Sync, Trakt, Simkl, MDBList, AniList, WeTrakr scrobbling: Settings › Account. Upstream.
- Watch stats / profile insights: Settings › Profile. 0.5.10.2.
- Episode release alerts: Settings › Notifications. Upstream.
- Debrid (accounts, link preparation, result filters and formatting): Settings › Integrations › Connected Services. Upstream.
- Appearance (themes, AMOLED, Liquid Glass tab bar, app icon, language, navigation bar, poster card style): Settings › Layout. Upstream.
- What's New, check for updates, debug logs, Sentry crash reports: Settings › About / Advanced. Upstream/enhanced.

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
