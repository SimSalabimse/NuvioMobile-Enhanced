# Local CI

GitHub Actions no longer runs on this private repo: the free minutes ran out and Simsalabim chose not to add billing. `.github/workflows/pr-full-debug-build.yml` is kept, but its only trigger is `workflow_dispatch`, so a pull request does not queue a build. Put the `pull_request` trigger back, using the comment at the top of that file, to turn it on again.

`scripts/ci-local.sh` runs the Linux-capable checks on a branch or pull request:

```sh
scripts/ci-local.sh cursor/my-branch
scripts/ci-local.sh pr/133
scripts/ci-local.sh pr/133 --base origin/enhanced
```

| Actions job | Local step |
| --- | --- |
| PR full debug APK (`:androidApp:assembleFullDebug`) | Runs only when `cargo-ndk` and an Android NDK are installed. Otherwise SKIPPED. The APK needs Rust targets and the NDK, which the Ubuntu Actions runner installs and this script does not. |
| Android host tests | `./gradlew :composeApp:testAndroidHostTest`. This is the required Linux test gate. The pull-request workflow does not run it. |
| (not an Actions job) | Name denylist, from `NAME_DENYLIST` or `~/.config/nuvio/name-denylist` |
| (not an Actions job) | Secrets scan of the diff against the base |
| iOS test IPA / sideload IPA | On macOS: stable Xcode, then `scripts/prepare-ios-dependencies.sh` and `scripts/build-ios-ipa.sh`. SKIPPED on Linux. |

The ref is checked out in a temporary git worktree, so the checkout you started from is not changed. The worktree is deleted at the end (`--keep` leaves it).

Each run writes `~/agent-tools/ci-local/<ref>-<timestamp>.log` (override with `NUVIO_CI_LOG_DIR`) and prints that path. The last line is `CI-LOCAL PASS ref=… sha=…` or `CI-LOCAL FAIL ref=… failed: …`. The exit status is non-zero on any required failure. A SKIPPED step is not a failure.

Rules the script enforces:

- The name scan reads `NAME_DENYLIST` or `~/.config/nuvio/name-denylist`. A missing or empty list fails the run. The list is never printed and never committed.
- The secrets scan looks at lines added versus the base (default `origin/enhanced`). It reports the pattern name and path, not the matching text.
- `DEVELOPER_DIR` defaults to `/Applications/Xcode.app/Contents/Developer` on the iOS step. A beta Xcode is refused. An iPhone SDK or simulator SDK version `27.3` is refused. The script never calls `sudo`.
- When `/proc/meminfo` reports under 10 GiB available, the temporary worktree's Gradle and Kotlin heaps are lowered so the host tests can run. That edit is not committed.

## Other workflows

`ios-test-build.yml`, `build-sideload-ipa.yml`, `mobile-release.yml`, and `sync-upstream.yml` were already `workflow_dispatch` (or `workflow_call`). `update-store-source.yml` still runs when a release is published, and `mobile-release.yml` calls it. Those are release jobs, not pull-request jobs, so this script does not run them.
