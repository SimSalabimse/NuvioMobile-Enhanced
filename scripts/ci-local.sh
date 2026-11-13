#!/usr/bin/env bash
# Local stand-in for the pull-request jobs in .github/workflows.
# GitHub Actions does not run on this repo while billing is off.
#
#   scripts/ci-local.sh <branch|pr/N> [--base <ref>]
#
# <ref> is a branch or "pr/<number>". origin is checked first, then a local
# branch. The ref is checked out in a temporary git worktree, so the checkout
# you run from is left alone.
#
# Options:
#   --base <ref>   Diff base for the name and secrets scans (default origin/enhanced)
#   --no-fetch     Do not git fetch
#   --keep         Keep the worktree after the run
#
# Environment:
#   NUVIO_CI_LOG_DIR   Log folder (default ~/agent-tools/ci-local)
#   ANDROID_HOME       Android SDK. Falls back to ~/android-sdk when that exists.
#   NAME_DENYLIST      Comma or newline list for the name scan. When unset,
#                      ~/.config/nuvio/name-denylist is read. A missing list
#                      fails the run. The list is never written to the repo or the log.
#   DEVELOPER_DIR      Used only on macOS. Defaults to
#                      /Applications/Xcode.app/Contents/Developer.
#                      A path or version that says "beta" is refused.
#
# The iOS step runs only on macOS. It refuses a beta Xcode and the iOS 27.3
# runtime, and it never uses sudo. On Linux that step is SKIPPED.
#
# Exit status is 0 only when every required step passed. The last line is
# "CI-LOCAL PASS ref=... sha=..." or "CI-LOCAL FAIL ref=... failed: ...".
# Written for macOS bash 3.2 as well as newer bash.
set -o pipefail

usage() { sed -n '2,31p' "$0" | sed 's/^# \{0,1\}//'; }

BASE="origin/enhanced"
FETCH=1
KEEP=0
REF=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) BASE="${2:-}"; shift 2 ;;
    --no-fetch) FETCH=0; shift ;;
    --keep) KEEP=1; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
    *)
      if [[ -n "$REF" ]]; then echo "unexpected argument: $1" >&2; usage >&2; exit 2; fi
      REF="$1"; shift ;;
  esac
done
[[ -n "$REF" && -n "$BASE" ]] || { usage >&2; exit 2; }

REPO="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)" || exit 2
CI_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${NUVIO_CI_LOG_DIR:-$HOME/agent-tools/ci-local}"
mkdir -p "$LOG_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
SAFE_REF="$(printf '%s' "$REF" | tr -c 'A-Za-z0-9._-' '-')"
LOG="${LOG_DIR}/${SAFE_REF}-${STAMP}.log"
: > "$LOG"
exec > >(tee -a "$LOG") 2>&1

echo "Nuvio ci-local"
echo "log: ${LOG}"
echo "started: $(date '+%Y-%m-%d %H:%M:%S %Z')"
RESULTS=()
FAILED=()

finish() {
  local status="$1"
  shift
  echo ""
  echo "==> summary"
  local line
  for line in "${RESULTS[@]}"; do echo "  $line"; done
  echo "finished: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "log: ${LOG}"
  if [[ "$status" -eq 0 ]]; then
    echo "CI-LOCAL PASS ref=${REF} sha=${SHA}"
    exit 0
  fi
  echo "CI-LOCAL FAIL ref=${REF} failed: $*"
  exit 1
}

if [[ "$FETCH" -eq 1 ]]; then
  git -C "$REPO" fetch --quiet --prune origin || finish 1 "git fetch"
fi

resolve_sha() {
  local name="$1"
  if git -C "$REPO" rev-parse --verify --quiet "origin/${name}^{commit}" >/dev/null; then
    local remote local_sha
    remote="$(git -C "$REPO" rev-parse "origin/${name}^{commit}")"
    if git -C "$REPO" rev-parse --verify --quiet "refs/heads/${name}^{commit}" >/dev/null; then
      local_sha="$(git -C "$REPO" rev-parse "refs/heads/${name}^{commit}")"
      if [[ "$local_sha" == "$remote" ]]; then
        printf '%s\n' "$local_sha"
        return 0
      fi
      if git -C "$REPO" merge-base --is-ancestor "$remote" "$local_sha"; then
        printf '%s\n' "$local_sha"
        return 0
      fi
    fi
    printf '%s\n' "$remote"
    return 0
  fi
  if git -C "$REPO" rev-parse --verify --quiet "refs/heads/${name}^{commit}" >/dev/null; then
    git -C "$REPO" rev-parse "refs/heads/${name}^{commit}"
    return 0
  fi
  if git -C "$REPO" rev-parse --verify --quiet "${name}^{commit}" >/dev/null; then
    git -C "$REPO" rev-parse "${name}^{commit}"
    return 0
  fi
  return 1
}

