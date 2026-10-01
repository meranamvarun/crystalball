//! Hearth domain core.
//!
//! Pure functions only: no clock, I/O or randomness. Time enters as parameters
//! (`now_ms`, anchor dates, `tz_offset_minutes`). Behaviour shared with the Kotlin
//! core is pinned by the fixtures in `/contracts` (see `tests/contracts.rs`).

pub mod analytics;
pub mod hlc;
pub mod model;
pub mod money;
pub mod period;
pub mod portfolio;
pub mod report;
pub mod sync;
