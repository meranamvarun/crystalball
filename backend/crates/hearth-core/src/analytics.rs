//! Trend, budget-vs-actual and unusual-spend detection (see contracts/analytics.json).

use chrono::{Months, NaiveDate};
use serde::Serialize;

use crate::model::{Budget, Transaction};
use crate::money::{percent, round_div_wide};
use crate::period::{month_start, Range};
use crate::report::{spend_in, Scope};

const ANOMALY_FACTOR: i64 = 3;
const ANOMALY_MIN_BASELINE: i64 = 3;
const ANOMALY_WINDOW_MS: i64 = 90 * 24 * 60 * 60 * 1000;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct MonthTotal {
    pub month: String,
    pub total_minor: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct BudgetStatus {
    pub budget_id: String,
    pub category: String,
    pub cap_minor: i64,
    pub spent_minor: i64,
    pub used_pct: i64,
    pub over: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Anomaly {
    pub transaction_id: String,
    pub category: String,
    pub amount_minor: i64,
    pub ratio_pct: i64,
}

/// Spend totals for the `months` calendar months ending with the anchor's month, oldest first.
pub fn monthly_trend(
    txns: &[Transaction],
    scope: &Scope,
    anchor: NaiveDate,
    months: u32,
    tz: i32,
) -> Vec<MonthTotal> {
    let last = month_start(anchor);
    (0..months)
        .rev()
        .map(|back| {
            let m = last - Months::new(back);
            MonthTotal {
                month: m.format("%Y-%m").to_string(),
                total_minor: spend_in(txns, scope, Range::Month.period(m), tz)
                    .map(|t| t.amount_minor)
                    .sum(),
            }
        })
        .collect()
}

fn budget_in_scope(b: &Budget, scope: &Scope) -> bool {
    !b.deleted
        && match scope {
            Scope::Family => b.member_id.is_none(),
            Scope::Member(m) => b.member_id.as_deref() == Some(m.as_str()),
        }
}

/// Budgets of the scope against the anchor month's spend, sorted by category.
pub fn budget_status(
    txns: &[Transaction],
    budgets: &[Budget],
    scope: &Scope,
    anchor: NaiveDate,
    tz: i32,
) -> Vec<BudgetStatus> {
    let month = Range::Month.period(anchor);
    let mut out: Vec<BudgetStatus> = budgets
        .iter()
        .filter(|b| budget_in_scope(b, scope))
        .map(|b| {
            let spent: i64 = spend_in(txns, scope, month, tz)
                .filter(|t| t.category.as_deref() == Some(b.category.as_str()))
                .map(|t| t.amount_minor)
                .sum();
            BudgetStatus {
                budget_id: b.id.clone(),
                category: b.category.clone(),
                cap_minor: b.cap_minor,
                spent_minor: spent,
                used_pct: percent(spent, b.cap_minor),
                over: spent > b.cap_minor,
            }
        })
        .collect();
    out.sort_by(|a, b| {
        a.category
            .cmp(&b.category)
            .then_with(|| a.budget_id.cmp(&b.budget_id))
    });
    out
}

/// Debits in the anchor month that are ≥ 3× the scope's usual spend in that category, newest first.
pub fn anomalies(txns: &[Transaction], scope: &Scope, anchor: NaiveDate, tz: i32) -> Vec<Anomaly> {
    let month = Range::Month.period(anchor);
    let mut found: Vec<(i64, Anomaly)> = spend_in(txns, scope, month, tz)
        .filter_map(|t| {
            let category = t.category.as_ref()?;
            let (count, sum) = txns
                .iter()
                .filter(|b| {
                    b.is_spend()
                        && scope.includes(b)
                        && b.category.as_ref() == Some(category)
                        && b.occurred_at_ms < t.occurred_at_ms
                        && b.occurred_at_ms >= t.occurred_at_ms - ANOMALY_WINDOW_MS
                })
                .fold((0i64, 0i64), |(c, s), b| (c + 1, s + b.amount_minor));
            if count < ANOMALY_MIN_BASELINE || sum == 0 {
                return None;
            }
            // amount >= FACTOR * (sum / count), kept in integers.
            if (t.amount_minor as i128) * (count as i128) < (ANOMALY_FACTOR as i128) * (sum as i128)
            {
                return None;
            }
            Some((
                t.occurred_at_ms,
                Anomaly {
                    transaction_id: t.id.clone(),
                    category: category.clone(),
                    amount_minor: t.amount_minor,
                    ratio_pct: round_div_wide(
                        t.amount_minor as i128 * 100 * count as i128,
                        sum as i128,
                    ),
                },
            ))
        })
        .collect();
    found.sort_by(|a, b| {
        b.0.cmp(&a.0)
            .then_with(|| a.1.transaction_id.cmp(&b.1.transaction_id))
    });
    found.into_iter().map(|(_, a)| a).collect()
}
