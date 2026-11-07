#!/usr/bin/env bash
# Drive the iOS Simulator and save PNG screenshots plus offline/online transition strips.
#
# Nuvio-specific values are the variables below. Another app (InkList) can reuse this
# script by changing them and keeping the same -NuvioUiFixture-style launch argument.
#
# This machine check does not compile or boot a simulator. On a Mac, run:
#   ./scripts/ui-shots.sh --dry-run
#   ./scripts/ui-shots.sh
#   ./scripts/ui-shots.sh --all
#   ./scripts/ui-shots.sh --changed integration/batch9
#   ./scripts/ui-shots.sh --self-test
#
# --all and --changed read scripts/ui-manifest.tsv (the list of screens).
# The legacy invocation above still shoots ALL_STATES and the long transition.
# A full or changed pass builds once, boots each simulator once, and stops if
# it exceeds NUVIO_UI_SHOTS_BUDGET_SECONDS (default 1800, including the build).
set -euo pipefail

# --- app-specific (change these to reuse the script) ---
SCHEME="iosApp"
PROJECT_REL="iosApp/iosApp.xcodeproj"
CONFIGURATION="Debug"
IOS_DISTRIBUTION="${NUVIO_IOS_DISTRIBUTION:-full}"
BUNDLE_ID="com.nuvio.app"
FIXTURE_ARG="-NuvioUiFixture"
CONNECTIVITY_FILE="nuvio-ui-connectivity.txt"
# Extra launch arguments. "-Key value" lands in the UserDefaults argument domain for this
# launch only, so the first-run diagnostics sheet does not cover the screenshot.
EXTRA_LAUNCH_ARGS=(-NuvioDiagnosticsIntroSeen YES)
ACTION_FILE="nuvio-ui-action.txt"
MANIFEST_REL="scripts/ui-manifest.tsv"
# Device tokens used by the manifest. Point these at the simulators you have.
DEVICE_TOKEN_LARGE="iPhone 18 Pro"
DEVICE_TOKEN_SMALL="iPhone 17e"
DEVICE_TOKEN_LANDSCAPE="iPhone 18 Pro"
# Whole run, including the single build. The simulator pass is the rest.
BUDGET_SECONDS="${NUVIO_UI_SHOTS_BUDGET_SECONDS:-1800}"
STRIP_LEAD_SECONDS="0.3"
STRIP_WINDOW_SECONDS="0.6"
# Keep ALL_STATES in the same order as UiFixture.wireName.
# @dp suffixes are the same fixture at a fixed width. They are listed because
# known_state matches the launch string exactly.
ALL_STATES="home-online,home-offline,home-tall,details-online,details-offline,details-movie-offline,details-tall,downloads,downloads-season,long-titles,smart-downloads,player-submit-bar,player-submit-bar@667,player-submit-bar@844,player-submit-bar@932,player-submit-bar-long,player-submit-bar-fresh,player-submit-bar-touched-invalid,season-overview,season-overview-long,movie-segment-rows"
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
RUN_ALL=0
CHANGED_REF=""
CHANGED_FILES=""
SELF_TEST=0
EXPLAIN=0
NO_ICLOUD=0
UI_SHOTS_RUN_ALL=0
UI_SHOTS_CHANGED_REF=""
UI_SHOTS_CHANGED_FILES=""

log() { printf '[ui-shots] %s\n' "$*"; }
die() { printf '[ui-shots] error: %s\n' "$*" >&2; exit 1; }

on_error() {
  printf '[ui-shots] error: command failed at line %s: %s\n' "$1" "$2" >&2
  exit 1
}
trap 'on_error "$LINENO" "$BASH_COMMAND"' ERR

