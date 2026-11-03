# Long-press accessibility

Semantics only. No layout, motion, or visible copy changes. New strings live in `composeApp/src/commonMain/composeResources/values/strings.xml` (English only, matching recent string additions).

Activating a custom action runs the same callback the long-press already runs. A single Downloads action (cancel the season, or re-analyze) therefore runs that handler. A multi-entry menu announces every entry, and activating any entry opens that same menu. It does not fire one row by itself.

## Removed: none

- No action, button, gesture, list, setting, or state was removed or hidden.
- Long-press, tap, and existing visible buttons stay wired to the same handlers.
- Decorative icons that sit next to text keep `contentDescription = null`.
- Player hold-to-speed is unchanged. A custom action would leave the speed boost on, so it is not announced.

## Sites

| Site | Installer | What VoiceOver / TalkBack hears |
| --- | --- | --- |
| Downloads active row | `DownloadsScreen.DownloadRow` | Cancel season, when the row belongs to an unfinished season group |
| Downloads completed item | `DownloadsScreen.DownloadRow` | Re-analyze |
| Downloads completed show | `DownloadsScreen.downloadsRootContent` | Re-analyze |
| Offline home resume | `OfflineDownloadsHome.ResumeCard` | Details, watched toggle, remove. Dimmed cards set the same actions inside `clearAndSetSemantics` |
| Offline home title | `OfflineDownloadsHome.PosterTile` | Details, library toggle, watched toggle |
| Poster shelf | `HomePosterCard`, `PosterGrid.PosterGridTile`, `CatalogScreen.CatalogPosterTile` | Details, library toggle, watched toggle |
| Continue watching | `ContinueWatchingCard`, `ContinueWatchingWideCard`, `ContinueWatchingPosterCard`, `FinishSeriesCard` | Details (hidden for cloud items), watched toggle, remove |
| Episode | `EpisodeHorizontalCard`, `EpisodeListCard`, `RatingMapCellView` | Watched, previous episodes, season, rate. Sheet menus also announce mark-up-to-here, download, and play manually when those rows exist |
| Season | Season chip and `SeasonPosterButton` | Season watched toggle, rate, previous seasons |
| Stream card | `StreamCard` | Pin (when the source can be pinned), copy link, open internal/external player, download. Only when the long-press already opens the menu |
| Stream source header | `StreamSectionHeader`, `StreamSourceHeader` | Pin or unpin |
| Provider filter chip | `ProviderFilterRow.FilterChip` | Pin or unpin, when the chip can open the pin sheet |
| Detail play | `PlayButton` | Play manually |
| Detail save | `DetailIconAction` | Library lists |

`HomeCatalogSection`, `LibraryScreen`, and `DetailPosterRailSection` pass their long-press into `HomePosterCard`.

Continue watching rows that the sheet shows only sometimes (play manually, start over, download, finish-series goal, auto-skip) are not named separately. Their visibility is decided in the sheet, not on the card.

## Player icon names

`SubtitleStepperButton` decrease and increase, the player download button (including progress and spinner), and the live-channel search clear button now have names. Skip-step icons, checkmarks, artwork, and the search glyph next to the field stay unnamed because the control or neighbouring text already has a name.

## Not covered

- Compose semantics are not executed in a UI test. `LongPressAccessibilityTest` and `PlayerIconAccessibilityTest` check the label table.
- iOS and Kotlin/Native are not compiled here. No VoiceOver device run.
- Hold-to-speed and the optional continue-watching rows above.

Device check: iOS Settings > Accessibility > VoiceOver on; on Downloads, swipe up/down on a row: you hear the actions menu entries.
