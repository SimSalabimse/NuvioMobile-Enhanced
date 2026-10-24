#!/usr/bin/env bash
# Write the container preferences the iOS app already reads, so a simulator
# launch can pass the anonymous-profile and What's New gates.
# Removes the one-time diagnostics intro flag so that sheet can show again.
# The app must not be running: a live process overwrites this plist on exit.
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <simulator-udid> <path-to-Nuvio-Enhanced.app>" >&2
  exit 2
fi

UDID="$1"
APP_PATH="$2"
ANON_ID="${SMOKE_ANON_ID:-verify-anon-local}"

if [[ ! -d "${APP_PATH}" ]]; then
  echo "seed: app bundle not found: ${APP_PATH}" >&2
  exit 1
fi

BUNDLE_ID="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "${APP_PATH}/Info.plist")"
VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "${APP_PATH}/Info.plist")"
BUILD="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "${APP_PATH}/Info.plist")"
WHATS_NEW_KEY="${VERSION}(${BUILD})"

DATA_DIR="$(xcrun simctl get_app_container "${UDID}" "${BUNDLE_ID}" data 2>/dev/null || true)"
if [[ -z "${DATA_DIR}" || ! -d "${DATA_DIR}" ]]; then
  echo "seed: no data container for ${BUNDLE_ID} on ${UDID}. Launch the app once, terminate it, then seed." >&2
  exit 1
fi

PLIST="${DATA_DIR}/Library/Preferences/${BUNDLE_ID}.plist"
mkdir -p "$(dirname "${PLIST}")"

python3 - "${PLIST}" "${ANON_ID}" "${WHATS_NEW_KEY}" <<'PY'
import json
import plistlib
import sys
from pathlib import Path

path, anon, whats = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
payload = {
    "userId": anon,
    "activeProfileIndex": 1,
    "hasEverSelectedProfile": True,
    "rememberLastProfileEnabled": True,
    "profiles": [{
        "id": "local-1",
        "user_id": anon,
        "profile_index": 1,
        "name": "Verify",
        "avatar_color_hex": "#1E88E5",
        "avatar_id": None,
        "avatar_url": None,
        "profile_background_id": None,
        "profile_background_url": None,
        "uses_primary_addons": False,
        "uses_primary_plugins": False,
        "pin_enabled": False,
        "pin_locked_until": None,
        "created_at": "2026-10-23T00:00:00Z",
        "updated_at": "2026-10-23T00:00:00Z",
    }],
}
data = {}
if path.exists():
    try:
        data = plistlib.loads(path.read_bytes())
    except Exception:
        data = {}
data["anonymous_user_id"] = anon
data["profile_payload"] = json.dumps(payload)
data["whats_new_last_seen_v1"] = whats
for key in (
    "NuvioDiagnosticsIntroSeen",
    "NuvioDiagnosticsEnabled",
    "smoke_deeplink",
    "smoke_activate_tab",
):
    data.pop(key, None)
path.write_bytes(plistlib.dumps(data, fmt=plistlib.FMT_XML))
PY

# Drop a cached copy of the pre-seed defaults so the next launch reads this plist.
xcrun simctl spawn "${UDID}" killall -9 cfprefsd >/dev/null 2>&1 || true

echo "seed: ${BUNDLE_ID} anon=${ANON_ID} whats_new=${WHATS_NEW_KEY} plist=${PLIST}"
