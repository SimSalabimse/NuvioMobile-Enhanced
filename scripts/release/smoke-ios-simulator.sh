#!/usr/bin/env bash
# iOS Simulator smoke from build 196 (scripts/release/smoke-ios-simulator.sh).
# Builds the Debug simulator app, installs it, seeds the existing profile and
# What's New preferences, and launches it.
#
# The original details/settings checks launched nuvio:// URLs through
# SMOKE_DEEPLINK / SMOKE_ACTIVATE_TAB. Those in-app hooks are not on enhanced,
# and this script does not add them. Screen changes are driven with XCUITest
# (see .cursor/skills/verify-nuvio-ios). This script still owns build, install,
# seed, launch, and the readiness log.
set -euo pipefail

repository_root="$(cd "$(dirname "$0")/../.." && pwd -P)"
cd "${repository_root}"
# shellcheck source=simulator-common.sh
source "${repository_root}/scripts/release/simulator-common.sh"

SIMULATOR_NAME="${SMOKE_SIMULATOR_NAME:-iPhone 17 Pro}"
DERIVED_DATA="$(simulator_derived_data)"
RESULTS_DIR="${SMOKE_RESULTS_DIR:-${repository_root}/build/smoke-results/latest}"
LAUNCH_WAIT_SECONDS="${SMOKE_LAUNCH_WAIT_SECONDS:-8}"
SKIP_BUILD="${SMOKE_SKIP_BUILD:-0}"
SESSION_FILE="${VERIFY_SESSION_FILE:-}"
READY_TIMEOUT_SECONDS="${SMOKE_READY_TIMEOUT_SECONDS:-90}"

mkdir -p "${RESULTS_DIR}" "${DERIVED_DATA}"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  elif /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17)"
    export JAVA_HOME
  fi
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}${PATH}"
export ORG_GRADLE_JAVA_HOME="${ORG_GRADLE_JAVA_HOME:-${JAVA_HOME:-}}"
export NUVIO_IOS_DISTRIBUTION="${NUVIO_IOS_DISTRIBUTION:-full}"
export NUVIO_GRADLE_JVMARGS="${NUVIO_GRADLE_JVMARGS:--Xmx4608M -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=768M}"
export NUVIO_KOTLIN_NATIVE_JVMARGS="${NUVIO_KOTLIN_NATIVE_JVMARGS:--Xmx4608M}"
export KOTLIN_DAEMON_JVMARGS="${KOTLIN_DAEMON_JVMARGS:--Xmx2048M}"
# The Gradle property name contains dots, which bash cannot assign. `env` can.

pick_simulator() {
  if [[ -n "${SMOKE_SIMULATOR_UDID:-}" ]]; then
    printf '%s\n' "${SMOKE_SIMULATOR_UDID}"
    return
  fi
  local booted udid
  booted="$(xcrun simctl list devices booted | awk -F '[()]' '/iPhone/ && /Booted/ { print $2; exit }')"
  if [[ -n "${booted}" ]]; then
    printf '%s\n' "${booted}"
    return
  fi
  udid="$(xcrun simctl list devices available | awk -v name="${SIMULATOR_NAME}" '
    index($0, name) && $0 !~ /unavailable/ {
      if (match($0, /\([0-9A-Fa-f-]{36}\)/)) { print substr($0, RSTART+1, RLENGTH-2); exit }
    }')"
  if [[ -z "${udid}" ]]; then
    udid="$(xcrun simctl list devices available | awk '
      /iPhone/ && $0 !~ /unavailable/ {
        if (match($0, /\([0-9A-Fa-f-]{36}\)/)) { print substr($0, RSTART+1, RLENGTH-2); exit }
      }')"
  fi
  if [[ -z "${udid}" ]]; then
    echo "No available iPhone simulator (preferred: ${SIMULATOR_NAME})." >&2
    xcrun simctl list devices available >&2 || true
    xcrun simctl list runtimes >&2 || true
    exit 1
  fi
  printf '%s\n' "${udid}"
}

BOOTED_BY_US=0
ensure_booted() {
  local udid="$1"
  if xcrun simctl list devices booted | grep -q "${udid}"; then
    return
  fi
  # Keep the software keyboard from covering bottom controls during XCUITest.
  defaults write com.apple.iphonesimulator ConnectHardwareKeyboard -bool true
  xcrun simctl boot "${udid}" >/dev/null 2>&1 || true
  xcrun simctl bootstatus "${udid}" -b
  BOOTED_BY_US=1
}

