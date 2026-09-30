# NuvioMobile-Enhanced

Personal mirror of [luqmanfadlli/NuvioMobile-Enhanced](https://github.com/luqmanfadlli/NuvioMobile-Enhanced) with **TheIntroDB** (`https://theintrodb.org`) skip-intro provider restored as a first-class source.

This repository provides:
- **TheIntroDB integration** (v3 API at `https://api.theintrodb.org/v3/media`) alongside the official introdb.app provider
- **Unsigned IPA builds** for SideStore / AltStore / TrollStore (no Apple certificate required)
- **Daily auto-sync** from upstream Enhanced `enhanced` branch. The schedule merges only; sideload publishing stays on Build Sideload IPA

> **Note:** This is not a GitHub fork because [SimSalabimse/NuvioMobile](https://github.com/SimSalabimse/NuvioMobile) already occupies the network fork slot for the original Nuvio repository.

## Building a Sideload IPA

### Manual Build

Go to **Actions → Build Sideload IPA → Run workflow** and select:
- **Configuration:** Release (production) or Debug (faster validation)
- **Publish release:** Yes to create a GitHub pre-release and update `store.json`

The workflow produces an unsigned IPA with:
```
CODE_SIGNING_ALLOWED=NO
CODE_SIGNING_REQUIRED=NO
CODE_SIGN_IDENTITY=
```

Install it with SideStore, AltStore, or TrollStore. These tools sign on-device using your Apple ID—no paid developer certificate is required.

### Test Build (No Release)

**Actions → Build Test IPA** runs a validation build without creating a release or updating `store.json`. Useful for quick testing before a full sideload build.

### Auto-Sync

- **Daily at 06:17 UTC:** Merges upstream `luqmanfadlli/NuvioMobile-Enhanced@enhanced` into this repo. The schedule does not build an IPA.
- **Manual trigger:** Actions → **Sync Enhanced upstream** (same merge).
- **Publish:** Actions → **Build Sideload IPA**, after the release-train manifest check. That run creates the GitHub pre-release and refreshes `store.json`.

### SideStore / AltStore Source

Add this source in SideStore or AltStore. The first version in the file is the update it installs, and that IPA is built from this repository:

```
https://raw.githubusercontent.com/SimSalabimse/NuvioMobile-Enhanced/enhanced/store.json
```

> **Important:** AltStore and SideStore cannot read private GitHub repositories or releases without authentication. This repository is public to support direct IPA distribution.

### Optional Secret

- **`NUVIO_LOCAL_PROPERTIES_BASE64`** — Base64-encoded `local.properties` file containing Trakt / Simkl API keys (same format used by official Nuvio CI). The IPA builds without it, but sign-in features require valid keys.

## TheIntroDB Integration

**Client:** `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/skip/TheIntroDb.kt`

- Fetches skip segments from `GET https://api.theintrodb.org/v3/media?imdb_id=…&season=…&episode=…`
- TMDB ID is preferred when the player already has one
- Times are in milliseconds and converted to seconds for Nuvio `SkipInterval`
- Segment mapping: intro→intro, recap→recap, credits→outro / movie-credits, preview→preview
- **Priority:** TheIntroDB is merged **first** in `SkipIntroRepository.mergeByPriority`, so it wins over introdb.app when both sources have the same category
- Works even when `INTRODB_API_URL` is blank (the setting that disables introdb.app in official builds)

The integration is controlled by a Feature Flag gate. See comments in `TheIntroDb.kt` for wiring details.

## Upstream & Fork Context

- **Upstream Enhanced:** [luqmanfadlli/NuvioMobile-Enhanced](https://github.com/luqmanfadlli/NuvioMobile-Enhanced) (branch `enhanced`)
- **Official Fork:** [SimSalabimse/NuvioMobile](https://github.com/SimSalabimse/NuvioMobile) (fork of the original Nuvio repository)
- **This Repository:** Personal mirror that combines Enhanced features with TheIntroDB restoration

## License

GNU GPLv3, same as Nuvio / Nuvio Enhanced. This is an unofficial personal mirror and is not affiliated with NuvioMedia or luqmanfadlli.
