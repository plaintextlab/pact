//! Natural-language quick add: "Meeting tomorrow at 10am #work p1".
//!
//! Each recognizer is a regex; a match is blanked out of the text and what is left
//! becomes the title. The Kotlin port (android/.../core/Nlp.kt) uses the same
//! patterns in the same order; both are checked against spec/nlp_cases.json.

use std::sync::LazyLock;

use chrono::{Datelike, Duration, NaiveDate, NaiveDateTime, NaiveTime, Timelike};
use regex::{Captures, Regex};
use serde::{Deserialize, Serialize};

use crate::model::{Freq, Recurrence};
use crate::recur::{add_business_days, add_months, days_in_month, first_on_or_after, next_after, weekday, ymd_clamped};

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct NlpConfig {
    /// Weekday that "next week" lands on, 1 = Monday … 7 = Sunday.
    pub next_week_day: u32,
    /// "next month"/"next year": false = the 1st, true = same day of month.
    pub next_period_same_day: bool,
    /// Numeric dates are month/day (US) instead of day/month.
    pub month_first: bool,
    /// First day of the week, 1 = Monday or 7 = Sunday.
    pub week_start: u32,
    pub morning_hour: u32,
    pub afternoon_hour: u32,
    pub evening_hour: u32,
    pub night_hour: u32,
    pub end_of_day_hour: u32,
}

