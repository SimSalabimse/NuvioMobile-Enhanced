#!/usr/bin/env bash
# Fail when an approved pull request's head commit is not an ancestor of HEAD.
# The list is docs/release/approved-prs.txt. Release IPA builds run this first.

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
manifest="${1:-${repository_root}/docs/release/approved-prs.txt}"

if [[ ! -f "${manifest}" ]]; then
    echo "Approved PR manifest is missing: ${manifest}" >&2
    exit 1
fi

cd "${repository_root}"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "Approved PR check needs a git checkout." >&2
    exit 1
fi

failures=0
checked=0
while IFS= read -r line || [[ -n "${line}" ]]; do
    trimmed="${line#"${line%%[![:space:]]*}"}"
    [[ -z "${trimmed}" || "${trimmed}" == \#* ]] && continue
    pr="${trimmed%%[[:space:]]*}"
    sha="${trimmed#"${pr}"}"
    sha="${sha#"${sha%%[![:space:]]*}"}"
    sha="${sha%%[[:space:]]*}"
    if [[ ! "${pr}" =~ ^[0-9]+$ || ! "${sha}" =~ ^[0-9a-fA-F]{40}$ ]]; then
        echo "Approved PR line must be '<number> <40-character sha>': ${trimmed}" >&2
        failures=$((failures + 1))
        continue
    fi
    checked=$((checked + 1))
    if ! git cat-file -e "${sha}^{commit}" 2>/dev/null; then
        echo "PR #${pr} head ${sha} is not in this checkout. Fetch the full history (fetch-depth: 0) and run again." >&2
        failures=$((failures + 1))
        continue
    fi
    if ! git merge-base --is-ancestor "${sha}" HEAD; then
        echo "PR #${pr} head ${sha} is not an ancestor of HEAD ($(git rev-parse --short HEAD))." >&2
        failures=$((failures + 1))
        continue
    fi
    echo "PR #${pr} ${sha} is an ancestor of HEAD"
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
