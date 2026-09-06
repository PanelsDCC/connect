#!/bin/bash
# Publish Connect to GitHub (commit, tag, push). Attach the .deb to the release manually.
#
#   ./publish-github.sh
#
# Full flow (set version, build, commit, tag, push):
#   VERSION=2.0.1 ./release-github.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "ERROR: not a git repository" >&2
  exit 1
fi

chmod +x version.sh
VERSION="$(./version.sh maven)"
if [ -z "$VERSION" ]; then
  echo "ERROR: could not read version from pom.xml" >&2
  exit 1
fi
TAG="v${VERSION}"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
REMOTE_URL="$(git remote get-url origin)"
REPO="$(echo "$REMOTE_URL" | sed -E 's#.*github.com[:/](.+)(\.git)?$#\1#' | sed 's/\.git$//')"
REPO="${REPO:-PanelsDCC/connect}"

has_local_tag() {
  git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null 2>&1
}

has_remote_tag() {
  git ls-remote --tags origin "refs/tags/${TAG}" 2>/dev/null | grep -q .
}

echo "Publishing ${TAG} on ${REPO} (${BRANCH})"

git add -A
if git diff --cached --quiet; then
  echo "Nothing new to commit."
else
  git commit -m "${TAG}"
fi

if has_local_tag; then
  LOCAL_SHA="$(git rev-parse "${TAG}^{}")"
  HEAD_SHA="$(git rev-parse HEAD)"
  if [ "$LOCAL_SHA" != "$HEAD_SHA" ]; then
    if has_remote_tag; then
      echo "Tag ${TAG} already exists on origin; leaving it in place."
    else
      echo "Moving local tag ${TAG} to HEAD (not yet on origin)."
      git tag -d "${TAG}" >/dev/null
      git tag "${TAG}"
    fi
  else
    echo "Local tag ${TAG} already points at HEAD."
  fi
else
  git tag "${TAG}"
fi

git push origin "${BRANCH}"

if has_remote_tag; then
  echo "Tag ${TAG} already on origin."
else
  git push origin "${TAG}"
fi

echo "Pushed ${TAG}: https://github.com/${REPO}/releases/tag/${TAG}"

DEB_NAME="panelsdcc-connect_$(./version.sh debian-full)_all.deb"
if [ -f "${SCRIPT_DIR}/../${DEB_NAME}" ]; then
  echo "Attach the .deb to the GitHub release manually:"
  echo "  ${SCRIPT_DIR}/../${DEB_NAME}"
elif [ -f "${SCRIPT_DIR}/${DEB_NAME}" ]; then
  echo "Attach the .deb to the GitHub release manually:"
  echo "  ${SCRIPT_DIR}/${DEB_NAME}"
else
  echo "No ${DEB_NAME} found. Run ./build-deb.sh then attach it to the GitHub release."
fi
