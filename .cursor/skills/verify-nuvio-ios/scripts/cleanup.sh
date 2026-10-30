#!/usr/bin/env bash
# Stop only the simulator app this verification run started.
# Does not delete build/verify-nuvio-ios/evidence.
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"

if [[ ! -f "${VERIFY_SESSION_FILE}" ]]; then
  echo "cleanup: no session at ${VERIFY_SESSION_FILE}"
  exit 0
fi

# shellcheck disable=SC1090
source "${VERIFY_SESSION_FILE}"

if [[ -n "${SIMULATOR_UDID:-}" && -n "${BUNDLE_ID:-}" ]]; then
  xcrun simctl terminate "${SIMULATOR_UDID}" "${BUNDLE_ID}" >/dev/null 2>&1 || true
  xcrun simctl uninstall "${SIMULATOR_UDID}" "${BUNDLE_ID}" >/dev/null 2>&1 || true
  echo "cleanup: terminated and uninstalled ${BUNDLE_ID} on ${SIMULATOR_UDID}"
fi

if [[ "${BOOTED_BY_US:-0}" == "1" && -n "${SIMULATOR_UDID:-}" ]]; then
  xcrun simctl shutdown "${SIMULATOR_UDID}" >/dev/null 2>&1 || true
  echo "cleanup: shut down simulator ${SIMULATOR_UDID}"
fi

rm -f "${VERIFY_SESSION_FILE}"
echo "cleanup: evidence kept at ${VERIFY_EVIDENCE_DIR}"
