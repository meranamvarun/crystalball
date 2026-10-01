//! Hybrid logical clock with a fixed-width, lexicographically ordered string form:
//! `{millis:013}-{counter:04}-{node}` (see contracts/hlc.json).

use std::fmt;

use serde::{Deserialize, Serialize};
use thiserror::Error;

const MAX_COUNTER: u32 = 9_999;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Hlc {
    pub millis: i64,
    pub counter: u32,
    pub node: String,
}

#[derive(Debug, Error, PartialEq, Eq)]
#[error("invalid HLC {0:?}: expected '<13 digit millis>-<4 digit counter>-<node>'")]
pub struct HlcError(pub String);

impl Hlc {
    pub fn new(node: impl Into<String>) -> Self {
        Hlc {
            millis: 0,
            counter: 0,
            node: node.into(),
        }
    }

    pub fn parse(s: &str) -> Result<Hlc, HlcError> {
        let err = || HlcError(s.to_string());
        let mut parts = s.splitn(3, '-');
        let millis = parts.next().ok_or_else(err)?;
        let counter = parts.next().ok_or_else(err)?;
        let node = parts.next().ok_or_else(err)?;
        let digits = |p: &str, n: usize| p.len() == n && p.bytes().all(|b| b.is_ascii_digit());
        if !digits(millis, 13) || !digits(counter, 4) || node.is_empty() {
            return Err(err());
        }
        Ok(Hlc {
            millis: millis.parse().map_err(|_| err())?,
            counter: counter.parse().map_err(|_| err())?,
            node: node.to_string(),
        })
    }

    /// Stamp a local event happening at wall time `now_ms`.
    pub fn tick(&mut self, now_ms: i64) -> Hlc {
        if now_ms > self.millis {
            self.millis = now_ms;
            self.counter = 0;
        } else {
            self.bump();
        }
        self.clone()
    }

    /// Merge a remote stamp observed at wall time `now_ms`.
    pub fn recv(&mut self, remote: &Hlc, now_ms: i64) -> Hlc {
        let max = self.millis.max(remote.millis).max(now_ms);
        if max == now_ms && now_ms > self.millis && now_ms > remote.millis {
            self.millis = now_ms;
            self.counter = 0;
            return self.clone();
        }
        let next = if self.millis == remote.millis {
            self.counter.max(remote.counter) + 1
        } else if max == self.millis {
            self.counter + 1
        } else {
            remote.counter + 1
        };
        self.millis = max;
        self.counter = next;
        if self.counter > MAX_COUNTER {
            self.millis += 1;
            self.counter = 0;
        }
        self.clone()
    }

    fn bump(&mut self) {
        if self.counter >= MAX_COUNTER {
            self.millis += 1;
            self.counter = 0;
        } else {
            self.counter += 1;
        }
    }
}

impl fmt::Display for Hlc {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:013}-{:04}-{}", self.millis, self.counter, self.node)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn string_order_matches_clock_order() {
        let mut c = Hlc::new("a");
        let a = c.tick(5).to_string();
        let b = c.tick(5).to_string();
        let d = c.tick(1_000_000).to_string();
        assert!(a < b && b < d);
    }

    #[test]
    fn node_may_contain_dashes() {
        assert_eq!(
            Hlc::parse("0000000000001-0000-phone-1").unwrap().node,
            "phone-1"
        );
    }
}
