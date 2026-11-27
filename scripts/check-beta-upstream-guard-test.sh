#!/usr/bin/env bash
# The beta push 766f2e602 changed three protected paths. Pull request 150
# explained each one, and the push payload did not. Recovery from the merged
# pull request must accept that range. An empty recovery, a failed lookup, a
# partial explanation, and a pull request with an empty body must not.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
guard="${root}/scripts/check-beta-upstream-guard.sh"
base="66eac76423fe372bad50746fc21639440212f580"
head="766f2e602ba714143797ae9eb85ccae0a174277f"
scheduler="composeApp/src/commonMain/kotlin/com/nuvio/app/features/analysis/MediaAnalysisScheduler.kt"
playback="composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/PlaybackPriority.kt"
lines="upstream-guard: ${scheduler}
upstream-guard: ${playback}
upstream-guard: sync-worker"

git -C "$root" cat-file -e "${base}^{commit}"
git -C "$root" cat-file -e "${head}^{commit}"

work="$(mktemp -d)"
cleanup() { rm -rf "$work"; }
trap cleanup EXIT

cat > "${work}/gh" <<'EOF'
#!/usr/bin/env bash
mode="$(cat "${NUVIO_FAKE_GH_MODE}")"
printf 'called\n' >> "${NUVIO_FAKE_GH_LOG}"
printf 'gh-token:%s\n' "${GH_TOKEN:-}" >> "${NUVIO_FAKE_GH_LOG}"
case "$mode" in
  explain)
    printf '%s\n' "${NUVIO_FAKE_GH_BODY}"
    ;;
  empty)
    ;;
  fail)
    echo "lookup failed" >&2
    exit 1
    ;;
esac
EOF
chmod +x "${work}/gh"

: > "${work}/log"
printf 'empty\n' > "${work}/mode"

run_guard() {
  local event="$1"
  local pr_body="$2"
  local mode="$3"
  local fake_body="${4:-}"
  local repo="${5-SimSalabimse/NuvioMobile-Enhanced}"
  printf '%s\n' "$mode" > "${work}/mode"
  : > "${work}/log"
  set +e
  env \
    GITHUB_EVENT_NAME="$event" \
    GITHUB_REPOSITORY="$repo" \
    NUVIO_BASE_SHA="$base" \
    NUVIO_HEAD_SHA="$head" \
    NUVIO_BASE_REF="beta" \
    NUVIO_PR_BODY="$pr_body" \
    NUVIO_FAKE_GH_MODE="${work}/mode" \
    NUVIO_FAKE_GH_LOG="${work}/log" \
    NUVIO_FAKE_GH_BODY="$fake_body" \
    PATH="${work}:${PATH}" \
    bash "$guard" >"${work}/out.txt" 2>"${work}/err.txt"
  status=$?
  set -e
  return "$status"
}

expect_fail() {
  local label="$1"
  if run_guard "$2" "$3" "$4" "${5:-}" "${6-SimSalabimse/NuvioMobile-Enhanced}"; then
    echo "upstream-guard test: ${label} was accepted" >&2
    cat "${work}/out.txt" >&2
    cat "${work}/err.txt" >&2
    exit 1
  fi
  if [[ "$status" -ne 1 ]]; then
    echo "upstream-guard test: ${label} exited ${status}, expected 1" >&2
    exit 1
  fi
  if ! grep -F "differs from beta" "${work}/err.txt" >/dev/null; then
    echo "upstream-guard test: ${label} did not report the protected diff" >&2
    cat "${work}/err.txt" >&2
    exit 1
  fi
}

expect_fail "empty push recovery" push "" empty
if ! grep -q '^called$' "${work}/log"; then
  echo "upstream-guard test: a push with an empty body did not look up the pull request" >&2
  exit 1
fi

expect_fail "failed push lookup" push "" fail

expect_fail "push without a repository" push "" explain "$lines" ""
if grep -q '^called$' "${work}/log"; then
  echo "upstream-guard test: a push without a repository consulted GitHub" >&2
  exit 1
fi

