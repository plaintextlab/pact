//! Recurrence arithmetic. Mirrored in android/.../core/Recur.kt.

use chrono::{Datelike, Duration, NaiveDate};

use crate::model::{Freq, Recurrence};

pub fn weekday(d: NaiveDate) -> u32 {
    d.weekday().number_from_monday()
}

pub fn is_workday(d: NaiveDate) -> bool {
    weekday(d) <= 5
}

pub fn days_in_month(y: i32, m: u32) -> u32 {
    let (ny, nm) = if m == 12 { (y + 1, 1) } else { (y, m + 1) };
    NaiveDate::from_ymd_opt(ny, nm, 1).unwrap().pred_opt().unwrap().day()
}

/// Builds a date, clamping the day to the month's last day.
pub fn ymd_clamped(y: i32, m: u32, d: u32) -> NaiveDate {
    NaiveDate::from_ymd_opt(y, m, d.clamp(1, days_in_month(y, m))).unwrap()
}

pub fn add_months(d: NaiveDate, months: i64, day: u32) -> NaiveDate {
    let total = d.year() as i64 * 12 + d.month0() as i64 + months;
    ymd_clamped(total.div_euclid(12) as i32, total.rem_euclid(12) as u32 + 1, day)
}

pub fn add_business_days(d: NaiveDate, n: i64) -> NaiveDate {
    let mut d = d;
    let mut left = n;
    while left > 0 {
        d += Duration::days(1);
        if is_workday(d) {
            left -= 1;
        }
    }
    d
}

/// First occurrence on or after `d`.
pub fn first_on_or_after(r: &Recurrence, d: NaiveDate) -> NaiveDate {
    match r.freq {
        Freq::Daily if r.workdays => {
            let mut x = d;
            while !is_workday(x) {
                x += Duration::days(1);
            }
            x
        }
        Freq::Weekly if !r.weekdays.is_empty() => {
            let mut x = d;
            while !r.weekdays.contains(&weekday(x)) {
                x += Duration::days(1);
            }
            x
        }
        Freq::Monthly => match r.month_day {
            Some(md) => {
                let c = ymd_clamped(d.year(), d.month(), md);
                if c >= d { c } else { add_months(d, 1, md) }
            }
            None => d,
        },
        Freq::Yearly => match (r.month, r.month_day) {
            (Some(m), Some(md)) => {
                let c = ymd_clamped(d.year(), m, md);
                if c >= d { c } else { ymd_clamped(d.year() + 1, m, md) }
            }
            _ => d,
        },
        _ => d,
    }
}

/// Next occurrence strictly after `d`.
pub fn next_after(r: &Recurrence, d: NaiveDate) -> NaiveDate {
    let n = r.interval.max(1) as i64;
    match r.freq {
        Freq::Daily if r.workdays => add_business_days(d, n),
        Freq::Daily => d + Duration::days(n),
        Freq::Weekly if !r.weekdays.is_empty() => {
            let mut x = d + Duration::days(1);
            while !r.weekdays.contains(&weekday(x)) {
                x += Duration::days(1);
            }
            // Wrapped into a new week: skip the weeks in between for "every 2 weeks on mon".
            if weekday(x) <= weekday(d) && n > 1 {
                x += Duration::weeks(n - 1);
            }
            x
        }
        Freq::Weekly => d + Duration::weeks(n),
        Freq::Monthly => add_months(d, n, r.month_day.unwrap_or(d.day())),
        Freq::Yearly => {
            let m = r.month.unwrap_or(d.month());
            ymd_clamped(d.year() + n as i32, m, r.month_day.unwrap_or(d.day()))
        }
    }
}

/// Next due date after completing a task due on `due`: never earlier than `today`.
pub fn advance(r: &Recurrence, due: NaiveDate, today: NaiveDate) -> NaiveDate {
    let mut x = next_after(r, due);
    while x < today {
        x = next_after(r, x);
    }
    x
}

const DAY_NAMES: [&str; 7] = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const MONTH_NAMES: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

pub fn ordinal(n: u32) -> String {
    let suffix = match (n % 10, n % 100) {
        (_, 11..=13) => "th",
        (1, _) => "st",
        (2, _) => "nd",
        (3, _) => "rd",
        _ => "th",
    };
    format!("{n}{suffix}")
}

/// Human-readable description, e.g. "every 2 weeks on Mon, Wed".
pub fn describe(r: &Recurrence) -> String {
    let n = r.interval.max(1);
    let unit = match r.freq {
        Freq::Daily => "day",
        Freq::Weekly => "week",
        Freq::Monthly => "month",
        Freq::Yearly => "year",
    };
    if r.freq == Freq::Daily && r.workdays {
        return "every workday".into();
    }
    if r.freq == Freq::Weekly && r.weekdays == [6, 7] && n == 1 {
        return "every weekend".into();
    }
    let mut s = match n {
        1 => format!("every {unit}"),
        2 => format!("every other {unit}"),
        _ => format!("every {n} {unit}s"),
    };
    match r.freq {
        Freq::Weekly if !r.weekdays.is_empty() => {
            let days: Vec<&str> = r.weekdays.iter().filter_map(|d| DAY_NAMES.get(*d as usize - 1).copied()).collect();
            s = if n == 1 { format!("every {}", days.join(", ")) } else { format!("{s} on {}", days.join(", ")) };
        }
        Freq::Monthly => {
            if let Some(md) = r.month_day {
                s = if md >= 31 { format!("{s} on the last day") } else { format!("{s} on the {}", ordinal(md)) };
            }
        }
        Freq::Yearly => {
            if let (Some(m), Some(md)) = (r.month, r.month_day) {
                s = format!("{s} on {} {md}", MONTH_NAMES[(m as usize - 1).min(11)]);
            }
        }
        _ => {}
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(s: &str) -> NaiveDate {
        NaiveDate::parse_from_str(s, "%Y-%m-%d").unwrap()
    }

    #[test]
    fn monthly_clamps_to_month_end() {
        let r = Recurrence { freq: Freq::Monthly, month_day: Some(31), ..Default::default() };
        assert_eq!(next_after(&r, d("2026-01-31")), d("2026-02-28"));
        assert_eq!(next_after(&r, d("2026-02-28")), d("2026-03-31"));
    }

    #[test]
    fn weekly_days_with_interval() {
        let r = Recurrence { freq: Freq::Weekly, interval: 2, weekdays: vec![1, 3], ..Default::default() };
        assert_eq!(next_after(&r, d("2026-10-19")), d("2026-10-21")); // Mon -> Wed same week
        assert_eq!(next_after(&r, d("2026-10-21")), d("2026-11-02")); // Wed -> Mon two weeks on
    }

    #[test]
    fn advance_skips_past_occurrences() {
        let r = Recurrence::default();
        assert_eq!(advance(&r, d("2026-10-01"), d("2026-10-16")), d("2026-10-16"));
    }

    #[test]
    fn workdays_skip_weekend() {
        let r = Recurrence { workdays: true, ..Default::default() };
        assert_eq!(next_after(&r, d("2026-10-16")), d("2026-10-19"));
        assert_eq!(describe(&r), "every workday");
    }
}
