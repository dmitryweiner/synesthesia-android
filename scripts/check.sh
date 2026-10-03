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

./gradlew --console=plain \
  :core:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