# shellcheck source=scripts/ui-manifest.sh
source "$ROOT_DIR/scripts/ui-manifest.sh"

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
  --all                                  Shoot every screen in ${MANIFEST_REL}
  --changed BASE                         Shoot screens whose globs match git diff BASE...HEAD
  --changed-files FILE                   Same match, from a newline list of paths
  --explain                              Print the selected screens and why, then exit 0
  --self-test                            Check the manifest matcher without a simulator
  --no-icloud                            Do not copy a full or changed pass to iCloud
  -h, --help

Without --all or --changed, the legacy pass runs. Transitions are recorded for
every device after the stills: ${TRANSITION_FIXTURE} goes offline -> online at
${TRANSITION_ONLINE_SECONDS}s -> offline at ${TRANSITION_OFFLINE_SECONDS}s.

--all and --changed build once, boot each simulator once, write contact sheets,
and copy to iCloud Drive under SL/Nuvio/ui-shots/<label>/ unless --no-icloud is
set. They stop if the run exceeds ${BUDGET_SECONDS}s (NUVIO_UI_SHOTS_BUDGET_SECONDS).
Strip rows record about ${STRIP_WINDOW_SECONDS}s of frames.
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
    --all) RUN_ALL=1; UI_SHOTS_RUN_ALL=1; shift ;;
    --changed)
      [[ $# -ge 2 ]] || die "--changed needs a base ref"
      CHANGED_REF="$2"
      UI_SHOTS_CHANGED_REF="$2"
      shift 2
      ;;
    --changed-files)
      [[ $# -ge 2 ]] || die "--changed-files needs a file"
      CHANGED_FILES="$2"
      UI_SHOTS_CHANGED_FILES="$2"
      shift 2
      ;;
    --explain) EXPLAIN=1; shift ;;
    --self-test) SELF_TEST=1; shift ;;
    --no-icloud) NO_ICLOUD=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1 (try --help)" ;;
  esac
done

mode_count=0
[[ "$RUN_ALL" == "1" ]] && mode_count=$((mode_count + 1))
[[ -n "$CHANGED_REF" ]] && mode_count=$((mode_count + 1))
[[ -n "$CHANGED_FILES" ]] && mode_count=$((mode_count + 1))
if [[ "$mode_count" -gt 1 ]]; then
  die "Pass only one of --all, --changed, or --changed-files"
fi
if [[ "$SELF_TEST" == "1" ]]; then
  ui_manifest_self_test "$ROOT_DIR/scripts/ui-shots.sh" "$ROOT_DIR"
  exit $?
fi
if [[ "$EXPLAIN" == "1" ]]; then
  ui_manifest_explain
  exit $?
fi

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

write_action() {
  local udid="$1"
  local word="$2"
  if [[ "$DRY_RUN" == "1" ]]; then
    log "dry-run: write '${word}' to <app data>/Documents/${ACTION_FILE} on ${udid}"
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
  printf '%s\n' "$word" > "${docs}/${ACTION_FILE}"
  log "wrote ${word} -> ${docs}/${ACTION_FILE}"
}

launch_fixture() {
  local udid="$1"
  local state="$2"
  if [[ "$DRY_RUN" == "0" ]]; then
    xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
  else
    log "dry-run: xcrun simctl terminate ${udid} ${BUNDLE_ID} || true"
  fi
  run_cmd xcrun simctl launch "$udid" "$BUNDLE_ID" "$FIXTURE_ARG" "$state" "${EXTRA_LAUNCH_ARGS[@]}"
}

screenshot_state() {
  local udid="$1"
  local slug="$2"
  local state="$3"
  local shot_name="${4:-$state}"
  local destination="$OUT_DIR/$slug/${shot_name}.png"
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

device_token_name() {
  case "$1" in
    phone-large) printf '%s' "$DEVICE_TOKEN_LARGE" ;;
    phone-small) printf '%s' "$DEVICE_TOKEN_SMALL" ;;
    landscape) printf '%s' "$DEVICE_TOKEN_LANDSCAPE" ;;
    *) die "Unknown device token: $1" ;;
  esac
}

