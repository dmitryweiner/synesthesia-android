# Synesthesia for Android — plan & decisions (DRAFT, awaiting approval)

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

Numbered so later docs can cite them. ❓ marks the ones that still need the
user's answer (see *Open questions*).

1. **❓ The core is the existing Rust `syn-core`, reached from Kotlin through
   UniFFI.** Not a Kotlin re-port of the DSP. Reasons:
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
2. **The control logic moves into the core as `Session`.** A new crate
   `syn-session` (in this repo first; to be upstreamed into
   `synesthesia-rust` so the console uses it too) holds what `main.ts` and
   `main.rs` both implement: the explorer plus the 2 s morph (`from, to,
   started`), step count, undo depth, scout scheduling (the 800 ms settle,
   the version check, picking the best candidate on a press), 🎲 near a
   preset, load/restore, the points list model, the status text. It is pure:
   `tick(now)` and commands in, effects out (`PlayState`, `SwitchTo`,
   `Reseed`, `SaveLastPoint`, `StartScout`, `Status`). No threads, no clock
   of its own, no files — the app supplies those. Tests run on the host and
   pin the behaviour `main.ts` has (a press mid-morph starts from what is
   audible; a load is a hard switch and a reseed; Settings closes as one
   undoable step).
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
   that pulls blocks from the core (`render(frames) -> FloatArray`, 2 048–4 096
   frames a call) and blocks in `AudioTrack.write` — the pacing model the
   console's `PipeSink` proved. `AudioTrack.getUnderrunCount()` is the bench.
   If it underruns under load (scout running, screen rotating), the audio
   thread moves into Rust on AAudio (`ndk` crate, `audio` feature; no C++
   build) and Kotlin only starts and stops it. The feature frames
   (loudness, swell, brightness, bands, onset, hits, spectrum) are published
   from the render loop exactly as `syn-audio`'s `Frame`.
