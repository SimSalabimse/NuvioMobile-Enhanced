#!/usr/bin/env bash
# The beta push that merged the next-episode analysis changed a protected
# file. The pull request body explained it, and the push payload did not.
# Recovery from the merged pull request must accept that range. An empty
# recovery, a failed lookup, and a pull request with an empty body must not.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
guard="${root}/scripts/check-beta-upstream-guard.sh"
base="dff04e03fcd743d1aa0b0cba222a3505c31c225b"
head="4beed54597d21496f44ebd0c7147a743c596e5d9"
path="composeApp/src/commonMain/kotlin/com/nuvio/app/features/analysis/MediaAnalysisScheduler.kt"
line="upstream-guard: ${path}"

git -C "$root" cat-file -e "${base}^{commit}"
git -C "$root" cat-file -e "${head}^{commit}"

work="$(mktemp -d)"
cleanup() { rm -rf "$work"; }
trap cleanup EXIT

cat > "${work}/gh" <<'EOF'
#!/usr/bin/env bash
mode="$(cat "${NUVIO_FAKE_GH_MODE}")"
printf 'called\n' >> "${NUVIO_FAKE_GH_LOG}"
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
  printf '%s\n' "$mode" > "${work}/mode"
  : > "${work}/log"
  set +e
  env \
    GITHUB_EVENT_NAME="$event" \
    GITHUB_REPOSITORY="SimSalabimse/NuvioMobile-Enhanced" \
    NUVIO_BASE_SHA="$base" \
    NUVIO_HEAD_SHA="$head" \
    NUVIO_BASE_REF="beta" \
    NUVIO_PR_BODY="$pr_body" \
    NUVIO_FAKE_GH_MODE="${work}/mode" \
    NUVIO_FAKE_GH_LOG="${work}/log" \
    NUVIO_FAKE_GH_BODY="$line" \
    PATH="${work}:${PATH}" \
    bash "$guard" >"${work}/out.txt" 2>"${work}/err.txt"
  status=$?
  set -e
  return "$status"
}

expect_fail() {
  local label="$1"
  if run_guard "$2" "$3" "$4"; then
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
    exit 1
  fi
}

expect_fail "empty push recovery" push "" empty
if ! grep -q '^called$' "${work}/log"; then
  echo "upstream-guard test: a push with an empty body did not look up the pull request" >&2
  exit 1
fi

expect_fail "failed push lookup" push "" fail

expect_fail "empty pull request body" pull_request "" explain
if grep -q '^called$' "${work}/log"; then
  echo "upstream-guard test: an empty pull request body consulted GitHub" >&2
  exit 1
fi

if ! run_guard push "" explain; then
  echo "upstream-guard test: the merged explanation was rejected" >&2
  cat "${work}/out.txt" >&2
  cat "${work}/err.txt" >&2
  exit 1
fi
if ! grep -F "using the merged pull request body" "${work}/out.txt" >/dev/null; then
  echo "upstream-guard test: the push did not use the merged body" >&2
  exit 1
fi
if ! grep -F "allowed by the PR body: ${path}" "${work}/out.txt" >/dev/null; then
  echo "upstream-guard test: the protected path was not allowed" >&2
  exit 1
fi

if ! run_guard pull_request "${line}" called-should-not-run; then
  echo "upstream-guard test: a pull request that already explained the path was rejected" >&2
  exit 1
fi
if grep -q '^called$' "${work}/log"; then
  echo "upstream-guard test: a present pull request body consulted GitHub" >&2
  exit 1
fi

echo "upstream-guard test: push recovery accepts only a merged explanation"
