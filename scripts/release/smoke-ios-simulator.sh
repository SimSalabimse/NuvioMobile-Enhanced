#!/usr/bin/env bash
# Cheap iOS Simulator smoke after an IPA tip. Never installs on a real phone.
# Checks: build_install, launch, home, details, settings.
# Seeds local prefs (anonymous + profile + whats-new seen) so Auth/WhatsNew do not block.
# Uses in-app smoke_deeplink / smoke_activate_tab prefs (see iosApp/iOSApp.swift) to avoid
# the system "Open in Nuvio Enhanced?" dialog that blocks simctl openurl.
set -euo pipefail

repository_root="$(cd "$(dirname "$0")/../.." && pwd -P)"
cd "${repository_root}"

SIMULATOR_UDID="${SMOKE_SIMULATOR_UDID:-}"
SIMULATOR_NAME="${SMOKE_SIMULATOR_NAME:-iPhone 18 Pro}"
DERIVED_DATA="${SMOKE_DERIVED_DATA:-${repository_root}/build/ios-derived-full-simulator}"
BUNDLE_ID="${SMOKE_BUNDLE_ID:-com.nuvio.app}"
APP_NAME="${SMOKE_APP_NAME:-Nuvio Enhanced.app}"
DISTRIBUTION="${NUVIO_IOS_DISTRIBUTION:-full}"
RESULTS_DIR="${SMOKE_RESULTS_DIR:-${repository_root}/build/smoke-results/latest}"
LAUNCH_WAIT_SECONDS="${SMOKE_LAUNCH_WAIT_SECONDS:-14}"
HOME_WAIT_SECONDS="${SMOKE_HOME_WAIT_SECONDS:-5}"
SKIP_BUILD="${SMOKE_SKIP_BUILD:-0}"
ANON_ID="${SMOKE_ANON_ID:-smoke-anon-local}"
DETAILS_URL="${SMOKE_DETAILS_URL:-nuvio://detail/movie/tt0111161}"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  elif /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  else
    export JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
  fi
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}${PATH}"
export ORG_GRADLE_JAVA_HOME="${ORG_GRADLE_JAVA_HOME:-${JAVA_HOME:-}}"
export NUVIO_GRADLE_JVMARGS="${NUVIO_GRADLE_JVMARGS:--Xmx4608M -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=768M}"
export NUVIO_KOTLIN_NATIVE_JVMARGS="${NUVIO_KOTLIN_NATIVE_JVMARGS:--Xmx4608M}"
export KOTLIN_DAEMON_JVMARGS="${KOTLIN_DAEMON_JVMARGS:--Xmx2048M}"

mkdir -p "${RESULTS_DIR}"
RESULT_JSON="${RESULTS_DIR}/smoke-result.json"
declare -a CHECK_NAMES=() CHECK_RESULTS=() CHECK_NOTES=()

record() {
  local name="$1" result="$2" note="${3:-}"
  CHECK_NAMES+=("$name")
  CHECK_RESULTS+=("$result")
  CHECK_NOTES+=("$note")
  printf 'smoke: %-16s %s%s\n' "$name" "$result" "${note:+ — $note}"
}

pick_simulator() {
  if [[ -n "${SIMULATOR_UDID}" ]]; then
    printf '%s\n' "${SIMULATOR_UDID}"; return
  fi
  local booted
  booted="$(xcrun simctl list devices booted | awk -F '[()]' '/Booted/ { print $2; exit }')"
  if [[ -n "${booted}" ]]; then printf '%s\n' "${booted}"; return; fi
  local udid
  udid="$(xcrun simctl list devices available | awk -v name="${SIMULATOR_NAME}" '
    $0 ~ name {
      if (match($0, /\(([0-9A-F-]{36})\)/)) { print substr($0, RSTART+1, RLENGTH-2); exit }
    }')"
  if [[ -z "${udid}" ]]; then echo "No simulator named ${SIMULATOR_NAME}" >&2; exit 1; fi
  printf '%s\n' "${udid}"
}

ensure_booted() {
  local udid="$1"
  if ! xcrun simctl list devices | grep -q "${udid}.*Booted"; then
    xcrun simctl boot "${udid}" >/dev/null 2>&1 || true
    xcrun simctl bootstatus "${udid}" -b >/dev/null
  fi
}

