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

**Status: phase 0.** The core is built for the phone and called from
Kotlin through generated bindings; the app lists the built-in points. Sound
is phase 1.

## Trying it on a phone

Every CI run on GitHub (Actions → *check* → the run) has the debug build as
the artifact **app-debug**: arm64 phones and the x86_64 emulator, Android
8.0+. Unzip it, open the APK on the phone and allow installing from that
source. Every debug build is signed with the same key
(`app/debug.keystore`), so a newer one installs over the older one.

## Building

Needs a JDK 17+ (21 is what CI uses), Rust (stable), and the Android SDK:

```bash
ANDROID_HOME=/opt/android-sdk scripts/setup-android-sdk.sh   # once: SDK, NDK, Rust targets, cargo-ndk
scripts/check.sh                                             # after every change
./gradlew :app:installDebug                                  # onto a connected device
```

`scripts/check.sh` runs rustfmt and clippy on `core/rust`, the JVM tests that
call the real core through the generated Kotlin, Android lint, and builds
the debug APK.

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
