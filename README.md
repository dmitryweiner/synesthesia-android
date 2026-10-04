# Synesthesia for Android

Sound and image generated together from one point in a large parameter
space, and steered by you: 👍 when you like where it is going, 👎 when you
don't, and the search follows. The phone version of
[synesthesia](https://github.com/dmitryweiner/synesthesia), built to keep
playing with the screen off.

The model — 21 formula generators, the FX chain, the LFOs, the genome, the
search, the picture's simulation — is the shared Rust core,
[synesthesia-core](https://github.com/dmitryweiner/synesthesia-core). This
repository is the Android app around it: the audio device, the GPU picture,
storage, the background service and the interface, in Kotlin.
[PLAN.md](PLAN.md) has the decisions and the phases.

**Status: phase 2.** The built-in points play, also with the screen off
(a foreground service with a media notification and lock-screen controls),
the screen shows what the sound is doing, and 👍 👎 🎲 ↩ steer the search:
a press glides the sound to where it leads over about two seconds, and
while you listen the core renders and scores candidates in the background
so that the next press takes the best one it found. The notification
carries 👎 ⏹ 👍, so the search runs with the phone locked. **The picture is
on screen** as of phase 3: the web app's own seven shaders on OpenGL ES 3.0,
driven by the sound that is being heard, and a finger paints into it.

## Trying it on a phone

Every CI run on GitHub (Actions → *check* → the run) has the debug build as
the artifact **app-debug**: arm64 phones and the x86_64 emulator, Android
8.0+. Unzip it, open the APK on the phone and allow installing from that
source. Every debug build is signed with the same key
(`app/debug.keystore`), so a newer one installs over the older one.

## Building

Needs a JDK 17+ (21 is what CI uses), Rust (current stable — CI uses the
latest, and a newer clippy finds more), and the Android SDK. With Android
Studio, point `ANDROID_HOME` at its SDK (`~/Android/Sdk` on Linux,
`~/Library/Android/sdk` on macOS); the script installs whatever of the rest
is missing:

```bash
ANDROID_HOME=~/Android/Sdk scripts/setup-android-sdk.sh   # once: SDK packages, NDK, Rust targets, cargo-ndk
rustup update stable                                      # before checking, to match CI
scripts/check.sh                                          # after every change
./gradlew :app:installDebug                               # onto a connected phone (USB debugging)
./gradlew connectedDebugAndroidTest                       # the instrumented tests, on that phone
```

`scripts/check.sh` runs rustfmt and clippy on `core/rust`, the JVM tests that
call the real core through the generated Kotlin, Android lint, and builds
the debug APK. The instrumented tests (`src/androidTest`: the core through
JNA on Android, the audio output, the service) need a device or an
emulator; CI runs them on emulators with API 26 and 35.

## How the core gets in

```
core/rust/Cargo.toml        pins synesthesia-core by git revision
core/rust/syn-android/      the cdylib the app loads: libsyn_android.so
core/rust/uniffi-bindgen/   the bindings generator, same UniFFI as the core
core/build.gradle.kts       cargoBuildAndroid  → jniLibs for arm64-v8a, x86_64
                            cargoBuildHost     → the same library for the JVM tests
                            uniffiBindings     → Kotlin, package
                                                 io.github.dmitryweiner.synesthesia.core
```

To take a newer core: push it to synesthesia-core, change `rev` in
`core/rust/Cargo.toml`, run `scripts/check.sh`, commit the bump with
`Cargo.lock`.

## License

GPL-3.0, see [LICENSE](LICENSE).
