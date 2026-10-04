#!/bin/sh
# Copies the picture's shaders from the web app, which is their only source
# (PLAN.md: never re-type a shader). They are GLSL ES 3.00 already —
# `#version 300 es`, texelFetch, float targets — so they run on OpenGL ES 3.0
# here verbatim, and a diff against ../synesthesia is the whole of what keeps
# the two pictures the same picture.
#
#   scripts/sync-shaders.sh          copy, and say what changed
#   scripts/sync-shaders.sh --check  fail if a copy has drifted (CI)
set -e
cd "$(dirname "$0")/.."

WEB=${SYNESTHESIA_WEB:-../synesthesia}
FROM="$WEB/src/sim/shaders"
TO=app/src/main/assets/shaders

if [ ! -d "$FROM" ]; then
  echo "sync-shaders: no web app at $WEB (set SYNESTHESIA_WEB)" >&2
  exit 1
fi

mkdir -p "$TO"
status=0
for f in common.glsl seed.frag react.frag paramfield.frag velocity.frag advect.frag inject.frag display.frag; do
  if [ ! -f "$FROM/$f" ]; then
    echo "sync-shaders: $FROM/$f is missing" >&2
    exit 1
  fi
  if [ "$1" = "--check" ]; then
    if ! diff -q "$FROM/$f" "$TO/$f" >/dev/null 2>&1; then
      echo "sync-shaders: $f has drifted from the web app:"
      diff -u "$TO/$f" "$FROM/$f" || true
      status=1
    fi
  else
    if diff -q "$FROM/$f" "$TO/$f" >/dev/null 2>&1; then
      echo "   same  $f"
    else
      cp "$FROM/$f" "$TO/$f"
      echo " copied  $f"
    fi
  fi
done
exit $status
