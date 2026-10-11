#!/bin/sh
# run-bigm.sh — pristine launcher: bundled JDK, no system proxy inheritance,
# no ambient bundle identifier. Called by run.sh (the user-facing wrapper).
set -u
ROOT="$(CDPATH= cd "$(dirname "$0")" && pwd -P)"

unset __CFBundleIdentifier 2>/dev/null || true

JAVA="$ROOT/jdk/Contents/Home/bin/java"
if [ ! -x "$JAVA" ] && [ -x "$(command -v java 2>/dev/null)" ]; then
    JAVA="$(command -v java)"
fi

# -XstartOnFirstThread: SWT/Cocoa requires the AppKit run loop on thread 0.
# --enable-native-access: JNA on JDK 21+.
exec "$JAVA" \
    -XstartOnFirstThread \
    --enable-native-access=ALL-UNNAMED \
    -XX:ErrorFile="$ROOT/portable-data/profile/hs_err_pid%p.log" \
    -Dbigm.root="$ROOT" \
    -Xdock:icon="$ROOT/Resources/VoiceMemos.icns" \
    -cp "$ROOT/target/bigm.jar:$ROOT/lib/*" \
    BigMApp "$@"
