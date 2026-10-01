//! HTTP handlers (see docs/architecture.md#hub-http-api-v1).

use std::collections::HashSet;

use axum::extract::{FromRequestParts, Query, State};
use axum::http::request::Parts;
use axum::http::StatusCode;
use axum::routing::{get, post};
use axum::{Json, Router};
use chrono::NaiveDate;
use hearth_core::analytics::{
    anomalies, budget_status, monthly_trend, Anomaly, BudgetStatus, MonthTotal,
};
use hearth_core::model::Ledger;
use hearth_core::period::{local_date, Range};
use hearth_core::portfolio::{summarize, PortfolioSummary};
use hearth_core::report::{spend_report, MerchantTotal, Report, Scope};
use hearth_core::sync::SyncRecord;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::db::{Credentials, Device};
use crate::error::{ApiError, ApiResult};
use crate::AppState;

const MAX_PUSH: usize = 5_000;
const DEFAULT_TZ_OFFSET: i32 = 330; // IST
const CATEGORIES_JSON: &str = include_str!("../../../../contracts/categories.json");

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/healthz", get(healthz))
        .route("/v1/families", post(create_family))
        .route("/v1/join", post(join))
        .route("/v1/family", get(family))
        .route("/v1/categories", get(categories))
        .route("/v1/sync", post(sync))
        .route("/v1/sync/status", get(sync_status))
        .route("/v1/reports", get(reports))
        .route("/v1/analytics", get(analytics))
        .route("/v1/portfolio", get(portfolio))
        .with_state(state)
}

/// Authenticated device, extracted from `Authorization: Bearer <token>`.
pub struct Auth(pub Device);

impl FromRequestParts<AppState> for Auth {
    type Rejection = ApiError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let token = parts
            .headers
            .get(axum::http::header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .ok_or(ApiError::Unauthorized)?;
        let db = state.db.lock().await;
        Ok(Auth(db.authenticate(token.trim())?))
    }
}

fn required(field: &str, value: &str) -> ApiResult<String> {
    let v = value.trim();
    if v.is_empty() || v.len() > 80 {
        return Err(ApiError::BadRequest(format!(
            "{field} must be 1..=80 characters"
        )));
    }
    Ok(v.to_string())
}

async fn healthz() -> Json<Value> {
    Json(serde_json::json!({"status": "ok"}))
}

#[derive(Deserialize)]
struct CreateFamily {
    family_name: String,
    member_name: String,
    device_name: String,
    tz_offset_minutes: Option<i32>,
}

#[derive(Serialize)]
struct CredentialsResponse {
    family_id: String,
    member_id: String,
    device_id: String,
    token: String,
    invite_code: String,
    role: String,
}

impl From<Credentials> for CredentialsResponse {
    fn from(c: Credentials) -> Self {
        CredentialsResponse {
            family_id: c.family_id,
            member_id: c.member_id,
            device_id: c.device_id,
            token: c.token,
            invite_code: c.invite_code,
            role: c.role,
        }
    }
}

async fn create_family(
    State(state): State<AppState>,
    Json(body): Json<CreateFamily>,
) -> ApiResult<(StatusCode, Json<CredentialsResponse>)> {
    let family = required("family_name", &body.family_name)?;
    let member = required("member_name", &body.member_name)?;
    let device = required("device_name", &body.device_name)?;
    let tz = body.tz_offset_minutes.unwrap_or(DEFAULT_TZ_OFFSET);
    if !(-12 * 60..=14 * 60).contains(&tz) {
        return Err(ApiError::BadRequest(
            "tz_offset_minutes out of range".into(),
        ));
    }
    let now = state.now();
    let creds = state
        .db
        .lock()
        .await
        .create_family(&family, &member, &device, tz, now)?;
    Ok((StatusCode::CREATED, Json(creds.into())))
}

#[derive(Deserialize)]
struct JoinFamily {
    invite_code: String,
    member_name: String,
    device_name: String,
}

async fn join(
    State(state): State<AppState>,
    Json(body): Json<JoinFamily>,
) -> ApiResult<(StatusCode, Json<CredentialsResponse>)> {
    let member = required("member_name", &body.member_name)?;
    let device = required("device_name", &body.device_name)?;
    let now = state.now();
    let creds = state
        .db
        .lock()
        .await
        .join(&body.invite_code, &member, &device, now)?;
    Ok((StatusCode::CREATED, Json(creds.into())))
}

