//! iCalendar (RFC 5545) import/export for events. Covers what calendar apps
//! commonly exchange: VEVENT with DTSTART/DTEND/DURATION, SUMMARY, DESCRIPTION,
//! LOCATION, URL, CATEGORIES, RRULE (FREQ/INTERVAL/BYDAY/BYMONTHDAY/BYMONTH/
//! UNTIL/COUNT), EXDATE and VALARM triggers. Times with TZID are read as local
//! wall-clock time; UTC (`Z`) times are converted to local time.
//! Mirrored in android/.../core/Ics.kt.

use chrono::{DateTime, Duration, Local, NaiveDate, NaiveDateTime, NaiveTime, TimeZone, Utc};

use crate::event::Event;
use crate::model::{Freq, Group, Recurrence, DATE_FMT};

const DAYS: [&str; 7] = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"];

fn escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace(';', "\\;").replace(',', "\\,").replace('\n', "\\n")
}

fn unescape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut it = s.chars();
    while let Some(c) = it.next() {
        if c == '\\' {
            match it.next() {
                Some('n') | Some('N') => out.push('\n'),
                Some(o) => out.push(o),
                None => {}
            }
        } else {
            out.push(c);
        }
    }
    out
}

/// Folds a content line to 75 octets as RFC 5545 requires.
fn fold(line: &str, out: &mut String) {
    let mut width = 0;
    for c in line.chars() {
        let w = c.len_utf8();
        if width + w > 75 {
            out.push_str("\r\n ");
            width = 1;
        }
        out.push(c);
        width += w;
    }
    out.push_str("\r\n");
}

fn rrule(r: &Recurrence) -> String {
    let mut parts = vec![];
    if r.freq == Freq::Daily && r.workdays {
        parts.push("FREQ=WEEKLY".to_string());
        parts.push("BYDAY=MO,TU,WE,TH,FR".to_string());
    } else {
        let f = match r.freq {
            Freq::Daily => "DAILY",
            Freq::Weekly => "WEEKLY",
            Freq::Monthly => "MONTHLY",
            Freq::Yearly => "YEARLY",
        };
        parts.push(format!("FREQ={f}"));
        if !r.weekdays.is_empty() {
            let days: Vec<&str> = r.weekdays.iter().filter_map(|d| DAYS.get(*d as usize - 1).copied()).collect();
            parts.push(format!("BYDAY={}", days.join(",")));
        }
        if let Some(md) = r.month_day {
            // 31 means "last day of the month".
            parts.push(format!("BYMONTHDAY={}", if md >= 31 { -1 } else { md as i32 }));
        }
        if let Some(m) = r.month {
            parts.push(format!("BYMONTH={m}"));
        }
    }
    if r.interval > 1 {
        parts.push(format!("INTERVAL={}", r.interval));
    }
    if let Some(u) = r.until_date() {
        parts.push(format!("UNTIL={}", u.format("%Y%m%d")));
    }
    if let Some(c) = r.count {
        parts.push(format!("COUNT={c}"));
    }
    parts.join(";")
}