device_token_orientation() {
  case "$1" in
    landscape) printf '%s' "landscapeLeft" ;;
    *) printf '%s' "portrait" ;;
  esac
}

set_orientation() {
  local udid="$1"
  local orientation="$2"
  # simctl ui has no orientation option on current Xcode; landscape fixtures rotate in-app.
  if ! run_cmd xcrun simctl ui "$udid" orientation "$orientation" >/dev/null 2>&1; then
    log "orientation $orientation not supported by simctl; continuing"
  fi
}

check_budget() {
  [[ "$DRY_RUN" == "1" ]] && return 0
  local now elapsed
  now="$(date +%s)"
  elapsed=$((now - RUN_STARTED))
  if [[ "$elapsed" -gt "$BUDGET_SECONDS" ]]; then
    die "runtime cap ${BUDGET_SECONDS}s exceeded after ${elapsed}s. Raise NUVIO_UI_SHOTS_BUDGET_SECONDS."
  fi
}

record_strip() {
  local udid="$1"
  local token="$2"
  local screen_id="$3"
  local fixture="$4"
  local action="$5"
  local mp4="$OUT_DIR/$token/${screen_id}.mp4"
  local sheet="$OUT_DIR/strips/${screen_id}--${token}.png"
  if [[ "$DRY_RUN" == "0" ]]; then
    mkdir -p "$OUT_DIR/$token" "$OUT_DIR/strips"
  fi
  launch_fixture "$udid" "$fixture"
  if [[ "$action" == "offline-banner" ]]; then
    write_connectivity "$udid" "offline"
  fi
  if [[ "$DRY_RUN" == "1" ]]; then
    log "dry-run: sleep ${SETTLE_SECONDS} (settle before strip)"
    log "dry-run: record ${STRIP_LEAD_SECONDS}s, trigger ${action}, keep ${STRIP_WINDOW_SECONDS}s"
    run_cmd "$FFMPEG_BIN" -y -hide_banner -loglevel error -ss 0.35 -t "$STRIP_WINDOW_SECONDS" -i "$mp4" \
      -vf "fps=10,scale=160:-1,tile=6x1" -frames:v 1 "$sheet"
    return 0
  fi
  sleep "$SETTLE_SECONDS"
  log "recording strip $mp4"
  xcrun simctl io "$udid" recordVideo --codec h264 "$mp4" &
  local rec_pid=$!
  sleep 0.4
  if ! kill -0 "$rec_pid" 2>/dev/null; then
    die "simctl recordVideo exited before the strip trigger. Check ${mp4}."
  fi
  printf '%s\n' "xcrun simctl io ${udid} recordVideo --codec h264 ${mp4}" >> "$COMMAND_LOG"
  if [[ "$action" == "offline-banner" ]]; then
    write_connectivity "$udid" "online"
  else
    write_action "$udid" "dismiss"
  fi
  sleep 0.9
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
  run_cmd "$FFMPEG_BIN" -y -hide_banner -loglevel error -ss 0.35 -t "$STRIP_WINDOW_SECONDS" -i "$mp4" \
    -vf "fps=10,scale=160:-1,tile=6x1" -frames:v 1 "$sheet"
  rm -f "$mp4"
  xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
}

