//! Calendar events. Mirrored in android/.../core/Event.kt.

use chrono::{Duration, NaiveDate, NaiveDateTime, NaiveTime};
use serde::{Deserialize, Serialize};

use crate::model::{Recurrence, DATE_FMT, TIME_FMT};
use crate::{now_millis, recur};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Event {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub notes: String,
    #[serde(default)]
    pub location: String,
    /// The group doubles as the event's calendar (name and color).
    #[serde(default)]
    pub group_id: Option<String>,
    pub start_date: String,
    /// None = all-day event.
    #[serde(default)]
    pub start_time: Option<String>,
    /// Inclusive; equals `start_date` for single-day events.
    pub end_date: String,
    #[serde(default)]
    pub end_time: Option<String>,
    #[serde(default)]
    pub recurrence: Option<Recurrence>,
    /// Start dates of cancelled occurrences of a recurring event.
    #[serde(default)]
    pub exdates: Vec<String>,
    /// Minutes before start.
    #[serde(default)]
    pub reminders: Vec<i64>,
    /// Ring an alarm instead of a plain notification.
    #[serde(default)]
    pub urgent: bool,
    /// Overrides the group color: theme key or `#RRGGBB`.
    #[serde(default)]
    pub color: Option<String>,
    #[serde(default)]
    pub url: String,
    /// iCalendar UID, kept so re-importing a file updates instead of duplicating.
    #[serde(default)]
    pub uid: Option<String>,
    #[serde(default)]
    pub created: i64,
    #[serde(default)]
    pub updated: i64,
    #[serde(default)]
    pub deleted: bool,
}

/// One concrete occurrence of an event.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Occurrence {
    pub start: NaiveDateTime,
    pub end: NaiveDateTime,
    pub all_day: bool,
}

impl Occurrence {
    pub fn date(&self) -> NaiveDate {
        self.start.date()
    }

    /// Whether any part of the occurrence falls on `d`.
    pub fn covers(&self, d: NaiveDate) -> bool {
        self.start.date() <= d && self.last_day() >= d
    }

    /// Last calendar day the occurrence touches (an event ending exactly at midnight ends the day before).
    pub fn last_day(&self) -> NaiveDate {
        if !self.all_day && self.end.time() == NaiveTime::MIN && self.end.date() > self.start.date() {
            self.end.date() - Duration::days(1)
        } else {
            self.end.date()
        }
    }
}

fn parse_date(s: &str) -> Option<NaiveDate> {
    NaiveDate::parse_from_str(s, DATE_FMT).ok()
}

fn parse_time(s: Option<&str>) -> Option<NaiveTime> {
    s.and_then(|t| NaiveTime::parse_from_str(t, TIME_FMT).ok())
}

impl Event {
    /// A one-hour event, or an all-day one when `time` is None.
    pub fn new(title: impl Into<String>, date: NaiveDate, time: Option<NaiveTime>) -> Self {
        let now = now_millis();
        let mut e = Self {
            id: uuid::Uuid::new_v4().to_string(),
            title: title.into(),
            notes: String::new(),
            location: String::new(),
            group_id: None,
            start_date: String::new(),
            start_time: None,
            end_date: String::new(),
            end_time: None,
            recurrence: None,
            exdates: vec![],
            reminders: vec![],
            urgent: false,
            color: None,
            url: String::new(),
            uid: None,
            created: now,
            updated: now,
            deleted: false,
        };
        match time {
            Some(t) => {
                let s = date.and_time(t);
                e.set_span(s, s + Duration::hours(1), false);
            }
            None => e.set_span(date.and_time(NaiveTime::MIN), date.and_time(NaiveTime::MIN), true),
        }
        e
    }

    pub fn all_day(&self) -> bool {
        self.start_time.is_none()
    }

    pub fn start(&self) -> NaiveDateTime {
        let d = parse_date(&self.start_date).unwrap_or_default();
        d.and_time(parse_time(self.start_time.as_deref()).unwrap_or(NaiveTime::MIN))
    }

    pub fn end(&self) -> NaiveDateTime {
        let d = parse_date(&self.end_date).unwrap_or_else(|| self.start().date());
        match parse_time(self.end_time.as_deref()) {
            Some(t) if !self.all_day() => d.and_time(t),
            _ => d.and_hms_opt(23, 59, 59).unwrap(),
        }
    }

