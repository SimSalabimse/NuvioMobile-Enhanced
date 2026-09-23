# NuvioMobile-Enhanced

Public mirror of [luqmanfadlli/NuvioMobile-Enhanced](https://github.com/luqmanfadlli/NuvioMobile-Enhanced) with:

- **TheIntroDB** (`https://theintrodb.org`) skip-intro provider restored as a first-class source
- **Unsigned IPA** builds for SideStore / AltStore / TrollStore (no Apple certificate on the server)
- **Daily auto-sync** from the public Enhanced `enhanced` branch, then an IPA rebuild when the tree changes

Official Nuvio still talks to **introdb.app**. This fork keeps that provider and adds **TheIntroDB v3** (`https://api.theintrodb.org/v3/media`) beside it.

## 1. Mirror the Enhanced tree (required once)

GitHub will not let a fork of a public repo be private. This repository was created private and empty on purpose. Copy Enhanced in:

```bash
git clone https://github.com/SimSalabimse/NuvioMobile-Enhanced.git
cd NuvioMobile-Enhanced
git remote add enhanced https://github.com/luqmanfadlli/NuvioMobile-Enhanced.git
git fetch enhanced
git checkout -B enhanced enhanced/enhanced
# keep the overlay files from main
git checkout main -- README.md .github/workflows/sync-upstream.yml .github/workflows/build-sideload-ipa.yml composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/skip/TheIntroDb.kt
git add README.md .github composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/skip/TheIntroDb.kt
git commit -m "Keep TheIntroDB overlay and sideload CI on Enhanced tree"
git push -u origin enhanced
```

Then in GitHub: **Settings → General → Default branch → `enhanced`**.

Until that mirror exists, the IPA workflow will no-op because `scripts/build-ios-ipa.sh` is not in the tree.

## 2. Build an unsigned IPA

Actions → **Build Sideload IPA** → Run workflow.

The IPA is produced with:

```
CODE_SIGNING_ALLOWED=NO
CODE_SIGNING_REQUIRED=NO
CODE_SIGN_IDENTITY=
```

Install it with SideStore, AltStore, or TrollStore. Those tools sign on-device with *your* Apple ID. No paid developer certificate is required on this repo.

Optional repository secret:

- `NUVIO_LOCAL_PROPERTIES_BASE64` — base64 of a `local.properties` that contains Trakt / Simkl keys (same file official/Enhanced CI uses). The IPA still builds without it.

## 3. Auto-update

- Daily at 06:17 UTC: merge `luqmanfadlli/NuvioMobile-Enhanced@enhanced`, then rebuild the IPA if commits landed.
- Manual: Actions → **Sync Enhanced upstream**.

### SideStore source

After the first published IPA:

```
https://github.com/SimSalabimse/NuvioMobile-Enhanced/raw/refs/heads/enhanced/store.json
```

**Private-repo caveat:** AltStore/SideStore cannot read `raw.githubusercontent.com` or private GitHub Release assets without authentication. Options:

1. Leave this repo private and download IPA artifacts from Actions yourself.
2. Host a public `store.json` gist that points at a public IPA URL.
3. Only if you accept the risk: put a fine-grained PAT in the source URL.

GitHub also cannot attach public releases to a private repository.

## TheIntroDB integration

Client: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/skip/TheIntroDb.kt`

- `GET https://api.theintrodb.org/v3/media?imdb_id=…&season=…&episode=…` (TMDB id preferred when the player already has one)
- Times are milliseconds; converted to seconds for Nuvio `SkipInterval`
- Segment map: intro→intro, recap→recap, credits→outro / movie-credits, preview→preview
- Merged **first** in `SkipIntroRepository.mergeByPriority`, so it wins over introdb.app when both have the same category
- Still runs when `INTRODB_API_URL` is blank (that is how official builds disable introdb.app)

After mirroring Enhanced, wire the client into `SkipIntroRepository` (see comments at the bottom of `TheIntroDb.kt`).

## License

GNU GPLv3, same as Nuvio / Nuvio Enhanced. This is an unofficial private copy and is not affiliated with NuvioMedia or luqmanfadlli.