impl Default for NlpConfig {
    fn default() -> Self {
        Self {
            next_week_day: 1,
            next_period_same_day: false,
            month_first: false,
            week_start: 1,
            morning_hour: 9,
            afternoon_hour: 14,
            evening_hour: 18,
            night_hour: 21,
            end_of_day_hour: 17,
        }
    }
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Parsed {
    pub title: String,
    pub date: Option<NaiveDate>,
    pub time: Option<NaiveTime>,
    pub recurrence: Option<Recurrence>,
    pub priority: Option<u8>,
    pub labels: Vec<String>,
    pub group: Option<String>,
    pub reminders: Vec<i64>,
    pub urgent: bool,
}

const WD: &str = "monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun";
// Bare weekday words; "sat"/"sun" only count with a prefix ("on sat") to spare "sun lotion".
const WD_BARE: &str = "monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues|tue|wed|thurs|thur|thu|fri";
const MONTH: &str = "january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec";
const NUM: &str = r"\d+|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|(?:a\s+)?couple(?:\s+of)?|(?:a\s+)?few";
const UNIT: &str = "minutes|minute|mins|min|m|hours|hour|hrs|hr|h|days|day|d|weeks|week|wks|wk|w|fortnights|fortnight|months|month|mos|mo|years|year|yrs|yr|y";

fn re(p: &str) -> Regex {
    let p = p.replace("<WD>", WD).replace("<WDB>", WD_BARE).replace("<MONTH>", MONTH).replace("<NUM>", NUM).replace("<UNIT>", UNIT);
    Regex::new(&format!("(?i){p}")).expect("bad nlp regex")
}

macro_rules! rx {
    ($($name:ident = $p:expr;)*) => { $(static $name: LazyLock<Regex> = LazyLock::new(|| re($p));)* };
}

rx! {
    GROUP = r"(?:^|\s)#([\p{L}\p{N}_][\p{L}\p{N}_\-/]*)";
    LABEL = r"(?:^|\s)@([\p{L}_][\p{L}\p{N}_\-]*)";
    PRIORITY = r"(?:^|\s)(?:p|!!)([1-4])\b";
    PRIORITY_WORD = r"\b(?:priority|prio)\s+(high|medium|med|low|none|[1-4])\b";
    REMIND = r"\b(remind(?:\s+me)?|reminder|alert|alarm|notify(?:\s+me)?)\s+(<NUM>|half\s+an?)\s*(minutes|minute|mins|min|m|hours|hour|hrs|hr|h|days|day|d|weeks|week|w)\s+(?:before|early|earlier|prior|ahead)\b";
    REMIND_SHORT = r"(?:^|\s)!(\d+)\s*(m|min|h|d)\b";
    URGENT = r"(?:^|\s)(?:!urgent|!alarm|!!)(?:\s|$)";

    R_OTHER = r"\bevery\s+other\s+(day|week|month|year)\b";
    R_EVERY_N = r"\bevery\s+(\d+)\s+(days?|weeks?|months?|years?)\b";
    R_WORKDAYS = r"\b(?:every\s+(?:week\s?day|work(?:ing)?\s?day|business\s+day)s?|on\s+weekdays|weekdays\s+only)\b";
    R_WEEKEND = r"\bevery\s+weekend\b";
    R_WEEKDAYS = r"\bevery\s+((?:<WD>)\b(?:\s*(?:,|and|&|\+)\s*(?:<WD>)\b)*)";
    R_MONTH_LAST = r"\bevery\s+(?:the\s+)?(?:last\s+day|end)\s+of\s+(?:the\s+)?month\b";
    R_EVERY_UNIT = r"\bevery\s+(day|morning|afternoon|evening|night|week|month|year)\b";
    R_ADVERB = r"\b(daily|weekly|monthly|yearly|annually|nightly|fortnightly|biweekly)\b";
    R_YEARLY_MD = r"\bevery\s+(<MONTH>)\s+(\d{1,2})(?:st|nd|rd|th)?\b";
    R_YEARLY_DM = r"\bevery\s+(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?(<MONTH>)\b";
    R_MONTHDAY = r"\bevery\s+(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)?(?:\s+(?:of\s+)?(?:the\s+|each\s+|every\s+)?month)?\b";

    T_AMPM = r"(?:\bat\s+|@\s*)?\b(\d{1,2})(?:[:.](\d{2}))?\s*(am\b|pm\b|a\.m\.|p\.m\.)";
    T_24 = r"(?:\bat\s+|@\s*)?\b([01]?\d|2[0-3])[:h]([0-5]\d)\b";
    T_AT = r"\bat\s+(\d{1,2})\b";
    T_NOON = r"\b(?:at\s+)?(noon|midday|midnight)\b";
    T_TONIGHT = r"\b(?:tonight|tonite)\b";
    T_THIS_PART = r"\bthis\s+(morning|afternoon|evening)\b";
    T_PART = r"\b(?:in\s+the\s+|at\s+)?(morning|afternoon|evening)\b|\bat\s+(night)\b";
    T_EOD = r"\b(?:by\s+)?(?:eod|end\s+of\s+(?:the\s+)?day)\b";

    D_BUSINESS = r"\b(?:in|after|within)\s+(<NUM>)\s+(?:(?:working|business|work)\s+days?|workdays?|weekdays?)\b";
    D_BUSINESS_SUFFIX = r"\b(<NUM>)\s+(?:(?:working|business|work)\s+days?|workdays?|weekdays?)\s+(?:from\s+(?:now|today)|later)\b";
    D_HALF_HOUR = r"\bin\s+(?:half\s+an?\s+hour|a\s+half\s+hour)\b";
    D_HOUR_HALF = r"\bin\s+(?:an?|one)\s+hour\s+and\s+a\s+half\b";
    D_REL = r"\b(?:in|after|within)\s+(<NUM>)\s+(<UNIT>)\b";
    D_REL_SUFFIX = r"\b(<NUM>)\s+(<UNIT>)\s+(?:from\s+(?:now|today)|later|hence)\b";

    W_NEXT_WEEK_DAY = r"\bnext\s+week\s+(?:on\s+)?(<WD>)\b|\b(?:on\s+)?(<WD>)\s+(?:of\s+)?next\s+week\b";
    N_DAY_AFTER = r"\b(?:the\s+)?day\s+after\s+(?:tomorrow|tmrw?)\b";
    N_TODAY = r"\b(?:today|tod|tdy)\b";
    N_TOMORROW = r"\b(?:tomorrow|tmrw|tmr|tmw|tomorow|tommorow|tommorrow)\b";
    N_NEXT_WEEKEND = r"\bnext\s+weekend\b";
    N_WEEKEND = r"\b(?:(?:this|the|on\s+the)\s+)?weekend\b";
    N_EOW = r"\b(?:by\s+)?(?:end\s+of\s+(?:the\s+)?week|eow)\b";
    N_END_NEXT_MONTH = r"\bend\s+of\s+next\s+month\b";
    N_EOM = r"\b(?:by\s+)?(?:end\s+of\s+(?:the\s+)?month|eom)\b";
    N_EOY = r"\b(?:by\s+)?(?:end\s+of\s+(?:the\s+)?year|eoy)\b";
    N_NEXT_WEEK = r"\b(?:(start|beginning)\s+of\s+)?next\s+week\b";
    N_NEXT_MONTH = r"\b(?:(start|beginning)\s+of\s+)?next\s+month\b";
    N_NEXT_YEAR = r"\b(?:(start|beginning)\s+of\s+)?next\s+year\b";
    N_MID_MONTH = r"\b(?:mid|middle\s+of)(?:\s+the)?[\s-]+month\b";

    W_NEXT = r"\bnext\s+(<WD>)\b";
    W_THIS = r"\b(?:this\s+coming|this|coming|on|by|until|till|before)\s+(<WD>)\b";
    W_BARE = r"\b(<WDB>)\b";

    A_ISO = r"\b(\d{4})-(\d{1,2})-(\d{1,2})\b";
    A_DM = r"\b(?:on\s+)?(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?(<MONTH>)\b\.?(?:,?\s+(\d{4})\b)?";
    A_MD = r"\b(?:on\s+)?(<MONTH>)\.?\s+(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)?\b(?:,?\s+(\d{4})\b)?";
    A_NUM = r"\b(\d{1,2})[/.](\d{1,2})(?:[/.](\d{4}|\d{2}))?\b";
    A_ON_THE = r"\bon\s+the\s+(\d{1,2})(?:st|nd|rd|th)?\b";
    A_ORD = r"\b(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)\b";
    A_IN_MONTH = r"\b(?:in|during|by)\s+(<MONTH>)\b";

    TRAIL = r"(?:^|\s)(?:on|at|by|due|in|from|starting|until|till|before)$|[\s,;:\-–]+$";
    LEAD = r"^(?:due|on|at)\s+|^[\s,;:\-–]+";
}

/// Working text: matches are blanked out with spaces so offsets stay valid.
struct Text(String);

impl Text {
    fn take<T>(&mut self, re: &Regex, mut f: impl FnMut(&Captures) -> Option<T>) -> Option<T> {
        let mut hit = None;
        for c in re.captures_iter(&self.0) {
            if let Some(v) = f(&c) {
                let m = c.get(0).unwrap();
                hit = Some((m.start(), m.end(), v));
                break;
            }
        }
        let (s, e, v) = hit?;
        self.0.replace_range(s..e, &" ".repeat(e - s));
        Some(v)
    }
}

fn lc(c: &Captures, i: usize) -> Option<String> {
    c.get(i).map(|m| m.as_str().to_lowercase())
}

fn int(c: &Captures, i: usize) -> Option<u32> {
    c.get(i)?.as_str().parse().ok()
}

fn hm(h: u32, m: u32) -> Option<NaiveTime> {
    NaiveTime::from_hms_opt(h, m, 0)
}

fn num(s: &str) -> Option<i64> {
    let s = s.to_lowercase().split_whitespace().collect::<Vec<_>>().join(" ");
    if let Ok(n) = s.parse() {
        return Some(n);
    }
    Some(match s.as_str() {
        "a" | "an" | "one" => 1,
        "two" => 2,
        "three" => 3,
        "four" => 4,
        "five" => 5,
        "six" => 6,
        "seven" => 7,
        "eight" => 8,
        "nine" => 9,
        "ten" => 10,
        "eleven" => 11,
        "twelve" => 12,
        "couple" | "a couple" | "couple of" | "a couple of" => 2,
        "few" | "a few" => 3,
        _ => return None,
    })
}

fn weekday_num(s: &str) -> Option<u32> {
    let s = s.to_lowercase();
    Some(match s.get(..2)? {
        "mo" => 1,
        "tu" => 2,
        "we" => 3,
        "th" => 4,
        "fr" => 5,
        "sa" => 6,
        "su" => 7,
        _ => return None,
    })
}

fn month_num(s: &str) -> Option<u32> {
    let s = s.to_lowercase();
    let i = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"].iter().position(|m| s.starts_with(m))?;
    Some(i as u32 + 1)
}

enum Span {
    Minutes(i64),
    Days(i64),
    Months(i64),
}

fn unit_span(u: &str, n: i64) -> Option<Span> {
    let u = u.to_lowercase();
    Some(if u == "m" || u.starts_with("mi") {
        Span::Minutes(n)
    } else if u.starts_with('h') {
        Span::Minutes(n * 60)
    } else if u.starts_with('d') {
        Span::Days(n)
    } else if u.starts_with('w') {
        Span::Days(n * 7)
    } else if u.starts_with('f') {
        Span::Days(n * 14)
    } else if u.starts_with("mo") {
        Span::Months(n)
    } else if u.starts_with('y') {
        Span::Months(n * 12)
    } else {
        return None;
    })
}

fn reminder_minutes(u: &str) -> i64 {
    let u = u.to_lowercase();
    if u.starts_with('h') {
        60
    } else if u.starts_with('d') {
        1440
    } else if u.starts_with('w') {
        10080
    } else {
        1
    }
}

fn start_of_week(d: NaiveDate, week_start: u32) -> NaiveDate {
    d - Duration::days(((weekday(d) + 7 - week_start) % 7) as i64)
}

/// Weekday `wd` in the week after the current one.
fn in_next_week(today: NaiveDate, wd: u32, week_start: u32) -> NaiveDate {
    start_of_week(today, week_start) + Duration::days(7 + ((wd + 7 - week_start) % 7) as i64)
}

/// Next `wd`, today included.
fn upcoming(today: NaiveDate, wd: u32) -> NaiveDate {
    today + Duration::days(((wd + 7 - weekday(today)) % 7) as i64)
}

/// Month/day without a year: this year, or next year if already past.
fn upcoming_md(today: NaiveDate, m: u32, d: u32, year: Option<i32>) -> Option<NaiveDate> {
    if let Some(y) = year {
        return NaiveDate::from_ymd_opt(y, m, d);
    }
    (0..8).filter_map(|i| NaiveDate::from_ymd_opt(today.year() + i, m, d)).find(|x| *x >= today)
}

/// Next date with day-of-month `d`, today included.
fn upcoming_day_of_month(today: NaiveDate, d: u32) -> Option<NaiveDate> {
    if !(1..=31).contains(&d) {
        return None;
    }
    (0..13).map(|i| add_months(today, i, 1)).filter(|first| d <= days_in_month(first.year(), first.month())).map(|first| ymd_clamped(first.year(), first.month(), d)).find(|x| *x >= today)
}

fn year_of(c: &Captures, i: usize) -> Option<Option<i32>> {
    match c.get(i) {
        None => Some(None),
        Some(m) => {
            let y: i32 = m.as_str().parse().ok()?;
            Some(Some(if y < 100 { 2000 + y } else { y }))
        }
    }
}

pub fn parse(input: &str, now: NaiveDateTime, cfg: &NlpConfig) -> Parsed {
    let today = now.date();
    let mut t = Text(input.to_string());
    let mut p = Parsed { group: t.take(&GROUP, |c| Some(c[1].to_string())), ..Default::default() };

    while let Some(l) = t.take(&LABEL, |c| Some(c[1].to_string())) {
        p.labels.push(l);
    }
    p.priority = t.take(&PRIORITY, |c| c[1].parse().ok()).or_else(|| {
        t.take(&PRIORITY_WORD, |c| match lc(c, 1)?.as_str() {
            "high" => Some(1),
            "medium" | "med" => Some(2),
            "low" => Some(3),
            "none" => Some(4),
            n => n.parse().ok(),
        })
    });

    loop {
        let hit = t
            .take(&REMIND, |c| {
                let mult = reminder_minutes(&c[3]);
                let n = if lc(c, 2)?.starts_with("half") { mult / 2 } else { num(&c[2])? * mult };
                Some((n, lc(c, 1)?.starts_with("alarm")))
            })
            .or_else(|| t.take(&REMIND_SHORT, |c| Some((c[1].parse::<i64>().ok()? * reminder_minutes(&c[2]), false))));
        match hit {
            Some((m, alarm)) => {
                if !p.reminders.contains(&m) {
                    p.reminders.push(m);
                }
                p.urgent |= alarm;
            }
            None => break,
        }
    }
    if t.take(&URGENT, |_| Some(())).is_some() {
        p.urgent = true;
    }

    let mut rec_time = None;
    p.recurrence = parse_recurrence(&mut t, cfg, &mut rec_time);

    // Time of day.
    let mut today_hint = false;
    let part = |s: &str| -> Option<NaiveTime> {
        hm(
            match s {
                "morning" => cfg.morning_hour,
                "afternoon" => cfg.afternoon_hour,
                "evening" => cfg.evening_hour,
                _ => cfg.night_hour,
            },
            0,
        )
    };
    let mut time = t.take(&T_AMPM, |c| {
        let h = int(c, 1)?;
        let m = if c.get(2).is_some() { int(c, 2)? } else { 0 };
        if h == 0 || h > 12 || m > 59 {
            return None;
        }
        let pm = lc(c, 3)?.starts_with('p');
        hm(if pm { h % 12 + 12 } else { h % 12 }, m)
    });
    if time.is_none() {
        time = t.take(&T_24, |c| hm(int(c, 1)?, int(c, 2)?));
    }
    if time.is_none() {
        // "at 5" means 17:00; 7 and up are taken literally.
        time = t.take(&T_AT, |c| {
            let h = int(c, 1)?;
            hm(if (1..=6).contains(&h) { h + 12 } else { h }, 0).filter(|_| h <= 23)
        });
    }
    if time.is_none() {
        time = t.take(&T_NOON, |c| if lc(c, 1)? == "midnight" { hm(23, 59) } else { hm(12, 0) });
    }
    if time.is_none() {
        time = t.take(&T_TONIGHT, |_| hm(cfg.night_hour, 0));
        today_hint = time.is_some();
    }
    if time.is_none() {
        time = t.take(&T_THIS_PART, |c| part(&lc(c, 1)?));
        today_hint = time.is_some();
    }
    if time.is_none() {
        time = t.take(&T_PART, |c| part(&lc(c, 1).or_else(|| lc(c, 2))?));
    }
    if time.is_none() {
        time = t.take(&T_EOD, |_| hm(cfg.end_of_day_hour, 0));
    }

    // Relative offsets.
    let mut date = t.take(&D_BUSINESS, |c| num(&c[1])).or_else(|| t.take(&D_BUSINESS_SUFFIX, |c| num(&c[1]))).map(|n| add_business_days(today, n));
    if date.is_none() {
        if let Some(m) = t.take(&D_HALF_HOUR, |_| Some(30)).or_else(|| t.take(&D_HOUR_HALF, |_| Some(90))) {
            let dt = now + Duration::minutes(m);
            date = Some(dt.date());
            time = time.or(hm(dt.hour(), dt.minute()));
        }
    }
    if date.is_none() {
        let rel = |c: &Captures| unit_span(&c[2], num(&c[1])?);
        if let Some(span) = t.take(&D_REL, rel).or_else(|| t.take(&D_REL_SUFFIX, rel)) {
            match span {
                Span::Minutes(m) => {
                    let dt = now + Duration::minutes(m);
                    date = Some(dt.date());
                    time = time.or(hm(dt.hour(), dt.minute()));
                }
                Span::Days(n) => date = Some(today + Duration::days(n)),
                Span::Months(n) => date = Some(add_months(today, n, today.day())),
            }
        }
    }

    if date.is_none() {
        date = named_date(&mut t, today, cfg);
    }
    if date.is_none() {
        date = t
            .take(&W_NEXT, |c| Some(in_next_week(today, weekday_num(&c[1])?, cfg.week_start)))
            .or_else(|| t.take(&W_THIS, |c| Some(upcoming(today, weekday_num(&c[1])?))))
            .or_else(|| t.take(&W_BARE, |c| Some(upcoming(today, weekday_num(&c[1])?))));
    }
    if date.is_none() {
        date = absolute_date(&mut t, today, cfg);
    }

    if date.is_none() && today_hint {
        date = Some(today);
    }
    if let Some(r) = &p.recurrence {
        if time.is_none() {
            time = rec_time;
        }
        if date.is_none() {
            let mut d = first_on_or_after(r, today);
            if d == today && time.is_some_and(|tm| tm < now.time()) {
                d = next_after(r, d);
            }
            date = Some(d);
        }
    }
    if date.is_none() {
        if let Some(tm) = time {
            date = Some(if tm > now.time() { today } else { today + Duration::days(1) });
        }
    }

    p.date = date;
    p.time = time;
    p.title = clean_title(&t.0);
    p
}

fn parse_recurrence(t: &mut Text, cfg: &NlpConfig, time: &mut Option<NaiveTime>) -> Option<Recurrence> {
    let freq_of = |s: &str| -> Option<Freq> {
        Some(match s.to_lowercase().chars().next()? {
            'd' => Freq::Daily,
            'w' => Freq::Weekly,
            'm' => Freq::Monthly,
            'y' => Freq::Yearly,
            _ => return None,
        })
    };
    let mk = |freq, interval| Recurrence { freq, interval, ..Default::default() };

    if let Some(r) = t.take(&R_OTHER, |c| Some(mk(freq_of(&c[1])?, 2))) {
        return Some(r);
    }
    if let Some(r) = t.take(&R_EVERY_N, |c| Some(mk(freq_of(&c[2])?, int(c, 1).filter(|n| *n > 0)?))) {
        return Some(r);
    }
    if let Some(r) = t.take(&R_WORKDAYS, |_| Some(Recurrence { workdays: true, ..Default::default() })) {
        return Some(r);
    }
    if let Some(r) = t.take(&R_WEEKEND, |_| Some(Recurrence { freq: Freq::Weekly, weekdays: vec![6, 7], ..Default::default() })) {
        return Some(r);
    }
    static ONE_WD: LazyLock<Regex> = LazyLock::new(|| re(r"\b(<WD>)\b"));
    if let Some(r) = t.take(&R_WEEKDAYS, |c| {
        let mut days: Vec<u32> = ONE_WD.captures_iter(&c[1]).filter_map(|w| weekday_num(&w[1])).collect();
        days.sort();
        days.dedup();
        Some(Recurrence { freq: Freq::Weekly, weekdays: days, ..Default::default() })
    }) {
        return Some(r);
    }
    if let Some(r) = t.take(&R_MONTH_LAST, |_| Some(Recurrence { freq: Freq::Monthly, month_day: Some(31), ..Default::default() })) {
        return Some(r);
    }
    if let Some(r) = t.take(&R_EVERY_UNIT, |c| {
        let u = lc(c, 1)?;
        let h = match u.as_str() {
            "morning" => Some(cfg.morning_hour),
            "afternoon" => Some(cfg.afternoon_hour),
            "evening" => Some(cfg.evening_hour),
            "night" => Some(cfg.night_hour),
            _ => None,
        };
        match h {
            Some(h) => {
                *time = hm(h, 0);
                Some(mk(Freq::Daily, 1))
            }
            None => Some(mk(freq_of(&u)?, 1)),
        }
    }) {
        return Some(r);
    }
    if let Some(r) = t.take(&R_ADVERB, |c| {
        Some(match lc(c, 1)?.as_str() {
            "daily" => mk(Freq::Daily, 1),
            "nightly" => {
                *time = hm(cfg.night_hour, 0);
                mk(Freq::Daily, 1)
            }
            "weekly" => mk(Freq::Weekly, 1),
            "fortnightly" | "biweekly" => mk(Freq::Weekly, 2),
            "monthly" => mk(Freq::Monthly, 1),
            _ => mk(Freq::Yearly, 1),
        })
    }) {
        return Some(r);
    }
    let yearly = |m: u32, d: u32| (1..=31).contains(&d).then(|| Recurrence { freq: Freq::Yearly, month: Some(m), month_day: Some(d), ..Default::default() });
    if let Some(r) = t.take(&R_YEARLY_MD, |c| yearly(month_num(&c[1])?, int(c, 2)?)).or_else(|| t.take(&R_YEARLY_DM, |c| yearly(month_num(&c[2])?, int(c, 1)?))) {
        return Some(r);
    }
    t.take(&R_MONTHDAY, |c| {
        let d = int(c, 1).filter(|d| (1..=31).contains(d))?;
        Some(Recurrence { freq: Freq::Monthly, month_day: Some(d), ..Default::default() })
    })
}

fn named_date(t: &mut Text, today: NaiveDate, cfg: &NlpConfig) -> Option<NaiveDate> {
    let ws = cfg.week_start;
    let is_start = |c: &Captures| c.get(1).is_some();
    let next_month_first = add_months(today, 1, 1);
    t.take(&W_NEXT_WEEK_DAY, |c| Some(in_next_week(today, weekday_num(c.get(1).or(c.get(2))?.as_str())?, ws)))
        .or_else(|| t.take(&N_DAY_AFTER, |_| Some(today + Duration::days(2))))
        .or_else(|| t.take(&N_TODAY, |_| Some(today)))
        .or_else(|| t.take(&N_TOMORROW, |_| Some(today + Duration::days(1))))
        .or_else(|| t.take(&N_NEXT_WEEKEND, |_| Some(in_next_week(today, 6, ws))))
        .or_else(|| t.take(&N_WEEKEND, |_| Some(if weekday(today) == 7 { today } else { upcoming(today, 6) })))
        .or_else(|| t.take(&N_EOW, |_| Some(upcoming(today, 5))))
        .or_else(|| t.take(&N_END_NEXT_MONTH, |_| Some(ymd_clamped(next_month_first.year(), next_month_first.month(), 31))))
        .or_else(|| t.take(&N_EOM, |_| Some(ymd_clamped(today.year(), today.month(), 31))))
        .or_else(|| t.take(&N_EOY, |_| NaiveDate::from_ymd_opt(today.year(), 12, 31)))
        .or_else(|| t.take(&N_NEXT_WEEK, |c| Some(in_next_week(today, if is_start(c) { ws } else { cfg.next_week_day }, ws))))
        .or_else(|| t.take(&N_NEXT_MONTH, |c| Some(if is_start(c) || !cfg.next_period_same_day { next_month_first } else { add_months(today, 1, today.day()) })))
        .or_else(|| {
            t.take(&N_NEXT_YEAR, |c| {
                if is_start(c) || !cfg.next_period_same_day {
                    NaiveDate::from_ymd_opt(today.year() + 1, 1, 1)
                } else {
                    Some(add_months(today, 12, today.day()))
                }
            })
        })
        .or_else(|| t.take(&N_MID_MONTH, |_| upcoming_day_of_month(today, 15)))
}

fn absolute_date(t: &mut Text, today: NaiveDate, cfg: &NlpConfig) -> Option<NaiveDate> {
    t.take(&A_ISO, |c| NaiveDate::from_ymd_opt(c[1].parse().ok()?, int(c, 2)?, int(c, 3)?))
        .or_else(|| t.take(&A_DM, |c| upcoming_md(today, month_num(&c[2])?, int(c, 1)?, year_of(c, 3)?)))
        .or_else(|| t.take(&A_MD, |c| upcoming_md(today, month_num(&c[1])?, int(c, 2)?, year_of(c, 3)?)))
        .or_else(|| {
            t.take(&A_NUM, |c| {
                let (a, b) = (int(c, 1)?, int(c, 2)?);
                let (d, m) = if cfg.month_first { (b, a) } else { (a, b) };
                if !(1..=12).contains(&m) {
                    return None;
                }
                upcoming_md(today, m, d, year_of(c, 3)?)
            })
        })
        .or_else(|| t.take(&A_ON_THE, |c| upcoming_day_of_month(today, int(c, 1)?)))
        .or_else(|| t.take(&A_ORD, |c| upcoming_day_of_month(today, int(c, 1)?)))
        .or_else(|| {
            t.take(&A_IN_MONTH, |c| {
                let m = month_num(&c[1])?;
                Some(match m.cmp(&today.month()) {
                    std::cmp::Ordering::Equal => today,
                    std::cmp::Ordering::Greater => NaiveDate::from_ymd_opt(today.year(), m, 1)?,
                    std::cmp::Ordering::Less => NaiveDate::from_ymd_opt(today.year() + 1, m, 1)?,
                })
            })
        })
}

fn clean_title(s: &str) -> String {
    let mut s = s.split_whitespace().collect::<Vec<_>>().join(" ");
    loop {
        let n = LEAD.replace(&TRAIL.replace(&s, ""), "").trim().to_string();
        if n == s {
            return s;
        }
        s = n;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    #[test]
    fn shared_cases() {
        let spec: Value = serde_json::from_str(include_str!("../../spec/nlp_cases.json")).unwrap();
        let now = NaiveDateTime::parse_from_str(spec["now"].as_str().unwrap(), "%Y-%m-%dT%H:%M:%S").unwrap();
        let mut failures = vec![];
        for case in spec["cases"].as_array().unwrap() {
            let input = case["input"].as_str().unwrap();
            let mut cfg_json = serde_json::to_value(NlpConfig::default()).unwrap();
            if let Some(o) = case.get("config").and_then(|c| c.as_object()) {
                for (k, v) in o {
                    cfg_json[k] = v.clone();
                }
            }
            let cfg: NlpConfig = serde_json::from_value(cfg_json).unwrap();
            let p = parse(input, now, &cfg);
            let mut check = |field: &str, got: Value| {
                let want = case.get(field).cloned().unwrap_or(Value::Null);
                if got != want {
                    failures.push(format!("{input:?}: {field} = {got}, want {want}"));
                }
            };
            check("title", Value::from(p.title.clone()));
            check("date", p.date.map(|d| Value::from(d.to_string())).unwrap_or(Value::Null));
            check("time", p.time.map(|t| Value::from(t.format("%H:%M").to_string())).unwrap_or(Value::Null));
            if case.get("rec").is_some() {
                let want = case["rec"].clone();
                let got = serde_json::to_value(p.recurrence.clone()).unwrap();
                let ok = want.as_object().unwrap().iter().all(|(k, v)| got.get(k) == Some(v));
                check("rec", if ok { want } else { got });
            }
            if case.get("priority").is_some() {
                check("priority", p.priority.map(Value::from).unwrap_or(Value::Null));
            }
            if case.get("group").is_some() {
                check("group", p.group.clone().map(Value::from).unwrap_or(Value::Null));
            }
            if case.get("labels").is_some() {
                check("labels", Value::from(p.labels.clone()));
            }
            if case.get("reminders").is_some() {
                check("reminders", Value::from(p.reminders.clone()));
            }
            if case.get("urgent").is_some() {
                check("urgent", Value::from(p.urgent));
            }
        }
        assert!(failures.is_empty(), "{} failures:\n{}", failures.len(), failures.join("\n"));
    }
}
