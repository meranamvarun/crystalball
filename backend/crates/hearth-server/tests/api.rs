//! End-to-end tests of the hub HTTP API against an in-memory SQLite database.

use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Arc;

use axum::body::Body;
use axum::http::{Request, StatusCode};
use axum::Router;
use hearth_server::{app, AppState, Db};
use http_body_util::BodyExt;
use serde_json::{json, Value};
use tower::ServiceExt;

/// 2026-09-29T14:30:00Z (20:00 IST).
const NOW_MS: i64 = 1_790_692_200_000;

struct Hub {
    router: Router,
    clock: Arc<AtomicI64>,
}

impl Hub {
    fn new() -> Hub {
        let clock = Arc::new(AtomicI64::new(NOW_MS));
        let c = clock.clone();
        let state = AppState::new(
            Db::open_in_memory().unwrap(),
            Arc::new(move || c.load(Ordering::SeqCst)),
        );
        Hub {
            router: app(state),
            clock,
        }
    }

    async fn call(
        &self,
        method: &str,
        uri: &str,
        token: Option<&str>,
        body: Option<Value>,
    ) -> (StatusCode, Value) {
        let mut req = Request::builder().method(method).uri(uri);
        if let Some(t) = token {
            req = req.header("authorization", format!("Bearer {t}"));
        }
        let req = match body {
            Some(b) => req
                .header("content-type", "application/json")
                .body(Body::from(b.to_string())),
            None => req.body(Body::empty()),
        }
        .unwrap();
        let resp = self.router.clone().oneshot(req).await.unwrap();
        let status = resp.status();
        let bytes = resp.into_body().collect().await.unwrap().to_bytes();
        let value = if bytes.is_empty() {
            Value::Null
        } else {
            serde_json::from_slice(&bytes).unwrap()
        };
        (status, value)
    }

    async fn create_family(&self) -> Value {
        let (s, v) = self
            .call(
                "POST",
                "/v1/families",
                None,
                Some(json!({"family_name": "The Sharma Family", "member_name": "You", "device_name": "Pixel 8"})),
            )
            .await;
        assert_eq!(s, StatusCode::CREATED, "{v}");
        v
    }

    async fn join(&self, invite: &str, name: &str) -> Value {
        let (s, v) = self
            .call(
                "POST",
                "/v1/join",
                None,
                Some(json!({"invite_code": invite, "member_name": name, "device_name": "phone"})),
            )
            .await;
        assert_eq!(s, StatusCode::CREATED, "{v}");
        v
    }
}

fn token(v: &Value) -> String {
    v["token"].as_str().unwrap().to_string()
}

fn txn(
    id: &str,
    hlc: &str,
    member: &str,
    amount: i64,
    category: Option<&str>,
    at_ms: i64,
) -> Value {
    json!({
        "entity": "transaction", "id": id, "hlc": hlc, "deleted": false, "author": member,
        "payload": {"member_id": member, "amount_minor": amount, "direction": "debit", "merchant": "Big Bazaar",
                    "category": category, "occurred_at_ms": at_ms, "source": "sms"}
    })
}

#[tokio::test]
async fn healthz() {
    let hub = Hub::new();
    let (s, v) = hub.call("GET", "/healthz", None, None).await;
    assert_eq!(s, StatusCode::OK);
    assert_eq!(v["status"], "ok");
}

#[tokio::test]
async fn create_family_then_read_it_back() {
    let hub = Hub::new();
    let created = hub.create_family().await;
    assert_eq!(created["role"], "admin");
    assert_eq!(
        created["invite_code"].as_str().unwrap().len(),
        9,
        "XXXX-XXXX"
    );
    let (s, fam) = hub
        .call("GET", "/v1/family", Some(&token(&created)), None)
        .await;
    assert_eq!(s, StatusCode::OK);
    assert_eq!(fam["name"], "The Sharma Family");
    assert_eq!(fam["me"], created["member_id"]);
    assert_eq!(fam["tz_offset_minutes"], 330);
    assert_eq!(fam["members"].as_array().unwrap().len(), 1);
}

#[tokio::test]
async fn create_family_validates_input() {
    let hub = Hub::new();
    let (s, v) = hub
        .call(
            "POST",
            "/v1/families",
            None,
            Some(json!({"family_name": " ", "member_name": "x", "device_name": "d"})),
        )
        .await;
    assert_eq!(s, StatusCode::BAD_REQUEST);
    assert!(v["error"].as_str().unwrap().contains("family_name"));
}

