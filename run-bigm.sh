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
# --enable-native-access: FFM bridge (DockIdentity) on JDK 22+.
# Energy: the launcher idles most of its life — Serial GC + a small heap
# keep idle threads, GC concurrency, and memory pressure (and therefore
# memory-compression + swap churn) low on machines that leave it open
# all day. Downloads stream straight to disk; nothing here needs a big heap.
exec "$JAVA" \
    -XstartOnFirstThread \
    --enable-native-access=ALL-UNNAMED \
    -XX:+UseSerialGC \
    -Xms32m \
    -Xmx256m \
    -XX:MaxHeapFreeRatio=30 \
    -XX:MinHeapFreeRatio=10 \
    -XX:ErrorFile="$ROOT/portable-data/profile/hs_err_pid%p.log" \
    -Dbigm.root="$ROOT" \
    -Xdock:icon="$ROOT/Resources/VoiceMemos.icns" \
    -cp "$ROOT/target/bigm.jar:$ROOT/lib/*" \
    BigMApp "$@"