/// Serializes events as an iCalendar file.
pub fn export(events: &[&Event], groups: &[&Group]) -> String {
    let mut out = String::new();
    let stamp = Utc::now().format("%Y%m%dT%H%M%SZ").to_string();
    for l in ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Pact//Pact Calendar//EN", "CALSCALE:GREGORIAN"] {
        fold(l, &mut out);
    }
    for e in events.iter().filter(|e| !e.deleted) {
        fold("BEGIN:VEVENT", &mut out);
        fold(&format!("UID:{}", e.uid.clone().unwrap_or_else(|| format!("{}@pact", e.id))), &mut out);
        fold(&format!("DTSTAMP:{stamp}"), &mut out);
        let (start, end) = (e.start(), e.end());
        if e.all_day() {
            fold(&format!("DTSTART;VALUE=DATE:{}", start.format("%Y%m%d")), &mut out);
            fold(&format!("DTEND;VALUE=DATE:{}", (end.date() + Duration::days(1)).format("%Y%m%d")), &mut out);
        } else {
            fold(&format!("DTSTART:{}", start.format("%Y%m%dT%H%M%S")), &mut out);
            fold(&format!("DTEND:{}", end.format("%Y%m%dT%H%M%S")), &mut out);
        }
        fold(&format!("SUMMARY:{}", escape(&e.title)), &mut out);
        if !e.notes.is_empty() {
            fold(&format!("DESCRIPTION:{}", escape(&e.notes)), &mut out);
        }
        if !e.location.is_empty() {
            fold(&format!("LOCATION:{}", escape(&e.location)), &mut out);
        }
        if !e.url.is_empty() {
            fold(&format!("URL:{}", e.url), &mut out);
        }
        if let Some(g) = e.group_id.as_deref().and_then(|id| groups.iter().find(|g| g.id == id)) {
            fold(&format!("CATEGORIES:{}", escape(&g.name)), &mut out);
        }
        if let Some(r) = &e.recurrence {
            fold(&format!("RRULE:{}", rrule(r)), &mut out);
        }
        for x in &e.exdates {
            if let Ok(d) = NaiveDate::parse_from_str(x, DATE_FMT) {
                let line = if e.all_day() {
                    format!("EXDATE;VALUE=DATE:{}", d.format("%Y%m%d"))
                } else {
                    format!("EXDATE:{}", d.and_time(start.time()).format("%Y%m%dT%H%M%S"))
                };
                fold(&line, &mut out);
            }
        }
        for m in &e.reminders {
            for l in ["BEGIN:VALARM".to_string(), "ACTION:DISPLAY".into(), format!("DESCRIPTION:{}", escape(&e.title)), format!("TRIGGER:-PT{m}M"), "END:VALARM".into()] {
                fold(&l, &mut out);
            }
        }
        fold("END:VEVENT", &mut out);
    }
    fold("END:VCALENDAR", &mut out);
    out
}

/// A parsed content line: name, parameters, value.
struct Prop {
    name: String,
    params: Vec<(String, String)>,
    value: String,
}

impl Prop {
    fn param(&self, k: &str) -> Option<&str> {
        self.params.iter().find(|(n, _)| n.eq_ignore_ascii_case(k)).map(|(_, v)| v.as_str())
    }
}

fn parse_line(line: &str) -> Option<Prop> {
    // The value starts at the first ':' outside a quoted parameter value.
    let mut in_quotes = false;
    let split = line.char_indices().find(|(_, c)| {
        if *c == '"' {
            in_quotes = !in_quotes;
        }
        *c == ':' && !in_quotes
    })?;
    let (head, value) = (&line[..split.0], &line[split.0 + 1..]);
    let mut parts = head.split(';');
    let name = parts.next()?.trim().to_uppercase();
    let params = parts.filter_map(|p| p.split_once('=')).map(|(k, v)| (k.to_uppercase(), v.trim_matches('"').to_string())).collect();
    Some(Prop { name, params, value: value.to_string() })
}

/// A DTSTART/DTEND/EXDATE value: (local date-time, is date-only).
fn parse_when(value: &str, p: &Prop) -> Option<(NaiveDateTime, bool)> {
    let v = value.trim();
    if p.param("VALUE").is_some_and(|x| x.eq_ignore_ascii_case("DATE")) || v.len() == 8 {
        let d = NaiveDate::parse_from_str(&v[..8.min(v.len())], "%Y%m%d").ok()?;
        return Some((d.and_time(NaiveTime::MIN), true));
    }
    let (body, utc) = match v.strip_suffix('Z') {
        Some(b) => (b, true),
        None => (v, false),
    };
    let dt = NaiveDateTime::parse_from_str(body, "%Y%m%dT%H%M%S").ok()?;
    if utc {
        let local: DateTime<Local> = Local.from_utc_datetime(&dt);
        return Some((local.naive_local(), false));
    }
    Some((dt, false))
}

/// ISO 8601 duration like `PT1H30M`, `P1D`, `-PT15M` → minutes.
fn parse_duration(s: &str) -> Option<i64> {
    let s = s.trim();
    let (neg, s) = match s.strip_prefix('-') {
        Some(r) => (true, r),
        None => (false, s.strip_prefix('+').unwrap_or(s)),
    };
    let s = s.strip_prefix('P')?;
    let mut total = 0i64;
    let mut num = String::new();
    for c in s.chars() {
        match c {
            '0'..='9' => num.push(c),
            'T' => {}
            'W' | 'D' | 'H' | 'M' | 'S' => {
                let n: i64 = num.parse().ok()?;
                num.clear();
                total += match c {
                    'W' => n * 7 * 1440,
                    'D' => n * 1440,
                    'H' => n * 60,
                    'M' => n,
                    _ => n / 60,
                };
            }
            _ => return None,
        }
    }
    Some(if neg { -total } else { total })
}

