//! Shared core for Pact: data model, natural-language quick add, storage and git sync.
//!
//! The Android app has a Kotlin port of `nlp` and `recur`; both are tested against
//! `spec/nlp_cases.json`, and both read/write the layout in `spec/data-format.md`.

pub mod model;
pub mod nlp;
pub mod recur;
pub mod settings;
pub mod store;
pub mod sync;
pub mod themes;

pub fn now_millis() -> i64 {
    chrono::Utc::now().timestamp_millis()
}
