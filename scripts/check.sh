#!/bin/sh
# Run after every change: the Rust side (format, lints), then the Android
# side (the bindings against the real core, Android lint, the debug APK).
# The core's own checks live in synesthesia-core (its scripts/check.sh).
set -e
cd "$(dirname "$0")/.."

(
  cd core/rust
  cargo fmt --check
  cargo clippy --all-targets -- -D warnings
)

# The shaders are the web app's, copied (PLAN.md: never re-type a shader).
# Only a machine that has the web app next door can tell whether a copy has
# drifted; CI has this repository alone, so there it is nothing to check.
if [ -d "${SYNESTHESIA_WEB:-../synesthesia}/src/sim/shaders" ]; then
  scripts/sync-shaders.sh --check
fi

./gradlew --console=plain \
  :core:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
