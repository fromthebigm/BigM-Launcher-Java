#!/bin/sh
# run.sh — user-facing launcher for the Java-hosted BigM Portable launcher.
# Redirects HOME into the portable folder BEFORE java starts (caches, SWT
# state, and any JVM file activity stay inside the package), then execs the
# pristine launcher. BIGM_JAVA_REAL_HOME preserves the real home for
# anything that legitimately wants the outside machine (the login keychain
# reads are process-level, not HOME-based).
set -u
ROOT="$(CDPATH= cd "$(dirname "$0")" && pwd -P)"

PORTABLE_HOME="$ROOT/portable-data"
mkdir -p "$PORTABLE_HOME"

# Capture the machine's real home BEFORE the portable redirect. The inner
# (detached) re-invocation arrives with HOME already pointed at
# portable-data — without the guard we'd recompute "real home" as the
# portable folder and clobber BIGM_JAVA_REAL_HOME for keychain lookups.
if [ -z "${BIGM_JAVA_REAL_HOME:-}" ]; then
    REAL_HOME="$(CDPATH= cd "$HOME" 2>/dev/null && pwd -P || printf '%s' "$HOME")"
    export BIGM_JAVA_REAL_HOME="$REAL_HOME"
fi

export HOME="$PORTABLE_HOME"
export CFFIXED_USER_HOME="$PORTABLE_HOME"

# Portable invariant: never fall back to a system Java. Fail loudly before
# detaching so the user sees why nothing launched instead of an empty window.
if [ ! -x "$ROOT/jdk/Contents/Home/bin/java" ]; then
    printf '%s\n' "BigM Launcher: bundled JDK not found at $ROOT/jdk/Contents/Home/bin/java." >&2
    printf '%s\n' "This portable app requires its bundled JDK; not using a system Java." >&2
    exit 1
fi

# Self-heal the exec bits + quarantine attrs after any Folder/AirDrop move
# (transfers strip them; without this the bundled runtime answers "unable
# to find a local java runtime" — same discipline as Rigel's launchers).
chmod -f +x "$ROOT"/*.sh 2>/dev/null || true
chmod -f +x "$ROOT/jdk/Contents/Home/bin/"* 2>/dev/null || true
xattr -dr com.apple.quarantine "$ROOT" 2>/dev/null || true

# ── Detach from the invoker's lifetime ────────────────────────────────────
# When Blendt (or a terminal, or any parent) starts BigM, closing that
# parent must NOT close the launcher: double-fork under nohup, park stdio
# on a parked log, and let launchd re-parent us. The launcher owns its own
# lifetime; --ui-smoke/--self-test style runs keep this too (they finish
# on their own). BIGM_JAVA_DETACHED already-set means we're the inner copy.
if [ -z "${BIGM_JAVA_DETACHED:-}" ]; then
    mkdir -p "$ROOT/Support"
    LOG="$ROOT/Support/runner.log"
    (
        nohup env BIGM_JAVA_DETACHED=1 "$ROOT/run.sh" "$@" >>"$LOG" 2>&1 &
    )
    exit 0
fi

exec "$ROOT/run-bigm.sh" "$@"