#[tokio::test]
async fn join_with_invite_code() {
    let hub = Hub::new();
    let admin = hub.create_family().await;
    let invite = admin["invite_code"].as_str().unwrap().to_lowercase(); // codes are case-insensitive
    let priya = hub.join(&invite, "Priya").await;
    assert_eq!(priya["family_id"], admin["family_id"]);
    assert_eq!(priya["role"], "member");
    let (_, fam) = hub
        .call("GET", "/v1/family", Some(&token(&priya)), None)
        .await;
    let names: Vec<&str> = fam["members"]
        .as_array()
        .unwrap()
        .iter()
        .map(|m| m["name"].as_str().unwrap())
        .collect();
    assert_eq!(names, vec!["Priya", "You"]);
}

#[tokio::test]
async fn join_with_unknown_invite_is_not_found() {
    let hub = Hub::new();
    let (s, _) = hub
        .call(
            "POST",
            "/v1/join",
            None,
            Some(json!({"invite_code": "ZZZZ-ZZZZ", "member_name": "x", "device_name": "d"})),
        )
        .await;
    assert_eq!(s, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn requests_without_valid_token_are_unauthorized() {
    let hub = Hub::new();
    hub.create_family().await;
    assert_eq!(
        hub.call("GET", "/v1/family", None, None).await.0,
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        hub.call("GET", "/v1/family", Some("deadbeef"), None)
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn sync_round_trip_between_two_phones() {
    let hub = Hub::new();
    let admin = hub.create_family().await;
    let priya = hub
        .join(admin["invite_code"].as_str().unwrap(), "Priya")
        .await;
    let me = admin["member_id"].as_str().unwrap();

    let push = json!({"since": 0, "records": [txn("t1", "1790692200000-0000-a", me, 184000, Some("Groceries"), NOW_MS)]});
    let (s, v) = hub
        .call("POST", "/v1/sync", Some(&token(&admin)), Some(push))
        .await;
    assert_eq!(s, StatusCode::OK, "{v}");
    assert_eq!(v["applied"], 1);
    assert_eq!(v["rejected"], json!([]));

    let (_, pulled) = hub
        .call(
            "POST",
            "/v1/sync",
            Some(&token(&priya)),
            Some(json!({"since": 0, "records": []})),
        )
        .await;
    let records = pulled["records"].as_array().unwrap();
    assert_eq!(records.len(), 1);
    assert_eq!(records[0]["id"], "t1");
    assert_eq!(records[0]["payload"]["amount_minor"], 184000);
    let cursor = pulled["cursor"].as_i64().unwrap();
    assert!(cursor > 0);

    // Nothing new after the cursor.
    let (_, again) = hub
        .call(
            "POST",
            "/v1/sync",
            Some(&token(&priya)),
            Some(json!({"since": cursor, "records": []})),
        )
        .await;
    assert_eq!(again["records"], json!([]));
    assert_eq!(again["cursor"].as_i64().unwrap(), cursor);

    // Priya recategorizes with a newer HLC; an older concurrent edit from the admin loses.
    let newer = txn(
        "t1",
        "1790692200001-0000-b",
        me,
        184000,
        Some("Shopping"),
        NOW_MS,
    );
    let older = txn(
        "t1",
        "1790692199999-0000-a",
        me,
        184000,
        Some("Dining"),
        NOW_MS,
    );
    let (_, v) = hub
        .call(
            "POST",
            "/v1/sync",
            Some(&token(&priya)),
            Some(json!({"since": cursor, "records": [newer]})),
        )
        .await;
    assert_eq!(v["applied"], 1);
    let (_, v) = hub
        .call(
            "POST",
            "/v1/sync",
            Some(&token(&admin)),
            Some(json!({"since": 0, "records": [older]})),
        )
        .await;
    assert_eq!(v["applied"], 0);
    let rec = &v["records"].as_array().unwrap()[0];
    assert_eq!(rec["payload"]["category"], "Shopping");
    assert_eq!(
        rec["author"], priya["member_id"],
        "author is the member who pushed the winning edit"
    );
}

#[tokio::test]
async fn invalid_records_are_rejected_individually() {
    let hub = Hub::new();
    let admin = hub.create_family().await;
    let me = admin["member_id"].as_str().unwrap();
    let mut bad = txn("t2", "1790692200000-0000-a", me, -5, None, NOW_MS);
    bad["payload"]["amount_minor"] = json!(-5);
    let body = json!({"since": 0, "records": [
        txn("t1", "1790692200000-0000-a", me, 100, None, NOW_MS),
        bad,
        {"entity": "transaction", "id": "t3", "hlc": "garbage", "deleted": false, "author": me, "payload": {}}
    ]});
    let (s, v) = hub
        .call("POST", "/v1/sync", Some(&token(&admin)), Some(body))
        .await;
    assert_eq!(s, StatusCode::OK);
    assert_eq!(v["applied"], 1);
    let rejected: Vec<&str> = v["rejected"]
        .as_array()
        .unwrap()
        .iter()
        .map(|r| r["id"].as_str().unwrap())
        .collect();
    assert_eq!(rejected, vec!["t2", "t3"]);
}

#[tokio::test]
async fn families_are_isolated() {
    let hub = Hub::new();
    let a = hub.create_family().await;
    let b = hub.create_family().await;
    let me = a["member_id"].as_str().unwrap();
    let push =
        json!({"since": 0, "records": [txn("t1", "1790692200000-0000-a", me, 100, None, NOW_MS)]});
    hub.call("POST", "/v1/sync", Some(&token(&a)), Some(push))
        .await;
    let (_, v) = hub
        .call(
            "POST",
            "/v1/sync",
            Some(&token(&b)),
            Some(json!({"since": 0, "records": []})),
        )
        .await;
    assert_eq!(v["records"], json!([]));
}

#[tokio::test]
async fn reports_cover_family_and_personal_scope() {
    let hub = Hub::new();
    let admin = hub.create_family().await;
    let priya = hub
        .join(admin["invite_code"].as_str().unwrap(), "Priya")
        .await;
    let me = admin["member_id"].as_str().unwrap();
    let her = priya["member_id"].as_str().unwrap();
    let records = json!({"since": 0, "records": [
        txn("t1", "1790692200000-0000-a", me, 184000, Some("Groceries"), NOW_MS),
        txn("t2", "1790692200000-0001-a", her, 78000, Some("Dining"), NOW_MS - 3_600_000),
        txn("t3", "1790692200000-0002-a", me, 85000, None, NOW_MS - 3 * 86_400_000),
    ]});
    hub.call("POST", "/v1/sync", Some(&token(&admin)), Some(records))
        .await;

    let (s, fam) = hub
        .call(
            "GET",
            "/v1/reports?scope=family&range=month&anchor=2026-09-29",
            Some(&token(&admin)),
            None,
        )
        .await;
    assert_eq!(s, StatusCode::OK, "{fam}");
    assert_eq!(fam["total_minor"], 347000);
    assert_eq!(fam["needs_review"], 1);
    assert_eq!(fam["start"], "2026-09-01");
    assert_eq!(fam["end"], "2026-10-01");
    assert_eq!(fam["members"].as_array().unwrap().len(), 2);

    let (_, mine) = hub
        .call(
            "GET",
            "/v1/reports?scope=me&range=day&anchor=2026-09-29",
            Some(&token(&admin)),
            None,
        )
        .await;
    assert_eq!(mine["total_minor"], 184000);

    // anchor defaults to "today" in the family's timezone
    let (_, today) = hub
        .call(
            "GET",
            "/v1/reports?scope=family&range=day",
            Some(&token(&admin)),
            None,
        )
        .await;
    assert_eq!(today["start"], "2026-09-29");
    assert_eq!(today["total_minor"], 262000);

    let (s, _) = hub
        .call(
            "GET",
            "/v1/reports?range=fortnight",
            Some(&token(&admin)),
            None,
        )
        .await;
    assert_eq!(s, StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn analytics_and_portfolio() {
    let hub = Hub::new();
    let admin = hub.create_family().await;
    let me = admin["member_id"].as_str().unwrap();
    let records = json!({"since": 0, "records": [
        txn("t1", "1790692200000-0000-a", me, 184000, Some("Groceries"), NOW_MS),
        {"entity": "budget", "id": "b1", "hlc": "1790692200000-0001-a", "deleted": false, "author": me,
         "payload": {"category": "Groceries", "cap_minor": 150000, "member_id": null}},
        {"entity": "holding", "id": "h1", "hlc": "1790692200000-0002-a", "deleted": false, "author": me,
         "payload": {"member_id": me, "name": "NIFTY ETF", "asset_class": "equity", "invested_minor": 100, "value_minor": 150}},
        {"entity": "liability", "id": "l1", "hlc": "1790692200000-0003-a", "deleted": false, "author": me,
         "payload": {"member_id": me, "name": "Loan", "outstanding_minor": 50}}
    ]});
    let (_, v) = hub
        .call("POST", "/v1/sync", Some(&token(&admin)), Some(records))
        .await;
    assert_eq!(v["applied"], 4, "{v}");

    let (s, a) = hub
        .call(
            "GET",
            "/v1/analytics?scope=family&anchor=2026-09-29&months=3",
            Some(&token(&admin)),
            None,
        )
        .await;
    assert_eq!(s, StatusCode::OK, "{a}");
    assert_eq!(a["trend"].as_array().unwrap().len(), 3);
    assert_eq!(a["trend"][2]["total_minor"], 184000);
    assert_eq!(a["budgets"][0]["over"], true);
    assert_eq!(a["budgets"][0]["used_pct"], 123);
    assert_eq!(a["top_merchants"][0]["merchant"], "Big Bazaar");
    assert!(a["anomalies"].is_array());

    let (s, p) = hub
        .call("GET", "/v1/portfolio", Some(&token(&admin)), None)
        .await;
    assert_eq!(s, StatusCode::OK);
    assert_eq!(p["total_value_minor"], 150);
    assert_eq!(p["net_worth_minor"], 100);
    assert_eq!(p["gain_bps"], 5000);
}

#[tokio::test]
async fn sync_status_reports_last_sync_per_member() {
    let hub = Hub::new();
    let admin = hub.create_family().await;
    let priya = hub
        .join(admin["invite_code"].as_str().unwrap(), "Priya")
        .await;
    hub.call(
        "POST",
        "/v1/sync",
        Some(&token(&admin)),
        Some(json!({"since": 0, "records": []})),
    )
    .await;
    hub.clock.store(NOW_MS + 120_000, Ordering::SeqCst);
    let (s, v) = hub
        .call("GET", "/v1/sync/status", Some(&token(&priya)), None)
        .await;
    assert_eq!(s, StatusCode::OK);
    assert_eq!(v["now_ms"], NOW_MS + 120_000);
    let members = v["members"].as_array().unwrap();
    let you = members.iter().find(|m| m["name"] == "You").unwrap();
    let her = members.iter().find(|m| m["name"] == "Priya").unwrap();
    assert_eq!(you["last_sync_ms"], NOW_MS);
    assert_eq!(her["last_sync_ms"], Value::Null);
}

#[tokio::test]
async fn categories_endpoint_serves_default_rules() {
    let hub = Hub::new();
    let admin = hub.create_family().await;
    let (s, v) = hub
        .call("GET", "/v1/categories", Some(&token(&admin)), None)
        .await;
    assert_eq!(s, StatusCode::OK);
    assert!(v["categories"]
        .as_array()
        .unwrap()
        .iter()
        .any(|c| c == "Groceries"));
    assert!(!v["rules"].as_array().unwrap().is_empty());
}

#[tokio::test]
async fn records_must_reference_members_of_the_family() {
    let hub = Hub::new();
    let admin = hub.create_family().await;
    let other = hub.create_family().await;
    let stranger = other["member_id"].as_str().unwrap();
    let body = json!({"since": 0, "records": [
        txn("t1", "1790692200000-0000-a", stranger, 100, None, NOW_MS),
        {"entity": "budget", "id": "b1", "hlc": "1790692200000-0001-a", "deleted": false, "author": "x",
         "payload": {"category": "Dining", "cap_minor": 100, "member_id": "m_nobody"}},
        {"entity": "budget", "id": "b2", "hlc": "1790692200000-0002-a", "deleted": false, "author": "x",
         "payload": {"category": "Dining", "cap_minor": 100, "member_id": null}}
    ]});
    let (_, v) = hub
        .call("POST", "/v1/sync", Some(&token(&admin)), Some(body))
        .await;
    assert_eq!(v["applied"], 1, "{v}");
    let rejected: Vec<&str> = v["rejected"]
        .as_array()
        .unwrap()
        .iter()
        .map(|r| r["id"].as_str().unwrap())
        .collect();
    assert_eq!(rejected, vec!["t1", "b1"]);
    assert!(v["rejected"][0]["reason"]
        .as_str()
        .unwrap()
        .contains("member"));
}
