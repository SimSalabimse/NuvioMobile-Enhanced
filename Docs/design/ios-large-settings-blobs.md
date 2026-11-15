# iOS large settings blobs

Design only. No application code in this change.

On iOS, almost every persisted string goes through `NSUserDefaults.standardUserDefaults` (`setObject` / `stringForKey`). There is no multiplatform-settings wrapper on this path. A few sync caches use a named suite (`NSUserDefaults(suiteName = ...)`), which is a separate plist. Android already uses one `SharedPreferences` file per store.

`standardUserDefaults` is one preferences domain. The first read in a process parses that whole domain. On a cold start that first read is `profile_payload` inside `ProfileRepository.loadCachedProfiles`, called from `AppGate` and again from `StartupPreloader`. Keys the feature code does not touch until later are still in that parse. A value that is large, or a crowd of medium values, slows that parse. A large string is also a poor fit for the preferences domain: the process can be killed, or the preferences daemon can be under memory pressure, before the write lands. Watch progress and the local library must not depend on that.

Named suites are parsed only when that suite is first touched. They do not inflate the first `standardUserDefaults` read. They do run during startup when the tracking lane calls `ensureLoaded` on the matching provider.

## What is already a file

Leave these alone.

| Data | Where | Why it stays |
| --- | --- | --- |
| Home catalog snapshot (rows and hero items) | `HomeSnapshotStore` via `AppFileCache` | `Library/Caches/NuvioFileCache/home/<hash>.json`. `StartupPreloader` step `homeSnapshot` reads this file, not defaults. |
| Plugin scraper source | `PluginStorage` in `iosFull` | `Library/Application Support/nuvio_plugin_scrapers/<profileId>/<digest>.js`. The defaults blob `plugins_state_<profileId>` stores metadata with `code = null`. |
| Profile Insight title facts | `ProfileTitleFactsStorage.ios.kt` | `Library/Caches/NuvioProfileInsight/title_facts.json`. `writeToFile(..., atomically = true)` is the existing atomic-write pattern. |
| Membership images, subtitle fonts, download bytes | their own file helpers | Image and font bytes are already files. |

`AppFileCache` is the wrong root for watch progress and the library. Its iOS root is `Library/Caches`, which the system may delete.

## Size estimates

No device plist was measured, and the app was not launched, so there is no cold-start millisecond in this note. The figures below are estimates from the serialized shape.

Method: compact JSON of one representative object, with a short title, a ~340 character description where the model has one, and image URL fields around 100 characters. Nullable fields were included as `null` because the codecs that set `encodeDefaults = true` write defaults. Counts then use the caps in the code, or "no cap" when the code has none. `MAX_PROFILES` is 6, and every profile's keys share the standard domain even though startup reads the active profile.

| Shape | One item | Scaled |
| --- | --- | --- |
| `WatchProgressEntry` | about 1.2 KB | stored list is uncapped. The on-screen continue-watching list is capped at 20 (`DefaultContinueWatchingLimit`). 500 stored entries are about 0.6 MB per profile. |
| `LibraryItem` | about 1.1 KB with a description, about 0.8 KB without | no item cap. 1,000 items are about 1.1 MB per profile. Trakt library entries are the same type, once per list. |
| `StoredWatchedItemGroup` with 10 episodes | about 1.1 KB | episode history is uncapped. |
| `CachedStreamLink` with empty header maps | about 0.5 KB | no entry cap. A stale entry is removed only when that title is read. |
| One OMDb season, 12 episodes | about 150 bytes | `MaxCachedSeasons` is 400, about 70 KB at the cap. |

A real library with long plot text, or a stream-link cache with header maps, will be larger than these rows. A debug log of `payload.length` (the count, never the body) is the way to rank a given device.

## Inventory

Owner paths are the iOS writers. Android twins use the same key inside a private `SharedPreferences` file (`nuvio_library`, `nuvio_watch_progress`, and so on). "Startup" means the feature code reads the key during `AppGate` or `StartupPreloader`, including provider `ensureLoaded` on that lane.

### Standard domain, worth moving