dump_build_tail() {
  local log="$1"
  [[ -f "${log}" ]] || return 0
  tail -n 400 "${log}" > "${RESULTS_DIR}/xcodebuild-tail.log" || true
  grep -E '(^e: |error:|fatal error:|BUILD FAILED|What went wrong|Undefined symbol)' "${log}" \
    | tail -n 120 > "${RESULTS_DIR}/xcodebuild-errors.log" || true
}

SIMULATOR_UDID="$(pick_simulator)"
ensure_booted "${SIMULATOR_UDID}"
echo "smoke: simulator ${SIMULATOR_UDID} booted_by_us=${BOOTED_BY_US}"

APP_PATH="$(simulator_app_path)"
LOG="${RESULTS_DIR}/xcodebuild-simulator.log"
SIGNING=()
while IFS= read -r signing_flag; do
  SIGNING+=("${signing_flag}")
done < <(simulator_xcode_overrides)

if [[ "${SKIP_BUILD}" != "1" ]]; then
  set +e
  caffeinate_cmd=()
  command -v caffeinate >/dev/null && caffeinate_cmd=(caffeinate -dims)
  "${caffeinate_cmd[@]+"${caffeinate_cmd[@]}"}" \
    env NUVIO_IOS_DISTRIBUTION="${NUVIO_IOS_DISTRIBUTION}" \
      "ORG_GRADLE_PROJECT_kotlin.native.jvmArgs=${NUVIO_KOTLIN_NATIVE_JVMARGS}" \
    xcodebuild \
      -project iosApp/iosApp.xcodeproj \
      -scheme iosApp \
      -configuration Debug \
      -destination "id=${SIMULATOR_UDID}" \
      -derivedDataPath "${DERIVED_DATA}" \
      "${SIGNING[@]}" \
      build >"${LOG}" 2>&1
  BUILD_RC=$?
  set -e
  dump_build_tail "${LOG}"
  if [[ ${BUILD_RC} -ne 0 || ! -d "${APP_PATH}" ]]; then
    echo "smoke: build failed (exit ${BUILD_RC}). See ${LOG}" >&2
    exit 1
  fi
  echo "smoke: built ${APP_PATH}"
  # Hosted runners do not have the project development certificate. An ad-hoc
  # signature is enough for the simulator. Sign nested plugins before the app.
  if ! codesign --verify "${APP_PATH}" >/dev/null 2>&1; then
    echo "smoke: applying ad-hoc signature"
    if [[ -d "${APP_PATH}/PlugIns" ]]; then
      while IFS= read -r plugin; do
        codesign --force --sign - "${plugin}"
      done < <(find "${APP_PATH}/PlugIns" -name '*.appex' -print)
    fi
    codesign --force --sign - "${APP_PATH}"
  fi
else
  if [[ ! -d "${APP_PATH}" ]]; then
    echo "smoke: SMOKE_SKIP_BUILD=1 but app missing: ${APP_PATH}" >&2
    exit 1
  fi
  echo "smoke: skipped build, using ${APP_PATH}"
fi

BUNDLE_ID="$(simulator_bundle_id_from_app "${APP_PATH}")"
echo "smoke: bundle ${BUNDLE_ID}"

xcrun simctl uninstall "${SIMULATOR_UDID}" "${BUNDLE_ID}" >/dev/null 2>&1 || true
xcrun simctl install "${SIMULATOR_UDID}" "${APP_PATH}"
# Create the data container, then seed while the process is stopped.
xcrun simctl launch "${SIMULATOR_UDID}" "${BUNDLE_ID}" >/dev/null
sleep 4
xcrun simctl terminate "${SIMULATOR_UDID}" "${BUNDLE_ID}" >/dev/null 2>&1 || true
sleep 1
"${repository_root}/scripts/release/seed-simulator-prefs.sh" "${SIMULATOR_UDID}" "${APP_PATH}"

