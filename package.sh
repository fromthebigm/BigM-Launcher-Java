#!/bin/zsh
# package.sh — build the release zip: runtime-only payload, real JDK copy
# (the dev jdk symlink is dereferenced), empty Support/, no portable-data
# content, no product key or activation state anywhere.
set -euo pipefail

ROOT="${0:A:h}"
TAG="${1:-portable-$(date -u '+%Y.%m.%d')}"
TODAY="$(date -u '+%Y-%m-%d')"

"$ROOT/build.sh"

DIST="$ROOT/dist"
PAYLOAD="$DIST/BigM-Launcher-Java"
ZIP="$DIST/BigM-Launcher-Java-${TAG}-macOS-arm64.zip"

rm -rf "$PAYLOAD" "$ZIP"
mkdir -p "$PAYLOAD/portable-data/profile" "$PAYLOAD/Support"

runtime() (
    set -e
    cp -R "$ROOT/run.sh" \
          "$ROOT/run-bigm.sh" \
          "$ROOT/target" \
          "$ROOT/lib" \
          "$ROOT/Resources" \
          "$PAYLOAD/"
    # The identity dylib ships WITHOUT its source (port-zip rule: loader
    # sources and the shared header are compiled in, never readable shippables).
    mkdir -p "$PAYLOAD/native"
    cp "$ROOT/native/bigm_identity.dylib" "$PAYLOAD/native/"
    # The dev symlink must become a real copy in the payload.
    if [[ -L "$ROOT/jdk" ]]; then
        rm -rf "$PAYLOAD/jdk-copy-pending" 2>/dev/null
        cp -RL "$ROOT/jdk" "$PAYLOAD/jdk"
    else
        cp -R "$ROOT/jdk" "$PAYLOAD/jdk"
    fi
    printf '%s\n%s\n' "$TAG" "$TODAY" > "$PAYLOAD/version.txt"
)

runtime

# Never ship: sources, tests, build outputs, user data, keys, activation
# state, caches, editor junk, the build script itself.
find "$PAYLOAD" -name '.DS_Store' -delete
find "$PAYLOAD" -name '*.dSYM' -prune -exec rm -rf {} + 2>/dev/null || true
rm -rf "$PAYLOAD/build" "$PAYLOAD/build.sh" "$PAYLOAD/portable-data"/* 2>/dev/null || true
# Defensive: the product key is invited at first run and must never travel.
grep -rl "436#2f4w9abg" "$PAYLOAD" >/dev/null 2>&1 && {
    print -u2 "REFUSING TO SHIP: product key found in payload"
    exit 1
}
xattr -cr "$PAYLOAD" 2>/dev/null || true

ditto -c -k --norsrc --noextattr --noqtn --noacl --keepParent "$PAYLOAD" "$ZIP"
print "Built $ZIP ($(du -h "$ZIP" | cut -f1))"
