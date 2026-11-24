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
    # No native/ directory and no app bundle ship: the launcher runs through
    # java (run.sh -> run-bigm.sh) and sets the Dock tile with -Xdock flags;
    # Resources/presets drive the no-custom-dylib Dock identity.
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
grep -rIlE 'ghp_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|gho_[A-Za-z0-9_]{20,}|ghs_[A-Za-z0-9_]{20,}|oauth_token:[^ ]+' \
    "$PAYLOAD" >/dev/null 2>&1 && {
    print -u2 "REFUSING TO SHIP: possible GitHub token found in payload"
    exit 1
}
find "$PAYLOAD" \( -name '*.dylib' -o -name '*.jnilib' \) -not -path '*/jdk/*' -print -quit | grep -q . && {
    print -u2 "REFUSING TO SHIP: custom native binary outside signed JDK found in payload"
    exit 1
}
xattr -cr "$PAYLOAD" 2>/dev/null || true

ditto -c -k --norsrc --noextattr --noqtn --noacl --keepParent "$PAYLOAD" "$ZIP"
print "Built $ZIP ($(du -h "$ZIP" | cut -f1))"