6. **Realtime discipline carries over.** Nothing allocates or locks on the
   render path beyond the one FFI call per block; a UI that falls behind
   loses frames, never sound. The scout runs on its own rayon pool of
   `cores − 2` threads (the console's fix for xruns), on the little cores
   where the scheduler puts it; its render length and rate are settings,
   because a phone pays in battery for 7 × 30 s renders per press.
7. **Storage is the web app's JSON in the app's files.** `last-point.json`
   and `points.json` in `filesDir`, settings in DataStore. A point file from
   the console opens here and the other way round.
8. **❓ Links and sharing.** Recommended: the app **opens** the web app's
   links (`?presetId=` via the points Worker, `#s=` tokens, `?preset=N`)
   through an intent filter on the GitHub Pages URL, and **shares** the way
   the web does (POST to the Worker for a short link, long `#s=` link when
   offline) — so a point found on the phone opens in any browser. The
   alternative is the console's decision 9: local only, no network. The
   Worker already validates points with the app's own sanitizer.
9. **Jetpack Compose, single Activity.** Screens: the picture full-screen
   with the 👎 👍 🎲 ↩ bar and a status line; a top bar with the point's name,
   ▶ sound, 💾, 🔗, ⚙, ?; Points (bottom sheet: *My points*, built-in);
   Details; Settings (two tabs, Audio / Video, generated from the schema).
   Touch on the picture paints growth and a ripple, sampled once per frame
   as the web does. `FLAG_KEEP_SCREEN_ON` while the sound plays.
10. **❓ Lifecycle.** v1 plays while the app is in front (as the web does);
    the sound pauses in `onStop` and the LFO clock stays continuous. A
    foreground service with a media notification (sound with the screen
    off) is a separate phase, if wanted.
11. **minSdk 26 (Android 8.0), OpenGL ES 3.0 required, arm64-v8a first**
    (plus x86_64 for the emulator; armeabi-v7a only if asked).
12. **Measure first** — carried over verbatim. Every performance claim comes
    from a checked-in bench: render speed per preset on the device,
    underruns, GL frame time per rung, scout wall time and energy. CI runs
    `cargo test` for the core crates and the JVM tests; the device numbers
    are written down here with a date.

## Architecture

```
 Kotlin (Android)                          Rust (portable)
 ┌───────────────────────────┐             ┌──────────────────────────────┐
 │ Compose UI                │  UniFFI     │ syn-ffi  (cdylib / staticlib)│
 │  main · points · details  │ ◄─────────► │  SynCore: session, render,   │
 │  settings (from schema)   │             │  frames, schema, presets,    │
 ├───────────────────────────┤             │  tokens, picture (CPU)       │
 │ AudioTrack thread ────────┼── render ──►│ syn-session: Session (pure)  │
 │ GLES 3.0 renderer ◄───────┼── frame ────│ syn-core (git dep, pinned):  │
 │  7 passes, shaders verbatim│   params   │  dsp · fx · engine · features│
 │ files / DataStore / links │             │  genome · scout · sim · state│
 └───────────────────────────┘             └──────────────────────────────┘
       later: Swift + Metal / AVAudioEngine around the same syn-ffi
```

Repository layout:

```
core/
  Cargo.toml          workspace: syn-session, syn-ffi
  syn-session/        the control logic (decision 2), host tests
  syn-ffi/            the UniFFI surface; builds for Android via cargo-ndk,
                      for the host for tests, later for iOS
app/                  the Android application (Gradle, Kotlin, Compose)
  src/main/.../audio  AudioTrack sink thread, feature frames
  src/main/.../gl     EGL, ping-pong targets, the seven passes
  src/main/.../ui     Compose screens
  src/main/.../store  last point, points, settings, links
  src/main/assets/shaders/   copied verbatim from the web app by a script
scripts/
  check.sh            cargo fmt/clippy/test + gradle lint/test
  sync-shaders.sh     re-copies the shaders from ../synesthesia
```

`syn-core` is a **pinned cargo git dependency** on `synesthesia-rust` (❓ or
a submodule — see questions). Nothing in it is copied.

## Phases

0. **Scaffold.** Gradle project (AGP, Kotlin, Compose), Rust workspace,
   `cargo-ndk` builds `libsyn_ffi.so` for arm64-v8a and x86_64 into the
   app's `jniLibs`, UniFFI generates the Kotlin, `scripts/check.sh`. Proof:
   the app lists the 12 presets from the core on an emulator.
1. **Sound.** `AudioTrack` thread pulling blocks from the core; play a
   preset; feature frames to a meter on screen; bench: underruns and CPU per
   preset on a device (decision 5 is decided here).
2. **Session.** `syn-session` ported from `main.ts` / `main.rs` with host
   tests; the main screen: 👎 👍 🎲 ↩, status, point name, morph audible.
3. **Picture.** GLES 3.0 renderer with the verbatim shaders, frame params
   from the core, onset hits → growth + ripples, touch painting, quality
   probe, the CPU fallback, the GPU-vs-CPU parity test.
4. **Points, storage, links.** Last point restored; 💾 with a name;
   *My points*; open `#s=` / `?preset=N` / `?presetId=` links; 🔗 share
   (decision 8).
5. **Settings.** The two-tab page generated from the schema; sound edits
   heard as made; the picture paused while open; close = one undoable step.
6. **Polish.** Details, help, wake lock, lifecycle (decision 10), the
   bench numbers written into this file.
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

1. **Core language** (decision 1): Rust `syn-core` through UniFFI — or a
   Kotlin re-port (KMP or plain)? The recommendation is Rust.
2. **How to depend on `synesthesia-rust`**: a pinned cargo git dependency
   (recommended: no submodule in the Gradle build, bump the rev on purpose),
   a git submodule, or `syn-core` moved to its own repository?
   And is upstreaming `syn-session` into `synesthesia-rust` later agreed?
3. **Sharing** (decision 8): Worker short links + opening web links, or
   local only?
4. **Lifecycle** (decision 10): foreground-service playback in v1, or later?
5. **The build environment**: this cloud session cannot reach
   `dl.google.com` (the Android SDK, build-tools and NDK are served from
   there), so nothing Android can be compiled here until the environment's
   network policy allows that host. Rust and the host tests build fine.

## Don'ts (inherited)

- Never allocate, lock or log on the render path.
- Never write an oscillator as `sin(2π·f·t)` with absolute `t`.
- Never change the `AppState` shape or the gene order.
- Never re-type a range, a default, a preset or a shader: they are read from
  the core or copied by a script.
- Don't tune by ear alone: the numbers come first, the listening pass second.
