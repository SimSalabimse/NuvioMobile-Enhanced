#!/usr/bin/env bash
# Compare the latest published release of luqmanfadlli/NuvioMobile-Enhanced
# with docs/release/upstream-synced.txt. A new tag is merged, not squashed,
# onto upstream-sync/<tag> from beta. DRY_RUN=true only prints the comparison.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"

upstream_repo="luqmanfadlli/NuvioMobile-Enhanced"
case "$upstream_repo" in
  *NuvioMedia*) echo "sync: refusing NuvioMedia" >&2; exit 1 ;;
esac
upstream_url="https://github.com/${upstream_repo}.git"
dry_run="${DRY_RUN:-false}"
recorded="$(tr -d '[:space:]' < docs/release/upstream-synced.txt)"
if [[ -z "$recorded" ]]; then
  echo "sync: docs/release/upstream-synced.txt is empty" >&2
  exit 1
fi

tag="$(gh api "repos/${upstream_repo}/releases?per_page=20" --jq '[.[] | select(.draft == false)][0].tag_name')"
if [[ -z "$tag" || "$tag" == "null" ]]; then
  echo "sync: no published release on ${upstream_repo}" >&2
  exit 1
fi
compare="${tag#v}"
recorded_compare="${recorded#v}"
echo "sync: recorded ${recorded_compare}, latest published ${compare} (${tag})"
if [[ "$compare" == "$recorded_compare" ]]; then
  echo "sync: nothing to do"
  exit 0
fi
if [[ "$dry_run" == "true" ]]; then
  echo "sync: dry run, would open upstream-sync/${tag} for ${compare}"
  exit 0
fi

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
if git remote get-url upstream >/dev/null 2>&1; then
  git remote set-url upstream "$upstream_url"
else
  git remote add upstream "$upstream_url"
fi
git fetch --no-tags upstream "refs/tags/${tag}:refs/tags/${tag}"
git fetch origin beta

branch="upstream-sync/${tag}"
git checkout -B "$branch" origin/beta
if ! git merge --no-edit "refs/tags/${tag}"; then
  conflicts="$(git diff --name-only --diff-filter=U || true)"
  git merge --abort || true
  git checkout --detach origin/beta
  gh label create upstream-sync-conflict \
    --color B60205 \
    --description "An upstream release merge conflict" || true
  title="Upstream sync conflict: ${tag}"
  existing="$(gh issue list --state open --label upstream-sync-conflict --json number,title --jq "first(.[] | select(.title==\"${title}\") | .number)")"
  body="$(printf 'Merge of %s into beta stopped on conflicts.\n\n```\n%s\n```\n' "$tag" "$conflicts")"
  if [[ -n "$existing" ]]; then
    gh issue comment "$existing" --body "$body"
  else
    gh issue create --title "$title" --label upstream-sync-conflict --body "$body"
  fi
  echo "sync: conflict, stopped"
  exit 1
fi

printf '%s\n' "$compare" > docs/release/upstream-synced.txt
git add docs/release/upstream-synced.txt
if ! git diff --cached --quiet; then
  git commit -m "Record upstream sync ${compare}"
fi

git push origin "HEAD:${branch}"
existing_pr="$(gh pr list --base beta --head "$branch" --json number --jq '.[0].number')"
if [[ -z "$existing_pr" ]]; then
  gh pr create --base beta --head "$branch" \
    --title "Sync upstream ${tag}" \
    --body "$(cat <<EOF
Merges published release \`${tag}\` of luqmanfadlli/NuvioMobile-Enhanced into \`beta\` with a real git merge.

The beta gates are dispatched on this branch because a pull request opened with the built-in token does not start other workflows.

A protected path that had to change needs a line \`upstream-guard: <path>\` in this body. Auto Sync's default and fallback stay off.
EOF
)"
fi

gh workflow run beta-gates.yml --ref "$branch"
echo "sync: opened ${branch} and dispatched beta-gates"