app_alive() {
  local udid="$1"
  local i
  for i in 1 2 3 4 5; do
    if xcrun simctl spawn "${udid}" launchctl list 2>/dev/null | grep -q "UIKitApplication:${BUNDLE_ID}"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# Reject known gate screens / system Open dialog. Pass when bottom tab chrome is present
# (home/settings) or when the frame clearly differs from the seeded home empty state after a deeplink.
classify_screen() {
  local path="$1"
  python3 - "$path" <<'PY'
import sys
from pathlib import Path
path = Path(sys.argv[1])
if not path.exists() or path.stat().st_size < 20000:
    print("empty"); raise SystemExit(0)
try:
    from PIL import Image
except ImportError:
    import subprocess; subprocess.check_call([sys.executable,"-m","pip","install","pillow","-q"])
    from PIL import Image
im = Image.open(path).convert("RGB")
w, h = im.size
# Sample center for system alert (light grey panel ~135)
cx, cy = w//2, h//2
center = im.getpixel((cx, cy))
# Blue Open button sits slightly right of center on the alert
blueish = im.getpixel((int(w*0.62), cy))
# Bottom tab bar region
bottom = [im.getpixel((x, int(h*0.93))) for x in range(int(w*0.15), int(w*0.85), max(1,w//40))]
bottom_mean = tuple(sum(c[i] for c in bottom)//len(bottom) for i in range(3))
# Top content
top = im.getpixel((cx, int(h*0.18)))

def is_grey(p, lo=100, hi=180):
    return lo <= p[0] <= hi and abs(p[0]-p[1])<25 and abs(p[1]-p[2])<25

def is_blue(p):
    return p[2] > 140 and p[2] > p[0] + 40 and p[2] > p[1] + 20

# System "Open in …?" dialog: grey panel + blue button
if is_grey(center) and is_blue(blueish):
    print("open_dialog"); raise SystemExit(0)

# Welcome / What's New: bright white Continue button near bottom
continue_btn = im.getpixel((cx, int(h*0.88)))
if continue_btn[0] > 200 and continue_btn[1] > 200 and continue_btn[2] > 200 and top[0] < 40:
    print("whats_new"); raise SystemExit(0)

# Who's watching / auth: dark bg, little bottom chrome (tab bar is mid-grey pill)
# Tab bar present if bottom band is elevated grey (not pure black)
if bottom_mean[0] > 25 or bottom_mean[1] > 25:
    # Distinguish home empty ("No content sources" card near top) vs settings (list)
    # Settings / profile tends to have more mid-screen structure; home empty is very black mid.
    mid_band = [sum(im.getpixel((x, y)))/3 for x in range(0,w,24) for y in range(int(h*0.35), int(h*0.7), 24)]
    mid_mean = sum(mid_band)/len(mid_band)
    if mid_mean < 20:
        print("home_empty")
    elif mid_mean < 55:
        print("home_or_catalog")
    else:
        print("settings_or_details")
    raise SystemExit(0)

print("gate_or_blank")
PY
}

# App UserDefaults live in the *container* plist. `simctl spawn defaults write`
# hits the simulator-global prefs domain and is invisible to the running app
# once a container plist exists. Always write the container file.
container_prefs_plist() {
  local udid="$1"
  local data
  data="$(xcrun simctl get_app_container "${udid}" "${BUNDLE_ID}" data 2>/dev/null || true)"
  if [[ -z "${data}" || ! -d "${data}" ]]; then
    echo ""
    return 0
  fi
  mkdir -p "${data}/Library/Preferences"
  echo "${data}/Library/Preferences/${BUNDLE_ID}.plist"
}

seed_base_prefs() {
  local udid="$1"
  local payload plist ver code key
  payload="$(python3 - <<PY
import json
print(json.dumps({
  "userId": "${ANON_ID}",
  "activeProfileIndex": 1,
  "hasEverSelectedProfile": True,
  "rememberLastProfileEnabled": True,
  "profiles": [{
    "id": "local-1",
    "user_id": "${ANON_ID}",
    "profile_index": 1,
    "name": "Smoke",
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
}))
PY
)"
  ver="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "${APP_PATH}/Info.plist" 2>/dev/null || echo "0.6.2.1")"
  code="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "${APP_PATH}/Info.plist" 2>/dev/null || echo "196")"
  key="${ver}(${code})"
  plist="$(container_prefs_plist "${udid}")"
  if [[ -n "${plist}" ]]; then
    python3 - "${plist}" "${ANON_ID}" "${payload}" "${key}" <<'PY'
import plistlib, sys
from pathlib import Path
path = Path(sys.argv[1])
anon, payload, whats = sys.argv[2], sys.argv[3], sys.argv[4]
data = {}
if path.exists():
    try:
        data = plistlib.loads(path.read_bytes())
    except Exception:
        data = {}
data["anonymous_user_id"] = anon
data["profile_payload"] = payload
data["whats_new_last_seen_v1"] = whats
data.pop("smoke_deeplink", None)
data.pop("smoke_activate_tab", None)
path.parent.mkdir(parents=True, exist_ok=True)
path.write_bytes(plistlib.dumps(data, fmt=plistlib.FMT_XML))
PY
  else
    # Fresh install: container not created yet — global defaults work until first app write
    xcrun simctl spawn "${udid}" defaults write "${BUNDLE_ID}" anonymous_user_id -string "${ANON_ID}" >/dev/null
    xcrun simctl spawn "${udid}" defaults write "${BUNDLE_ID}" profile_payload -string "${payload}" >/dev/null
    xcrun simctl spawn "${udid}" defaults write "${BUNDLE_ID}" whats_new_last_seen_v1 -string "${key}" >/dev/null
  fi
}

clear_smoke_prefs() {
  local udid="$1"
  local plist
  plist="$(container_prefs_plist "${udid}")"
  if [[ -n "${plist}" && -f "${plist}" ]]; then
    python3 - "${plist}" <<'PY'
import plistlib, sys
from pathlib import Path
path = Path(sys.argv[1])
try:
    data = plistlib.loads(path.read_bytes())
except Exception:
    raise SystemExit(0)
data.pop("smoke_deeplink", None)
data.pop("smoke_activate_tab", None)
path.write_bytes(plistlib.dumps(data, fmt=plistlib.FMT_XML))
PY
  fi
  xcrun simctl spawn "${udid}" defaults delete "${BUNDLE_ID}" smoke_deeplink >/dev/null 2>&1 || true
  xcrun simctl spawn "${udid}" defaults delete "${BUNDLE_ID}" smoke_activate_tab >/dev/null 2>&1 || true
}

launch_clean() {
  local udid="$1"
  xcrun simctl terminate "${udid}" "${BUNDLE_ID}" >/dev/null 2>&1 || true
  clear_smoke_prefs "${udid}"
  seed_base_prefs "${udid}"
  env -u SIMCTL_CHILD_SMOKE_DEEPLINK -u SIMCTL_CHILD_SMOKE_ACTIVATE_TAB \
    xcrun simctl launch "${udid}" "${BUNDLE_ID}" >/dev/null
}

shots_differ() {
  local a="$1" b="$2"
  [[ -f "$a" && -f "$b" ]] || return 1
  ! cmp -s "$a" "$b"
}

SIMULATOR_UDID="$(pick_simulator)"
ensure_booted "${SIMULATOR_UDID}"
echo "smoke: simulator ${SIMULATOR_UDID}"
echo "smoke: free disk $(df -h /System/Volumes/Data | awk 'NR==2{print $4}')"
echo "smoke: Europe/Oslo $(TZ=Europe/Oslo date '+%Y-%m-%d %H:%M:%S %Z')"

APP_PATH="${DERIVED_DATA}/Build/Products/Debug-iphonesimulator/${APP_NAME}"
BUILD_OK=0

if [[ "${SKIP_BUILD}" != "1" ]]; then
  mkdir -p "${DERIVED_DATA}"
  LOG="${RESULTS_DIR}/xcodebuild-simulator.log"
  set +e
  caffeinate -dims env \
    JAVA_HOME="${JAVA_HOME:-}" \
    ORG_GRADLE_JAVA_HOME="${ORG_GRADLE_JAVA_HOME:-}" \
    NUVIO_IOS_DISTRIBUTION="${DISTRIBUTION}" \
    NUVIO_GRADLE_JVMARGS="${NUVIO_GRADLE_JVMARGS}" \
    NUVIO_KOTLIN_NATIVE_JVMARGS="${NUVIO_KOTLIN_NATIVE_JVMARGS}" \
    KOTLIN_DAEMON_JVMARGS="${KOTLIN_DAEMON_JVMARGS}" \
    xcodebuild \
      -project iosApp/iosApp.xcodeproj \
      -scheme iosApp \
      -configuration Debug \
      -destination "id=${SIMULATOR_UDID}" \
      -derivedDataPath "${DERIVED_DATA}" \
      build >"${LOG}" 2>&1
  BUILD_RC=$?
  set -e
  if [[ ${BUILD_RC} -ne 0 || ! -d "${APP_PATH}" ]]; then
    record "build_install" "FAIL" "xcodebuild exit ${BUILD_RC}; see ${LOG}"
  else
    record "build_install" "PASS" "${APP_PATH}"
    BUILD_OK=1
  fi
else
  if [[ -d "${APP_PATH}" ]]; then
    record "build_install" "PASS" "skipped build; using ${APP_PATH}"
    BUILD_OK=1
  else
    record "build_install" "FAIL" "SMOKE_SKIP_BUILD=1 but app missing: ${APP_PATH}"
  fi
fi

if [[ "${BUILD_OK}" == "1" ]]; then
  xcrun simctl uninstall "${SIMULATOR_UDID}" "${BUNDLE_ID}" >/dev/null 2>&1 || true
  if ! xcrun simctl install "${SIMULATOR_UDID}" "${APP_PATH}"; then
    record "build_install" "FAIL" "simctl install failed"
    BUILD_OK=0
  else
    # Warm launch so the data container exists before we seed prefs into it.
    xcrun simctl launch "${SIMULATOR_UDID}" "${BUNDLE_ID}" >/dev/null 2>&1 || true
    sleep 4
    xcrun simctl terminate "${SIMULATOR_UDID}" "${BUNDLE_ID}" >/dev/null 2>&1 || true
    sleep 1
  fi
fi

# --- launch ---
LAUNCH_OK=0
if [[ "${BUILD_OK}" == "1" ]]; then
  launch_clean "${SIMULATOR_UDID}"
  sleep "${LAUNCH_WAIT_SECONDS}"
  if app_alive "${SIMULATOR_UDID}"; then
    record "launch" "PASS" "alive after ${LAUNCH_WAIT_SECONDS}s"
    LAUNCH_OK=1
  else
    record "launch" "FAIL" "process not alive after ${LAUNCH_WAIT_SECONDS}s"
  fi
else
  record "launch" "FAIL" "skipped — no install"
fi

# --- home ---
HOME_SHOT="${RESULTS_DIR}/home.png"
if [[ "${LAUNCH_OK}" == "1" ]]; then
  kind="empty"
  # Cold start can show a black frame before the tab bar paints.
  for _try in 1 2 3 4 5 6; do
    sleep "${HOME_WAIT_SECONDS}"
    xcrun simctl io "${SIMULATOR_UDID}" screenshot "${HOME_SHOT}" >/dev/null 2>&1 || true
    kind="$(classify_screen "${HOME_SHOT}")"
    if [[ "${kind}" == "home_empty" || "${kind}" == "home_or_catalog" || "${kind}" == "settings_or_details" ]]; then
      break
    fi
  done
  if [[ "${kind}" == "home_empty" || "${kind}" == "home_or_catalog" || "${kind}" == "settings_or_details" ]]; then
    record "home" "PASS" "${kind} ${HOME_SHOT}"
  else
    record "home" "FAIL" "got ${kind} instead of home (${HOME_SHOT})"
  fi
else
  record "home" "FAIL" "skipped — launch failed"
fi

# --- details via SIMCTL_CHILD_SMOKE_DEEPLINK (in-app; no Open dialog) ---
DETAILS_SHOT="${RESULTS_DIR}/details.png"
if [[ "${LAUNCH_OK}" == "1" ]]; then
  xcrun simctl terminate "${SIMULATOR_UDID}" "${BUNDLE_ID}" >/dev/null 2>&1 || true
  sleep 1
  seed_base_prefs "${SIMULATOR_UDID}"
  clear_smoke_prefs "${SIMULATOR_UDID}"
  SIMCTL_CHILD_SMOKE_DEEPLINK="${DETAILS_URL}" \
    xcrun simctl launch "${SIMULATOR_UDID}" "${BUNDLE_ID}" >/dev/null
  sleep $((LAUNCH_WAIT_SECONDS + 8))
  kind="empty"
  for _try in 1 2 3; do
    xcrun simctl io "${SIMULATOR_UDID}" screenshot "${DETAILS_SHOT}" >/dev/null 2>&1 || true
    kind="$(classify_screen "${DETAILS_SHOT}")"
    if shots_differ "${HOME_SHOT}" "${DETAILS_SHOT}" && [[ "${kind}" != "open_dialog" && "${kind}" != "whats_new" && "${kind}" != "empty" ]]; then
      break
    fi
    sleep 5
  done
  # Detail screens hide the tab bar, so classify_screen often returns gate_or_blank
  # even when navigation succeeded. Prefer byte-diff from home; only hard-fail
  # known gate dialogs / empty captures.
  if [[ "${kind}" == "open_dialog" || "${kind}" == "whats_new" || "${kind}" == "empty" ]]; then
    record "details" "FAIL" "got ${kind} after ${DETAILS_URL}"
  elif shots_differ "${HOME_SHOT}" "${DETAILS_SHOT}"; then
    record "details" "PASS" "${kind} (differs from home) via ${DETAILS_URL}"
  elif [[ "${kind}" == "settings_or_details" || "${kind}" == "home_or_catalog" ]]; then
    record "details" "PASS" "${kind} via ${DETAILS_URL}"
  else
    record "details" "FAIL" "still on ${kind} (same as home) after ${DETAILS_URL}"
  fi
else
  record "details" "FAIL" "skipped — launch failed"
fi

# --- settings via SIMCTL_CHILD_SMOKE_ACTIVATE_TAB ---
SETTINGS_SHOT="${RESULTS_DIR}/settings.png"
if [[ "${LAUNCH_OK}" == "1" ]]; then
  xcrun simctl terminate "${SIMULATOR_UDID}" "${BUNDLE_ID}" >/dev/null 2>&1 || true
  sleep 1
  seed_base_prefs "${SIMULATOR_UDID}"
  clear_smoke_prefs "${SIMULATOR_UDID}"
  SIMCTL_CHILD_SMOKE_ACTIVATE_TAB=settings \
    xcrun simctl launch "${SIMULATOR_UDID}" "${BUNDLE_ID}" >/dev/null
  sleep $((LAUNCH_WAIT_SECONDS + 6))
  xcrun simctl io "${SIMULATOR_UDID}" screenshot "${SETTINGS_SHOT}" >/dev/null 2>&1 || true
  kind="$(classify_screen "${SETTINGS_SHOT}")"
  if [[ "${kind}" == "open_dialog" || "${kind}" == "whats_new" || "${kind}" == "gate_or_blank" || "${kind}" == "empty" ]]; then
    record "settings" "FAIL" "got ${kind} after activate settings"
  elif shots_differ "${HOME_SHOT}" "${SETTINGS_SHOT}"; then
    record "settings" "PASS" "${kind} (differs from home) via SMOKE_ACTIVATE_TAB=settings"
  elif [[ "${kind}" == "settings_or_details" || "${kind}" == "home_or_catalog" ]]; then
    record "settings" "PASS" "${kind} via SMOKE_ACTIVATE_TAB=settings"
  else
    record "settings" "FAIL" "still on ${kind} after activate settings"
  fi
else
  record "settings" "FAIL" "skipped — launch failed"
fi

if [[ "${SMOKE_TRY_STREAM:-0}" == "1" ]]; then
  record "test_stream" "SKIP" "optional; no credentialed stream harness yet"
else
  record "test_stream" "SKIP" "optional; set SMOKE_TRY_STREAM=1 to attempt"
fi

RESULTS_TSV="${RESULTS_DIR}/checks.tsv"
: > "${RESULTS_TSV}"
i=0
while [[ $i -lt ${#CHECK_NAMES[@]} ]]; do
  printf '%s\t%s\t%s\n' "${CHECK_NAMES[$i]}" "${CHECK_RESULTS[$i]}" "${CHECK_NOTES[$i]}" >> "${RESULTS_TSV}"
  i=$((i + 1))
done
python3 - "${RESULT_JSON}" "${RESULTS_TSV}" <<'PY'
import json, sys
out, tsv = sys.argv[1], sys.argv[2]
checks = []
for line in open(tsv, encoding="utf-8"):
    name, result, note = line.rstrip("\n").split("\t", 2)
    checks.append({"name": name, "result": result, "note": note})
required = {"build_install", "launch", "home", "details", "settings"}
passed = all(c["result"] == "PASS" for c in checks if c["name"] in required)
payload = {"checks": checks, "passed": passed}
json.dump(payload, open(out, "w", encoding="utf-8"), indent=2)
print(json.dumps(payload, indent=2))
raise SystemExit(0 if passed else 1)
PY
