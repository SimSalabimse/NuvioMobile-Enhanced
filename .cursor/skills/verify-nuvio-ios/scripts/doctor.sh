#!/usr/bin/env bash
# Read-only check that the simulator instance launch.sh started is worth driving.
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"
# shellcheck source=../../../../scripts/release/simulator-common.sh
source "${verify_root}/scripts/release/simulator-common.sh"

verify_load_session

expected_version="$(sed -nE 's/^[[:space:]]*MARKETING_VERSION[[:space:]]*=[[:space:]]*([^[:space:]#]+).*$/\1/p' "${verify_root}/iosApp/Configuration/Version.xcconfig" | head -n 1)"
expected_build="$(sed -nE 's/^[[:space:]]*CURRENT_PROJECT_VERSION[[:space:]]*=[[:space:]]*([^[:space:]#]+).*$/\1/p' "${verify_root}/iosApp/Configuration/Version.xcconfig" | head -n 1)"

if ! xcrun simctl list devices booted | grep -q "${SIMULATOR_UDID}"; then
  echo "doctor: simulator ${SIMULATOR_UDID} is not booted" >&2
  exit 1
fi

app_bundle="$(xcrun simctl get_app_container "${SIMULATOR_UDID}" "${BUNDLE_ID}" app 2>/dev/null || true)"
if [[ -z "${app_bundle}" || ! -d "${app_bundle}" ]]; then
  echo "doctor: ${BUNDLE_ID} is not installed on ${SIMULATOR_UDID}" >&2
  exit 1
fi

installed_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "${app_bundle}/Info.plist")"
installed_build="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "${app_bundle}/Info.plist")"
installed_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "${app_bundle}/Info.plist")"

if [[ "${installed_id}" != "${BUNDLE_ID}" ]]; then
  echo "doctor: installed bundle ${installed_id} != session ${BUNDLE_ID}" >&2
  exit 1
fi
if [[ "${installed_version}" != "${expected_version}" || "${installed_build}" != "${expected_build}" ]]; then
  echo "doctor: installed ${installed_version} (${installed_build}) != Version.xcconfig ${expected_version} (${expected_build})" >&2
  exit 1
fi

if ! simulator_app_alive "${SIMULATOR_UDID}" "${BUNDLE_ID}"; then
  echo "doctor: ${BUNDLE_ID} is installed but not running" >&2
  exit 1
fi

printf 'doctor: ok simulator=%s bundle=%s version=%s build=%s booted_by_us=%s\n' \
  "${SIMULATOR_UDID}" "${installed_id}" "${installed_version}" "${installed_build}" "${BOOTED_BY_US}"
