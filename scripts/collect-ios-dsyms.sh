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
            *ComposeApp*.dSYM) kotlin=$((kotlin + 1)) ;;
        esac
    done < <(find "${root}" -type d \( -name '*.app.dSYM' -o -name '*.appex.dSYM' -o -name '*ComposeApp*.dSYM' \) -print)
done

# A static ComposeApp.framework has no separate dSYM until dsymutil runs on its binary.
if [[ "${kotlin}" -lt 1 ]]; then
    binary=""
    for root in "$@"; do
        if [[ ! -d "${root}" ]]; then
            continue
        fi
        binary="$(find "${root}" -type f -path '*/ComposeApp.framework/ComposeApp' -print | head -n 1 || true)"
        if [[ -n "${binary}" ]]; then
            break
        fi
    done
    if [[ -n "${binary}" ]]; then
        echo "ComposeApp binary: ${binary}"
        file_desc="$(file "${binary}" || true)"
        echo "${file_desc}"
        if [[ "${file_desc}" == *"Mach-O"* ]]; then
            generated="${dest}/ComposeApp.framework.dSYM"
            if dsymutil "${binary}" -o "${generated}" && [[ -d "${generated}" ]]; then
                kotlin=$((kotlin + 1))
                count=$((count + 1))
                echo "Generated ${generated}"
            else
                echo "dsymutil did not produce a ComposeApp dSYM." >&2
            fi
        else
            echo "ComposeApp.framework is a static archive, so it has no dSYM of its own."
        fi
    else
        echo "No ComposeApp.framework/ComposeApp binary was found." >&2
    fi
fi

# Static frameworks are linked into the app. Their DWARF is in the app dSYM.
if [[ "${kotlin}" -lt 1 && "${app}" -ge 1 ]]; then
    dwarf="$(find "${dest}" -path '*.app.dSYM/Contents/Resources/DWARF/*' -type f -print | head -n 1 || true)"
    if [[ -n "${dwarf}" ]] && grep -a -m 1 -q 'kfun:' "${dwarf}"; then
        kotlin=$((kotlin + 1))
        printf '%s\n' \
            "ComposeApp.framework is a static archive linked into the app." \
            "Kotlin frames (kfun:) are in the app dSYM." \
            "atos -arch arm64 -o <App>.dSYM/Contents/Resources/DWARF/<App> -l <load-address> <address>" \
            > "${dest}/kotlin-symbols.txt"
        echo "App dSYM contains Kotlin function names."
    fi
fi

echo "Collected ${count} dSYM bundles (app=${app} kotlin=${kotlin}) into ${dest}"
if [[ "${REQUIRE_APP_DSYM:-0}" == "1" && "${app}" -lt 1 ]]; then
    echo "No app dSYM was produced. atos cannot symbolicate the app binary." >&2
    exit 1
fi
if [[ "${REQUIRE_KOTLIN_DSYM:-0}" == "1" && "${kotlin}" -lt 1 ]]; then
    echo "No ComposeApp.framework.dSYM was produced. atos cannot symbolicate Kotlin frames." >&2
    exit 1
fi