fn parse_rrule(v: &str, start: NaiveDate) -> Option<Recurrence> {
    let mut r = Recurrence::default();
    let mut freq = None;
    for part in v.split(';') {
        let (k, val) = part.split_once('=')?;
        match k.to_uppercase().as_str() {
            "FREQ" => {
                freq = Some(match val.to_uppercase().as_str() {
                    "DAILY" => Freq::Daily,
                    "WEEKLY" => Freq::Weekly,
                    "MONTHLY" => Freq::Monthly,
                    "YEARLY" => Freq::Yearly,
                    _ => return None,
                })
            }
            "INTERVAL" => r.interval = val.parse().unwrap_or(1),
            "COUNT" => r.count = val.parse().ok(),
            "UNTIL" => {
                let d = NaiveDate::parse_from_str(&val[..8.min(val.len())], "%Y%m%d").ok()?;
                r.until = Some(d.format(DATE_FMT).to_string());
            }
            "BYDAY" => {
                // Plain weekdays only; ordinals like "2MO" (second Monday) aren't supported.
                r.weekdays = val.split(',').filter_map(|d| DAYS.iter().position(|x| d.eq_ignore_ascii_case(x)).map(|i| i as u32 + 1)).collect();
                r.weekdays.sort();
            }
            "BYMONTHDAY" => {
                let md: i32 = val.split(',').next()?.parse().ok()?;
                r.month_day = Some(if md < 0 { 31 } else { md as u32 });
            }
            "BYMONTH" => r.month = val.split(',').next()?.parse().ok(),
            _ => {}
        }
    }
    r.freq = freq?;
    match r.freq {
        Freq::Weekly if r.weekdays == [1, 2, 3, 4, 5] && r.interval == 1 => {
            r = Recurrence { freq: Freq::Daily, workdays: true, weekdays: vec![], ..r };
        }
        Freq::Weekly => {}
        _ => r.weekdays.clear(),
    }
    if r.freq == Freq::Yearly && r.month.is_some() && r.month_day.is_none() {
        r.month_day = Some(chrono::Datelike::day(&start));
    }
    Some(r)
}

/// Parses VEVENTs from an iCalendar file. `group_for` maps a CATEGORIES name to a group id.
pub fn import(text: &str, mut group_for: impl FnMut(&str) -> Option<String>) -> Vec<Event> {
    // Unfold continuation lines (CRLF or LF followed by a space/tab).
    let mut lines: Vec<String> = vec![];
    for raw in text.split('\n') {
        let raw = raw.strip_suffix('\r').unwrap_or(raw);
        match raw.strip_prefix(' ').or_else(|| raw.strip_prefix('\t')) {
            Some(cont) if !lines.is_empty() => lines.last_mut().unwrap().push_str(cont),
            _ => lines.push(raw.to_string()),
        }
    }

    let mut out = vec![];
    let mut cur: Option<Vec<Prop>> = None;
    let mut alarms: Vec<i64> = vec![];
    let mut in_alarm = false;
    for line in lines {
        let Some(p) = parse_line(&line) else { continue };
        match (p.name.as_str(), p.value.to_uppercase().as_str()) {
            ("BEGIN", "VEVENT") => {
                cur = Some(vec![]);
                alarms.clear();
            }
            ("BEGIN", "VALARM") => in_alarm = true,
            ("END", "VALARM") => in_alarm = false,
            ("END", "VEVENT") => {
                if let Some(props) = cur.take() {
                    if let Some(e) = build_event(&props, &alarms, &mut group_for) {
                        out.push(e);
                    }
                }
            }
            ("TRIGGER", _) if in_alarm => {
                if let Some(m) = parse_duration(&p.value) {
                    if m <= 0 && !alarms.contains(&-m) {
                        alarms.push(-m);
                    }
                }
            }
            _ => {
                if let (Some(props), false) = (cur.as_mut(), in_alarm) {
                    props.push(p);
                }
            }
        }
    }
    out
}

