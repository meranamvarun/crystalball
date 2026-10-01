//! Spend reports for a scope and period (see contracts/reports.json).

use std::collections::BTreeMap;

use chrono::NaiveDate;
use serde::Serialize;

use crate::model::Transaction;
use crate::money::{change_bps, percent};
use crate::period::{Period, Range};

pub const UNCATEGORIZED: &str = "Uncategorized";
const TOP_MERCHANTS: usize = 5;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Scope {
    Family,
    Member(String),
}

impl Scope {
    pub fn includes(&self, t: &Transaction) -> bool {
        match self {
            Scope::Family => true,
            Scope::Member(m) => &t.member_id == m,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct CategoryTotal {
    pub category: Option<String>,
    pub amount_minor: i64,
    pub pct: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct MemberTotal {
    pub member_id: String,
    pub amount_minor: i64,
    pub top_category: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct MerchantTotal {
    pub merchant: String,
    pub amount_minor: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Report {
    pub total_minor: i64,
    pub prev_total_minor: i64,
    pub delta_bps: Option<i64>,
    pub categories: Vec<CategoryTotal>,
    pub members: Vec<MemberTotal>,
    pub top_merchants: Vec<MerchantTotal>,
    pub needs_review: usize,
}

/// Spend transactions (non-deleted debits) of `scope` inside `period`.
pub fn spend_in<'a>(
    txns: &'a [Transaction],
    scope: &'a Scope,
    period: Period,
    tz: i32,
) -> impl Iterator<Item = &'a Transaction> + 'a {
    let (start, end) = period.to_ms(tz);
    txns.iter().filter(move |t| {
        t.is_spend() && scope.includes(t) && t.occurred_at_ms >= start && t.occurred_at_ms < end
    })
}

fn category_sort_key(c: &Option<String>) -> &str {
    c.as_deref().unwrap_or(UNCATEGORIZED)
}

/// Sum per category, ordered by amount desc then category name.
pub fn by_category<'a>(txns: impl Iterator<Item = &'a Transaction>) -> Vec<(Option<String>, i64)> {
    let mut sums: BTreeMap<Option<String>, i64> = BTreeMap::new();
    for t in txns {
        *sums.entry(t.category.clone()).or_default() += t.amount_minor;
    }
    let mut out: Vec<_> = sums.into_iter().collect();
    out.sort_by(|a, b| {
        b.1.cmp(&a.1)
            .then_with(|| category_sort_key(&a.0).cmp(category_sort_key(&b.0)))
    });
    out
}

pub fn spend_report(
    txns: &[Transaction],
    scope: &Scope,
    range: Range,
    anchor: NaiveDate,
    tz: i32,
) -> Report {
    let current: Vec<&Transaction> = spend_in(txns, scope, range.period(anchor), tz).collect();
    let prev_total: i64 = spend_in(txns, scope, range.previous(anchor), tz)
        .map(|t| t.amount_minor)
        .sum();
    let total: i64 = current.iter().map(|t| t.amount_minor).sum();

    let categories = by_category(current.iter().copied())
        .into_iter()
        .map(|(category, amount)| CategoryTotal {
            category,
            amount_minor: amount,
            pct: percent(amount, total),
        })
        .collect();

    let mut per_member: BTreeMap<&str, Vec<&Transaction>> = BTreeMap::new();
    for t in &current {
        per_member.entry(t.member_id.as_str()).or_default().push(t);
    }
    let mut members: Vec<MemberTotal> = per_member
        .into_iter()
        .map(|(member, ts)| MemberTotal {
            member_id: member.to_string(),
            amount_minor: ts.iter().map(|t| t.amount_minor).sum(),
            top_category: by_category(ts.iter().copied())
                .into_iter()
                .next()
                .and_then(|(c, _)| c),
        })
        .collect();
    members.sort_by(|a, b| {
        b.amount_minor
            .cmp(&a.amount_minor)
            .then_with(|| a.member_id.cmp(&b.member_id))
    });

    let mut merchants: BTreeMap<&str, i64> = BTreeMap::new();
    for t in &current {
        *merchants.entry(t.merchant.as_str()).or_default() += t.amount_minor;
    }
    let mut top_merchants: Vec<MerchantTotal> = merchants
        .into_iter()
        .map(|(m, a)| MerchantTotal {
            merchant: m.to_string(),
            amount_minor: a,
        })
        .collect();
    top_merchants.sort_by(|a, b| {
        b.amount_minor
            .cmp(&a.amount_minor)
            .then_with(|| a.merchant.cmp(&b.merchant))
    });
    top_merchants.truncate(TOP_MERCHANTS);

    Report {
        total_minor: total,
        prev_total_minor: prev_total,
        delta_bps: change_bps(total, prev_total),
        categories,
        members,
        top_merchants,
        needs_review: current.iter().filter(|t| t.category.is_none()).count(),
    }
}
