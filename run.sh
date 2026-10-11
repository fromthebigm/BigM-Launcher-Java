#!/bin/sh
# run.sh — user-facing launcher for the Java-hosted BigM Portable launcher.
# Redirects HOME into the portable folder BEFORE java starts (caches, SWT
# state, and any JNA/JVM file activity stay inside the package), then execs
# the pristine launcher. BIGM_JAVA_REAL_HOME preserves the real home for
# anything that legitimately wants the outside machine (the login keychain
# reads are process-level, not HOME-based).
set -u
ROOT="$(CDPATH= cd "$(dirname "$0")" && pwd -P)"

REAL_HOME="$(CDPATH= cd "$HOME" 2>/dev/null && pwd -P || printf '%s' "$HOME")"
PORTABLE_HOME="$ROOT/portable-data"
mkdir -p "$PORTABLE_HOME"

export BIGM_JAVA_REAL_HOME="$REAL_HOME"
export HOME="$PORTABLE_HOME"
export CFFIXED_USER_HOME="$PORTABLE_HOME"

exec "$ROOT/run-bigm.sh" "$@"