| Key | Owner | Typical size (estimate) | Read at startup | Written |
| --- | --- | --- | --- | --- |
| `library_payload_<profileId>` | `features/library/LibraryStorage.ios.kt` | about 1.1 KB per item, no cap, up to 6 profiles in the one domain | yes (`library`) | each local library change and each applied sync |
| `trakt_library_payload_<profileId>` | `features/trakt/TraktLibraryStorage.ios.kt` | same `LibraryItem` shape, one array per Trakt list, no cap | yes, tracking lane | after a Trakt library refresh |
| `simkl_sync_snapshot_<profileId>` | `features/simkl/SimklSyncStorage.ios.kt` | one row per show or movie, plus nested seasons and episodes, no cap | yes, tracking lane | each Simkl sync |
| `watch_progress_payload_<profileId>` | `features/watchprogress/WatchProgressStorage.ios.kt` | about 1.2 KB per entry, stored list uncapped | yes (`watchProgress`) | every 60s during playback (`PlaybackProgressPersistIntervalMs`), on seek after 700ms, and on pause or stop. Overlapping flushes collapse to one write |
| `watched_payload_<profileId>` | `features/watched/WatchedStorage.ios.kt` | grouped by title; about 1.1 KB per show with 10 episodes | yes (`watched`) | each watched-state change and sync |
| `stream_link_<hash>_<profileId>` | `features/streams/StreamLinkCacheStorage.ios.kt` | about 0.5 KB per title before header maps; one key per title; no cap | no | when a reusable playback link is saved |
| `binge_group_<hash>_<profileId>`, `binge_group_fn_<hash>_<profileId>`, `binge_group_ih_<hash>_<profileId>` | `features/streams/BingeGroupCacheStorage.ios.kt` | short strings, up to three keys per title, no cap | no | when a binge group, filename, or info hash is remembered |
| `collections_payload_<profileId>` | `features/collection/CollectionStorage.ios.kt` | folders and sources. Usually tens of KB. No hard cap | no | each collection edit |
| `installed_manifest_urls_<profileId>` | `features/addons/AddonPlatform.ios.kt` | newline-separated manifest URLs, usually a few KB | no. `AddonRepository.initialize` runs from `MainAppContent` | install, remove, reorder |
| `installed_manifest_enabled_states_<profileId>` | `features/addons/AddonPlatform.ios.kt` | one line per installed manifest | same as the URL list | each enable or disable |
| `plugins_state_<profileId>` | `features/plugins/PluginPlatform.ios.kt` (full flavor) | scraper metadata. Source text is already a file | no | plugin list changes |
| `downloads_payload_<profileId>` | `features/downloads/DownloadsStorage.ios.kt` | one `DownloadItem` carries URLs, header maps, and subtitle requests. Queue-sized | no | status changes immediately; byte progress at most every 5s |
| `omdb_episode_ratings_cache` | `features/details/OmdbEpisodeRatingsStorage.ios.kt` | capped near 70 KB | no | when a season's ratings are stored |
| `cw_enrichment_cache_<source>_<profileId>` | `features/watchprogress/ContinueWatchingEnrichmentStorage.ios.kt` | derived continue-watching rows, one key per progress source. Legacy key `cw_enrichment_cache_<profileId>` is removed by the cache itself | no | when the home continue-watching rows are rebuilt |
| `library_release_schedule_payload_<profileId>` | `features/library/LibraryReleaseScheduleStorage.ios.kt` | one event per episode in the loaded calendar months. Rebuildable from the library | no | when the library calendar is saved |
| `avatar_catalog_payload` | `features/profiles/AvatarStorage.ios.kt` | catalog metadata (ids and paths). Image bytes are already files | yes. `AppGate` calls `AvatarRepository.fetchAvatars`, which hydrates this key | catalog fetch; refresh interval 15 minutes |
| `member_background_catalog_payload` | `features/membership/MemberAssetStorage.ios.kt` | catalog metadata. Image bytes are already files | when membership backgrounds load, not on the preload lane | catalog fetch |
| `episode_release_notifications_payload_<profileId>` | `features/notifications/EpisodeReleaseNotificationsStorage.ios.kt` | followed shows plus cached notification requests | yes (`releaseNotifications`) | follow list or schedule changes |
| `favorite_channel_ids_blob_<profileId>` | `features/livetv/LiveTvStorage.ios.kt` | one id per favourite channel. Grows with a large channel list. The channel list itself is not stored | yes (`liveTv`) | each favourite edit |
| `playlists_blob_<profileId>`, `epg_sources_blob_<profileId>` | `features/livetv/LiveTvStorage.ios.kt` | playlist and EPG source records (name and URL), not the guide programmes | yes (`liveTv`) | when those lists are edited |

`settings_<scraperId>` (`PluginPlatform.ios.kt`) is scraper settings JSON. Size depends on the scraper. Treat it as a small settings bag unless a length log shows otherwise.

