#!/usr/bin/env bash
# Drive the iOS Simulator and save PNG screenshots plus offline/online transition strips.
#
# Nuvio-specific values are the variables below. Another app (InkList) can reuse this
# script by changing them and keeping the same -NuvioUiFixture-style launch argument.
#
# This machine check does not compile or boot a simulator. On a Mac, run:
#   ./scripts/ui-shots.sh --dry-run
#   ./scripts/ui-shots.sh

set -euo pipefail

# --- app-specific (change these to reuse the script) ---
SCHEME="iosApp"
PROJECT_REL="iosApp/iosApp.xcodeproj"
CONFIGURATION="Debug"
IOS_DISTRIBUTION="${NUVIO_IOS_DISTRIBUTION:-full}"
BUNDLE_ID="com.nuvio.app"
FIXTURE_ARG="-NuvioUiFixture"
CONNECTIVITY_FILE="nuvio-ui-connectivity.txt"
# Keep ALL_STATES in the same order as UiFixture.wireName.
ALL_STATES="home-online,home-offline,details-online,details-offline,details-movie-offline,downloads,long-titles,smart-downloads"
DEFAULT_DEVICES="iPhone 18 Pro,iPhone 17e"
TRANSITION_FIXTURE="home-offline"
TRANSITION_ONLINE_SECONDS=2
TRANSITION_OFFLINE_SECONDS=5
TRANSITION_TAIL_SECONDS=3

# --- runtime ---
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FFMPEG_BIN="${FFMPEG:-/opt/homebrew/bin/ffmpeg}"
SETTLE_SECONDS="${NUVIO_UI_SHOTS_SETTLE:-4}"
DERIVED_DATA="${NUVIO_UI_SHOTS_DERIVED:-$ROOT_DIR/build/ios-ui-shots-derived}"
DEVICES="$DEFAULT_DEVICES"
STATES="$ALL_STATES"
OUT_DIR=""
LABEL=""
SKIP_BUILD=0
DRY_RUN=0
ICLOUD=0

log() { printf '[ui-shots] %s\n' "$*"; }
die() { printf '[ui-shots] error: %s\n' "$*" >&2; exit 1; }

on_error() {
  printf '[ui-shots] error: command failed at line %s: %s\n' "$1" "$2" >&2
  exit 1
}
trap 'on_error "$LINENO" "$BASH_COMMAND"' ERR

