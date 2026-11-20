#!/usr/bin/env bash
# Negative test: a throwaway repository holds a synthetic token assembled at
# runtime. This file never contains the token, and nothing here is committed.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
bash "${root}/scripts/install-gitleaks.sh"
gitleaks="${root}/.tools/gitleaks"

work="$(mktemp -d)"
cleanup() { rm -rf "$work"; }
trap cleanup EXIT

git -C "$work" init -q
git -C "$work" config user.email "scan@example.com"
git -C "$work" config user.name "Scan"
prefix="AKIA"
body="M7QK4RWT"
tail="C2N6P5HB"
printf 'aws = "%s%s%s"\n' "$prefix" "$body" "$tail" > "${work}/leak.txt"
git -C "$work" add leak.txt
git -C "$work" commit -q -m "seed an example key"

set +e
"$gitleaks" detect --source "$work" --no-banner --redact >"${work}/report.txt" 2>&1
code=$?
set -e
if [[ "$code" -ne 1 ]]; then
  echo "secret-scan negative test: gitleaks exit ${code}, expected 1" >&2
  exit 1
fi
echo "secret-scan negative test: gitleaks rejected the seeded token"

rm -rf "$work"
trap - EXIT
work="$(mktemp -d)"
trap cleanup EXIT
git -C "$work" init -q
git -C "$work" config user.email "scan@example.com"
git -C "$work" config user.name "Scan"
printf 'hello\n' > "${work}/readme.txt"
git -C "$work" add readme.txt
git -C "$work" commit -q -m "clean"
"$gitleaks" detect --source "$work" --no-banner --redact
echo "secret-scan negative test passed"