    /// Sets start and end; all-day spans keep only their dates.
    pub fn set_span(&mut self, start: NaiveDateTime, end: NaiveDateTime, all_day: bool) {
        let end = if end < start { start } else { end };
        self.start_date = start.date().format(DATE_FMT).to_string();
        self.end_date = end.date().format(DATE_FMT).to_string();
        if all_day {
            self.start_time = None;
            self.end_time = None;
        } else {
            self.start_time = Some(start.time().format(TIME_FMT).to_string());
            self.end_time = Some(end.time().format(TIME_FMT).to_string());
        }
    }

    fn occurrence_at(&self, date: NaiveDate) -> Occurrence {
        let first = self.start();
        let start = date.and_time(first.time());
        Occurrence { start, end: start + (self.end() - first), all_day: self.all_day() }
    }

    /// Occurrences that touch any day in `from..=to`.
    pub fn occurrences(&self, from: NaiveDate, to: NaiveDate) -> Vec<Occurrence> {
        let first = self.start();
        let Some(r) = &self.recurrence else {
            let o = self.occurrence_at(first.date());
            return if o.date() <= to && o.last_day() >= from { vec![o] } else { vec![] };
        };
        let until = r.until_date();
        let span_days = (self.end().date() - first.date()).num_days().max(0);
        let mut out = vec![];
        let mut x = first.date();
        let mut n = 0u32;
        for _ in 0..50_000 {
            if x > to || until.is_some_and(|u| x > u) || r.count.is_some_and(|c| n >= c) {
                break;
            }
            n += 1;
            if x + Duration::days(span_days) >= from && !self.exdates.iter().any(|d| parse_date(d) == Some(x)) {
                out.push(self.occurrence_at(x));
            }
            x = recur::next_after(r, x);
        }
        out
    }

    /// First occurrence starting at or after `t`.
    pub fn next_occurrence(&self, t: NaiveDateTime) -> Option<Occurrence> {
        self.occurrences(t.date(), t.date() + Duration::days(400)).into_iter().find(|o| o.start >= t)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Freq;

    fn d(s: &str) -> NaiveDate {
        parse_date(s).unwrap()
    }

    #[test]
    fn single_and_multi_day() {
        let mut e = Event::new("trip", d("2026-10-20"), None);
        e.end_date = "2026-10-22".into();
        let o = e.occurrences(d("2026-10-21"), d("2026-10-21"));
        assert_eq!(o.len(), 1);
        assert!(o[0].covers(d("2026-10-22")));
        assert!(e.occurrences(d("2026-10-23"), d("2026-10-30")).is_empty());
    }

    #[test]
    fn recurring_with_count_until_and_exdates() {
        let mut e = Event::new("standup", d("2026-10-19"), NaiveTime::from_hms_opt(9, 30, 0));
        e.recurrence = Some(Recurrence { freq: Freq::Weekly, weekdays: vec![1, 3, 5], count: Some(5), ..Default::default() });
        e.exdates = vec!["2026-10-21".into()];
        let dates: Vec<String> = e.occurrences(d("2026-10-01"), d("2026-12-31")).iter().map(|o| o.date().to_string()).collect();
        assert_eq!(dates, ["2026-10-19", "2026-10-23", "2026-10-26", "2026-10-28"]);

        e.recurrence = Some(Recurrence { freq: Freq::Daily, until: Some("2026-10-21".into()), ..Default::default() });
        e.exdates.clear();
        assert_eq!(e.occurrences(d("2026-10-01"), d("2026-12-31")).len(), 3);
    }

    #[test]
    fn overnight_event_and_next_occurrence() {
        let mut e = Event::new("late show", d("2026-10-16"), NaiveTime::from_hms_opt(23, 0, 0));
        e.set_span(d("2026-10-16").and_hms_opt(23, 0, 0).unwrap(), d("2026-10-17").and_hms_opt(1, 0, 0).unwrap(), false);
        assert_eq!(e.occurrences(d("2026-10-17"), d("2026-10-17")).len(), 1);
        e.recurrence = Some(Recurrence { freq: Freq::Weekly, ..Default::default() });
        let next = e.next_occurrence(d("2026-10-17").and_hms_opt(0, 0, 0).unwrap()).unwrap();
        assert_eq!(next.date(), d("2026-10-23"));
    }
}