Player track preferences (`PlayerTrackPreferenceStorage.ios.kt`) are not one JSON document. Each title writes about fifteen short keys (`subtitle_type_<id>_<profileId>` and the matching audio, subtitle, speed, and skip keys) with no cap. They inflate the same standard domain. Move them in their own later change, after the single-blob store exists. This note does not fold them into the first migration.

### Named suites

These are separate plists. Moving them speeds the tracking-lane read, not the first `standardUserDefaults` parse.

| Key | Suite | Owner | Typical size (estimate) | Read at startup | Written |
| --- | --- | --- | --- | --- | --- |
| `profile.<profileId>` | `nuvio_mdblist_sync` | `MdbListSyncStorage.ios.kt` | watched rows, playback, and library lists with items. No local item cap beyond what the sync stored | yes, if that provider is on the tracking lane | after each sync |
| `profile.<profileId>` | `nuvio_anilist_sync` | `AniListSyncStorage.ios.kt` | list entries, id mappings, and at most 200 local playback rows (`MAX_LOCAL_PLAYBACK`) | same | sync, and local playback after 30s (`LOCAL_PERSIST_DELAY_MS`) |
| `profile.<profileId>` | `nuvio_wetrakr_sync` | `WeTrakrSyncStorage.ios.kt` | lists, episodes, playback, ratings, and at most 200 local playback rows | same | sync, and local playback after 30s |

### Stay in defaults

Small values, secrets, and flags. Examples: `profile_payload` (at most 6 profiles), theme and player scalars, `catalog_settings_payload_<profileId>` (one small object per home row; about 15 KB at 100 rows), `search_history_payload_<profileId>` (capped at 10), `continue_watching_preferences_payload_<profileId>` except that `dismissedNextUpKeys` is uncapped and should be rechecked if a length log grows, auth payloads, server configuration, and the Live TV Stalker and Xtream records (they contain passwords). `member_access_payload` is an access record, not a catalog.

`PlatformLocalAccountDataCleaner.ios.kt` deletes a fixed key list and then scans `dictionaryRepresentation()` for `stream_link_` and `cw_enrichment_cache_`. That scan parses the whole standard domain again. After a key moves to a file, the cleaner has to delete the file as well, or a sign-out leaves the copy on disk.

## Blob store

One small type in `commonMain`. No `java.io`, no `platform.Foundation`, no path APIs.

```kotlin
internal interface LargeBlobStore {
    fun read(name: String): String?
    /** False when the write does not land or the read-back differs. */
    fun write(name: String, payload: String): Boolean
    fun remove(name: String)
}
```

Migration is a pure function in `commonMain` that takes two stores and a predicate:

```kotlin
internal fun readMigrated(
    name: String,
    file: LargeBlobStore,
    legacy: LargeBlobStore,
    usable: (String) -> Boolean,
): String?
```

`usable` is the existing decoder's success, not its "corrupt means empty list" fallback. `LibraryStoragePayloadCodec.decode` and `WatchProgressCodec.decodePayload` return an empty payload when JSON fails. Using that as `usable` would treat a damaged file as a successful empty library and then delete the old key. The migration predicate has to be the `runCatching` result. An empty string is a real value (Trakt library clear writes `""`) and counts as usable.

### iOS file store

New type in `iosMain`, next to `AppFileCache` but with a different root.

- Directory: `NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)` plus `NuvioBlobStore/<storeName>/`. `SubtitleFontFiles` already uses Application Support this way.
- One file per defaults key. The file name is the key. Reject a name that contains `/` or `..`. Current keys do not.
- Write to `<name>.tmp` in the same directory, then `moveItemAtPath` over the destination. `NSString.writeToFile(..., atomically = true)` matches `ProfileTitleFactsStorage` and is enough if the Boolean result is checked. Read the destination back and require an exact match before returning true.
- `durable` flag on the store. Durable data (library, watch progress, watched, collections, addon list, plugin metadata, downloads) stays eligible for device backup, which is how the preferences domain behaves today. Regenerable caches set `NSURLIsExcludedFromBackupKey` on the file. Caches: stream links, binge groups, OMDb ratings, continue-watching enrichment, avatar catalog, background catalog, release-schedule calendar.
- The store is not locked. Callers already serialize their own writes (`persistWriteLock` for watch progress, `persistenceLock` for the library).

### Android

Keep today's `SharedPreferences` for every store. That is the smaller change.

Android already isolates each store in its own XML file, so a large library is not parsed when the theme is read. The failure this design is answering is the single iOS preferences domain. A file migration on Android would add a second copy of the same state machine and a second chance to drop a library, without that payoff.

