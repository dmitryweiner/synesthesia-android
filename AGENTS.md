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
rustup update stable           # CI uses the latest stable; an older clippy misses its lints
scripts/check.sh               # after every change
./gradlew :core:testDebugUnitTest   # the bindings against the real core only
./gradlew connectedDebugAndroidTest # instrumented tests, with a phone or emulator attached
```

A **comment-only change in the core still costs a re-pin**: a new `rev` in
`core/rust/Cargo.toml` rebuilds the Rust side here and in CI, so a wording
fix there is best carried by the next real commit rather than pushed alone.

**Developing against a local core checkout** (not committed — the file is
in `.gitignore`):

```toml
# core/rust/.cargo/config.toml
[patch."https://github.com/dmitryweiner/synesthesia-core"]
syn-ffi = { path = "/path/to/synesthesia-core/syn-ffi" }
```

Remove it, push the core, bump `rev` and run `scripts/check.sh` before
committing here.

## Rules

- **Logic goes into the core, not into Kotlin.** If two apps would need the
  same code (the morph, the explorer, scout scheduling, the points model),
  it belongs in synesthesia-core (`syn-core` or `syn-session`), exposed
  through `syn-ffi`. Kotlin is the device, the screen, storage, lifecycle.
  The shape this takes: a session call answers with effects, and
  `PlaybackController.applyEffects` is the whole of what they mean on Android. A new
  behaviour is a new effect in `syn-session`, with its host test, not a new
  `if` in Kotlin.
- **Changing the core**: commit and push in synesthesia-core (run its
  `scripts/check.sh`), then bump `rev` in `core/rust/Cargo.toml` here and
  run this repo's `scripts/check.sh`. Never vendor or patch the core here.
- **Never re-type** a range, a default, a preset or a shader: read it
  through the core, or copy it with a script (`scripts/sync-shaders.sh`,
  whose `--check` the local `scripts/check.sh` runs).
- **The Rust side is always built in release** — the DSP cannot play in
  real time unoptimized.
- **Realtime discipline**: nothing allocates, locks or logs on the audio
  path, beyond the one `SoundPlayer.render` call per chunk (UniFFI
  allocates the returned buffer); a UI that falls behind loses frames,
  never sound. `PlaybackController` starts a new `AudioOutput` only once the
  stopped one `isDoneWithPlayer` — two threads must never render one player.
- **Measure first**: performance claims come with a number from a bench,
  written into PLAN.md with a date.
- **Break a claim before writing it down.** A line in these docs that says a
  check catches something ("lint fails on a missing translation") is worth
  only the once it was proved: make the mistake on purpose, watch the build
  go red, put it back. The same goes for the premise under a test — read the
  code that makes it true (a 👍 drops the point's name in
  `syn-session`'s `after_action`, so the JSON has no `presetName`) rather
  than remembering that it does. Two claims checked that way in this project
  were wrong, and one of them was already in PLAN.md as a fact.
- **A line the app says is a resource**, never a literal in Kotlin: add it to
  `app/src/main/res/values/strings.xml` and to all three translations (lint's
  `MissingTranslation` is an error, so a forgotten one fails `check.sh`).
  Read it with `stringResource` in a composable — `LocalContext.getString` is
  not configuration-aware and Android lint rejects it — and capture what a
  lambda needs before the lambda. What the core computes is left as the core
  says it.
  - **Hebrew is `values-iw`**, not `values-he` (as Indonesian would be `in`
    and Yiddish `ji`): Android rewrites the modern code to the obsolete one
    before looking a resource up (`ResourcesImpl.adjustLanguageTag`), so a
    `values-he` folder is compiled, listed by `generateLocaleConfig`,
    accepted by lint — and never read. androidx's own Hebrew is under `iw`;
    `aapt2 dump resources` next to a library's string is how to check.
  - **A test tag must not be made of a label.** `testTag("tab$title")` was
    fine until the title became a resource; tags are the app's own names and
    translating one breaks a test in a language nobody reads.
  - **A number in a line is formatted in that language's way** — ru says
    `12,0` — so assert that the number arrived, not how it is written.
- **No build outputs in git.** Every commit on `main` becomes a GitHub
  **release** — `v<version>`, with `synesthesia-<version>.apk` attached
  (`scripts/release-apk.sh`, run by CI) — and the same APK is the workflow
  artifact `app-debug` until it expires. Debug builds are signed with
  `app/debug.keystore` so APKs from any run install over each other; never
  replace that key.
- **The version counts commits**, it is not a number anyone types:
  `versionCode` is `git rev-list --count HEAD` and `versionName` is
  `<releaseLine>.<that count − lineOpenedAt>` (app/build.gradle.kts), so the
  first build of a line is `x.y.0`. Only the line is bumped by hand, and
  `lineOpenedAt` is set to the count the bump commit will have. ⋮ shows the
  version with the core's own. CI checks out with `fetch-depth: 0` for that
  reason — a shallow clone would count its own depth.
- **Where the instrumented tests run**: on a local machine, on an attached
  phone or emulator (`connectedDebugAndroidTest`). In a cloud session there
  is no emulator (no KVM) — there it is the JVM tests, lint and the APK
  build, and the instrumented tests run in CI (`emulator` job, API 26 and
  35), which prints each failing test's trace into the log. Say so when a
  change could only be checked on a device.
- **Things the user checks by hand on a phone** are listed per phase in
  PLAN.md; the APK to install is the newest release (or the `app-debug`
  artifact of that run).

## Module map

```
core/                 Android library: the Rust core + its generated Kotlin
  build.gradle.kts    the cargo tasks and their wiring (see README)
  rust/               cargo workspace: syn-android (cdylib), uniffi-bindgen
  src/test/           JVM tests that call the real core through the bindings
app/                  the application (Compose)
  SynesthesiaApp      holds the one PlaybackController
  audio/              AudioOutput (AudioTrack + its thread), PlayedClock
  playback/           PlaybackController — the core's Session plus what it
                      has none of: a clock (nanoTime as `now`), a 25 ms tick
                      while `wantsTick`, a thread for the scout, audio focus,
                      noisy, the wake lock;
                      PlaybackService (foreground, MediaSession, notification
                      with 👎 ⏹/▶ 👍)
  gl/                 the picture: Program (shaders → passes), Targets
                      (RG16F ping-pong), SimRenderer (the seven passes),
                      PictureView (the GL surface, the rung, the finger),
                      CpuPictureView (the fallback: the core draws, this blits)
  store/              AppFiles: last-point.json with last-name.txt, plus
                      points.json, view.txt, welcome.txt — written atomically
                      off the main thread
  res/values*/        every line the app says itself, in en/ru/he/uk; what
                      the core computes is not translated (PLAN.md phase 6)
  ui/                 PlayerScreen (the picture, the presses, the status),
                      Picture (the view in Compose), Points (the sheet, 💾,
                      the token), SettingsScreen (generated from the schema),
                      Details (what the point is, what changed) and help,
                      Meters (the spectrogram and the feature bars)
app/debug.keystore    the shared debug key; never replace it
scripts/              check.sh, setup-android-sdk.sh, sync-shaders.sh,
                      release-apk.sh, android-test-failures.sh (CI)
gradle/libs.versions.toml   every version, the NDK and the SDK levels
.github/workflows/check.yml check + emulator (API 26, 35); the APK artifact
```