fn build_event(props: &[Prop], alarms: &[i64], group_for: &mut impl FnMut(&str) -> Option<String>) -> Option<Event> {
    let get = |n: &str| props.iter().find(|p| p.name == n);
    let start_p = get("DTSTART")?;
    let (start, all_day) = parse_when(&start_p.value, start_p)?;
    let title = get("SUMMARY").map(|p| unescape(&p.value)).filter(|s| !s.trim().is_empty()).unwrap_or_else(|| "(no title)".into());
    let mut e = Event::new(title, start.date(), (!all_day).then_some(start.time()));
    let end = match get("DTEND").and_then(|p| parse_when(&p.value, p)) {
        // All-day DTEND is exclusive.
        Some((end, true)) => end - Duration::days(1),
        Some((end, false)) => end,
        None => match get("DURATION").and_then(|p| parse_duration(&p.value)) {
            Some(m) if all_day => start + Duration::minutes(m) - Duration::days(1),
            Some(m) => start + Duration::minutes(m),
            None if all_day => start,
            None => start + Duration::hours(1),
        },
    };
    e.set_span(start, end.max(start), all_day);
    e.notes = get("DESCRIPTION").map(|p| unescape(&p.value)).unwrap_or_default();
    e.location = get("LOCATION").map(|p| unescape(&p.value)).unwrap_or_default();
    e.url = get("URL").map(|p| p.value.clone()).unwrap_or_default();
    e.uid = get("UID").map(|p| p.value.clone());
    e.recurrence = get("RRULE").and_then(|p| parse_rrule(&p.value, start.date()));
    e.exdates = props
        .iter()
        .filter(|p| p.name == "EXDATE")
        .flat_map(|p| p.value.split(',').filter_map(|v| parse_when(v, p)).map(|(d, _)| d.date().format(DATE_FMT).to_string()).collect::<Vec<_>>())
        .collect();
    e.reminders = alarms.to_vec();
    if let Some(cat) = get("CATEGORIES").and_then(|p| p.value.split(',').next().map(unescape)) {
        e.group_id = group_for(cat.trim());
    }
    Some(e)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn imports_sample_and_round_trips() {
        let events = import(include_str!("../../spec/ics_sample.ics"), |c| (c == "Personal").then(|| "personal".to_string()));
        assert_eq!(events.len(), 3);

        let s = &events[0];
        assert_eq!(s.title, "Team standup");
        assert_eq!(s.notes, "Daily sync, keep it short\nAgenda in the doc");
        assert_eq!(s.location, "Room 4; floor 2");
        assert_eq!((s.start_date.as_str(), s.start_time.as_deref(), s.end_time.as_deref()), ("2026-10-19", Some("09:30"), Some("09:45")));
        let r = s.recurrence.as_ref().unwrap();
        assert_eq!((r.freq, r.weekdays.clone(), r.until.as_deref()), (Freq::Weekly, vec![1, 3, 5], Some("2026-12-31")));
        assert_eq!(s.exdates, ["2026-10-21"]);
        assert_eq!(s.reminders, [15]);
        assert_eq!(s.uid.as_deref(), Some("standup-123@example.com"));

        let h = &events[1];
        assert!(h.all_day());
        assert_eq!((h.start_date.as_str(), h.end_date.as_str()), ("2026-12-24", "2026-12-26"));
        assert_eq!(h.group_id.as_deref(), Some("personal"));

        let d = &events[2];
        assert!(d.title.ends_with("folded by the exporter"));
        assert_eq!(d.end_time.as_deref(), Some("15:45"));
        let r = d.recurrence.as_ref().unwrap();
        assert_eq!((r.freq, r.interval, r.count), (Freq::Monthly, 6, Some(4)));

        let groups = vec![Group { id: "personal".into(), name: "Personal".into(), color: "green".into(), order: 0, updated: 0, deleted: false }];
        let text = export(&events.iter().collect::<Vec<_>>(), &groups.iter().collect::<Vec<_>>());
        assert!(text.lines().all(|l| l.len() <= 76), "lines are folded");
        let again = import(&text, |c| (c == "Personal").then(|| "personal".to_string()));
        assert_eq!(again.len(), 3);
        for (a, b) in events.iter().zip(&again) {
            assert_eq!((&a.title, &a.notes, &a.location, &a.start_date, &a.start_time, &a.end_date, &a.end_time), (&b.title, &b.notes, &b.location, &b.start_date, &b.start_time, &b.end_date, &b.end_time));
            assert_eq!((&a.recurrence, &a.exdates, &a.reminders, &a.uid, &a.group_id), (&b.recurrence, &b.exdates, &b.reminders, &b.uid, &b.group_id));
        }
    }

    #[test]
    fn workdays_round_trip() {
        let mut e = Event::new("x", NaiveDate::from_ymd_opt(2026, 10, 19).unwrap(), None);
        e.recurrence = Some(Recurrence { workdays: true, ..Default::default() });
        let text = export(&[&e], &[]);
        assert!(text.contains("RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"));
        assert_eq!(import(&text, |_| None)[0].recurrence, e.recurrence);
    }
}
