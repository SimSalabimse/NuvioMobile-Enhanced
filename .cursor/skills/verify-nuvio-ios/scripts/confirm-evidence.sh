#!/usr/bin/env bash
# Run after cleanup.sh. Fails if the proof files were removed with the simulator.
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"

evidence="${VERIFY_EVIDENCE_DIR}"
required=(
  "${evidence}/diagnostics-intro-default-on.png"
  "${evidence}/diagnostics-intro-toggled-off.png"
  "${evidence}/diagnostics-intro-dismissed.png"
  "${evidence}/diagnostics-intro-hierarchy.txt"
  "${evidence}/side-effects.txt"
  "${evidence}/feature.txt"
  "${evidence}/readiness.txt"
  "${evidence}/launch.png"
)

missing=0
for path in "${required[@]}"; do
  if [[ ! -s "${path}" ]]; then
    echo "confirm: missing ${path}" >&2
    missing=1
  fi
done
if [[ "${missing}" != "0" ]]; then
  exit 1
fi
grep -q 'NuvioDiagnosticsIntroSeen=true' "${evidence}/side-effects.txt"
grep -q 'NuvioDiagnosticsEnabled=true' "${evidence}/side-effects.txt"
grep -q 'signal=\[Nuvio\] app ready true' "${evidence}/readiness.txt"
echo "confirm: evidence survived at ${evidence}"
