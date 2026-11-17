#!/usr/bin/env bash
# Manifest helpers for scripts/ui-shots.sh. Sourced, not executed.
# Keep this file compatible with macOS /bin/bash 3.2: no associative arrays,
# no ${var,,}, no mapfile, and no namerefs.

ui_manifest_trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

ui_manifest_reset() {
  UI_MANIFEST_IDS=()
  UI_MANIFEST_FIXTURES=()
  UI_MANIFEST_DEVICES=()
  UI_MANIFEST_KINDS=()
  UI_MANIFEST_GLOBS=()
  UI_MANIFEST_EXTRAS=()
  UI_SELECTED_IDS=()
  UI_SELECTED_REASONS=()
  UI_UNMATCHED=()
}

ui_manifest_fail() {
  printf '[ui-shots] error: %s\n' "$1" >&2
}

# ** crosses directories. * and ? stay inside one path segment.
ui_manifest_glob_to_ere() {
  local glob="$1"
  local ere=""
  local i=0
  local n=${#glob}
  local c two three
  while [[ $i -lt $n ]]; do
    c="${glob:$i:1}"
    two="${glob:$i:2}"
    three="${glob:$i:3}"
    if [[ "$two" == "**" ]]; then
      if [[ "$three" == "**/" ]]; then
        ere="${ere}(.*/)?"
        i=$((i + 3))
      else
        ere="${ere}.*"
        i=$((i + 2))
      fi
    elif [[ "$c" == "*" ]]; then
      ere="${ere}[^/]*"
      i=$((i + 1))
    elif [[ "$c" == "?" ]]; then
      ere="${ere}[^/]"
      i=$((i + 1))
    else
      case "$c" in
        "."|"("|")"|"|"|"+"|"^"|"$"|"["|"]"|"{"|"}"|"\\")
          ere="${ere}\\${c}"
          ;;
        *)
          ere="${ere}${c}"
          ;;
      esac
      i=$((i + 1))
    fi
  done
  printf '%s' "$ere"
}

ui_manifest_glob_match() {
  local glob="$1"
  local path="$2"
  local ere
  ere="$(ui_manifest_glob_to_ere "$glob")"
  [[ "$path" =~ ^${ere}$ ]]
}

ui_manifest_settings_wire() {
  local name="$1"
  local out=""
  local i=0
  local n=${#name}
  local c lower
  while [[ $i -lt $n ]]; do
    c="${name:$i:1}"
    if [[ $i -gt 0 && "$c" =~ [A-Z] ]]; then
      out="${out}-"
    fi
    lower="$(printf '%s' "$c" | tr '[:upper:]' '[:lower:]')"
    out="${out}${lower}"
    i=$((i + 1))
  done
  printf 'settings-%s' "$out"
}

ui_manifest_settings_pages() {
  local file="$1"
  local in_enum=0
  local line
  while IFS= read -r line; do
    if [[ "$line" == *"enum class SettingsPage"* ]]; then
      in_enum=1
      continue
    fi
    if [[ "$in_enum" == "1" && "$line" == "}" ]]; then
      break
    fi
    if [[ "$in_enum" == "1" && "$line" =~ ^[[:space:]]*([A-Z][A-Za-z0-9]*)\( ]]; then
      printf '%s\n' "${BASH_REMATCH[1]}"
    fi
  done < "$file"
}

ui_manifest_has_id() {
  local needle="$1"
  local existing
  for existing in "${UI_MANIFEST_IDS[@]+"${UI_MANIFEST_IDS[@]}"}"; do
    [[ "$existing" == "$needle" ]] && return 0
  done
  return 1
}

ui_manifest_kind_ok() {
  case "$1" in
    still|strip|shared) return 0 ;;
    *) return 1 ;;
  esac
}

ui_manifest_device_ok() {
  case "$1" in
    phone-large|phone-small|landscape) return 0 ;;
    *) return 1 ;;
  esac
}

ui_manifest_load() {
  local file="$1"
  ui_manifest_reset
  [[ -f "$file" ]] || { ui_manifest_fail "manifest not found: $file"; return 1; }
  local line lineno=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    lineno=$((lineno + 1))
    local trimmed
    trimmed="$(ui_manifest_trim "$line")"
    case "$trimmed" in
      ""|"#"*) continue ;;
    esac
    local -a cols=()
    local part
    IFS=$'\t' read -r -a cols <<EOF