The `iosMain` actual of a store composes `MigratingBlobStore(file, legacyDefaults)`. The `androidMain` actual keeps the `putString` / `getString` calls it has now. The shared type is the interface and the migration function, which the Android actual does not call.

A later Android file port can implement `LargeBlobStore` with the same temp-and-rename pattern as `AppFileCache.android.kt` (`context.filesDir`, not `cacheDir`, for durable stores). Do that only if a length log shows an Android XML file is itself a problem.

## Migration, per store

Same steps for every store. One store per pull request.

1. Read the file.
2. File missing: read the old defaults key. If the old value is usable, write the file, read it back, and compare. Remove the old key only after that compare succeeds. If the write or the compare fails, return the old value and leave the old key.
3. File present and usable: use the file. If the old key is still there, remove it only after a read-back of the file matches the usable payload. That covers a crash between the write and the `removeObjectForKey`.
4. File present and not usable: if the old key is usable, return it and try to replace the file. Remove the old key only after the replacement read-back matches. If the replacement fails, leave both copies and keep serving the old key. If the old key is missing, return null and leave the bad file in place. Do not write an empty payload over it. Do not rename or delete it until a later successful write replaces it.
5. Both missing, or both unusable: return null. The repository keeps its current empty-state behaviour. Do not persist that empty state back.

Watch progress and the library never take the "both unusable, pretend it was empty, then save" path. Their codecs may still decode a failed read as empty for the UI, which is the behaviour they have today. The new code must not follow that with `savePayload("")`.

Subsequent saves go to the file only. A failed file write leaves the previous file and any leftover defaults key untouched, and the repository keeps the in-memory snapshot it already has.

### Order

Smallest risk first. Regenerable single keys before uncapped user data. The biggest cold-start cut is not the first pull request.

| Step | Store | Risk | Cold-start effect |
| --- | --- | --- | --- |
| 1 | `omdb_episode_ratings_cache` | one key, capped, rebuilt by fetching ratings, not read at startup | small. Proves the store and the tests |
| 2 | `cw_enrichment_cache_*` | rebuilt from watch progress. Account wipe already scans these keys | small |
| 3 | `stream_link_*` and `binge_group_*` | rebuilt on the next play. Many keys. Wipe must delete the files. Highest chance of being the largest resident of the standard domain, because nothing evicts unread entries | large if the cache has grown, and it comes off the plist that profile load parses |
| 4 | `avatar_catalog_payload`, `member_background_catalog_payload` | rebuilt from the network. Avatar catalog is on the `AppGate` path | moderate, early read |
| 5 | `library_release_schedule_payload_*` | rebuilt from the library | small |
| 6 | `episode_release_notifications_payload_*`, `favorite_channel_ids_blob_*`, playlist and EPG source blobs | user lists, startup reads, usually smaller than a library | moderate |
| 7 | `installed_manifest_urls_*`, `installed_manifest_enabled_states_*`, `collections_payload_*`, `plugins_state_*` | user configuration. Addon and collection lists are what people notice if a migration drops them. Still smaller than a library | moderate. Addon init is after the shell; the bytes are still in the standard plist |
| 8 | `downloads_payload_*` | user queue. Writes every few seconds while a download runs | usually small |
| 9 | named suites `nuvio_mdblist_sync`, `nuvio_anilist_sync`, `nuvio_wetrakr_sync` | separate plists. Same migration. Loss hurts a connected account's offline library | the tracking lane, not the first defaults parse |
| 10 | `simkl_sync_snapshot_*`, `trakt_library_payload_*` | standard domain, uncapped, startup, can mirror the whole remote library | large on a connected account |
| 11 | `watched_payload_*` | startup, user history | large once episode history is long |
| 12 | `watch_progress_payload_*` | startup, written during playback, must not be lost | large |
| 13 | `library_payload_*` | startup, descriptions, no cap, must not be lost | the largest explicit startup read in the standard domain |

The store with the most cold-start win is the largest resident of the standard domain, because that domain is parsed on the first profile read.

From the model, that is `library_payload_*` plus `trakt_library_payload_*` (same fat item, startup, no cap), with `simkl_sync_snapshot_*` and `watch_progress_payload_*` next, and `stream_link_*` able to pass all of them on a device that has saved links for a long time. This note does not pick a winner among those four without a length log. Step 3 is the safe way to get a large cut if the log shows the stream-link keys dominate, because a lost link is fetched again. Steps 12 and 13 are the durable startup reads. They wait until the migration tests have shipped on a cache.