#[derive(Serialize)]
struct MemberView {
    id: String,
    name: String,
    role: String,
}

#[derive(Serialize)]
struct FamilyView {
    id: String,
    name: String,
    invite_code: String,
    tz_offset_minutes: i32,
    me: String,
    members: Vec<MemberView>,
}

async fn family(State(state): State<AppState>, Auth(dev): Auth) -> ApiResult<Json<FamilyView>> {
    let db = state.db.lock().await;
    let fam = db.family(&dev.family_id)?;
    let members = db
        .members(&dev.family_id)?
        .into_iter()
        .map(|m| MemberView {
            id: m.id,
            name: m.name,
            role: m.role,
        })
        .collect();
    Ok(Json(FamilyView {
        id: fam.id,
        name: fam.name,
        invite_code: fam.invite_code,
        tz_offset_minutes: fam.tz_offset_minutes,
        me: dev.member_id,
        members,
    }))
}

async fn categories(_auth: Auth) -> ApiResult<Json<Value>> {
    Ok(Json(serde_json::from_str(CATEGORIES_JSON)?))
}

#[derive(Deserialize)]
struct SyncRequest {
    since: i64,
    #[serde(default)]
    records: Vec<SyncRecord>,
}

#[derive(Serialize)]
struct Rejected {
    entity: String,
    id: String,
    reason: String,
}

#[derive(Serialize)]
struct SyncResponse {
    applied: usize,
    rejected: Vec<Rejected>,
    cursor: i64,
    records: Vec<SyncRecord>,
}

async fn sync(
    State(state): State<AppState>,
    Auth(dev): Auth,
    Json(body): Json<SyncRequest>,
) -> ApiResult<Json<SyncResponse>> {
    if body.records.len() > MAX_PUSH {
        return Err(ApiError::BadRequest(format!(
            "at most {MAX_PUSH} records per sync"
        )));
    }
    let members: HashSet<String> = state
        .db
        .lock()
        .await
        .members(&dev.family_id)?
        .into_iter()
        .map(|m| m.id)
        .collect();
    let mut accepted = Vec::with_capacity(body.records.len());
    let mut rejected = Vec::new();
    for mut rec in body.records {
        match rec
            .validate()
            .and_then(|()| check_member_ref(&rec, &members))
        {
            Ok(()) => {
                rec.author = dev.member_id.clone(); // the pushing member is the author; never trust the client
                accepted.push(rec);
            }
            Err(reason) => rejected.push(Rejected {
                entity: rec.entity,
                id: rec.id,
                reason,
            }),
        }
    }
    let now = state.now();
    let mut db = state.db.lock().await;
    let applied = db.apply(&dev.family_id, &accepted)?;
    let (records, cursor) = db.changes_since(&dev.family_id, body.since.max(0))?;
    db.touch_device(&dev.device_id, now)?;
    Ok(Json(SyncResponse {
        applied,
        rejected,
        cursor,
        records,
    }))
}

/// A live payload's `member_id` (when present and not null) must be a member of this family.
fn check_member_ref(rec: &SyncRecord, members: &HashSet<String>) -> Result<(), String> {
    if rec.deleted {
        return Ok(());
    }
    match rec.payload.get("member_id") {
        None | Some(Value::Null) => Ok(()),
        Some(Value::String(m)) if members.contains(m) => Ok(()),
        Some(other) => Err(format!("member_id {other} is not a member of this family")),
    }
}

#[derive(Serialize)]
struct MemberSync {
    member_id: String,
    name: String,
    last_sync_ms: Option<i64>,
}

#[derive(Serialize)]
struct SyncStatus {
    now_ms: i64,
    members: Vec<MemberSync>,
}

async fn sync_status(
    State(state): State<AppState>,
    Auth(dev): Auth,
) -> ApiResult<Json<SyncStatus>> {
    let members = state
        .db
        .lock()
        .await
        .members(&dev.family_id)?
        .into_iter()
        .map(|m| MemberSync {
            member_id: m.id,
            name: m.name,
            last_sync_ms: m.last_sync_ms,
        })
        .collect();
    Ok(Json(SyncStatus {
        now_ms: state.now(),
        members,
    }))
}

