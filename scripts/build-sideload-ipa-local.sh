#!/usr/bin/env bash
#
# Build unsigned sideload IPA locally on macOS.
#
# This script prepares local.properties with Official sign-in config (matching CI bake),
# fetches iOS dependencies, and builds the full unsigned IPA.
#
# Usage: ./scripts/build-sideload-ipa-local.sh [version]
#

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "${repository_root}"

# Ensure local.properties exists
if [[ ! -f local.properties ]]; then
    cat > local.properties <<'EOF'
# Local build properties
EOF
fi

# Public official client config (https://nuvio.tv/docs and GET https://api.nuvio.tv/.well-known/nuvio).
# Needed for Official sign-in. Non-blank existing values win.
ensure_prop() {
    local key="$1"
    local value="$2"
    if grep -Eq "^[[:space:]]*${key}[[:space:]]*=[[:space:]]*[^[:space:]]" local.properties; then
        return 0
    fi
    # Remove any existing blank/commented entries for this key
    if [[ "$(uname)" == "Darwin" ]]; then
        sed -i '' -E "/^[[:space:]]*${key}[[:space:]]*=/d" local.properties
    else
        sed -i -E "/^[[:space:]]*${key}[[:space:]]*=/d" local.properties
    fi
    printf '%s=%s\n' "${key}" "${value}" >> local.properties
}

ensure_prop NUVIO_SUPABASE_URL 'https://api.nuvio.tv'
ensure_prop NUVIO_SUPABASE_ANON_KEY 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzgxNTIxMzQ2LCJleHAiOjE5MzkyMDEzNDZ9.tmQaj682pwzehpqlgCDMnySOqiUvpgRbrE43T4VJpDI'

echo "local.properties keys (names only):"
awk -F= '/^[[:space:]]*[^#[:space:]]/ { print "  " $1 }' local.properties

echo
echo "Preparing iOS dependencies..."
./scripts/prepare-ios-dependencies.sh

echo
echo "Building unsigned IPA..."
./scripts/build-ios-ipa.sh "$@"
