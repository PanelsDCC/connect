#!/bin/bash
# Build Debian package for panelsdcc-connect

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "Building Debian package for panelsdcc-connect..."

# Check for required tools
for cmd in dpkg-buildpackage mvn; do
    if ! command -v $cmd &> /dev/null; then
        echo "ERROR: $cmd is required but not installed."
        echo "Install with: sudo apt-get install dpkg-dev debhelper maven"
        exit 1
    fi
done

# Make sure scripts are executable
if [ -f scripts/jmri-install ]; then
    chmod +x scripts/jmri-install
fi
if [ -f debian/rules ]; then
    chmod +x debian/rules
fi
if [ -f debian/postinst ]; then
    chmod +x debian/postinst
fi
if [ -f debian/prerm ]; then
    chmod +x debian/prerm
fi
if [ -f debian/postrm ]; then
    chmod +x debian/postrm
fi

# Check if we need to build the JAR first
# For Debian package, we'll let debian/rules handle the build
# But we can do a test build to make sure everything compiles

echo "Building JAR file (this may fail if JMRI is not available - that's OK for packaging)..."
mvn clean package -DskipTests || {
    echo "WARNING: Maven build failed (likely due to missing JMRI)."
    echo "This is expected for packaging - JMRI will be installed separately."
    echo "Continuing with package build..."
}

# Get version for package filename
if [ ! -f version.sh ]; then
    echo "ERROR: version.sh not found. Make sure you're in the project root." >&2
    exit 1
fi

chmod +x version.sh
source version.sh
DEBIAN_FULL_VERSION=$(bash version.sh debian-full)
JAR_FILENAME=$(bash version.sh jar-filename)

echo "Building version: $DEBIAN_FULL_VERSION (JAR: $JAR_FILENAME)"

# Check if debian/changelog version matches (warn if not)
if [ -f debian/changelog ]; then
    CURRENT_CHANGELOG_VERSION=$(head -1 debian/changelog | grep -oP '\([^)]+\)' | tr -d '()' || echo "")
    if [ -n "$CURRENT_CHANGELOG_VERSION" ] && [ "$CURRENT_CHANGELOG_VERSION" != "$DEBIAN_FULL_VERSION" ]; then
        echo ""
        echo "WARNING: debian/changelog version ($CURRENT_CHANGELOG_VERSION) doesn't match pom.xml version ($DEBIAN_FULL_VERSION)"
        echo "You may want to update it with: ./update-version.sh $(bash version.sh maven)"
        echo ""
    fi
fi

# Build the Debian package
echo ""
echo "Building Debian package (version: $DEBIAN_FULL_VERSION)..."
dpkg-buildpackage -us -uc -b

echo ""
echo "Build complete!"
echo ""
echo "To install the package:"
echo "  sudo dpkg -i ../panelsdcc-connect_${DEBIAN_FULL_VERSION}_all.deb"
echo ""
echo "After installation, install JMRI with:"
echo "  sudo jmri-install"

