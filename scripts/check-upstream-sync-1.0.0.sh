#!/bin/sh
# Fails the 1.0.0 sync when a protected decision moved.
set -eu
cd "$(dirname "$0")/.."

OURS=81572208faeaf871239a1a3087f250d5cf3d630c
THEIRS=6c2ad7937171965608d05005080b96ca19345379
BASE=313ca3d15c3a31d1d1b456fbbb09d6c31711b8b2
PIN=7f40522ab9a18c7f14c04316f71c7dd47e2a7e05

fail() {
  echo "gate failed: $1" >&2
  exit 1
}

mb=$(git merge-base "$OURS" "$THEIRS")
test "$mb" = "$BASE" || fail "merge-base is $mb, want $BASE"

merge=$(git rev-list --merges --ancestry-path "$OURS..HEAD" | tail -n 1)
test -n "$merge" || fail "no merge commit between $OURS and HEAD"
p1=$(git rev-parse "$merge^1")
p2=$(git rev-parse "$merge^2")
test "$p1" = "$OURS" || fail "merge first parent is $p1"
test "$p2" = "$THEIRS" || fail "merge second parent is $p2"

if git grep -nE '^(<<<<<<<|>>>>>>>|=======$)' ':!docs' >/tmp/sync-markers.txt; then
  cat /tmp/sync-markers.txt >&2
  fail "conflict markers"
fi

kit=$(git ls-tree HEAD MPVKit)
echo "$kit" | grep -q "$PIN" || fail "MPVKit pin moved: $kit"
echo "$kit" | grep -q 359a885342d4eeda757f8e1d2c24fb01fe6d39c2 && fail "MPVKit is the upstream pin"

prefs=composeApp/src/commonMain/kotlin/com/nuvio/app/features/autosync/AutoSyncPreferencesRepository.kt
grep -q '_preferredSubtitleAutoSyncOnStart = MutableStateFlow(false)' "$prefs" || fail "Auto Sync default is not off"
grep -q 'loadPersistedValue?.invoke() ?: false' "$prefs" || fail "Auto Sync persisted fallback is not off"
if grep -q '_preferredSubtitleAutoSyncOnStart = MutableStateFlow(true)' "$prefs"; then
  fail "Auto Sync default is on"
fi
if grep -q 'loadPersistedValue?.invoke() ?: true' "$prefs"; then
  fail "Auto Sync persisted fallback is on"
fi

xc=iosApp/Configuration/Version.xcconfig
grep -q 'MARKETING_VERSION=1.0.0.1' "$xc" || fail "marketing version is not 1.0.0.1"
grep -q 'CURRENT_PROJECT_VERSION = 225' "$xc" || fail "build is not 225"
if grep -q 'CURRENT_PROJECT_VERSION=157' "$xc" || grep -q 'CURRENT_PROJECT_VERSION = 157' "$xc"; then
  fail "build reset to 157"
fi
if grep -q 'MARKETING_VERSION=1.0.0$' "$xc"; then
  fail "marketing version reset to 1.0.0"
fi
grep -q 'NUVIO_TELEMETRY_INGEST_KEY=$' "$xc" || fail "telemetry key line is not empty"
grep -q 'NUVIO_TELEMETRY_BASE_URL=$' "$xc" || fail "telemetry url line is not empty"

git diff --exit-code "$OURS" -- \
  composeApp/src/commonMain/kotlin/com/nuvio/app/features/analysis/MediaAnalysisScheduler.kt \
  composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/DownloadGate.kt \
  composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/PlaybackPriority.kt \
  || fail "playback lane or heat gate changed"
git diff --exit-code "$OURS" -- sync-worker || fail "sync-worker changed"
git diff --exit-code "$OURS" -- store.json || fail "store.json changed"

if grep -q 'useForcedSubtitles: Boolean = true' composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/SubtitleAudioModels.kt; then
  fail "forced subtitles default is on"
fi
if git grep -n 'staticFromBackdrop = true' -- '*.kt' >/tmp/sync-glass.txt; then
  cat /tmp/sync-glass.txt >&2
  fail "staticFromBackdrop is on"
fi
if git grep -n 'frameQueue = .main' -- '*.swift' >/tmp/sync-pip.txt; then
  cat /tmp/sync-pip.txt >&2
  fail "PiP frame queue is the main queue"
fi
if ! git grep -n 'func getIsOpening' -- iosApp/iosApp/Player/MPVPlayerBridge.swift >/dev/null; then
  fail "getIsOpening is missing from MPVPlayerBridge"
fi

echo "upstream sync gates passed"
