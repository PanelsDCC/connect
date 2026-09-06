#!/bin/bash
# Set version, build, then commit/tag/push to GitHub.
# Attach the .deb to the GitHub release manually.
#
#   VERSION=2.0.1 ./release-github.sh
#   ./release-github.sh 2.0.1
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

VERSION="${VERSION:-${1:-}}"
if [ -z "$VERSION" ]; then
  echo "Usage: VERSION=2.0.1 $0"
  echo "   or: $0 2.0.1"
  exit 1
fi

chmod +x update-version.sh build.sh publish-github.sh
./update-version.sh "$VERSION"
./build.sh
./publish-github.sh
