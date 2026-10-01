//! Hearth home hub: stores the family's sync records in SQLite and serves the LAN sync and
//! reporting API. Phones discover it over mDNS (`_hearth._tcp.local.`).

pub mod api;
pub mod db;
pub mod error;
#[cfg(feature = "mdns")]
pub mod mdns;

use std::sync::Arc;

use axum::Router;
use tokio::sync::Mutex;

pub use db::Db;

/// Wall clock in epoch milliseconds; injected so tests are deterministic.
pub type Clock = Arc<dyn Fn() -> i64 + Send + Sync>;

#[derive(Clone)]
pub struct AppState {
    pub db: Arc<Mutex<Db>>,
    clock: Clock,
}

impl AppState {
    pub fn new(db: Db, clock: Clock) -> AppState {
        AppState {
            db: Arc::new(Mutex::new(db)),
            clock,
        }
    }

    pub fn now(&self) -> i64 {
        (self.clock)()
    }
}

pub fn system_clock() -> Clock {
    Arc::new(|| chrono::Utc::now().timestamp_millis())
}

pub fn app(state: AppState) -> Router {
    api::router(state)
}
