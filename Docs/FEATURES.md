# Nuvio Enhanced (mobile): features and settings

Built from the code at build 198 (0.6.5.1). This comparison build merges the luqmanfadlli enhanced tag 0.6.5-beta (313ca3d1) on top of the player tools work and the download-reason work. Docs/sync-overlaps-0.6.5.md lists each overlap and the decision. The tools panel, marking strip, Play range, Sync strip, TheIntroDB Flag, download reasons, Storage, Diagnostics and Auto Sync are still here. Notch spacing and the player button bar are the fork's. Sync is our bottom strip. The title fades while Sleep or Speed is open, and the seek bar does not move. "You're watching" stays hidden while a panel or strip is open. Each line shows the feature, where it is in the app, and the build or version it was added in when the changelog records it ("upstream" means it came from the base app). Settings pages are under the Settings tab.

## Playback
- Player with gestures: Settings › Playback › Player › Touch gestures (Off, No swipe-seek, or All) and Hold to speed (Off or a speed). Upstream controls, one picker each.
- Playback engine and decoder (libmpv renderer, hardware decoding, YUV420P compatibility, DV7→HEVC fallback, audio passthrough, buffers, VOD disk cache): Settings › Playback › Decoder. Upstream.
- iOS audio output and video output (hardware decoder stays here): Settings › Playback › iOS Audio/Video Output. Extended dynamic range, display colour hint, target primaries and target transfer are under Settings › Playback › Advanced. Upstream.
- Preferred and secondary audio and subtitle languages, strip SDH, forced subtitles, show only preferred languages, auto-show subtitles on rewind or mute: Settings › Playback › Subtitle and Audio. Upstream.
- Subtitle rendering (size, offset, bold, colours, outline, shadow, libass on by default): Settings › Playback › Subtitle Rendering. Upstream; shadow and imported subtitle fonts in 0.6.4.1. An imported font is used for Latin subtitles; Chinese, Japanese and Korean keep the bundled face.
- Player tools open in one right-hand panel (Subtitles, Audio, Sources, Episodes, Info, Video settings) over the playing video. Subtitles keeps Off, language tracks, a pinned Sync row and Style in the same panel. The older separate subtitle, audio, sources, episodes, playback-info and video-settings panels were replaced. Sync drops the panel into a bottom strip (−0.1 / +0.1, Heard it / Saw it, Reset, Done). While that strip is open the subtitle cue is lifted by the strip height and put back on Done. Reset also clears a half-finished Heard it / Saw it. Unwatched episode pictures blur again when that setting is on, and a failed or unfinished episode list offers Retry. Changing episode closes the open tool. Aspect, speed, brightness, contrast, saturation and tone-map stay on the Video tab, with Reset pinned.
- Audio and subtitle choices and playback speed remembered per show: automatic, in the player. 0.5.10.3.
- Sleep timer: player › speed popup. 181.
- Mini player (off by default): Settings › Playback › Mini Player. 0.5.10.1; default off in 190.
- Private Viewing, Content Warnings, Pause Overlay, Loading screen (Off, Plain, or With status), Movie Recommendations: Settings › Playback › Player. Upstream. With status still shows the stall reason while buffering after playback has started.
- A slow open of a downloaded file on iOS is not treated as a stall until a playback time has been seen, or until 60 seconds.
- Playback debug readout (position, duration and every skip segment with its source): Settings › Playback › Advanced. 190. Legacy layout is in that section too.
- Skip backward and skip forward lengths: Settings › Playback. Upstream 0.6.4.
- Tap the on-screen duration to switch between elapsed time and time left. The clock time the stream ends sits above the seek bar. Upstream 0.6.4.
- Player badges for what is playing, and a choice of badge pack: player. Upstream 0.6.3–0.6.4.
- Switch playback engine from the player, with a confirmation first. Upstream 0.6.3.
- Player controls fade out instead of disappearing at once. Upstream 0.6.4.
- External player app (sends subtitles and intro/outro times): Settings › Playback › Player. Upstream.
- Seek bar thumbnails: Settings › Playback; downloads use on-device scrub previews. 0.5.10.2. The fork's faster thumbnail path is in 0.6.5.1.
- Player buttons: Settings › Playback › Player buttons. Choose which buttons stay on the bar, which sit behind the arrow, and their order. 0.6.5.1 from the fork. Live TV stays on the bar as an extra icon. The arrow grows that row.
- The player shows one Flag button: the top pill, the legacy header, or the fold row. The bottom-bar Flag and the mark-segment Flag opened the same submit bar, so they are not shown.
- While Sleep or Speed is open, the title, version and episode fade out and the seek bar stays put.
- Show Chapters applies to every engine, with marks on the seek bar and a chapters list. The settings row is hidden on iOS; the saved value is kept. 0.6.5.1 from the fork.
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
- With the submit bar open, play/pause and the seek bar stay visible and usable.
- Submit Timestamps opens one slim bar on the playing video, about 64 pt tall, inset by the safe area on every side so it stays clear of the Dynamic Island, the notch, and the home indicator in portrait and landscape. On a short window the bar shrinks and stays within an eighth of the video height. One row: Intro, Recap, Credits, Preview (movies: Intro and Credits), a Start chip and an End chip with the time, a short green length when the range is valid or the error in red, then No intro, No recap, No credits, or No preview, Submit, and close. Tapping a chip opens a popover above it with −5, −1, +1, +5 and Set to now. Holding −1 or +1 steps by 0.1 s. Clocks show whole seconds until a 0.1 step, in H:MM:SS. Tap outside or the same chip again closes the popover. No intro hides the chips and the popover, and Submit reads "No intro · S1 E22" (or the movie equivalent). Tapping the open tab returns to editing. The chip does not toggle off. The submit sends 0 and 0 plus the stream length. An end equal to the video length stays valid, and a time a hair past the length clamps to it. Start and end of the open segment are drawn on the player's own seek bar. IntroDB refuses an empty segment and keeps that refusal line, and a movie with an IntroDB key shows the IntroDB movie message. A failed send shows the real reason. The Flag in the top pill opens the same bar pre-filled; Mark segment in the more menu opens it blank. A missing key is a control in the bar that opens the existing key dialog. The older timestamp dialog is no longer opened from the player.
- AnimeSkip: Settings › Playback › Skip Segments. Upstream.
- Ask after Skip (Good/Wrong): Settings › Playback › Skip Segments. 0.5.10.3.
- Your marks and shared nuvio://skip links: player skip menu. 191/192.
- On-device analysis of downloads (intros/credits, recaps, loudness, scrub previews, subtitle sync, "analyse only while charging"): Settings › Downloads (analysis section). 0.5.10.1–0.5.10.2.
- Delete after watching and Smart Downloads keep a finished episode until its intro fingerprint is saved (or 30 minutes, or analysis fails for good), so the next episode still has a fingerprint to match. Automatic. The 30 minutes count only while analysis is allowed to run: with charger-only analysis on, time unplugged does not count. The last failed try releases the file.
- Analysis runs one pass at a time. A kick during that pass runs again when the pass finishes.
- Downloads analysis chips: episodes show Intro and Credits separately; movies show Credits only.
- Sibling clip (fingerprints a nearby episode's intro window from its stream): automatic. 189.
- Saving an episode fingerprint back-fills intro, credits, and recap for the other stored episodes of that season, including ones whose download is already gone.
- Season intro and credits take a start time other episodes agree on, and a later pass replaces a weaker score only when that segment was not marked.
- Intro and credits edges snap to a nearby silence, and credits stay in the tail with a three-minute cap.
- A transferred intro uses the shared audio in this file, a different cut is refused, and TheIntroDB is asked with the file length.
- A failed analysis or a sibling clip that could not be read is retried up to 3 times (after 2 minutes, then 4) and remembered across launches. A new download file starts the count over. Cancelling does not count.
- Analysis queue order: lowest season and episode first, per show. 193.
- "Playing an episode that isn't analysed yet" prompt (Play now / Finish first / In background / Don't ask again): Settings › Playback. Only shown when the file has no stored analysis (193).

## Downloads
- Download one episode or movie, or a whole season (Download Season sheet with auto-pick): details page / streams list. 0.5.10.3.
- Download quality preference, plus a confirmation showing the size: Settings › Downloads › Download quality. 0.5.10.2.
- **Allow mobile data**: Settings › Downloads (top). Upstream.
- **Only on Wi-Fi** (smart downloads, queued episodes and Finish-a-series goals wait for Wi-Fi; on by default): Settings › Downloads. 0.5.10.2. Shown even with Smart downloads off since 193; shows 'Waiting now: …' since 194.
- Only while charging: Settings › Downloads. 0.5.10.2. On Android, where on-device analysis is not available, this switch does not hold automatic downloads.
- Smart downloads, "Episodes to keep ready" (1–3): Settings › Downloads. 0.5.10.2.
- Per-series Smart Downloads count (Default, 0, 1, 2, 3, or 5): series page, under the action icons, or in the ⋯ actions menu. Default follows Episodes to keep ready; 0 turns smart next-episode downloads off for that series.
- Finish a series (flag a show; downloads continue from the furthest watched episode): details chip, Continue Watching hold menu, flag badge on cards. 178.
- Finish a series with Smart downloads on: keeps exactly 'Episodes to keep ready' downloaded, lowest first, topped up as you watch. 194.
- **Finish a series downloads the rest** (only with Smart downloads off; on: every remaining episode, lowest first; off: the next one): Settings › Downloads. 193; scoped in 194.
- Queue unaired episodes: Settings › Downloads. Upstream/0.5.10.x.
- Pause downloads while watching: Settings › Downloads. 181.
- Downloads throttled during playback (1 while playing, 0 while buffering or with Pause while watching) resume by themselves when the cap rises or the player closes, and new ones start; a Smart download whose debrid link fails is retried in 1 h with the reason shown instead of being dropped: automatic. Checked by PlaybackCapRestartTest and SmartDownloadRetryTest.
- While an episode plays, the next one starts early enough to finish when this one ends (length, playhead, playback speed, known size or bitrate times duration, a saved average of real download speed at 1 MB/s until one is measured, and a 20% margin). It starts on Wi-Fi only, skips Low Data Mode and metered Wi-Fi, needs free space for the file plus the usual reserve, waits while a download you started is running or queued, and does not start when the next episode is already saved, already queued, or has not aired: automatic. Checked by NextEpisodeDownloadScheduleTest.
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
- Posters and backdrops that were shown while online stay on disk and still show with no network, or when the image request fails. The disk copy is capped at 250 MB and drops the least recently used picture past that. While online, a copy still inside its cache lifetime is reused and an expired one is loaded again, including when the server sent no-store or a short max-age. A miss offline keeps the card placeholder instead of waiting on a spinner. Home, details, library, search.
- Continue Watching (show/hide, episode thumbnails, Next Up rules, sort order, resume prompt on launch, pause untouched items): Settings › Layout › Continue Watching. Upstream/enhanced.
- Offline Home Continue Watching shows the saved Continue Watching rows for the active profile, in that order. A downloaded episode or movie plays; a card that is not downloaded is dimmed and does not open. If that save is missing or unreadable, the row stays the downloaded episodes already started, and it hides when there are none.
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
- Opened titles stay on disk per profile (details, season and episode lists, OMDb season ratings, and Continue Watching rows). See Offline details below.
- Live TV (playlists, EPG): Settings › Integrations › Live TV. Upstream.

## Profiles, sync and the app
- Profiles, Account, Nuvio Sync, Trakt, Simkl, MDBList, AniList, WeTrakr scrobbling: Settings › Account. Upstream. The active profile is shown as a card with its avatar. Upstream 0.6.4.
- Cold launch with Remember Last Profile on opens that profile's Home on the first frame. The profile picker is not composed while storage is still being read, and the launch avatar cover is not drawn over Home.
- Cold launch reads the saved profile from device storage before the first frame. One profile without a PIN, or remember-last with a remembered profile without a PIN (any profile number), opens Home on that first frame and the picker is not drawn. The same saved profile opens the same screen online, offline, or when the network times out, including the start tab, and the saved avatar and name are already there. The wordmark is not held on screen. Account sync, the avatar catalog, addon manifest refresh and catalog refresh start after the first paint and stop after a few seconds if the network does not answer. If that work fails or times out, the next time the connection is back they run once; a second online signal while that refresh is still running does not start another. A first launch with nothing cached still waits for catalogs. Sign-in and a profile with a PIN still stop on their own screens.
- Live connection watcher: iOS uses NWPathMonitor and Android uses ConnectivityManager, and both update the shared network status. A drop goes offline immediately. A return is confirmed with one debounced probe, so a captive portal or Wi-Fi with no internet stays offline. Coming back online still retries the deferred startup work once.
- Offline banner: one line under the status bar, "Offline: showing saved data", after the connection has stayed offline for about a second. It hides as soon as the connection is back. A drop that recovers within a second does not show it. Reduce Motion skips the slide and fade. It is hidden on the full-screen player and during launch and profile selection. Tap it to check the connection once. Home, Library, Catalog, Search and Discover no longer add their own offline card.
- Offline account sync: watched marks, the latest watch progress for each title or episode, and Library adds and removes made while offline are kept in a per-profile outbox on the device. When the connection returns they are sent once, in that order, through the existing Trakt, Simkl, and Nuvio account sync. An entry stays until the account accepts it. A failed send is retried with backoff and, after three failures, waits for the next reconnect. A newer remote change wins over an older local one, including a remote removal against a local add. While a video is playing, watched marks can still be sent; watch progress and Library edits wait until the player closes.
- An offline unwatch is stamped with the time it was made, so a server watched time a few seconds newer does not mark that episode watched again.
- Long-press menus, the details page, and watched or unwatched toggles work offline for a saved or downloaded title: Home, Continue Watching, Library, downloaded movies and episodes, and a series page whose details or episode list is saved. Streams, trailers, remote search, and account-list sync stay disabled until the connection is back. An offline watched change shows at once and is kept in the account outbox.
- Coming back online fades the offline banner out over the screen, so the lists do not jump. Home stays on the downloads page until saved catalogs are on screen. Search, Discover, and Catalog keep the rows already showing while they refresh. Queued account edits send in the background with no toast. Reduce Motion snaps the fade.
- The offline account outbox is written to disk before the edit call returns, so the last change is still there if the app is killed.
- One offline sync entry that fails three times is held aside; the other queued entries still send.
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
- Offline mode: PARTIAL (downloads keep their artwork offline; opened titles and Continue Watching rows reopen from disk; there's no app-wide offline mode).
- Offline mode: the app-wide banner says saved data is on screen. Downloads still play, and the downloads-first home lists them.
- Offline mode: PARTIAL (downloads keep their artwork offline; posters and backdrops already seen stay in a 250 MB on-disk cache. There's no app-wide offline catalog).

## Offline details and Continue Watching

Opened titles stay available after a restart. The details page, its season and episode list, and the Continue Watching row are saved per profile. A profile only reads its own files. Switching profiles drops the in-memory copy so one profile cannot see another's titles.

Details keep the fields the page needs to draw: name, artwork, description, ratings, cast, trailers, and the episode list (season, episode, title, overview, release, and artwork URLs). Playback streams are not saved. Addon links and website URLs are not saved. An image or thumbnail URL that carries a token, key, or password in the query string is dropped rather than written to disk.

Season lists are saved beside the title. OMDb season ratings are saved separately, still per profile. Continue Watching keeps the next-up and in-progress rows it already stored (title, artwork, episode, progress). That row is stamped with the profile id and a schema version.

A saved title is fresh for 12 hours. Season and episode lists use the same 12 hours. OMDb season ratings stay fresh for 7 days, matching the existing complete-season rating lifetime. Continue Watching rows are fresh for 30 minutes.

Offline, a stale copy is still shown. Online, a stale copy is shown immediately and refreshed in the background. A refresh that times out leaves the saved copy on screen. A title that was never opened has nothing to show when the device is offline.

Titles are capped at 150 entries and about 8 MB, the same entry count as the in-memory details cache. The byte count is the JSON character length, which tracks the file size for this text. Season and episode lists are capped at 400 titles and about 2 MB. OMDb season ratings are capped at 400 seasons and about 1 MB. Continue Watching keeps the 60 most recent next-up rows and the 60 most recent in-progress rows for each profile and source.

When a cap is passed, the least recently opened entries are removed. A file whose schema version is not the current one is discarded. A file that cannot be read is ignored.

Disk reads run off the main thread, and only after a title or Continue Watching row is requested. Nothing is read during launch. Writes are batched, debounced by one second, and held while the player is open or a home, Continue Watching, upcoming, or details list is scrolling.
