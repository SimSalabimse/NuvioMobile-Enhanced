#!/usr/bin/env bash
# Local unsigned/sideload IPA build for SideStore / AltStore / TrollStore.
# Mirrors .github/workflows/build-sideload-ipa.yml without publishing.
# Official sign-in bake matches CI ensure_prop (blank props get public api.nuvio.tv anon).
#
# Usage (from repo root or anywhere):
#   ./scripts/build-sideload-ipa-local.sh
#   IOS_CONFIGURATION=Debug ./scripts/build-sideload-ipa-local.sh
#
# Requires: Xcode with iOS SDK, JDK 17+ (prefers 17), network once for NuvioEngine + deps.
# Optional: local.properties with Trakt/Simkl keys (same as CI secret NUVIO_LOCAL_PROPERTIES_BASE64).

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "${repository_root}"

configuration="${IOS_CONFIGURATION:-Release}"
export IOS_CONFIGURATION="${configuration}"

# Prefer Temurin/Homebrew JDK 17 (matches CI); fall back to 21, then default.
if [[ -z "${JAVA_HOME:-}" ]]; then
  if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  elif [[ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  elif /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  elif [[ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  else
    export JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
  fi
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}${PATH}"

# Cap JVM heaps for Apple Silicon laptops (CI uses ~4.5G; gradle.properties defaults to 12G).
# build-ios-ipa.sh forwards these into xcodebuild/Gradle via `env` (dotted names are invalid for `export`).
export NUVIO_GRADLE_JVMARGS="${NUVIO_GRADLE_JVMARGS:--Xmx4608M -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=768M}"
export NUVIO_KOTLIN_NATIVE_JVMARGS="${NUVIO_KOTLIN_NATIVE_JVMARGS:--Xmx4608M}"
export KOTLIN_DAEMON_JVMARGS="${KOTLIN_DAEMON_JVMARGS:--Xmx2048M}"
export GRADLE_OPTS="${GRADLE_OPTS:--Dfile.encoding=UTF-8}"

echo "==> Repo: ${repository_root}"
echo "==> Configuration: ${configuration}"
echo "==> JAVA_HOME: ${JAVA_HOME:-"(unset)"}"
if command -v java >/dev/null 2>&1; then
  java -version 2>&1 | head -n 1 || true
else
  echo "ERROR: java not found. Install OpenJDK 17: brew install openjdk@17" >&2
  exit 1
fi

if ! xcodebuild -version >/dev/null 2>&1; then
  echo "ERROR: xcodebuild not available. Install Xcode and run: sudo xcode-select -s /Applications/Xcode.app/Contents/Developer" >&2
  exit 1
fi
echo "==> $(xcodebuild -version | tr '\n' ' ')"
echo "==> iOS SDK: $(xcrun --sdk iphoneos --show-sdk-version)"

if [[ ! -f local.properties ]]; then
  echo "==> Creating empty local.properties (gitignored; optional Trakt/Simkl/TMDB keys)"
  cat > local.properties <<'PROPS'
# Optional API keys for local/sideload builds. Leave blank to build; Trakt/Simkl sign-in needs real values.
# TMDB_API_KEY=
# TRAKT_CLIENT_ID=
# TRAKT_CLIENT_SECRET=
# SIMKL_CLIENT_ID=
PROPS
fi

# Public official client config (same as CI ensure_prop in build-sideload-ipa.yml).
# Needed for Official sign-in after logout. Non-blank existing values win.
ensure_prop() {
  local key="$1"
  local value="$2"
  if grep -Eq "^[[:space:]]*${key}[[:space:]]*=[[:space:]]*[^[:space:]]" local.properties; then
    return 0
  fi
  if [[ "$(uname)" == "Darwin" ]]; then
    sed -i '' -E "/^[[:space:]]*${key}[[:space:]]*=/d" local.properties
  else
    sed -i -E "/^[[:space:]]*${key}[[:space:]]*=/d" local.properties
  fi
  printf '%s=%s\n' "${key}" "${value}" >> local.properties
}

ensure_prop NUVIO_SUPABASE_URL 'https://api.nuvio.tv'
ensure_prop NUVIO_SUPABASE_ANON_KEY 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzgxNTIxMzQ2LCJleHAiOjE5MzkyMDEzNDZ9.tmQaj682pwzehpqlgCDMnySOqiUvpgRbrE43T4VJpDI'

echo "==> local.properties keys (names only):"
awk -F= '/^[[:space:]]*[^#[:space:]]/ { print "  " $1 }' local.properties

if [[ ! -f MPVKit/Package.swift ]]; then
  echo "==> Initializing MPVKit submodule"
  git submodule update --init --depth 1 MPVKit
fi

echo "==> Preparing iOS dependencies (NuvioEngine xcframework)"
./scripts/prepare-ios-dependencies.sh

echo "==> Building unsigned IPA (this can take 15–60+ minutes on first run)"
./scripts/build-ios-ipa.sh

configuration_slug="$(printf '%s' "${configuration}" | tr '[:upper:]' '[:lower:]')"
ipa_path="$(find build/ios-ipa -maxdepth 1 -type f -name "*-full-${configuration_slug}.ipa" -print -quit 2>/dev/null || true)"
if [[ -z "${ipa_path}" ]]; then
  echo "ERROR: IPA not found under build/ios-ipa/" >&2
  ls -la build/ios-ipa/ 2>&1 || true
  exit 1
fi

ipa_path="$(cd "$(dirname "${ipa_path}")" && pwd -P)/$(basename "${ipa_path}")"
echo
echo "========================================"
echo "IPA ready (unsigned / sideloadable):"
echo "  ${ipa_path}"
echo "Size: $(du -h "${ipa_path}" | cut -f1)"
echo "SHA-256: $(shasum -a 256 "${ipa_path}" | awk '{print $1}')"
echo "Install with SideStore, AltStore, or TrollStore."
echo "========================================"
