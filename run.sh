#!/bin/bash
# Run the panelsdcc-connect fat JAR produced by the last ./build.sh (mvn package).
# JMRI stays on the classpath (system-scoped deps are not bundled in the assembly JAR).

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JMRI_PREBUILT="${JMRI_HOME:-$SCRIPT_DIR/../JMRI}"

shopt -s nullglob
candidates=("$SCRIPT_DIR"/target/*-jar-with-dependencies.jar)
if [ "${#candidates[@]}" -eq 0 ]; then
    echo "No *-jar-with-dependencies.jar in $SCRIPT_DIR/target" >&2
    echo "Run ./build.sh first." >&2
    exit 1
fi

# If several versions exist (e.g. after a pom bump), use the newest file
JAR="$(ls -t "${candidates[@]}" | head -1)"

if [ ! -f "$JMRI_PREBUILT/jmri.jar" ]; then
    echo "JMRI JAR not found: $JMRI_PREBUILT/jmri.jar" >&2
    echo "Set JMRI_HOME to the directory that contains jmri.jar (same as for ./build.sh)." >&2
    exit 1
fi

echo "Running $JAR"
exec java -cp "$JAR:$JMRI_PREBUILT/jmri.jar:$JMRI_PREBUILT/lib/*" \
    cc.panelsd.connect.daemon.DccIoDaemon "$@"
