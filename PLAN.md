# Synesthesia for Android — plan & decisions

*Status: the plan was agreed with the user on 2026-10-03. Phases 0 and 1
are done (2026-10-03; phase 1's numbers from a phone are still to be taken).
Phase 2 is next.*

The third home of [synesthesia](https://github.com/dmitryweiner/synesthesia):
one point in a ~500-gene space makes sound (21 formula generators, an FX
chain, 4 shared LFOs) and a picture (Gray–Scott reaction-diffusion driven by
the sound), and 👍/👎 steer the search. The web app is the spec; the
[Rust console port](https://github.com/dmitryweiner/synesthesia-rust) is the
verified model. This document says how the Android app is built so that the
next one (Swift) is a shell, not a fourth port.

Docs, UI strings and code comments are in English; the user talks to agents
in Russian — the same rule as the sibling projects.

## What the two existing ports teach

| | web (TypeScript) | console (Rust) |
|---|---|---|
| model (DSP, genome, state, sim) | `src/dsp`, `src/genome`, `src/state`, shaders | `syn-core`: no I/O, no threads, deterministic; 21 generators bit-exact with the TS (63 golden takes), genome identical to 1e-16, CPU picture |
| control logic (morph, 👍/👎/🎲/↩, scout scheduling, points, status) | `main.ts`, 1 075 lines | `syn-app/main.rs`, 888 lines |
| platform (audio device, screen, storage, input) | Web Audio, WebGL2, localStorage, DOM | pw-cat pipe, ratatui, XDG files, crossterm |

The model was ported once and is tested against the original. The **control
logic was written twice**, by hand, from the same `main.ts` — and a Swift app
would write it a third time. That is the layer this project fixes: it moves
into the core as a platform-free `Session`, and every app becomes
*device + screen + storage* around it.

## Decisions

Numbered so later docs can cite them. Each agreed decision carries the date.

1. **The core is the existing Rust `syn-core`, reached from Kotlin through
   UniFFI** *(agreed 2026-10-03)*. Not a Kotlin re-port of the DSP. Reasons:
   - `syn-core` is already the thing a portable core should be: no I/O, no
     threads (the scout's `rayon` pool is handed in by the caller), serde
     JSON that is byte-compatible with the web app, 63 golden takes and the
     genome test. A Kotlin port would have to earn all of that again
     (~10 000 lines, the `check.sh` suite, the parity scripts).
   - It compiles unchanged for `aarch64-linux-android` and
     `aarch64-apple-ios`. UniFFI generates the Kotlin **and the Swift**
     bindings from one interface file, so the iOS app gets the same core and
     the same `Session` for the cost of a build step.
   - Per-sample DSP in Rust on a phone costs what it costs on the A76 in the
     console port (≤ 15% of a big core for the heaviest preset); the same code
     in Kotlin/ART or Kotlin/Native would be slower and shares the heap with
     the UI's garbage collector.
   - What stays Kotlin is everything that is Android: the audio device, the
     GL renderer, storage, links, lifecycle, and the whole UI in Compose. That
     is the honest size of "the Android app".
   - Alternatives weighed: **Kotlin Multiplatform core** (one language on
     Android, Swift consumes an XCFramework; but a full re-port, no golden
     parity until re-earned, slower DSP, GC on the audio path) and **pure
     Kotlin core module, re-ported to Swift later** (simplest build, but the
     Swift app is a third hand port). Either is possible if the user prefers
     Kotlin for the DSP; the phases below stay the same, only phase 1 grows.
2. **The core lives in its own repository, `synesthesia-core`** *(agreed
   2026-10-03)*. It holds `syn-core` (moved there from `synesthesia-rust`
   together with `assets/` and `golden/`, which its tests and `include_str!`
   read), the new `syn-player`, `syn-session` and `syn-ffi` crates,
   `scripts/check.sh` and the dump scripts. Changes to the model are committed there; this
   repository depends on it as a **cargo git dependency pinned to a
   revision**, bumped on purpose. `synesthesia-rust` is expected to switch
   to the same dependency afterwards, so the console and the phone run one
   core — that change is made in that repository, not here.
   - **The control logic moves into the core as `Session`.** `syn-session`
     holds what `main.ts` and `main.rs` both implement: the explorer plus the
     2 s morph (`from, to, started`), step count, undo depth, scout
     scheduling (the 800 ms settle, the version check, picking the best
     candidate on a press), 🎲 near a preset, load/restore, the points list
     model, the status text. It is pure: `tick(now)` and commands in,
     effects out (`PlayState`, `SwitchTo`, `Reseed`, `SaveLastPoint`,
     `StartScout`, `Status`). No threads, no clock of its own, no files —
     the app supplies those. Tests run on the host and pin the behaviour
     `main.ts` has (a press mid-morph starts from what is audible; a load is
     a hard switch and a reseed; Settings closes as one undoable step).
   - `syn-player` is the render side every app's audio thread needs (built
     in phase 1): the engine behind a command queue, PCM out in any chunk
     size, fades, feature frames on the played clock.
   - `syn-ffi` is the UniFFI surface: functions and records (presets, the
     schema, tokens) and objects (`SoundPlayer` now; the session and the CPU
     picture as their phases come). It is a plain Rust library: each app
     links it into its own native library — a `cdylib` here
     (`syn-android`), a `staticlib` for iOS — and generates its bindings
     from that.
3. **The point is the same point.** `AppState` v1 JSON, unchanged; the 12
   presets, the schema (ranges, defaults, labels, gene list) come from
   `syn-core`'s dumps and are **read through the core**, never re-typed in
   Kotlin. The Settings page is generated from `schema()`.
4. **The picture is the web app's GPU picture.** The seven passes are
   GLSL ES 3.00 already (`#version 300 es`, `texelFetch`, float targets);
   on Android they run on OpenGL ES 3.0 verbatim from `GLSurfaceView` /
   `SurfaceView` + EGL. The per-frame inputs (cards through LFOs, through
   the couplings, display effects, ripples) come from `syn_core::sim::frame`,
   so the Kotlin renderer is uniforms + ping-pong + seven draw calls.
   - **The CPU `Picture` from `syn-core` is the fallback and the reference**:
     a device without `GL_EXT_color_buffer_float` draws it into a texture;
     and a test renders the same seeded field on both paths and compares.
   - The quality ladder and boot probe (`src/sim/quality.ts`, pure) move into
     the core so every platform measures the same way.
   - For iOS later: the same seven shaders in Metal (~300 lines of GLSL), or
     `wgpu` in Rust for both platforms — decided then, by measurement; the
     renderer boundary (`Visualizer`-shaped: step on the sim clock, draw on
     the frame clock) is the same either way.
5. **Audio output: measured first, simplest first.** v1 is a Kotlin thread
   that pulls PCM from the core (`SoundPlayer.render(frames)`: f32
   little-endian bytes, 1 024 frames a call, into a 250 ms `AudioTrack`
   buffer) and blocks in `AudioTrack.write` — the pacing model the console's
   `PipeSink` proved. `AudioTrack.getUnderrunCount()` is the bench. If it
   underruns under load (scout running, screen rotating), the audio thread
   moves into Rust on AAudio (`ndk` crate, `audio` feature; no C++ build)
   and Kotlin only starts and stops it. The feature frames (loudness, swell,
   brightness, bands, onset, hits, spectrum) are published from the render
   loop by `syn-player`, the console's `Frame` moved into the core.
6. **Realtime discipline carries over.** Nothing allocates or locks on the
   render path beyond the one FFI call per chunk (which allocates the
   returned buffer — the price of UniFFI, paid outside the engine); a UI
   that falls behind loses frames, never sound. The scout runs on its own rayon pool of
   `cores − 2` threads (the console's fix for xruns), on the little cores
   where the scheduler puts it; its render length and rate are settings,
   because a phone pays in battery for 7 × 30 s renders per press.
7. **Storage is the web app's JSON in the app's files.** `last-point.json`
   and `points.json` in `filesDir`, settings in DataStore. A point file from
   the console opens here and the other way round.
8. **No sharing and no network** *(agreed 2026-10-03)* — the console's
   decision 9. Points are kept locally under the name the user types; a
   point can be exported as the web app's `#s=` token and a token pasted
   from a browser link can be imported (clipboard, and the web app's URL
   via an intent filter so a shared link opens in the app). No Worker, no
   HTTP client, no point ids.
9. **Jetpack Compose, single Activity.** Screens: the picture full-screen
   with the 👎 👍 🎲 ↩ bar and a status line; a top bar with the point's name,
   ▶ sound, 💾, a token export/import, ⚙, ?; Points (bottom sheet: *My
   points*, built-in);
   Details; Settings (two tabs, Audio / Video, generated from the schema).
   Touch on the picture paints growth and a ripple, sampled once per frame
   as the web does. `FLAG_KEEP_SCREEN_ON` while the picture is on screen.
10. **Sound keeps playing with the screen off** *(agreed 2026-10-03; the
    reason the app exists)*. The sound (and, from phase 2, the `Session`)
    lives in one `PlaybackController` per process; a **foreground service**
    with a media-style notification (▶/⏹, from phase 2 also 👍, 👎) keeps
    the process in front while it plays, started on ▶ and ended on ⏹. The
    Activity and the service both talk to the controller; the Activity is
    only a view. (As built in phase 1 — simpler than binding the Activity
    to the service, with the same lifetime.) The picture runs only while the Activity is visible; the LFO
    clock is the audio clock, so the picture rejoins in sync. Audio focus
    is requested (pause on a call, resume after) and headphone media keys
    map to ▶/⏹. This is part of phase 1, not polish.
11. **minSdk 26 (Android 8.0), OpenGL ES 3.0 required, arm64-v8a first**
    (plus x86_64 for the emulator; armeabi-v7a only if asked). The APK
    carries exactly these two ABIs (`abiFilters`), so a device the core is
    not built for cannot install it. compileSdk and targetSdk are 37: the
    Compose libraries of BOM 2026.09 refuse to build against less.
12. **Measure first** — carried over verbatim. Every performance claim comes
    from a checked-in bench: render speed per preset on the device,
    underruns, GL frame time per rung, scout wall time and energy. CI runs
    `cargo test` for the core crates and the JVM tests; the device numbers
    are written down here with a date.

## Architecture

```
 Kotlin (Android)                          Rust (portable, synesthesia-core)
 ┌────────────────────────────┐            ┌──────────────────────────────┐
 │ Compose UI                 │  UniFFI    │ syn-ffi: functions, records, │
 │  player · points · details │ ◄────────► │  SoundPlayer, (Session,      │
 │  settings (from schema)    │            │  Picture as they come)       │
 ├────────────────────────────┤            │ syn-player: commands → PCM,  │
 │ PlaybackController ────────┼── render ─►│  fades, frames on the clock  │
 │  AudioOutput (AudioTrack)  │            │ syn-session: Session (pure)  │
 │  PlaybackService (fg, MS)  │            │  (phase 2)                   │
 │ GLES 3.0 renderer ◄────────┼── frame ───│ syn-core: dsp · fx · engine ·│
 │  7 passes, shaders verbatim│   params   │  features · genome · scout · │
 │ files / DataStore / links  │            │  sim · state                 │
 └────────────────────────────┘            └──────────────────────────────┘
   linked as libsyn_android.so (core/rust/syn-android, pinned by `rev`)
   later: Swift + Metal / AVAudioEngine around the same syn-ffi
```

Repository layout — `synesthesia-core`:

```
Cargo.toml            workspace: syn-core, syn-player, syn-ffi (+ syn-session)
syn-core/             the model, moved from synesthesia-rust
assets/  golden/      its dumps and reference takes, moved with it
syn-player/           the live render side (phase 1)
syn-session/          the control logic, host tests (phase 2)
syn-ffi/              the UniFFI surface (decision 2)
scripts/check.sh      fmt + clippy + tests; dump-*.mjs against ../synesthesia
TODO.md               agreed follow-ups (the point's name spelling)
```

Repository layout — this one (as of phase 1; the later phases add what
is marked):

```
core/                 Android library module: the Rust core + its Kotlin
  build.gradle.kts    cargoBuildAndroid (cargo-ndk → jniLibs), cargoBuildHost
                      (for the JVM tests), uniffiBindings (→ Kotlin)
  rust/Cargo.toml     cargo workspace; pins synesthesia-core by `rev`
  rust/syn-android/   the cdylib the app loads (libsyn_android.so)
  rust/uniffi-bindgen/ the generator, calling syn-ffi's own, so the
                      bindings always match the scaffolding
  src/test/           JVM tests calling the real core through the bindings
  src/androidTest/    the same on a device or emulator
app/                  the application (Kotlin, Compose)
  src/main/.../audio     AudioOutput (AudioTrack + thread), PlayedClock
  src/main/.../playback  PlaybackController, PlaybackService
  src/main/.../ui        PlayerScreen, Meters, BenchScreen
  src/main/.../bench     Bench (offline render speed per preset)
  src/main/.../gl        EGL, ping-pong targets, the seven passes  (phase 3)
  src/main/.../store     last point, points, settings, tokens      (phase 4)
  src/main/assets/shaders/  copied verbatim from the web app       (phase 3)
  debug.keystore      the shared debug key (debug APKs install over each other)
scripts/
  check.sh            rustfmt + clippy on core/rust; JVM tests, lint, APK
  setup-android-sdk.sh  SDK, NDK, Rust targets, cargo-ndk, local.properties
  android-test-failures.sh  failing instrumented tests with their traces (CI)
  sync-shaders.sh     re-copies the shaders from ../synesthesia    (phase 3)
.github/workflows/check.yml  check (as scripts/check.sh) + emulator (API 26, 35)
```

`syn-android` is a thin `cdylib` crate whose only dependency is `syn-ffi`
from `synesthesia-core`, pinned to a revision. Nothing from the core is
copied into this repository.

## Phases

0. **Scaffold.** `synesthesia-core` populated (syn-core + assets + golden
   moved, `check.sh` green there); `syn-ffi` with a first surface (presets,
   schema); here: Gradle project (AGP, Kotlin, Compose), `core/syn-android`,
   `cargo-ndk` builds the `.so` for arm64-v8a and x86_64 into `jniLibs`,
   UniFFI generates the Kotlin, `scripts/check.sh`. Proof: a JVM test lists
   the 12 presets through the generated bindings against a host build of
   the library, and the app does the same on an emulator.
   **Done 2026-10-03.** The cloud machine has no KVM, so the emulator runs
   in CI instead (the `emulator` job, API 26 and 35): an instrumented test
   loads the core through JNA on Android and lists the presets, and the app
   starts and shows them. Locally: the APK is built (debug, and release
   through R8) and holds `libsyn_android.so` for both ABIs. **On a real
   phone (2026-10-03, by the user): the app starts and lists the presets.**
   Found on the way, fixed in the core: `AppState` wrote the point's name as
   `preset_name`, the web app's key is `presetName`; the core now writes
   `presetName` and reads both. **Agreed afterwards (2026-10-03): the name
   becomes `preset_name` in all three apps, and the web app changes** — the
   steps are in synesthesia-core's TODO.md. Versions: AGP 9.4.1
   with its built-in Kotlin 2.4.20, Gradle 9.8.0, Compose BOM 2026.09.00,
   UniFFI 0.32.2, JNA 5.19.1, NDK 27.2.
1. **Sound, in the background.** The foreground service with the
   `AudioTrack` thread pulling blocks from the core; play a preset with the
   screen off; the notification; audio focus; feature frames to a meter on
   screen; bench: underruns and CPU per preset on a device (decision 5 is
   decided here).
   **Built 2026-10-03:**
   - Core: `syn-player` (synesthesia-core) — the engine behind a command
     queue, whole 128-sample blocks whatever the device's chunk size (the
     live sound is bit-identical to the offline render), fades on start and
     stop, a feature frame every 8 blocks stamped on the engine clock;
     through the FFI as `SoundPlayer`, `AudioFrame`, `render_stats`.
   - `AudioOutput`: float PCM, mono, the device's own rate, 1024-frame
     chunks into a 250 ms `AudioTrack` buffer, blocking writes, the thread at
     `THREAD_PRIORITY_URGENT_AUDIO`. Latency does not matter here (a change
     morphs over 2 s), robustness does — hence the deep buffer. The meters
     ask for the frame at the *played* position (`PlayedClock`, which
     survives the 32-bit wrap of the head position), not the rendered one.
   - `PlaybackController` (one per process): audio focus (a call pauses and
     the end of it resumes; losing it for good stops), headphones unplugged
     stops, a partial wake lock while playing.
   - `PlaybackService`: foreground service of type `mediaPlayback`, a
     `MediaSession` (lock screen, headphone buttons, system media
     controls), a MediaStyle notification with ⏹ / ▶; after a stop the
     notification stays with ▶ and the service ends.
   - The screen: ▶/⏹, the point, the features as meters and a 64-band
     spectrum, the output's cost (`core %` = time in the core per second of
     audio, peak chunk, underruns); the presets (tap to switch, live); a
     Bench screen that renders 10 s of every preset offline and copies a
     report.
   - Tests: the core's player (10, host); `PlayedClock` (JVM); on the CI
     emulators (API 26, 35, green 2026-10-03): the player through JNA, an
     `AudioOutput` run whose clock follows the track, a second run on the
     same player once the first has let go of it, the service coming to the
     front and leaving it, stop-and-play at once.
   **On a real phone (2026-10-03, by the user):** the sound plays well, with
   no stutter, and keeps playing in the background.
   **Decision 5, for now: `AudioTrack` stays.** Nothing in the listening
   asks for AAudio. Still to be measured, so the choice rests on numbers
   (decision 12) and is re-checked once phase 2 adds the scout's renders:
   the Bench report; `core %` and underruns after 30 min with the screen
   off; and by hand — the lock screen and headphone controls, a call
   pausing and resuming the sound, unplugging headphones, switching presets
   while playing without a click.
2. **Session.** `syn-session` ported from `main.ts` / `main.rs` with host
   tests; the main screen: 👎 👍 🎲 ↩, status, point name, morph audible;
   👍/👎 from the notification.
3. **Picture.** GLES 3.0 renderer with the verbatim shaders, frame params
   from the core, onset hits → growth + ripples, touch painting, quality
   probe, the CPU fallback, the GPU-vs-CPU parity test.
4. **Points and tokens.** Last point restored; 💾 with a name; *My points*;
   export a `#s=` token, import one from the clipboard or an opened link.
5. **Settings.** The two-tab page generated from the schema; sound edits
   heard as made; the picture paused while open; close = one undoable step.
6. **Polish.** Details, help, wake lock while the picture shows, the bench
   numbers written into this file.
7. **iOS readiness (optional, small).** Build `syn-ffi` as an XCFramework
   with Swift bindings and call it from a one-file Swift test — proves the
   architecture before the Swift app exists.

## Performance budget (targets; measured values go here with a date)

| metric | target |
|---|---|
| live point, one big core | ≤ 20% (heaviest preset) |
| underruns | 0 in 30 min with the picture on and a press every 10 s |
| picture, chosen rung | ≥ 30 fps on a 2020 mid-range phone |
| scout, 3 + 3 candidates | < 4 s wall, measured energy per press noted |
| cold start → first sound | < 500 ms after ▶ |

## Open questions (for the user)

1. **The scout on a phone** (phase 2). Full-quality renders (30 s at
   22 kHz, as the console) or the web's cheaper surrogate (24 s at 8 kHz)?
   Decided by measuring: phase 1's Bench gives the render speed, phase 2
   measures a scout batch's wall time and battery; the length and rate
   become settings either way.

Resolved on 2026-10-03: the core language (Rust, decision 1), the separate
repository (decision 2; `synesthesia-core` created by the user), no
sharing (decision 8), background sound (decision 10), the network policy of
the cloud build environment, and the spelling of the point's name
(`preset_name` everywhere; synesthesia-core's TODO.md).

## Don'ts (inherited)

- Never allocate, lock or log on the render path.
- Never write an oscillator as `sin(2π·f·t)` with absolute `t`.
- Never change the `AppState` shape or the gene order.
- Never re-type a range, a default, a preset or a shader: they are read from
  the core or copied by a script.
- Don't tune by ear alone: the numbers come first, the listening pass second.
