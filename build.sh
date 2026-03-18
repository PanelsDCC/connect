#!/bin/bash
# Build script for panelsdcc-connect
# Uses pre-built jmri.jar if available, otherwise builds from source

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JMRI_PREBUILT="${JMRI_HOME:-$(dirname "$0")/../JMRI}"
JMRI_SOURCE="${JMRI_HOME:-$(dirname "$0")/../JMRI-5.12}"

echo "Building panelsdcc-connect..."

# Check for pre-built jmri.jar first
if [ -f "$JMRI_PREBUILT/jmri.jar" ]; then
    echo "Using pre-built JMRI JAR: $JMRI_PREBUILT/jmri.jar"
    # Build the daemon (pom.xml will use system scope to reference the JAR)
    cd "$SCRIPT_DIR"
    mvn clean package
elif [ -d "$JMRI_SOURCE/java" ]; then
    echo "JMRI source found. Building JMRI first..."
    cd "$JMRI_SOURCE"
    if command -v ant &> /dev/null; then
        ant
    else
        echo "ERROR: 'ant' command not found. Please build JMRI manually:"
        echo "  cd $JMRI_SOURCE"
        echo "  ant"
        exit 1
    fi
    
    # Create JMRI JAR from compiled classes
    echo "Creating JMRI JAR..."
    jar -cf jmri.jar -C java/build/classes . 2>/dev/null || {
        echo "ERROR: Failed to create JMRI JAR. Make sure 'jar' command is available."
        exit 1
    }
    
    # Install to local Maven repo
    echo "Installing JMRI JAR to local Maven repository..."
    mvn install:install-file -Dfile=jmri.jar \
        -DgroupId=org.jmri \
        -DartifactId=jmri \
        -Dversion=5.12.0 \
        -Dpackaging=jar \
        -DgeneratePom=true
    
    cd "$SCRIPT_DIR"
    mvn clean package
else
    echo "ERROR: Neither pre-built jmri.jar nor JMRI source found."
    echo "  Expected: $JMRI_PREBUILT/jmri.jar"
    echo "  Or: $JMRI_SOURCE/java (source directory)"
    exit 1
fi

echo ""
echo "Build complete! Run with:"
echo "  java -jar target/panelsdcc-connect-0.1.0-SNAPSHOT-jar-with-dependencies.jar [port]"
echo ""
echo "Note: If using system scope, you may need to add JMRI to classpath:"
echo "  java -cp \"target/panelsdcc-connect-0.1.0-SNAPSHOT-jar-with-dependencies.jar:$JMRI_PREBUILT/jmri.jar:$JMRI_PREBUILT/lib/*\" cc.panelsd.connect.daemon.DccIoDaemon [port]"

