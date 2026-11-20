#!/usr/bin/env bash
# Install the pinned gitleaks CLI. No licence key. The checksum file is the
# one published with that exact release.
set -euo pipefail

version="8.30.1"
root="$(cd "$(dirname "$0")/.." && pwd)"
dest="${root}/.tools"
mkdir -p "$dest"
if [[ -x "${dest}/gitleaks" ]] && "${dest}/gitleaks" version 2>/dev/null | grep -q "$version"; then
  exit 0
fi

os="$(uname -s | tr '[:upper:]' '[:lower:]')"
arch="$(uname -m)"
case "$arch" in
  x86_64) arch="x64" ;;
  aarch64 | arm64) arch="arm64" ;;
  *) echo "gitleaks: unsupported architecture ${arch}" >&2; exit 1 ;;
esac
asset="gitleaks_${version}_${os}_${arch}.tar.gz"
url="https://github.com/gitleaks/gitleaks/releases/download/v${version}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl -fsSL -o "${work}/${asset}" "${url}/${asset}"
curl -fsSL -o "${work}/gitleaks_${version}_checksums.txt" "${url}/gitleaks_${version}_checksums.txt"
(
  cd "$work"
  grep "  ${asset}$" "gitleaks_${version}_checksums.txt" | sha256sum -c -
)
tar -xzf "${work}/${asset}" -C "$work" gitleaks
install -m 0755 "${work}/gitleaks" "${dest}/gitleaks"
"${dest}/gitleaks" version
