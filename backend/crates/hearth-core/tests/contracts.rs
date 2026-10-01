//! Runs the shared fixtures in /contracts against hearth-core. The Kotlin core runs the same files.

use std::path::PathBuf;

use chrono::{NaiveDate, NaiveDateTime};
use hearth_core::analytics::{anomalies, budget_status, monthly_trend};
use hearth_core::hlc::Hlc;
use hearth_core::model::{
    Budget, Direction, Goal, Holding, Liability, NetWorthSnapshot, Transaction,
};
use hearth_core::period::{local_to_ms, Range};
use hearth_core::portfolio::summarize;
use hearth_core::report::{spend_report, Scope};
use hearth_core::sync::{Store, SyncRecord};
use serde::Deserialize;
use serde_json::Value;

fn contract(name: &str) -> Value {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../../contracts")
        .join(name);
    let text = std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("read {path:?}: {e}"));
    serde_json::from_str(&text).unwrap()
}

fn date(s: &str) -> NaiveDate {
    NaiveDate::parse_from_str(s, "%Y-%m-%d").unwrap()
}

fn scope(case: &Value) -> Scope {
    match case["scope"].as_str().unwrap() {
        "family" => Scope::Family,
        "me" => Scope::Member(case["me"].as_str().unwrap().to_string()),
        other => panic!("unknown scope {other}"),
    }
}

#[derive(Deserialize)]
struct FixtureTxn {
    id: String,
    member_id: String,
    amount_minor: i64,
    direction: Direction,
    merchant: String,
    category: Option<String>,
    at: String,
    #[serde(default)]
    deleted: bool,
}

fn transactions(list: &Value, tz: i32) -> Vec<Transaction> {
    let fixtures: Vec<FixtureTxn> = serde_json::from_value(list.clone()).unwrap();
    fixtures
        .into_iter()
        .map(|f| {
            let local = NaiveDateTime::parse_from_str(&f.at, "%Y-%m-%dT%H:%M").unwrap();
            Transaction {
                id: f.id,
                member_id: f.member_id,
                amount_minor: f.amount_minor,
                direction: f.direction,
                merchant: f.merchant,
                category: f.category,
                occurred_at_ms: local_to_ms(local, tz),
                deleted: f.deleted,
            }
        })
        .collect()
}

fn dataset() -> (Vec<Transaction>, Vec<Budget>, i32) {
    let d = contract("dataset.json");
    let tz = d["tz_offset_minutes"].as_i64().unwrap() as i32;
    let budgets = serde_json::from_value(d["budgets"].clone()).unwrap();
    (transactions(&d["transactions"], tz), budgets, tz)
}

#[test]
fn contract_hlc_tick() {
    for case in contract("hlc.json")["tick"].as_array().unwrap() {
        let mut clock: Hlc = serde_json::from_value(case["state"].clone()).unwrap();
        let got = clock.tick(case["now_ms"].as_i64().unwrap());
        assert_eq!(
            got.to_string(),
            case["expect"].as_str().unwrap(),
            "contract case '{}' failed",
            case["name"]
        );
        assert_eq!(
            clock.to_string(),
            got.to_string(),
            "clock state must advance to the returned stamp"
        );
    }
}

#[test]
fn contract_hlc_recv() {
    for case in contract("hlc.json")["recv"].as_array().unwrap() {
        let mut clock: Hlc = serde_json::from_value(case["state"].clone()).unwrap();
        let remote = Hlc::parse(case["remote"].as_str().unwrap()).unwrap();
        let got = clock.recv(&remote, case["now_ms"].as_i64().unwrap());
        assert_eq!(
            got.to_string(),
            case["expect"].as_str().unwrap(),
            "contract case '{}' failed",
            case["name"]
        );
    }
}

#[test]
fn contract_hlc_parse() {
    for case in contract("hlc.json")["parse"].as_array().unwrap() {
        let input = case["input"].as_str().unwrap();
        let parsed = Hlc::parse(input);
        if case["valid"].as_bool().unwrap() {
            let h = parsed.unwrap_or_else(|e| panic!("contract case '{input}' failed: {e}"));
            assert_eq!(h.millis, case["millis"].as_i64().unwrap());
            assert_eq!(h.counter as i64, case["counter"].as_i64().unwrap());
            assert_eq!(h.node, case["node"].as_str().unwrap());
            assert_eq!(h.to_string(), input);
        } else {
            assert!(
                parsed.is_err(),
                "contract case '{input}' failed: expected parse error"
            );
        }
    }
}

