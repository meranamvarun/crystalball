//! Integer money helpers. Amounts are paise (`i64`); ratios are integer percent or basis points.

/// `a / b` rounded half away from zero. `b` must be positive.
pub fn round_div(a: i64, b: i64) -> i64 {
    assert!(b > 0, "round_div divisor must be positive");
    let (a, b) = (a as i128, b as i128);
    let q = if a >= 0 {
        (a + b / 2) / b
    } else {
        -((-a + b / 2) / b)
    };
    q as i64
}

/// Integer percent of `part` in `whole`; 0 when `whole` is 0.
pub fn percent(part: i64, whole: i64) -> i64 {
    if whole <= 0 {
        0
    } else {
        round_div_wide(part as i128 * 100, whole as i128)
    }
}

/// Change from `prev` to `cur` in basis points; `None` when there is no baseline.
pub fn change_bps(cur: i64, prev: i64) -> Option<i64> {
    (prev > 0).then(|| round_div_wide((cur as i128 - prev as i128) * 10_000, prev as i128))
}

pub(crate) fn round_div_wide(a: i128, b: i128) -> i64 {
    let q = if a >= 0 {
        (a + b / 2) / b
    } else {
        -((-a + b / 2) / b)
    };
    q as i64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rounds_half_away_from_zero() {
        assert_eq!(round_div(5, 2), 3);
        assert_eq!(round_div(-5, 2), -3);
        assert_eq!(round_div(4, 3), 1);
        assert_eq!(round_div(-4, 3), -1);
        assert_eq!(round_div(0, 7), 0);
    }

    #[test]
    fn percent_handles_zero_whole() {
        assert_eq!(percent(10, 0), 0);
        assert_eq!(percent(1, 3), 33);
        assert_eq!(percent(2, 3), 67);
    }

    #[test]
    fn change_bps_is_none_without_baseline() {
        assert_eq!(change_bps(100, 0), None);
        assert_eq!(change_bps(150, 100), Some(5_000));
        assert_eq!(change_bps(50, 100), Some(-5_000));
    }

    #[test]
    fn no_overflow_on_large_amounts() {
        // ₹10 crore in paise times 10_000 overflows i64 without widening.
        assert_eq!(
            change_bps(2_000_000_000_000_000, 1_000_000_000_000_000),
            Some(10_000)
        );
    }
}
