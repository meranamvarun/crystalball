//! Typed entities carried in `SyncRecord` payloads (see docs/architecture.md#entities).

use std::str::FromStr;

use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::sync::SyncRecord;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Entity {
    Transaction,
    Budget,
    Bill,
    Holding,
    Liability,
    Goal,
    NetWorthSnapshot,
    CategoryRule,
}

impl FromStr for Entity {
    type Err = String;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Ok(match s {
            "transaction" => Entity::Transaction,
            "budget" => Entity::Budget,
            "bill" => Entity::Bill,
            "holding" => Entity::Holding,
            "liability" => Entity::Liability,
            "goal" => Entity::Goal,
            "networth_snapshot" => Entity::NetWorthSnapshot,
            "category_rule" => Entity::CategoryRule,
            other => return Err(format!("unknown entity {other:?}")),
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Direction {
    Debit,
    Credit,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Transaction {
    #[serde(default)]
    pub id: String,
    pub member_id: String,
    pub amount_minor: i64,
    pub direction: Direction,
    pub merchant: String,
    pub category: Option<String>,
    pub occurred_at_ms: i64,
    #[serde(default)]
    pub deleted: bool,
}

impl Transaction {
    pub fn is_spend(&self) -> bool {
        !self.deleted && self.direction == Direction::Debit
    }
}

/// Monthly budget; `member_id: None` is a family budget.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Budget {
    #[serde(default)]
    pub id: String,
    pub category: String,
    pub cap_minor: i64,
    #[serde(default)]
    pub member_id: Option<String>,
    #[serde(default)]
    pub deleted: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BillKind {
    Recurring,
    Variable,
    Autopay,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Bill {
    #[serde(default)]
    pub id: String,
    pub name: String,
    pub amount_minor: i64,
    pub due_day: u8,
    pub kind: BillKind,
    #[serde(default)]
    pub deleted: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AssetClass {
    Equity,
    MutualFund,
    Fd,
    Gold,
    Cash,
    Other,
}

impl AssetClass {
    pub fn as_str(self) -> &'static str {
        match self {
            AssetClass::Equity => "equity",
            AssetClass::MutualFund => "mutual_fund",
            AssetClass::Fd => "fd",
            AssetClass::Gold => "gold",
            AssetClass::Cash => "cash",
            AssetClass::Other => "other",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Holding {
    #[serde(default)]
    pub id: String,
    pub member_id: String,
    pub name: String,
    pub asset_class: AssetClass,
    pub invested_minor: i64,
    pub value_minor: i64,
    #[serde(default)]
    pub deleted: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Liability {
    #[serde(default)]
    pub id: String,
    pub member_id: String,
    pub name: String,
    pub outstanding_minor: i64,
    #[serde(default)]
    pub deleted: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Goal {
    #[serde(default)]
    pub id: String,
    pub name: String,
    pub target_minor: i64,
    pub saved_minor: i64,
    pub target_month: String,
    #[serde(default)]
    pub deleted: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NetWorthSnapshot {
    #[serde(default)]
    pub id: String,
    pub month: String,
    pub value_minor: i64,
    #[serde(default)]
    pub deleted: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CategoryRule {
    #[serde(default)]
    pub id: String,
    pub merchant_key: String,
    pub category: String,
    #[serde(default)]
    pub deleted: bool,
}

/// Deserialize a record's payload into a typed entity, injecting `id` and `deleted`.
pub fn from_record<T: DeserializeOwned>(rec: &SyncRecord) -> Result<T, String> {
    let mut obj = rec
        .payload
        .as_object()
        .cloned()
        .ok_or_else(|| "payload must be a JSON object".to_string())?;
    obj.insert("id".into(), Value::String(rec.id.clone()));
    obj.insert("deleted".into(), Value::Bool(rec.deleted));
    serde_json::from_value(Value::Object(obj)).map_err(|e| e.to_string())
}

fn is_month(s: &str) -> bool {
    chrono::NaiveDate::parse_from_str(&format!("{s}-01"), "%Y-%m-%d").is_ok() && s.len() == 7
}

fn check(ok: bool, msg: &str) -> Result<(), String> {
    if ok {
        Ok(())
    } else {
        Err(msg.to_string())
    }
}

fn parse<T: DeserializeOwned>(payload: &Value) -> Result<T, String> {
    let rec = SyncRecord {
        entity: String::new(),
        id: String::new(),
        hlc: String::new(),
        deleted: false,
        author: String::new(),
        payload: payload.clone(),
    };
    from_record(&rec)
}

/// Semantic validation of a live (non-tombstone) payload.
pub fn validate_payload(entity: Entity, payload: &Value) -> Result<(), String> {
    match entity {
        Entity::Transaction => {
            let t: Transaction = parse(payload)?;
            check(t.amount_minor > 0, "amount_minor must be positive")?;
            check(!t.member_id.is_empty(), "member_id is required")?;
            check(
                !t.merchant.trim().is_empty() && t.merchant.len() <= 200,
                "merchant must be 1..=200 chars",
            )?;
            check(
                t.category
                    .as_deref()
                    .is_none_or(|c| !c.is_empty() && c.len() <= 64),
                "bad category",
            )?;
            check(t.occurred_at_ms >= 0, "occurred_at_ms must be >= 0")
        }
        Entity::Budget => {
            let b: Budget = parse(payload)?;
            check(b.cap_minor > 0, "cap_minor must be positive")?;
            check(!b.category.is_empty(), "category is required")
        }
        Entity::Bill => {
            let b: Bill = parse(payload)?;
            check(b.amount_minor >= 0, "amount_minor must be >= 0")?;
            check((1..=31).contains(&b.due_day), "due_day must be 1..=31")?;
            check(!b.name.is_empty(), "name is required")
        }
        Entity::Holding => {
            let h: Holding = parse(payload)?;
            check(
                h.invested_minor >= 0 && h.value_minor >= 0,
                "holding amounts must be >= 0",
            )?;
            check(!h.name.is_empty(), "name is required")
        }
        Entity::Liability => {
            let l: Liability = parse(payload)?;
            check(l.outstanding_minor >= 0, "outstanding_minor must be >= 0")
        }
        Entity::Goal => {
            let g: Goal = parse(payload)?;
            check(
                g.target_minor > 0 && g.saved_minor >= 0,
                "goal amounts invalid",
            )?;
            check(is_month(&g.target_month), "target_month must be YYYY-MM")
        }
        Entity::NetWorthSnapshot => {
            let s: NetWorthSnapshot = parse(payload)?;
            check(is_month(&s.month), "month must be YYYY-MM")
        }
        Entity::CategoryRule => {
            let r: CategoryRule = parse(payload)?;
            check(
                !r.merchant_key.is_empty() && !r.category.is_empty(),
                "merchant_key and category are required",
            )
        }
    }
}

/// All live entities of a family, decoded from sync records (tombstones and undecodable rows skipped).
#[derive(Debug, Default, Clone)]
pub struct Ledger {
    pub transactions: Vec<Transaction>,
    pub budgets: Vec<Budget>,
    pub bills: Vec<Bill>,
    pub holdings: Vec<Holding>,
    pub liabilities: Vec<Liability>,
    pub goals: Vec<Goal>,
    pub snapshots: Vec<NetWorthSnapshot>,
    pub category_rules: Vec<CategoryRule>,
}

impl Ledger {
    pub fn from_records<'a>(records: impl IntoIterator<Item = &'a SyncRecord>) -> Ledger {
        let mut l = Ledger::default();
        for r in records.into_iter().filter(|r| !r.deleted) {
            let Ok(entity) = r.entity.parse::<Entity>() else {
                continue;
            };
            match entity {
                Entity::Transaction => l.transactions.extend(from_record(r).ok()),
                Entity::Budget => l.budgets.extend(from_record(r).ok()),
                Entity::Bill => l.bills.extend(from_record(r).ok()),
                Entity::Holding => l.holdings.extend(from_record(r).ok()),
                Entity::Liability => l.liabilities.extend(from_record(r).ok()),
                Entity::Goal => l.goals.extend(from_record(r).ok()),
                Entity::NetWorthSnapshot => l.snapshots.extend(from_record(r).ok()),
                Entity::CategoryRule => l.category_rules.extend(from_record(r).ok()),
            }
        }
        l
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn record(entity: &str, id: &str, deleted: bool, payload: Value) -> SyncRecord {
        SyncRecord {
            entity: entity.into(),
            id: id.into(),
            hlc: "0000000000001-0000-a".into(),
            deleted,
            author: "m1".into(),
            payload,
        }
    }

    #[test]
    fn ledger_decodes_live_records_and_skips_tombstones() {
        let recs = vec![
            record(
                "budget",
                "b1",
                false,
                json!({"category": "Dining", "cap_minor": 100}),
            ),
            record(
                "budget",
                "b2",
                true,
                json!({"category": "Health", "cap_minor": 100}),
            ),
            record(
                "goal",
                "g1",
                false,
                json!({"name": "Goa", "target_minor": 10, "saved_minor": 1, "target_month": "2026-12"}),
            ),
            record("unknown", "u1", false, json!({})),
        ];
        let l = Ledger::from_records(&recs);
        assert_eq!(l.budgets.len(), 1);
        assert_eq!(l.budgets[0].id, "b1");
        assert_eq!(l.goals[0].id, "g1");
    }

    #[test]
    fn goal_month_must_be_well_formed() {
        let bad =
            json!({"name": "x", "target_minor": 1, "saved_minor": 0, "target_month": "2026-13"});
        assert!(validate_payload(Entity::Goal, &bad).is_err());
    }
}
