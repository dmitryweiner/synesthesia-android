# AGENTS.md — project map and working rules

(Claude Code reads CLAUDE.md, which points here; keep everything in this one
file so the two never drift apart.)

The Android app of Synesthesia. The model is the shared Rust core,
synesthesia-core, pinned by revision; this repository is everything that
is Android. Decisions agreed with the user live in **PLAN.md** — read it
before changing behaviour. Docs, UI strings and code comments are in
English; the user talks to agents in Russian.

## Commands

```bash
scripts/setup-android-sdk.sh   # a fresh machine or cloud session: SDK, NDK, Rust targets, cargo-ndk
scripts/check.sh               # after every change
./gradlew :core:testDebugUnitTest   # the bindings against the real core only
```

## Rules

- **Logic goes into the core, not into Kotlin.** If two apps would need the
  same code (the morph, the explorer, scout scheduling, the points model),
  it belongs in synesthesia-core (`syn-core` or `syn-session`), exposed
  through `syn-ffi`. Kotlin is the device, the screen, storage, lifecycle.
- **Changing the core**: commit and push in synesthesia-core (run its
  `scripts/check.sh`), then bump `rev` in `core/rust/Cargo.toml` here and
  run this repo's `scripts/check.sh`. Never vendor or patch the core here.
- **Never re-type** a range, a default, a preset or a shader: read it
  through the core, or copy it with a script.
- **The Rust side is always built in release** — the DSP cannot play in
  real time unoptimized.
- **Realtime discipline**: nothing allocates, locks or logs on the audio
  path; a UI that falls behind loses frames, never sound.
- **Measure first**: performance claims come with a number from a bench,
  written into PLAN.md with a date.
- There is no emulator in the cloud sessions (no KVM): what runs there is
  the JVM tests, lint and the APK build. Say so when a change could only be
  checked on a device.

## Module map

```
core/                 Android library: the Rust core + its generated Kotlin
  build.gradle.kts    the cargo tasks and their wiring (see README)
  rust/               cargo workspace: syn-android (cdylib), uniffi-bindgen
  src/test/           JVM tests that call the real core through the bindings
app/                  the application (Compose)
scripts/              check.sh, setup-android-sdk.sh
gradle/libs.versions.toml   every version, the NDK and the SDK levels
```
