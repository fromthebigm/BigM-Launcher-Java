#!/bin/sh
# run.sh — the ONLY user-facing launcher for the Java-hosted BigM Portable
# launcher. It redirects HOME into the portable folder BEFORE java starts,
# self-heals exec bits/quarantine, detaches from the invoker when appropriate,
# unsets any ambient bundle id, and execs the bundled JDK directly.
# There is no separate inner launcher and no .app executable: this script plus
# the bundled java is the whole entry point.
set -u
ROOT="$(CDPATH= cd "$(dirname "$0")" && pwd -P)"

PORTABLE_HOME="$ROOT/portable-data"
mkdir -p "$PORTABLE_HOME"

# Capture the machine's real home BEFORE the portable redirect. The inner
# (detached) re-invocation arrives with HOME already pointed at portable-data —
# without the guard we'd recompute "real home" as the portable folder and
# clobber BIGM_JAVA_REAL_HOME for keychain lookups.
if [ -z "${BIGM_JAVA_REAL_HOME:-}" ]; then
    REAL_HOME="$(CDPATH= cd "$HOME" 2>/dev/null && pwd -P || printf '%s' "$HOME")"
    export BIGM_JAVA_REAL_HOME="$REAL_HOME"
fi

export HOME="$PORTABLE_HOME"
export CFFIXED_USER_HOME="$PORTABLE_HOME"

# Portable invariant: never fall back to a system Java. Fail loudly before
# detaching so the user sees why nothing launched instead of an empty window.
JAVA="$ROOT/jdk/Contents/Home/bin/java"
if [ ! -x "$JAVA" ]; then
    printf '%s\n' "BigM Launcher: bundled JDK not found at $JAVA." >&2
    printf '%s\n' "This portable app requires its bundled JDK; not using a system Java." >&2
    exit 1
fi

# Self-heal the exec bits + quarantine attrs after any Folder/AirDrop move
# (transfers strip them; without this the bundled runtime answers "unable
# to find a local java runtime").
chmod -f +x "$ROOT"/*.sh 2>/dev/null || true
chmod -f +x "$ROOT/jdk/Contents/Home/bin/"* 2>/dev/null || true
xattr -dr com.apple.quarantine \
  "$ROOT/jdk" "$ROOT/lib" "$ROOT/target" "$ROOT/Resources" \
  "$ROOT"/*.sh "$ROOT"/*.icns "$ROOT"/*.png 2>/dev/null || true

# No ambient bundle identifier: we are a bare java process, not an .app.
unset __CFBundleIdentifier 2>/dev/null || true

# ── Detach from the invoker's lifetime ────────────────────────────────────
# Closing the terminal/parent must NOT close the launcher: double-fork under
# nohup, park stdio on a parked log, and let launchd re-parent us. --foreground
# skips the detach so startup errors stay visible in the terminal.
mkdir -p "$ROOT/Support" 2>/dev/null || true
if [ ! -w "$ROOT/Support" ]; then
    printf '%s\n' "BigM Launcher: cannot write $ROOT/Support (check folder permissions)." >&2
    exit 1
fi

if [ "${1:-}" = "--foreground" ]; then
    shift
elif [ -z "${BIGM_JAVA_DETACHED:-}" ]; then
    LOG="$ROOT/Support/runner.log"
    (
        nohup env BIGM_JAVA_DETACHED=1 "$ROOT/run.sh" "$@" >>"$LOG" 2>&1 &
    )
    exit 0
fi

# -XstartOnFirstThread: SWT/Cocoa requires the AppKit run loop on thread 0.
# Energy: Serial GC + small heap for an idle launcher; downloads stream to disk.
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
    -Xdock:name="Google Chrome" \
    -Xdock:icon="$ROOT/Resources/VoiceMemos.icns" \
    -cp "$ROOT/target/bigm.jar:$ROOT/lib/*" \
    BigMApp "$@"
