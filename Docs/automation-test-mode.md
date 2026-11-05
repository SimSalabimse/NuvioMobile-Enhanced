# iOS automation test mode

A phone harness (taps, screenshots, rotation, scripted launches) can start the sideloaded release IPA with an explicit launch argument. That process uses a hidden profile namespace and never writes the owner's profile, progress, library, ledgers, or TheIntroDB account. A normal tap on the icon does not pass the argument, so it cannot enable the mode. There is no debug-only gate.

## Launch arguments

| Signal | Effect |
| --- | --- |
| `-NuvioAutomation` or env `NUVIO_AUTOMATION=1` | Turn the mode on for this process |
| `-NuvioAutomationReset` | Delete only the automation namespace, then start clean |
| `-NuvioAutomationSubmitResult=409` (also `429` or `200`; env `NUVIO_AUTOMATION_SUBMIT_RESULT`) | Fake TheIntroDB `POST /submit` and `POST /map` status. Default is a local success |

```
xcrun simctl launch booted <bundle> -NuvioAutomation
xcrun devicectl device process launch --device <udid> <bundle> -- -NuvioAutomation -NuvioAutomationReset
```

The mode is not drawn. The root view exposes accessibility identifier `nuvio_automation_test_mode` only while the mode is on. One stdout line names the namespace id and nothing else (no keys, addon URLs, or stream URLs). The profile name on screen is **Test**. Saved profile names are not written.

## Stub catalog, sources, and playback

While the mode is on, Home, Library, source lookup, debrid resolution, downloads, and the player stay inside the app. Nothing is sent to addons, TorBox, Real-Debrid, Trakt, Simkl, MDBList, TheIntroDB, AniList, or WeTrakr. The owner's addon list may still be copied into the automation keys, and it is not contacted.

`AutomationStubs` is the only seam. With the flag off, the same call sites use the real clients.

### What is on screen

The hidden profile's Home and Library show two artwork-free titles:

| Title | Id | Shape |
| --- | --- | --- |
| Automation Series | `automation:series` | 3 seasons, 3 episodes each (S1E1–S3E3), aired 2020-01-15 |
| Automation Movie | `automation:movie` | one movie |

Find sources, for any title, returns two stub sources from the addon name Automation:

| Source | Shown size |
| --- | --- |
| Automation 1080p | 1.2 GB |
| Automation 2160p | 3.4 GB |

Resolving either source returns a local file, not a debrid link. Season and episode downloads go through the normal queue (progress, completion, retry, delete-when-finished, already-in-downloads) and write that same clip. The file is `composeApp/src/commonMain/composeResources/files/automation/color-bars.mp4`: synthetic FFmpeg `testsrc` colour bars, 64×48, 8 seconds, no audio, **8720 bytes**.

### Phone check: season download

1. Launch with `-NuvioAutomation`. Add `-NuvioAutomationReset` for a clean automation namespace.
2. Confirm accessibility id `nuvio_automation_test_mode` and that the profile name is Test.
3. On Home (or Library), open **Automation Series**.
4. Open a season and tap **Download Season**.
5. Tap **Find sources**. The two Automation sources appear with sizes 1.2 GB and 3.4 GB.
6. Pick one and start the download.
7. The downloads queue runs and completes. The file on disk is the 8720-byte colour-bar clip. No addon or debrid account is used.

### Phone check: player submit bar

1. Launch with `-NuvioAutomation` and `-NuvioAutomationSubmitResult=409` (use `200` for the success pass).
2. Open **Automation Movie**.
3. Play it and choose **Automation 1080p** or **Automation 2160p**. The player opens the local 8-second clip.
4. Open the submit bar (Intro / Recap / Credits / Preview). The clip is long enough for a 5 second intro.
5. Submit. `409` shows Failed. Launch again with `200` and the same steps show sent.

## Isolation

Profiles already exist (`profile_payload`, `activeProfileIndex`, indexes 1..6). The harness does not become one of those profiles and does not change the saved pointer.

Hidden id: `8001` (outside 1..6, not listed in the profile picker).

`isolateStorageKey` is applied wherever a store key is built:

- A key that ends in `_1`..`_6` is rewritten to `_8001` (`watch_progress_payload_1` becomes `watch_progress_payload_8001`). `ProfileScopedKey` does this for settings, player prefs, Continue Watching enrichment, flag ledger, Trakt/Simkl/MDBList local payloads, and both intro-database API key fields.
- Any other app key is prefixed `nuvio_auto_` (ledgers such as `own_theintrodb_submissions_v1`, `skip_source_ledger_v1`, `analysis_temp_download_ids_v1`).
- The same key run through the function twice stays put.

`ProfileRepository.persist()` and `ProfileStorage.savePayload` do not run in this mode, so `profile_payload` (the active-profile pointer and the profile list) stays byte-for-byte. The next launch without the flag loads that pointer and opens Home as before.

### Isolated (own keys or folder)

- Watch progress `watch_progress_payload_*`, Continue Watching prefs, enrichment cache `cw_enrichment_cache_*`
- Library `library_payload_*`, library display and release schedule
- Watched `watched_payload_*`
- Downloads index `downloads_payload_*` and download settings
- Home catalog settings, collections, search history, theme, player settings, stream badges, track prefs
- Flag ledger, OwnTheIntroDb ledger, SkipSource ledger, analysis-temp id ledger
- Analysis files: `Application Support/NuvioAnalysisAutomation` instead of `NuvioAnalysis`. The Swift analyser writes `NuvioAnalysisThrottleLevel` and `NuvioAnalysisPlayerSessionOpen` under `nuvio_auto_`
- Plugin state `plugins_state_*`, scraper settings, scraper files under directory `8001`
- TheIntroDB key `the_intro_db_api_key_*` and legacy IntroDB key `introdb_api_key_*` (the real values are never read)
- Trakt/Simkl/MDBList local auth and library payloads (real tokens are not loaded)
- Offline account outbox
- PIN cache (keychain account and defaults) addressed as profile `8001`
- User ratings, shuffle, notifications prefs, live TV, debrid, p2p, TMDB settings, poster/card style

### Read-only (real key, writes dropped)

These stay on the owner's key so the process can still boot and browse. Saves, deletes, and wipes return without touching them.

- `profile_payload` (active profile pointer)
- `anonymous_user_id`
- `avatar_catalog_payload`
- `member_access_payload` and member catalog bytes (no delete of branding files)
- `server_*` backend configuration
- Supabase session storage: load only, no save/delete, no token refresh (a refresh would rotate the owner's refresh token)
- Profile biometric enable, disable, authenticate, and the launch cleanup of the legacy keychain item
- `LocalAccountDataCleaner.wipe` and sign-out

### Addons

Catalogs and streams need the same addon list. On the first automation launch, the owner's `installed_manifest_urls_<active>` and enabled-state string are copied once into the `_8001` keys. Later launches reuse that copy. Reset clears it and copies again. The owner's keys are never written. URLs are not logged. The copy is not pushed back to the account.

### Outward writes (stubbed)

Catalog, addon, and stream reads are stubbed too (see above). These writes do not leave the device:

- Account sync pull and push (Supabase profile, library, watched, progress, settings, addons)
- Offline outbox send
- Trakt and Simkl scrobble, history, and watchlist writes
- MDBList `GET`/`POST`/`PUT`/`DELETE` (including scrobble and list writes). The client returns an empty JSON object and does not open a socket
- Push: episode-release notification scheduling and authorization prompts
- TheIntroDB `POST /v4/submit` and `POST /v4/map`, and IntroDB `POST /submit`

The submit sheet still runs. With no saved key (the real key is not loaded) the TheIntroDB path is the one exercised, and the HTTP call is replaced by the fake status. `GET /v4/media` returns no segments and does not open a socket.

## Risks

- Download video files still share the downloads directory. The download index is namespaced, so this process does not list or delete the owner's rows. A new download uses a new file name.
- Supabase will not refresh the session. A long harness run can outlive the access token. That is deliberate: refreshing would invalidate the saved refresh token.
- Plugin scraper code is not copied. Addon manifests are.
- A bug that writes `NSUserDefaults` with a raw key, bypassing `isolateStorageKey` / `ProfileScopedKey`, would still hit the owner. Identity stores above are also write-blocked at the save function. The catalog in `AutomationStoreCatalog` is what the host tests check.