SHA=""
if [[ "$REF" =~ ^pr/([0-9]+)$ ]]; then
  PR_NUMBER="${BASH_REMATCH[1]}"
  if [[ "$FETCH" -eq 1 ]] && git -C "$REPO" fetch --quiet origin "pull/${PR_NUMBER}/head"; then
    SHA="$(git -C "$REPO" rev-parse FETCH_HEAD)"
  fi
  if [[ -z "$SHA" ]] && command -v gh >/dev/null 2>&1; then
    SHA="$(cd "$REPO" && gh pr view "$PR_NUMBER" --json headRefOid --jq .headRefOid 2>/dev/null || true)"
    if [[ -n "$SHA" ]]; then
      git -C "$REPO" cat-file -e "${SHA}^{commit}" 2>/dev/null || git -C "$REPO" fetch --quiet origin "$SHA" || true
    fi
  fi
  git -C "$REPO" cat-file -e "${SHA}^{commit}" 2>/dev/null || finish 1 "cannot resolve ${REF}"
else
  SHA="$(resolve_sha "$REF")" || finish 1 "cannot resolve ${REF}"
fi

BASE_SHA="$(resolve_sha "$BASE")" || BASE_SHA="$(git -C "$REPO" rev-parse --verify --quiet "${BASE}^{commit}" || true)"
[[ -n "$BASE_SHA" ]] || finish 1 "cannot resolve base ${BASE}"

echo "ref: ${REF}  sha: ${SHA}  base: ${BASE} (${BASE_SHA})"

WT="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-ci.XXXXXX")"
cleanup() {
  if [[ "$KEEP" -eq 1 ]]; then
    echo "kept worktree: ${WT}"
  else
    git -C "$REPO" worktree remove --force "$WT" >/dev/null 2>&1 || rm -rf "$WT"
    git -C "$REPO" worktree prune >/dev/null 2>&1 || true
    rm -rf "$WT"
  fi
}
trap cleanup EXIT
git -C "$REPO" worktree add --quiet --detach "$WT" "$SHA" || finish 1 "worktree add"
cd "$WT" || finish 1 "cd worktree"

step() {
  local name="$1"
  shift
  echo ""
  echo "==> ${name}"
  local t0=$SECONDS
  if "$@"; then
    RESULTS+=("ok      ${name} ($((SECONDS - t0))s)")
  else
    RESULTS+=("FAIL    ${name} ($((SECONDS - t0))s)")
    FAILED+=("$name")
  fi
}
skip() {
  RESULTS+=("SKIPPED ${1}: ${2}")
  echo ""
  echo "==> ${1}"
  echo "SKIPPED: ${2}"
}

cap_gradle_memory() {
  local avail_kb=999999999
  if [[ -r /proc/meminfo ]]; then
    avail_kb="$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)"
  fi
  [[ "$avail_kb" -lt 10485760 ]] || return 0
  python3 - "$PWD/gradle.properties" <<'PY'
import sys
from pathlib import Path
path = Path(sys.argv[1])
lines = path.read_text(encoding="utf-8").splitlines()
wanted = {
    "org.gradle.jvmargs": "-Xmx3072m -Xms512m -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=768m -XX:+UseG1GC",
    "kotlin.daemon.jvmargs": "-Xmx4096m -Xms512m -XX:MaxMetaspaceSize=768m -XX:+UseG1GC",
    "kotlin.native.jvmArgs": "-Xmx2048m",
    "org.gradle.parallel": "false",
    "org.gradle.workers.max": "1",
    "org.gradle.configuration-cache": "false",
}
seen = set()
out = []
for line in lines:
    key = line.split("=", 1)[0].strip()
    if key in wanted:
        out.append(f"{key}={wanted[key]}")
        seen.add(key)
    else:
        out.append(line)
for key, value in wanted.items():
    if key not in seen:
        out.append(f"{key}={value}")
path.write_text("\n".join(out) + "\n", encoding="utf-8")
PY
  cat > "$WT/ci-local.init.gradle" <<'EOF'
allprojects {
    tasks.withType(Test).configureEach {
        maxParallelForks = 1
        maxHeapSize = "2048m"
    }
}
EOF
  echo "capped Gradle and Kotlin heaps in the temporary worktree (MemAvailable under 10 GiB)"
}

prepare_android_sdk() {
  if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/android-sdk" ]]; then
    export ANDROID_HOME="$HOME/android-sdk"
  fi
  if [[ -z "${ANDROID_SDK_ROOT:-}" && -n "${ANDROID_HOME:-}" ]]; then
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
  fi
  [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]] || { echo "ANDROID_HOME is not set and ~/android-sdk is missing"; return 1; }
  if [[ ! -f "$ANDROID_HOME/licenses/android-sdk-license" ]]; then
    mkdir -p "$ANDROID_HOME/licenses"
    printf '%s\n' "24333f8a63b6825ea9c5514f83c2829b004d1fee" > "$ANDROID_HOME/licenses/android-sdk-license"
  fi
  if [[ ! -f local.properties ]] || ! grep -q '^sdk.dir=' local.properties; then
    printf 'sdk.dir=%s\n' "$ANDROID_HOME" >> local.properties
  fi
  echo "ANDROID_HOME=${ANDROID_HOME}"
}

