#!/bin/zsh
# build.sh — compile the Java sources into build/classes and package
# target/bigm.jar. Classes compile at the bundled JDK's own level (25): the
# launcher ships that JDK, and the native bridge uses Java's Foreign
# Function & Memory API (stable in 22+), which a --release pin could not
# express.
set -euo pipefail

ROOT="${0:A:h}"
JAVA_HOME="$ROOT/jdk/Contents/Home"
BUILD_DIR="$ROOT/build"
CLASSES="$BUILD_DIR/classes"
JAR_TMP="$ROOT/target/bigm.jar.tmp"

# The release-read token is compiled in, exactly like the Swift build:
# it comes from BIGM_EMBEDDED_GH_TOKEN or the local, gitignored
# Packaging/embedded-token.txt, written into a generated source that exists
# only for the duration of this build.
GENERATED_TOKEN_SOURCE="$ROOT/src/core/EmbeddedToken.java"
EMBEDDED_TOKEN="${BIGM_EMBEDDED_GH_TOKEN:-}"
if [[ -z "${EMBEDDED_TOKEN}" && -f "$ROOT/Packaging/embedded-token.txt" ]]; then
    EMBEDDED_TOKEN="$(tr -d '[:space:]' < "$ROOT/Packaging/embedded-token.txt")"
fi
# Convention fallback for this workspace: the Swift launcher's gitignored
# token file is the canonical home for the read-only release token.
if [[ -z "${EMBEDDED_TOKEN}" && -f "$HOME/Downloads/BigMLauncher/Packaging/embedded-token.txt" ]]; then
    mkdir -p "$ROOT/Packaging"
    cp "$HOME/Downloads/BigMLauncher/Packaging/embedded-token.txt" \
        "$ROOT/Packaging/embedded-token.txt"
    EMBEDDED_TOKEN="$(tr -d '[:space:]' < "$ROOT/Packaging/embedded-token.txt")"
fi
if [[ -n "${EMBEDDED_TOKEN}" ]]; then
    cp "$GENERATED_TOKEN_SOURCE" "$GENERATED_TOKEN_SOURCE.orig" 2>/dev/null || true
    printf 'package core;\n\n/** Generated at build time; never committed with a live value. */\npublic final class EmbeddedToken {\n    public static final String VALUE = "%s";\n    private EmbeddedToken() {}\n}\n' "${EMBEDDED_TOKEN}" > "$GENERATED_TOKEN_SOURCE"
fi

mkdir -p "$CLASSES" "$ROOT/target"

# Sources: every .java under src/, sorted for reproducibility.
SOURCES=( ${(f)"$(find "$ROOT/src" -name '*.java' -print | sort)"} )
(( ${#SOURCES[@]} > 0 )) || { print -u2 "no Java sources found"; exit 1; }

"$JAVA_HOME/bin/javac" \
    -encoding UTF-8 \
    -Xlint:all \
    -cp "$ROOT/lib/*" \
    -d "$CLASSES" \
    "${SOURCES[@]}"
BUILD_RC=$?
# Put the pristine stub back regardless of how this build ended.
if [[ -n "${EMBEDDED_TOKEN}" ]]; then
    printf 'package core;\n\n/** Generated at build time; never committed with a live value. */\npublic final class EmbeddedToken {\n    public static final String VALUE = "";\n    private EmbeddedToken() {}\n}\n' > "$GENERATED_TOKEN_SOURCE"
fi
(( BUILD_RC == 0 )) || exit $BUILD_RC

# Copy resources referenced from code into the jar layout.
pull_resource() {
    local src="$1"
    local dst="$2"
    if [[ -f "$src" ]]; then
        mkdir -p "${CLASSES}/$(dirname "$dst")"
        cp "$src" "${CLASSES}/$dst"
    fi
}
pull_resource "$ROOT/Resources/default-catalog.json" "default-catalog.json"

"$JAVA_HOME/bin/jar" --create \
    --file "$JAR_TMP" \
    --main-class BigMApp \
    -C "$CLASSES" .
mv -f "$JAR_TMP" "$ROOT/target/bigm.jar"
print "Built $ROOT/target/bigm.jar"
print "Bundled classes: $(find "$CLASSES" -name '*.class' | wc -l | tr -d ' ') class files"

# No custom dylibs are built: the face is pure Java/SWT and the Dock identity
# is applied through public SWT APIs (ui.NativelessIdentity).