$trimmed
EOF
    if [[ ${#cols[@]} -ne 6 ]]; then
      ui_manifest_fail "manifest ${file}:${lineno} needs 6 tab-separated columns, found ${#cols[@]}"
      return 1
    fi
    local id fixture devices kind globs extra
    id="$(ui_manifest_trim "${cols[0]}")"
    fixture="$(ui_manifest_trim "${cols[1]}")"
    devices="$(ui_manifest_trim "${cols[2]}")"
    kind="$(ui_manifest_trim "${cols[3]}")"
    globs="$(ui_manifest_trim "${cols[4]}")"
    extra="$(ui_manifest_trim "${cols[5]}")"
    [[ -n "$id" ]] || { ui_manifest_fail "manifest ${file}:${lineno} has an empty id"; return 1; }
    if ui_manifest_has_id "$id"; then
      ui_manifest_fail "duplicate manifest id '${id}'"
      return 1
    fi
    if ! ui_manifest_kind_ok "$kind"; then
      ui_manifest_fail "manifest id '${id}' has unknown kind '${kind}'"
      return 1
    fi
    [[ -n "$globs" && "$globs" != "-" ]] || { ui_manifest_fail "manifest id '${id}' needs globs"; return 1; }
    local token
    if [[ "$kind" == "shared" ]]; then
      [[ "$fixture" == "-" && "$devices" == "-" ]] || {
        ui_manifest_fail "shared row '${id}' must use '-' for fixture and devices"
        return 1
      }
      [[ "$extra" != "-" ]] || { ui_manifest_fail "shared row '${id}' needs a screen list"; return 1; }
    else
      [[ "$fixture" != "-" && -n "$fixture" ]] || { ui_manifest_fail "manifest id '${id}' needs a fixture"; return 1; }
      local -a device_cols=()
      IFS=',' read -r -a device_cols <<EOF
$devices
EOF
      [[ ${#device_cols[@]} -gt 0 ]] || { ui_manifest_fail "manifest id '${id}' needs devices"; return 1; }
      for token in "${device_cols[@]}"; do
        token="$(ui_manifest_trim "$token")"
        if ! ui_manifest_device_ok "$token"; then
          ui_manifest_fail "manifest id '${id}' has unknown device '${token}'"
          return 1
        fi
      done
      if [[ "$kind" == "strip" ]]; then
        case "$extra" in
          offline-banner|sheet-dismiss) ;;
          *) ui_manifest_fail "strip '${id}' needs action offline-banner or sheet-dismiss"; return 1 ;;
        esac
      else
        [[ "$extra" == "-" ]] || { ui_manifest_fail "still '${id}' must use '-' in the last column"; return 1; }
      fi
    fi
    UI_MANIFEST_IDS+=("$id")
    UI_MANIFEST_FIXTURES+=("$fixture")
    UI_MANIFEST_DEVICES+=("$devices")
    UI_MANIFEST_KINDS+=("$kind")
    UI_MANIFEST_GLOBS+=("$globs")
    UI_MANIFEST_EXTRAS+=("$extra")
  done < "$file"

  local i shared_id
  for ((i = 0; i < ${#UI_MANIFEST_IDS[@]}; i++)); do
    [[ "${UI_MANIFEST_KINDS[$i]}" == "shared" ]] || continue
    local -a selected=()
    IFS=',' read -r -a selected <<EOF
${UI_MANIFEST_EXTRAS[$i]}
EOF
    for shared_id in "${selected[@]}"; do
      shared_id="$(ui_manifest_trim "$shared_id")"
      if ! ui_manifest_has_id "$shared_id"; then
        ui_manifest_fail "shared row '${UI_MANIFEST_IDS[$i]}' lists unknown screen '${shared_id}'"
        return 1
      fi
      local kind_of=""
      local j
      for ((j = 0; j < ${#UI_MANIFEST_IDS[@]}; j++)); do
        if [[ "${UI_MANIFEST_IDS[$j]}" == "$shared_id" ]]; then
          kind_of="${UI_MANIFEST_KINDS[$j]}"
          break
        fi
      done
      if [[ "$kind_of" == "shared" ]]; then
        ui_manifest_fail "shared row '${UI_MANIFEST_IDS[$i]}' points at shared id '${shared_id}'"
        return 1
      fi
    done
  done
  [[ ${#UI_MANIFEST_IDS[@]} -gt 0 ]] || { ui_manifest_fail "manifest is empty"; return 1; }
  return 0
}

ui_manifest_selected_has() {
  local needle="$1"
  local existing
  for existing in "${UI_SELECTED_IDS[@]+"${UI_SELECTED_IDS[@]}"}"; do
    [[ "$existing" == "$needle" ]] && return 0
  done
  return 1
}

ui_manifest_add_screen() {
  local id="$1"
  local reason="$2"
  if ui_manifest_selected_has "$id"; then
    return 0
  fi
  UI_SELECTED_IDS+=("$id")
  UI_SELECTED_REASONS+=("$reason")
}

ui_manifest_matching_glob() {
  local globs="$1"
  local path="$2"
  local -a parts=()
  local part
  IFS='|' read -r -a parts <<EOF
$globs
EOF
  for part in "${parts[@]}"; do
    part="$(ui_manifest_trim "$part")"
    [[ -n "$part" ]] || continue
    if ui_manifest_glob_match "$part" "$path"; then
      printf '%s' "$part"
      return 0
    fi
  done
  return 1
}

ui_manifest_add_shared() {
  local index="$1"
  local path="$2"
  local glob="$3"
  local -a selected=()
  local shared_id
  IFS=',' read -r -a selected <<EOF
${UI_MANIFEST_EXTRAS[$index]}
EOF
  for shared_id in "${selected[@]}"; do
    shared_id="$(ui_manifest_trim "$shared_id")"
    [[ -n "$shared_id" ]] || continue
    ui_manifest_add_screen "$shared_id" "${path} matches shared ${UI_MANIFEST_IDS[$index]} (${glob})"
  done
}

# Sets UI_SELECTED_* and UI_UNMATCHED from path arguments.
ui_manifest_select_files() {
  UI_SELECTED_IDS=()
  UI_SELECTED_REASONS=()
  UI_UNMATCHED=()
  local file i glob matched
  for file in "$@"; do
    matched=0
    for ((i = 0; i < ${#UI_MANIFEST_IDS[@]}; i++)); do
      glob="$(ui_manifest_matching_glob "${UI_MANIFEST_GLOBS[$i]}" "$file" || true)"
      [[ -n "$glob" ]] || continue
      matched=1
      if [[ "${UI_MANIFEST_KINDS[$i]}" == "shared" ]]; then
        ui_manifest_add_shared "$i" "$file" "$glob"
      else
        ui_manifest_add_screen "${UI_MANIFEST_IDS[$i]}" "${file} matches ${glob}"
      fi
    done
    if [[ "$matched" == "0" ]]; then
      UI_UNMATCHED+=("$file")
    fi
  done
}

ui_manifest_select_all() {
  UI_SELECTED_IDS=()
  UI_SELECTED_REASONS=()
  UI_UNMATCHED=()
  local i
  for ((i = 0; i < ${#UI_MANIFEST_IDS[@]}; i++)); do
    [[ "${UI_MANIFEST_KINDS[$i]}" == "shared" ]] && continue
    ui_manifest_add_screen "${UI_MANIFEST_IDS[$i]}" "full pass (--all)"
  done
}

ui_manifest_print_selection() {
  local i
  for ((i = 0; i < ${#UI_SELECTED_IDS[@]}; i++)); do
    printf '[ui-shots] screen\t%s\t%s\n' "${UI_SELECTED_IDS[$i]}" "${UI_SELECTED_REASONS[$i]}"
  done
  if [[ ${#UI_UNMATCHED[@]} -gt 0 ]]; then
    local file
    for file in "${UI_UNMATCHED[@]}"; do
      printf '[ui-shots] unmatched\t%s\n' "$file"
    done
  fi
  if [[ ${#UI_SELECTED_IDS[@]} -eq 0 ]]; then
    printf '[ui-shots] no screens selected\n'
  fi
  printf '[ui-shots] selected-count:\t%s\n' "${#UI_SELECTED_IDS[@]}"
}

ui_manifest_row_index() {
  local needle="$1"
  local i
  for ((i = 0; i < ${#UI_MANIFEST_IDS[@]}; i++)); do
    if [[ "${UI_MANIFEST_IDS[$i]}" == "$needle" ]]; then
      printf '%s' "$i"
      return 0
    fi
  done
  return 1
}

ui_manifest_screen_count() {
  local i count=0
  for ((i = 0; i < ${#UI_MANIFEST_IDS[@]}; i++)); do
    [[ "${UI_MANIFEST_KINDS[$i]}" == "shared" ]] && continue
    count=$((count + 1))
  done
  printf '%s' "$count"
}

# Contact sheet of same-height tiles. Args: ffmpeg, output, inputs...
ui_manifest_write_contact() {
  local ffmpeg="$1"
  local output="$2"
  shift 2
  local -a inputs=("$@")
  local n=${#inputs[@]}
  [[ "$n" -gt 0 ]] || return 0
  mkdir -p "$(dirname "$output")"
  if [[ "$n" -eq 1 ]]; then
    "$ffmpeg" -y -hide_banner -loglevel error -i "${inputs[0]}" \
      -vf "scale=180:390:force_original_aspect_ratio=decrease,pad=180:390:(ow-iw)/2:(oh-ih)/2:color=black" \
      -frames:v 1 "$output"
    return 0
  fi
  local -a args=()
  local i filter="" stack=""
  for ((i = 0; i < n; i++)); do
    args+=(-i "${inputs[$i]}")
    filter="${filter}[${i}:v]scale=180:390:force_original_aspect_ratio=decrease,pad=180:390:(ow-iw)/2:(oh-ih)/2:color=black[s${i}];"
    stack="${stack}[s${i}]"
  done
  filter="${filter}${stack}hstack=inputs=${n}"
  "$ffmpeg" -y -hide_banner -loglevel error "${args[@]}" -filter_complex "$filter" -frames:v 1 "$output"
}

# Grid of contacts for the top-level index. Args: ffmpeg, output, columns, inputs...
ui_manifest_write_index_image() {
  local ffmpeg="$1"
  local output="$2"
  local column_count="$3"
  shift 3
  local -a inputs=("$@")
  local n=${#inputs[@]}
  [[ "$n" -gt 0 ]] || return 0
  [[ "$column_count" -ge 1 ]] || return 1
  mkdir -p "$(dirname "$output")"
  local rows=$(((n + column_count - 1) / column_count))
  local blanks=$((rows * column_count - n))
  local -a args=()
  local i r c idx filter="" row_inputs row_filter blank_i=0
  for ((i = 0; i < n; i++)); do
    args+=(-i "${inputs[$i]}")
    filter="${filter}[${i}:v]scale=160:280:force_original_aspect_ratio=decrease,pad=160:280:(ow-iw)/2:(oh-ih)/2:color=0x111111[t${i}];"
  done
  if [[ "$blanks" -gt 0 ]]; then
    filter="${filter}color=c=0x111111:s=160x280:d=1,format=rgb24,split=${blanks}"
    for ((i = 0; i < blanks; i++)); do
      filter="${filter}[b${i}]"
    done
    filter="${filter};"
  fi
  for ((r = 0; r < rows; r++)); do
    row_inputs=""
    for ((c = 0; c < column_count; c++)); do
      idx=$((r * column_count + c))
      if [[ $idx -lt $n ]]; then
        row_inputs="${row_inputs}[t${idx}]"
      else
        row_inputs="${row_inputs}[b${blank_i}]"
        blank_i=$((blank_i + 1))
      fi
    done
    filter="${filter}${row_inputs}hstack=inputs=${column_count}[row${r}];"
  done
  if [[ "$rows" -eq 1 ]]; then
    filter="${filter}[row0]copy"
  else
    row_filter=""
    for ((r = 0; r < rows; r++)); do
      row_filter="${row_filter}[row${r}]"
    done
    filter="${filter}${row_filter}vstack=inputs=${rows}"
  fi
  "$ffmpeg" -y -hide_banner -loglevel error "${args[@]}" -filter_complex "$filter" -frames:v 1 "$output"
}

ui_manifest_read_lines() {
  local file="$1"
  local line
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="$(ui_manifest_trim "$line")"
    [[ -n "$line" ]] || continue
    printf '%s\n' "$line"
  done < "$file"
}

# Uses ROOT_DIR, MANIFEST_REL, and one of UI_SHOTS_RUN_ALL / UI_SHOTS_CHANGED_REF / UI_SHOTS_CHANGED_FILES.
ui_manifest_explain() {
  ui_manifest_load "$ROOT_DIR/$MANIFEST_REL" || return 1
  if [[ -n "${UI_SHOTS_CHANGED_FILES:-}" ]]; then
    local -a files=()
    local line
    while IFS= read -r line; do
      files+=("$line")
    done <<EOF
$(ui_manifest_read_lines "$UI_SHOTS_CHANGED_FILES")
EOF
    if [[ ${#files[@]} -eq 0 ]]; then
      ui_manifest_select_files
    else
      ui_manifest_select_files "${files[@]}"
    fi
  elif [[ -n "${UI_SHOTS_CHANGED_REF:-}" ]]; then
    local diff
    diff="$(git -C "$ROOT_DIR" diff --name-only "${UI_SHOTS_CHANGED_REF}...HEAD")" || return 1
    local -a files=()
    local line
    if [[ -n "$diff" ]]; then
      while IFS= read -r line; do
        [[ -n "$line" ]] || continue
        files+=("$line")
      done <<EOF
$diff
EOF
    fi
    if [[ ${#files[@]} -eq 0 ]]; then
      ui_manifest_select_files
    else
      ui_manifest_select_files "${files[@]}"
    fi
  elif [[ "${UI_SHOTS_RUN_ALL:-0}" == "1" ]]; then
    ui_manifest_select_all
  else
    ui_manifest_fail "--explain needs --all, --changed, or --changed-files"
    return 1
  fi
  ui_manifest_print_selection
}

ui_manifest_self_test() {
  local script="$1"
  local root="$2"
  local manifest="$root/scripts/ui-manifest.tsv"
  local fixtures="$root/composeApp/src/commonMain/kotlin/com/nuvio/app/core/uifixture/UiFixtures.kt"
  local registry="$root/composeApp/src/commonMain/kotlin/com/nuvio/app/core/uifixture/UiWalkRegistry.kt"
  local settings="$root/composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsModels.kt"
  local shots="$root/scripts/ui-shots.sh"

  ui_manifest_glob_match "composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/**" \
    "composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeScreen.kt" || {
    ui_manifest_fail "glob did not match HomeScreen.kt"
    return 1
  }
  ui_manifest_glob_match "composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/**" \
    "composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/Card.kt" || {
    ui_manifest_fail "glob did not match a nested home file"
    return 1
  }
  if ui_manifest_glob_match "composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/**" \
    "composeApp/src/commonMain/kotlin/com/nuvio/app/features/homeward/X.kt"; then
    ui_manifest_fail "home glob matched homeward"
    return 1
  fi
  ui_manifest_glob_match "**/values/strings.xml" \
    "composeApp/src/commonMain/composeResources/values/strings.xml" || {
    ui_manifest_fail "strings glob missed the default strings file"
    return 1
  }
  if ui_manifest_glob_match "**/values/strings.xml" \
    "composeApp/src/commonMain/composeResources/values-de/strings.xml"; then
    ui_manifest_fail "default strings glob matched a translation"
    return 1
  fi

  [[ "$(ui_manifest_settings_wire Appearance)" == "settings-appearance" ]] || return 1
  [[ "$(ui_manifest_settings_wire TmdbEnrichment)" == "settings-tmdb-enrichment" ]] || return 1
  [[ "$(ui_manifest_settings_wire MdbListRatings)" == "settings-mdb-list-ratings" ]] || return 1
  [[ "$(ui_manifest_settings_wire WhatsNew)" == "settings-whats-new" ]] || return 1
  [[ "$(ui_manifest_settings_wire DebugLogs)" == "settings-debug-logs" ]] || return 1

  ui_manifest_load "$manifest" || return 1
  local screen_count
  screen_count="$(ui_manifest_screen_count)"

  local tmp
  tmp="$(mktemp -d)"
  local bad_err
  printf 'dup\thome-online\tphone-small\tstill\tfoo/**\t-\ndup\thome-online\tphone-small\tstill\tbar/**\t-\n' > "$tmp/bad.tsv"
  bad_err="$(ui_manifest_load "$tmp/bad.tsv" 2>&1 || true)"
  printf '%s\n' "$bad_err" | grep -q "duplicate manifest id" || {
    ui_manifest_fail "duplicate id was accepted"
    rm -rf "$tmp"
    return 1
  }
  ui_manifest_load "$manifest" || { rm -rf "$tmp"; return 1; }

  local -a enum_wires=()
  local wire
  while IFS= read -r wire; do
    [[ "$wire" == "walk" ]] && continue
    enum_wires+=("$wire")
  done <<EOF
$(grep -E '^[[:space:]]*[A-Za-z][A-Za-z0-9]*\("[a-z0-9-]+"' "$fixtures" | sed -n 's/.*("\([a-z0-9-]*\)".*/\1/p')
EOF
  [[ ${#enum_wires[@]} -gt 0 ]] || { ui_manifest_fail "no enum fixtures found"; rm -rf "$tmp"; return 1; }

  local -a walk_wires=()
  while IFS= read -r wire; do
    [[ -n "$wire" ]] || continue
    walk_wires+=("$wire")
  done <<EOF
$(grep -oE 'WalkScreen\("[a-z0-9-]+"' "$registry" | sed -n 's/WalkScreen("\([a-z0-9-]*\)".*/\1/p')
EOF
  [[ ${#walk_wires[@]} -gt 0 ]] || { ui_manifest_fail "no walk fixtures found"; rm -rf "$tmp"; return 1; }

  local -a settings_wires=()
  local page
  while IFS= read -r page; do
    [[ -n "$page" ]] || continue
    settings_wires+=("$(ui_manifest_settings_wire "$page")")
  done <<EOF
$(ui_manifest_settings_pages "$settings")
EOF
  [[ ${#settings_wires[@]} -gt 0 ]] || { ui_manifest_fail "no settings pages found"; rm -rf "$tmp"; return 1; }

  local fixture known base
  local i
  for ((i = 0; i < ${#UI_MANIFEST_IDS[@]}; i++)); do
    [[ "${UI_MANIFEST_KINDS[$i]}" == "shared" ]] && continue
    fixture="${UI_MANIFEST_FIXTURES[$i]}"
    known=0
    base="${fixture%%@*}"
    if [[ "$fixture" == *"@"* ]]; then
      case "$base" in
        player-submit-bar|player-submit-bar-long) ;;
        *) ui_manifest_fail "width suffix on '${fixture}' is not a submit-bar fixture"; rm -rf "$tmp"; return 1 ;;
      esac
      [[ "$fixture" =~ ^[a-z0-9-]+@[0-9]+$ ]] || { ui_manifest_fail "bad width suffix '${fixture}'"; rm -rf "$tmp"; return 1; }
      fixture="$base"
    fi
    for wire in "${enum_wires[@]}" "${walk_wires[@]}" "${settings_wires[@]}"; do
      if [[ "$wire" == "$fixture" ]]; then
        known=1
        break
      fi
    done
    if [[ "$known" == "0" ]]; then
      ui_manifest_fail "manifest screen '${UI_MANIFEST_IDS[$i]}' has no fixture '${UI_MANIFEST_FIXTURES[$i]}'"
      rm -rf "$tmp"
      return 1
    fi
  done

  local manifest_fixtures=""
  for ((i = 0; i < ${#UI_MANIFEST_IDS[@]}; i++)); do
    [[ "${UI_MANIFEST_KINDS[$i]}" == "shared" ]] && continue
    manifest_fixtures="${manifest_fixtures} ${UI_MANIFEST_FIXTURES[$i]} "
  done
  for wire in "${enum_wires[@]}" "${walk_wires[@]}" "${settings_wires[@]}"; do
    case "$manifest_fixtures" in
      *" ${wire} "*) ;;
      *) ui_manifest_fail "fixture '${wire}' is missing from the manifest"; rm -rf "$tmp"; return 1 ;;
    esac
  done

  grep -q 'nuvio-ui-action.txt' "$shots" || { ui_manifest_fail "script is missing the action file name"; rm -rf "$tmp"; return 1; }
  grep -q 'nuvio-ui-action.txt' "$fixtures" || { ui_manifest_fail "fixtures are missing the action file name"; rm -rf "$tmp"; return 1; }
  grep -q -- '-NuvioUiFixture' "$shots" || { ui_manifest_fail "launch argument was removed from the script"; rm -rf "$tmp"; return 1; }
  grep -q -- '-NuvioDiagnosticsIntroSeen' "$shots" || { ui_manifest_fail "diagnostics launch argument was removed"; rm -rf "$tmp"; return 1; }

  local home_list="$tmp/home.txt"
  local none_list="$tmp/none.txt"
  local strings_list="$tmp/strings.txt"
  local theme_list="$tmp/theme.txt"
  printf '%s\n' "composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeScreen.kt" > "$home_list"
  printf '%s\n' "notes/not-a-screen.txt" > "$none_list"
  printf '%s\n' "composeApp/src/commonMain/composeResources/values/strings.xml" > "$strings_list"
  printf '%s\n' "composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/Theme.kt" > "$theme_list"

  local out actual expected
  out="$("$script" --changed-files "$home_list" --explain)" || { rm -rf "$tmp"; return 1; }
  actual="$(printf '%s\n' "$out" | awk -F '\t' '$1 == "[ui-shots] screen" { print $2 }' | sort)"
  expected="$(printf '%s\n' home-offline home-online long-titles offline-banner-strip | sort)"
  if [[ "$actual" != "$expected" ]]; then
    ui_manifest_fail "HomeScreen.kt selected:
${actual}
expected:
${expected}"
    rm -rf "$tmp"
    return 1
  fi

  out="$("$script" --changed-files "$none_list" --explain)" || { rm -rf "$tmp"; return 1; }
  printf '%s\n' "$out" | grep -q 'unmatched	notes/not-a-screen.txt' || {
    ui_manifest_fail "unmatched file was not reported"
    rm -rf "$tmp"
    return 1
  }
  printf '%s\n' "$out" | grep -q "no screens selected" || {
    ui_manifest_fail "unmatched file did not say that no screen was selected"
    rm -rf "$tmp"
    return 1
  }
  actual="$(printf '%s\n' "$out" | awk -F '\t' '$1 == "[ui-shots] screen" { print $2 }')"
  if [[ -n "$actual" ]]; then
    ui_manifest_fail "unmatched file selected screens: ${actual}"
    rm -rf "$tmp"
    return 1
  fi

  out="$("$script" --changed-files "$strings_list" --explain)" || { rm -rf "$tmp"; return 1; }
  actual="$(printf '%s\n' "$out" | awk -F '\t' '$1 == "[ui-shots] screen" { print $2 }' | sort)"
  expected="$(printf '%s\n' details-online downloads home-online player-submit-bar search settings-root | sort)"
  if [[ "$actual" != "$expected" ]]; then
    ui_manifest_fail "strings.xml selected:
${actual}
expected:
${expected}"
    rm -rf "$tmp"
    return 1
  fi
  if [[ "$(printf '%s\n' "$actual" | grep -c . || true)" -ge "$screen_count" ]]; then
    ui_manifest_fail "strings.xml selected every screen"
    rm -rf "$tmp"
    return 1
  fi

  out="$("$script" --changed-files "$theme_list" --explain)" || { rm -rf "$tmp"; return 1; }
  actual="$(printf '%s\n' "$out" | awk -F '\t' '$1 == "[ui-shots] screen" { print $2 }' | sort)"
  expected="$(printf '%s\n' details-online home-online library player-submit-bar settings-appearance | sort)"
  if [[ "$actual" != "$expected" ]]; then
    ui_manifest_fail "Theme.kt selected:
${actual}
expected:
${expected}"
    rm -rf "$tmp"
    return 1
  fi

  if command -v ffmpeg >/dev/null 2>&1; then
    local red="$tmp/red.png" blue="$tmp/blue.png" contact="$tmp/contact.png" index="$tmp/index.png"
    ffmpeg -y -hide_banner -loglevel error -f lavfi -i "color=c=red:s=90x160" -frames:v 1 "$red"
    ffmpeg -y -hide_banner -loglevel error -f lavfi -i "color=c=blue:s=80x140" -frames:v 1 "$blue"
    ui_manifest_write_contact "$(command -v ffmpeg)" "$contact" "$red" "$blue" || { rm -rf "$tmp"; return 1; }
    ui_manifest_write_index_image "$(command -v ffmpeg)" "$index" 8 "$contact" "$red" "$blue" || { rm -rf "$tmp"; return 1; }
    [[ -s "$contact" && -s "$index" ]] || { ui_manifest_fail "contact sheet was empty"; rm -rf "$tmp"; return 1; }
  fi

  rm -rf "$tmp"
  printf '[ui-shots] self-test ok, screens=%s\n' "$screen_count"
  return 0
}