usage() {
  cat <<EOF
Usage: ./scripts/ui-shots.sh [options]

  --devices "iPhone 18 Pro,iPhone 17e"   Simulators (default: one large, one small)
  --states a,b,c                         Fixture names (default: all)
  --out DIR                              Output directory
  --label NAME                           Name used for the iCloud copy
  --settle SECONDS                       Wait after launch (default: ${SETTLE_SECONDS})
  --skip-build                           Install the existing Debug simulator build
  --dry-run                              Print the plan and the commands, then exit 0
  --icloud                               Copy the folder into iCloud Drive
  -h, --help

Transitions are recorded for every device after the stills: ${TRANSITION_FIXTURE}
goes offline -> online at ${TRANSITION_ONLINE_SECONDS}s -> offline at ${TRANSITION_OFFLINE_SECONDS}s.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --devices)
      [[ $# -ge 2 ]] || die "--devices needs a value"
      DEVICES="$2"
      shift 2
      ;;
    --states)
      [[ $# -ge 2 ]] || die "--states needs a value"
      STATES="$2"
      shift 2
      ;;
    --out)
      [[ $# -ge 2 ]] || die "--out needs a directory"
      OUT_DIR="$2"
      shift 2
      ;;
    --label)
      [[ $# -ge 2 ]] || die "--label needs a name"
      LABEL="$2"
      shift 2
      ;;
    --settle)
      [[ $# -ge 2 ]] || die "--settle needs seconds"
      SETTLE_SECONDS="$2"
      shift 2
      ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --icloud) ICLOUD=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1 (try --help)" ;;
  esac
done

if [[ ! "$SETTLE_SECONDS" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  die "--settle must be a positive number of seconds (got '${SETTLE_SECONDS}')"
fi
if [[ -n "$LABEL" && "$LABEL" == *"/"* ]]; then
  die "--label must not contain a slash"
fi

# Bash 3.2 (the macOS /bin/bash) has no namerefs, and set -u rejects empty arrays.
trim_token() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

read_csv() {
  local csv="$1"
  local part
  local -a raw=()
  IFS=',' read -r -a raw <<< "$csv"
  [[ ${#raw[@]} -gt 0 ]] || die "list is empty: '$csv'"
  for part in "${raw[@]}"; do
    part="$(trim_token "$part")"
    [[ -n "$part" ]] || die "empty entry in '$csv'"
    printf '%s\n' "$part"
  done
}

# Command substitution runs in a subshell. On macOS bash 3.2, set -e does not
# abort the parent when that subshell exits, so the status is checked here.
devices_blob="$(read_csv "$DEVICES")" || die "could not parse --devices"
states_blob="$(read_csv "$STATES")" || die "could not parse --states"
DEVICE_NAMES=()
STATE_NAMES=()
while IFS= read -r part; do
  [[ -n "$part" ]] || continue
  DEVICE_NAMES+=("$part")
done <<EOF
$devices_blob
EOF
while IFS= read -r part; do
  [[ -n "$part" ]] || continue
  STATE_NAMES+=("$part")
done <<EOF
$states_blob
EOF
[[ ${#DEVICE_NAMES[@]} -gt 0 ]] || die "device list is empty"
[[ ${#STATE_NAMES[@]} -gt 0 ]] || die "state list is empty"

known_state() {
  case ",${ALL_STATES}," in
    *,"$1",*) return 0 ;;
    *) return 1 ;;
  esac
}

for state in "${STATE_NAMES[@]}"; do
  if ! known_state "$state"; then
    die "Unknown state '${state}'. Known states: ${ALL_STATES}"
  fi
done

if [[ -z "$OUT_DIR" ]]; then
  sha="$(git -C "$ROOT_DIR" rev-parse --short=8 HEAD 2>/dev/null || echo nogit)"
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  OUT_DIR="$ROOT_DIR/build/ui-shots/${stamp}-${sha}"
fi
if [[ -z "$LABEL" ]]; then
  LABEL="$(basename "$OUT_DIR")"
fi

if [[ ! -x "$FFMPEG_BIN" ]]; then
  if command -v ffmpeg >/dev/null 2>&1; then
    FFMPEG_BIN="$(command -v ffmpeg)"
  elif [[ "$DRY_RUN" == "0" ]]; then
    die "ffmpeg not found at ${FFMPEG_BIN}. Install it or set FFMPEG."
  else
    log "dry-run: ffmpeg not found; transition sheets would fail on a real run"
    FFMPEG_BIN="ffmpeg"
  fi
fi

require_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    die "Missing required command: $1"
  fi
}

if [[ "$DRY_RUN" == "0" ]]; then
  require_tool xcrun
  require_tool xcodebuild
  [[ "$SKIP_BUILD" == "1" ]] || require_tool git
fi

SIMCTL_OK=0
if command -v xcrun >/dev/null 2>&1; then
  SIMCTL_OK=1
fi

COMMAND_LOG=""
run_cmd() {
  local rendered=""
  local arg
  for arg in "$@"; do
    rendered+=" $(printf '%q' "$arg")"
  done
  rendered="${rendered# }"
  if [[ -n "$COMMAND_LOG" ]]; then
    printf '%s\n' "$rendered" >> "$COMMAND_LOG"
  fi
  if [[ "$DRY_RUN" == "1" ]]; then
    log "dry-run: $rendered"
    return 0
  fi
  log "$rendered"
  "$@"
}

slugify() {
  local name="${1// /-}"
  # Hyphen stays literal at the end of the class. No newline is fed to tr.
  printf '%s' "$name" | tr -c 'A-Za-z0-9._-' '-'
}

resolve_udid() {
  local name="$1"
  if [[ "$SIMCTL_OK" == "0" ]]; then
    if [[ "$DRY_RUN" == "1" ]]; then
      printf '%s\n' "DRY-RUN-UDID"
      return 0
    fi
    die "xcrun is not available, so '${name}' cannot be resolved"
  fi
  local listing
  listing="$(xcrun simctl list devices available)"
  local matches
  matches="$(printf '%s\n' "$listing" | awk -v name="$name" '
    {
      line = $0
      sub(/^[[:space:]]+/, "", line)
      if (index(line, name " (") == 1) print $0
    }
  ')"
  if [[ -z "$matches" ]]; then
    die "No available simulator named '${name}'. Run: xcrun simctl list devices available"$'\n'"${listing}"
  fi
  local chosen
  chosen="$(printf '%s\n' "$matches" | grep '(Booted)' | head -n 1 || true)"
  if [[ -z "$chosen" ]]; then
    chosen="$(printf '%s\n' "$matches" | head -n 1)"
  fi
  local udid
  udid="$(printf '%s\n' "$chosen" | sed -n 's/.*(\([A-Fa-f0-9-]\{36\}\)).*/\1/p' | head -n 1)"
  [[ -n "$udid" ]] || die "Could not parse a UDID for '${name}' from: ${chosen}"
  printf '%s\n' "$udid"
}

boot_simulator() {
  local udid="$1"
  if [[ "$DRY_RUN" == "1" && "$SIMCTL_OK" == "0" ]]; then
    run_cmd xcrun simctl boot "$udid"
    run_cmd xcrun simctl bootstatus "$udid" -b
    return 0
  fi
  if xcrun simctl list devices | grep -F "($udid)" | grep -q '(Booted)'; then
    log "simulator already booted: $udid"
  else
    run_cmd xcrun simctl boot "$udid"
  fi
  run_cmd xcrun simctl bootstatus "$udid" -b
}

override_status_bar() {
  local udid="$1"
  run_cmd xcrun simctl status_bar "$udid" override \
    --time 9:41 \
    --batteryState charged \
    --batteryLevel 100 \
    --cellularBars 4 \
    --wifiBars 3
}

find_built_app() {
  local products="$DERIVED_DATA/Build/Products/${CONFIGURATION}-iphonesimulator"
  if [[ "$DRY_RUN" == "1" && ! -d "$products" ]]; then
    printf '%s\n' "$products/Nuvio.app"
    return 0
  fi
  [[ -d "$products" ]] || die "Simulator products directory not found: ${products}. Build first or drop --skip-build."
  local -a apps=()
  local candidate
  while IFS= read -r candidate; do
    apps+=("$candidate")
  done < <(find "$products" -maxdepth 1 -type d -name '*.app' | sort)
  [[ ${#apps[@]} -eq 1 ]] || die "Expected one .app in ${products}, found ${#apps[@]}."
  printf '%s\n' "${apps[0]}"
}

read_bundle_id() {
  local app="$1"
  if [[ "$DRY_RUN" == "1" && ! -d "$app" ]]; then
    printf '%s\n' "$BUNDLE_ID"
    return 0
  fi
  local built
  built="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app/Info.plist")"
  [[ -n "$built" ]] || die "Built app has no CFBundleIdentifier: ${app}"
  printf '%s\n' "$built"
}

build_app() {
  if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
  local udid="$1"
  log "Building ${SCHEME} ${CONFIGURATION} for simulator ${udid} (distribution ${IOS_DISTRIBUTION})"
  run_cmd env "NUVIO_IOS_DISTRIBUTION=${IOS_DISTRIBUTION}" xcodebuild \
    -project "$ROOT_DIR/$PROJECT_REL" \
    -scheme "$SCHEME" \
    -configuration "$CONFIGURATION" \
    -destination "id=${udid}" \
    -derivedDataPath "$DERIVED_DATA" \
    build
}

write_connectivity() {
  local udid="$1"
  local word="$2"
  if [[ "$DRY_RUN" == "1" ]]; then
    log "dry-run: write '${word}' to <app data>/Documents/${CONNECTIVITY_FILE} on ${udid}"
    return 0
  fi
  local container=""
  local _attempt
  for _attempt in 1 2 3 4 5 6 7 8 9 10; do
    if container="$(xcrun simctl get_app_container "$udid" "$BUNDLE_ID" data 2>/dev/null)"; then
      break
    fi
    container=""
    sleep 0.3
  done
  [[ -n "$container" ]] || die "App container for ${BUNDLE_ID} on ${udid} was not created. Did launch fail?"
  local docs="${container}/Documents"
  mkdir -p "$docs"
  printf '%s\n' "$word" > "${docs}/${CONNECTIVITY_FILE}"
  log "wrote ${word} -> ${docs}/${CONNECTIVITY_FILE}"
}

launch_fixture() {
  local udid="$1"
  local state="$2"
  if [[ "$DRY_RUN" == "0" ]]; then
    xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
  else
    log "dry-run: xcrun simctl terminate ${udid} ${BUNDLE_ID} || true"
  fi
  run_cmd xcrun simctl launch "$udid" "$BUNDLE_ID" "$FIXTURE_ARG" "$state"
}

screenshot_state() {
  local udid="$1"
  local slug="$2"
  local state="$3"
  local destination="$OUT_DIR/$slug/${state}.png"
  launch_fixture "$udid" "$state"
  local word="online"
  case "$state" in
    home-offline|details-offline|details-movie-offline) word="offline" ;;
  esac
  write_connectivity "$udid" "$word"
  if [[ "$DRY_RUN" == "1" ]]; then
    log "dry-run: sleep ${SETTLE_SECONDS}"
  else
    sleep "$SETTLE_SECONDS"
  fi
  if [[ "$DRY_RUN" == "0" ]]; then
    mkdir -p "$(dirname "$destination")"
  fi
  run_cmd xcrun simctl io "$udid" screenshot "$destination"
  if [[ "$DRY_RUN" == "0" ]]; then
    xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
  fi
}

record_transition() {
  local udid="$1"
  local slug="$2"
  local dir="$OUT_DIR/$slug"
  local mp4="$dir/transition.mp4"
  local online_sheet="$dir/transition-online.png"
  local offline_sheet="$dir/transition-offline.png"
  if [[ "$DRY_RUN" == "0" ]]; then
    mkdir -p "$dir"
  fi
  launch_fixture "$udid" "$TRANSITION_FIXTURE"
  write_connectivity "$udid" "offline"
  if [[ "$DRY_RUN" == "1" ]]; then
    log "dry-run: sleep ${SETTLE_SECONDS} (settle before recording)"
    log "dry-run: xcrun simctl io ${udid} recordVideo --codec h264 ${mp4} &"
    log "dry-run: sleep 0.5 (confirm recordVideo); sleep 1.5; write online at ${TRANSITION_ONLINE_SECONDS}s; sleep $((TRANSITION_OFFLINE_SECONDS - TRANSITION_ONLINE_SECONDS)); write offline at ${TRANSITION_OFFLINE_SECONDS}s; sleep ${TRANSITION_TAIL_SECONDS}"
    log "dry-run: kill -INT <record-pid>"
    run_cmd "$FFMPEG_BIN" -y -hide_banner -loglevel error -ss 1.5 -t 2.5 -i "$mp4" -vf "fps=15,scale=240:-1,tile=8x4" -frames:v 1 "$online_sheet"
    run_cmd "$FFMPEG_BIN" -y -hide_banner -loglevel error -ss 4.5 -t 2.5 -i "$mp4" -vf "fps=15,scale=240:-1,tile=8x4" -frames:v 1 "$offline_sheet"
    return 0
  fi
  sleep "$SETTLE_SECONDS"
  log "recording $mp4"
  xcrun simctl io "$udid" recordVideo --codec h264 "$mp4" &
  local rec_pid=$!
  sleep 0.5
  if ! kill -0 "$rec_pid" 2>/dev/null; then
    die "simctl recordVideo exited before the toggles. Check ${mp4}."
  fi
  printf '%s\n' "xcrun simctl io ${udid} recordVideo --codec h264 ${mp4}" >> "$COMMAND_LOG"
  # 0.5s already elapsed, so the writes land at 2s and 5s on the recording clock.
  sleep 1.5
  write_connectivity "$udid" "online"
  sleep "$((TRANSITION_OFFLINE_SECONDS - TRANSITION_ONLINE_SECONDS))"
  write_connectivity "$udid" "offline"
  sleep "$TRANSITION_TAIL_SECONDS"
  kill -INT "$rec_pid" || die "could not signal recordVideo (pid ${rec_pid})"
  local _waits
  for _waits in 1 2 3 4 5 6 7 8 9 10; do
    if ! kill -0 "$rec_pid" 2>/dev/null; then
      break
    fi
    sleep 1
  done
  if kill -0 "$rec_pid" 2>/dev/null; then
    die "recordVideo (pid ${rec_pid}) did not exit after SIGINT"
  fi
  wait "$rec_pid" || true
  [[ -s "$mp4" ]] || die "recording is missing or empty: ${mp4}"
  run_cmd "$FFMPEG_BIN" -y -hide_banner -loglevel error -ss 1.5 -t 2.5 -i "$mp4" -vf "fps=15,scale=240:-1,tile=8x4" -frames:v 1 "$online_sheet"
  run_cmd "$FFMPEG_BIN" -y -hide_banner -loglevel error -ss 4.5 -t 2.5 -i "$mp4" -vf "fps=15,scale=240:-1,tile=8x4" -frames:v 1 "$offline_sheet"
  xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
}

write_index() {
  [[ "$DRY_RUN" == "1" ]] && return 0
  local index="$OUT_DIR/index.md"
  {
    printf '# UI shots\n\n'
    printf -- '- scheme: %s\n' "\`$SCHEME\`"
    printf -- '- project: %s\n' "\`$PROJECT_REL\`"
    printf -- '- configuration: %s\n' "\`$CONFIGURATION\`"
    printf -- '- distribution: %s\n' "\`$IOS_DISTRIBUTION\`"
    printf -- '- bundle id: %s\n' "\`$BUNDLE_ID\`"
    printf -- '- fixture argument: %s\n' "\`$FIXTURE_ARG\`"
    printf -- '- connectivity file: %s\n' "\`Documents/$CONNECTIVITY_FILE\`"
    printf -- '- settle seconds: %s\n' "$SETTLE_SECONDS"
    printf -- '- label: %s\n\n' "\`$LABEL\`"
    printf '## Files\n\n'
    local file
    local relative
    while IFS= read -r file; do
      relative="${file#"$OUT_DIR"/}"
      printf -- '- %s\n' "\`$relative\`"
    done < <(find "$OUT_DIR" -type f ! -name 'index.md' ! -name 'commands.txt' | sort)
    printf '\n## Commands\n\n'
    printf '```\n'
    if [[ -f "$COMMAND_LOG" ]]; then
      cat "$COMMAND_LOG"
    fi
    printf '```\n'
  } > "$index"
}

if [[ "$DRY_RUN" == "0" ]]; then
  mkdir -p "$OUT_DIR"
  COMMAND_LOG="$OUT_DIR/commands.txt"
  : > "$COMMAND_LOG"
fi

log "output: $OUT_DIR"
log "states: ${STATE_NAMES[*]}"
log "devices: ${DEVICE_NAMES[*]}"

FIRST_UDID=""
declare -a RESOLVED_UDIDS=()
declare -a RESOLVED_SLUGS=()
for name in "${DEVICE_NAMES[@]}"; do
  udid="$(resolve_udid "$name")"
  slug="$(slugify "$name")"
  RESOLVED_UDIDS+=("$udid")
  RESOLVED_SLUGS+=("$slug")
  log "device ${name} -> ${udid} (${slug})"
  if [[ -z "$FIRST_UDID" ]]; then
    FIRST_UDID="$udid"
  fi
done

if [[ "$SKIP_BUILD" == "0" ]]; then
  build_app "$FIRST_UDID"
else
  log "skipping build; using ${DERIVED_DATA}"
fi

APP_PATH="$(find_built_app)"
BUNDLE_ID="$(read_bundle_id "$APP_PATH")"
log "app: ${APP_PATH}"
log "bundle id: ${BUNDLE_ID}"

for index in "${!DEVICE_NAMES[@]}"; do
  name="${DEVICE_NAMES[$index]}"
  udid="${RESOLVED_UDIDS[$index]}"
  slug="${RESOLVED_SLUGS[$index]}"
  log "---- ${name} ----"
  boot_simulator "$udid"
  override_status_bar "$udid"
  run_cmd xcrun simctl install "$udid" "$APP_PATH"
  for state in "${STATE_NAMES[@]}"; do
    log "state ${state}"
    screenshot_state "$udid" "$slug" "$state"
  done
  log "transition ${TRANSITION_FIXTURE} offline -> online -> offline"
  record_transition "$udid" "$slug"
done

write_index

if [[ "$ICLOUD" == "1" ]]; then
  icloud_root="${HOME}/Library/Mobile Documents/com~apple~CloudDocs/SL/Nuvio/ui-shots/${LABEL}"
  if [[ "$DRY_RUN" == "1" ]]; then
    log "dry-run: mkdir -p ${icloud_root} && cp -R ${OUT_DIR}/. ${icloud_root}/"
  else
    mkdir -p "$icloud_root"
    cp -R "${OUT_DIR}/." "${icloud_root}/"
    log "copied to ${icloud_root}"
  fi
fi

log "done: ${OUT_DIR}"
