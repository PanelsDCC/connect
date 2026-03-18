#!/bin/bash
# Version management script for panelsDCC-connect
# Extracts version from pom.xml and provides it in various formats

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
POM_FILE="${SCRIPT_DIR}/pom.xml"

if [ ! -f "$POM_FILE" ]; then
    echo "ERROR: pom.xml not found at $POM_FILE" >&2
    exit 1
fi

# Extract version from pom.xml (e.g., "0.1.0-SNAPSHOT")
MAVEN_VERSION=$(grep -oP '<version>\K[^<]+' "$POM_FILE" | head -1)

if [ -z "$MAVEN_VERSION" ]; then
    echo "ERROR: Could not extract version from pom.xml" >&2
    exit 1
fi

# Remove -SNAPSHOT suffix for Debian version
DEBIAN_VERSION=$(echo "$MAVEN_VERSION" | sed 's/-SNAPSHOT//')
DEBIAN_FULL_VERSION="${DEBIAN_VERSION}-1"

# JAR filename (matches Maven assembly output)
JAR_VERSION="$DEBIAN_VERSION"
JAR_FILENAME="panelsDCC-connect-${JAR_VERSION}-jar-with-dependencies.jar"

# Export variables for use in other scripts
export MAVEN_VERSION
export DEBIAN_VERSION
export DEBIAN_FULL_VERSION
export JAR_VERSION
export JAR_FILENAME

# Function to print version in requested format
case "${1:-all}" in
    maven)
        echo "$MAVEN_VERSION"
        ;;
    debian)
        echo "$DEBIAN_VERSION"
        ;;
    debian-full)
        echo "$DEBIAN_FULL_VERSION"
        ;;
    jar)
        echo "$JAR_VERSION"
        ;;
    jar-filename)
        echo "$JAR_FILENAME"
        ;;
    all)
        echo "MAVEN_VERSION=$MAVEN_VERSION"
        echo "DEBIAN_VERSION=$DEBIAN_VERSION"
        echo "DEBIAN_FULL_VERSION=$DEBIAN_FULL_VERSION"
        echo "JAR_VERSION=$JAR_VERSION"
        echo "JAR_FILENAME=$JAR_FILENAME"
        ;;
    *)
        echo "Usage: $0 [maven|debian|debian-full|jar|jar-filename|all]" >&2
        exit 1
        ;;
esac

