//! Escalating deadline reminders: the closer a deadline gets, the more often it nags.
//!
//! * 4, 3 and 2 weeks before: weekly, at the start of the day
//! * the final week: daily, at the start of the day
//! * the last 24 hours: every `final_hours` hours, but only within waking hours
//! * at the deadline itself
//! * once a day for 3 days after, while the task is still open
//!
//! Waking hours are `day_start..=day_end` (the "morning" and "night" hours of the
//! quick-add settings). A deadline without a time counts as due at `day_end`.
//! Mirrored in android/.../core/Deadline.kt; both are tested against spec/deadline_cases.json.

use chrono::{Duration, NaiveDate, NaiveDateTime, NaiveTime, Timelike};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct DeadlineConfig {
    pub day_start: u32,
    pub day_end: u32,
    /// Hours between reminders during the last day.
    pub final_hours: u32,
}

impl Default for DeadlineConfig {
    fn default() -> Self {
        Self { day_start: 9, day_end: 21, final_hours: 1 }
    }
}

const OVERDUE_DAYS: i64 = 3;

/// The moment a deadline falls due.
pub fn moment(date: NaiveDate, time: Option<NaiveTime>, cfg: &DeadlineConfig) -> NaiveDateTime {
    date.and_time(time.unwrap_or_else(|| NaiveTime::from_hms_opt(cfg.day_end.min(23), 0, 0).unwrap()))
}

/// Every reminder time for a deadline, sorted, without duplicates.
pub fn alerts(deadline: NaiveDateTime, cfg: &DeadlineConfig) -> Vec<NaiveDateTime> {
    let day = deadline.date();
    let at_start = |d: NaiveDate| d.and_hms_opt(cfg.day_start.min(23), 0, 0).unwrap();
    let mut out = vec![];
    for weeks in [4, 3, 2] {
        out.push(at_start(day - Duration::weeks(weeks)));
    }
    for days in 1..=7 {
        out.push(at_start(day - Duration::days(days)));
    }
    let step = Duration::hours(cfg.final_hours.clamp(1, 12) as i64);
    let floor = deadline - Duration::hours(24);
    let mut t = deadline - step;
    while t > floor {
        if t.hour() >= cfg.day_start && t.hour() <= cfg.day_end {
            out.push(t);
        }
        t -= step;
    }
    out.push(deadline);
    for days in 1..=OVERDUE_DAYS {
        out.push(at_start(day + Duration::days(days)));
    }
    // A daily alert can't come after the deadline itself (e.g. a 7:00 deadline).
    out.retain(|a| *a <= deadline || a.date() > day);
    out.sort();
    out.dedup();
    out
}

/// "Deadline in 3 weeks", "… tomorrow", "… in 2 hours", "Deadline passed 2 days ago".
pub fn message(deadline: NaiveDateTime, at: NaiveDateTime) -> String {
    let left = deadline - at;
    let days = (deadline.date() - at.date()).num_days();
    if left <= Duration::zero() {
        let ago = (at.date() - deadline.date()).num_days();
        return match ago {
            0 if left > -Duration::minutes(1) => "Deadline now".into(),
            0 => "Deadline passed today".into(),
            1 => "Deadline passed yesterday".into(),
            n => format!("Deadline passed {n} days ago"),
        };
    }
    if days >= 14 {
        format!("Deadline in {} weeks", (days + 3) / 7)
    } else if days >= 2 {
        format!("Deadline in {days} days")
    } else if left >= Duration::hours(24) || (days == 1 && left > Duration::hours(12)) {
        "Deadline tomorrow".into()
    } else if left >= Duration::hours(1) {
        let h = (left.num_minutes() + 30) / 60;
        format!("Deadline in {h} hour{}", if h == 1 { "" } else { "s" })
    } else {
        format!("Deadline in {} min", left.num_minutes().max(1))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    fn dt(s: &str) -> NaiveDateTime {
        NaiveDateTime::parse_from_str(s, "%Y-%m-%dT%H:%M").unwrap()
    }

    #[test]
    fn shared_cases() {
        let spec: Value = serde_json::from_str(include_str!("../../spec/deadline_cases.json")).unwrap();
        for case in spec["schedules"].as_array().unwrap() {
            let cfg: DeadlineConfig = serde_json::from_value(case["config"].clone()).unwrap();
            let date = NaiveDate::parse_from_str(case["date"].as_str().unwrap(), "%Y-%m-%d").unwrap();
            let time = case["time"].as_str().map(|t| NaiveTime::parse_from_str(t, "%H:%M").unwrap());
            let got: Vec<String> = alerts(moment(date, time, &cfg), &cfg).iter().map(|a| a.format("%Y-%m-%dT%H:%M").to_string()).collect();
            let want: Vec<String> = case["alerts"].as_array().unwrap().iter().map(|v| v.as_str().unwrap().to_string()).collect();
            assert_eq!(got, want, "{}", case["name"]);
        }
        for case in spec["messages"].as_array().unwrap() {
            let got = message(dt(case["deadline"].as_str().unwrap()), dt(case["at"].as_str().unwrap()));
            assert_eq!(got, case["text"].as_str().unwrap(), "{case}");
        }
    }
}
