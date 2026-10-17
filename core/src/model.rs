use chrono::{NaiveDate, NaiveDateTime, NaiveTime};
use serde::{Deserialize, Serialize};

use crate::now_millis;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Freq {
    #[default]
    Daily,
    Weekly,
    Monthly,
    Yearly,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Recurrence {
    pub freq: Freq,
    pub interval: u32,
    /// 1 = Monday … 7 = Sunday (weekly only).
    pub weekdays: Vec<u32>,
    /// Monday–Friday only (daily only).
    pub workdays: bool,
    pub month_day: Option<u32>,
    pub month: Option<u32>,
    /// Last date an occurrence may fall on (`YYYY-MM-DD`), inclusive.
    pub until: Option<String>,
    /// Total number of occurrences, counting the first.
    pub count: Option<u32>,
}

impl Default for Recurrence {
    fn default() -> Self {
        Self { freq: Freq::Daily, interval: 1, weekdays: vec![], workdays: false, month_day: None, month: None, until: None, count: None }
    }
}

impl Recurrence {
    pub fn until_date(&self) -> Option<NaiveDate> {
        self.until.as_deref().and_then(|d| NaiveDate::parse_from_str(d, DATE_FMT).ok())
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AttachmentKind {
    Image,
    Audio,
    File,
    Text,
}

impl AttachmentKind {
    pub fn from_path(path: &std::path::Path) -> Self {
        let ext = path.extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();
        match ext.as_str() {
            "png" | "jpg" | "jpeg" | "gif" | "webp" | "bmp" | "heic" => Self::Image,
            "wav" | "mp3" | "m4a" | "ogg" | "opus" | "flac" | "aac" | "3gp" => Self::Audio,
            _ => Self::File,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Attachment {
    pub id: String,
    pub kind: AttachmentKind,
    pub name: String,
    /// Relative to the data dir, `/`-separated.
    #[serde(default)]
    pub path: Option<String>,
    #[serde(default)]
    pub text: Option<String>,
    #[serde(default)]
    pub created: i64,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Task {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub notes: String,
    #[serde(default)]
    pub group_id: Option<String>,
    #[serde(default)]
    pub parent_id: Option<String>,
    #[serde(default)]
    pub due_date: Option<String>,
    #[serde(default)]
    pub due_time: Option<String>,
    /// Hard deadline, separate from the planned due date; reminders escalate as it nears.
    #[serde(default)]
    pub deadline_date: Option<String>,
    #[serde(default)]
    pub deadline_time: Option<String>,
    #[serde(default)]
    pub recurrence: Option<Recurrence>,
    #[serde(default = "default_priority")]
    pub priority: u8,
    #[serde(default)]
    pub labels: Vec<String>,
    #[serde(default)]
    pub urgent: bool,
    #[serde(default)]
    pub reminders: Vec<i64>,
    #[serde(default)]
    pub pinned: bool,
    #[serde(default)]
    pub completed: bool,
    #[serde(default)]
    pub completed_at: Option<i64>,
    #[serde(default)]
    pub attachments: Vec<Attachment>,
    #[serde(default)]
    pub order: i64,
    #[serde(default)]
    pub created: i64,
    #[serde(default)]
    pub updated: i64,
    #[serde(default)]
    pub deleted: bool,
}

fn default_priority() -> u8 {
    4
}

pub const DATE_FMT: &str = "%Y-%m-%d";
pub const TIME_FMT: &str = "%H:%M";

impl Task {
    pub fn new(title: impl Into<String>) -> Self {
        let now = now_millis();
        Self {
            id: uuid::Uuid::new_v4().to_string(),
            title: title.into(),
            notes: String::new(),
            group_id: None,
            parent_id: None,
            due_date: None,
            due_time: None,
            deadline_date: None,
            deadline_time: None,
            recurrence: None,
            priority: 4,
            labels: vec![],
            urgent: false,
            reminders: vec![],
            pinned: false,
            completed: false,
            completed_at: None,
            attachments: vec![],
            order: now,
            created: now,
            updated: now,
            deleted: false,
        }
    }

    pub fn date(&self) -> Option<NaiveDate> {
        self.due_date.as_deref().and_then(|d| NaiveDate::parse_from_str(d, DATE_FMT).ok())
    }

    pub fn time(&self) -> Option<NaiveTime> {
        self.due_time.as_deref().and_then(|t| NaiveTime::parse_from_str(t, TIME_FMT).ok())
    }

    pub fn set_date(&mut self, d: Option<NaiveDate>) {
        self.due_date = d.map(|d| d.format(DATE_FMT).to_string());
        if d.is_none() {
            self.due_time = None;
        }
    }

    pub fn set_time(&mut self, t: Option<NaiveTime>) {
        self.due_time = t.map(|t| t.format(TIME_FMT).to_string());
    }

    /// Due moment; all-day tasks are due at the end of their day.
    pub fn due_at(&self) -> Option<NaiveDateTime> {
        let d = self.date()?;
        Some(match self.time() {
            Some(t) => d.and_time(t),
            None => d.and_hms_opt(23, 59, 59).unwrap(),
        })
    }

    pub fn deadline(&self) -> Option<NaiveDate> {
        self.deadline_date.as_deref().and_then(|d| NaiveDate::parse_from_str(d, DATE_FMT).ok())
    }

    pub fn deadline_clock(&self) -> Option<NaiveTime> {
        self.deadline_time.as_deref().and_then(|t| NaiveTime::parse_from_str(t, TIME_FMT).ok())
    }

    pub fn set_deadline(&mut self, d: Option<NaiveDate>, t: Option<NaiveTime>) {
        self.deadline_date = d.map(|d| d.format(DATE_FMT).to_string());
        self.deadline_time = d.and(t).map(|t| t.format(TIME_FMT).to_string());
    }

    pub fn is_overdue(&self, now: NaiveDateTime) -> bool {
        !self.completed && self.due_at().is_some_and(|due| due < now)
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Group {
    pub id: String,
    pub name: String,
    /// Theme key (`red`, `blue`, …) or `#RRGGBB`.
    #[serde(default = "default_color")]
    pub color: String,
    #[serde(default)]
    pub order: i64,
    #[serde(default)]
    pub updated: i64,
    #[serde(default)]
    pub deleted: bool,
}

fn default_color() -> String {
    "blue".into()
}

pub const GROUP_COLORS: [&str; 7] = ["red", "orange", "yellow", "green", "aqua", "blue", "purple"];

impl Group {
    pub fn new(name: impl Into<String>, color: impl Into<String>) -> Self {
        let now = now_millis();
        Self { id: uuid::Uuid::new_v4().to_string(), name: name.into(), color: color.into(), order: now, updated: now, deleted: false }
    }
}
