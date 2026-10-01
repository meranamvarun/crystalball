//! Reporting periods in family-local time (see contracts/periods.json).

use std::str::FromStr;

use chrono::{DateTime, Datelike, Days, Months, NaiveDate, NaiveDateTime};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Range {
    Day,
    Week,
    Month,
    Year,
}

impl FromStr for Range {
    type Err = String;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s {
            "day" => Ok(Range::Day),
            "week" => Ok(Range::Week),
            "month" => Ok(Range::Month),
            "year" => Ok(Range::Year),
            other => Err(format!("unknown range {other:?} (day|week|month|year)")),
        }
    }
}

/// Local dates, `end` exclusive.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Period {
    pub start: NaiveDate,
    pub end: NaiveDate,
}

impl Period {
    /// Epoch-millisecond bounds `[start, end)` for a family at `tz_offset_minutes`.
    pub fn to_ms(self, tz_offset_minutes: i32) -> (i64, i64) {
        (
            local_to_ms(midnight(self.start), tz_offset_minutes),
            local_to_ms(midnight(self.end), tz_offset_minutes),
        )
    }

    pub fn contains_ms(self, ms: i64, tz_offset_minutes: i32) -> bool {
        let (s, e) = self.to_ms(tz_offset_minutes);
        ms >= s && ms < e
    }
}

impl Range {
    pub fn period(self, anchor: NaiveDate) -> Period {
        let start = match self {
            Range::Day => anchor,
            Range::Week => anchor - Days::new(anchor.weekday().num_days_from_monday() as u64),
            Range::Month => month_start(anchor),
            Range::Year => NaiveDate::from_ymd_opt(anchor.year(), 1, 1).expect("valid date"),
        };
        Period {
            start,
            end: self.advance(start),
        }
    }

    pub fn previous(self, anchor: NaiveDate) -> Period {
        let current = self.period(anchor);
        let start = match self {
            Range::Day => current.start - Days::new(1),
            Range::Week => current.start - Days::new(7),
            Range::Month => current.start - Months::new(1),
            Range::Year => current.start - Months::new(12),
        };
        Period {
            start,
            end: current.start,
        }
    }

    fn advance(self, start: NaiveDate) -> NaiveDate {
        match self {
            Range::Day => start + Days::new(1),
            Range::Week => start + Days::new(7),
            Range::Month => start + Months::new(1),
            Range::Year => start + Months::new(12),
        }
    }
}

pub fn month_start(d: NaiveDate) -> NaiveDate {
    d.with_day(1).expect("day 1 always exists")
}

fn midnight(d: NaiveDate) -> NaiveDateTime {
    d.and_hms_opt(0, 0, 0).expect("midnight exists")
}

/// Epoch millis of a family-local wall time.
pub fn local_to_ms(local: NaiveDateTime, tz_offset_minutes: i32) -> i64 {
    local.and_utc().timestamp_millis() - tz_offset_minutes as i64 * 60_000
}

/// Family-local calendar date of an instant.
pub fn local_date(ms: i64, tz_offset_minutes: i32) -> NaiveDate {
    DateTime::from_timestamp_millis(ms + tz_offset_minutes as i64 * 60_000)
        .expect("timestamp in range")
        .date_naive()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn local_conversion_round_trips_across_midnight() {
        let local = NaiveDate::from_ymd_opt(2026, 9, 29)
            .unwrap()
            .and_hms_opt(2, 0, 0)
            .unwrap();
        let ms = local_to_ms(local, 330);
        assert_eq!(local_date(ms, 330), local.date());
        assert_eq!(
            local_date(ms, 0),
            NaiveDate::from_ymd_opt(2026, 9, 28).unwrap()
        );
    }
}
