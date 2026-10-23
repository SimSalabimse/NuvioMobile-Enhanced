#!/usr/bin/env bash
# After a green simulator smoke: upload IPA as GitHub pre-release and update store.json.
# Keeps iCloud SL copies untouched. Does not force-push. No real names in commits.
set -euo pipefail

repository_root="$(cd "$(dirname "$0")/../.." && pwd -P)"
cd "${repository_root}"

REPO="${PUBLISH_REPO:-SimSalabimse/NuvioMobile-Enhanced}"
IPA_PATH="${1:-}"
VERSION="${PUBLISH_VERSION:-}"
BUILD="${PUBLISH_BUILD:-}"
STORE_BRANCH="${PUBLISH_STORE_BRANCH:-enhanced}"
SCRIPTS_BRANCH_PREFIX="${PUBLISH_SCRIPTS_BRANCH_PREFIX:-chore/release-scripts}"

usage() {
  echo "Usage: $0 <unsigned.ipa>" >&2
  echo "Env: PUBLISH_VERSION PUBLISH_BUILD (optional; read from IPA / Version.xcconfig)" >&2
  exit 1
}

[[ -n "${IPA_PATH}" && -f "${IPA_PATH}" ]] || usage
command -v gh >/dev/null
command -v python3 >/dev/null

SHA="$(git rev-parse --short=8 HEAD)"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
CHANGELOG="${repository_root}/composeApp/src/commonMain/composeResources/files/changelog.json"

if [[ -z "${VERSION}" || -z "${BUILD}" ]]; then
  TMP="$(mktemp -d)"
  trap 'rm -rf "${TMP}"' EXIT
  unzip -q "${IPA_PATH}" -d "${TMP}"
  APP_PLIST="$(find "${TMP}/Payload" -maxdepth 2 -name Info.plist | head -1)"
  VERSION="${VERSION:-$(plutil -extract CFBundleShortVersionString raw "${APP_PLIST}")}"
  BUILD="${BUILD:-$(plutil -extract CFBundleVersion raw "${APP_PLIST}")}"
fi

TAG="sideload-${VERSION}-${SHA}"
ASSET_NAME="nuvio-${VERSION}-full-release.ipa"
NOTES_FILE="$(mktemp)"
python3 "${repository_root}/scripts/release/changelog-to-notes.py" \
  --changelog "${CHANGELOG}" \
  --version "${VERSION}" \
  --build "${BUILD}" \
  --branch "${BRANCH}" \
  --sha "${SHA}" \
  --output "${NOTES_FILE}"

# Copy IPA to a stable asset name for the release
STAGE_DIR="$(mktemp -d)"
STAGE_IPA="${STAGE_DIR}/${ASSET_NAME}"
cp "${IPA_PATH}" "${STAGE_IPA}"

echo "publish: creating ${TAG} on ${REPO}"
if gh release view "${TAG}" --repo "${REPO}" >/dev/null 2>&1; then
  echo "publish: release ${TAG} already exists — uploading asset if missing" >&2
else
  gh release create "${TAG}" \
    --repo "${REPO}" \
    --title "Sideload ${VERSION}" \
    --notes-file "${NOTES_FILE}" \
    --prerelease \
    --target "$(git rev-parse HEAD)" \
    "${STAGE_IPA}"
fi

# Ensure asset present (re-upload if create raced without it)
if ! gh release view "${TAG}" --repo "${REPO}" --json assets -q '.assets[].name' | grep -Fxq "${ASSET_NAME}"; then
  gh release upload "${TAG}" "${STAGE_IPA}" --repo "${REPO}" --clobber
fi

DOWNLOAD_URL="https://github.com/${REPO}/releases/download/${TAG}/${ASSET_NAME}"
RELEASE_DATE="$(gh release view "${TAG}" --repo "${REPO}" --json publishedAt -q .publishedAt)"
if [[ -z "${RELEASE_DATE}" || "${RELEASE_DATE}" == "null" ]]; then
  RELEASE_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
fi

# Update store.json from the live enhanced copy so we don't drop newer entries
WORK="$(mktemp -d)"
git fetch origin "${STORE_BRANCH}"
git show "origin/${STORE_BRANCH}:store.json" > "${WORK}/store.json"
python3 "${repository_root}/scripts/update-store-source.py" \
  --source "${WORK}/store.json" \
  --ipa "${STAGE_IPA}" \
  --release-notes "${NOTES_FILE}" \
  --release-version "${VERSION}" \
  --release-date "${RELEASE_DATE}" \
  --download-url "${DOWNLOAD_URL}" \
  --output "${WORK}/store.json"

# Commit store.json onto STORE_BRANCH without merging feature work
git worktree add --detach "${WORK}/wt" "origin/${STORE_BRANCH}"
cp "${WORK}/store.json" "${WORK}/wt/store.json"
(
  cd "${WORK}/wt"
  if [[ -z "$(git status --porcelain -- store.json)" ]]; then
    echo "publish: store.json already current for ${VERSION} (${BUILD})"
  else
    git add store.json
    git commit -m "chore(store): publish ${VERSION}"
    git push origin "HEAD:refs/heads/${STORE_BRANCH}"
    echo "publish: pushed store.json to ${STORE_BRANCH} @ $(git rev-parse --short=8 HEAD)"
  fi
)
git worktree remove --force "${WORK}/wt"

echo "PUBLISH_OK"
echo "RELEASE_URL=https://github.com/${REPO}/releases/tag/${TAG}"
echo "SOURCE_URL=https://raw.githubusercontent.com/${REPO}/${STORE_BRANCH}/store.json"
echo "DOWNLOAD_URL=${DOWNLOAD_URL}"
echo "TAG=${TAG}"
echo "VERSION=${VERSION}"
echo "BUILD=${BUILD}"
