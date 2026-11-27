#!/usr/bin/env bash
# Generalised upstream guard. Protected paths and the MPVKit pin must match
# the pull-request base unless the PR body has a line "upstream-guard: <path>".
# A push event has no pull-request payload. When its body is empty, the merged
# pull request for that commit supplies the same line. Auto Sync's default and
# persisted fallback stay off either way.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"

# stdout is the merged pull request body, or nothing. A missing token, an
# unknown commit, or a GitHub error stays empty so the diff still fails closed.
recover_merged_pr_body() {
  local sha="$1"
  local repo="${GITHUB_REPOSITORY:-}"
  local resolved
  [[ -n "$repo" && -n "$sha" ]] || return 0
  command -v gh >/dev/null 2>&1 || return 0
  resolved="$(git rev-parse --verify "${sha}^{commit}")" || return 0
  gh api "repos/${repo}/commits/${resolved}/pulls" \
    --jq '[.[] | select(.merged_at != null and .body != null) | .body] | join("\n")'
}

base_ref="${NUVIO_BASE_REF:-beta}"
if [[ -n "${NUVIO_BASE_SHA:-}" && ! "${NUVIO_BASE_SHA}" =~ ^0+$ ]]; then
  base="$NUVIO_BASE_SHA"
elif git rev-parse --verify "origin/${base_ref}" >/dev/null 2>&1; then
  base="$(git rev-parse "origin/${base_ref}")"
else
  base="$(git rev-parse "$base_ref")"
fi
head="${NUVIO_HEAD_SHA:-HEAD}"
body="${NUVIO_PR_BODY:-}"
if [[ -z "$body" && "${GITHUB_EVENT_NAME:-}" == "push" ]]; then
  recovered="$(recover_merged_pr_body "$head" || true)"
  if [[ -n "$recovered" ]]; then
    body="$recovered"
    echo "upstream-guard: using the merged pull request body"
  fi
fi

explained() {
  local path="$1"
  printf '%s\n' "$body" | grep -Eq "^upstream-guard: ${path}[[:space:]]*$"
}

fail=0
protected=(
  composeApp/src/commonMain/kotlin/com/nuvio/app/features/analysis/MediaAnalysisScheduler.kt
  composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/DownloadGate.kt
  composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/PlaybackPriority.kt
  store.json
)

for path in "${protected[@]}"; do
  if ! git diff --quiet "$base" "$head" -- "$path"; then
    if explained "$path"; then
      echo "upstream-guard: allowed by the PR body: ${path}"
    else
      echo "upstream-guard: ${path} differs from ${base_ref}" >&2
      echo "upstream-guard: explain it with a PR body line: upstream-guard: ${path}" >&2
      fail=1
    fi
  fi
done

if ! git diff --quiet "$base" "$head" -- sync-worker; then
  if explained "sync-worker" || explained "sync-worker/"; then
    echo "upstream-guard: allowed by the PR body: sync-worker"
  else
    echo "upstream-guard: sync-worker/ differs from ${base_ref}" >&2
    echo "upstream-guard: explain it with a PR body line: upstream-guard: sync-worker" >&2
    fail=1
  fi
fi

base_pin="$(git rev-parse "${base}:MPVKit")"
head_pin="$(git rev-parse "${head}:MPVKit")"
if [[ "$base_pin" != "$head_pin" ]]; then
  if explained "MPVKit"; then
    echo "upstream-guard: allowed by the PR body: MPVKit"
  else
    echo "upstream-guard: MPVKit pin moved" >&2
    echo "upstream-guard: explain it with a PR body line: upstream-guard: MPVKit" >&2
    fail=1
  fi
else
  echo "upstream-guard: MPVKit pin unchanged"
fi

prefs="composeApp/src/commonMain/kotlin/com/nuvio/app/features/autosync/AutoSyncPreferencesRepository.kt"
file="$(git show "${head}:${prefs}")"
if ! grep -q '_preferredSubtitleAutoSyncOnStart = MutableStateFlow(false)' <<<"$file"; then
  echo "upstream-guard: Auto Sync default is not off" >&2
  fail=1
fi
if ! grep -q 'loadPersistedValue?.invoke() ?: false' <<<"$file"; then
  echo "upstream-guard: Auto Sync fallback is not off" >&2
  fail=1
fi
if [[ "$fail" -eq 0 ]]; then
  echo "upstream-guard: Auto Sync default and fallback are off"
fi

exit "$fail"