host_test_counts() {
  python3 - <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
roots = list(Path("composeApp/build/test-results").glob("**/*.xml")) if Path("composeApp/build/test-results").is_dir() else []
if not roots:
    print("host tests: not measured")
    sys.exit(0)
tests = failures = errors = skipped = 0
for path in roots:
    root = ET.parse(path).getroot()
    tests += int(root.attrib.get("tests", 0))
    failures += int(root.attrib.get("failures", 0))
    errors += int(root.attrib.get("errors", 0))
    skipped += int(root.attrib.get("skipped", 0))
passed = tests - failures - errors - skipped
print(f"host tests: {passed} pass / {failures + errors} fail / {skipped} skipped (xml tests={tests})")
PY
}

android_host_tests() {
  prepare_android_sdk || return 1
  cap_gradle_memory
  chmod +x ./gradlew
  set +e
  if [[ -f "$WT/ci-local.init.gradle" ]]; then
    ./gradlew :composeApp:testAndroidHostTest --stacktrace -I "$WT/ci-local.init.gradle"
  else
    ./gradlew :composeApp:testAndroidHostTest --stacktrace
  fi
  local code=$?
  set +e
  host_test_counts
  return "$code"
}

apk_toolchain_ready() {
  command -v cargo-ndk >/dev/null 2>&1 || return 1
  [[ -n "${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}" ]] && return 0
  [[ -d "${ANDROID_HOME:-$HOME/android-sdk}/ndk" ]] && return 0
  return 1
}

android_full_debug_apk() {
  prepare_android_sdk || return 1
  cap_gradle_memory
  ./gradlew :androidApp:assembleFullDebug -Pnuvio.android.distribution=full -Pnuvio.ios.distribution=appstore --stacktrace
}

sdk_version_is_27_3() {
  local version="$1"
  [[ "$version" == "27.3" || "$version" == 27.3.* ]]
}

ios_job() {
  export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
  case "$DEVELOPER_DIR" in
    *[Bb][Ee][Tt][Aa]*) echo "refusing a beta Xcode: ${DEVELOPER_DIR}"; return 1 ;;
  esac
  command -v xcodebuild >/dev/null 2>&1 || { echo "xcodebuild is missing"; return 1; }
  local version sdk_device sdk_sim
  version="$(xcodebuild -version)" || return 1
  printf '%s\n' "$version"
  case "$version" in
    *[Bb][Ee][Tt][Aa]*) echo "refusing a beta Xcode"; return 1 ;;
  esac
  sdk_device="$(xcrun --sdk iphoneos --show-sdk-version)" || return 1
  sdk_sim="$(xcrun --sdk iphonesimulator --show-sdk-version)" || return 1
  echo "iphoneos SDK ${sdk_device}; iphonesimulator SDK ${sdk_sim}"
  if sdk_version_is_27_3 "$sdk_device" || sdk_version_is_27_3 "$sdk_sim"; then
    echo "refusing iOS 27.3 runtime"
    return 1
  fi
  if xcrun simctl list runtimes available 2>/dev/null | grep -E -q 'iOS 27\.3([^0-9]|$)'; then
    echo "notice: iOS 27.3 runtime is installed and will not be used"
  fi
  ./scripts/prepare-ios-dependencies.sh
  ./scripts/build-ios-ipa.sh
}

echo "java: $(java -version 2>&1 | head -n 1)"

step "name denylist" python3 "$CI_DIR/ci/name_denylist.py" --repo "$WT" --base "$BASE_SHA" --head HEAD
step "secrets scan" python3 "$CI_DIR/ci/secrets_scan.py" --repo "$WT" --base "$BASE_SHA" --head HEAD
step "android host tests" android_host_tests

if apk_toolchain_ready; then
  step "android fullDebug APK" android_full_debug_apk
else
  skip "android fullDebug APK" "Android NDK, Rust targets, or cargo-ndk is missing. The pull-request workflow builds this APK; this host does not."
fi

if [[ "$(uname -s)" == "Darwin" ]]; then
  step "iOS unsigned IPA" ios_job
else
  skip "iOS unsigned IPA" "Linux has no Xcode. This step runs on macOS with a stable Xcode and refuses a beta Xcode and the iOS 27.3 runtime."
fi

if [[ "${#FAILED[@]}" -eq 0 ]]; then
  finish 0
fi
finish 1 "$(IFS='; '; echo "${FAILED[*]}")"
