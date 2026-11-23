#!/bin/zsh
# aitest.sh — BigM edge-case harness. Default is headless-safe: build,
# self-test, ticket-e2e, static gates. Pass --gui to also construct the SWT
# window and run ui-audit/action-e2e with capture protection disabled.
set -euo pipefail

ROOT="${0:A:h}"
cd "$ROOT"

GUI=0
[[ "${1:-}" == "--gui" ]] && GUI=1

echo "[aitest] build"
./build.sh >/tmp/bigm-aitest-build.log 2>&1
tail -3 /tmp/bigm-aitest-build.log

echo "[aitest] self-test"
./run-bigm.sh --self-test

echo "[aitest] ticket-e2e"
./run-bigm.sh --ticket-e2e

echo "[aitest] static gates"
# Identity must be BigM, never a copied browser name.
if grep -RIn --exclude-dir=portable-data --exclude-dir=build --exclude-dir=target --exclude-dir=dist --exclude-dir=.git 'Google Chrome' \
    src run.sh run-bigm.sh BigM.app 2>/dev/null; then
  echo "FAIL: BigM identity still contains 'Google Chrome'" >&2
  exit 1
fi
# No live secrets in sources or packaged resources.
if grep -RInE --exclude-dir=portable-data --exclude-dir=build --exclude-dir=target --exclude-dir=dist --exclude-dir=.git \
    -e 'ghp_[A-Za-z0-9_]{20,}' -e 'github_pat_[A-Za-z0-9_]{20,}' -e 'gho_[A-Za-z0-9_]{20,}' \
    -e 'ghs_[A-Za-z0-9_]{20,}' -e 'oauth_token:[^ ]+' \
    src Resources run.sh run-bigm.sh BigM.app 2>/dev/null; then
  echo "FAIL: possible secret in sources/resources" >&2
  exit 1
fi
# No custom native binaries may be loaded by the packaged face. Allow only
# Apple's own system frameworks (libobjc/CoreGraphics/SkyLight/etc.)
# reached via FFM; fail on System.load/loadLibrary or a lookup of a shipped
# binary path.
if grep -RInE --exclude-dir=portable-data --exclude-dir=build --exclude-dir=target --exclude-dir=dist --exclude-dir=.git \
    -e 'System\.load\(' -e 'System\.loadLibrary\(' -e 'libraryLookup\("[^"]+\.dylib' \
    src run.sh run-bigm.sh 2>/dev/null | grep -v '/usr/lib/libobjc.A.dylib' \
    | grep -vE '/System/Library|/usr/lib/'; then
  echo "FAIL: custom native binary load found" >&2
  exit 1
fi

if [[ "$GUI" == "1" ]]; then
  echo "[aitest] gui ui-audit (capture diagnostic mode)"
  BIGM_DIAGNOSTIC_CAPTURE=1 timeout 120 ./run-bigm.sh --ui-audit || { echo "FAIL: ui-audit" >&2; exit 1; }
  echo "[aitest] gui action-e2e"
  BIGM_DIAGNOSTIC_CAPTURE=1 timeout 120 ./run-bigm.sh --action-e2e || { echo "FAIL: action-e2e" >&2; exit 1; }
  echo "[aitest] gui screenshots"
  BIGM_DIAGNOSTIC_CAPTURE=1 timeout 120 ./run-bigm.sh --shot /tmp/bigm-aitest-shot.png || true
  BIGM_DIAGNOSTIC_CAPTURE=1 timeout 120 ./run-bigm.sh --sheet-shot /tmp/bigm-aitest-sheet.png || true
fi

echo "AITEST PASS"
