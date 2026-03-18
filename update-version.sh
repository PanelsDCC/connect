#!/bin/bash
# Helper script to update version across the project
# Usage: ./update-version.sh <new-version>
# Example: ./update-version.sh 0.2.0-SNAPSHOT

set -e

if [ $# -ne 1 ]; then
    echo "Usage: $0 <new-version>"
    echo "Example: $0 0.2.0-SNAPSHOT"
    exit 1
fi

NEW_VERSION="$1"

# Validate version format (basic check)
if ! echo "$NEW_VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$'; then
    echo "WARNING: Version format doesn't match expected pattern (e.g., 0.1.0 or 0.1.0-SNAPSHOT)"
    read -p "Continue anyway? [y/N] " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
POM_FILE="${SCRIPT_DIR}/pom.xml"

if [ ! -f "$POM_FILE" ]; then
    echo "ERROR: pom.xml not found at $POM_FILE" >&2
    exit 1
fi

echo "Updating version to $NEW_VERSION..."

# Update pom.xml - only the project version (the version tag right after <artifactId>dcc-io-daemon</artifactId>)
# Use awk to be more precise and only change the version after the artifactId
awk -v new_version="${NEW_VERSION}" '
/<artifactId>dcc-io-daemon<\/artifactId>/ {
    print
    getline
    if (match($0, /<version>[^<]*<\/version>/)) {
        sub(/<version>[^<]*<\/version>/, "<version>" new_version "</version>")
    }
    print
    next
}
{ print }
' "$POM_FILE" > "${POM_FILE}.tmp" && mv "${POM_FILE}.tmp" "$POM_FILE"
echo "✓ Updated pom.xml (project version only)"

# Update debian/changelog (add new entry)
CHANGELOG_FILE="${SCRIPT_DIR}/debian/changelog"
DEBIAN_VERSION=$(echo "$NEW_VERSION" | sed 's/-SNAPSHOT//')
DEBIAN_FULL_VERSION="${DEBIAN_VERSION}-1"
DATE=$(date -R)

if [ -f "$CHANGELOG_FILE" ]; then
    # Create new changelog entry at the top
    {
        echo "dcc-io-daemon (${DEBIAN_FULL_VERSION}) unstable; urgency=medium"
        echo ""
        echo "  * Version ${NEW_VERSION}"
        echo ""
        echo " -- panelsDCC-connect Maintainer <maintainer@example.com>  ${DATE}"
        echo ""
        echo ""
        cat "$CHANGELOG_FILE"
    } > "${CHANGELOG_FILE}.new"
    mv "${CHANGELOG_FILE}.new" "$CHANGELOG_FILE"
    echo "✓ Updated debian/changelog"
else
    echo "WARNING: debian/changelog not found, skipping"
fi

echo ""
echo "Version updated to: $NEW_VERSION"
echo "Debian version: $DEBIAN_FULL_VERSION"
echo ""
echo "You can verify with: ./version.sh all"

