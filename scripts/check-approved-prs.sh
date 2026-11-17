#!/usr/bin/env bash
# Fail unless every approved pull request is in HEAD.
# The list is docs/release/approved-prs.txt. A pull request passes when its
# recorded commit is an ancestor of HEAD, or when a squash commit in history
# is recorded as including it. That squash commit's subject must end with (#N).
# The squash list is docs/release/approved-pr-squashes.txt.
# Release IPA builds run this first.

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
manifest="${1:-${repository_root}/docs/release/approved-prs.txt}"
squashes="${2:-${repository_root}/docs/release/approved-pr-squashes.txt}"

if [[ ! -f "${manifest}" ]]; then
    echo "Approved PR manifest is missing: ${manifest}" >&2
    exit 1
fi

cd "${repository_root}"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "Approved PR check needs a git checkout." >&2
    exit 1
fi

trim_line() {
    local line="$1"
    line="${line#"${line%%[![:space:]]*}"}"
    printf '%s' "${line}"
}

# Returns 0 and prints the squash sha when one in history includes the PR.
# A record counts only when the commit is an ancestor of HEAD and the subject
# ends with (#N). Format errors return 2.
squash_including() {
    local pr="$1"
    local line trimmed squash rest token included subject
    [[ -f "${squashes}" ]] || return 1
    while IFS= read -r line || [[ -n "${line}" ]]; do
        trimmed="$(trim_line "${line}")"
        [[ -z "${trimmed}" || "${trimmed}" == \#* ]] && continue
        squash="${trimmed%%[[:space:]]*}"
        rest="${trimmed#"${squash}"}"
        rest="$(trim_line "${rest}")"
        if [[ ! "${squash}" =~ ^[0-9a-fA-F]{40}$ || -z "${rest}" ]]; then
            echo "Squash line must be '<40-character sha> <pull request numbers>': ${trimmed}" >&2
            return 2
        fi
        included=0
        for token in ${rest}; do
            if [[ ! "${token}" =~ ^[0-9]+$ ]]; then
                echo "Squash line has a non-numeric pull request: ${trimmed}" >&2
                return 2
            fi
            if [[ "${token}" == "${pr}" ]]; then
                included=1
            fi
        done
        [[ "${included}" -eq 1 ]] || continue
        if ! git cat-file -e "${squash}^{commit}" 2>/dev/null; then
            continue
        fi
        if ! git merge-base --is-ancestor "${squash}" HEAD; then
            continue
        fi
        subject="$(git log -1 --format=%s "${squash}")"
        if [[ "${subject}" =~ \(#[0-9]+\)$ ]]; then
            printf '%s' "${squash}"
            return 0
        fi
    done < "${squashes}"
    return 1
}

failures=0
checked=0
while IFS= read -r line || [[ -n "${line}" ]]; do
    trimmed="$(trim_line "${line}")"
    [[ -z "${trimmed}" || "${trimmed}" == \#* ]] && continue
    pr="${trimmed%%[[:space:]]*}"
    sha="${trimmed#"${pr}"}"
    sha="$(trim_line "${sha}")"
    sha="${sha%%[[:space:]]*}"
    if [[ ! "${pr}" =~ ^[0-9]+$ || ! "${sha}" =~ ^[0-9a-fA-F]{40}$ ]]; then
        echo "Approved PR line must be '<number> <40-character sha>': ${trimmed}" >&2
        failures=$((failures + 1))
        continue
    fi
    checked=$((checked + 1))
    if git cat-file -e "${sha}^{commit}" 2>/dev/null && git merge-base --is-ancestor "${sha}" HEAD; then
        echo "PR #${pr} ${sha} is an ancestor of HEAD"
        continue
    fi
    set +e
    squash_sha="$(squash_including "${pr}")"
    squash_status=$?
    set -e
    if [[ "${squash_status}" -eq 2 ]]; then
        failures=$((failures + 1))
        continue
    fi
    if [[ "${squash_status}" -eq 0 && -n "${squash_sha}" ]]; then
        echo "PR #${pr} is included in squash ${squash_sha} ($(git log -1 --format=%s "${squash_sha}"))"
        continue
    fi
    echo "PR #${pr} ${sha} is not an ancestor of HEAD ($(git rev-parse --short HEAD)) and no squash commit in history is recorded as including it." >&2
    failures=$((failures + 1))
done < "${manifest}"

if [[ "${checked}" -eq 0 ]]; then
    echo "Approved PR manifest has no entries: ${manifest}" >&2
    exit 1
fi

if [[ "${failures}" -ne 0 ]]; then
    echo "Approved PR check failed (${failures})." >&2
    exit 1
fi

echo "Approved PR check passed (${checked} pull requests)."
