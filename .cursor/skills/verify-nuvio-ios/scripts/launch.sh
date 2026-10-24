#!/usr/bin/env bash
# Build, install, seed, and launch Nuvio Enhanced on the iOS Simulator.
# Leaves the process running. Evidence screenshots go under build/verify-nuvio-ios/evidence.
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"
# shellcheck source=../../../../scripts/release/simulator-common.sh
source "${verify_root}/scripts/release/simulator-common.sh"

mkdir -p "${VERIFY_EVIDENCE_DIR}"
scrub_launch_logs() {
  verify_scrub_text "${VERIFY_EVIDENCE_DIR}/xcodebuild-simulator.log" || true
  verify_scrub_text "${VERIFY_EVIDENCE_DIR}/xcodebuild-tail.log" || true
  verify_scrub_text "${VERIFY_EVIDENCE_DIR}/xcodebuild-errors.log" || true
  verify_scrub_text "${VERIFY_EVIDENCE_DIR}/readiness.txt" || true
  verify_scrub_text "${VERIFY_EVIDENCE_DIR}/app-ready-lines.txt" || true
  verify_scrub_text "${VERIFY_EVIDENCE_DIR}/app-log-stream.txt" || true
}
trap scrub_launch_logs EXIT

if [[ -f "${VERIFY_SESSION_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${VERIFY_SESSION_FILE}"
  if [[ -n "${SIMULATOR_UDID:-}" && -n "${BUNDLE_ID:-}" ]] \
      && simulator_app_alive "${SIMULATOR_UDID}" "${BUNDLE_ID}"; then
    echo "A verification instance is already running on ${SIMULATOR_UDID} (${BUNDLE_ID})." >&2
    echo "Run cleanup.sh before launching another. One simulator app container is shared." >&2
    exit 1
  fi
fi

verify_ensure_local_properties
"${verify_root}/scripts/prepare-ios-dependencies.sh"

export SMOKE_RESULTS_DIR="${VERIFY_EVIDENCE_DIR}"
export VERIFY_SESSION_FILE
"${verify_root}/scripts/release/smoke-ios-simulator.sh"

echo "launch: evidence ${VERIFY_EVIDENCE_DIR}"
echo "launch: ready when ${VERIFY_EVIDENCE_DIR}/readiness.txt contains: signal=[Nuvio] app ready true"