write_manifest_index() {
  [[ "$DRY_RUN" == "1" ]] && return 0
  local index="$OUT_DIR/index.md"
  {
    printf '# UI shots\n\n'
    printf -- '- scheme: %s\n' "\`$SCHEME\`"
    printf -- '- manifest: %s\n' "\`$MANIFEST_REL\`"
    printf -- '- screens: %s\n' "$(ui_manifest_screen_count)"
    printf -- '- selected: %s\n' "${#UI_SELECTED_IDS[@]}"
    printf -- '- budget seconds: %s\n' "$BUDGET_SECONDS"
    printf -- '- label: %s\n\n' "\`$LABEL\`"
    printf '## Screens\n\n'
    local i
    for ((i = 0; i < ${#UI_SELECTED_IDS[@]}; i++)); do
      printf -- '- %s — %s\n' "\`${UI_SELECTED_IDS[$i]}\`" "${UI_SELECTED_REASONS[$i]}"
    done
    if [[ ${#UI_UNMATCHED[@]} -gt 0 ]]; then
      printf '\n## Unmatched\n\n'
      local unmatched
      for unmatched in "${UI_UNMATCHED[@]}"; do
        printf -- '- %s\n' "\`$unmatched\`"
      done
    fi
    printf '\n## Files\n\n'
    local file relative
    while IFS= read -r file; do
      relative="${file#"$OUT_DIR"/}"
      printf -- '- %s\n' "\`$relative\`"
    done < <(find "$OUT_DIR" -type f ! -name 'index.md' ! -name 'commands.txt' | sort)
  } > "$index"
}

run_manifest_pass() {
  ui_manifest_explain || die "could not select screens"
  if [[ ${#UI_SELECTED_IDS[@]} -eq 0 ]]; then
    log "no screens selected"
    return 0
  fi
  if [[ "$NO_ICLOUD" == "0" ]]; then
    ICLOUD=1
  fi
  RUN_STARTED="$(date +%s)"
  check_budget

  UNIQUE_NAMES=()
  UNIQUE_UDIDS=()
  local s token name udid existing found
  for s in "${UI_SELECTED_IDS[@]}"; do
    local row
    row="$(ui_manifest_row_index "$s")" || die "selected screen '${s}' is not in the manifest"
    local -a tokens=()
    IFS=',' read -r -a tokens <<EOF
${UI_MANIFEST_DEVICES[$row]}
EOF
    for token in "${tokens[@]}"; do
      token="$(ui_manifest_trim "$token")"
      name="$(device_token_name "$token")"
      found=0
      for existing in "${UNIQUE_NAMES[@]+"${UNIQUE_NAMES[@]}"}"; do
        if [[ "$existing" == "$name" ]]; then
          found=1
          break
        fi
      done
      if [[ "$found" == "0" ]]; then
        UNIQUE_NAMES+=("$name")
      fi
    done
  done

  local first_udid=""
  for name in "${UNIQUE_NAMES[@]}"; do
    udid="$(resolve_udid "$name")"
    UNIQUE_UDIDS+=("$udid")
    log "device ${name} -> ${udid}"
    if [[ -z "$first_udid" ]]; then
      first_udid="$udid"
    fi
  done

  if [[ "$SKIP_BUILD" == "0" ]]; then
    build_app "$first_udid"
  else
    log "skipping build; using ${DERIVED_DATA}"
  fi
  APP_PATH="$(find_built_app)"
  BUNDLE_ID="$(read_bundle_id "$APP_PATH")"
  log "app: ${APP_PATH}"
  log "bundle id: ${BUNDLE_ID}"

  local i
  for ((i = 0; i < ${#UNIQUE_NAMES[@]}; i++)); do
    name="${UNIQUE_NAMES[$i]}"
    udid="${UNIQUE_UDIDS[$i]}"
    log "---- boot ${name} ----"
    boot_simulator "$udid"
    override_status_bar "$udid"
    run_cmd xcrun simctl install "$udid" "$APP_PATH"
  done

  for s in "${UI_SELECTED_IDS[@]}"; do
    check_budget
    row="$(ui_manifest_row_index "$s")" || die "selected screen '${s}' disappeared"
    local fixture="${UI_MANIFEST_FIXTURES[$row]}"
    local kind="${UI_MANIFEST_KINDS[$row]}"
    local extra="${UI_MANIFEST_EXTRAS[$row]}"
    local -a shot_tokens=()
    IFS=',' read -r -a shot_tokens <<EOF
${UI_MANIFEST_DEVICES[$row]}
EOF
    log "screen ${s} (${fixture}, ${kind})"
    for token in "${shot_tokens[@]}"; do
      token="$(ui_manifest_trim "$token")"
      name="$(device_token_name "$token")"
      udid=""
      for ((i = 0; i < ${#UNIQUE_NAMES[@]}; i++)); do
        if [[ "${UNIQUE_NAMES[$i]}" == "$name" ]]; then
          udid="${UNIQUE_UDIDS[$i]}"
          break
        fi
      done
      [[ -n "$udid" ]] || die "no simulator for ${name}"
      set_orientation "$udid" "$(device_token_orientation "$token")"
      override_status_bar "$udid"
      if [[ "$kind" == "strip" ]]; then
        record_strip "$udid" "$token" "$s" "$fixture" "$extra"
      else
        screenshot_state "$udid" "$token" "$fixture" "$s"
      fi
    done
  done

  local contact_dir="$OUT_DIR/contacts"
  local -a contact_files=()
  for s in "${UI_SELECTED_IDS[@]}"; do
    row="$(ui_manifest_row_index "$s")" || continue
    local -a pieces=()
    IFS=',' read -r -a pieces <<EOF
${UI_MANIFEST_DEVICES[$row]}
EOF
    local -a shots=()
    for token in "${pieces[@]}"; do
      token="$(ui_manifest_trim "$token")"
      if [[ "${UI_MANIFEST_KINDS[$row]}" == "strip" ]]; then
        shots+=("$OUT_DIR/strips/${s}--${token}.png")
      else
        shots+=("$OUT_DIR/${token}/${s}.png")
      fi
    done
    local contact="$contact_dir/${s}.png"
    if [[ "$DRY_RUN" == "1" ]]; then
      log "dry-run: contact ${contact}"
    else
      ui_manifest_write_contact "$FFMPEG_BIN" "$contact" "${shots[@]}"
      contact_files+=("$contact")
    fi
  done
  if [[ "$DRY_RUN" == "1" ]]; then
    log "dry-run: index image ${OUT_DIR}/index.png"
  elif [[ ${#contact_files[@]} -gt 0 ]]; then
    ui_manifest_write_index_image "$FFMPEG_BIN" "$OUT_DIR/index.png" 8 "${contact_files[@]}"
  fi
  write_manifest_index

  if [[ "$DRY_RUN" == "0" ]]; then
    find "$OUT_DIR" -name '*.mp4' -delete
    local default_derived="$ROOT_DIR/build/ios-ui-shots-derived"
    if [[ "$SKIP_BUILD" == "0" && "$DERIVED_DATA" == "$default_derived" ]]; then
      rm -rf "$DERIVED_DATA"
      log "removed derived data ${DERIVED_DATA}"
    fi
  fi

  if [[ "$ICLOUD" == "1" ]]; then
    local icloud_root="${HOME}/Library/Mobile Documents/com~apple~CloudDocs/SL/Nuvio/ui-shots/${LABEL}"
    if [[ "$DRY_RUN" == "1" ]]; then
      log "dry-run: mkdir -p ${icloud_root} && cp -R ${OUT_DIR}/. ${icloud_root}/"
    else
      mkdir -p "$icloud_root"
      cp -R "${OUT_DIR}/." "${icloud_root}/"
      log "copied to ${icloud_root}"
    fi
  fi
  local finished=0
  if [[ "$DRY_RUN" == "0" ]]; then
    finished=$(( $(date +%s) - RUN_STARTED ))
    log "elapsed seconds: ${finished} (cap ${BUDGET_SECONDS})"
  fi
  log "done: ${OUT_DIR}"
}

if [[ "$DRY_RUN" == "0" ]]; then
  mkdir -p "$OUT_DIR"
  COMMAND_LOG="$OUT_DIR/commands.txt"
  : > "$COMMAND_LOG"
fi

log "output: $OUT_DIR"
if [[ "$RUN_ALL" == "1" || -n "$CHANGED_REF" || -n "$CHANGED_FILES" ]]; then
  run_manifest_pass
  exit 0
fi
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
