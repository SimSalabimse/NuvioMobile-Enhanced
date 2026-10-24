#!/usr/bin/env bash
# Drive the one-time Diagnostics intro sheet on the simulator launch.sh started.
# XCUITest relaunches the app, reseeds preferences, toggles Save diagnostics log,
# taps Done, and writes screenshots into the evidence directory.
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"
# shellcheck source=../../../../scripts/release/simulator-common.sh
source "${verify_root}/scripts/release/simulator-common.sh"

verify_load_session
mkdir -p "${VERIFY_EVIDENCE_DIR}"

printf '%s' "${VERIFY_EVIDENCE_DIR}" > /tmp/verify-nuvio-ios-evidence-path
printf '%s' "${SIMULATOR_UDID}" > /tmp/verify-nuvio-ios-udid
printf '%s' "${verify_root}/scripts/release/seed-simulator-prefs.sh" > /tmp/verify-nuvio-ios-seed-script
printf '%s' "${APP_PATH}" > /tmp/verify-nuvio-ios-app-path

SIGNING=()
while IFS= read -r signing_flag; do
  SIGNING+=("${signing_flag}")
done < <(simulator_xcode_overrides)

cd "${verify_root}"
set +e
env NUVIO_IOS_DISTRIBUTION="${NUVIO_IOS_DISTRIBUTION:-full}" \
  OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES \
  xcodebuild test \
    -project iosApp/iosApp.xcodeproj \
    -scheme VerifyNuvioIOS \
    -configuration Debug \
    -destination "id=${SIMULATOR_UDID}" \
    -derivedDataPath "${DERIVED_DATA}" \
    -only-testing:VerifyUITests/DiagnosticsIntroUITest/testIntroSheetToggleDefaultsOnAndDismisses \
    "${SIGNING[@]}" \
    > "${VERIFY_EVIDENCE_DIR}/xcuitest.log" 2>&1
TEST_RC=$?
set -e
tail -n 200 "${VERIFY_EVIDENCE_DIR}/xcuitest.log" > "${VERIFY_EVIDENCE_DIR}/xcuitest-tail.log" || true
verify_scrub_text "${VERIFY_EVIDENCE_DIR}/xcuitest.log"
verify_scrub_text "${VERIFY_EVIDENCE_DIR}/xcuitest-tail.log"

if [[ -d /tmp/verify-nuvio-ios-evidence ]]; then
  cp -n /tmp/verify-nuvio-ios-evidence/. "${VERIFY_EVIDENCE_DIR}/" 2>/dev/null || \
    cp -n /tmp/verify-nuvio-ios-evidence/* "${VERIFY_EVIDENCE_DIR}/" 2>/dev/null || true
fi

# Flush UserDefaults, then read the side effect. `defaults read` sees the
# simulator preference daemon; the container plist is the fallback.
xcrun simctl terminate "${SIMULATOR_UDID}" "${BUNDLE_ID}" >/dev/null 2>&1 || true
sleep 2
DATA_DIR="$(xcrun simctl get_app_container "${SIMULATOR_UDID}" "${BUNDLE_ID}" data 2>/dev/null || true)"
defaults_seen="$(xcrun simctl spawn "${SIMULATOR_UDID}" defaults read "${BUNDLE_ID}" NuvioDiagnosticsIntroSeen 2>/dev/null | tr -d '[:space:]' || true)"
defaults_enabled="$(xcrun simctl spawn "${SIMULATOR_UDID}" defaults read "${BUNDLE_ID}" NuvioDiagnosticsEnabled 2>/dev/null | tr -d '[:space:]' || true)"
python3 - "${DATA_DIR}" "${BUNDLE_ID}" "${VERIFY_EVIDENCE_DIR}/side-effects.txt" "${defaults_seen}" "${defaults_enabled}" <<'PY'
import plistlib
import sys
from pathlib import Path
data_dir, bundle_id, out = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
defaults_seen, defaults_enabled = sys.argv[4], sys.argv[5]
lines = []
plist = data_dir / "Library" / "Preferences" / f"{bundle_id}.plist"
log = data_dir / "Documents" / "diagnostics.jsonl"

def as_bool(text):
    lowered = text.strip().lower()
    if lowered in {"1", "true", "yes"}:
        return True
    if lowered in {"0", "false", "no"}:
        return False
    return None

plist_values = {}
if data_dir.is_dir() and plist.exists():
    data = plistlib.loads(plist.read_bytes())
    plist_values = {key: data.get(key) for key in ("NuvioDiagnosticsIntroSeen", "NuvioDiagnosticsEnabled")}
    lines.append("plist=present")
else:
    lines.append("plist=missing")

resolved = {
    "NuvioDiagnosticsIntroSeen": as_bool(defaults_seen),
    "NuvioDiagnosticsEnabled": as_bool(defaults_enabled),
}
for key, fallback in zip(resolved, (defaults_seen, defaults_enabled)):
    if resolved[key] is None and isinstance(plist_values.get(key), bool):
        resolved[key] = plist_values[key]
    value = resolved[key]
    lines.append(f"{key}={'true' if value is True else 'false' if value is False else 'missing'}")
    lines.append(f"defaults.{key}={fallback or 'missing'}")
if log.exists():
    text = log.read_text(errors="replace")
    lowered = text.lower()
    if any(host in lowered for host in ("real-debrid", "alldebrid", "premiumize")):
        lines.append("diagnostics.jsonl=withheld")
    else:
        dest = out.parent / "diagnostics.jsonl"
        dest.write_text(text)
        lines.append(f"diagnostics.jsonl=copied bytes={log.stat().st_size}")
else:
    lines.append("diagnostics.jsonl=absent")
out.write_text("\n".join(lines) + "\n")
print("\n".join(lines))
PY

xcrun simctl io "${SIMULATOR_UDID}" screenshot "${VERIFY_EVIDENCE_DIR}/diagnostics-after-drive.png" >/dev/null 2>&1 || true
printf 'diagnostics\nentry=intro-sheet\n' > "${VERIFY_EVIDENCE_DIR}/feature.txt"

if [[ ${TEST_RC} -ne 0 ]]; then
  echo "drive: XCUITest failed (exit ${TEST_RC}). See ${VERIFY_EVIDENCE_DIR}/xcuitest-tail.log" >&2
  exit "${TEST_RC}"
fi

grep -q 'NuvioDiagnosticsIntroSeen=true' "${VERIFY_EVIDENCE_DIR}/side-effects.txt"
grep -q 'NuvioDiagnosticsEnabled=true' "${VERIFY_EVIDENCE_DIR}/side-effects.txt"
echo "drive: diagnostics intro persisted"