#[derive(Deserialize)]
struct ReportQuery {
    scope: Option<String>,
    range: Option<String>,
    anchor: Option<String>,
    months: Option<u32>,
}

struct Context {
    ledger: Ledger,
    scope: Scope,
    anchor: NaiveDate,
    tz: i32,
}

async fn context(state: &AppState, dev: &Device, q: &ReportQuery) -> ApiResult<Context> {
    let db = state.db.lock().await;
    let tz = db.family(&dev.family_id)?.tz_offset_minutes;
    let ledger = Ledger::from_records(&db.all_records(&dev.family_id)?);
    drop(db);
    let scope = match q.scope.as_deref().unwrap_or("family") {
        "family" => Scope::Family,
        "me" => Scope::Member(dev.member_id.clone()),
        other => {
            return Err(ApiError::BadRequest(format!(
                "unknown scope {other:?} (me|family)"
            )))
        }
    };
    let anchor = match &q.anchor {
        Some(a) => NaiveDate::parse_from_str(a, "%Y-%m-%d")
            .map_err(|_| ApiError::BadRequest("anchor must be YYYY-MM-DD".into()))?,
        None => local_date(state.now(), tz),
    };
    Ok(Context {
        ledger,
        scope,
        anchor,
        tz,
    })
}

#[derive(Serialize)]
struct ReportResponse {
    range: Range,
    anchor: NaiveDate,
    start: NaiveDate,
    end: NaiveDate,
    #[serde(flatten)]
    report: Report,
}

async fn reports(
    State(state): State<AppState>,
    Auth(dev): Auth,
    Query(q): Query<ReportQuery>,
) -> ApiResult<Json<ReportResponse>> {
    let range: Range = q
        .range
        .as_deref()
        .unwrap_or("month")
        .parse()
        .map_err(ApiError::BadRequest)?;
    let ctx = context(&state, &dev, &q).await?;
    let period = range.period(ctx.anchor);
    let report = spend_report(
        &ctx.ledger.transactions,
        &ctx.scope,
        range,
        ctx.anchor,
        ctx.tz,
    );
    Ok(Json(ReportResponse {
        range,
        anchor: ctx.anchor,
        start: period.start,
        end: period.end,
        report,
    }))
}

#[derive(Serialize)]
struct AnalyticsResponse {
    anchor: NaiveDate,
    trend: Vec<MonthTotal>,
    budgets: Vec<BudgetStatus>,
    anomalies: Vec<Anomaly>,
    top_merchants: Vec<MerchantTotal>,
}

async fn analytics(
    State(state): State<AppState>,
    Auth(dev): Auth,
    Query(q): Query<ReportQuery>,
) -> ApiResult<Json<AnalyticsResponse>> {
    let months = q.months.unwrap_or(6);
    if !(1..=36).contains(&months) {
        return Err(ApiError::BadRequest("months must be 1..=36".into()));
    }
    let ctx = context(&state, &dev, &q).await?;
    let txns = &ctx.ledger.transactions;
    Ok(Json(AnalyticsResponse {
        anchor: ctx.anchor,
        trend: monthly_trend(txns, &ctx.scope, ctx.anchor, months, ctx.tz),
        budgets: budget_status(txns, &ctx.ledger.budgets, &ctx.scope, ctx.anchor, ctx.tz),
        anomalies: anomalies(txns, &ctx.scope, ctx.anchor, ctx.tz),
        top_merchants: spend_report(txns, &ctx.scope, Range::Month, ctx.anchor, ctx.tz)
            .top_merchants,
    }))
}

async fn portfolio(
    State(state): State<AppState>,
    Auth(dev): Auth,
) -> ApiResult<Json<PortfolioSummary>> {
    let records = state.db.lock().await.all_records(&dev.family_id)?;
    let l = Ledger::from_records(&records);
    Ok(Json(summarize(
        &l.holdings,
        &l.liabilities,
        &l.goals,
        &l.snapshots,
    )))
}
