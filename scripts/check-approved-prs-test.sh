#!/usr/bin/env bash
# The approved-PR check must fail for a pull request that is neither in history
# nor recorded as included by a squash commit whose subject ends with (#N).

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
checker="${repository_root}/scripts/check-approved-prs.sh"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

cd "${repository_root}"

expect_failure() {
    local name="$1"
    local manifest="$2"
    local squashes="$3"
    local status output
    set +e
    output="$("${checker}" "${manifest}" "${squashes}" 2>&1)"
    status=$?
    set -e
    if [[ "${status}" -eq 0 ]]; then
        echo "${name} should have failed" >&2
        printf '%s\n' "${output}" >&2
        exit 1
    fi
    echo "${name} failed as required (exit ${status})"
    printf '%s\n' "${output}"
}

missing_sha="0123456789abcdef0123456789abcdef01234567"
printf '999 %s\n' "${missing_sha}" > "${work}/missing.txt"
: > "${work}/empty-squashes.txt"
expect_failure "unrecorded pull request" "${work}/missing.txt" "${work}/empty-squashes.txt"

head_sha="$(git rev-parse HEAD)"
head_subject="$(git log -1 --format=%s HEAD)"
if [[ "${head_subject}" =~ \(#[0-9]+\)$ ]]; then
    echo "HEAD subject ends with (#N), so it cannot be the negative squash case." >&2
    exit 1
fi
printf '999 %s\n' "${missing_sha}" > "${work}/bad-subject.txt"
printf '%s 999\n' "${head_sha}" > "${work}/bad-subject-squashes.txt"
expect_failure "squash whose subject does not end with (#N)" "${work}/bad-subject.txt" "${work}/bad-subject-squashes.txt"

printf '999 %s\n' "${missing_sha}" > "${work}/recorded.txt"
printf '81572208faeaf871239a1a3087f250d5cf3d630c 999\n' > "${work}/recorded-squashes.txt"
"${checker}" "${work}/recorded.txt" "${work}/recorded-squashes.txt"

echo "Approved PR negative cases passed."
"${checker}"
