#!/bin/sh
# Publishes the debug APK that `scripts/check.sh` just built as a GitHub
# release named by its version — so every commit on main has a build anyone
# can download, at a permanent URL, rather than a workflow artifact that
# expires (PLAN.md: no build outputs in git; a release is not git).
#
# The version is not computed here: it is read from what the build wrote
# (`output-metadata.json`), so the APK and its release cannot disagree.
#
#   scripts/release-apk.sh         # needs gh, authenticated (CI: GH_TOKEN)
set -e
cd "$(dirname "$0")/.."

META=app/build/outputs/apk/debug/output-metadata.json
APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "$META" ] || { echo "release-apk: no $META — run scripts/check.sh first" >&2; exit 1; }
[ -f "$APK" ] || { echo "release-apk: no $APK" >&2; exit 1; }

VERSION=$(python3 -c "import json,sys;print(json.load(open('$META'))['elements'][0]['versionName'])")
TAG="v$VERSION"
NAMED="synesthesia-$VERSION.apk"
CORE=$(sed -n 's/.*rev = "\(.......\).*/\1/p' core/rust/Cargo.toml | head -1)
SUBJECT=$(git log -1 --pretty=%s)

cp "$APK" "$NAMED"
NOTES=$(cat <<TEXT
$SUBJECT

A debug build of $(git rev-parse --short HEAD), on synesthesia-core \`$CORE\`.
Signed with the shared debug key (\`app/debug.keystore\`), so it installs over
any other build from this project; arm64 phones and the x86_64 emulator,
Android 8.0+.
TEXT
)

# A re-run of the same commit replaces the file rather than failing.
if gh release view "$TAG" >/dev/null 2>&1; then
  gh release upload "$TAG" "$NAMED" --clobber
  gh release edit "$TAG" --notes "$NOTES"
else
  gh release create "$TAG" "$NAMED" \
    --title "$TAG" \
    --notes "$NOTES" \
    --target "$(git rev-parse HEAD)"
fi
rm -f "$NAMED"
echo "released $TAG"