#[test]
fn contract_merge() {
    for case in contract("merge.json")["cases"].as_array().unwrap() {
        let name = case["name"].as_str().unwrap();
        let local: Vec<SyncRecord> = serde_json::from_value(case["local"].clone()).unwrap();
        let incoming: Vec<SyncRecord> = serde_json::from_value(case["incoming"].clone()).unwrap();
        let mut store = Store::default();
        store.merge(local);
        let applied: Vec<String> = store.merge(incoming).iter().map(|r| r.key()).collect();
        let expected_applied: Vec<String> =
            serde_json::from_value(case["applied"].clone()).unwrap();
        assert_eq!(
            applied, expected_applied,
            "contract case '{name}' failed (applied)"
        );
        let state = serde_json::to_value(store.records()).unwrap();
        assert_eq!(
            state, case["state"],
            "contract case '{name}' failed (state)"
        );
    }
}

#[test]
fn contract_periods() {
    for case in contract("periods.json")["cases"].as_array().unwrap() {
        let range: Range = case["range"].as_str().unwrap().parse().unwrap();
        let anchor = date(case["anchor"].as_str().unwrap());
        let cur = range.period(anchor);
        let prev = range.previous(anchor);
        let label = format!("{} {}", case["range"], case["anchor"]);
        assert_eq!(
            cur.start,
            date(case["start"].as_str().unwrap()),
            "contract case '{label}' failed"
        );
        assert_eq!(
            cur.end,
            date(case["end"].as_str().unwrap()),
            "contract case '{label}' failed"
        );
        assert_eq!(
            prev.start,
            date(case["prev_start"].as_str().unwrap()),
            "contract case '{label}' failed"
        );
        assert_eq!(
            prev.end,
            date(case["prev_end"].as_str().unwrap()),
            "contract case '{label}' failed"
        );
    }
}

#[test]
fn contract_reports() {
    let (txns, _, tz) = dataset();
    for case in contract("reports.json")["cases"].as_array().unwrap() {
        let range: Range = case["range"].as_str().unwrap().parse().unwrap();
        let anchor = date(case["anchor"].as_str().unwrap());
        let report = spend_report(&txns, &scope(case), range, anchor, tz);
        assert_eq!(
            serde_json::to_value(&report).unwrap(),
            case["expect"],
            "contract case '{}' failed",
            case["name"]
        );
    }
}

#[test]
fn contract_analytics_trend() {
    let (txns, _, tz) = dataset();
    for case in contract("analytics.json")["trend"].as_array().unwrap() {
        let anchor = date(case["anchor"].as_str().unwrap());
        let months = case["months"].as_u64().unwrap() as u32;
        let got = monthly_trend(&txns, &scope(case), anchor, months, tz);
        assert_eq!(
            serde_json::to_value(got).unwrap(),
            case["expect"],
            "contract case 'trend {}' failed",
            case["anchor"]
        );
    }
}

#[test]
fn contract_analytics_budgets() {
    let (txns, budgets, tz) = dataset();
    for case in contract("analytics.json")["budgets"].as_array().unwrap() {
        let anchor = date(case["anchor"].as_str().unwrap());
        let got = budget_status(&txns, &budgets, &scope(case), anchor, tz);
        assert_eq!(
            serde_json::to_value(got).unwrap(),
            case["expect"],
            "contract case 'budgets {}' failed",
            case["scope"]
        );
    }
}

#[test]
fn contract_analytics_anomalies() {
    let doc = contract("analytics.json");
    let section = &doc["anomalies"];
    let tz = section["tz_offset_minutes"].as_i64().unwrap() as i32;
    let txns = transactions(&section["transactions"], tz);
    for case in section["cases"].as_array().unwrap() {
        let anchor = date(case["anchor"].as_str().unwrap());
        let got = anomalies(&txns, &scope(case), anchor, tz);
        assert_eq!(
            serde_json::to_value(got).unwrap(),
            case["expect"],
            "contract case 'anomalies {} {}' failed",
            case["scope"],
            case["anchor"]
        );
    }
}

#[test]
fn contract_portfolio() {
    let doc = contract("portfolio.json");
    let input = &doc["input"];
    let holdings: Vec<Holding> = serde_json::from_value(input["holdings"].clone()).unwrap();
    let liabilities: Vec<Liability> = serde_json::from_value(input["liabilities"].clone()).unwrap();
    let goals: Vec<Goal> = serde_json::from_value(input["goals"].clone()).unwrap();
    let snapshots: Vec<NetWorthSnapshot> =
        serde_json::from_value(input["snapshots"].clone()).unwrap();
    let got = summarize(&holdings, &liabilities, &goals, &snapshots);
    assert_eq!(
        serde_json::to_value(got).unwrap(),
        doc["expect"],
        "contract case 'portfolio' failed"
    );
}
