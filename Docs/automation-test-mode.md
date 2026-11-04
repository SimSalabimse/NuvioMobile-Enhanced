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

The mode is not drawn. The root view exposes accessibility identifier `nuvio_automation_test_mode` only while the mode is on. One stdout line names the namespace id and nothing else (no keys, addon URLs, or stream URLs).

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

Reads of catalogs, addon manifests, and streams stay on the network. These do not:

- Account sync pull and push (Supabase profile, library, watched, progress, settings, addons)
- Offline outbox send
- Trakt and Simkl scrobble, history, and watchlist writes
- MDBList `POST`/`PUT`/`DELETE` (including scrobble and list writes). `GET` stays
- Push: episode-release notification scheduling and authorization prompts
- TheIntroDB `POST /v4/submit` and `POST /v4/map`, and IntroDB `POST /submit`

The submit sheet still runs. With no saved key (the real key is not loaded) the TheIntroDB path is the one exercised, and the HTTP call is replaced by the fake status. `GET /v4/media` is unchanged and does not send a key.

## Risks

- Download video files still share the downloads directory. The download index is namespaced, so this process does not list or delete the owner's rows. A new download uses a new file name.
- Supabase will not refresh the session. A long harness run can outlive the access token. That is deliberate: refreshing would invalidate the saved refresh token.
- Plugin scraper code is not copied. Addon manifests are.
- A bug that writes `NSUserDefaults` with a raw key, bypassing `isolateStorageKey` / `ProfileScopedKey`, would still hit the owner. Identity stores above are also write-blocked at the save function. The catalog in `AutomationStoreCatalog` is what the host tests check.
