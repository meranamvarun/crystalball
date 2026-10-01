//! Sync records and the last-writer-wins merge (see contracts/merge.json).

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::hlc::Hlc;
use crate::model::{self, Entity};

/// One replicated row. `payload` is entity-specific JSON (see `model`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SyncRecord {
    pub entity: String,
    pub id: String,
    pub hlc: String,
    pub deleted: bool,
    pub author: String,
    pub payload: Value,
}

impl SyncRecord {
    pub fn key(&self) -> String {
        format!("{}/{}", self.entity, self.id)
    }

    /// Structural validation done by the hub before accepting a pushed record.
    pub fn validate(&self) -> Result<(), String> {
        if self.id.is_empty() || self.id.len() > 128 {
            return Err("id must be 1..=128 characters".into());
        }
        Hlc::parse(&self.hlc).map_err(|e| e.to_string())?;
        let entity: Entity = self.entity.parse()?;
        if self.deleted {
            return Ok(()); // tombstones may carry a stale or empty payload
        }
        model::validate_payload(entity, &self.payload)
    }
}

/// True when `incoming` must replace the current version (or there is none).
pub fn wins(current_hlc: Option<&str>, incoming_hlc: &str) -> bool {
    current_hlc.is_none_or(|cur| incoming_hlc > cur)
}

/// In-memory LWW store keyed by (entity, id). Used by tests and as the reference merge.
#[derive(Debug, Default, Clone)]
pub struct Store {
    records: BTreeMap<(String, String), SyncRecord>,
}

impl Store {
    /// Applies records in order; returns the ones that changed local state.
    pub fn merge(&mut self, incoming: Vec<SyncRecord>) -> Vec<SyncRecord> {
        let mut applied: Vec<SyncRecord> = Vec::new();
        for rec in incoming {
            let key = (rec.entity.clone(), rec.id.clone());
            if wins(self.records.get(&key).map(|r| r.hlc.as_str()), &rec.hlc) {
                applied.retain(|r| r.key() != rec.key());
                applied.push(rec.clone());
                self.records.insert(key, rec);
            }
        }
        applied
    }

    pub fn records(&self) -> Vec<&SyncRecord> {
        self.records.values().collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn rec(entity: &str, payload: Value, deleted: bool) -> SyncRecord {
        SyncRecord {
            entity: entity.into(),
            id: "x1".into(),
            hlc: "0000000001000-0000-a".into(),
            deleted,
            author: "m1".into(),
            payload,
        }
    }

    #[test]
    fn validate_accepts_well_formed_transaction() {
        let r = rec(
            "transaction",
            json!({"member_id": "m1", "amount_minor": 100, "direction": "debit", "merchant": "Uber",
                   "category": null, "occurred_at_ms": 1, "source": "sms"}),
            false,
        );
        assert_eq!(r.validate(), Ok(()));
    }

    #[test]
    fn validate_rejects_unknown_entity_bad_hlc_and_bad_payload() {
        assert!(rec("spaceship", json!({}), false).validate().is_err());
        let mut r = rec(
            "budget",
            json!({"category": "Dining", "cap_minor": 10}),
            false,
        );
        r.hlc = "nope".into();
        assert!(r.validate().is_err());
        assert!(rec(
            "budget",
            json!({"category": "Dining", "cap_minor": -5}),
            false
        )
        .validate()
        .is_err());
        assert!(rec("transaction", json!({"amount_minor": "12.5"}), false)
            .validate()
            .is_err());
    }

    #[test]
    fn tombstones_skip_payload_validation() {
        assert_eq!(rec("budget", json!(null), true).validate(), Ok(()));
    }
}
