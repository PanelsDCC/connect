#!/bin/bash
# Run the panelsdcc-connect fat JAR produced by the last ./build.sh (mvn package).
# JMRI stays on the classpath (system-scoped deps are not bundled in the assembly JAR).

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JMRI_PREBUILT="${JMRI_HOME:-$SCRIPT_DIR/../JMRI}"

# Prefer an explicit JAVA_HOME; otherwise Homebrew OpenJDK (macOS /usr/bin/java is a stub).
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
    for candidate in \
        /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
        /opt/homebrew/Cellar/openjdk/*/libexec/openjdk.jdk/Contents/Home \
        /usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home
    do
        # shellcheck disable=SC2086
        for home in $candidate; do
            if [ -x "$home/bin/java" ]; then
                JAVA_HOME="$home"
                break 2
            fi
        done
    done
fi
if [ -n "${JAVA_HOME:-}" ]; then
    export JAVA_HOME
    PATH="$JAVA_HOME/bin:$PATH"
fi
JAVA_BIN="$(command -v java || true)"
if [ -z "$JAVA_BIN" ] || ! "$JAVA_BIN" -version >/dev/null 2>&1; then
    echo "No working Java runtime found. Install OpenJDK (e.g. brew install openjdk) or set JAVA_HOME." >&2
    exit 1
fi

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

echo "Running $JAR (java: $JAVA_BIN)"
exec "$JAVA_BIN" -cp "$JAR:$JMRI_PREBUILT/jmri.jar:$JMRI_PREBUILT/lib/*" \
    cc.panelsd.connect.daemon.DccIoDaemon "$@"
