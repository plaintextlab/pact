//! Per-device settings (not synced).

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::nlp::NlpConfig;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    /// Shown as the window title / launcher name.
    pub app_name: String,
    pub theme: String,
    pub dark: bool,
    pub nlp: NlpConfig,
    /// Offsets (minutes before due) offered in the reminder picker.
    pub reminder_presets: Vec<i64>,
    /// Applied to new timed tasks that don't name their own reminder.
    pub default_reminders: Vec<i64>,
    /// How long an urgent alarm rings before stopping by itself.
    pub alarm_ring_seconds: u32,
    pub snooze_minutes: u32,
    pub notifications: bool,
    /// The git repository used for sync; defaults to the platform data dir.
    /// It may be shared with other apps.
    pub data_dir: Option<PathBuf>,
    /// Pact's folder inside that repository ("" = repository root).
    pub git_folder: String,
    pub git_remote: String,
    pub git_branch: String,
    /// 0 disables periodic sync.
    pub auto_sync_minutes: u32,
    pub sync_on_change: bool,
    pub show_completed: bool,
    pub ui_scale: f32,
    /// 24-hour clock (`14:30`) instead of 12-hour (`2:30 PM`).
    pub clock_24h: bool,
    /// Last calendar view: "month", "week", "day" or "agenda".
    pub calendar_view: String,
    /// Length of new timed events.
    pub event_minutes: u32,
    /// Reminders (minutes before) for new events.
    pub event_reminders: Vec<i64>,
    /// Group used as the calendar for new events.
    pub default_calendar: Option<String>,
    pub calendar_show_tasks: bool,
    /// Escalating reminders for task deadlines.
    pub deadline_reminders: bool,
    /// Hours between deadline reminders on the last day.
    pub deadline_final_hours: u32,
}

impl Settings {
    /// Deadline reminders run during the quick-add "morning".."night" hours.
    pub fn deadline_config(&self) -> crate::deadline::DeadlineConfig {
        crate::deadline::DeadlineConfig { day_start: self.nlp.morning_hour, day_end: self.nlp.night_hour, final_hours: self.deadline_final_hours }
    }
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            app_name: "Pact".into(),
            theme: "gruvbox".into(),
            dark: true,
            nlp: NlpConfig::default(),
            reminder_presets: vec![5, 10, 15, 20, 30, 45, 60, 120, 1440],
            default_reminders: vec![],
            alarm_ring_seconds: 60,
            snooze_minutes: 10,
            notifications: true,
            data_dir: None,
            git_folder: "pact".into(),
            git_remote: String::new(),
            git_branch: "main".into(),
            auto_sync_minutes: 15,
            sync_on_change: true,
            show_completed: false,
            ui_scale: 1.0,
            clock_24h: true,
            calendar_view: "month".into(),
            event_minutes: 60,
            event_reminders: vec![10],
            default_calendar: None,
            calendar_show_tasks: true,
            deadline_reminders: true,
            deadline_final_hours: 1,
        }
    }
}

impl Settings {
    pub fn load(path: &Path) -> Self {
        std::fs::read_to_string(path).ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default()
    }

    pub fn save(&self, path: &Path) -> anyhow::Result<()> {
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir)?;
        }
        std::fs::write(path, serde_json::to_string_pretty(self)?)?;
        Ok(())
    }
}

/// "14:30" or "2:30 PM".
pub fn format_time(t: chrono::NaiveTime, clock_24h: bool) -> String {
    t.format(if clock_24h { "%H:%M" } else { "%-I:%M %p" }).to_string()
}

/// "15 min", "2 h", "1 day" — for reminder offsets.
pub fn format_offset(minutes: i64) -> String {
    match minutes {
        0 => "at due time".into(),
        m if m % 1440 == 0 => format!("{} day{}", m / 1440, if m == 1440 { "" } else { "s" }),
        m if m % 60 == 0 => format!("{} h", m / 60),
        m => format!("{m} min"),
    }
}
