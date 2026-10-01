//! Family investment portfolio and net worth (see contracts/portfolio.json).

use std::collections::BTreeMap;

use serde::Serialize;

use crate::model::{AssetClass, Goal, Holding, Liability, NetWorthSnapshot};
use crate::money::{change_bps, percent};

const NET_WORTH_TREND_POINTS: usize = 6;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct MemberPortfolio {
    pub member_id: String,
    pub value_minor: i64,
    pub invested_minor: i64,
    pub gain_bps: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Allocation {
    pub asset_class: &'static str,
    pub value_minor: i64,
    pub pct: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct GoalProgress {
    pub goal_id: String,
    pub name: String,
    pub target_minor: i64,
    pub saved_minor: i64,
    pub pct: i64,
    pub target_month: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct MonthValue {
    pub month: String,
    pub value_minor: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct PortfolioSummary {
    pub total_value_minor: i64,
    pub invested_minor: i64,
    pub gain_minor: i64,
    pub gain_bps: Option<i64>,
    pub members: Vec<MemberPortfolio>,
    pub allocation: Vec<Allocation>,
    pub liabilities_minor: i64,
    pub net_worth_minor: i64,
    pub goals: Vec<GoalProgress>,
    pub networth_trend: Vec<MonthValue>,
}

pub fn summarize(
    holdings: &[Holding],
    liabilities: &[Liability],
    goals: &[Goal],
    snapshots: &[NetWorthSnapshot],
) -> PortfolioSummary {
    let live: Vec<&Holding> = holdings.iter().filter(|h| !h.deleted).collect();
    let total: i64 = live.iter().map(|h| h.value_minor).sum();
    let invested: i64 = live.iter().map(|h| h.invested_minor).sum();

    let mut per_member: BTreeMap<&str, (i64, i64)> = BTreeMap::new();
    let mut per_class: BTreeMap<AssetClass, i64> = BTreeMap::new();
    for h in &live {
        let e = per_member.entry(h.member_id.as_str()).or_default();
        e.0 += h.value_minor;
        e.1 += h.invested_minor;
        *per_class.entry(h.asset_class).or_default() += h.value_minor;
    }
    let mut members: Vec<MemberPortfolio> = per_member
        .into_iter()
        .map(|(m, (value, inv))| MemberPortfolio {
            member_id: m.to_string(),
            value_minor: value,
            invested_minor: inv,
            gain_bps: change_bps(value, inv),
        })
        .collect();
    members.sort_by(|a, b| {
        b.value_minor
            .cmp(&a.value_minor)
            .then_with(|| a.member_id.cmp(&b.member_id))
    });

    let mut allocation: Vec<Allocation> = per_class
        .into_iter()
        .map(|(class, value)| Allocation {
            asset_class: class.as_str(),
            value_minor: value,
            pct: percent(value, total),
        })
        .collect();
    allocation.sort_by(|a, b| {
        b.value_minor
            .cmp(&a.value_minor)
            .then_with(|| a.asset_class.cmp(b.asset_class))
    });

    let owed: i64 = liabilities
        .iter()
        .filter(|l| !l.deleted)
        .map(|l| l.outstanding_minor)
        .sum();

    let mut goal_progress: Vec<GoalProgress> = goals
        .iter()
        .filter(|g| !g.deleted)
        .map(|g| GoalProgress {
            goal_id: g.id.clone(),
            name: g.name.clone(),
            target_minor: g.target_minor,
            saved_minor: g.saved_minor,
            pct: percent(g.saved_minor, g.target_minor),
            target_month: g.target_month.clone(),
        })
        .collect();
    goal_progress.sort_by(|a, b| a.name.cmp(&b.name));

    let mut trend: Vec<MonthValue> = snapshots
        .iter()
        .filter(|s| !s.deleted)
        .map(|s| MonthValue {
            month: s.month.clone(),
            value_minor: s.value_minor,
        })
        .collect();
    trend.sort_by(|a, b| a.month.cmp(&b.month));
    let skip = trend.len().saturating_sub(NET_WORTH_TREND_POINTS);
    trend.drain(..skip);

    PortfolioSummary {
        total_value_minor: total,
        invested_minor: invested,
        gain_minor: total - invested,
        gain_bps: change_bps(total, invested),
        members,
        allocation,
        liabilities_minor: owed,
        net_worth_minor: total - owed,
        goals: goal_progress,
        networth_trend: trend,
    }
}