expect_fail "partial merged body" push "" explain "upstream-guard: ${scheduler}"
if ! grep -F "differs from beta" "${work}/err.txt" >/dev/null; then
  echo "upstream-guard test: a partial explanation hid another protected path" >&2
  exit 1
fi
if ! grep -F "$playback" "${work}/err.txt" >/dev/null; then
  echo "upstream-guard test: a partial explanation allowed the playback path" >&2
  cat "${work}/err.txt" >&2
  exit 1
fi

expect_fail "empty pull request body" pull_request "" explain "$lines"
if grep -q '^called$' "${work}/log"; then
  echo "upstream-guard test: an empty pull request body consulted GitHub" >&2
  exit 1
fi

expect_fail "workflow dispatch" workflow_dispatch "" explain "$lines"
if grep -q '^called$' "${work}/log"; then
  echo "upstream-guard test: a workflow dispatch consulted GitHub" >&2
  exit 1
fi

printf 'explain\n' > "${work}/mode"
: > "${work}/log"
if ! env -u GH_TOKEN \
  GITHUB_TOKEN="sentinel-token" \
  GITHUB_EVENT_NAME=push \
  GITHUB_REPOSITORY="SimSalabimse/NuvioMobile-Enhanced" \
  NUVIO_BASE_SHA="$base" \
  NUVIO_HEAD_SHA="$head" \
  NUVIO_BASE_REF=beta \
  NUVIO_PR_BODY= \
  NUVIO_FAKE_GH_MODE="${work}/mode" \
  NUVIO_FAKE_GH_LOG="${work}/log" \
  NUVIO_FAKE_GH_BODY="$lines" \
  PATH="${work}:${PATH}" \
  bash "$guard" >"${work}/out.txt" 2>"${work}/err.txt"
then
  echo "upstream-guard test: copying GITHUB_TOKEN rejected the merged explanation" >&2
  cat "${work}/err.txt" >&2
  exit 1
fi
if ! grep -q '^gh-token:sentinel-token$' "${work}/log"; then
  echo "upstream-guard test: GITHUB_TOKEN was not copied for gh" >&2
  cat "${work}/log" >&2
  exit 1
fi

if ! run_guard push "" explain "$lines"; then
  echo "upstream-guard test: the merged explanation was rejected" >&2
  cat "${work}/out.txt" >&2
  cat "${work}/err.txt" >&2
  exit 1
fi
if ! grep -F "using the merged pull request body" "${work}/out.txt" >/dev/null; then
  echo "upstream-guard test: the push did not use the merged body" >&2
  exit 1
fi
for path in "$scheduler" "$playback" sync-worker; do
  if ! grep -F "allowed by the PR body: ${path}" "${work}/out.txt" >/dev/null; then
    echo "upstream-guard test: ${path} was not allowed" >&2
    cat "${work}/out.txt" >&2
    exit 1
  fi
done

if ! run_guard pull_request "$lines" called-should-not-run; then
  echo "upstream-guard test: a pull request that already explained the paths was rejected" >&2
  cat "${work}/out.txt" >&2
  cat "${work}/err.txt" >&2
  exit 1
fi
if grep -q '^called$' "${work}/log"; then
  echo "upstream-guard test: a present pull request body consulted GitHub" >&2
  exit 1
fi

if ! env \
  GITHUB_EVENT_NAME=push \
  GITHUB_REPOSITORY="SimSalabimse/NuvioMobile-Enhanced" \
  NUVIO_BASE_SHA="$base" \
  NUVIO_HEAD_SHA="$head" \
  NUVIO_BASE_REF=beta \
  NUVIO_PR_BODY= \
  bash "$guard" >"${work}/live-out.txt" 2>"${work}/live-err.txt"
then
  echo "upstream-guard test: the live merged pull request was rejected" >&2
  cat "${work}/live-out.txt" >&2
  cat "${work}/live-err.txt" >&2
  exit 1
fi
if ! grep -F "using the merged pull request body" "${work}/live-out.txt" >/dev/null; then
  echo "upstream-guard test: the live push did not use the merged body" >&2
  exit 1
fi

echo "upstream-guard test: push recovery accepts only a merged explanation"
