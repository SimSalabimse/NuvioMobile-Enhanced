#!/usr/bin/env bash
# Copy app, extension, and ComposeApp.framework dSYM bundles into one directory
# so a CI artifact can symbolicate a later crash or CPU report with atos.
set -euo pipefail

dest="${1:?destination directory}"
shift
mkdir -p "${dest}"

count=0
app=0
kotlin=0
seen_file="$(mktemp)"
trap 'rm -f "${seen_file}"' EXIT

for root in "$@"; do
    if [[ ! -d "${root}" ]]; then
        continue
    fi
    while IFS= read -r path; do
        # find can print the same bundle twice when a search root contains another.
        if grep -Fxq "${path}" "${seen_file}"; then
            continue
        fi
        printf '%s\n' "${path}" >> "${seen_file}"
        base="$(basename "${path}")"
        target="${dest}/${base}"
        if [[ -e "${target}" ]]; then
            target="${dest}/${count}-${base}"
        fi
        ditto "${path}" "${target}"
        count=$((count + 1))
        case "${base}" in
            *.app.dSYM) app=$((app + 1)) ;;
            ComposeApp.framework.dSYM) kotlin=$((kotlin + 1)) ;;
        esac
    done < <(find "${root}" -type d \( -name '*.app.dSYM' -o -name '*.appex.dSYM' -o -name 'ComposeApp.framework.dSYM' \) -print)
done

echo "Collected ${count} dSYM bundles (app=${app} kotlin=${kotlin}) into ${dest}"
if [[ "${REQUIRE_APP_DSYM:-0}" == "1" && "${app}" -lt 1 ]]; then
    echo "No app dSYM was produced. atos cannot symbolicate the app binary." >&2
    exit 1
fi
if [[ "${REQUIRE_KOTLIN_DSYM:-0}" == "1" && "${kotlin}" -lt 1 ]]; then
    echo "No ComposeApp.framework.dSYM was produced. atos cannot symbolicate Kotlin frames." >&2
    exit 1
fi
