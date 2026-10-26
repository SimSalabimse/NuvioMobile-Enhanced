#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
engine_version=0.1.6
engine_checksum=f556718ec0fd122a61bc5408307b19fcd54397595954c46c6ff8db1e5a179421
engine_root="${NUVIO_ENGINE_ROOT:-${repository_root}/../nuvio-engine}"
engine_framework="${engine_root}/platform/apple/NuvioEngine.xcframework"

# Keep MPVKit on the commit this checkout records, not only when it is missing. A reused worktree
# kept an older MPVKit after the upstream 0.5.10 merge, so the libmpv without the PiP frame hook
# was linked and Picture in Picture never received frames.
recorded_mpvkit="$(git -C "${repository_root}" ls-tree HEAD MPVKit | awk '{print $3}')"
current_mpvkit="$(git -C "${repository_root}/MPVKit" rev-parse HEAD 2>/dev/null || true)"
if [[ ! -f "${repository_root}/MPVKit/Package.swift" || ( -n "${recorded_mpvkit}" && "${current_mpvkit}" != "${recorded_mpvkit}" ) ]]; then
    git -C "${repository_root}" submodule sync -- MPVKit
    if ! git -C "${repository_root}" submodule update --init --depth 1 MPVKit; then
        git -C "${repository_root}/MPVKit" fetch --depth 1 origin "${recorded_mpvkit}"
        git -C "${repository_root}" submodule update --init MPVKit
    fi
    echo "MPVKit at $(git -C "${repository_root}/MPVKit" rev-parse --short HEAD)"
fi

if [[ -f "${engine_framework}/Info.plist" ]]; then
    exit 0
fi

temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-ios-dependencies.XXXXXX")"
trap 'rm -rf "${temporary_directory}"' EXIT

archive="${temporary_directory}/nuvio-engine-apple-${engine_version}.zip"
curl --fail --location --retry 5 --retry-all-errors --silent --show-error \
    --output "${archive}" \
    "https://github.com/NuvioMedia/nuvio-engine/releases/download/v${engine_version}/nuvio-engine-apple-${engine_version}.zip"

actual_checksum="$(shasum -a 256 "${archive}" | awk '{print $1}')"
if [[ "${actual_checksum}" != "${engine_checksum}" ]]; then
    echo "Nuvio Engine Apple package checksum mismatch." >&2
    exit 1
fi

extraction_root="${temporary_directory}/extracted"
mkdir -p "${extraction_root}"
unzip -q "${archive}" -d "${extraction_root}"
source_framework="$(find "${extraction_root}" -type d -name NuvioEngine.xcframework -print -quit)"
if [[ -z "${source_framework}" || ! -f "${source_framework}/Info.plist" ]]; then
    echo "Nuvio Engine Apple package does not contain NuvioEngine.xcframework." >&2
    exit 1
fi
if [[ ! -f "${source_framework}/ios-arm64/libCNuvioEngine.a" ]]; then
    echo "Nuvio Engine Apple package does not contain the iOS arm64 library." >&2
    exit 1
fi

mkdir -p "$(dirname "${engine_framework}")"
ditto "${source_framework}" "${engine_framework}"
