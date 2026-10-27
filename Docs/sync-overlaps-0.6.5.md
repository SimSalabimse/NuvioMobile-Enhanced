# Overlaps in the 0.6.5 comparison build

This build merges fork commit 313ca3d1 (tag 0.6.5-beta on luqmanfadlli/NuvioMobile-Enhanced) into the player-tools work at 28e4b858 plus the download-reason work. Where both sides already did the same job in different code, the fork's version is what runs. The list below is every one of those overlaps.

To put our version back for one item, use the commit and file named on that item. 28e4b858 is the player-tools build. 6c278407 is that build plus the download-reason commit, before this merge.

Features that exist only on our side were left as they are: the tools panel, the marking strip, Play range and Submit, the Sync strip, TheIntroDB Flag and H:MM:SS submit, download reasons, Storage, Diagnostics, and Auto Sync.

## 1. Title and episode text next to the notch

Files: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerControls.kt` (the padding on the title block, the chapter row, and the parental-guide card) and `PlayerLayout.kt` (`playerTextCutoutPadding`).

The fork adds the same extra inset on the left and the right, taken from the wider screen cutout after the normal player padding. That is commits a66716a4 and 40692f0a, now inside 313ca3d1.

Our build at 28e4b858 pulls only the start edge back with `playerTextStartPadding` (commit a32ba36c1, still defined in `PlayerLayout.kt`).

The fork's inset is the one in this build, because this build is for comparing that spacing. It treats both edges the same, so a notch on either side clears the text.

On a phone in landscape, the title, version and episode sit further from the island than they do on the 28e4b858 build.

Both paddings cannot be on the same text. Choose one before merging. To restore ours, put the `textStart` / `textStartExtra` padding from `git show 28e4b858:composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerControls.kt` back on those three spots. `playerTextStartPadding` is still in `PlayerLayout.kt`.

## 2. Title block while Sleep, Speed, or the button row is open

File: `PlayerControls.kt`, the `PlayerTimelineDetails` call in the new layout.

The fork leaves the title, version and episode on screen.

Our 28e4b858 build sets that block's alpha to 0 while the speed popup or the more menu is open (`coverTitle`), so the words do not sit under those controls. The block stays in the layout, which is why the seek bar does not jump.

This build uses the fork: the words stay visible. The seek bar still does not move, because the title composable is still there.

On a phone, open Sleep or Speed and the title stays readable over the video.

Choose one. To restore the hide, put the `coverTitle` graphics layer from that same 28e4b858 `PlayerControls.kt` function back on `PlayerTimelineDetails`.

## 3. Player buttons versus the more menu

Files: `PlayerControlActions.kt`, `PlayerToolbarLayout.kt`, `PlayerToolbarSettingsDialog.kt`.

The fork's Settings › Playback › Player buttons list decides which buttons stay on the bar, which sit behind the arrow, and the order. The arrow grows the same bar (commit 329a9830). It does not open a popup, so the clipped-menu bug does not come back.

Our 28e4b858 build opens a popup from that arrow (`PlayerToolsMenu` in `PlayerControlActions.kt`, commits 3ffd92e8d and 28e4b858) so the menu is not clipped and the controls do not jump.

This build uses the fork's bar. Mark segment and Live TV are extra icons on the bar, because the fork has no buttons for them. Flag stays on the top pill. Submit intro is also in the fork's button list, hidden behind the arrow unless that setting shows it, so expanding the row can show Flag twice.

The fork's bar is what matches the new setting. Our popup is the one that was fixed so it does not clip.

On a phone, Settings › Playback › Player buttons changes the row, and the arrow slides more buttons into that row instead of opening a menu.

The arrow cannot do both. Choose before merging. To restore the popup, replace `PlayerControlActions` from `git show 28e4b858:composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerControlActions.kt` (the `PlayerToolsMenu` function and the primary / menu split). Keep the fork's `playerControlActions()` list, because the folded-phone layout calls it.

## 4. Chapters list versus marks on the seek bar

Files: `PlayerChaptersPanel.kt`, `PlayerScreenRuntimeChapters.kt`, and the mark helpers in `PlayerChapters.kt` (`chapterMarkFractions`, `PlayerChapterLabel`).

The fork opens a chapters list from the right edge and seeks when a row is tapped.

Our marks are ticks on the seek bar and the chapter name above the bar. Those helpers were already on 28e4b858 in `PlayerChapters.kt`.

The list is the fork's. The ticks and the name stayed, because they do not replace the list.

On a phone, the seek bar has tick marks and a chapter name, and the chapters button opens the fork's list.

Both can stay in one build. To drop the list, remove the `PlayerChaptersPanel` call in `PlayerScreenRuntimeUi.kt`. To drop the ticks, remove `chapterMarkFractions` from `PlayerTimeline.kt`.

## 5. Pinch-zoom icon

Files: `PlayerOverlays.kt` (`GestureFeedbackIcon.VideoZoom`) and `PlayerScreenRuntimeGestureActions.kt` (the zoom limits).

The fork shows a zoom icon while a pinch is changing the picture (commit 86ee5aac).

Our pinch already scaled the picture. It had no icon.

This build keeps our zoom and shows the fork's icon. They fit together.

On a phone, pinching the picture scales it and a zoom icon appears.

Both can stay. To remove only the icon, drop the `VideoZoom` branch in `PlayerOverlays.kt` from 313ca3d1.

## 6. Sync card versus the Sync strip

Files: `SubtitleSyncByEarCard.kt` (fork) and `PlayerToolsOverlay.kt` (`SubtitleSyncStrip`).

The fork floats a Heard it / Saw it card at the top of the video.

Our strip sits on the bottom of the video with nudge buttons, Heard it, Saw it, Reset and Done (28e4b858, `PlayerToolsStrips.kt`).

This build shows both when Sync is open, because the strip was kept and the fork's card was also taken. They do the same job and will look stacked.

On a phone, open Sync and a card is at the top while the strip is at the bottom.

Choose one before merging. To keep only the strip, delete the `SubtitleSyncByEarCard` call in `PlayerScreenRuntimeUi.kt`. To keep only the card, delete the `if (showSubtitleSyncByEar)` block in `PlayerToolsOverlay.kt`.

## 7. Pause overlay ("You're watching")

File: `PlayerScreenRuntimeUi.kt`, the `AnimatedVisibility` around `PauseMetadataOverlay`. The helper is `pauseOverlaySuppressed()` in `PlayerToolsRuntime.kt`.

The fork hides that overlay when the phone is folded (`fold == null`).

Our 28e4b858 build hides it while a tool panel or strip is open, so it does not sit behind them.

This build uses both checks. Taking only the fork's check would show "You're watching" behind the tools panel and the strips again, so our check stays on top.

On a phone, pause with the tools panel or the Sync strip open and the "You're watching" card stays hidden. On a folded phone it also stays hidden.

Both checks can stay. To drop ours, remove `!pauseOverlaySuppressed()` from that `visible` line.

## 8. Where episodes, sources, and submit intro open

Files: `PlayerScreenRuntimeSourceActions.kt` (`openSourcesPanel`, `openEpisodesPanel`), `PlayerToolsOverlay.kt`, `PlayerScreenModalHosts.kt`.

The fork opens episodes, sources, and submit intro as the older side panels (`PlayerEpisodesPanel`, `SubmitIntroDialog`, `PlaybackInfoModal` in its `PlayerScreenModalHosts.kt` and `PlayerScreenRuntimeUi.kt`).

Our tools panel replaced those panels (28e4b858, commit 8066d7ae7).

This build keeps the tools panel. The fork's side-panel wiring was not connected, because the task keeps the tools panel and two panels for the same tap would both open.

On a phone, Sources, Episodes and Flag open the tools panel and the marking strip, not the fork's old side panels.

Choose before merging if those old panels should come back. To restore them, take `PlayerScreenModalHosts.kt` and the `PlayerScreenModalHosts(` call from `git show 313ca3d1:composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeUi.kt`.

## 9. TMDB enrichment cache

File: `TmdbMetadataService.kt`, the cache fields and `cachedOriginalTitle`.

The fork stores enrichments in a map with a lock and can read a cached original title.

Our build used `BoundedCache` for those same maps (6c278407).

This build uses the fork's maps and original-title lookup. `seasonEpisodeCache` stays a `BoundedCache`, because the fork has no such cache and the season loader still uses it.

On a phone the cache type is invisible. Original-language titles on the hero are the fork's feature and are on.

The overlapping caches cannot be both types. To restore our caches, put the `BoundedCache` fields from `git show 6c278407:composeApp/src/commonMain/kotlin/com/nuvio/app/features/tmdb/TmdbMetadataService.kt` back, and keep `cachedOriginalTitle`.

## 10. Emby and Jellyfin HTTP client

Files: `EmbyProvider.kt` and `JellyfinProvider.kt`, the `http` default argument.

The fork builds a new client with `createApiHttpClient()`.

Our build passed `sharedApiHttpClient` (6c278407).

This build uses the fork's client so the title-lookup methods compile with it.

On a phone there is no new control. Title lookup between episodes uses the fork's client.

Choose the client before merging. To restore ours, set `http: HttpClient = sharedApiHttpClient` in both files from 6c278407.

## 11. In-app update catalog

File: `store.json`, the first versions.

The fork's newest row is the 0.6.5 IPA (build 148).

Our newest row was the 0.6.4.1 sideload IPA (build 197) at 6c278407.

This build lists the fork's 0.6.5, 0.6.4 and 0.6.3 rows first, then our older sideload rows. There is no 0.6.5.1 IPA.

On a phone, Check for updates can offer the fork's 0.6.5 IPA.

Choose before any release. To put our 0.6.4.1 row back on top, copy that object from `git show 6c278407:store.json`.

## Layout fixes that still hold

- The more-menu popup is not in this build, so it cannot clip or jump. Restoring it must use the 28e4b858 menu, not an older popup.
- The marking chip row still scrolls inside the panel (`PlayerToolsPanel.kt`, `ChipLine`). The fork has no chip row.
- "You're watching" stays hidden behind panels and strips (`pauseOverlaySuppressed()`), and also while the phone is folded.
