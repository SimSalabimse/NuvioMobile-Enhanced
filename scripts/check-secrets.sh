#!/usr/bin/env bash
# Run the pinned gitleaks CLI over the pull-request commit range.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"
bash "${root}/scripts/install-gitleaks.sh"
gitleaks="${root}/.tools/gitleaks"

base="${NUVIO_BASE_SHA:-}"
head="${NUVIO_HEAD_SHA:-HEAD}"
if [[ -z "$base" || "$base" =~ ^0+$ ]]; then
  ref="${NUVIO_BASE_REF:-beta}"
  if git rev-parse --verify "origin/${ref}" >/dev/null 2>&1; then
    base="$(git rev-parse "origin/${ref}")"
  else
    base="$(git rev-parse "$ref")"
  fi
fi

echo "secret-scan: gitleaks ${base}..${head}"
"$gitleaks" detect \
  --source "$root" \
  --config "${root}/.gitleaks.toml" \
  --redact \
  --no-banner \
  --log-opts="${base}..${head}"