## Files to touch

First pull request (step 1):

- `composeApp/src/commonMain/kotlin/com/nuvio/app/core/storage/LargeBlobStore.kt` — interface and `readMigrated` / `writeMigrated`.
- `composeApp/src/commonTest/kotlin/com/nuvio/app/core/storage/BlobMigrationTest.kt` — in-memory fake.
- `composeApp/src/iosMain/kotlin/com/nuvio/app/core/storage/IosFileBlobStore.kt` — Application Support, atomic write, backup flag.
- `composeApp/src/iosMain/kotlin/com/nuvio/app/core/storage/IosDefaultsBlobStore.kt` — read and remove one key. Writes to defaults stop once the file read-back has succeeded.
- `composeApp/src/iosMain/kotlin/com/nuvio/app/features/details/OmdbEpisodeRatingsStorage.ios.kt` — call the migration.
- `composeApp/src/androidHostTest/...` — add the commonMain API scan described below. `androidMain` OMDb storage stays as it is.

Later stores only change their `*.ios.kt` storage object, plus `PlatformLocalAccountDataCleaner.ios.kt` when the key is in the wipe list (`library_payload_`, `watched_payload_`, `watch_progress_payload_`, `stream_link_`, `cw_enrichment_cache_`, `collections_payload`, `plugins_state_`, and the other keys in that file). Android `*.android.kt` files stay on `SharedPreferences` until someone deliberately ports a store.

`commonMain` must stay free of JVM-only and Native-only APIs. This repository has no type named `CommonMainJvmOnlyApiTest` (the tree was searched). `java.io` inside `commonMain` still compiles for the Android target, so `Build fullDebug APK` would stay green while the iOS compile failed. The first implementation pull request should add an `androidHostTest` that walks `src/commonMain/kotlin` and fails on `import java.` or `import platform.`. That is the gate the Android CI job can actually run. Native calls (`NSFileManager`, `NSSearchPathForDirectoriesInDomains`, `NSURL` resource values, `ExperimentalForeignApi`) stay in `iosMain`.

## How to verify

Unit tests use an in-memory `LargeBlobStore` (a map, plus a flag that makes `write` return false). Cases:

| State | Expect |
| --- | --- |
| Old key only | Read returns the old text. File contains the same text. Old key is gone. |
| File only | Read returns the file. Old key is not consulted for the value. |
| Both, file usable | Read returns the file. Old key is removed after read-back. |
| Both, file not usable, old key usable | Read returns the old text. File is replaced. Old key is removed only after the new read-back matches. |
| File not usable, no old key | Read returns null. The bad bytes are still in the file store. |
| Old key only, write returns false | Read returns the old text. Old key is still there. File is absent. |
| Usable file, later write returns false | Read still returns the previous file. |

One extra case for the library and watch-progress codecs, when those stores move: a payload that throws in `decodeFromString` must not count as usable, even though the codec's public `decode` returns an empty object.

No on-device number was measured for this design. An implementation pull request can record two numbers without logging bodies:

- Sum of `payload.length` for the store being moved, printed once at migration.
- The existing `StartupPreloader` line `preloaded in <ms>ms` (`App/StartupPreload`), before and after a store that the preload lane reads. Profile load is the first `standardUserDefaults` parse, so a stream-link move can change time before that log line. Time from process start to the `profiles` preload step is the matching measurement for the standard domain.

## First pull request

Ship step 1 only: the `commonMain` interface and migration, the iOS file store, the migration tests, the commonMain import scan, and `OmdbEpisodeRatingsStorage` on iOS. Android OMDb storage stays on `SharedPreferences`.

Leave for later: every other row in the order table, player-track key fan-out, Android file storage, and any change to `AppFileCache` or the home snapshot.

## Risks

- Deleting the defaults key before a matching read-back drops the only good copy.
- Treating a decode failure as an empty library or empty watch history, then saving, wipes the user. The migration predicate must see the failure.
- Account wipe that only clears defaults leaves files behind after a store has moved.
- Putting durable blobs in `Library/Caches` (the `AppFileCache` root) lets the system delete watch progress and the library.
- Setting `NSURLIsExcludedFromBackupKey` on durable files drops them from device backup. Use that flag for caches only.
- `java.io` or `platform.Foundation` in `commonMain` passes the Android APK build and fails the iOS compile. The new host test is what makes the Android job catch it.
- Logging a payload can leak a stream URL or a header. Log lengths only.
