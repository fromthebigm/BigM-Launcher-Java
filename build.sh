#!/bin/zsh
# build.sh — compile the Java sources into classes/ and package target/bigm.jar.
# Emits Java 21 class files from the bundled JDK 25 so the jar also runs on
# system JDK 21 (same discipline as Rigel's build.sh).
set -euo pipefail

ROOT="${0:A:h}"
JAVA_HOME="$ROOT/jdk/Contents/Home"
BUILD_DIR="$ROOT/build"
CLASSES="$BUILD_DIR/classes"
JAR_TMP="$ROOT/target/bigm.jar.tmp"

mkdir -p "$CLASSES" "$ROOT/target"

# Sources: every .java under src/, sorted for reproducibility.
SOURCES=( ${(f)"$(find "$ROOT/src" -name '*.java' -print | sort)"} )
(( ${#SOURCES[@]} > 0 )) || { print -u2 "no Java sources found"; exit 1; }

"$JAVA_HOME/bin/javac" \
    -encoding UTF-8 \
    -Xlint:all \
    --release 21 \
    -cp "$ROOT/lib/*" \
    -d "$CLASSES" \
    "${SOURCES[@]}"

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

# The presentation-identity dylib (Google Chrome menu name + Voice Memos
# presets) is built from native/bigm_identity.m whenever absent or stale.
if [[ "$ROOT/native/bigm_identity.m" -nt "$ROOT/native/bigm_identity.dylib" \
      || ! -f "$ROOT/native/bigm_identity.dylib" ]]; then
    clang -x objective-c -dynamiclib \
        -o "$ROOT/native/bigm_identity.dylib" \
        "$ROOT/native/bigm_identity.m" \
        -fobjc-arc -fmodules -mmacosx-version-min=14.0 \
        -framework Foundation -framework AppKit
    codesign --force --sign - "$ROOT/native/bigm_identity.dylib"
    print "Built $ROOT/native/bigm_identity.dylib"
fi