STREAM_LOG="${RESULTS_DIR}/app-log-stream.txt"
: > "${STREAM_LOG}"
STREAM_PID=""
stop_log_stream() {
  if [[ -n "${STREAM_PID}" ]]; then
    kill "${STREAM_PID}" >/dev/null 2>&1 || true
    wait "${STREAM_PID}" 2>/dev/null || true
    STREAM_PID=""
  fi
}
trap stop_log_stream EXIT
# Start before the seeded launch. The warm-up launch may already have logged ready,
# so a later `log show --last` is not proof of this launch.
xcrun simctl spawn "${SIMULATOR_UDID}" log stream --style compact --level info \
  --predicate 'composedMessage CONTAINS "[Nuvio]"' >> "${STREAM_LOG}" 2>&1 &
STREAM_PID=$!

READY_START="$(date '+%Y-%m-%d %H:%M:%S')"
LAUNCH_LINE="$(xcrun simctl launch "${SIMULATOR_UDID}" "${BUNDLE_ID}")"
echo "smoke: ${LAUNCH_LINE}"
sleep "${LAUNCH_WAIT_SECONDS}"

if ! simulator_app_alive "${SIMULATOR_UDID}" "${BUNDLE_ID}"; then
  echo "smoke: process not alive after ${LAUNCH_WAIT_SECONDS}s" >&2
  exit 1
fi

HOME_SHOT="${RESULTS_DIR}/launch.png"
xcrun simctl io "${SIMULATOR_UDID}" screenshot "${HOME_SHOT}"
echo "smoke: screenshot ${HOME_SHOT}"

READY_LOG="${RESULTS_DIR}/readiness.txt"
ready=0
deadline=$((SECONDS + READY_TIMEOUT_SECONDS))
while (( SECONDS < deadline )); do
  if grep -q '\[Nuvio\] app ready true' "${STREAM_LOG}"; then
    grep '\[Nuvio\] app ready true' "${STREAM_LOG}" | tail -n 5 > "${RESULTS_DIR}/app-ready-lines.txt" || true
    ready=1
    break
  fi
  if python3 - "${SIMULATOR_UDID}" "${READY_START}" "${RESULTS_DIR}/app-ready-lines.txt" <<'PY'
import subprocess
import sys
udid, start, dest = sys.argv[1], sys.argv[2], sys.argv[3]
predicate = 'composedMessage CONTAINS "[Nuvio] app ready true"'
cmd = [
    "xcrun", "simctl", "spawn", udid, "log", "show",
    "--start", start, "--style", "compact", "--predicate", predicate,
]
try:
    completed = subprocess.run(cmd, check=False, capture_output=True, text=True, timeout=15)
    output = (completed.stdout or "") + (completed.stderr or "")
except subprocess.TimeoutExpired as exc:
    output = exc.stdout or ""
    if isinstance(output, bytes):
        output = output.decode("utf-8", "replace")
lines = [line for line in output.splitlines() if "[Nuvio] app ready true" in line]
open(dest, "w", encoding="utf-8").write("\n".join(lines[-5:]) + ("\n" if lines else ""))
raise SystemExit(0 if lines else 1)
PY
  then
    ready=1
    break
  fi
  sleep 2
done
stop_log_stream

if [[ "${ready}" != "1" ]]; then
  {
    echo "signal=missing"
    echo "waited_seconds=${READY_TIMEOUT_SECONDS}"
    echo "process=alive"
  } > "${READY_LOG}"
  echo "smoke: timed out waiting for [Nuvio] app ready true" >&2
  exit 1
fi

{
  echo "signal=[Nuvio] app ready true"
  echo "simulator=${SIMULATOR_UDID}"
  echo "bundle=${BUNDLE_ID}"
  echo "booted_by_us=${BOOTED_BY_US}"
} > "${READY_LOG}"
echo "smoke: ready"

if [[ -n "${SESSION_FILE}" ]]; then
  mkdir -p "$(dirname "${SESSION_FILE}")"
  {
    printf 'SIMULATOR_UDID=%q\n' "${SIMULATOR_UDID}"
    printf 'BOOTED_BY_US=%q\n' "${BOOTED_BY_US}"
    printf 'BUNDLE_ID=%q\n' "${BUNDLE_ID}"
    printf 'APP_PATH=%q\n' "${APP_PATH}"
    printf 'DERIVED_DATA=%q\n' "${DERIVED_DATA}"
  } > "${SESSION_FILE}"
  echo "smoke: session ${SESSION_FILE}"
fi
