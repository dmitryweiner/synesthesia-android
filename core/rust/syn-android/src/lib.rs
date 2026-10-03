//! The library the Android app loads. Everything it exports comes from the
//! shared core's `syn-ffi`; this crate only links it into one `.so` (and is
//! where Android-only native code would go, should the audio thread ever
//! move into Rust — PLAN.md decision 5).

pub use syn_ffi::*;
